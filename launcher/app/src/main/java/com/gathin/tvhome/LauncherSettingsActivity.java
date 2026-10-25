package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Launcher settings, in the launcher's own look: the blurred wallpaper under a dark veil, sections on glass panels,
 * rows that turn white with focus, green glass switches and "‹ value ›" steppers moved with Left and Right. Only what
 * the Settings pill's quick menu does not already offer: how Home is laid out and moves, the wallpaper's dim, Liquid
 * Glass, and the system shortcuts. Apps, widgets, wallpapers and the dock size live in that menu.
 */
public final class LauncherSettingsActivity extends Activity {
    /** Result extra: something Home should do once it is back in front. */
    static final String EXTRA_THEN = "then";
    static final String THEN_REORDER = "reorder";
    static final String THEN_DOCK_EDIT = "dock_edit";

    private static final int[] DIM_STEPS = {0, 10, 20, 30, 40, 50, 60};

    private SharedPreferences choices;
    private float density;
    private LinearLayout content;
    /** Rows that only apply while their parent switch is on (blur under Liquid Glass, effects under animations). */
    private View blurRow;
    private View advancedRow;
    private GlassToggle advancedToggle;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        Config config = Config.load(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(backdrop());
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int side = dp(90);
        content.setPadding(side, dp(36), side, dp(48));
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        content.addView(text(getString(R.string.launcher_settings), 34, R.font.outfit_light, 0xFFFFFFFF));
        content.addView(credits());
        // The project it grew from, as its MIT license asks: small and dim under the author's line.
        TextView basedOn = text(getString(R.string.credits_based_on), 12, R.font.outfit_regular, 0x80FFFFFF);
        basedOn.setPadding(0, dp(2), 0, 0);
        content.addView(basedOn);
        TextView subtitle = text(getString(R.string.settings_subtitle), 14, R.font.outfit_regular, 0x99FFFFFF);
        subtitle.setPadding(0, dp(6), 0, 0);
        content.addView(subtitle);

        // Two columns, to use the width of a TV: layout and system on the left, glass and motion on the right.
        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(columns, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout left = column(columns, 0);
        LinearLayout right = column(columns, dp(36));

        // Home screen
        LinearLayout home = section(left, R.string.section_home);
        View firstRow = stepper(home, getString(R.string.settings_rows), getString(R.string.settings_rows_hint),
                new String[]{"0", "1", "2", "3"}, config.rowsBelowDock,
                i -> choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, i).apply());
        String[] packs = {getString(R.string.icon_pack_stock), getString(R.string.icon_pack_glass)};
        int packAt = java.util.Arrays.asList(Extras.ICON_PACKS).indexOf(Extras.iconPack(this));
        stepper(home, getString(R.string.icon_pack), getString(R.string.icon_pack_hint), packs, Math.max(0, packAt),
                i -> choices.edit().putString(Extras.ICON_PACK, Extras.ICON_PACKS[i]).apply());
        String[] styles = {getString(R.string.clock_style_normal), getString(R.string.clock_style_glass),
                getString(R.string.clock_style_glass_thin)};
        slider(home, getString(R.string.rim_intensity), getString(R.string.rim_intensity_hint), R.drawable.ic_brightness,
                Extras.rimIntensity(this), v -> choices.edit().putInt(Extras.RIM_INTENSITY, v).apply());
        int styleAt = java.util.Arrays.asList(Extras.CLOCK_STYLES).indexOf(Extras.clockStyle(this));
        stepper(home, getString(R.string.clock_style), getString(R.string.clock_style_hint), styles,
                Math.max(0, styleAt), i -> choices.edit().putString(Extras.CLOCK_STYLE, Extras.CLOCK_STYLES[i]).apply());

        // Wallpaper
        LinearLayout wallpaper = section(left, R.string.section_wallpaper);
        String[] dims = new String[DIM_STEPS.length];
        int dimAt = 0;
        for (int i = 0; i < DIM_STEPS.length; i++) {
            dims[i] = DIM_STEPS[i] + "%";
            if (DIM_STEPS[i] <= config.wallpaperDim) dimAt = i;
        }
        stepper(wallpaper, getString(R.string.settings_dim), getString(R.string.settings_dim_hint), dims, dimAt,
                i -> choices.edit().putInt(Extras.WALLPAPER_DIM, DIM_STEPS[i]).apply());

        // Liquid Glass
        // Liquid Glass and motion share one panel: the glass and how it moves are tuned together.
        LinearLayout glass = section(right, R.string.section_glass_motion);
        toggle(glass, getString(R.string.section_glass), getString(R.string.glass_hint), Extras.liquidGlass(this), on -> {
            choices.edit().putBoolean(Extras.LIQUID_GLASS, on).apply();
            enable(blurRow, on);
        });
        TextView[] zeroNote = {null};
        blurRow = slider(glass, getString(R.string.settings_blur), getString(R.string.settings_blur_hint),
                R.drawable.ic_blur, Extras.glassBlur(this), v -> {
                    choices.edit().putInt(Extras.GLASS_BLUR_PERCENT, v).apply();
                    if (zeroNote[0] != null) zeroNote[0].setVisibility(v == 0 ? View.VISIBLE : View.GONE);
                });
        // At 0 the glass is clear and its refraction is the whole effect: advanced effects keep it on while moving.
        zeroNote[0] = text(getString(R.string.settings_blur_zero_note), 12, R.font.outfit_medium, 0xFF64D2FF);
        zeroNote[0].setPadding(0, dp(8), 0, 0);
        zeroNote[0].setVisibility(Extras.glassBlur(this) == 0 ? View.VISIBLE : View.GONE);
        ((LinearLayout) blurRow).addView(zeroNote[0]);
        enable(blurRow, Extras.liquidGlass(this));

        // Motion, in the same panel as the glass
        LinearLayout motion = glass;
        boolean animations = config.animations;
        toggle(motion, getString(R.string.settings_animations), null, animations, on -> {
            choices.edit().putBoolean("animations", on).apply();
            if (!on) {
                choices.edit().putBoolean("advanced_effects", false).apply();
                advancedToggle.setChecked(false, true);
            }
            enable(advancedRow, on);
        });
        advancedRow = toggle(motion, getString(R.string.advanced_effects), getString(R.string.advanced_effects_warning),
                animations && config.advancedEffects, on -> choices.edit().putBoolean("advanced_effects", on).apply());
        advancedToggle = (GlassToggle) ((ViewGroup) advancedRow).getChildAt(1);
        enable(advancedRow, animations);

        // Thanks: how to tip the author, right under Motion where it is seen.
        thanks(right);

        // System
        LinearLayout system = section(left, R.string.section_system);
        screensaverRows(system);
        action(system, getString(R.string.home_settings), getString(R.string.settings_home_hint), () -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        action(system, getString(R.string.follow_theme), getString(R.string.follow_theme_hint), () -> {
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY).remove(Extras.WALLPAPER_DIM)
                    .commit();
            changed();
            Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_SHORT).show();
            recreate();
        });

