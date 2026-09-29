package com.cappielloantonio.tempo.service

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionCompatBridge
import androidx.media3.session.legacy.MediaMetadataCompat

/**
 * Writes metadata directly into Media3's existing legacy MediaSessionCompat.
 *
 * This mirrors NetEase Cloud Music's Bluetooth lyric path without creating a
 * second active media session.
 */
@UnstableApi
class LegacyBluetoothMetadataPublisher(session: MediaSession) {
    private val mediaSession = MediaSessionCompatBridge.getSessionCompat(session)
    private var lastArtworkData: ByteArray? = null

    fun publish(metadata: MediaMetadata?) {
        if (metadata == null) {
            lastArtworkData = null
            return
        }

        val current = mediaSession.controller.metadata
        val builder = if (current != null) {
            MediaMetadataCompat.Builder(current)
        } else {
            MediaMetadataCompat.Builder()
        }

        metadata.title?.let {
            builder.putText(MediaMetadataCompat.METADATA_KEY_TITLE, it)
        }
        metadata.artist?.let {
            builder.putText(MediaMetadataCompat.METADATA_KEY_ARTIST, it)
        }
        metadata.displayTitle?.let {
            builder.putText(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, it)
        }
        metadata.subtitle?.let {
            builder.putText(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, it)
        }

        val artworkData = metadata.artworkData
        if (artworkData != null &&
            (lastArtworkData == null || !artworkData.contentEquals(lastArtworkData))
        ) {
            val bitmap = BitmapFactory.decodeByteArray(artworkData, 0, artworkData.size)
            if (bitmap != null) {
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap)
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
                lastArtworkData = artworkData.copyOf()
            }
        }

        val compatMetadata = builder.build()
        val startNs = SystemClock.elapsedRealtimeNanos()
        mediaSession.setMetadata(compatMetadata)
        val elapsedUs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000L

        Log.i(
            TAG,
            "Direct MediaSessionCompat.setMetadata title=${metadata.title} " +
                "artist=${metadata.artist} artwork=${artworkData != null} elapsedUs=$elapsedUs"
        )
    }

    companion object {
        private const val TAG = "LegacyBluetoothMetadata"
    }
}
