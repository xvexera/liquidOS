package com.gathin.tvhome;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.service.notification.NotificationListenerService;

import java.util.ArrayList;
import java.util.List;

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
    private final List<MediaController> followed = new ArrayList<>();

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::follow;

    private final Runnable sample = new Runnable() {
        @Override
        public void run() {
            boolean playing = false;
            for (MediaController c : followed) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    lookUp(WatchHistory.record(MediaListener.this, c));
                    playing = true;
                }
            }
            if (playing) worker.postDelayed(this, SAMPLE_MS);
        }
    };

    private final class Watcher extends MediaController.Callback {
        final MediaController controller;

        Watcher(MediaController controller) {
            this.controller = controller;
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
                    lookUp(WatchHistory.record(MediaListener.this, controller));
                    break;
                default:
                    break;
            }
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            lookUp(WatchHistory.record(MediaListener.this, controller));
        }
    }

    /** A YouTube video without its id: looked up once, on the worker thread (it is network). */
    private void lookUp(WatchHistory.Entry e) {
        if (e != null) WatchHistory.findYouTubeId(this, e);
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
                WatchHistory.record(this, w.controller);
            } catch (RuntimeException gone) {
                // the session died before it could be read; its last pause or minute was written already
            }
        }
        watchers.clear();
        followed.clear();
        if (sessions == null) return;
        for (MediaController c : sessions) {
            if (WatchHistory.isMusicApp(this, c.getPackageName())) continue;
            Watcher w = new Watcher(c);
            c.registerCallback(w, worker);
            watchers.add(w);
            followed.add(c);
        }
        worker.removeCallbacks(sample);
        sample.run();
    }
}
