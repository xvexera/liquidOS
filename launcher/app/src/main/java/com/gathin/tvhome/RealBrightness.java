package com.gathin.tvhome;

import android.content.Context;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;

/**
 * The TV's real backlight, where Android can reach it. On a TV that runs Android itself (Sony, TCL, Xiaomi,
 * Hisense, Philips…) the panel follows the system brightness, which the launcher writes with WRITE_SETTINGS. A box
 * plugged into a TV over HDMI (onn, Chromecast, Shield…) only sends a picture: HDMI-CEC has no brightness command,
 * so there the TV's own menu is the only way and the bar is shown as unavailable.
 */
final class RealBrightness {
    private RealBrightness() {
    }

    /** A TV with its own panel: it lists hardware HDMI inputs (a box lists none, it is the HDMI source itself). */
    static boolean hasPanel(Context ctx) {
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return false;
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getType() == TvInputInfo.TYPE_HDMI) return true;
            }
        } catch (RuntimeException ignored) {
            // no TV input framework on this build
        }
        return false;
    }

    static boolean canWrite(Context ctx) {
        return Settings.System.canWrite(ctx);
    }

    static boolean supported(Context ctx) {
        return hasPanel(ctx) && canWrite(ctx);
    }

    /** The current backlight, 0 to 100. */
    static int get(Context ctx) {
        int v = Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
        return Math.round(v * 100f / 255f);
    }

    /** Sets the backlight, 0 to 100 (kept at least 1, so the panel never goes fully dark). */
    static void set(Context ctx, int percent) {
        if (!supported(ctx)) return;
        try {
            Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS,
                    Math.max(1, Math.min(255, Math.round(percent * 255f / 100f))));
        } catch (RuntimeException ignored) {
            // the grant was taken back
        }
    }
}
