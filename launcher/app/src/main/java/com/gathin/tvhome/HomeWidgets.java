package com.gathin.tvhome;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * The widgets row under the clock: the BTC/USDT card and the now playing card. Home calls start() from
 * onResume and stop() from onPause, so the price is fetched and the media sessions are followed only while
 * Home is on screen. Layouts without the row (the Hebrew one) get an inert instance.
 */
final class HomeWidgets {
    private static final int UP_COLOR = 0xFF34C759;
    private static final int DOWN_COLOR = 0xFFFF453A;

    private final Activity activity;
    private final View row;
    private final View btcCard;
    private final View playerCard;
    private final boolean btcOn;
    private final boolean playerOn;
    private final Runnable focusDock;
    private final Runnable focusTopBar;
    private CryptoTicker ticker;
    private NowPlaying nowPlaying;
    private boolean okDown;
    private boolean okHandled;
    /** The selected player button while the card has focus: PREV, TOGGLE or NEXT. */
    private int selected = TOGGLE;
    private static final int PREV = 0, TOGGLE = 1, NEXT = 2;
    private static final int[] BUTTONS = {R.id.playerPrev, R.id.playerToggle, R.id.playerNext};
    /** The cover on screen, kept while a new track's metadata arrives before its art (no blank flash). */
    private Bitmap shownArt;
    private String shownArtPackage;

