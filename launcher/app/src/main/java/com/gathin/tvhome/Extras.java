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
    static final String CALENDAR = "calendar_widget";
    /** Days ahead the calendar pill counts events for: 1 to 30. */
    static final String CALENDAR_DAYS = "calendar_days";
    static final int CALENDAR_MAX_DAYS = 30;
    static final String LIQUID_GLASS = "liquid_glass";
    /** The old three-step blur (0 soft, 1 medium, 2 strong); read once to seed the 0-100 one. */
    static final String GLASS_BLUR = "glass_blur";
    /** How strongly Liquid Glass blurs the wallpaper behind it, 0 (almost sharp) to 100. */
    static final String GLASS_BLUR_PERCENT = "glass_blur_percent";
    static final String WALLPAPER_DIM = "wallpaper_dim";
    /** How app tiles look: "stock" (the apps' own banners), "mono" (the same in black and white), "minimal" (the name). */
    static final String ICON_PACK = "icon_pack";
    static final String[] ICON_PACKS = {"stock", "mono", "minimal"};
    /** The market widget's assets (MarketTicker.Asset ids, comma-separated) and whether their line shows. */
    static final String MARKET_ASSET = "market_asset";
    static final String MARKET_CHART = "market_chart";
    /** Bumped by Launcher settings on every change; Home rebuilds when it differs from the one it started with. */
    static final String REVISION = "settings_revision";


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

    static String iconPack(Context ctx) {
        String pack = prefs(ctx).getString(ICON_PACK, "stock");
        for (String p : ICON_PACKS) if (p.equals(pack)) return p;
        return "stock";
    }

    static boolean btcTicker(Context ctx) {
        return prefs(ctx).getBoolean(BTC_TICKER, true);
    }

    /** The picked assets, in the order they were picked; BTC when none (or none still known) is left. */
    static java.util.List<MarketTicker.Asset> marketAssets(Context ctx) {
        java.util.List<MarketTicker.Asset> list = new java.util.ArrayList<>();
        for (String id : prefs(ctx).getString(MARKET_ASSET, "BTC").split(",")) {
            MarketTicker.Asset a = MarketTicker.find(ctx, id.trim());
            if (a != null && !list.contains(a) && list.size() < MarketTicker.MAX_PICKED) list.add(a);
        }
        if (list.isEmpty()) list.add(MarketTicker.ALL[0]);
        return list;
    }

    static void setMarketAssets(Context ctx, java.util.List<String> ids) {
        prefs(ctx).edit().putString(MARKET_ASSET, String.join(",", ids)).apply();
    }

    static boolean marketChart(Context ctx) {
        return prefs(ctx).getBoolean(MARKET_CHART, true);
    }

    static boolean calendar(Context ctx) {
        return prefs(ctx).getBoolean(CALENDAR, true);
    }

    /** Days ahead the calendar menu lists events for: 1 to 30. */
    static final String CALENDAR_LIST_DAYS = "calendar_list_days";

    static int calendarListDays(Context ctx) {
        return Math.max(1, Math.min(CALENDAR_MAX_DAYS, prefs(ctx).getInt(CALENDAR_LIST_DAYS, 7)));
    }

    static int calendarDays(Context ctx) {
        return Math.max(1, Math.min(CALENDAR_MAX_DAYS, prefs(ctx).getInt(CALENDAR_DAYS, 3)));
    }

    static boolean nowPlaying(Context ctx) {
        return prefs(ctx).getBoolean(NOW_PLAYING, true);
    }

    static boolean liquidGlass(Context ctx) {
        return prefs(ctx).getBoolean(LIQUID_GLASS, true);
    }

    static int glassBlur(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (!p.contains(GLASS_BLUR_PERCENT)) {
            // Soft, medium and strong become 25, 50 and 90 on the new bar.
            int level = p.getInt(GLASS_BLUR, 1);
            return level <= 0 ? 25 : level >= 2 ? 90 : 50;
        }
        return Math.max(0, Math.min(100, p.getInt(GLASS_BLUR_PERCENT, 50)));
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
