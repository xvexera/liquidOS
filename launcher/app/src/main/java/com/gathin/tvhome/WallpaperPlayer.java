package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private final FrameLayout root;
    private final ImageView image;
    private final List<File> files;
    private final long interval;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    private final int height;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    /** The picture on screen now, for Liquid Glass to blur. */
    private Bitmap current;
    private java.util.function.Consumer<Bitmap> onImage;
    /** The built-in wallpaper picked on the TV, or null. */
    private final String builtin;
    private final File cacheDir;
    private final boolean shown;
    /** Online wallpapers (Unsplash) picked on the TV: one downloaded picture, replaced at each rotation. */
    private final boolean online;
    private final Context context;
    /** The quick wallpaper menu is previewing: rotation waits, and endPreview() puts the real picture back. */
    private boolean previewing;
    /** Only the latest preview request lands; earlier decodes still running are dropped. */
    private int previewToken;
    private static final int FADE_MS = 320;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // A built-in wallpaper picked on the TV replaces the theme's pictures and their rotation.
        builtin = Extras.builtinWallpaper(context);
        online = OnlineWallpapers.active(context);
        this.context = context.getApplicationContext();
        cacheDir = context.getCacheDir();
        if (builtin != null || online) files.clear();
        shown = (config.wallpaperEnabled && !files.isEmpty()) || builtin != null || online;
        if (shown) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // No shade layer at all when the theme doesn't dim: a full-screen view is overdraw on 1 GB TVs.
            if (config.wallpaperDim > 0) {
                View shade = new View(context);
                shade.setBackgroundColor((config.wallpaperDim * 255 / 100) << 24);
                shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                root.addView(shade, 1, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            load();
        }
    }

    /** Puts the picture up: the built-in one (drawn once, then read from the cache), or the theme's. */
    private void load() {
        if (online) {
            File picture = OnlineWallpapers.current(context);
            Bitmap bitmap = picture != null ? OnlineWallpapers.tame(decode(picture)) : null;
            if (bitmap != null) {
                show(bitmap);
                displayed = OnlineWallpapers.CHOICE;
            }
        } else if (builtin != null) {
            Bitmap bitmap = BuiltinWallpapers.load(builtin, Math.max(1, width / 2), Math.max(1, height / 2), cacheDir);
            if (bitmap != null) {
                show(bitmap);
                displayed = BuiltinWallpapers.PREFIX + builtin;
            }
        } else {
            showFirst();
        }
    }

    /**
     * Drops the picture while Home is hidden (onTrimMemory UI_HIDDEN): a 1080p wallpaper is several megabytes the
     * app in front can use instead. start() puts it back.
     */
    void release() {
        if (!shown || current == null || active) return;
        image.setImageDrawable(null);
        current = null;
        displayed = "";
    }

    /** The picture on screen now, or null for the plain background colour. */
    Bitmap current() {
        return current;
    }

    /** Called on the main thread whenever a rotation puts up another picture. */
    void setOnImageChanged(java.util.function.Consumer<Bitmap> listener) {
        onImage = listener;
    }

    private void show(Bitmap bitmap) {
        image.setImageBitmap(bitmap);
        current = bitmap;
        if (onImage != null) onImage.accept(bitmap);
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        File first = WallpaperRotation.find(files, previous);
        if (first == null) first = WallpaperRotation.pick(files, previous, random);
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                show(bitmap);
                displayed = first.getName();
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            first = WallpaperRotation.pick(files, previous, random);
        }
    }

    void start() {
        active = true;
        if (shown && current == null && !closed) load();
        update();
    }

    void stop() {
        active = false;
    }

    void close() {
        active = false;
        closed = true;
        decoder.shutdownNow();
    }

    /**
     * Shows another wallpaper for a moment, cross-fading from what is on screen: the quick menu calls it as each
     * row gets focus. {@code choice} is a WallpaperChoices value; its picture is decoded off the main thread.
     */
    void preview(WallpaperChoices.Choice choice) {
        previewing = true;
        int token = ++previewToken;
        decoder.execute(() -> {
            Bitmap bitmap = null;
            if (choice.value.startsWith(BuiltinWallpapers.PREFIX)) {
                bitmap = BuiltinWallpapers.load(choice.value.substring(BuiltinWallpapers.PREFIX.length()),
                        Math.max(1, width / 2), Math.max(1, height / 2), cacheDir);
            } else if (choice.file != null) {
                bitmap = decode(choice.file);
                if (OnlineWallpapers.CHOICE.equals(choice.value)) bitmap = OnlineWallpapers.tame(bitmap);
            }
            Bitmap result = bitmap;
            root.post(() -> {
                if (closed || token != previewToken || !previewing) return;
                crossFade(result);
            });
        });
    }

    /** A new online picture arrived: decode it off the main thread and cross-fade to it. */
    void showOnline(File picture) {
        if (!online || picture == null || closed) return;
        decoder.execute(() -> {
            Bitmap bitmap = OnlineWallpapers.tame(decode(picture));
            root.post(() -> {
                if (closed || bitmap == null || previewing) return;
                crossFade(bitmap);
                current = bitmap;
                displayed = OnlineWallpapers.CHOICE;
            });
        });
    }

    /** Back out of the quick menu without picking: the real wallpaper (or none) fades back in. */
    void endPreview() {
        if (!previewing) return;
        previewing = false;
        previewToken++;
        crossFade(current);
    }

    private void crossFade(Bitmap to) {
        if (image.getParent() == null) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        Drawable from = image.getDrawable();
        // A finished fade leaves a TransitionDrawable: start the next one from the picture it ended on.
        if (from instanceof TransitionDrawable) from = ((TransitionDrawable) from).getDrawable(1);
        if (from == null) from = new ColorDrawable(0);
        Drawable next = to != null ? new BitmapDrawable(root.getResources(), to) : new ColorDrawable(0);
        TransitionDrawable fade = new TransitionDrawable(new Drawable[]{from, next});
        fade.setCrossFadeEnabled(true);
        image.setImageDrawable(fade);
        fade.startTransition(FADE_MS);
        if (onImage != null) onImage.accept(to);
    }

    void update() {
        if (!active || closed || loading || previewing || files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        File selected = WallpaperRotation.find(files, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(files, previous, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                show(bitmap);
                displayed = next.getName();
                if (!displayed.equals(previous) || changed == 0) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