        firstRow.requestFocus();
    }

    /** Where tips go: one address on BNB Smart Chain (BEP20) takes both BTC and USDT. */
    private static final String TIP_ADDRESS = "0x612bd350dd82edc6772b375a5c534540439a9ef7";

    /**
     * The thank-you panel under Motion: a line, the network, a yellow "send only on BEP20" warning, the address on two
     * lines (a column is too narrow for it on one) and its QR code for a phone. It takes focus so Down scrolls to it;
     * focused it turns white like every row, its text dark.
     */
    private void thanks(LinearLayout column) {
        LinearLayout panel = section(column, R.string.thanks_section);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(14), dp(18), dp(16));
        box.setBackgroundResource(R.drawable.input_row_bg);
        box.setFocusable(true);
        TextView title = text(getString(R.string.thanks_title), 20, R.font.outfit_medium, 0xFFFFFFFF);
        title.setTextColor(getColorStateList(R.drawable.pill_text));
        TextView network = text(getString(R.string.thanks_network), 13, R.font.outfit_regular, 0xFFFFFFFF);
        network.setTextColor(getColorStateList(R.drawable.pill_text_dim));
        network.setPadding(0, dp(4), 0, 0);
        // Yellow, and a darker amber on the focused (white) row so it stays readable.
        TextView warning = text(getString(R.string.thanks_warning), 14, R.font.outfit_medium, 0xFFFFD60A);
        warning.setTextColor(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}}, new int[]{0xFF9A6200, 0xFFFFD60A}));
        warning.setPadding(0, dp(8), 0, dp(10));
        int half = TIP_ADDRESS.length() - 15;   // split as Binance shows it: 0x + 25 characters, then 15
        TextView address = text(TIP_ADDRESS.substring(0, half) + "\n" + TIP_ADDRESS.substring(half), 17,
                R.font.outfit_medium, 0xFFFFFFFF);
        address.setTypeface(android.graphics.Typeface.MONOSPACE);
        address.setTextColor(getColorStateList(R.drawable.pill_text));
        for (TextView t : new TextView[]{title, network, warning, address}) {
            t.setDuplicateParentStateEnabled(true);
            box.addView(t);
        }
        android.graphics.Bitmap code = PhoneUpload.qr(TIP_ADDRESS, dp(130));
        if (code != null) {
            android.widget.ImageView qr = new android.widget.ImageView(this);
            qr.setImageBitmap(code);
            qr.setScaleType(android.widget.ImageView.ScaleType.CENTER);
            android.graphics.drawable.GradientDrawable white = new android.graphics.drawable.GradientDrawable();
            white.setColor(0xFFFFFFFF);
            white.setCornerRadius(dp(12));
            qr.setBackground(white);
            qr.setClipToOutline(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(code.getWidth(), code.getHeight());
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.topMargin = dp(14);
            box.addView(qr, lp);
        }
        panel.addView(box);
    }

    // ---- screensaver ---------------------------------------------------------------------------------

    private static final int[] SCREENSAVER_MINUTES = {1, 5, 10, 15, 30, 60};

    /**
     * Google TV has no Android screensaver picker (only Google's ambient mode, which the debloat turned off), so the
     * choice is made here: which installed screensaver, after how long, and a way into its own settings. Writing them
     * needs two grants given once over adb (WRITE_SECURE_SETTINGS and WRITE_SETTINGS); without them the rows say so.
     */
    private void screensaverRows(LinearLayout panel) {
        android.content.pm.PackageManager pm = getPackageManager();
        java.util.List<android.content.pm.ResolveInfo> found = pm.queryIntentServices(
                new Intent("android.service.dreams.DreamService"), 0);
        java.util.List<android.content.ComponentName> dreams = new java.util.ArrayList<>();
        java.util.List<String> names = new java.util.ArrayList<>();
        for (android.content.pm.ResolveInfo ri : found) {
            dreams.add(new android.content.ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name));
            names.add(ri.loadLabel(pm).toString());
        }
        boolean canWrite = checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED && Settings.System.canWrite(this);
        String current = Settings.Secure.getString(getContentResolver(), "screensaver_components");
        // The saved screensaver, matched exactly or, failing that, by its app: an update can rename the service (Aerial
        // Views moved .services.DreamActivity to .ui.screensaver.DreamActivity) and the saved name then starts nothing.
        int at = -1;
        android.content.ComponentName saved = current == null || current.isEmpty() ? null
                : android.content.ComponentName.unflattenFromString(current.split(",")[0]);
        for (int i = 0; i < dreams.size() && saved != null; i++) {
            if (dreams.get(i).equals(saved)) at = i;
        }
        if (at < 0 && saved != null) {
            for (int i = 0; i < dreams.size(); i++) {
                if (dreams.get(i).getPackageName().equals(saved.getPackageName())) at = i;
            }
            if (at >= 0 && canWrite) {
                String fixed = dreams.get(at).flattenToString();
                Settings.Secure.putString(getContentResolver(), "screensaver_components", fixed);
                Settings.Secure.putString(getContentResolver(), "screensaver_default_component", fixed);
            }
        }
        if (at < 0) at = 0;
        if (dreams.isEmpty()) {
            row(panel, getString(R.string.screensaver_settings), getString(R.string.screensaver_none), null);
            return;
        }
        View pick = stepper(panel, getString(R.string.screensaver_settings),
                canWrite ? null : getString(R.string.screensaver_needs_grant),
                names.toArray(new String[0]), at, i -> {
                    String c = dreams.get(i).flattenToString();
                    Settings.Secure.putString(getContentResolver(), "screensaver_components", c);
                    Settings.Secure.putString(getContentResolver(), "screensaver_default_component", c);
                    Settings.Secure.putInt(getContentResolver(), "screensaver_enabled", 1);
                });
        int minutes = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 600_000) / 60_000;
        String[] labels = new String[SCREENSAVER_MINUTES.length];
        int minAt = 0;
        for (int i = 0; i < SCREENSAVER_MINUTES.length; i++) {
            labels[i] = getString(R.string.online_minutes, SCREENSAVER_MINUTES[i]);
            if (SCREENSAVER_MINUTES[i] <= minutes) minAt = i;
        }
        View after = stepper(panel, getString(R.string.screensaver_after), null, labels, minAt,
                i -> Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT,
                        SCREENSAVER_MINUTES[i] * 60_000));
        enable(pick, canWrite);
        enable(after, canWrite);
        action(panel, getString(R.string.screensaver_configure), null, () -> {
            String now = Settings.Secure.getString(getContentResolver(), "screensaver_components");
            android.content.ComponentName chosen = now == null ? null
                    : android.content.ComponentName.unflattenFromString(now.split(",")[0]);
            Intent open = chosen == null ? null : pm.getLeanbackLaunchIntentForPackage(chosen.getPackageName());
            if (open == null && chosen != null) open = pm.getLaunchIntentForPackage(chosen.getPackageName());
            if (open == null || chosen.getPackageName().equals(getPackageName())) {
                Toast.makeText(this, R.string.screensaver_no_settings, Toast.LENGTH_LONG).show();
                return;
            }
            open(open);
        });
    }

    // ---- building blocks ----------------------------------------------------------------------------

    private int dp(float v) {
        return Math.round(v * density);
    }

    /**
     * The wallpaper, always heavily blurred whatever the glass blur is set to (at 0 Home's copy is nearly sharp, and
     * text over it would be hard to read): shrunk to a few dozen pixels and stretched back, under a dark veil.
     * Plain black without a wallpaper.
     */
    private Drawable backdrop() {
        Bitmap source = LiquidGlass.backdrop();
        if (source == null) return new ColorDrawable(0xFF000000);
        Bitmap blurred = Bitmap.createScaledBitmap(source, 48, 27, true);
        BitmapDrawable picture = new BitmapDrawable(getResources(), blurred);
        picture.setFilterBitmap(true);
        return new LayerDrawable(new Drawable[]{picture, new ColorDrawable(0xB3000000)});
    }

    private TextView text(String s, float sp, int font, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(getResources().getFont(font));
        return t;
    }

    /** One of the two columns, sharing the width equally; {@code gap} to the column before it. */
    private LinearLayout column(LinearLayout columns, int gap) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(gap);
        columns.addView(column, lp);
        return column;
    }

    /** The credit under the title: who made the launcher, and their X account with the X mark beside it. */
    private View credits() {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(android.view.Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(4), 0, 0);
        line.addView(text(getString(R.string.credits_made_by), 15, R.font.outfit_regular, 0xCCFFFFFF));
        android.widget.ImageView mark = new android.widget.ImageView(this);
        mark.setImageResource(R.drawable.ic_x_logo);
        mark.setContentDescription("X");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(20), dp(20));
        lp.setMarginStart(dp(14));
        lp.setMarginEnd(dp(7));
        line.addView(mark, lp);
        line.addView(text(getString(R.string.credits_x_handle), 15, R.font.outfit_medium, 0xFFFFFFFF));
        return line;
    }

    /** A section: its title in small spaced capitals, and the glass panel its rows go into. */
    private LinearLayout section(LinearLayout column, int title) {
        TextView heading = text(getString(title), 12, R.font.outfit_medium, 0x8CFFFFFF);
        heading.setAllCaps(true);
        heading.setLetterSpacing(0.2f);
        heading.setPadding(dp(4), dp(28), 0, dp(10));
        column.addView(heading);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(6), dp(6), dp(6), dp(6));
        // A light frosted slab over the blurred backdrop: Home's glass would sample its own copy of the wallpaper,
        // which can be sharp, and does not line up with this screen's.
        android.graphics.drawable.GradientDrawable slab = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x2EFFFFFF, 0x14FFFFFF});
        slab.setCornerRadius(dp(22));
        slab.setStroke(Math.max(1, dp(1)), 0x38FFFFFF);
        panel.setBackground(slab);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        column.addView(panel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return panel;
    }

    /** A focusable row: title (and a hint under it) on the left, {@code end} on the right; white while focused. */
    private LinearLayout row(LinearLayout panel, String title, String hint, View end) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(18), dp(10), dp(18), dp(10));
        row.setBackgroundResource(R.drawable.input_row_bg);
        row.setFocusable(true);
        row.setClickable(true);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setDuplicateParentStateEnabled(true);
        TextView t = text(title, 17, R.font.outfit_medium, 0xFFFFFFFF);
        t.setTextColor(getColorStateList(R.drawable.pill_text));
        t.setDuplicateParentStateEnabled(true);
        texts.addView(t);
        if (hint != null) {
            TextView h = text(hint, 12, R.font.outfit_regular, 0xFFFFFFFF);
            h.setTextColor(getColorStateList(R.drawable.pill_text_dim));
            h.setDuplicateParentStateEnabled(true);
            h.setPadding(0, dp(3), dp(24), 0);
            texts.addView(h);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (end != null) {
            end.setDuplicateParentStateEnabled(true);
            row.addView(end);
        }
        panel.addView(row);
        return row;
    }

    private View toggle(LinearLayout panel, String title, String hint, boolean on,
                        java.util.function.Consumer<Boolean> changed) {
        GlassToggle toggle = new GlassToggle(this);
        toggle.setChecked(on, false);
        LinearLayout row = row(panel, title, hint, toggle);
        row.setOnClickListener(v -> {
            if (!v.isEnabled()) return;
            toggle.setChecked(!toggle.isChecked(), true);
            changed.accept(toggle.isChecked());
            changed();
        });
        return row;
    }

    /**
     * "Title  value": Left and Right move focus like on any row, so the second column stays one press away. OK starts
     * editing: the value turns blue between arrows, Left and Right change it (stopping at the ends), and OK or Back
     * finish.
     */
    private View stepper(LinearLayout panel, String title, String hint, String[] values, int at,
                         java.util.function.IntConsumer changed) {
        TextView value = text("", 17, R.font.outfit_medium, 0xFFFFFFFF);
        value.setTextColor(getColorStateList(R.drawable.pill_text));
        int[] index = {Math.max(0, Math.min(values.length - 1, at))};
        boolean[] editing = {false};
        Runnable show = () -> {
            if (editing[0]) {
                value.setText("\u25C0  " + values[index[0]] + "  \u25B6");
                value.setTextColor(0xFF0A84FF);
            } else {
                value.setText(values[index[0]] + "   \u203A");
                value.setTextColor(getColorStateList(R.drawable.pill_text));
            }
        };
        show.run();
        LinearLayout row = row(panel, title, hint, value);
        java.util.function.IntConsumer select = next -> {
            if (next == index[0]) return;
            index[0] = next;
            show.run();
            value.setScaleX(0.88f);
            value.setScaleY(0.88f);
            value.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            changed.accept(next);
            changed();
        };
        Runnable toggle = () -> {
            editing[0] = !editing[0];
            show.run();
        };
        row.setOnClickListener(v -> {
            if (v.isEnabled()) toggle.run();
        });
        row.setOnFocusChangeListener((v, has) -> {
            if (!has && editing[0]) toggle.run();
        });
        row.setOnKeyListener((v, code, event) -> {
            if (!editing[0]) return false;
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            if (code == KeyEvent.KEYCODE_BACK) {
                if (!down) toggle.run();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down) select.accept(Math.max(0, Math.min(values.length - 1,
                        index[0] + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1))));
                return true;
            }
            // Up and Down stay put while editing, so the value cannot be left half-chosen.
            return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
        });
        return row;
    }

    /**
     * A level: its title and hint, then the glass bar of the night mode panel under them. The bar itself takes focus;
     * Left and Right move it by 5.
     */
    private View slider(LinearLayout panel, String title, String hint, int icon, int value,
                        java.util.function.IntConsumer changed) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(10), dp(18), dp(12));
        box.setClipChildren(false);
        box.setClipToPadding(false);
        box.addView(text(title, 17, R.font.outfit_medium, 0xFFFFFFFF));
        if (hint != null) {
            TextView h = text(hint, 12, R.font.outfit_regular, 0xA6FFFFFF);
            h.setPadding(0, dp(3), 0, 0);
            box.addView(h);
        }
        GlassSlider bar = new GlassSlider(this, null);
        bar.setNeedsEdit(true);
        bar.setIcon(icon);
        bar.setValue(value);
        bar.setOnChange(v -> {
            changed.accept(v);
            changed();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(8);
        box.addView(bar, lp);
        panel.addView(box);
        return box;
    }

    private void action(LinearLayout panel, String title, String hint, Runnable run) {
        TextView chevron = text("›", 22, R.font.outfit_light, 0xFFFFFFFF);
        chevron.setTextColor(getColorStateList(R.drawable.pill_text_dim));
        row(panel, title, hint, chevron).setOnClickListener(v -> run.run());
    }

    /** A row that depends on a switch above it: dimmed and inert while that switch is off. */
    private static void enable(View row, boolean on) {
        row.setEnabled(on);
        row.setAlpha(on ? 1f : 0.4f);
        // A slider box: its bar is what takes focus, so that is what must stop taking it.
        if (row instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) row;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (group.getChildAt(i) instanceof GlassSlider) group.getChildAt(i).setFocusable(on);
            }
        }
    }

    /** Home rebuilds with the change: by result when we finish, and by revision when Home is reached another way. */
    private void changed() {
        Extras.bumpRevision(this);
        setResult(RESULT_OK);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
