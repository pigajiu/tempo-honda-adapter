package com.cappielloantonio.tempo.service

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi

/**
 * Exposes Bluetooth-specific metadata through MediaSession without mutating ExoPlayer's playlist.
 * Updating the override invalidates only the session-facing player state.
 */
@UnstableApi
class BluetoothMetadataPlayer(player: Player) : ForwardingSimpleBasePlayer(player) {
    private var metadataOverride: MediaMetadata? = null

    fun setMetadataOverride(metadata: MediaMetadata?) {
        if (metadataOverride == metadata) return
        metadataOverride = metadata
        invalidateState()
    }

    override fun getState(): SimpleBasePlayer.State {
        val state = super.getState()
        val metadata = metadataOverride ?: return state
        return state.buildUpon()
            .setPlaylist(state.timeline, state.currentTracks, metadata)
            .build()
    }
}
