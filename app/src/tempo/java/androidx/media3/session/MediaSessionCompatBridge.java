package androidx.media3.session;

import androidx.media3.session.legacy.MediaSessionCompat;

/**
 * Exposes Media3's existing legacy session to Tempo without creating a second active session.
 */
public final class MediaSessionCompatBridge {
    private MediaSessionCompatBridge() {}

    public static MediaSessionCompat getSessionCompat(MediaSession session) {
        return session.getSessionCompat();
    }
}
