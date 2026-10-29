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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The widgets row under the clock: the market card, the now playing card and the Continue watching cards, in the
 * order chosen in the Widgets menu, in one row that slides sideways when they do not fit. Home calls start() from
 * onResume and stop() from onPause, so the price is fetched and the media sessions are followed only while Home is
 * on screen.
 */
final class HomeWidgets {
    /** Tags of the views Home's focus light goes over: a widget card (22dp corners) or a round glass button. */
    static final String LIT_CARD = "lit_card";
    static final String LIT_PILL = "lit_pill";
    private static final int UP_COLOR = 0xFF34C759;
    private static final int DOWN_COLOR = 0xFFFF453A;

    private final Activity activity;
    private final android.widget.LinearLayout row;
    /** The row's sideways scroller: shown when any card is. */
    private final android.widget.HorizontalScrollView scroll;
    private final ContinueRow continueRow;
    /** The stop of the row focused last, where Up from the dock and Down from the top bar come back to. */
    private View lastStop;
    /** Told on every frame the row slides, so Home's focus light can ride along with the focused card. */
    private Runnable rowScrolled;
    private final View btcCard;
    private final View playerCard;
    private final float density;
    private final boolean btcOn;
    private final boolean playerOn;
    private final Runnable focusDock;
    private final Runnable focusTopBar;
    /** OK on the market card: Home opens its menu (asset and chart). */
    private Runnable onMarketMenu;
    private MarketTicker ticker;
    private java.util.List<MarketTicker.Asset> assets;
    /** Which of the picked assets is on the card. */
    private int shown;
    private boolean active;
    private static final long ROTATE_MS = 8000;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
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

    /** Advanced effects: the cards' glass is redrawn live while they zoom; otherwise cached, with a fade. */
    private final boolean advanced;
    private final boolean liquidGlass;

