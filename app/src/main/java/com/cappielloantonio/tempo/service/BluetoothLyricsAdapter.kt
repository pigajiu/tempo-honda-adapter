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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.subsonic.base.ApiResponse
import com.cappielloantonio.tempo.subsonic.models.Line
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.OpenSubsonicExtensionsUtil
import com.cappielloantonio.tempo.util.Preferences
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
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
    private val player: Player
) : Player.Listener, SharedPreferences.OnSharedPreferenceChangeListener {
    private data class TimedLyric(val startMs: Long, val text: String)

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(appContext)
    private val lrcTimestamp = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

    private var sourceMediaItem: MediaItem? = null
    private var activeMediaId: String? = null
    private var timedLyrics: List<TimedLyric> = emptyList()
    private var displayedLyricIndex = -1
    private var artworkData: ByteArray? = null
    private var requestGeneration = 0
    private var a2dpConnected = false
    private var artworkTarget: CustomTarget<Bitmap>? = null

    private val lyricTicker = object : Runnable {
        override fun run() {
            updateTimedLyric()
            if (shouldPublishLyrics() && player.isPlaying) {
                handler.postDelayed(this, LYRIC_POLL_INTERVAL_MS)
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
    }

    fun release() {
        requestGeneration++
        handler.removeCallbacksAndMessages(null)
        artworkTarget?.let { Glide.with(appContext).clear(it) }
        artworkTarget = null
        player.removeListener(this)
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences, key: String?) {
        if (key != Preferences.BLUETOOTH_LYRICS) return

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
        if (isPlaying && shouldPublishLyrics()) {
            scheduleLyricUpdate()
        } else {
            handler.removeCallbacks(lyricTicker)
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        displayedLyricIndex = -1
        if (shouldPublishLyrics()) updateTimedLyric()
    }

    private fun handleMediaItem(mediaItem: MediaItem?) {
        requestGeneration++
        handler.removeCallbacks(lyricTicker)
        artworkTarget?.let { Glide.with(appContext).clear(it) }
        artworkTarget = null
        sourceMediaItem = mediaItem
        activeMediaId = mediaItem?.mediaId
        timedLyrics = emptyList()
        displayedLyricIndex = -1
        artworkData = mediaItem?.mediaMetadata?.artworkData

        if (mediaItem == null ||
            mediaItem.mediaMetadata.extras?.getString("type") != Constants.MEDIA_TYPE_MUSIC
        ) return

        loadArtwork(mediaItem)
        loadLyrics(mediaItem, requestGeneration)
        if (shouldPublishLyrics()) scheduleLyricUpdate()
    }

    private fun updateA2dpState() {
        val connected = isA2dpConnected()
        if (connected == a2dpConnected) return

        a2dpConnected = connected
        displayedLyricIndex = -1
        if (shouldPublishLyrics()) {
            scheduleLyricUpdate()
        } else {
            handler.removeCallbacks(lyricTicker)
            publishMetadata(null)
        }
    }

    private fun isA2dpConnected(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }

    private fun shouldPublishLyrics(): Boolean =
        Preferences.isBluetoothLyricsEnabled() && a2dpConnected

    private fun loadArtwork(mediaItem: MediaItem) {
        if (artworkData != null) return

        val artworkUri = mediaItem.mediaMetadata.artworkUri ?: return
        val mediaId = mediaItem.mediaId
        artworkTarget = object : CustomTarget<Bitmap>(ARTWORK_SIZE_PX, ARTWORK_SIZE_PX) {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                if (activeMediaId != mediaId) return

                artworkData = ByteArrayOutputStream().use { output ->
                    resource.compress(Bitmap.CompressFormat.JPEG, ARTWORK_JPEG_QUALITY, output)
                    output.toByteArray()
                }
                publishMetadata(currentLyric())
            }

            override fun onLoadCleared(placeholder: Drawable?) = Unit
        }

        Glide.with(appContext)
            .asBitmap()
            .load(artworkUri)
            .into(artworkTarget!!)
    }

    private fun loadLyrics(mediaItem: MediaItem, generation: Int) {
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
                        displayedLyricIndex = -1
                        if (shouldPublishLyrics()) updateTimedLyric()
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
                        displayedLyricIndex = -1
                        if (shouldPublishLyrics()) updateTimedLyric()
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
        if (shouldPublishLyrics() && player.isPlaying) {
            handler.postDelayed(lyricTicker, LYRIC_POLL_INTERVAL_MS)
        }
    }

    private fun updateTimedLyric() {
        if (!shouldPublishLyrics() ||
            timedLyrics.isEmpty() ||
            activeMediaId != player.currentMediaItem?.mediaId
        ) return

        val newIndex = timedLyrics.indexOfLast { it.startMs <= player.currentPosition }
        if (newIndex == displayedLyricIndex) return

        displayedLyricIndex = newIndex
        publishMetadata(currentLyric())
    }

    private fun currentLyric(): String? = timedLyrics.getOrNull(displayedLyricIndex)?.text

    private fun publishMetadata(lyric: String?) {
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
        val extras = Bundle(base.extras ?: Bundle()).apply {
            putBoolean(EXTRA_MANAGED_METADATA, true)
            if (publishLyric) putString(EXTRA_CURRENT_LYRIC, lyric) else remove(EXTRA_CURRENT_LYRIC)
        }

        val metadata = base.buildUpon()
            .setTitle(if (publishLyric) lyric else base.title)
            .setArtist(if (publishLyric && titleArtist.isNotBlank()) titleArtist else base.artist)
            .setDisplayTitle(songTitle)
            .setSubtitle(songArtist)
            .setExtras(extras)
            .apply { artworkData?.let { setArtworkData(it, null) } }
            .build()

        if (current.mediaMetadata == metadata) return
        player.replaceMediaItem(index, source.buildUpon().setMediaMetadata(metadata).build())
    }

    private companion object {
        const val EXTRA_MANAGED_METADATA = "com.cappielloantonio.tempo.bluetooth.managed_metadata"
        const val EXTRA_CURRENT_LYRIC = "com.cappielloantonio.tempo.bluetooth.current_lyric"
        const val ARTWORK_SIZE_PX = 512
        const val ARTWORK_JPEG_QUALITY = 85
        const val LYRIC_POLL_INTERVAL_MS = 250L
    }
}
