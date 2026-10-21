package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

/**
 * Follows the media session worth showing on Home: one that is playing, else one that is paused with something
 * loaded. Active only between start() and stop(). Without the notification listener grant (see MediaListener)
 * start() reports nothing and the card stays hidden.
 */
final class NowPlaying {
    interface Listener {
        /** On the main thread; null when there is nothing to show. */
        void onSession(MediaController controller);
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ComponentName component;
    private MediaSessionManager manager;
    private MediaController current;
    private boolean started;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::choose;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            refresh();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            refresh();
        }

        @Override
        public void onSessionDestroyed() {
            refresh();
        }
    };

    NowPlaying(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        component = new ComponentName(context, MediaListener.class);
    }

    void start() {
        if (started) return;
        started = true;
        attempts = 0;
        manager = context.getSystemService(MediaSessionManager.class);
        if (manager == null) return;
        subscribe();
    }

    /**
     * Right after the launcher is installed or updated, Android rebinds the notification listener a few seconds
     * after Home is up, and until then every call here is refused. Try again for a while instead of staying blank
     * until the next visit to Home.
     */
    private void subscribe() {
        if (!started) return;
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, component, main);
            choose(manager.getActiveSessions(component));
        } catch (SecurityException notYet) {
            listener.onSession(null);
            if (++attempts <= RETRIES) main.postDelayed(retry, RETRY_MS);
        }
    }

    private static final int RETRIES = 10;
    private static final long RETRY_MS = 3000;
    private int attempts;
    private final Runnable retry = this::subscribe;

    void stop() {
        if (!started) return;
        started = false;
        main.removeCallbacks(retry);
        if (manager != null) {
            try {
                manager.removeOnActiveSessionsChangedListener(sessionsChanged);
            } catch (RuntimeException ignored) {
                // never registered: the grant was missing
            }
        }
        setCurrent(null);
    }

    MediaController current() {
        return current;
    }

    /** Asks every session again: a paused one can be overtaken by another that starts playing. */
    private void refresh() {
        if (!started || manager == null) return;
        try {
            choose(manager.getActiveSessions(component));
        } catch (SecurityException e) {
            setCurrent(null);
            listener.onSession(null);
        }
    }

    private void choose(List<MediaController> sessions) {
        if (!started) return;
        MediaController playing = null, paused = null;
        if (sessions != null) {
            for (MediaController c : sessions) {
                if (!worthShowing(c)) continue;
                int state = c.getPlaybackState().getState();
                if (isPlaying(state)) {
                    playing = c;
                    break;
                }
                if (paused == null) paused = c;
            }
        }
        setCurrent(playing != null ? playing : paused);
        listener.onSession(current);
    }

    private static boolean worthShowing(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        MediaMetadata m = c.getMetadata();
        if (s == null || m == null) return false;
        String title = m.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (title == null || title.trim().isEmpty()) return false;
        int state = s.getState();
        return isPlaying(state) || state == PlaybackState.STATE_PAUSED;
    }

    static boolean isPlaying(int state) {
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_FAST_FORWARDING || state == PlaybackState.STATE_REWINDING
                || state == PlaybackState.STATE_SKIPPING_TO_NEXT || state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS;
    }

    private void setCurrent(MediaController next) {
        if (current != null && (next == null || !current.getSessionToken().equals(next.getSessionToken()))) {
            current.unregisterCallback(callback);
            current = null;
        }
        if (next != null && current == null) {
            current = next;
            current.registerCallback(callback, main);
        }
    }
}