    HomeWidgets(Activity activity, float density, boolean liquidGlass, boolean advanced, Runnable focusDock,
                Runnable focusTopBar) {
        this.advanced = advanced;
        this.liquidGlass = liquidGlass;
        this.activity = activity;
        this.focusDock = focusDock;
        this.focusTopBar = focusTopBar;
        row = activity.findViewById(R.id.widgets);
        scroll = activity.findViewById(R.id.widgetsScroll);
        // Cards of different heights (Continue watching has its X under it) line up at the top.
        if (row != null) row.setBaselineAligned(false);
        continueRow = new ContinueRow(activity, density, liquidGlass, advanced, new ContinueRow.Host() {
            @Override
            public void step(View from, int direction) {
                HomeWidgets.this.step(from, direction);
            }

            @Override
            public void up() {
                focusTopBar.run();
            }

            @Override
            public void down() {
                focusDock.run();
            }
        });
        btcCard = activity.findViewById(R.id.btcCard);
        playerCard = activity.findViewById(R.id.playerCard);
        this.density = density;
        btcOn = row != null && Extras.btcTicker(activity);
        playerOn = row != null && Extras.nowPlaying(activity);
        if (row == null) return;

        float radius = 22 * density;
        // Both cards take focus: the same brighter glass while focused.
        for (View card : new View[]{btcCard, playerCard}) {
            if (liquidGlass) {
                // The row draws the wallpaper sample under the card (GlassRow); the rest of the glass and the content
                // stay in the card's cached layer, only moved while the row slides.
                card.setBackground(LiquidGlass.rowGlass(radius, density, true));
                card.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            } else {
                StateListDrawable d = new StateListDrawable();
                d.addState(new int[]{android.R.attr.state_focused}, flat(radius, 0x66FFFFFF, density));
                d.addState(new int[0], flat(radius, 0x33FFFFFF, density));
                card.setBackground(d);
            }
        }
        btcCard.setOnFocusChangeListener((v, focused) -> focusCard(v, focused));
        // The glass samples the wallpaper behind each card at its place on screen, so the row redraws it on every
        // frame of a slide: the plain blur meanwhile (LiquidGlass.sliding), the refraction where it stops.
        ((GlideScrollView) scroll).setOnGlide(() -> sliding(true), () -> sliding(false));
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            row.invalidate();
            if (rowScrolled != null) rowScrolled.run();
        });
        btcCard.setOnKeyListener((v, keyCode, event) -> onMarketKey(keyCode, event));
        // A soft shadow under the widgets' text, so green and red figures stay legible over a bright wallpaper
        // without darkening the glass itself.
        for (int id : new int[]{R.id.btcPair, R.id.btcPrice, R.id.btcChange, R.id.playerKicker, R.id.playerTitle,
                R.id.playerArtist}) {
            ((TextView) activity.findViewById(id)).setShadowLayer(4 * density, 0, density, 0xCC000000);
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
            focusCard(v, focused);
            if (focused) lastStop = v;
            // Entering the card always starts on play/pause; leaving it shows no selection.
            if (focused) selected = TOGGLE;
            showSelection(focused);
        });
        playerCard.setOnKeyListener((v, keyCode, event) -> onPlayerKey(keyCode, event));

        if (btcOn) newTicker();
        if (playerOn) nowPlaying = new NowPlaying(activity, this::showSession);
    }

    /**
     * A card taking or losing focus. Advanced effects: it zooms, and its glass is redrawn every frame of the zoom so
     * the wallpaper behind it stays in place. Otherwise it does not zoom (a cached layer would show its old backdrop
     * stretched); the brighter glass fades in instead.
     */
    private void focusCard(View v, boolean focused) {
        if (focused) lastStop = v;
        if (!advanced) return;   // the background's own cross-fade is the whole animation
        v.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f).setDuration(140)
                .setUpdateListener(a -> row.invalidate()).start();
    }

    private static GradientDrawable flat(float radius, int color, float density) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setStroke(Math.round(density), 0x33FFFFFF);
        d.setCornerRadius(radius);
        return d;
    }

    /** A slide starts or stops: see the constructor. */
    private void sliding(boolean on) {
        LiquidGlass.sliding = on;
        row.invalidate();
    }

    /** Tells the row which views to draw glass under: the cards, and the X buttons of Continue watching. */
    private void updateGlass() {
        if (!(row instanceof GlassRow) || !liquidGlass) return;
        List<View> cards = new ArrayList<>();
        cards.add(btcCard);
        cards.add(playerCard);
        cards.addAll(continueRow.cardViews());
        ((GlassRow) row).setGlass(cards, continueRow.buttonViews());
    }

    void setOnRowScrolled(Runnable action) {
        rowScrolled = action;
    }

    void setOnMarketMenu(Runnable action) {
        onMarketMenu = action;
    }

    View marketCard() {
        return btcCard;
    }

    boolean marketShown() {
        return btcCard != null && btcCard.getVisibility() == View.VISIBLE;
    }

    void focusMarket() {
        if (marketShown()) btcCard.requestFocus();
    }

    /** The assets or the chart setting changed: show the first at once (its last price if known) and fetch them. */
    void reloadMarket() {
        if (!btcOn) return;
        boolean running = ticker != null && active;
        if (ticker != null) ticker.stop();
        main.removeCallbacks(rotate);
        newTicker();
        if (running) startMarket();
    }

    private void newTicker() {
        assets = Extras.marketAssets(activity);
        shown = 0;
        ticker = new MarketTicker(assets, this::onQuote);
        showAsset(assets.get(0), false);
    }

    private void startMarket() {
        ticker.start();
        if (assets.size() > 1) main.postDelayed(rotate, ROTATE_MS);
    }

    /** Several picked assets: every few seconds the card fades to the next one. */
    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            if (!active || assets.size() < 2) return;
            shown = (shown + 1) % assets.size();
            showAsset(assets.get(shown), true);
            main.postDelayed(this, ROTATE_MS);
        }
    };

    private void onQuote(MarketTicker.Quote q) {
        if (assets.get(shown).id.equals(q.asset.id)) showQuote(q);
    }

    /** Puts {@code asset} on the card: its badge and name, and its last price, or a dash until one arrives. */
    private void showAsset(MarketTicker.Asset asset, boolean fade) {
        android.view.ViewGroup card = (android.view.ViewGroup) btcCard;
        View content = card.getChildAt(0), spark = card.getChildAt(1);
        Runnable swap = () -> {
            TextView badge = activity.findViewById(R.id.btcBadge);
            badge.setText(asset.badge);
            badge.setTextSize(asset.badge.length() > 1 ? 8f : 11f);
            badge.getBackground().mutate().setTint(asset.badgeColor);
            ((TextView) activity.findViewById(R.id.btcPair)).setText(asset.label);
            MarketTicker.Quote last = MarketTicker.last(asset);
            if (last != null) {
                showQuote(last);
            } else {
                ((TextView) activity.findViewById(R.id.btcPrice)).setText("\u2014");
                ((TextView) activity.findViewById(R.id.btcChange)).setText("");
                spark.setVisibility(Extras.marketChart(activity) ? View.INVISIBLE : View.GONE);
            }
        };
        if (!fade) {
            swap.run();
            return;
        }
        for (View v : new View[]{content, spark}) v.animate().cancel();
        content.animate().alpha(0f).translationY(-6 * activity.getResources().getDisplayMetrics().density)
                .setDuration(180).withEndAction(() -> {
                    swap.run();
                    content.setTranslationY(6 * activity.getResources().getDisplayMetrics().density);
                    content.animate().alpha(1f).translationY(0f).setDuration(220).start();
                    spark.setAlpha(0f);
                    spark.animate().alpha(1f).setDuration(260).start();
                }).start();
        spark.animate().alpha(0f).setDuration(180).start();
    }

    /** OK opens the market menu; Right goes to the player, Down to the dock, Up to the top bar. */
    private boolean onMarketKey(int keyCode, KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!down && onMarketMenu != null) onMarketMenu.run();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) step(btcCard, keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
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

    void start() {
        if (row == null) return;
        btcCard.setVisibility(btcOn ? View.VISIBLE : View.GONE);
        active = true;
        if (ticker != null) startMarket();
        if (nowPlaying != null) nowPlaying.start();
        continueRow.start();
        applyOrder();
        updateRow();
        updateGlass();
    }

    void stop() {
        active = false;
        main.removeCallbacks(rotate);
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
        row.invalidate();
        btcCard.invalidate();
        playerCard.invalidate();
        continueRow.invalidate();
    }

    boolean rowShown() {
        return row != null && scroll.getVisibility() == View.VISIBLE && !stops().isEmpty();
    }

    /** Into the row from above or below: the card focused last, else the first. */
    void focusRow() {
        List<View> stops = stops();
        if (stops.isEmpty()) return;
        (lastStop != null && stops.contains(lastStop) ? lastStop : stops.get(0)).requestFocus();
    }

    /** What takes focus in the row, left to right: the shown cards, and each Continue watching card's X. */
    private List<View> stops() {
        List<View> stops = new ArrayList<>();
        if (row == null) return stops;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child == continueRow.view()) stops.addAll(continueRow.stops());
            else stops.add(child);
        }
        return stops;
    }

    /** Left or Right along the row; the scroller brings the next card into view. At the ends nothing moves. */
    private void step(View from, int direction) {
        List<View> stops = stops();
        int at = stops.indexOf(from) + direction;
        if (at < 0 || at >= stops.size()) return;
        View next = stops.get(at);
        next.requestFocus();
        lastStop = next;
        from.playSoundEffect(direction > 0 ? android.view.SoundEffectConstants.NAVIGATION_RIGHT
                : android.view.SoundEffectConstants.NAVIGATION_LEFT);
    }

    /** The cards in the order chosen in the Widgets menu. */
    private void applyOrder() {
        List<View> wanted = new ArrayList<>();
        for (String w : Extras.widgetOrder(activity)) {
            wanted.add(w.equals("market") ? btcCard : w.equals("player") ? playerCard : continueRow.view());
        }
        boolean same = true;
        for (int i = 0; i < wanted.size(); i++) same &= row.getChildAt(i) == wanted.get(i);
        if (same) return;
        for (View v : wanted) row.removeView(v);
        for (View v : wanted) row.addView(v);
    }

    private void updateRow() {
        boolean any = false;
        // The gap goes before every shown card but the first, whatever the order.
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) child.getLayoutParams();
            int gap = any ? Math.round(14 * density) : 0;
            if (lp.getMarginStart() != gap) {
                lp.setMarginStart(gap);
                child.setLayoutParams(lp);
            }
            any = true;
        }
        row.setVisibility(any ? View.VISIBLE : View.GONE);
        scroll.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    // ---- Market ------------------------------------------------------------------------------------

    private void showQuote(MarketTicker.Quote q) {
        NumberFormat number = q.asset.dollars ? NumberFormat.getCurrencyInstance(Locale.US)
                : NumberFormat.getNumberInstance(Locale.US);
        // Thousands without cents; coins under a cent (SHIB, PEPE) with enough decimals to move.
        int decimals = q.price >= 1000 ? (q.asset.dollars ? 0 : 2) : q.price >= 1 ? 2 : q.price >= 0.01 ? 4 : 8;
        number.setMinimumFractionDigits(q.asset.dollars ? Math.min(decimals, 2) : decimals);
        number.setMaximumFractionDigits(decimals);
        ((TextView) activity.findViewById(R.id.btcPrice)).setText(number.format(q.price));
        boolean up = q.changePercent >= 0;
        TextView change = activity.findViewById(R.id.btcChange);
        String period = activity.getString(q.asset.source == MarketTicker.Source.BINANCE
                ? R.string.market_period_24h : R.string.market_period_today);
        change.setText(String.format(Locale.US, "%s %.2f%%  \u00B7  %s", up ? "\u25B2" : "\u25BC",
                Math.abs(q.changePercent), period));
        change.setTextColor(up ? UP_COLOR : DOWN_COLOR);
        SparklineView spark = activity.findViewById(R.id.btcSpark);
        // The chart keeps its place while an asset has no history yet, so the card does not change size.
        boolean chart = Extras.marketChart(activity);
        spark.setVisibility(!chart ? View.GONE : q.history != null ? View.VISIBLE : View.INVISIBLE);
        if (chart && q.history != null) spark.setValues(q.history, up ? UP_COLOR : DOWN_COLOR);
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
        if (!had) playerCard.animate().alpha(1f).setDuration(220).setUpdateListener(a -> row.invalidate()).start();
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
                    if (next < PREV || next > NEXT) {
                        step(playerCard, next < PREV ? -1 : 1);
                        return true;
                    }
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
