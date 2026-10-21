package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.session.MediaSessionManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Remote-friendly settings owned by the launcher, in sections: Apps, Home screen, Wallpaper, Liquid Glass,
 * Widgets, Motion and System. System settings stay one row away.
 */
public final class LauncherSettingsActivity extends Activity {
    /** Result extra: something Home should do once it is back in front. */
    static final String EXTRA_THEN = "then";
    static final String THEN_REORDER = "reorder";
    static final String THEN_DOCK_EDIT = "dock_edit";

    private static final int[] DIM_STEPS = {0, 20, 40, 60};

    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private TextView wallpaperChoice;
    private TextView wallpaperDimChoice;
    private TextView liquidGlassChoice;
    private TextView glassBlurChoice;
    private TextView btcChoice;
    private TextView nowPlayingChoice;
    private TextView nowPlayingHint;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private int columns;
    private int rowsBelowDock;
    private int wallpaperDim;
    private boolean animations;
    private boolean advancedEffects;
    /** Something changed that Home must rebuild for. */
    private boolean changed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        wallpaperDim = config.wallpaperDim;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        findViewById(R.id.reorderAppsChoice).setOnClickListener(v -> finishThen(THEN_REORDER));

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                markChanged();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                markChanged();
            });
        }
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
        }

        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        wallpaperDimChoice = findViewById(R.id.wallpaperDimChoice);
        wallpaperDimChoice.setOnClickListener(v -> {
            wallpaperDim = nextStep(DIM_STEPS, wallpaperDim);
            choices.edit().putInt(Extras.WALLPAPER_DIM, wallpaperDim).apply();
            markChanged();
        });

        liquidGlassChoice = findViewById(R.id.liquidGlassChoice);
        liquidGlassChoice.setOnClickListener(v -> toggle(Extras.LIQUID_GLASS, Extras.liquidGlass(this)));
        glassBlurChoice = findViewById(R.id.glassBlurChoice);
        glassBlurChoice.setOnClickListener(v -> {
            choices.edit().putInt(Extras.GLASS_BLUR, (Extras.glassBlur(this) + 1) % 3).apply();
            markChanged();
        });

        btcChoice = findViewById(R.id.btcChoice);
        btcChoice.setOnClickListener(v -> toggle(Extras.BTC_TICKER, Extras.btcTicker(this)));
        nowPlayingChoice = findViewById(R.id.nowPlayingChoice);
        nowPlayingChoice.setOnClickListener(v -> toggle(Extras.NOW_PLAYING, Extras.nowPlaying(this)));
        nowPlayingHint = findViewById(R.id.nowPlayingHint);

        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            markChanged();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });

        findViewById(R.id.screensaverChoice).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_DREAM_SETTINGS)));
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_SETTINGS)));
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY).remove(Extras.WALLPAPER_DIM)
                    .commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            wallpaperDim = current.wallpaperDim;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            markChanged();
        });
        refresh();
        findViewById(R.id.reorderAppsChoice).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();   // the notification listener grant may have changed while we were away
    }

    private void finishThen(String action) {
        Intent data = new Intent().putExtra(EXTRA_THEN, action);
        setResult(changed ? RESULT_OK : RESULT_CANCELED, data);
        finish();
    }

    private void markChanged() {
        changed = true;
        // Home also checks this when it comes back by the Home key, which finishes us without a result.
        Extras.bumpRevision(this);
        setResult(RESULT_OK);
        refresh();
    }

    private void toggle(String key, boolean current) {
        choices.edit().putBoolean(key, !current).apply();
        markChanged();
    }

    private static int nextStep(int[] steps, int current) {
        for (int step : steps) if (step > current) return step;
        return steps[0];
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    /** Built-in wallpapers first, then the theme's pictures, then None (WallpaperChoices). */
    private void showWallpaperPicker() {
        List<WallpaperChoices.Choice> all = WallpaperChoices.all(this);
        String[] labels = new String[all.size()];
        int checked = -1;
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        for (int i = 0; i < all.size(); i++) {
            labels[i] = all.get(i).label;
            if (all.get(i).value.equals(current)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, all.get(which).value).apply();
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    markChanged();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    markChanged();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        markChanged();
    }

    private String onOff(boolean on) {
        return getString(on ? R.string.on : R.string.off);
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                WallpaperChoices.labelOf(this, choices.getString(Config.WALLPAPER_CHOICE_KEY, null))));
        wallpaperDimChoice.setText(getString(R.string.wallpaper_dim_state, wallpaperDim));

        boolean glass = Extras.liquidGlass(this);
        liquidGlassChoice.setText(getString(R.string.liquid_glass_state, onOff(glass)));
        int blur = Extras.glassBlur(this);
        glassBlurChoice.setText(getString(R.string.glass_blur_state, getString(
                blur == Extras.BLUR_SOFT ? R.string.blur_soft : blur == Extras.BLUR_STRONG ? R.string.blur_strong
                        : R.string.blur_medium)));
        glassBlurChoice.setEnabled(glass);
        glassBlurChoice.setAlpha(glass ? 1f : 0.5f);

        btcChoice.setText(getString(R.string.btc_widget_state, onOff(Extras.btcTicker(this))));
        nowPlayingChoice.setText(getString(R.string.now_playing_state, onOff(Extras.nowPlaying(this))));
        nowPlayingHint.setVisibility(mediaAccess() ? View.GONE : View.VISIBLE);

        animationsChoice.setText(getString(R.string.animations_state, onOff(animations)));
        advancedChoice.setText(getString(R.string.advanced_effects_state, onOff(advancedEffects)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    /** Whether the notification listener grant that the now playing card needs is in place. */
    private boolean mediaAccess() {
        MediaSessionManager m = getSystemService(MediaSessionManager.class);
        if (m == null) return false;
        try {
            m.getActiveSessions(new ComponentName(this, MediaListener.class));
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Extras.WALLPAPER_DIM);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
