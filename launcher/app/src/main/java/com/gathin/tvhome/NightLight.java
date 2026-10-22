package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;

/**
 * Night mode: a dim layer and a warm (amber) filter over everything on screen, drawn by NightLightService in one
 * overlay window. Replaces dimmer apps. The levels live with the other launcher settings; the overlay needs the
 * "display over other apps" grant, given once over adb:
 * {@code appops set com.gathin.tvhome SYSTEM_ALERT_WINDOW allow}
 */
final class NightLight {
    static final String ON = "night_on";
    static final String DIM = "night_dim";
    static final String WARM = "night_warm";
    /** The dim slider goes to 100%; color() keeps a trace of the picture even there. */
    static final int MAX_DIM = 100;
    /** Candle-like amber, about 1900 K. */
    private static final int WARM_RGB = 0xFF9329;
    /** Strongest warm filter, as alpha; more turns the picture orange rather than warm. */
    private static final float MAX_WARM_ALPHA = 0.45f;

    private NightLight() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    static boolean on(Context ctx) {
        return prefs(ctx).getBoolean(ON, false);
    }

    static int dim(Context ctx) {
        return Math.max(0, Math.min(MAX_DIM, prefs(ctx).getInt(DIM, 40)));
    }

    static int warm(Context ctx) {
        return Math.max(0, Math.min(100, prefs(ctx).getInt(WARM, 30)));
    }

    static boolean canDraw(Context ctx) {
        return Settings.canDrawOverlays(ctx);
    }

    /** Saves the levels and brings the overlay in line with them: shown, updated or removed. */
    static void set(Context ctx, boolean on, int dim, int warm) {
        prefs(ctx).edit().putBoolean(ON, on).putInt(DIM, dim).putInt(WARM, warm).apply();
        apply(ctx);
    }

    /** Starts or stops the overlay service to match the saved state. Home calls it at start, after a reboot too. */
    static void apply(Context ctx) {
        Context app = ctx.getApplicationContext();
        Intent service = new Intent(app, NightLightService.class);
        if (on(app) && canDraw(app)) {
            if (!NightLightService.update(app)) {
                try {
                    app.startForegroundService(service);
                } catch (RuntimeException notAllowed) {
                    // started from the background on a strict build: Home will try again when it is in front
                }
            }
        } else {
            app.stopService(service);
        }
    }

    /**
     * The one colour that equals the amber filter with the black dim layer over it: alpha compositing of the two
     * layers done here, so the overlay is a single flat fill.
     */
    static int color(int dimPercent, int warmPercent) {
        float aw = warmPercent / 100f * MAX_WARM_ALPHA;
        // 100% is as dark as the layer gets, short of black: a trace of the picture stays to find the moon again.
        float ad = dimPercent / 100f * 0.97f;
        float a = 1f - (1f - aw) * (1f - ad);
        if (a <= 0f) return 0;
        float scale = aw * (1f - ad) / a;   // un-premultiply the amber left after the dim layer
        int r = Math.round(((WARM_RGB >> 16) & 0xFF) * scale);
        int g = Math.round(((WARM_RGB >> 8) & 0xFF) * scale);
        int b = Math.round((WARM_RGB & 0xFF) * scale);
        return Math.round(a * 255) << 24 | r << 16 | g << 8 | b;
    }
}
