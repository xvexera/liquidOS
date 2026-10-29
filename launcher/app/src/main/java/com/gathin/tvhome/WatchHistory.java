package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Continue watching: what the TV's video apps played, kept by the launcher itself. Google TV keeps its own list in
 * a provider only system apps may read, so the launcher builds one from the media sessions it already follows for
 * the now playing card: title, picture and position, written when a video pauses, stops or changes.
 *
 * Everything stays in the launcher's private files on the TV: a small JSON file and one thumbnail per entry.
 */
final class WatchHistory {
    static final class Entry {
        String pkg;
        String title;
        String subtitle;
        /** The app's id for the video (a YouTube video id, say); may be empty. */
        String mediaId;
        long positionMs;
        long durationMs;
        long updatedAt;
        /** File name of the thumbnail in {@link #dir}, or empty. */
        String art;

        String key() {
            return pkg + "\n" + title;
        }

        int percent() {
            return durationMs > 0 ? (int) Math.max(0, Math.min(100, positionMs * 100 / durationMs)) : 0;
        }
    }

    /** Entries kept; Home shows the newest few. */
    private static final int MAX = 8;
    /** A video shorter than this is a clip, not something to come back to; live streams report no length at all. */
    private static final long MIN_DURATION_MS = 4 * 60_000L;
    /** Less than this watched: just sampled, not started. */
    private static final long MIN_POSITION_MS = 30_000L;
    /** Watched to here (or to the last two minutes): finished, so it leaves the list. */
    private static final int DONE_PERCENT = 95;
    private static final long DONE_LEFT_MS = 2 * 60_000L;
    private static final long MAX_AGE_MS = 30L * 24 * 60 * 60_000L;
    private static final int THUMB_WIDTH = 320;
    /** Music apps' sessions are songs, not something to continue watching. */
    private static final String[] MUSIC = {"com.spotify.tv.android", "com.google.android.youtube.tvmusic",
            "com.google.android.apps.youtube.music", "com.amazon.mp3", "com.aspiro.tidal", "deezer.android.tv",
            "com.apple.android.music", "com.soundcloud.android", "com.pandora.android.atv", "tunein.player"};

    private WatchHistory() {
    }