    HomeWidgets(Activity activity, float density, boolean liquidGlass, Runnable focusDock, Runnable focusTopBar) {
        this.activity = activity;
        this.focusDock = focusDock;
        this.focusTopBar = focusTopBar;
        row = activity.findViewById(R.id.widgets);
        btcCard = activity.findViewById(R.id.btcCard);
        playerCard = activity.findViewById(R.id.playerCard);
        btcOn = row != null && Extras.btcTicker(activity);
        playerOn = row != null && Extras.nowPlaying(activity);
        if (row == null) return;

        float radius = 22 * density;
        btcCard.setBackground(liquidGlass ? LiquidGlass.surface(radius, density) : flat(radius, 0x33FFFFFF, density));
        if (liquidGlass) {
            playerCard.setBackground(LiquidGlass.focusableSurface(radius, density));
        } else {
            StateListDrawable d = new StateListDrawable();
            d.addState(new int[]{android.R.attr.state_focused}, flat(radius, 0x66FFFFFF, density));
            d.addState(new int[0], flat(radius, 0x33FFFFFF, density));
            playerCard.setBackground(d);
        }
        ImageView art = activity.findViewById(R.id.playerArt);
        float artRadius = 12 * density;
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), artRadius);
            }
        });
        art.setClipToOutline(true);
        playerCard.setOnFocusChangeListener((v, focused) -> {
            v.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f).setDuration(160).start();
            // Entering the card always starts on play/pause; leaving it shows no selection.
            if (focused) selected = TOGGLE;
            showSelection(focused);
        });
        playerCard.setOnKeyListener((v, keyCode, event) -> onPlayerKey(keyCode, event));

        if (btcOn) {
            ticker = new CryptoTicker(this::showQuote);
            CryptoTicker.Quote last = CryptoTicker.last();
            if (last != null) showQuote(last);
        }
        if (playerOn) nowPlaying = new NowPlaying(activity, this::showSession);
    }

    private static GradientDrawable flat(float radius, int color, float density) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setStroke(Math.round(density), 0x33FFFFFF);
        d.setCornerRadius(radius);
        return d;
    }

    void start() {
        if (row == null) return;
        btcCard.setVisibility(btcOn ? View.VISIBLE : View.GONE);
        if (ticker != null) ticker.start();
        if (nowPlaying != null) nowPlaying.start();
        updateRow();
    }

    void stop() {
        if (ticker != null) ticker.stop();
        if (nowPlaying != null) nowPlaying.stop();
    }

    boolean playerShown() {
        return playerCard != null && playerCard.getVisibility() == View.VISIBLE;
    }

    void focusPlayer() {
        if (playerShown()) playerCard.requestFocus();
    }

    /** Repaints the glass cards, after the blurred backdrop behind them changed. */
    void invalidate() {
        if (row == null) return;
        btcCard.invalidate();
        playerCard.invalidate();
    }

    private void updateRow() {
        boolean any = btcCard.getVisibility() == View.VISIBLE || playerCard.getVisibility() == View.VISIBLE;
        row.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    // ---- BTC ---------------------------------------------------------------------------------------

    private void showQuote(CryptoTicker.Quote q) {
        NumberFormat money = NumberFormat.getCurrencyInstance(Locale.US);
        money.setMaximumFractionDigits(q.price >= 1000 ? 0 : 2);
        ((TextView) activity.findViewById(R.id.btcPrice)).setText(money.format(q.price));
        ((TextView) activity.findViewById(R.id.btcPair)).setText(q.pair);
        boolean up = q.changePercent >= 0;
        TextView change = activity.findViewById(R.id.btcChange);
        change.setText(String.format(Locale.US, "%s %.2f%%  ·  24 h", up ? "▲" : "▼", Math.abs(q.changePercent)));
        change.setTextColor(up ? UP_COLOR : DOWN_COLOR);
        SparklineView spark = activity.findViewById(R.id.btcSpark);
        spark.setVisibility(q.history != null ? View.VISIBLE : View.GONE);
        if (q.history != null) spark.setValues(q.history, up ? UP_COLOR : DOWN_COLOR);
    }

    // ---- Now playing -------------------------------------------------------------------------------

    private void showSession(MediaController c) {
        if (row == null) return;
        boolean had = playerShown();
        if (c == null || c.getMetadata() == null || c.getPlaybackState() == null) {
            boolean wasFocused = playerCard.hasFocus();
            playerCard.setVisibility(View.GONE);
            updateRow();
            if (wasFocused) focusDock.run();
            return;
        }
        MediaMetadata m = c.getMetadata();
        String title = m.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = firstNonEmpty(m.getString(MediaMetadata.METADATA_KEY_ARTIST),
                m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST), m.getString(MediaMetadata.METADATA_KEY_ALBUM));
        ((TextView) activity.findViewById(R.id.playerTitle)).setText(title);
        TextView artistView = activity.findViewById(R.id.playerArtist);
        artistView.setText(artist);
        artistView.setVisibility(artist == null ? View.GONE : View.VISIBLE);
        boolean playing = NowPlaying.isPlaying(c.getPlaybackState().getState());
        String app = appLabel(c.getPackageName());
        ((TextView) activity.findViewById(R.id.playerKicker)).setText(activity.getString(
                playing ? R.string.now_playing_from : R.string.paused_from, app));
        ((ImageView) activity.findViewById(R.id.playerToggle)).setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);

        ImageView art = activity.findViewById(R.id.playerArt);
        Bitmap bitmap = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bitmap == null) bitmap = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (bitmap == null) bitmap = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        // Apps send a new track's title first and its cover a moment later: keep the old cover until then.
        if (bitmap == null && shownArt != null && c.getPackageName().equals(shownArtPackage)) bitmap = shownArt;
        if (bitmap != null) {
            if (bitmap != shownArt && !(shownArt != null && bitmap.sameAs(shownArt))) {
                art.setImageBitmap(bitmap);
                if (shownArt != null) {
                    art.setAlpha(0.4f);
                    art.animate().alpha(1f).setDuration(260).start();
                }
            }
            shownArt = bitmap;
            shownArtPackage = c.getPackageName();
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setBackground(null);
            art.setPadding(0, 0, 0, 0);
        } else {
            shownArt = null;
            art.setImageResource(R.drawable.ic_music);
            art.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            art.setBackgroundColor(0x33FFFFFF);
            int pad = Math.round(16 * activity.getResources().getDisplayMetrics().density);
            art.setPadding(pad, pad, pad, pad);
        }
        playerCard.setVisibility(View.VISIBLE);
        updateRow();
        if (!had) playerCard.setAlpha(0f);
        if (!had) playerCard.animate().alpha(1f).setDuration(220).start();
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v;
        return null;
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = activity.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private void showSelection(boolean focused) {
        for (int i = 0; i < BUTTONS.length; i++) {
            activity.findViewById(BUTTONS[i]).setSelected(focused && i == selected);
        }
    }

    /**
     * Left and Right move the selection between previous, play/pause and next; OK presses the selected button
     * (a long OK opens the app); Up and Down leave the card. Nothing skips until OK confirms it.
     */
    private boolean onPlayerKey(int keyCode, KeyEvent event) {
        MediaController c = nowPlaying != null ? nowPlaying.current() : null;
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (down) {
                    if (event.getRepeatCount() == 0) {
                        okDown = true;
                        okHandled = false;
                    } else if (okDown && !okHandled && event.getRepeatCount() >= 2) {
                        okHandled = true;
                        openApp(c);
                    }
                } else {
                    if (okDown && !okHandled) press(c);
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {
                    int next = selected + (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    if (next >= PREV && next <= NEXT) {
                        selected = next;
                        showSelection(true);
                        playerCard.playSoundEffect(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                                ? android.view.SoundEffectConstants.NAVIGATION_RIGHT
                                : android.view.SoundEffectConstants.NAVIGATION_LEFT);
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) focusDock.run();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) focusTopBar.run();
                return true;
            default:
                return false;
        }
    }

    private void press(MediaController c) {
        if (c == null || c.getPlaybackState() == null) return;
        if (selected == PREV) {
            c.getTransportControls().skipToPrevious();
        } else if (selected == NEXT) {
            c.getTransportControls().skipToNext();
        } else if (NowPlaying.isPlaying(c.getPlaybackState().getState())) {
            c.getTransportControls().pause();
        } else {
            c.getTransportControls().play();
        }
        pulse(BUTTONS[selected]);
    }

    private void pulse(int id) {
        View v = activity.findViewById(id);
        v.animate().cancel();
        v.setScaleX(0.8f);
        v.setScaleY(0.8f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
    }

    private void openApp(MediaController c) {
        if (c == null) return;
        PendingIntent session = c.getSessionActivity();
        try {
            if (session != null) {
                session.send();
                return;
            }
        } catch (PendingIntent.CanceledException ignored) {
            // fall back to the app's own entry point
        }
        PackageManager pm = activity.getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(c.getPackageName());
        if (launch == null) launch = pm.getLaunchIntentForPackage(c.getPackageName());
        if (launch == null) return;
        try {
            activity.startActivity(launch);
        } catch (RuntimeException ignored) {
            // the app went away while its session was still listed
        }
    }
}
