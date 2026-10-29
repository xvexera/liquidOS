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
 * WatchHistory, each with its picture, title and how far it got. OK on a card opens the video again, at the same point
 * where the app allows it; Down from a card reaches the X under it, which takes it off. Rebuilt in start() from
 * onResume, so a video paused a moment ago is there.
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
    private final List<View> glass = new ArrayList<>();


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

    /** The cards, left to right: what Left and Right move between (the X under each is reached with Down). */
    List<View> stops() {
        return cards;
    }

    /** Repaints the glass cards and buttons, after the blurred backdrop behind them (or their place on screen) changed. */
    void invalidate() {
        for (View v : glass) v.invalidate();
    }

    List<View> cardViews() {
        return cards;
    }

    /** The X buttons. */
    List<View> buttonViews() {
        List<View> xs = new ArrayList<>(glass);
        xs.removeAll(cards);
        return xs;
    }

    /** The glass is drawn by the widgets row (GlassRow): it redraws when a card zooms or an X fades. */
    private void redrawGlass() {
        if (row != null && row.getParent() instanceof View) ((View) row.getParent()).invalidate();
    }

    void start() {
        if (row == null) return;
        row.removeAllViews();
        cards.clear();
        glass.clear();
        if (Extras.continueWatching(activity)) {
            for (WatchHistory.Entry e : WatchHistory.load(activity)) {
                if (cards.size() == MAX_CARDS) break;
                if (!installed(e.pkg) || !WatchHistory.appOn(activity, e.pkg)) continue;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
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
        card.setTag(HomeWidgets.LIT_CARD);
        float radius = 22 * density;
        if (liquidGlass) {
            // Like the widget cards: the row draws the wallpaper sample under it (GlassRow), the rest is a cached layer.
            card.setBackground(LiquidGlass.rowGlass(radius, density, true));
            card.setLayerType(View.LAYER_TYPE_HARDWARE, null);
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

        // The time left and a glass track with a white fill.
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
        trl.setMarginStart(dp(10));
        bottom.addView(track, trl);

        // The X is its own glass button under the card, shown only while the card or the X has focus.
        View remove = removeButton();
        remove.setAlpha(0f);
        Runnable showRemove = () -> remove.animate().alpha(card.hasFocus() || remove.hasFocus() ? 1f : 0f)
                .setDuration(120).setUpdateListener(a -> redrawGlass()).start();

        card.setContentDescription(e.title);
        card.setOnFocusChangeListener((v, focused) -> {
            showRemove.run();
            if (!advanced) return;   // the background's own cross-fade is the whole animation
            v.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f).setDuration(140)
                    .setUpdateListener(a -> redrawGlass()).start();
        });
        remove.setOnFocusChangeListener((v, focused) -> showRemove.run());
        card.setOnKeyListener((v, keyCode, event) -> onKey(card, remove, keyCode, event, false, () -> open(e)));
        remove.setOnKeyListener((v, keyCode, event) -> onKey(card, remove, keyCode, event, true, () -> remove(e)));
        cards.add(card);
        glass.add(card);
        glass.add(remove);

        LinearLayout item = new LinearLayout(activity);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setClipChildren(false);
        item.setClipToPadding(false);
        item.addView(card, new LinearLayout.LayoutParams(dp(300), dp(84)));
        LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(dp(36), dp(36));
        xl.topMargin = dp(10);
        item.addView(remove, xl);
        return item;
    }

    /** A round Liquid Glass button with an X, like the top bar's buttons; Home's focus light fades in over it. */
    private View removeButton() {
        ImageView x = new ImageView(activity);
        x.setImageResource(R.drawable.ic_close);
        x.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        x.setPadding(dp(10), dp(10), dp(10), dp(10));
        x.setTag(HomeWidgets.LIT_PILL);
        if (liquidGlass) {
            // Round glass like the top bar's buttons, brighter while focused; the row draws its wallpaper sample.
            x.setBackground(LiquidGlass.rowGlass(18 * density, density, false));
            x.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            x.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        } else {
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_focused}, oval(0xFFFFFFFF));
            bg.addState(new int[0], oval(0xCC2C2C2E));
            x.setBackground(bg);
            x.setImageTintList(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                    new int[]{0xFF1C1C1E, 0xDDFFFFFF}));
        }
        x.setFocusable(true);
        x.setClickable(true);
        x.setContentDescription(activity.getString(R.string.continue_remove));
        return x;
    }

    /**
     * OK runs {@code action}. Left and Right move along the whole widgets row, from the card. On the card Down goes to
     * its X and Up leaves the row; on the X Up goes back to the card and Down leaves the row.
     */
    private boolean onKey(View card, View remove, int keyCode, KeyEvent event, boolean onRemove, Runnable action) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!down && event.getRepeatCount() == 0) action.run();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) host.step(card, keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) {
                    if (onRemove) card.requestFocus();
                    else host.up();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    if (onRemove) host.down();
                    else remove.requestFocus();
                }
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