    private static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), "continue");
    }

    private static File index(Context ctx) {
        return new File(dir(ctx), "history.json");
    }

    static File artFile(Context ctx, Entry e) {
        return e.art == null || e.art.isEmpty() ? null : new File(dir(ctx), e.art);
    }

    /** Newest first. */
    static synchronized List<Entry> load(Context ctx) {
        List<Entry> list = new ArrayList<>();
        File f = index(ctx);
        if (!f.isFile()) return list;
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            long now = System.currentTimeMillis();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Entry e = new Entry();
                e.pkg = o.optString("pkg");
                e.title = o.optString("title");
                e.subtitle = o.optString("subtitle");
                e.mediaId = o.optString("media_id");
                e.positionMs = o.optLong("position");
                e.durationMs = o.optLong("duration");
                e.updatedAt = o.optLong("updated");
                e.art = o.optString("art");
                if (!e.pkg.isEmpty() && !e.title.isEmpty() && now - e.updatedAt < MAX_AGE_MS) list.add(e);
            }
        } catch (IOException | JSONException damaged) {
            // a half-written file: start over rather than show nothing forever
            list.clear();
        }
        list.sort((x, y) -> Long.compare(y.updatedAt, x.updatedAt));
        return list;
    }

    private static void save(Context ctx, List<Entry> list) {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : list) {
                a.put(new JSONObject().put("pkg", e.pkg).put("title", e.title).put("subtitle", e.subtitle)
                        .put("media_id", e.mediaId).put("position", e.positionMs).put("duration", e.durationMs)
                        .put("updated", e.updatedAt).put("art", e.art));
            }
        } catch (JSONException impossible) {
            return;
        }
        File d = dir(ctx);
        if (!d.isDirectory() && !d.mkdirs()) return;
        File tmp = new File(d, "history.json.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(a.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(index(ctx))) tmp.delete();
        // Thumbnails of entries that left the list.
        File[] files = d.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.getName().endsWith(".jpg")) continue;
            boolean used = false;
            for (Entry e : list) used |= f.getName().equals(e.art);
            if (!used) f.delete();
        }
    }

    static synchronized void remove(Context ctx, Entry gone) {
        List<Entry> list = load(ctx);
        list.removeIf(e -> e.key().equals(gone.key()));
        save(ctx, list);
    }

    /** Switched off in Settings: nothing of it is kept. */
    static synchronized void clear(Context ctx) {
        File[] files = dir(ctx).listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    static boolean isMusicApp(Context ctx, String pkg) {
        for (String m : MUSIC) if (m.equals(pkg)) return true;
        try {
            ApplicationInfo info = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return info.category == ApplicationInfo.CATEGORY_AUDIO;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    /** Where the video is now, counting the time it has been playing since the app last reported it. */
    static long position(PlaybackState state) {
        if (state == null) return 0;
        long pos = state.getPosition();
        if (state.getState() == PlaybackState.STATE_PLAYING && state.getLastPositionUpdateTime() > 0) {
            pos += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
        }
        return Math.max(0, pos);
    }

    /** Writes down where {@code c}'s video is, or drops it once finished. Called off the main thread. */
    static synchronized void record(Context ctx, MediaController c) {
        if (!Extras.continueWatching(ctx)) return;
        String pkg = c.getPackageName();
        if (pkg == null || pkg.equals(ctx.getPackageName()) || isMusicApp(ctx, pkg)) return;
        MediaMetadata m = c.getMetadata();
        if (m == null) return;
        String title = text(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        long duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (title.isEmpty() || duration < MIN_DURATION_MS) return;
        long position = Math.min(position(c.getPlaybackState()), duration);

        List<Entry> list = load(ctx);
        Entry e = null;
        for (Entry x : list) if (x.pkg.equals(pkg) && x.title.equals(title)) e = x;
        boolean done = position * 100 >= (long) DONE_PERCENT * duration || duration - position <= DONE_LEFT_MS;
        if (done || position < MIN_POSITION_MS) {
            // Finished: off the list. Barely started: not on it (but a started one keeps its place).
            if (e != null && done) {
                list.remove(e);
                save(ctx, list);
            }
            return;
        }
        if (e == null) {
            e = new Entry();
            e.pkg = pkg;
            e.title = title;
            e.art = "";
            list.add(0, e);
        }
        e.subtitle = text(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST);
        String id = m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        e.mediaId = id != null ? id : "";
        e.positionMs = position;
        e.durationMs = duration;
        e.updatedAt = System.currentTimeMillis();
        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art != null && (e.art.isEmpty() || !new File(dir(ctx), e.art).isFile())) {
            String name = String.format(Locale.ROOT, "%08x.jpg", e.key().hashCode());
            if (writeThumb(ctx, art, name)) e.art = name;
        }
        list.sort((x, y) -> Long.compare(y.updatedAt, x.updatedAt));
        while (list.size() > MAX) list.remove(list.size() - 1);
        save(ctx, list);
    }

    private static String text(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence s = m.getText(k);
            if (s != null && s.toString().trim().length() > 0) return s.toString().trim();
        }
        return "";
    }

    private static boolean writeThumb(Context ctx, Bitmap art, String name) {
        File d = dir(ctx);
        if (!d.isDirectory() && !d.mkdirs()) return false;
        int w = Math.min(THUMB_WIDTH, art.getWidth());
        int h = Math.max(1, art.getHeight() * w / Math.max(1, art.getWidth()));
        Bitmap small = w == art.getWidth() ? art : Bitmap.createScaledBitmap(art, w, h, true);
        try (FileOutputStream out = new FileOutputStream(new File(d, name))) {
            return small.compress(Bitmap.CompressFormat.JPEG, 82, out);
        } catch (IOException e) {
            return false;
        } finally {
            if (small != art) small.recycle();
        }
    }

    static Bitmap thumb(Context ctx, Entry e) {
        File f = artFile(ctx, e);
        return f != null && f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }
}
