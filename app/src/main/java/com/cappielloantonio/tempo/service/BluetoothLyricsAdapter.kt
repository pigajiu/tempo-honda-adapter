package com.cappielloantonio.tempo.service

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.glide.CustomGlideRequest
import com.cappielloantonio.tempo.subsonic.base.ApiResponse
import com.cappielloantonio.tempo.subsonic.models.Line
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.OpenSubsonicExtensionsUtil
import com.cappielloantonio.tempo.util.Preferences
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Publishes synchronized lyrics through the standard AVRCP title and artist attributes.
 *
 * This mirrors the behavior used by NetEase Cloud Music: TITLE contains the current lyric and
 * ARTIST contains "song - artist". DISPLAY_TITLE and DISPLAY_SUBTITLE remain unchanged so normal
 * MediaSession clients can continue showing the real song information.
 */
class BluetoothLyricsAdapter(
    context: Context,
    private val player: Player,
    private val sessionMetadataPublisher: ((MediaMetadata?) -> Unit)? = null
) : Player.Listener, SharedPreferences.OnSharedPreferenceChangeListener {
    private data class TimedLyric(val startMs: Long, val text: String)
    private data class EncodedArtwork(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val quality: Int
    )

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(appContext)
    private val lyricsCache = appContext.getSharedPreferences(LYRICS_CACHE_PREFS, Context.MODE_PRIVATE)
    private val lrcTimestamp = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

    private var sourceMediaItem: MediaItem? = null
    private var sessionMetadata: MediaMetadata? = null
    private var activeMediaId: String? = null
    private var timedLyrics: List<TimedLyric> = emptyList()
    private var displayedLyricIndex = -1
    private var artworkData: ByteArray? = null
    private var artworkWidth = 0
    private var artworkHeight = 0
    private var artworkQuality = 0
    private var artworkPublishedMediaId: String? = null
    private var requestGeneration = 0
    private var a2dpConnected = false
    private var artworkTarget: CustomTarget<Bitmap>? = null

    private val lyricTicker = object : Runnable {
        override fun run() {
            updateTimedLyric()
            scheduleNextLyricTick()
        }
    }

    private val a2dpWatcher = object : Runnable {
        override fun run() {
            updateA2dpState()
            if (player.isPlaying) {
                handler.postDelayed(this, A2DP_STATE_CHECK_MS)
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            updateA2dpState()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            updateA2dpState()
        }
    }

    fun start() {
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
        a2dpConnected = isA2dpConnected()
        player.addListener(this)
        handleMediaItem(player.currentMediaItem)
        if (player.isPlaying) startA2dpWatcher()
    }

    fun release() {
        requestGeneration++
        handler.removeCallbacksAndMessages(null)
        artworkTarget?.let { Glide.with(appContext).clear(it) }
        artworkTarget = null
        sessionMetadataPublisher?.invoke(null)
        player.removeListener(this)
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences, key: String?) {
        if (key != Preferences.BLUETOOTH_LYRICS &&
            key != Preferences.BLUETOOTH_LYRICS_LEAD_MS
        ) return

        displayedLyricIndex = -1
        if (shouldPublishLyrics()) {
            scheduleLyricUpdate()
        } else {
            handler.removeCallbacks(lyricTicker)
            publishMetadata(null)
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (mediaItem?.mediaMetadata?.extras?.getBoolean(EXTRA_MANAGED_METADATA) == true) return
        handleMediaItem(mediaItem)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            startA2dpWatcher()
            updateA2dpState()
            if (a2dpConnected) {
                republishBluetoothMetadata("playback-start")
                if (shouldPublishLyrics()) scheduleLyricUpdate()
            }
        } else {
            handler.removeCallbacks(lyricTicker)
            handler.removeCallbacks(a2dpWatcher)
        }
    }

    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
        val current = player.currentMediaItem ?: return
        if (activeMediaId != current.mediaId || artworkData != null) return

        val embeddedArtwork = mediaMetadata.artworkData ?: return
        val source = sourceMediaItem ?: current
        val mergedMetadata = source.mediaMetadata.buildUpon()
            .setArtworkData(embeddedArtwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()
        sourceMediaItem = source.buildUpon().setMediaMetadata(mergedMetadata).build()
        Log.d(TAG, "Using embedded artwork from local media metadata mediaId=${current.mediaId}")
        loadArtwork(sourceMediaItem!!)
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        displayedLyricIndex = -1
        if (shouldPublishLyrics()) scheduleLyricUpdate()
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        if (shouldPublishLyrics() && player.isPlaying) {
            scheduleLyricUpdate()
        }
    }

    private fun handleMediaItem(mediaItem: MediaItem?) {
        requestGeneration++
        handler.removeCallbacks(lyricTicker)
        artworkTarget?.let { Glide.with(appContext).clear(it) }
        artworkTarget = null
        sessionMetadataPublisher?.invoke(null)
        sourceMediaItem = mediaItem
        sessionMetadata = mediaItem?.mediaMetadata
        activeMediaId = mediaItem?.mediaId
        timedLyrics = emptyList()
        displayedLyricIndex = -1
        artworkData = null
        artworkWidth = 0
        artworkHeight = 0
        artworkQuality = 0
        artworkPublishedMediaId = null

        if (mediaItem == null ||
            mediaItem.mediaMetadata.extras?.getString("type") != Constants.MEDIA_TYPE_MUSIC
        ) return

        loadArtwork(mediaItem)
        loadLyrics(mediaItem, requestGeneration)
        if (shouldPublishLyrics()) scheduleLyricUpdate()
    }

    private fun startA2dpWatcher() {
        handler.removeCallbacks(a2dpWatcher)
        updateA2dpState()
        if (player.isPlaying) {
            handler.postDelayed(a2dpWatcher, A2DP_STATE_CHECK_MS)
        }
    }

    private fun updateA2dpState() {
        val connected = isA2dpConnected()
        if (connected == a2dpConnected) return

        a2dpConnected = connected
        displayedLyricIndex = -1
        Log.i(TAG, "A2DP state changed connected=$connected")

        if (connected) {
            republishBluetoothMetadata("a2dp-connected")
            if (shouldPublishLyrics() && player.isPlaying) {
                scheduleLyricUpdate()
            }
        } else {
            handler.removeCallbacks(lyricTicker)
            publishMetadata(null)
        }
    }

    private fun republishBluetoothMetadata(reason: String) {
        if (!a2dpConnected) return

        // Force the legacy MediaSessionCompat path to emit a fresh metadata event when
        // a receiver reconnects. AVRCP receivers can otherwise keep the empty metadata
        // they obtained during connection setup if the song/lyric text itself did not change.
        sessionMetadataPublisher?.invoke(null)

        val lyric = if (Preferences.isBluetoothLyricsEnabled() && timedLyrics.isNotEmpty()) {
            val lyricLeadMs = Preferences.getBluetoothLyricsLeadMs().toLong()
            val effectivePosition = (player.currentPosition + lyricLeadMs).coerceAtLeast(0L)
            displayedLyricIndex = timedLyrics.indexOfLast { it.startMs <= effectivePosition }
            currentLyric()
        } else {
            null
        }

        Log.i(
            TAG,
            "Force republish Bluetooth metadata reason=$reason connected=$a2dpConnected " +
                "positionMs=${player.currentPosition} lyric=$lyric artwork=${sessionMetadata?.artworkData != null}"
        )
        publishMetadata(lyric, force = true)
    }

    @Suppress("DEPRECATION")
    private fun isA2dpConnected(): Boolean =
        audioManager.isBluetoothA2dpOn ||
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }

    private fun shouldPublishLyrics(): Boolean =
        Preferences.isBluetoothLyricsEnabled() && a2dpConnected

    private fun loadArtwork(mediaItem: MediaItem) {
        val embeddedArtwork = mediaItem.mediaMetadata.artworkData
        val originalArtworkUri = mediaItem.mediaMetadata.artworkUri?.toString()
        val coverArtId = mediaItem.mediaMetadata.extras?.getString("coverArtId")
        val coverArtUrl = coverArtId
            ?.takeIf { it.isNotBlank() }
            ?.let { CustomGlideRequest.createUrl(it, ARTWORK_SIZE_PX) }

        val primaryArtwork: Any? = embeddedArtwork ?: coverArtUrl ?: originalArtworkUri
        if (primaryArtwork == null) {
            Log.w(TAG, "No artwork source for mediaId=${mediaItem.mediaId}")
            return
        }

        val fallbackArtwork: Any? = when {
            embeddedArtwork != null -> coverArtUrl ?: originalArtworkUri
            coverArtUrl != null && originalArtworkUri != coverArtUrl -> originalArtworkUri
            else -> null
        }
        val mediaId = mediaItem.mediaId
        val primaryDescription = if (embeddedArtwork != null) {
            "embedded:${embeddedArtwork.size}bytes"
        } else {
            primaryArtwork.toString()
        }
        Log.d(
            TAG,
            "Loading artwork mediaId=$mediaId primary=$primaryDescription fallback=$fallbackArtwork"
        )

        artworkTarget = object : CustomTarget<Bitmap>(ARTWORK_SIZE_PX, ARTWORK_SIZE_PX) {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                if (activeMediaId != mediaId) {
                    Log.d(TAG, "Ignoring stale artwork result for mediaId=$mediaId")
                    return
                }

                val encoded = encodeArtwork(resource)
                artworkData = encoded.data
                artworkWidth = encoded.width
                artworkHeight = encoded.height
                artworkQuality = encoded.quality
                Log.d(
                    TAG,
                    "Artwork ready mediaId=$mediaId bitmap=${encoded.width}x${encoded.height} " +
                        "jpegQuality=${encoded.quality} jpegBytes=${encoded.data.size} " +
                        "targetMaxBytes=$MAX_ARTWORK_JPEG_BYTES"
                )
                publishArtworkMetadata()
            }

            override fun onLoadFailed(errorDrawable: Drawable?) {
                if (activeMediaId == mediaId) {
                    Log.w(
                        TAG,
                        "Artwork load failed for mediaId=$mediaId primary=$primaryDescription " +
                            "fallback=$fallbackArtwork"
                    )
                }
            }

            override fun onLoadCleared(placeholder: Drawable?) = Unit
        }

        val requestManager = Glide.with(appContext)
        fun requestFor(model: Any) = requestManager
            .asBitmap()
            .load(model)
            .centerCrop()
            .override(ARTWORK_SIZE_PX, ARTWORK_SIZE_PX)

        val request = requestFor(primaryArtwork)
        if (fallbackArtwork != null) {
            request.error(requestFor(fallbackArtwork))
        }
        request.into(artworkTarget!!)
    }

    /**
     * Keep AVRCP/BIP artwork conservative for receivers with small image buffers.
     * Prefer 512x512 and reduce JPEG quality first; only reduce dimensions when the
     * encoded image still exceeds the target byte size.
     */
    private fun encodeArtwork(bitmap: Bitmap): EncodedArtwork {
        val side = minOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        val left = ((bitmap.width - side) / 2).coerceAtLeast(0)
        val top = ((bitmap.height - side) / 2).coerceAtLeast(0)
        val square = if (bitmap.width == side && bitmap.height == side) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, left, top, side, side)
        }

        var lastResult: EncodedArtwork? = null
        for (size in ARTWORK_SIZE_STEPS) {
            val scaled = if (square.width == size && square.height == size) {
                square
            } else {
                Bitmap.createScaledBitmap(square, size, size, true)
            }

            for (quality in ARTWORK_JPEG_QUALITIES) {
                val data = ByteArrayOutputStream().use { output ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
                    output.toByteArray()
                }
                val result = EncodedArtwork(data, scaled.width, scaled.height, quality)
                lastResult = result
                if (data.size <= MAX_ARTWORK_JPEG_BYTES) return result
            }
        }

        return checkNotNull(lastResult)
    }

    private fun publishArtworkMetadata() {
        val data = artworkData ?: return
        val source = sourceMediaItem ?: return
        val current = player.currentMediaItem ?: return
        val index = player.currentMediaItemIndex
        if (index < 0 || activeMediaId != current.mediaId) return
        if (artworkPublishedMediaId == source.mediaId) return

        val baseMetadata = sessionMetadata ?: current.mediaMetadata
        val extras = Bundle(baseMetadata.extras ?: source.mediaMetadata.extras ?: Bundle()).apply {
            putBoolean(EXTRA_MANAGED_METADATA, true)
        }
        val metadata = baseMetadata.buildUpon()
            .setArtworkData(data, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .setExtras(extras)
            .build()

        if (sessionMetadata != metadata) {
            dispatchSessionMetadata(metadata)
        }
        artworkPublishedMediaId = source.mediaId
        Log.d(
            TAG,
            "Artwork published once mediaId=${source.mediaId} bytes=${data.size} " +
                "bitmap=${artworkWidth}x$artworkHeight jpegQuality=$artworkQuality"
        )
    }

    private fun loadLyrics(mediaItem: MediaItem, generation: Int) {
        val cachedLyrics = readCachedLyrics(lyricsCache, mediaItem.mediaId)
        if (cachedLyrics.isNotEmpty()) {
            timedLyrics = cachedLyrics
            displayedLyricIndex = -1
            Log.d(TAG, "Loaded ${cachedLyrics.size} cached lyric lines for mediaId=${mediaItem.mediaId}")
            if (shouldPublishLyrics()) scheduleLyricUpdate()
        }

        if (OpenSubsonicExtensionsUtil.isSongLyricsExtensionAvailable()) {
            App.getSubsonicClientInstance(false)
                .openClient
                .getLyricsBySongId(mediaItem.mediaId)
                .enqueue(object : Callback<ApiResponse> {
                    override fun onResponse(call: Call<ApiResponse>, response: Response<ApiResponse>) {
                        if (generation != requestGeneration || !response.isSuccessful) return

                        val structuredLyrics = response.body()
                            ?.subsonicResponse
                            ?.lyricsList
                            ?.structuredLyrics
                            ?.firstOrNull { it.synced && !it.line.isNullOrEmpty() }

                        timedLyrics = structuredLyrics?.line
                            .orEmpty()
                            .mapNotNull(::mapStructuredLine)
                            .map {
                                it.copy(
                                    startMs = (it.startMs + (structuredLyrics?.offset ?: 0))
                                        .coerceAtLeast(0)
                                )
                            }
                            .sortedBy { it.startMs }
                        if (timedLyrics.isNotEmpty()) {
                            cacheLyrics(lyricsCache, mediaItem.mediaId, timedLyrics)
                        }
                        displayedLyricIndex = -1
                        if (shouldPublishLyrics()) scheduleLyricUpdate()
                    }

                    override fun onFailure(call: Call<ApiResponse>, throwable: Throwable) = Unit
                })
        } else {
            val title = mediaItem.mediaMetadata.title?.toString().orEmpty()
            val artist = mediaItem.mediaMetadata.artist?.toString().orEmpty()

            App.getSubsonicClientInstance(false)
                .mediaRetrievalClient
                .getLyrics(artist, title)
                .enqueue(object : Callback<ApiResponse> {
                    override fun onResponse(call: Call<ApiResponse>, response: Response<ApiResponse>) {
                        if (generation != requestGeneration || !response.isSuccessful) return

                        timedLyrics = parseLrc(response.body()?.subsonicResponse?.lyrics?.value)
                        if (timedLyrics.isNotEmpty()) {
                            cacheLyrics(lyricsCache, mediaItem.mediaId, timedLyrics)
                        }
                        displayedLyricIndex = -1
                        if (shouldPublishLyrics()) scheduleLyricUpdate()
                    }

                    override fun onFailure(call: Call<ApiResponse>, throwable: Throwable) = Unit
                })
        }
    }

    private fun mapStructuredLine(line: Line): TimedLyric? {
        val start = line.start ?: return null
        val text = line.value.trim()
        return if (text.isEmpty()) null else TimedLyric(start.toLong(), text)
    }

    private fun parseLrc(rawLyrics: String?): List<TimedLyric> {
        if (rawLyrics.isNullOrBlank()) return emptyList()

        return rawLyrics.lineSequence()
            .flatMap { row ->
                val text = row.replace(lrcTimestamp, "").trim()
                if (text.isEmpty()) return@flatMap emptySequence()

                lrcTimestamp.findAll(row).map { match ->
                    val minutes = match.groupValues[1].toLong()
                    val seconds = match.groupValues[2].toLong()
                    val fraction = match.groupValues[3]
                    val fractionMs = when (fraction.length) {
                        1 -> fraction.toLong() * 100
                        2 -> fraction.toLong() * 10
                        3 -> fraction.toLong()
                        else -> 0
                    }
                    TimedLyric((minutes * 60 + seconds) * 1000 + fractionMs, text)
                }
            }
            .sortedBy { it.startMs }
            .toList()
    }

    private fun scheduleLyricUpdate() {
        handler.removeCallbacks(lyricTicker)
        updateTimedLyric()
        scheduleNextLyricTick()
    }

    private fun scheduleNextLyricTick() {
        if (!shouldPublishLyrics() || !player.isPlaying || timedLyrics.isEmpty()) return

        val lyricLeadMs = Preferences.getBluetoothLyricsLeadMs().toLong()
        val effectivePosition = (player.currentPosition + lyricLeadMs).coerceAtLeast(0L)
        val nextStartMs = timedLyrics.firstOrNull { it.startMs > effectivePosition }?.startMs ?: return
        val speed = player.playbackParameters.speed.coerceAtLeast(0.1f)
        val untilNextMs = ((nextStartMs - effectivePosition) / speed).toLong()
        val delayMs = untilNextMs
            .coerceAtLeast(MIN_LYRIC_SCHEDULE_DELAY_MS)
            .coerceAtMost(MAX_LYRIC_SCHEDULE_DELAY_MS)
        handler.postDelayed(lyricTicker, delayMs)
    }

    private fun updateTimedLyric() {
        if (!shouldPublishLyrics() ||
            timedLyrics.isEmpty() ||
            activeMediaId != player.currentMediaItem?.mediaId
        ) return

        val playerPosition = player.currentPosition.coerceAtLeast(0L)
        val lyricLeadMs = Preferences.getBluetoothLyricsLeadMs().toLong()
        val effectivePosition = (playerPosition + lyricLeadMs).coerceAtLeast(0L)
        val newIndex = timedLyrics.indexOfLast { it.startMs <= effectivePosition }
        if (newIndex == displayedLyricIndex) return

        displayedLyricIndex = newIndex
        val lyric = currentLyric()
        val lyricStartMs = timedLyrics.getOrNull(newIndex)?.startMs
        Log.i(
            TAG,
            "Lyric publish mediaId=$activeMediaId positionMs=$playerPosition " +
                "lyricStartMs=$lyricStartMs leadMs=$lyricLeadMs text=$lyric"
        )
        publishMetadata(lyric)
    }

    private fun currentLyric(): String? = timedLyrics.getOrNull(displayedLyricIndex)?.text

    private fun publishMetadata(lyric: String?, force: Boolean = false) {
        val source = sourceMediaItem ?: return
        val current = player.currentMediaItem ?: return
        val index = player.currentMediaItemIndex
        if (index < 0 || activeMediaId != current.mediaId) return

        val base = source.mediaMetadata
        val publishLyric = shouldPublishLyrics() && !lyric.isNullOrBlank()
        val songTitle = base.displayTitle ?: base.title
        val songArtist = base.subtitle ?: base.artist
        val titleArtist = listOf(songTitle, songArtist)
            .map { it?.toString().orEmpty() }
            .filter { it.isNotBlank() }
            .joinToString(" - ")
        val currentSessionMetadata = sessionMetadata ?: current.mediaMetadata
        val extras = Bundle(currentSessionMetadata.extras ?: base.extras ?: Bundle()).apply {
            putBoolean(EXTRA_MANAGED_METADATA, true)
            if (publishLyric) putString(EXTRA_CURRENT_LYRIC, lyric) else remove(EXTRA_CURRENT_LYRIC)
        }

        // Match NetEase's strategy more closely: update only the MediaSession-facing metadata.
        // The ExoPlayer playlist/current MediaItem stays untouched, so lyric changes are emitted
        // as a lightweight MEDIA_METADATA_CHANGED event instead of replaceMediaItem().
        val metadata = currentSessionMetadata.buildUpon()
            .setTitle(if (publishLyric) lyric else base.title)
            .setArtist(if (publishLyric && titleArtist.isNotBlank()) titleArtist else base.artist)
            .setDisplayTitle(songTitle)
            .setSubtitle(songArtist)
            .setExtras(extras)
            .build()

        if (!force && sessionMetadata == metadata) return
        Log.i(
            TAG,
            "Publishing direct session metadata mediaId=${source.mediaId} lyric=$publishLyric " +
                "artworkStable=${metadata.artworkData != null}"
        )
        dispatchSessionMetadata(metadata)
    }

    private fun dispatchSessionMetadata(metadata: MediaMetadata) {
        sessionMetadata = metadata
        val publisher = sessionMetadataPublisher
        if (publisher != null) {
            publisher(metadata)
            return
        }

        // Compatibility fallback for flavors that still bind MediaSession directly to ExoPlayer.
        val source = sourceMediaItem ?: return
        val current = player.currentMediaItem ?: return
        val index = player.currentMediaItemIndex
        if (index < 0 || activeMediaId != current.mediaId) return
        player.replaceMediaItem(index, source.buildUpon().setMediaMetadata(metadata).build())
    }

    companion object {
        private const val EXTRA_MANAGED_METADATA = "com.cappielloantonio.tempo.bluetooth.managed_metadata"
        private const val EXTRA_CURRENT_LYRIC = "com.cappielloantonio.tempo.bluetooth.current_lyric"
        private const val LYRICS_CACHE_PREFS = "bluetooth_lyrics_cache"
        private const val TAG = "BluetoothLyricsAdapter"
        private const val ARTWORK_SIZE_PX = 512
        private const val MAX_ARTWORK_JPEG_BYTES = 40 * 1024
        private val ARTWORK_SIZE_STEPS = intArrayOf(512, 448, 384, 320, 256)
        private val ARTWORK_JPEG_QUALITIES = intArrayOf(90, 85, 80)
        private const val A2DP_STATE_CHECK_MS = 500L
        private const val MIN_LYRIC_SCHEDULE_DELAY_MS = 16L
        private const val MAX_LYRIC_SCHEDULE_DELAY_MS = 1000L
        private val cacheLrcTimestamp = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

        @JvmStatic
        fun clearCachedDownloadMetadata(context: Context, mediaId: String) {
            context.applicationContext
                .getSharedPreferences(LYRICS_CACHE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(mediaId)
                .apply()
        }

        @JvmStatic
        fun prefetchForDownload(context: Context, mediaItem: MediaItem) {
            val appContext = context.applicationContext
            val mediaId = mediaItem.mediaId
            val cache = appContext.getSharedPreferences(LYRICS_CACHE_PREFS, Context.MODE_PRIVATE)

            mediaItem.mediaMetadata.extras?.getString("coverArtId")
                ?.takeIf { it.isNotBlank() }
                ?.let { coverArtId ->
                    Glide.with(appContext)
                        .asBitmap()
                        .load(CustomGlideRequest.createUrl(coverArtId, ARTWORK_SIZE_PX))
                        .preload(ARTWORK_SIZE_PX, ARTWORK_SIZE_PX)
                }

            if (readCachedLyrics(cache, mediaId).isNotEmpty()) return

            if (OpenSubsonicExtensionsUtil.isSongLyricsExtensionAvailable()) {
                App.getSubsonicClientInstance(false)
                    .openClient
                    .getLyricsBySongId(mediaId)
                    .enqueue(object : Callback<ApiResponse> {
                        override fun onResponse(call: Call<ApiResponse>, response: Response<ApiResponse>) {
                            if (!response.isSuccessful) return
                            val structured = response.body()
                                ?.subsonicResponse
                                ?.lyricsList
                                ?.structuredLyrics
                                ?.firstOrNull { it.synced && !it.line.isNullOrEmpty() }
                                ?: return
                            val lyrics = structured.line
                                .orEmpty()
                                .mapNotNull { line ->
                                    val start = line.start ?: return@mapNotNull null
                                    val text = line.value.trim()
                                    if (text.isEmpty()) null else TimedLyric(
                                        (start.toLong() + structured.offset).coerceAtLeast(0),
                                        text
                                    )
                                }
                                .sortedBy { it.startMs }
                            if (lyrics.isNotEmpty()) cacheLyrics(cache, mediaId, lyrics)
                        }

                        override fun onFailure(call: Call<ApiResponse>, throwable: Throwable) = Unit
                    })
            } else {
                val title = mediaItem.mediaMetadata.title?.toString().orEmpty()
                val artist = mediaItem.mediaMetadata.artist?.toString().orEmpty()
                App.getSubsonicClientInstance(false)
                    .mediaRetrievalClient
                    .getLyrics(artist, title)
                    .enqueue(object : Callback<ApiResponse> {
                        override fun onResponse(call: Call<ApiResponse>, response: Response<ApiResponse>) {
                            if (!response.isSuccessful) return
                            val lyrics = parseCachedLrc(response.body()?.subsonicResponse?.lyrics?.value)
                            if (lyrics.isNotEmpty()) cacheLyrics(cache, mediaId, lyrics)
                        }

                        override fun onFailure(call: Call<ApiResponse>, throwable: Throwable) = Unit
                    })
            }
        }

        private fun readCachedLyrics(
            preferences: SharedPreferences,
            mediaId: String
        ): List<TimedLyric> {
            val raw = preferences.getString(mediaId, null) ?: return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        add(TimedLyric(item.getLong("startMs"), item.getString("text")))
                    }
                }.sortedBy { it.startMs }
            }.getOrDefault(emptyList())
        }

        private fun cacheLyrics(
            preferences: SharedPreferences,
            mediaId: String,
            lyrics: List<TimedLyric>
        ) {
            val array = JSONArray()
            lyrics.forEach { lyric ->
                array.put(
                    JSONObject()
                        .put("startMs", lyric.startMs)
                        .put("text", lyric.text)
                )
            }
            preferences.edit().putString(mediaId, array.toString()).apply()
        }

        private fun parseCachedLrc(rawLyrics: String?): List<TimedLyric> {
            if (rawLyrics.isNullOrBlank()) return emptyList()
            return rawLyrics.lineSequence()
                .flatMap { row ->
                    val text = row.replace(cacheLrcTimestamp, "").trim()
                    if (text.isEmpty()) return@flatMap emptySequence()
                    cacheLrcTimestamp.findAll(row).map { match ->
                        val minutes = match.groupValues[1].toLong()
                        val seconds = match.groupValues[2].toLong()
                        val fraction = match.groupValues[3]
                        val fractionMs = when (fraction.length) {
                            1 -> fraction.toLong() * 100
                            2 -> fraction.toLong() * 10
                            3 -> fraction.toLong()
                            else -> 0
                        }
                        TimedLyric((minutes * 60 + seconds) * 1000 + fractionMs, text)
                    }
                }
                .sortedBy { it.startMs }
                .toList()
        }
    }
}
