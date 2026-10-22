package com.gathin.tvhome;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Every wallpaper the owner can pick on the TV, in menu order: the built-in ones, the theme's own pictures, then
 * None. Shared by Launcher settings and the quick wallpaper menu, which store the same choice string.
 */
final class WallpaperChoices {
    /** No picture: the app colour glow behind the glass ("Background from icon"). */
    static final String NONE = "none";
    /** No picture and no glow: pure black, every pixel off on an OLED screen. */
    static final String OLED = "oled";

    static final class Choice {
        /** What Config.WALLPAPER_CHOICE_KEY stores: "builtin:name", a file name, or "none". */
        final String value;
        final String label;
        /** The theme picture behind a file choice; null for built-in and None. */
        final File file;

        Choice(String value, String label, File file) {
            this.value = value;
            this.label = label;
            this.file = file;
        }
    }

    private WallpaperChoices() {
    }

    static List<Choice> all(Context ctx) {
        List<Choice> list = new ArrayList<>();
        list.add(new Choice(OnlineWallpapers.CHOICE, ctx.getString(R.string.online_wallpapers),
                OnlineWallpapers.current(ctx)));
        for (String name : BuiltinWallpapers.names()) {
            list.add(new Choice(BuiltinWallpapers.PREFIX + name, builtinLabel(ctx, name), null));
        }
        File dir = Config.dir(ctx);
        List<File> images = new ArrayList<>(WallpaperRotation.images(dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        for (File image : images) {
            // An upload from the phone is named by when it came, short enough for a menu row: "Photo · 22 Oct 15:30".
            String label = image.getName().startsWith(PhoneUpload.PREFIX)
                    ? ctx.getString(R.string.wallpaper_upload_label, android.text.format.DateFormat.format(
                    android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "dMMMjmm"),
                    image.lastModified()))
                    : prettyName(image.getName());
            list.add(new Choice(image.getName(), label, image));
        }
        list.add(new Choice(NONE, ctx.getString(R.string.wallpaper_icon), null));
        list.add(new Choice(OLED, ctx.getString(R.string.wallpaper_oled), null));
        return list;
    }

    static String builtinLabel(Context ctx, String name) {
        int id = ctx.getResources().getIdentifier("wallpaper_" + name, "string", ctx.getPackageName());
        return id != 0 ? ctx.getString(id) : name;
    }

    /** Pictures whose file name starts with one of these sit in their own folder of the wallpaper menu. */
    static final String NASA = "nasa_", RETRO = "retro_";

    /** "nasa_pillars_of_creation.jpg" reads as "Pillars of creation" in a menu. */
    static String prettyName(String file) {
        String base = file.replaceFirst("\\.[A-Za-z0-9]+$", "").replaceFirst("^(?i)(nasa|retro)[_-]", "").replace('_', ' ').replace('-', ' ').trim();
        return base.isEmpty() ? file : Character.toUpperCase(base.charAt(0)) + base.substring(1);
    }

    /** The label of the stored choice, for the settings row: Theme default when nothing was picked. */
    static String labelOf(Context ctx, String value) {
        if (value == null) return ctx.getString(R.string.wallpaper_theme_default);
        for (Choice c : all(ctx)) if (c.value.equals(value)) return c.label;
        return value;
    }
}
