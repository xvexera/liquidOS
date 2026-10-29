package com.gathin.tvhome;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The Continue watching cards, in the widgets row (HomeWidgets places and orders them): the newest videos of
 * WatchHistory, each with its picture, title, how far it got and an X that takes it off. OK on a card opens the video
 * again, at the same point where the app allows it. Rebuilt in start() from onResume, so a video paused a moment ago
 * is there.
 */
final class ContinueRow {
    /** The widgets row: moves focus along it and out of it. */
    interface Host {
        /** Left (-1) or Right (+1) from {@code from} to the next stop of the whole row. */
        void step(View from, int direction);

        void up();

        void down();
    }

    private static final int MAX_CARDS = 6;
    /** Apps that open https://www.youtube.com/watch links themselves, with the start time. */
    private static final String[] YOUTUBE = {WatchHistory.YOUTUBE_TV, "com.google.android.youtube.tvkids",
            "com.google.android.youtube.tvunplugged", "org.smarttube.stable", "org.smarttube.beta",
            "com.liskovsoft.smarttubetv", "com.liskovsoft.smarttubetv.beta", "com.teamsmart.videomanager.tv"};

    private final Activity activity;
    private final LinearLayout row;
    private final float density;
    private final boolean liquidGlass;
    private final boolean advanced;
    private final Host host;
    private final List<View> cards = new ArrayList<>();
    /** Every focusable of the cards in order: each card, then its X. */
    private final List<View> stops = new ArrayList<>();

    ContinueRow(Activity activity, float density, boolean liquidGlass, boolean advanced, Host host) {
        this.activity = activity;
        this.row = activity.findViewById(R.id.continueRow);
        this.density = density;
        this.liquidGlass = liquidGlass;
        this.advanced = advanced;
        this.host = host;
    }

    View view() {
        return row;
    }

    boolean shown() {
        return row != null && !cards.isEmpty();
    }

    List<View> stops() {
        return stops;
    }

    /** Repaints the glass cards, after the blurred backdrop behind them (or their place on screen) changed. */
    void invalidate() {
        for (View c : cards) c.invalidate();
    }

