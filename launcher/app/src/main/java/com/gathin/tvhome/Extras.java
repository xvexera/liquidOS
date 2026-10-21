package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * On-TV choices for the Home widgets and the Liquid Glass look. They live next to the other launcher
 * settings (Config.SETTINGS_NAME) but a theme push does not reset them: they are the owner's, not the theme's.
 */
final class Extras {
    static final String BTC_TICKER = "btc_ticker";
    static final String NOW_PLAYING = "now_playing";
    static final String LIQUID_GLASS = "liquid_glass";
    static final String GLASS_BLUR = "glass_blur";
    static final String WALLPAPER_DIM = "wallpaper_dim";
    /** Bumped by Launcher settings on every change; Home rebuilds when it differs from the one it started with. */
    static final String REVISION = "settings_revision";

    /** Blur levels for the Liquid Glass backdrop: how far the wallpaper is shrunk before it is stretched back. */
    static final int BLUR_SOFT = 0, BLUR_MEDIUM = 1, BLUR_STRONG = 2;

    private Extras() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    static int revision(Context ctx) {
        return prefs(ctx).getInt(REVISION, 0);
    }

    static void bumpRevision(Context ctx) {
        SharedPreferences p = prefs(ctx);
        p.edit().putInt(REVISION, p.getInt(REVISION, 0) + 1).apply();
    }

    static boolean btcTicker(Context ctx) {
        return prefs(ctx).getBoolean(BTC_TICKER, true);
    }

    static boolean nowPlaying(Context ctx) {
        return prefs(ctx).getBoolean(NOW_PLAYING, true);
    }

    static boolean liquidGlass(Context ctx) {
        return prefs(ctx).getBoolean(LIQUID_GLASS, true);
    }

    static int glassBlur(Context ctx) {
        int level = prefs(ctx).getInt(GLASS_BLUR, BLUR_MEDIUM);
        return Math.max(BLUR_SOFT, Math.min(BLUR_STRONG, level));
    }

    /** The on-TV wallpaper dim in percent, or -1 when the theme decides. */
    static int wallpaperDim(Context ctx) {
        return prefs(ctx).getInt(WALLPAPER_DIM, -1);
    }

    /** The built-in wallpaper picked on the TV ("aurora"), or null. */
    static String builtinWallpaper(Context ctx) {
        String choice = prefs(ctx).getString(Config.WALLPAPER_CHOICE_KEY, null);
        if (choice == null || !choice.startsWith(BuiltinWallpapers.PREFIX)) return null;
        String name = choice.substring(BuiltinWallpapers.PREFIX.length());
        return BuiltinWallpapers.exists(name) ? name : null;
    }
}
