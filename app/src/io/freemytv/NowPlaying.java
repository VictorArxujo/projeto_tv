package io.freemytv;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;

import java.util.List;

/**
 * What's playing (Spotify, YouTube Music...), for the home screen. Android shows other apps' media sessions only to an
 * enabled notification listener: this service is that listener and does nothing with the notifications. The TV has no
 * screen to allow it, so HomeMode allows it through ADB in Free My TV mode, and Original takes it back.
 */
public class NowPlaying extends NotificationListenerService {
    static ComponentName component(Context c) {
        return new ComponentName(c, NowPlaying.class);
    }

    /** Allowed to read the media sessions (the listener is on). */
    static boolean allowed(Context c) {
        ComponentName me = component(c);
        if (Build.VERSION.SDK_INT >= 27) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            return nm != null && nm.isNotificationListenerAccessGranted(me);
        }
        String on = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return on != null && on.contains(me.flattenToString());
    }

    /**
     * Follows the media session that is playing (or paused) while the home screen is shown. Its fields change on the
     * main thread, then `changed` runs; the artwork is cut once per song, to the size the screen draws it.
     */
    static final class Watch implements MediaSessionManager.OnActiveSessionsChangedListener {
        interface Changed { void run(); }

        String title = "", artist = "";
        /** The song's length (0 when the app doesn't say) and where it was at `positionAt` (elapsedRealtime). */
        long duration, position, positionAt;
        float speed;
        Bitmap art;
        boolean playing, shown;

        private final Context c;
        private final Changed changed;
        private final int artSize;
        private final Handler main = new Handler(Looper.getMainLooper());
        private MediaSessionManager sessions;
        private MediaController controller;
        /** Cut the artwork again: Spotify sends a new song's name first and its artwork a moment later. */
        private boolean recut = true;

        private final MediaController.Callback callback = new MediaController.Callback() {
            @Override public void onMetadataChanged(MediaMetadata metadata) {
                recut = true;
                read();
            }
            @Override public void onPlaybackStateChanged(PlaybackState state) { read(); }
            @Override public void onSessionDestroyed() { pick(null); }
        };

        Watch(Context c, int artSize, Changed changed) {
            this.c = c.getApplicationContext();
            this.artSize = Math.max(1, artSize);
            this.changed = changed;
        }

        void start() {
            if (!allowed(c)) return;
            try {
                sessions = c.getSystemService(MediaSessionManager.class);
                sessions.addOnActiveSessionsChangedListener(this, component(c), main);
                pick(sessions.getActiveSessions(component(c)));
            } catch (SecurityException e) {
                sessions = null;
                AppLog.w("now playing: " + e.getMessage());
            }
        }

        /**
         * Nothing shown: follow the sessions again from scratch. Right after this app is updated the listener comes back a
         * moment later, and a session listener added before that never hears anything.
         */
        void retry() {
            if (shown) return;
            stop();
            start();
        }

        void stop() {
            if (sessions != null) sessions.removeOnActiveSessionsChangedListener(this);
            sessions = null;
            follow(null);
        }

        /** Where the song is now (ms): the last position the app gave, moved on by the time since, while it plays. */
        long positionNow() {
            long at = position;
            if (playing && positionAt > 0) at += (long) ((SystemClock.elapsedRealtime() - positionAt) * speed);
            return duration > 0 ? Math.min(Math.max(0, at), duration) : Math.max(0, at);
        }

        /** The remote's buttons on the player: 0 previous, 1 play or pause, 2 next. */
        void command(int i) {
            if (controller == null) return;
            MediaController.TransportControls t = controller.getTransportControls();
            if (i == 0) t.skipToPrevious();
            else if (i == 2) t.skipToNext();
            else if (playing) t.pause();
            else t.play();
        }

        @Override public void onActiveSessionsChanged(List<MediaController> list) {
            pick(list);
        }

        /** The first session that is playing; else the first paused one. */
        private void pick(List<MediaController> list) {
            MediaController best = null;
            if (list != null) {
                for (MediaController m : list) {
                    int s = state(m);
                    if (s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING) {
                        best = m;
                        break;
                    }
                    if (best == null && s == PlaybackState.STATE_PAUSED) best = m;
                }
            }
            follow(best);
            recut = true;
            read();
        }

        private void follow(MediaController m) {
            if (controller != null && (m == null || !controller.getSessionToken().equals(m.getSessionToken()))) {
                controller.unregisterCallback(callback);
                controller = null;
            }
            if (m != null && controller == null) {
                controller = m;
                controller.registerCallback(callback, main);
            }
        }

        private static int state(MediaController m) {
            PlaybackState s = m.getPlaybackState();
            return s == null ? PlaybackState.STATE_NONE : s.getState();
        }

        private void read() {
            MediaMetadata md = controller == null ? null : controller.getMetadata();
            int s = controller == null ? PlaybackState.STATE_NONE : state(controller);
            boolean show = md != null && (s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING
                    || s == PlaybackState.STATE_PAUSED);
            String t = show ? text(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE) : "";
            String a = show ? text(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) : "";
            show &= !t.isEmpty();
            boolean p = show && s != PlaybackState.STATE_PAUSED;
            PlaybackState ps = controller == null ? null : controller.getPlaybackState();
            long d = show ? Math.max(0, md.getLong(MediaMetadata.METADATA_KEY_DURATION)) : 0;
            boolean moved = ps != null && (ps.getPosition() != position || ps.getLastPositionUpdateTime() != positionAt);
            if (ps != null) {
                position = Math.max(0, ps.getPosition());
                positionAt = ps.getLastPositionUpdateTime();
                speed = ps.getPlaybackSpeed();
            }
            Bitmap was = art;
            if (show && (recut || !t.equals(title))) {
                recut = false;
                art = square(md);
            } else if (!show) {
                art = null;
            }
            boolean same = d == duration && !moved;
            duration = d;
            if (show == shown && p == playing && t.equals(title) && a.equals(artist) && art == was && same) return;
            shown = show;
            playing = p;
            title = t;
            artist = a;
            changed.run();
        }

        private static String text(MediaMetadata md, String key, String fallback) {
            CharSequence v = md.getText(key);
            if (v == null || v.length() == 0) v = md.getText(fallback);
            return v == null ? "" : v.toString().trim();
        }

        /** The album art, its center cut square at the size drawn. */
        private Bitmap square(MediaMetadata md) {
            Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (b == null || b.getWidth() == 0 || b.getHeight() == 0) return null;
            try {
                int side = Math.min(b.getWidth(), b.getHeight());
                Bitmap crop = Bitmap.createBitmap(b, (b.getWidth() - side) / 2, (b.getHeight() - side) / 2, side, side);
                Bitmap out = Bitmap.createScaledBitmap(crop, artSize, artSize, true);
                if (crop != b && crop != out) crop.recycle();
                return out;
            } catch (RuntimeException | OutOfMemoryError e) {
                return null;
            }
        }
    }
}