    void start() {
        if (row == null) return;
        row.removeAllViews();
        cards.clear();
        stops.clear();
        if (Extras.continueWatching(activity)) {
            for (WatchHistory.Entry e : WatchHistory.load(activity)) {
                if (cards.size() == MAX_CARDS) break;
                if (!installed(e.pkg) || !WatchHistory.appOn(activity, e.pkg)) continue;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(300), dp(84));
                if (!cards.isEmpty()) lp.setMarginStart(dp(14));
                row.addView(card(e), lp);
            }
        }
        row.setVisibility(cards.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private View card(WatchHistory.Entry e) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPaddingRelative(dp(12), 0, dp(14), 0);
        card.setFocusable(true);
        card.setClickable(true);
        float radius = 22 * density;
        if (liquidGlass) {
            card.setBackground(LiquidGlass.widgetCard(radius, density));
            // Like the widget cards: a cached layer unless advanced effects redraw the glass while it zooms.
            if (!advanced) card.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        } else {
            StateListDrawable d = new StateListDrawable();
            d.addState(new int[]{android.R.attr.state_focused}, flat(radius, 0x66FFFFFF));
            d.addState(new int[0], flat(radius, 0x33FFFFFF));
            card.setBackground(d);
        }

        // The picture, 16:9 with rounded corners; the app's banner when the video brought none.
        ImageView art = new ImageView(activity);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap thumb = WatchHistory.thumb(activity, e);
        if (thumb != null) art.setImageBitmap(thumb);
        else art.setImageDrawable(banner(e.pkg));
        float artRadius = 10 * density;
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), artRadius);
            }
        });
        art.setClipToOutline(true);
        art.setBackgroundColor(0x33000000);
        card.addView(art, new LinearLayout.LayoutParams(dp(104), dp(58)));

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(dp(12));
        card.addView(texts, tl);

        TextView kicker = text(activity.getString(R.string.continue_kicker, WatchHistory.label(activity, e.pkg)), 10,
                R.font.outfit_medium, 0x99FFFFFF);
        kicker.setLetterSpacing(0.1f);
        kicker.setAllCaps(true);
        texts.addView(kicker);
        TextView title = text(e.title, 15, R.font.outfit_medium, 0xFFFFFFFF);
        title.setPadding(0, dp(2), 0, 0);
        texts.addView(title);

        // The time left, a short glass track with a white fill, and the X that removes the card.
        LinearLayout bottom = new LinearLayout(activity);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24));
        bl.topMargin = dp(4);
        texts.addView(bottom, bl);
        long leftMin = Math.max(1, (e.durationMs - e.positionMs + 59_999) / 60_000);
        TextView left = text(activity.getString(R.string.continue_left, (int) leftMin), 11, R.font.outfit_regular,
                0xB3FFFFFF);
        bottom.addView(left);
        LinearLayout track = new LinearLayout(activity);
        track.setBackground(flat(2 * density, 0x40FFFFFF));
        View fill = new View(activity);
        fill.setBackground(flat(2 * density, 0xEEFFFFFF));
        track.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, e.percent())));
        track.addView(new View(activity), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, 100 - e.percent())));
        LinearLayout.LayoutParams trl = new LinearLayout.LayoutParams(0, dp(3), 1f);
        trl.setMarginStart(dp(8));
        trl.setMarginEnd(dp(8));
        bottom.addView(track, trl);
        View remove = removeButton();
        bottom.addView(remove, new LinearLayout.LayoutParams(dp(24), dp(24)));

        card.setContentDescription(e.title);
        card.setOnFocusChangeListener((v, focused) -> {
            if (!advanced) return;   // the background's own cross-fade is the whole animation
            v.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f).setDuration(140)
                    .setUpdateListener(a -> v.invalidate()).start();
        });
        card.setOnKeyListener((v, keyCode, event) -> onKey(v, keyCode, event, () -> open(e)));
        remove.setOnKeyListener((v, keyCode, event) -> onKey(v, keyCode, event, () -> remove(e)));
        cards.add(card);
        stops.add(card);
        stops.add(remove);
        return card;
    }

    /** A small glass circle with an X; white with a dark X while focused, like the other buttons. */
    private View removeButton() {
        ImageView x = new ImageView(activity);
        x.setImageResource(R.drawable.ic_close);
        x.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        x.setPadding(dp(6), dp(6), dp(6), dp(6));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, oval(0xFFFFFFFF));
        bg.addState(new int[0], oval(0x33FFFFFF));
        bg.setEnterFadeDuration(90);
        bg.setExitFadeDuration(90);
        x.setBackground(bg);
        x.setImageTintList(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFF1C1C1E, 0xDDFFFFFF}));
        x.setFocusable(true);
        x.setClickable(true);
        x.setContentDescription(activity.getString(R.string.continue_remove));
        return x;
    }

    /** OK runs {@code action}; Left and Right move along the whole widgets row, Up and Down leave it. */
    private boolean onKey(View v, int keyCode, KeyEvent event, Runnable action) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!down && event.getRepeatCount() == 0) action.run();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) host.step(v, keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) host.up();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) host.down();
                return true;
            default:
                return false;
        }
    }

    /** The X: off the history and the row; focus goes to the next card (or the one before, or the dock). */
    private void remove(WatchHistory.Entry e) {
        int at = -1;
        for (int i = 0; i < cards.size(); i++) if (cards.get(i).hasFocus()) at = i;
        WatchHistory.remove(activity, e);
        start();
        if (!cards.isEmpty()) cards.get(Math.max(0, Math.min(at, cards.size() - 1))).requestFocus();
        else host.down();
    }

    /**
     * Back to the video: through its session when the app still has it open (the exact player), else a YouTube link
     * with the start time (looking the video id up first if YouTube did not give it), else the app itself.
     */
    private void open(WatchHistory.Entry e) {
        try {
            MediaSessionManager m = activity.getSystemService(MediaSessionManager.class);
            if (m != null) {
                for (MediaController c : m.getActiveSessions(new ComponentName(activity, MediaListener.class))) {
                    if (!e.pkg.equals(c.getPackageName()) || c.getMetadata() == null) continue;
                    CharSequence t = c.getMetadata().getText(android.media.MediaMetadata.METADATA_KEY_TITLE);
                    PendingIntent session = c.getSessionActivity();
                    if (session != null && t != null && e.title.equals(t.toString().trim())) {
                        session.send();
                        return;
                    }
                }
            }
        } catch (SecurityException | PendingIntent.CanceledException ignored) {
            // no grant, or the session went away: the next ways still work
        }
        if (isYouTube(e.pkg)) {
            if (WatchHistory.hasVideoId(e)) {
                if (watch(e)) return;
            } else if (e.pkg.equals(WatchHistory.YOUTUBE_TV)) {
                // Not looked up yet (YouTube was offline when it played): look it up now, then open it.
                Thread t = new Thread(() -> {
                    String id = WatchHistory.findYouTubeId(activity, e);
                    activity.runOnUiThread(() -> {
                        if (id == null || !watch(e)) launch(e.pkg);
                    });
                }, "continue-youtube");
                t.start();
                return;
            }
        }
        launch(e.pkg);
    }

    private boolean watch(WatchHistory.Entry e) {
        Intent watch = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + e.mediaId
                + "&t=" + (e.positionMs / 1000) + "s")).setPackage(e.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(watch);
            return true;
        } catch (RuntimeException notTaken) {
            return false;   // this build of the app does not open links: open it plainly
        }
    }

    private void launch(String pkg) {
        PackageManager pm = activity.getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) return;
        try {
            activity.startActivity(launch);
        } catch (RuntimeException ignored) {
            // uninstalled since
        }
    }

    private static boolean isYouTube(String pkg) {
        for (String y : YOUTUBE) if (y.equals(pkg)) return true;
        return false;
    }

    private boolean installed(String pkg) {
        try {
            activity.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private Drawable banner(String pkg) {
        PackageManager pm = activity.getPackageManager();
        try {
            Drawable b = pm.getApplicationBanner(pkg);
            return b != null ? b : pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private TextView text(String s, int sp, int font, int color) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(activity.getResources().getFont(font));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        // The same soft shadow as the widgets' text, legible over a bright wallpaper.
        t.setShadowLayer(4 * density, 0, density, 0xCC000000);
        return t;
    }

    private GradientDrawable flat(float radius, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private static GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
