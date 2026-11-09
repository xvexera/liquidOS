package com.gathin.tvhome;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exists so Android lets the launcher read the active media sessions (MediaSessionManager.getActiveSessions
 * requires an enabled notification listener). It reads no notifications. Grant it once over adb:
 * {@code cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.MediaListener}
 *
 * While Android keeps it bound it also writes down Continue watching (see WatchHistory): the video apps' sessions
 * are followed on a background thread, and where each video is gets written when it pauses, stops or changes, and
 * once a minute while it plays.
 */
public class MediaListener extends NotificationListenerService {
    private static final long SAMPLE_MS = 60_000;

    private HandlerThread thread;
    private Handler worker;
    private MediaSessionManager manager;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::follow;

    private final Runnable sample = new Runnable() {
        @Override
        public void run() {
            boolean playing = false;
            for (Watcher w : watchers) {
                try {
                    PlaybackState s = w.controller.getPlaybackState();
                    if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                        w.record();
                        playing = true;
                    }
                } catch (RuntimeException gone) {
                    // the session died; the sessions listener takes it off the list
                }
            }
            if (playing) worker.postDelayed(this, SAMPLE_MS);
        }
    };

    private final class Watcher extends MediaController.Callback {
        final MediaController controller;
        /** The session's metadata without its pictures, as last reported; null until asked or reported. */
        private MediaMetadata known;

        Watcher(MediaController controller) {
            this.controller = controller;
        }

        /** Writes down where the video is, asking the app for its metadata only the first time. */
        void record() {
            if (known == null) known = WatchHistory.light(controller.getMetadata());
            lookUp(WatchHistory.record(MediaListener.this, controller, known));
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (state == null) return;
            switch (state.getState()) {
                case PlaybackState.STATE_PLAYING:
                    worker.removeCallbacks(sample);
                    worker.postDelayed(sample, SAMPLE_MS);
                    break;
                case PlaybackState.STATE_PAUSED:
                case PlaybackState.STATE_STOPPED:
                case PlaybackState.STATE_SKIPPING_TO_NEXT:
                case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
                    record();
                    break;
                default:
                    break;
            }
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            known = WatchHistory.light(metadata);
            record();
        }
    }

    private static final long LOOKUP_WAIT_MS = 2 * 60_000L, LOOKUP_WAIT_MAX_MS = 2 * 60 * 60_000L;
    /** Lookups that found nothing, by video: when to try again and the wait that led there. */
    private final Map<String, long[]> lookups = new HashMap<>();

    /**
     * A YouTube video without its id: looked up on the worker thread (it is network). A lookup that fails (offline, or
     * YouTube has no such video) is tried again after two minutes, then four, up to every two hours: each try
     * downloads a whole results page, and the video is written down every minute while it plays.
     */
    private void lookUp(WatchHistory.Entry e) {
        if (e == null) return;
        long now = SystemClock.elapsedRealtime();
        long[] last = lookups.get(e.key());
        if (last != null && now < last[0]) return;
        if (WatchHistory.findYouTubeId(this, e) != null) {
            lookups.remove(e.key());
            return;
        }
        long wait = last == null ? LOOKUP_WAIT_MS : Math.min(last[1] * 2, LOOKUP_WAIT_MAX_MS);
        if (lookups.size() > 40) lookups.clear();
        lookups.put(e.key(), new long[]{now + wait, wait});
    }

    private final List<Watcher> watchers = new ArrayList<>();

    @Override
    public void onListenerConnected() {
        if (thread != null) return;
        thread = new HandlerThread("continue-watching");
        thread.start();
        worker = new Handler(thread.getLooper());
        manager = getSystemService(MediaSessionManager.class);
        if (manager == null) return;
        ComponentName me = new ComponentName(this, MediaListener.class);
        worker.post(() -> {
            try {
                manager.addOnActiveSessionsChangedListener(sessionsChanged, me, worker);
                follow(manager.getActiveSessions(me));
            } catch (SecurityException notGranted) {
                // without the grant there is nothing to follow
            }
        });
    }

    @Override
    public void onListenerDisconnected() {
        if (thread == null) return;
        if (manager != null) manager.removeOnActiveSessionsChangedListener(sessionsChanged);
        worker.post(() -> follow(null));
        thread.quitSafely();
        thread = null;
    }

    /** On the worker thread: follows exactly the sessions listed, writing down the ones that went away. */
    private void follow(List<MediaController> sessions) {
        for (Watcher w : watchers) {
            w.controller.unregisterCallback(w);
            try {
                WatchHistory.record(this, w.controller, w.known);
            } catch (RuntimeException gone) {
                // the session died before it could be read; its last pause or minute was written already
            }
        }
        watchers.clear();
        if (sessions == null) return;
        for (MediaController c : sessions) {
            if (WatchHistory.isMusicApp(this, c.getPackageName())) continue;
            Watcher w = new Watcher(c);
            c.registerCallback(w, worker);
            watchers.add(w);
        }
        worker.removeCallbacks(sample);
        sample.run();
    }
}
