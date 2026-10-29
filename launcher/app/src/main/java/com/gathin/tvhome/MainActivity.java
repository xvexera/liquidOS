package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.SoundEffectConstants;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    private static final int LAUNCHER_SETTINGS_REQUEST = 42;
    /** Sent by QuickSettingsActivity: open Launcher settings from here so Home rebuilds when they close. */
    static final String ACTION_LAUNCHER_SETTINGS = "com.gathin.tvhome.action.LAUNCHER_SETTINGS";
    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private boolean rtlAppGrid;
    private static final long MINUTE_MS = 60_000L;
    private static final long STATS_MS = 10_000L;
    /**
     * 2025-01-01. TVs without a clock battery boot at the epoch (08:00 in China) and keep it until network
     * time arrives; the clock, date and greeting stay hidden before then.
     */
    private static final long TIME_VALID_AFTER_MS = 1_735_689_600_000L;
    private String liveTvPkg;
    private String liveTvAction;
    private String inputAction;
    private String sourcePanelService;
    private String sourcePanelAction;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private WallpaperPlayer wallpaperPlayer;
    /** BTC/USDT and now playing under the clock; see HomeWidgets. */
    private HomeWidgets widgets;
    /** The light that crosses the focused tile (GlintView), kept on the glass highlight's place and size. */
    private GlintView glint;
    /** The dock band; told when the grid scrolls so its glass is moved, not redrawn, meanwhile. */
    private DockDecoration dockDecoration;
    /** Launcher settings: Liquid Glass pills, dock band and widget cards over a blurred wallpaper. */
    private boolean liquidGlass;
    /** Something Launcher settings asked Home to do once it is back: "reorder" or "dock_edit". */
    private static String pendingSettingsAction;
    /** Extras.revision when this Home was built; a different one in onResume means settings changed. */
    private int settingsRevision;
    /** The app colour glow's full strength. */
    private float ambientMax = 1f;
    /**
     * A wallpaper (a picture, a built-in one or the rotating one) is behind Home: the app colour glow is off. Over a
     * picture it only tinted the photo faintly while its colour animation and repaint ran on every focus move.
     */
    private boolean glowOff;
    /** The colour behind everything (config background_color), for the glass when there is no wallpaper. */
    private int backgroundColor = 0xFF000000;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    /** The top shelf: heroName, plus the cinematic shelf's "Last opened" line and dock position dots. */
    private View hero;
    private TextView heroMeta;
    private LinearLayout heroDots;
    /** Cinematic top shelf (config.xml, cinematic_home): the focused dock app's artwork behind a larger shelf. */
    private boolean cinematic;
    private ImageView heroArt;
    private static final float HERO_ART_ALPHA = 0.5f;
    /** Softened artwork per app (HeroArt, about 5 KB each), made the first time its dock tile is focused. */
    private final Map<String, Bitmap> heroArtCache = new HashMap<>();
    /** The app whose artwork heroArt shows, so focusing it again only fades it back in. */
    private String heroArtKey;
    /** The focus glow's colour while the cinematic shelf tints it, 0 for plain white glass. */
    private int glassAccent;
    private int ambientColor = -1;
    private ValueAnimator ambientAnimator;
    private boolean heroShown;
    private boolean animations;
    private boolean advancedEffects;
    private TextView greeting;
    private View clockBlock;
    private View btnInputs;
    private TextView btnWifi;
    /** The moon pill: night mode's dim and warm panel. */
    private View btnNight;
    /** The calendar pill and the events behind its count; see CalendarEvents. */
    private TextView btnCalendar;
    private CalendarEvents calendarEvents;
    private List<CalendarEvents.Event> upcoming = new ArrayList<>();
    private TextView shamsiDate;
    private TextView stats;
    private List<RaceWeekend.Race> raceCalendar;
    private View racePanel;
    private TextView raceKicker, raceHeadline, raceSessions;
    /** config.xml show_stats, overridable by a theme. Off: the line is gone and nothing is read. */
    private boolean showStats;
    private boolean shamsi;
    /** greeting_name: said after the greeting, which is shown in capitals. */
    private String greetingName = "";
    /** Optional time-of-day backdrop (config.xml, sky_intensity); 0 keeps it hidden. */
    private SkyView sky;
    private String vpnApp;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    // How far the grid has scrolled the glass. springY works without it, in grid content space, so a
    // scroll moves the glass in lockstep with its tile instead of becoming a moving spring target.
    private float glassScrollShift;
    private int glassCardW;   // width of the tile the glass was last fitted to
    private boolean glassShown;
    private final Rect tileRect = new Rect();
    private final Rect revealRect = new Rect();
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** config settings_key_keycodes / settings_key_panel, so the Settings button works on Home without HomeKeyService. */
    private int[] settingsKeycodes = new int[0];
    private boolean settingsKeyPanel;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    private boolean reducedMotion;
    /** config.xml dock_row: the first row is a separate dock (band, parked low, top shelf) rather than plain grid. */
    private boolean dockRow;
    /** Festive skin from the theme (see Festival); applied while it is active today. */
    private Festival festival = Festival.NONE;
    private boolean festive;
    private DiyaDrawable diya;
    private BellDrawable bell;
    private MarigoldDrawable marigold;
    private int greetingColor;
    /** The day the petal shower last played, so it greets the first Home of each day only. */
    private static LocalDate lastPetalShower;
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /**
     * Rebuild the tiles on resume: the first time, when Home was pressed, or when apps were installed, updated or
     * removed while Home was hidden. Coming back from an app with nothing changed keeps the tiles as they are, so
     * Home is back at once.
     */
    private boolean rebuildOnResume = true;
    /** Home is stopped (an app covers it): package changes only mark the tiles for a rebuild on return. */
    private boolean stopped;
    /** The package receiver stays registered from the first onStart to onDestroy, to see changes made meanwhile. */
    private boolean packagesWatched;
    /** Lights the focused pill and hides the tiles' glass when focus leaves them; removed in onDestroy. */
    private ViewTreeObserver.OnGlobalFocusChangeListener focusWatcher;
    /** The Inputs or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;
    /** Reorder mode is on (top-bar button). */
    private boolean reordering;
    /** Key of the tile being carried in reorder mode, or null. */
    private String movingKey;
    /** Tile order when reorder mode started, to save only when something moved. */
    private List<String> orderAtStart;
    private View btnReorder;
    /** dock_apps as loaded (config.xml or a theme): a saved Order only applies while these are unchanged. */
    private List<String> dockApps;
    /** fill_dock false: the dock can have empty slots, which reorder mode leaves out and restores afterwards. */
    private boolean fillDockLeavesSlots;
    /** Edit-dock mode (Settings menu): a reorder session that swaps one chosen app into one dock place. */
    private boolean dockEditing;
    /** Dock place being changed in edit-dock mode, or -1 while the place is still being chosen. */
    private int dockEditSlot = -1;
    private AppLibraryDialog appLibrary;
    private boolean libraryFocusPending;
    private RecyclerView.OnScrollListener pendingFocusScroll;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            if (stopped) {
                rebuildOnResume = true;
                return;
            }
            reloadApps(false);
            if (appLibrary != null) appLibrary.refresh();
        }
    };
    // Right after boot the clock is often wrong until network time arrives; don't wait a minute to catch up.
    private final IntentFilter timeFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClockVisibility();
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateRace();
            updateWifiLabel();
            sky.update();
            wallpaperPlayer.update();
            refreshOnlineWallpaper(false);
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (forwardToHome()) return;
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        settingsKeycodes = cfg.settingsKeycodes;
        settingsKeyPanel = cfg.settingsKeyPanel;
        COLUMNS = cfg.columns;
        animations = cfg.animations;
        advancedEffects = cfg.animations && cfg.advancedEffects;
        reducedMotion = cfg.reducedMotion;
        dockRow = cfg.dockRow;
        festival = cfg.festival;
        festive = festival.active(LocalDate.now());
        liveTvPkg = cfg.liveTvPackage;
        liveTvAction = cfg.liveTvAction;
        inputAction = cfg.inputAction;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;

        root = findViewById(R.id.root);
        root.setBackgroundColor(cfg.backgroundColor);
        backgroundColor = cfg.backgroundColor;
        // root fills the window with an opaque colour (Config keeps background_color opaque), so the window's own
        // black under it is a second full-screen fill on every frame for nothing. The starting window still uses
        // the theme's background, so launch looks the same.
        getWindow().setBackgroundDrawable(null);
        settingsRevision = Extras.revision(this);
        wallpaperPlayer = new WallpaperPlayer(this, root, cfg);
        String choice = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getString(Config.WALLPAPER_CHOICE_KEY, null);
        boolean oled = WallpaperChoices.OLED.equals(choice);
        glowOff = oled || wallpaperPlayer.current() != null || OnlineWallpapers.active(this);
        liquidGlass = Extras.liquidGlass(this);
        if (liquidGlass) {
            int blur = Extras.glassBlur(this);
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int screenH = getResources().getDisplayMetrics().heightPixels;
            LiquidGlass.setBackdrop(wallpaperPlayer.current(), screenW, screenH, cfg.wallpaperDim, blur);
            wallpaperPlayer.setOnImageChanged(bitmap -> {
                LiquidGlass.setBackdrop(bitmap, screenW, screenH, cfg.wallpaperDim, blur);
                invalidateGlass();
            });
        } else {
            LiquidGlass.setBackdrop(null, 0, 0, 0, 0);
        }
        sky = findViewById(R.id.sky);
        sky.setIntensity(cfg.skyIntensity);
        glass = findViewById(R.id.glass);
        glint = new GlintView(this);
        glint.setAlpha(0f);
        root.addView(glint, root.indexOfChild(glass) + 1, new FrameLayout.LayoutParams(
                glass.getLayoutParams().width, glass.getLayoutParams().height));
        if (festive) glass.setAccent(festival.accentColor());
        ambient = findViewById(R.id.ambient);
        if (glowOff) ambient.setVisibility(View.GONE);
        // The glow is one bitmap, so its alpha can be applied while drawing it. Otherwise every frame of
        // a fade renders it into a full-screen offscreen buffer first.
        ambient.forceHasOverlappingRendering(false);
        heroName = findViewById(R.id.heroName);
        hero = findViewById(R.id.hero);
        // The hero is now a group (name, and the cinematic lines): without this every fade of the default
        // shelf would render it through an offscreen buffer first, as the glow above notes.
        hero.forceHasOverlappingRendering(false);
        heroMeta = findViewById(R.id.heroMeta);
        heroDots = findViewById(R.id.heroDots);
        heroArt = findViewById(R.id.heroArt);
        cinematic = cfg.cinematicHome;
        if (cinematic) setUpCinematicShelf();
        // dock_title false: the name above the dock (and the cinematic lines under it) never shows; the glow
        // still follows the dock.
        if (!cfg.dockTitle) hero.setVisibility(View.GONE);
        greeting = findViewById(R.id.greeting);
        clockBlock = findViewById(R.id.leftBlock);
        greetingColor = greeting.getCurrentTextColor();
        btnWifi = findViewById(R.id.btnWifi);
        btnNight = findViewById(R.id.btnNight);
        btnCalendar = findViewById(R.id.btnCalendar);
        shamsiDate = findViewById(R.id.shamsiDate);
        stats = findViewById(R.id.stats);
        raceCalendar = cfg.raceCalendar;
        racePanel = findViewById(R.id.racePanel);
        raceKicker = findViewById(R.id.raceKicker);
        raceHeadline = findViewById(R.id.raceHeadline);
        raceSessions = findViewById(R.id.raceSessions);
        showStats = cfg.showStats;
        shamsi = cfg.shamsiDate;
        greetingName = cfg.greetingName;
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        vpnApp = cfg.vpnApp;
        moduleCards = cfg.moduleCards;
        hiddenApps = new HashSet<>(cfg.hiddenApps);

        grid = findViewById(R.id.grid);
        rtlAppGrid = getResources().getBoolean(R.bool.rtl_app_grid);
        // Mirror only the app grid; the header and other locales keep their own layouts.
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS) {
            @Override
            protected boolean isLayoutRTL() {
                return rtlAppGrid;
            }

            /**
             * Every "bring this tile on screen" scroll lands here, both RecyclerView's own on focus and
             * onTileFocused's. The stock version treats the grid's top padding as the visible top, but that
             * padding only pushes the dock down the screen: grid rows scroll up into it. With the stock rule,
             * Up into a row above the padding dragged that row down to the padding line, cut it off at the
             * bottom of the screen and could scroll the focused tile out entirely, dropping focus into the
             * top bar. Grid tiles now scroll just enough to show the rect between the grid's top edge and its
             * bottom; dock tiles are left to onTileFocused, which parks the dock row on the padding line.
             */
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate,
                                                         boolean focusedChildVisible) {
                if (dockRow && getPosition(child) < COLUMNS) return false;
                int top = child.getTop() + rect.top;
                int bottom = top + rect.height();
                // A row already fully on screen stays put, even inside the bottom padding (the last row
                // rows_below_dock leaves visible ends there; nudging it up was the bug). A row that has to scroll
                // in still stops at the padding line, clear of the screen edge, as before.
                int limitBottom = getHeight() - getPaddingBottom();
                int dy;
                if (top < 0) dy = top;
                else if (bottom <= getHeight()) dy = 0;
                else dy = Math.min(top, bottom - limitBottom);
                if (dy == 0) return false;
                if (immediate || reducedMotion || !animations) parent.scrollBy(0, dy);
                else parent.smoothScrollBy(0, dy);
                return true;
            }
        });
        ((GridLayoutManager) grid.getLayoutManager()).setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) { return adapter != null && adapter.isLibraryAt(position) ? COLUMNS : 1; }
        });
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        // Tiles that scroll out while one is carried come back from the view cache without a rebind; set their
        // dimming again whenever one attaches, so none stays dim after the drop.
        grid.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View v) {
                v.setAlpha(tileAlpha(grid.getChildAdapterPosition(v)));
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View v) {
            }
        });
        if (dockRow) {
            dockDecoration = new DockDecoration(COLUMNS, density, cinematic, liquidGlass && !cinematic);
            dockDecoration.setAlwaysRefract(advancedEffects);
            grid.addItemDecoration(dockDecoration);
        }
        if (animations) {
            int enterAnim = reducedMotion ? R.anim.tile_enter_reduced : R.anim.tile_enter;
            float enterDelay = reducedMotion ? 0f : 0.06f;
            grid.setLayoutAnimation(new LayoutAnimationController(
                    AnimationUtils.loadAnimation(this, enterAnim), enterDelay));
        }
        adapter = new AppAdapter(this, this, COLUMNS, cfg);
        dockApps = cfg.dockApps;
        fillDockLeavesSlots = !cfg.fillDock;
        adapter.setSavedOrder(Order.ranks(Order.load(Order.file(this)), dockApps));
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row towards the bottom of the screen, leaving room for
        // rows_below_dock grid rows under it; the rest of the grid stays below until you press Down.
        int rowsBelowDock = cfg.rowsBelowDock;
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            // Item height follows the tile size, which follows the columns and the rows that must fit (AppAdapter.tileWidthPx).
            int inner = (r - l) - grid.getPaddingLeft() - grid.getPaddingRight();
            int tileW = AppAdapter.tileWidthPx(inner, h, COLUMNS, rowsBelowDock, cfg.dockTitle, cfg.dockRow, density);
            int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(tileW), density);
            int dockRow = itemH + (int) (AppAdapter.DOCK_EXTRA_DP * density);   // item height plus DockDecoration offsets
            int gridRows = itemH * rowsBelowDock;
            // Keep the hero below the header when the library is enabled on a short screen,
            // even when the theme requests an extra visible row below the dock.
            int shelfClearance = cfg.appLibrary ? hero.getMeasuredHeight() + (int) (40 * density) : 0;
            int want = Math.max(shelfClearance, h - dockRow - gridRows - (int) (AppAdapter.BOTTOM_ROOM_DP * density));
            // Where the rows cannot fit even at the smallest tile (3 rows with the title on), the title keeps its
            // room above the dock and the last rows run off the bottom instead of the title drawing over the clock.
            if (MainActivity.this.dockRow && cfg.dockTitle && rowsBelowDock > 0) want = Math.max(want, (int) (AppAdapter.TITLE_ROOM_DP * density));
            // No dock to push down: the grid starts at the top, with room for the first row's zoom and glass.
            if (!MainActivity.this.dockRow) want = (int) (AppAdapter.TOP_ROOM_DP * density);
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
            // Cold start or column count > 4: the first focus can run before the grid has its final width or
            // padding, leaving the glass at the XML design size until a key is pressed.
            grid.post(this::refitGlass);
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (dockDecoration != null) dockDecoration.setScrolling(state != RecyclerView.SCROLL_STATE_IDLE, rv);
            }

            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                glassScrollShift -= dy;
                glass.setTranslationY(glass.getTranslationY() - dy);
                glint.setTranslationY(glass.getTranslationY());
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentY") {
            @Override
            public float getValue(View v) {
                return v.getTranslationY() - glassScrollShift;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationY(value + glassScrollShift);
            }
        });
        // The light rides on the glass: every spring frame copies its position.
        springX.addUpdateListener((a, value, velocity) -> glint.setTranslationX(glass.getTranslationX()));
        springY.addUpdateListener((a, value, velocity) -> glint.setTranslationY(glass.getTranslationY()));
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            if (reducedMotion) {
                // Stiff and almost critically damped: settles in a few frames instead of oscillating,
                // and stops once the highlight is within 1.5 px of its tile.
                s.setSpring(new SpringForce().setStiffness(1400f).setDampingRatio(0.95f));
                s.setMinimumVisibleChange(1.5f);
            } else {
                s.setSpring(new SpringForce().setStiffness(advancedEffects ? 320f : 260f)
                        .setDampingRatio(advancedEffects ? 0.76f : 0.58f));
            }
        }

        btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        // Settings opens the launcher's own menu (launcher settings, reorder, edit dock, TV settings); hold it for the
        // TV's settings straight away.
        btnSettings.setOnClickListener(v -> showSettingsMenu());
        btnReorder = findViewById(R.id.btnReorder);
        btnReorder.setOnClickListener(v -> toggleReorder());
        btnReorder.setVisibility(cfg.reorderButton ? View.VISIBLE : View.GONE);
        btnSettings.setOnLongClickListener(v -> {
            open(systemIntent(Settings.ACTION_SETTINGS));
            return true;
        });
        if (liquidGlass) {
            for (View button : new View[]{btnInputs, btnWifi, btnCalendar, btnNight, btnReorder, btnSettings}) {
                button.setBackground(LiquidGlass.pill(density));
                ((TextView) button).setTextColor(0xFFFFFFFF);
                ((TextView) button).setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
                // Its own small layer: Android replays the whole window every frame of a grid scroll, and without
                // one the refraction shader of every pill ran again each frame. Redrawn only when the pill changes.
                if (!advancedEffects) button.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            }
        }

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        boolean rtlMenuBar = getResources().getBoolean(R.bool.rtl_menu_bar);
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // The widgets row sits between the top bar and the dock.
                if (widgets != null && widgets.rowShown()) {
                    widgets.focusRow();
                    return true;
                }
                // Back to the dock tile Up came from, not wherever the focus finder lands.
                focusTile(lastFocused % COLUMNS);
                return true;
            }
            int firstButton = btnInputs.getVisibility() == View.VISIBLE ? R.id.btnInputs
                    : btnCalendar.getVisibility() == View.VISIBLE ? R.id.btnCalendar : R.id.btnNight;
            int leftButton = rtlMenuBar ? R.id.btnSettings : firstButton;
            int rightButton = rtlMenuBar ? firstButton : R.id.btnSettings;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == leftButton;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == rightButton;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        btnReorder.setOnKeyListener(pillKeys);
        btnNight.setOnKeyListener(pillKeys);
        btnCalendar.setOnKeyListener(pillKeys);
        btnCalendar.setOnClickListener(v -> showCalendarMenu());
        // Shown only with the switch on and the calendar readable; its count comes in with the first load.
        if (Extras.calendar(this) && CalendarEvents.allowed(this)) {
            btnCalendar.setVisibility(View.VISIBLE);
            calendarEvents = new CalendarEvents(this, 20, events -> {
                upcoming = events;
                updateCalendarCount();
            });
        }
        btnNight.setOnClickListener(v -> showNightPanel());
        // A night mode left on keeps its overlay across a reboot: Home is the first thing that starts.
        NightLight.apply(this);
        if (advancedEffects) {
            for (View button : new View[]{btnInputs, btnWifi, btnCalendar, btnNight, btnReorder, btnSettings}) {
                button.setOnFocusChangeListener((view, focused) -> view.animate()
                        .scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                        .setDuration(160).start());
            }
        }
        if (festive) applyFestiveSkin((TextView) btnInputs, (TextView) btnSettings);

        // Hide the glass when focus leaves the tiles.
        // The focus light of the tiles, also on the top pills and the widget cards: one more GlintView, moved and
        // resized over whichever of them has focus, entering from the side focus came from.
        uiGlint = new GlintView(this);
        uiGlint.setAlpha(0f);
        root.addView(uiGlint, new FrameLayout.LayoutParams(10, 10));
        focusWatcher = (oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
            lightUp(oldF, newF);
        };
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener(focusWatcher);

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
        applyClockStyle(Extras.clockStyle(this));
        // No wallpaper: give the glass the plain background to show until the first app glow arrives.
        scheduleGlowBackdrop();
        widgets = new HomeWidgets(this, density, liquidGlass, advancedEffects,
                () -> focusTile(lastFocused % COLUMNS), () -> btnWifi.requestFocus());
        widgets.setOnMarketMenu(this::showMarketMenu);
        widgets.setOnRowScrolled(this::followGlint);
        // Not after recreate(): the launch intent survives it and would open the settings again.
        if (savedInstanceState == null) openLauncherSettingsIfAsked(getIntent());
    }

    private boolean openLauncherSettingsIfAsked(Intent intent) {
        if (intent == null || !ACTION_LAUNCHER_SETTINGS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        startActivityForResult(new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST);
        return true;
    }

    /**
     * Home pressed while Home is already in front: singleTask delivers it here, then onResume runs.
     * Back to the top with the first dock tile focused, like the stock launcher. Back from an app
     * never comes this way, so returning to the app you left from is unchanged (#118).
     */
    /**
     * Opened some other way than as Home (from the app list, or by name) while this launcher is the TV's Home: hand
     * the intent to the Home that is already running instead of building a second one beside it, which would hold
     * another full copy of the screen in memory. True when this activity is finishing for that.
     */
    private boolean forwardToHome() {
        Intent intent = getIntent();
        if (intent == null || intent.hasCategory(Intent.CATEGORY_HOME)) return false;
        android.content.pm.ResolveInfo home = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        if (home == null || home.activityInfo == null || !getPackageName().equals(home.activityInfo.packageName)) {
            return false;
        }
        Intent forward = new Intent(intent).setAction(Intent.ACTION_MAIN).setData(null)
                .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        forward.removeCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        forward.removeCategory(Intent.CATEGORY_LAUNCHER);
        startActivity(forward);
        finish();
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (openLauncherSettingsIfAsked(intent)) return;
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        grid.scrollToPosition(0);
        lastFocused = 0;
        lastFocusedKey = adapter.getItemCount() > 0 ? adapter.keyAt(0) : null;
        rebuildOnResume = true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!packagesWatched) registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(timeReceiver, timeFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            if (!packagesWatched) registerReceiver(packageReceiver, packageFilter);
            registerReceiver(timeReceiver, timeFilter);
        }
        packagesWatched = true;
        updateClockVisibility();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Settings changed and we came back without a result (the Home key closes them): rebuild with them.
        if (settingsRevision != Extras.revision(this)) {
            recreate();
            return;
        }
        // A festival that started or ended while Home was in the background: rebuild with or without it.
        if (festive != festival.active(LocalDate.now())) {
            recreate();
            return;
        }
        promptsPaused = false;
        if (rebuildOnResume) {
            tilesReady = false;
            reloadApps(true);
        }
        rebuildOnResume = false;
        updateGreeting();
        updateRace();
        updateWifiLabel();
        updateInputsButton();
        if (showStats) startStats();
        widgets.start();
        if (calendarEvents != null) calendarEvents.start();
        refreshOnlineWallpaper(false);
        if (pendingSettingsAction != null) {
            String action = pendingSettingsAction;
            pendingSettingsAction = null;
            // After the tiles are laid out again: reorder needs a focused tile to carry.
            handler.postDelayed(() -> {
                if (isFinishing()) return;
                if (LauncherSettingsActivity.THEN_REORDER.equals(action)) toggleReorder();
                else if (LauncherSettingsActivity.THEN_DOCK_EDIT.equals(action) && dockRow) startDockEdit();
            }, 600);
        }
        sky.update();
        wallpaperPlayer.start();
        handler.postDelayed(minuteTick, MINUTE_MS);
        if (festive && diya != null) {
            diya.start();
            LocalDate today = LocalDate.now();
            if (!reducedMotion && !today.equals(lastPetalShower)) {
                lastPetalShower = today;
                handler.postDelayed(this::playPetalShower, 450);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LAUNCHER_SETTINGS_REQUEST && data != null) {
            pendingSettingsAction = data.getStringExtra(LauncherSettingsActivity.EXTRA_THEN);
        }
        if (requestCode == LAUNCHER_SETTINGS_REQUEST && resultCode == RESULT_OK) recreate();
    }

    /** Rebuilds the tiles (and the module cards), then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        cancelPendingFocusScroll();
        RecyclerView.ViewHolder footer = grid.findViewHolderForAdapterPosition(adapter.appCount());
        final boolean restoreLibrary = adapter.isLibraryAt(adapter.appCount()) && footer != null
                && footer.itemView.findViewById(R.id.btnLibrary) != null
                && footer.itemView.findViewById(R.id.btnLibrary).hasFocus();
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = appLibrary == null && (animate || grid.hasFocus());
        if (restoreFocus) {
            glassShown = false;
            glass.setAlpha(0f);
            glint.setAlpha(0f);
            heroShown = false;
            hero.setAlpha(0f);
            ambient.setAlpha(0f);
            heroArt.animate().cancel();
            heroArt.setAlpha(0f);
        }
        reloadTiles();
        if (animate && animations && !reducedMotion) grid.scheduleLayoutAnimation();

        if (restoreFocus) {
            // Keep a focused footer at the end even when the app count changes.
            if (restoreLibrary) grid.post(this::focusLibrary);
            else restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.appCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
            if (pos < 0) hideHero();
            // A newly installed app may have brought a card with it.
            tilesReady = true;
            showCardPrompt();
        }
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    private void reloadTiles() {
        if (reordering) endReorder();   // an app installed or removed while reordering: keep the order so far
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final List<Cards.Card> none = Collections.emptyList();
        // An updated app may bring new artwork; the backdrop is cheap to make again.
        heroArtCache.clear();
        heroArtKey = null;
        cards = moduleCards ? CardFeature.run(() -> Cards.discover(this, hiddenApps, Cards.load(Cards.storeFile(this))), none) : none;
        adapter.reload(cards.isEmpty() ? none : CardFeature.run(() -> Cards.shown(cards), none));
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        final boolean returnToLibrary = libraryFocusPending;
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            if (returnToLibrary || libraryFocusPending) focusLibrary();
            else focusTile(pos >= 0 ? pos : returnSlot);
            tilesReady = true;
            showCardPrompt();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        endReorder();   // Home or another app while reordering keeps the order so far
        wallpaperPlayer.stop();
        stopAmbientAnimation();
        hero.animate().cancel();
        ambient.animate().cancel();
        heroArt.animate().cancel();
        handler.removeCallbacks(minuteTick);
        stopStats();
        widgets.stop();
        if (calendarEvents != null) calendarEvents.stop();
        if (diya != null) diya.stop();
        cancelPendingFocusScroll();
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (appLibrary != null) appLibrary.close();
        if (openPopup != null) openPopup.dismiss();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopped = true;
        unregisterReceiver(timeReceiver);
        handler.removeCallbacks(clockCheck);
        // The cinematic shelf's "Last opened" line changes with every app opened from Home.
        if (cinematic) rebuildOnResume = true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            if (libraryFocusPending && appLibrary == null) focusLibrary();
            showCardPrompt();
        }
    }

    private final Runnable collectGarbage = () -> {
        Thread gc = new Thread(Runtime.getRuntime()::gc, "home-gc");
        gc.setPriority(Thread.MIN_PRIORITY);
        gc.start();
    };

    /** Home is hidden behind an app: let go of what is cheap to make again. */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            heroArtCache.clear();
            heroArtKey = null;
            // Bitmaps let go of by Home only return to the system after a garbage collection, and Home allocates
            // too little Java memory to trigger one: run it once the app in front has had time to start.
            handler.removeCallbacks(collectGarbage);
            handler.postDelayed(collectGarbage, 5000);
            // A 1080p picture is a few megabytes the app in front (YouTube) can use; it comes back on return.
            wallpaperPlayer.release();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (wallpaperPlayer == null) return;   // forwarded to Home before anything was set up
        // A rebuild (recreate) keeps the window and its tree observer: a listener left there would keep this whole
        // Home, bitmaps and all, alive next to the new one.
        ViewTreeObserver observer = getWindow().getDecorView().getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnGlobalFocusChangeListener(focusWatcher);
        if (packagesWatched) unregisterReceiver(packageReceiver);
        wallpaperPlayer.close();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    /**
     * A Settings button sent as a keycode (settings_key_keycodes, e.g. 83 on TCL Google TV remotes) opens the same
     * thing HomeKeyService would. While that service runs it consumes the key first; without it (not enabled,
     * or switched off by a force-stop) the stock launcher that used to handle the key is gone, and the button did
     * nothing even on Home.
     */
    private boolean handleSettingsKey(KeyEvent event) {
        for (int code : settingsKeycodes) {
            if (event.getKeyCode() != code) continue;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                open(settingsKeyPanel ? new Intent(this, QuickSettingsActivity.class)
                        : systemIntent(Settings.ACTION_SETTINGS));
            }
            return true;
        }
        return false;
    }

    // ---- reorder ---------------------------------------------------------------------------------

    /** Blue the glass turns while reorder mode is on. */
    private static final int REORDER_ACCENT = 0xFF6EC1FF;

    /**
     * Reorder mode (top-bar button). While it is on, every key comes here first:
     * - nothing carried: arrows move focus as usual; OK on a tile picks it up instead of opening the app;
     *   Back ends the mode and saves the order.
     * - a tile carried: arrows move it; Back (or OK) puts it down and the mode stays on for the next one.
     * Other keys (volume, Home) pass through; leaving the launcher ends the mode and saves.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // The last arrow pressed: the focus light enters the newly focused object from the side the move came from.
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT: lastMoveX = -1; lastMoveY = 0; lastMoveAt = SystemClock.uptimeMillis(); break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: lastMoveX = 1; lastMoveY = 0; lastMoveAt = SystemClock.uptimeMillis(); break;
                case KeyEvent.KEYCODE_DPAD_UP: lastMoveX = 0; lastMoveY = -1; lastMoveAt = SystemClock.uptimeMillis(); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: lastMoveX = 0; lastMoveY = 1; lastMoveAt = SystemClock.uptimeMillis(); break;
                default: break;
            }
        }
        if (handleSettingsKey(event)) return true;
        if (!reordering) return super.dispatchKeyEvent(event);
        if (dockEditing) return dispatchDockEdit(event);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        if (movingKey != null) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) moveBy(code);
                    return true;
                default:
                    if (ok || back) {
                        if (!down) drop();
                        return true;
                    }
                    return super.dispatchKeyEvent(event);
            }
        }
        if (back) {
            if (!down) endReorder();
            return true;
        }
        int pos = focusedTilePosition();
        if (ok && grid.hasFocus()) {
            // RecyclerView can briefly report NO_POSITION during a move or rebind. Still consume
            // OK on the grid: falling through would let the focused card open its app in reorder mode.
            if (!down && pos >= 0) pickUp(pos);
            return true;
        }
        return super.dispatchKeyEvent(event);   // arrows navigate; OK on a top-bar pill works as usual
    }

    /** Adapter position of the focused tile, or -1 when focus is not on a tile. */
    private int focusedTilePosition() {
        View f = getCurrentFocus();
        if (f == null || f.getId() != R.id.card || !(f.getParent() instanceof View)) return -1;
        return grid.getChildAdapterPosition((View) f.getParent());
    }

    /** The reorder button: turns the mode on (focus goes back to the last tile), or off again. */
    private void toggleReorder() {
        if (reordering) {
            endReorder();
            return;
        }
        if (!beginReorder()) return;
        focusTile(Math.max(0, Math.min(lastFocused, adapter.movableCount() - 1)));
        showOrganizeHint();
    }

    /** The start shared by reorder and edit-dock mode. False when there is nothing to move. */
    private boolean beginReorder() {
        if (adapter.movableCount() < 2) return false;
        adapter.setReordering(true);
        if (adapter.hasEmptySlots()) reloadTiles();   // every position must hold a tile that can move
        reordering = true;
        orderAtStart = adapter.movableKeys();
        // Tiles slide to their new places while organizing (the grid has no animator otherwise: a plain reload
        // must not animate). No change fade, which would flash the tiles the dock edge rebinds.
        if (animations && !reducedMotion) {
            DefaultItemAnimator slide = new DefaultItemAnimator();
            slide.setSupportsChangeAnimations(false);
            slide.setMoveDuration(220);
            grid.setItemAnimator(slide);
        }
        glass.setAccent(REORDER_ACCENT);
        btnReorder.setActivated(true);
        return true;
    }

    /** Edit dock (Settings menu): focus goes to the first dock place, which OK then chooses. */
    private void startDockEdit() {
        if (reordering) endReorder();
        if (!beginReorder()) return;
        dockEditing = true;
        dockEditSlot = -1;
        focusTile(0);
        Toast.makeText(this, R.string.dock_edit_hint, Toast.LENGTH_LONG).show();
    }

    /**
     * Keys in edit-dock mode. First OK on a dock tile chooses that place (the other dock tiles dim) and focus moves
     * to the grid; then OK on any tile swaps that app into the place, saves the order and ends the mode. Back goes
     * one step back: from choosing the app to choosing the place, and from there out of the mode.
     */
    private boolean dispatchDockEdit(KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        if (back) {
            if (!down) {
                if (dockEditSlot >= 0) {
                    int slot = dockEditSlot;
                    dockEditSlot = -1;
                    dimOthers();
                    focusTile(slot);
                } else {
                    endReorder();
                }
            }
            return true;
        }
        int pos = focusedTilePosition();
        if (ok && pos >= 0) {
            if (!down) {
                int n = adapter.movableCount();
                if (dockEditSlot < 0) {
                    if (pos < Math.min(COLUMNS, n)) {
                        dockEditSlot = pos;
                        dimOthers();
                        focusTile(Math.min(COLUMNS, n - 1));
                        Toast.makeText(this, R.string.dock_edit_pick_app, Toast.LENGTH_LONG).show();
                    }
                } else if (pos < n) {
                    swapIntoDock(dockEditSlot, pos);
                }
            }
            return true;   // never opens the app while editing
        }
        return super.dispatchKeyEvent(event);   // arrows navigate as usual
    }

    /** Puts the app at pos into dock place slot, and the app that was there where the chosen one was; then saves. */
    private void swapIntoDock(int slot, int pos) {
        String slotKey = adapter.keyAt(slot);
        if (pos != slot) {
            keepScroll(() -> {
                adapter.move(pos, slot);
                adapter.move(adapter.positionOf(slotKey), pos);
            });
        }
        endReorder();
        refocusAfterLayout(slot);
    }

    private void pickUp(int pos) {
        if (pos >= adapter.movableCount()) return;   // the "Default home" tile stays put
        movingKey = adapter.keyAt(pos);
        dimOthers();
        showOrganizeHint();
    }

    /** Puts the carried tile down where it is; the mode stays on. */
    private void drop() {
        movingKey = null;
        dimOthers();
        if (reordering) showOrganizeHint();
    }

    /** While a tile is carried, every other tile is dimmed so the carried one stands out; otherwise all are full. */
    private void dimOthers() {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View item = grid.getChildAt(i);
            int pos = grid.getChildAdapterPosition(item);
            item.setAlpha(tileAlpha(pos));
            // The carried tile rides a little higher and larger than the rest, like an icon picked up on tvOS.
            boolean carried = movingKey != null && pos != RecyclerView.NO_POSITION && movingKey.equals(adapter.keyAt(pos));
            float lift = carried ? 1.07f : 1f;
            if (item.getScaleX() != lift) {
                item.animate().scaleX(lift).scaleY(lift).translationZ(carried ? 12 * density : 0f)
                        .setDuration(animations ? 160 : 0).start();
            }
        }
    }

    /** The organize mode's glass bar at the bottom: what the keys do now, carrying a tile or not. */
    private TextView organizeHint;

    private void showOrganizeHint() {
        if (organizeHint == null) {
            organizeHint = new TextView(this);
            organizeHint.setTextColor(0xFFFFFFFF);
            organizeHint.setTextSize(14f);
            organizeHint.setTypeface(getResources().getFont(R.font.outfit_medium));
            int h = (int) (22 * density), v = (int) (11 * density);
            organizeHint.setPadding(h, v, h, v);
            organizeHint.setBackground(liquidGlass ? LiquidGlass.panel(22 * density, density)
                    : getDrawable(R.drawable.glass_panel));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = (int) (26 * density);
            root.addView(organizeHint, lp);
            organizeHint.setAlpha(0f);
        }
        organizeHint.setText(movingKey != null ? R.string.organize_hint_carrying : R.string.organize_hint_browsing);
        organizeHint.animate().alpha(1f).setDuration(180).start();
    }

    private void hideOrganizeHint() {
        if (organizeHint != null) organizeHint.animate().alpha(0f).setDuration(180).start();
    }

    /** A tile's alpha: dim unless it is the carried one, or, in edit-dock mode, a dock tile other than the chosen place. */
    private float tileAlpha(int pos) {
        if (movingKey != null) {
            return pos != RecyclerView.NO_POSITION && movingKey.equals(adapter.keyAt(pos)) ? 1f : 0.45f;
        }
        if (dockEditSlot >= 0 && pos >= 0 && pos < COLUMNS && pos != dockEditSlot) return 0.45f;
        return 1f;
    }

    /** Ends the mode and saves the order if it changed since the mode started. */
    private void endReorder() {
        if (!reordering) return;
        dockEditing = false;
        dockEditSlot = -1;
        drop();
        reordering = false;
        adapter.setReordering(false);
        grid.setItemAnimator(null);
        hideOrganizeHint();
        glass.setAccent(festive ? festival.accentColor() : 0);
        btnReorder.setActivated(false);
        List<String> keys = adapter.movableKeys();
        List<String> before = orderAtStart;
        orderAtStart = null;
        if (!keys.equals(before)) {
            Order order = new Order(Order.dockKey(dockApps), keys);
            if (Order.save(Order.file(this), order)) {
                adapter.setSavedOrder(Order.ranks(order, dockApps));
            } else {
                Toast.makeText(this, R.string.reorder_not_saved, Toast.LENGTH_SHORT).show();
            }
        }
        if (fillDockLeavesSlots) reloadTiles();   // the empty dock slots left out while reordering come back
    }

    /**
     * Moves the carried tile one step. Left and Right wrap across rows, like a list; Up and Down move a whole row,
     * and Down into a short last row lands on its last tile. Nothing moves past either end or up into the top bar.
     */
    private void moveBy(int keyCode) {
        int from = adapter.positionOf(movingKey);
        int n = adapter.movableCount();
        if (from < 0 || from >= n) {
            drop();
            return;
        }
        int to;
        // rtl_app_grid (Hebrew) mirrors the grid: position 0 is on the right, so Left moves towards the end.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) to = rtlAppGrid ? from + 1 : from - 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) to = rtlAppGrid ? from - 1 : from + 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) to = from - COLUMNS;
        else if (from + COLUMNS < n) to = from + COLUMNS;
        else to = from / COLUMNS < (n - 1) / COLUMNS ? n - 1 : from;
        if (to < 0 || to >= n || to == from) return;
        keepScroll(() -> adapter.move(from, to));
        grid.playSoundEffect(SoundEffectConstants.CLICK);
        refocusAfterLayout(to);
    }

    /**
     * Applies an adapter change without the grid jumping. After a move the layout manager anchors on the focused
     * tile, keeping it at its old place on screen and shifting every other row; pinning the first visible row at its
     * current offset as the next layout's anchor keeps the scroll where it was. onTileFocused still scrolls
     * afterwards if the moved tile itself ends up off screen.
     */
    private void keepScroll(Runnable change) {
        GridLayoutManager lm = (GridLayoutManager) grid.getLayoutManager();
        int first = lm.findFirstVisibleItemPosition();
        View firstView = first != RecyclerView.NO_POSITION ? lm.findViewByPosition(first) : null;
        int offset = firstView != null ? lm.getDecoratedTop(firstView) - grid.getPaddingTop() : 0;
        change.run();
        if (firstView != null) lm.scrollToPositionWithOffset(first, offset);
    }

    /**
     * Once the grid has applied the change, put the glass (and focus, if the move dropped it) on the tile at pos.
     * The grid has a fixed size, so it applies adapter changes on the next animation frame rather than in a layout
     * pass; until then it cannot find the tile by position. Wait frame by frame (at most 10) for that. No focus
     * change fires for a moved tile, so focusTile's already-focused path calls onTileFocused itself.
     */
    private void refocusAfterLayout(int pos) {
        grid.postOnAnimation(new Runnable() {
            int frames;

            @Override
            public void run() {
                if ((grid.hasPendingAdapterUpdates() || grid.isLayoutRequested()) && ++frames < 10) {
                    grid.postOnAnimation(this);
                    return;
                }
                focusTile(pos);
                dimOthers();   // views that came into view or were rebound during the move
            }
        });
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onLibraryRequested() {
        if (appLibrary != null) appLibrary.close();
        if (openPopup != null) openPopup.dismiss();
        libraryFocusPending = false;
        appLibrary = new AppLibraryDialog(this, animations, reducedMotion, adapter::containsPackage, () -> reloadApps(false), () -> {
            appLibrary = null;
            openPopup = null;
            libraryFocusPending = true;
            grid.post(() -> {
                if (libraryFocusPending && appLibrary == null && hasWindowFocus() && !isFinishing()) focusLibrary();
            });
        });
        openPopup = appLibrary.popup;
        appLibrary.show();
    }

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // Focus restored before the first layout (cold start): the tile has no size yet, and the glass
        // would be sized to 2*pad. Run again once the tile is laid out.
        if (card.getWidth() == 0) {
            card.post(() -> {
                if (card.isFocused() && card.getWidth() > 0) onTileFocused(position, card);
            });
            return;
        }
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = tileRect;
        r.set(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float scale = adapter.focusScale();
        float w = card.getWidth() * scale + 2 * pad;
        float h = card.getHeight() * scale + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f - (advancedEffects ? 4f * density : 0f);

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h || card.getWidth() != glassCardW) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            // GlassView draws its slab inset by pad, i.e. exactly over the zoomed tile: same corners as the tile.
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * scale);
            ViewGroup.LayoutParams glp = glint.getLayoutParams();
            glp.width = lp.width;
            glp.height = lp.height;
            glint.setLayoutParams(glp);
            glint.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * scale);
        }
        // Which way focus moved, for the side the light comes in from (none on the first focus).
        boolean moved = glassShown && (Math.abs(x - glassTargetX) > 1 || Math.abs(y - glassTargetY) > 1);
        float fromX = moved ? Math.signum(x - glassTargetX) : 0f;
        float fromY = moved ? Math.signum(y - glassTargetY) : 0f;
        if (moved && fromX != 0 && fromY != 0) {
            // A move to another row that also shifts sideways: the vertical part is the one the eye follows.
            fromX = 0f;
        }
        glassCardW = card.getWidth();
        glassTargetX = x;
        glassTargetY = y;
        // Cinematic shelf: the highlight glows in the dock app's colour, plain white in the grid.
        int accent = cinematic && !festive && position < COLUMNS ? adapter.ambientColorAt(position) : 0;
        if (cinematic && !festive && accent != glassAccent) {
            glassAccent = accent;
            glass.setAccent(accent);
        }
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glint.setTranslationX(x);
            glint.setTranslationY(y);
            if (animations) {
                glass.animate().alpha(1f).setDuration(reducedMotion ? 100 : 180).start();
                glint.animate().alpha(1f).setDuration(reducedMotion ? 100 : 180).start();
            } else {
                glass.setAlpha(1f);
                glint.setAlpha(1f);
            }
            glassShown = true;
        } else if (!animations) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glint.setTranslationX(x);
            glint.setTranslationY(y);
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y - glassScrollShift);
        }
        if (recentMove()) {
            fromX = lastMoveX;
            fromY = lastMoveY;
        }
        glint.sweep(fromX, fromY, animations && !reducedMotion);
        View item = (View) card.getParent();
        if (dockRow && position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            // A 1px mismatch used to start a scroll on every dock move. Only scroll when the row is
            // actually off; reduced motion does it in one jump so it does not fight the highlight.
            boolean scroll = Math.abs(dy) > (int) (8 * density);
            if (scroll) {
                if (!animations || reducedMotion) grid.scrollBy(0, dy);
                else grid.smoothScrollBy(0, dy);
            }
            showHero(position, scroll ? r.top - dy : r.top);
        } else {
            // Below the dock (or in a plain grid) the glow follows the tile without the big name; letting it
            // fade out left the grid on plain black.
            showGlowOnly(position);
            // Scroll just enough so the whole glass (not only the tile) is visible.
            revealRect.set(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, revealRect, reducedMotion);
        }
    }

    /** Refits the glass to the focused holder once the grid layout and tile dimensions settle. */
    private void refitGlass() {
        if (isDestroyed() || adapter.appCount() == 0 || libraryFocusPending) return;
        int pos = Math.max(0, Math.min(lastFocused, adapter.appCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        View card = vh == null ? null : vh.itemView.findViewById(R.id.card);
        if (card == null || card.getWidth() <= 0 || !card.isFocused()) return;
        float pad = 6f * density;
        float scale = adapter.focusScale();
        int w = (int) (card.getWidth() * scale + 2 * pad);
        int h = (int) (card.getHeight() * scale + 2 * pad);
        if (!glassShown || card.getWidth() != glassCardW || glass.getWidth() != w || glass.getHeight() != h) {
            onTileFocused(pos, card);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - hero.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());
        if (cinematic) showHeroArt(position);

        if (!animations || reducedMotion) {
            // Snap the shelf instead of sliding and fading it: each of those frames repaints the
            // full-screen panel, and this switch exists for the GPUs that cannot afford it.
            hero.animate().cancel();
            ambient.animate().cancel();
            fillHero(position, label);
            hero.setY(y);
            hero.setTranslationX(0f);
            hero.setAlpha(1f);
            setAmbient(color);
            if (!animations) {
                ambient.setAlpha(ambientMax);
                heroShown = true;
            } else if (!heroShown) {
                ambient.setAlpha(0f);
                ambient.animate().alpha(ambientMax).setDuration(160).start();
                heroShown = true;
            }
            return;
        }

        if (!heroShown) {
            stopAmbientAnimation();
            hero.animate().cancel();
            ambient.animate().cancel();
            fillHero(position, label);
            hero.setY(y);
            hero.setTranslationX(0f);
            hero.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(ambientMax).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            if (advancedEffects) hero.animate().cancel();
            hero.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                fillHero(position, label);
                hero.setY(y);
                hero.setTranslationX(12 * density);
                hero.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (advancedEffects) {
                transitionAmbient(color);
            } else if (color != ambientColor) {
                ambient.animate().alpha(0.35f * ambientMax).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(ambientMax).setDuration(500).start();
                }).start();
            }
        } else {
            hero.setY(y);
        }
    }

    /** The shelf's text for this dock app: its name and, on the cinematic shelf, "Last opened" and the dots. */
    private void fillHero(int position, String label) {
        heroName.setText(label);
        if (!cinematic) return;
        updateHeroMeta(position);
        updateHeroDots(position);
    }

    // ---- cinematic shelf -------------------------------------------------------------------------

    /** The parts that never change: a larger name, the meta line and the dots, the artwork and the scrim. */
    private void setUpCinematicShelf() {
        heroName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 68f);
        heroName.setLetterSpacing(-0.025f);
        heroMeta.setVisibility(View.INVISIBLE);   // keeps its height, so the name does not jump when it fills in
        heroDots.setVisibility(View.VISIBLE);
        heroArt.setVisibility(View.VISIBLE);
        // One stretched bitmap: apply its alpha while drawing instead of through an offscreen buffer.
        heroArt.forceHasOverlappingRendering(false);
        View scrim = findViewById(R.id.heroScrim);
        scrim.setBackground(scrimDrawable());
        scrim.setVisibility(View.VISIBLE);
    }

    /**
     * Darkens the side the shelf's text is on and the lower part of the screen, so the name, the dock and
     * the grid stay readable over the artwork. One small bitmap stretched over the screen, like the glow.
     */
    private Drawable scrimDrawable() {
        int w = 480, h = 270;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        Paint paint = new Paint();
        paint.setShader(new LinearGradient(rtl ? w : 0, 0, rtl ? 0 : w, 0,
                new int[]{0xD1060406, 0x59060406, 0x00060406}, new float[]{0f, 0.42f, 0.7f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x00060406, 0x00060406, 0xDB060406, 0xFF060406}, new float[]{0f, 0.28f, 0.58f, 0.76f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        BitmapDrawable scrim = new BitmapDrawable(getResources(), bmp);
        scrim.setGravity(Gravity.FILL);
        return scrim;
    }

    /** Swaps the backdrop to this app's artwork: fade out, swap, fade in, the way the shelf's name changes. */
    private void showHeroArt(int position) {
        String key = adapter.keyAt(position);
        if (key == null) return;
        heroArt.animate().cancel();
        if (key.equals(heroArtKey)) {
            fadeHeroArt(420);
            return;
        }
        Bitmap art = heroArtCache.get(key);
        if (art == null) {
            art = HeroArt.from(adapter.artworkAt(position));
            if (art != null) heroArtCache.put(key, art);
        }
        heroArtKey = key;
        final Bitmap next = art;
        if (!animations || reducedMotion || heroArt.getAlpha() == 0f) {
            heroArt.setImageBitmap(next);
            fadeHeroArt(600);
            return;
        }
        heroArt.animate().alpha(0f).setDuration(140).withEndAction(() -> {
            heroArt.setImageBitmap(next);
            heroArt.animate().alpha(HERO_ART_ALPHA).setDuration(420).start();
        }).start();
    }

    private void fadeHeroArt(int ms) {
        if (!animations) heroArt.setAlpha(HERO_ART_ALPHA);
        else heroArt.animate().alpha(HERO_ART_ALPHA).setDuration(reducedMotion ? Math.min(ms, 160) : ms).start();
    }

    /** "Last opened yesterday, 9:40 PM", the time in white; nothing (but the same height) when it was never seen. */
    private void updateHeroMeta(int position) {
        long when = LastOpened.get(this, adapter.packageAt(position));
        if (when <= 0) {
            heroMeta.setVisibility(View.INVISIBLE);
            return;
        }
        String rel = LastOpened.describe(this, when);
        String text = getString(R.string.last_opened, rel);
        SpannableString line = new SpannableString(text);
        int at = text.indexOf(rel);
        if (at >= 0) line.setSpan(new ForegroundColorSpan(0xFFFFFFFF), at, at + rel.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        heroMeta.setText(line);
        heroMeta.setVisibility(View.VISIBLE);
    }

    /** One dot per dock app, the focused one drawn long and white. Hebrew docks start on the right, so do the dots. */
    private void updateHeroDots(int position) {
        int count = Math.min(adapter.dockFilled(), COLUMNS);
        heroDots.setVisibility(count > 1 ? View.VISIBLE : View.INVISIBLE);
        if (heroDots.getChildCount() != count) {
            heroDots.removeAllViews();
            for (int i = 0; i < count; i++) {
                View dot = new View(this);
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(3 * density);
                dot.setBackground(bg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (6 * density), (int) (6 * density));
                if (i > 0) lp.leftMargin = (int) (6 * density);
                heroDots.addView(dot, lp);
            }
        }
        for (int i = 0; i < count; i++) {
            View dot = heroDots.getChildAt(rtlAppGrid ? count - 1 - i : i);
            boolean on = i == position;
            ((GradientDrawable) dot.getBackground()).setColor(on ? 0xFFFFFFFF : 0x4DFFFFFF);
            ViewGroup.LayoutParams lp = dot.getLayoutParams();
            int w = (int) ((on ? 20 : 6) * density);
            if (lp.width != w) {
                lp.width = w;
                dot.setLayoutParams(lp);
            }
        }
    }

    /**
     * The glow used to be a full-screen radial GradientDrawable. On Android 10 that drawable is
     * drawn on the CPU, so fading it for half a second redrew 1920×1080 every frame and the
     * highlight looked like it was running at a few frames per second. A small bitmap stretched
     * over the screen is one texture. It is painted once in white and tinted with the app's colour,
     * so changing apps neither repaints it on the CPU (about 11 ms) nor uploads it again.
     */
    private void setAmbient(int color) {
        if (glowOff) return;
        if (color == ambientColor && ambient.getBackground() != null) return;
        ambientColor = color;
        BitmapDrawable glow = (BitmapDrawable) ambient.getBackground();
        if (glow == null) {
            int w = 480;
            int h = 270;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (cinematic) {
                // Higher and stronger, from the corner away from the shelf's text: the app's colour floods
                // the top of the screen over its artwork, and the scrim keeps the text side dark.
                boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
                paint.setShader(new RadialGradient(
                        w * (rtl ? 0.20f : 0.80f), h * 0.06f, w * 0.78f,
                        new int[]{0xA6FFFFFF, 0x47FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.5f, 1f},
                        Shader.TileMode.CLAMP));
            } else {
                paint.setShader(new RadialGradient(
                        w * 0.32f, h * 0.42f, w * 0.62f,
                        new int[]{0x5CFFFFFF, 0x26FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.45f, 1f},
                        Shader.TileMode.CLAMP));
            }
            canvas.drawRect(0, 0, w, h, paint);
            glow = new BitmapDrawable(getResources(), bmp);
            glow.setGravity(Gravity.FILL);
            ambient.setBackground(glow);
        }
        // The default tint mode, SRC_IN, keeps the mask's alpha and takes the colour from the tint.
        glow.setTint(0xFF000000 | color);
        scheduleGlowBackdrop();
    }

    private void transitionAmbient(int color) {
        if (glowOff) return;
        // A rapid focus change continues from the colour actually displayed on screen.
        stopAmbientAnimation();
        ambient.animate().cancel();
        if (color == ambientColor) {
            ambient.animate().alpha(ambientMax).setDuration(180).start();
            return;
        }
        ambient.animate().alpha(ambientMax).setDuration(180).start();
        ambientAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, color);
        ambientAnimator.setDuration(360);
        ambientAnimator.addUpdateListener(animation -> setAmbient((int) animation.getAnimatedValue()));
        ambientAnimator.start();
    }

    private void stopAmbientAnimation() {
        if (ambientAnimator != null) {
            ambientAnimator.cancel();
            ambientAnimator = null;
        }
    }

    /**
     * Grid tiles (every tile with dock_row false): the glow behind the grid takes the focused tile's colour; the big
     * name above the dock does not show (every grid tile has its name underneath).
     */
    private void showGlowOnly(int position) {
        int color = adapter.ambientColorAt(position);
        stopAmbientAnimation();
        hero.animate().cancel();
        ambient.animate().cancel();
        hero.setAlpha(0f);
        heroArt.animate().cancel();
        heroArt.setAlpha(0f);
        heroShown = false;
        if (!animations || reducedMotion) {
            setAmbient(color);
            ambient.setAlpha(ambientMax);
            return;
        }
        if (color == ambientColor && ambient.getBackground() != null) {
            ambient.animate().alpha(ambientMax).setDuration(300).start();
            return;
        }
        // The same dip-and-swap as the dock's top shelf.
        ambient.animate().alpha(0.35f * ambientMax).setDuration(ambient.getAlpha() > 0.35f ? 200 : 0).withEndAction(() -> {
            setAmbient(color);
            ambient.animate().alpha(ambientMax).setDuration(500).start();
        }).start();
    }

    private void hideHero() {
        if (!heroShown) return;
        stopAmbientAnimation();
        hero.animate().cancel();
        ambient.animate().cancel();
        heroArt.animate().cancel();
        heroShown = false;
        if (animations) {
            hero.animate().alpha(0f).setDuration(reducedMotion ? 100 : 160).start();
            ambient.animate().alpha(0f).setDuration(reducedMotion ? 140 : 300).start();
            heroArt.animate().alpha(0f).setDuration(reducedMotion ? 140 : 300).start();
        } else {
            hero.setAlpha(0f);
            ambient.setAlpha(0f);
            heroArt.setAlpha(0f);
        }
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().cancel();
        glint.animate().cancel();
        if (animations) {
            glass.animate().alpha(0f).setDuration(160).start();
            glint.animate().alpha(0f).setDuration(160).start();
        } else {
            glass.setAlpha(0f);
            glint.setAlpha(0f);
        }
    }

    // ---- optional system stats (config.xml show_stats) --------------------------------------------

    /** Background reader. Started only while Home is resumed and show_stats is on. */
    private HandlerThread statsThread;
    /** The current run's handler; a run whose handler is no longer this one stops (volatile: read off-thread). */
    private volatile Handler statsBg;

    private void startStats() {
        if (statsThread != null) return;
        stats.setVisibility(View.VISIBLE);
        cpuTotalPrev = -1;
        idlePrev = -1;
        procStatDead = false;
        statsThread = new HandlerThread("stats");
        statsThread.start();
        // Each start gets its own handler, held by its task: stopStats on the main thread can clear the field
        // at any moment without the reader tripping over it.
        Handler bg = new Handler(statsThread.getLooper());
        statsBg = bg;
        bg.post(new Runnable() {
            @Override
            public void run() {
                if (statsBg != bg) return;
                String line = readStatsLine();
                handler.post(() -> {
                    if (showStats && stats != null && statsBg == bg) stats.setText(line);
                });
                if (statsBg == bg) bg.postDelayed(this, STATS_MS);
            }
        });
    }

    private void stopStats() {
        if (statsThread == null) return;
        statsBg.removeCallbacksAndMessages(null);
        statsThread.quit();
        statsThread = null;
        statsBg = null;
        stats.setVisibility(View.GONE);
    }

    /** One quiet line, e.g. "CPU 8%  ·  Memory 430 MB free  ·  Storage 3.1 GB free". Runs off the main thread. */
    private static void appendStat(StringBuilder line, String part) {
        if (line.length() > 0) line.append("  ·  ");
        line.append(part);
    }

    private String readStatsLine() {
        int cpu = cpuPercent();
        if (cpu < 0) {
            try {
                Thread.sleep(200);             // second sample, so the first line already has a CPU figure
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
            cpu = cpuPercent();
        }
        // Android 8 and later hide /proc/stat from apps on many TVs: then the line leaves CPU out rather than
        // showing a dash that never fills in.
        StringBuilder line = new StringBuilder();
        if (cpu >= 0) line.append(getString(R.string.stats_cpu, cpu));
        long mem = memAvailableMb();
        if (mem >= 0) appendStat(line, getString(R.string.stats_memory, mem));
        String free = formatGb(Environment.getDataDirectory().getFreeSpace());
        if (!free.isEmpty()) appendStat(line, getString(R.string.stats_storage, free));
        return line.toString();
    }

    // -1: need another sample. -2: this source cannot be read (Android 9 hides /proc/stat from apps).
    private static final int CPU_UNREADABLE = -2;
    private long cpuTotalPrev = -1, cpuIdlePrev = -1;
    private boolean procStatDead;
    private long idlePrev = -1, wallPrev = -1;

    private int cpuPercent() {
        if (!procStatDead) {
            int fromStat = cpuFromProcStat();
            if (fromStat != CPU_UNREADABLE) return fromStat;
            procStatDead = true;
            idlePrev = -1;
        }
        return cpuFromIdle();
    }

    private int cpuFromProcStat() {
        String s = readFirstLine("/proc/stat");
        if (s == null || !s.startsWith("cpu")) return CPU_UNREADABLE;
        String[] t = s.trim().split("\\s+");
        if (t.length < 5) return CPU_UNREADABLE;
        try {
            long user = Long.parseLong(t[1]), nice = Long.parseLong(t[2]), sys = Long.parseLong(t[3]);
            long idle = Long.parseLong(t[4]);
            long iowait = t.length > 5 ? Long.parseLong(t[5]) : 0;
            long irq = t.length > 6 ? Long.parseLong(t[6]) : 0;
            long soft = t.length > 7 ? Long.parseLong(t[7]) : 0;
            long total = user + nice + sys + idle + iowait + irq + soft;
            long idleAll = idle + iowait;
            if (cpuTotalPrev < 0) {
                cpuTotalPrev = total;
                cpuIdlePrev = idleAll;
                return -1;
            }
            long dt = total - cpuTotalPrev, di = idleAll - cpuIdlePrev;
            cpuTotalPrev = total;
            cpuIdlePrev = idleAll;
            if (dt <= 0) return -1;
            int busy = (int) Math.round((dt - di) * 100.0 / dt);
            return Math.max(0, Math.min(100, busy));
        } catch (NumberFormatException e) {
            return CPU_UNREADABLE;
        }
    }

    /** Busy percent from cpuidle time (microseconds) across the cores. -1 until the second sample. */
    private int cpuFromIdle() {
        long idle = 0;
        int cores = 0;
        for (int i = 0; i < 8; i++) {
            boolean any = false;
            for (int state = 0; state < 8; state++) {
                String t = readFirstLine("/sys/devices/system/cpu/cpu" + i
                        + "/cpuidle/state" + state + "/time");
                if (t == null) break;
                try {
                    idle += Long.parseLong(t.trim());
                    any = true;
                } catch (NumberFormatException e) {
                    break;
                }
            }
            if (!any) break;
            cores++;
        }
        if (cores == 0) return -1;
        long wall = SystemClock.elapsedRealtime() * 1000L;
        if (idlePrev < 0) {
            idlePrev = idle;
            wallPrev = wall;
            return -1;
        }
        long di = idle - idlePrev;
        long dw = (wall - wallPrev) * cores;
        idlePrev = idle;
        wallPrev = wall;
        if (dw <= 0) return -1;
        int busy = (int) Math.round((dw - di) * 100.0 / dw);
        return Math.max(0, Math.min(100, busy));
    }

    private long memAvailableMb() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) {
                    String[] t = line.split("\\s+");
                    return t.length >= 2 ? Long.parseLong(t[1]) / 1024 : -1;
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        return -1;
    }

    private static String readFirstLine(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            return r.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static String formatGb(long bytes) {
        if (bytes <= 0) return "";
        double gb = bytes / (1024.0 * 1024 * 1024);
        return gb >= 10 ? String.format(Locale.US, "%.0f GB", gb)
                : String.format(Locale.US, "%.1f GB", gb);
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.appCount();
        int target;
        // Horizontal: swallow the key at a row's ends so focus never wraps to the next row or
        // escapes up into the top bar. Mid-row moves fall through to the default focus search.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            boolean forward = rtlAppGrid ? keyCode == KeyEvent.KEYCODE_DPAD_LEFT : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (forward && position == adapter.focusablePosition(count - 1) && adapter.isLibraryAt(count)) {
                focusLibrary();
                return true;
            }
            // In the dock, the last app is the row's end: the empty slots after it take no focus.
            int rowCount = position < COLUMNS ? Math.min(count, adapter.dockFilled()) : count;
            return GridNavigation.atHorizontalEdge(position, rowCount, COLUMNS,
                    rtlAppGrid, keyCode == KeyEvent.KEYCODE_DPAD_LEFT);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) {
                // dock row: the widgets row when it shows (the card focused there last), else the top bar
                if (widgets != null && widgets.rowShown()) {
                    widgets.focusRow();
                    return true;
                }
                return false;
            }
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) {
                if (adapter.isLibraryAt(count)) focusLibrary();
                return true;
            }
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        // The default focus search clicks for Left/Right; Up/Down are handled here, so click for them too.
        grid.playSoundEffect(keyCode == KeyEvent.KEYCODE_DPAD_UP
                ? SoundEffectConstants.NAVIGATION_UP : SoundEffectConstants.NAVIGATION_DOWN);
        return true;
    }

    @Override public void onLibraryFocused() {
        libraryFocusPending = false;
        hideGlass();
        hideHero();
    }

    @Override public void onLibraryUp() {
        if (adapter.appCount() == 0) { btnWifi.requestFocus(); return; }
        int lastRow = ((adapter.appCount() - 1) / COLUMNS) * COLUMNS;
        focusTile(Math.min(lastRow + lastFocused % COLUMNS, adapter.appCount() - 1));
    }

    private void focusLibrary() {
        cancelPendingFocusScroll();
        int position = adapter.appCount();
        if (!adapter.isLibraryAt(position)) return;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            View button = holder.itemView.findViewById(R.id.btnLibrary);
            Rect visible = new Rect();
            if (button.getGlobalVisibleRect(visible) && visible.height() >= button.getHeight()) {
                if (button.requestFocus()) libraryFocusPending = false;
                return;
            }
        }
        if (!animations) {
            grid.scrollToPosition(position);
            grid.post(() -> {
                if (appLibrary != null || !hasWindowFocus()) return;
                RecyclerView.ViewHolder footer = grid.findViewHolderForAdapterPosition(adapter.appCount());
                if (footer != null && adapter.isLibraryAt(adapter.appCount())
                        && footer.itemView.findViewById(R.id.btnLibrary).requestFocus()) libraryFocusPending = false;
            });
            return;
        }
        pendingFocusScroll = new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                if (pendingFocusScroll != this) return;
                pendingFocusScroll = null;
                RecyclerView.ViewHolder footer = rv.findViewHolderForAdapterPosition(position);
                if (footer != null && footer.itemView.findViewById(R.id.btnLibrary).requestFocus())
                    libraryFocusPending = false;
            }
        };
        grid.addOnScrollListener(pendingFocusScroll);
        grid.smoothScrollToPosition(position);
    }

    private void cancelPendingFocusScroll() {
        if (pendingFocusScroll != null) {
            grid.removeOnScrollListener(pendingFocusScroll);
            pendingFocusScroll = null;
            grid.stopScroll();
        }
    }

    private void focusTile(int position) {
        libraryFocusPending = false;
        cancelPendingFocusScroll();
        if (adapter.appCount() == 0) {
            if (adapter.isLibraryAt(0)) focusLibrary();
            else btnWifi.requestFocus();
            return;
        }
        final int pos = adapter.focusablePosition(Math.max(0, Math.min(position, adapter.appCount() - 1)));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        if (!animations) {
            grid.scrollToPosition(pos);
            grid.post(() -> {
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
                if (h != null) h.itemView.findViewById(R.id.card).requestFocus();
            });
            return;
        }
        pendingFocusScroll = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                if (pendingFocusScroll != this) return;
                pendingFocusScroll = null;
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        };
        grid.addOnScrollListener(pendingFocusScroll);
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /** Some TVs (Coocaa OS) set the network time without ACTION_TIME_CHANGED, so keep checking until it is valid. */
    private final Runnable clockCheck = this::updateClockVisibility;

    private void updateClockVisibility() {
        boolean valid = System.currentTimeMillis() >= TIME_VALID_AFTER_MS;
        clockBlock.setVisibility(valid ? View.VISIBLE : View.INVISIBLE);
        handler.removeCallbacks(clockCheck);
        if (valid) {
            updateGreeting();
        } else {
            handler.postDelayed(clockCheck, 1_000L);
        }
    }

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        String festiveGreeting = festive ? festival.greetingText() : "";
        if (!festiveGreeting.isEmpty()) {
            greeting.setText(festiveGreeting);
            greeting.setTextColor(liftTowardsWhite(festival.accentColor(), 0.25f));
        } else {
            greeting.setText(greetingName.isEmpty() ? getString(res)
                    : getString(R.string.greeting_with_name, getString(res), greetingName));
            greeting.setTextColor(greetingColor);
        }
        if (shamsi) shamsiDate.setText(Shamsi.today());
    }

    // ---- race-weekend panel (a theme's race_calendar) -----------------------------------------------

    private static final int RACE_RED = 0xFFFF3B2F;

    /**
     * Shows or hides the race-weekend panel for this minute. Text only, set once a minute with the clock:
     * nothing animates, so the panel costs the GPU nothing between ticks.
     */
    private void updateRace() {
        if (raceCalendar.isEmpty()) return;
        RaceWeekend.State s = RaceWeekend.at(raceCalendar, java.time.Instant.now());
        if (s == null) {
            racePanel.setVisibility(View.GONE);
            return;
        }
        RaceWeekend.Race race = s.race;
        raceKicker.setText(race.place.isEmpty()
                ? getString(R.string.race_kicker_no_place, race.round, race.shortName())
                : getString(R.string.race_kicker, race.round, race.shortName(), race.place));

        if (s.live != null) {
            SpannableString live = new SpannableString("\u25CF  " + getString(R.string.race_live, s.live.title()));
            live.setSpan(new android.text.style.ForegroundColorSpan(RACE_RED), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            raceHeadline.setText(live);
        } else if (s.next != null) {
            String in = RaceWeekend.countdown(s.untilNext);
            raceHeadline.setText(s.next.isRace() ? getString(R.string.race_lights_out, in)
                    : getString(R.string.race_next, s.next.title(), in));
        } else {
            raceHeadline.setText(R.string.race_finished);
        }

        // FP1 FRI 11:00 PM   FP2 SAT 2:30 AM ...: done sessions fade, the live one is red, the next one bright.
        java.time.format.DateTimeFormatter when = java.time.format.DateTimeFormatter.ofPattern(
                android.text.format.DateFormat.is24HourFormat(this) ? "EEE HH:mm" : "EEE h:mm a", Locale.getDefault())
                .withZone(java.time.ZoneId.systemDefault());
        android.text.SpannableStringBuilder row = new android.text.SpannableStringBuilder();
        for (int i = 0; i < race.sessions.size(); i++) {
            RaceWeekend.Session session = race.sessions.get(i);
            if (i > 0) row.append("     ");
            int start = row.length();
            row.append(session.shortName()).append("  ").append(when.format(session.start).toUpperCase(Locale.getDefault()));
            int color;
            switch (s.statuses.get(i)) {
                case DONE: color = 0x47FFFFFF; break;
                case LIVE: color = RACE_RED; break;
                case NEXT: color = 0xFFFFFFFF; break;
                default: color = 0x99FFFFFF;
            }
            row.setSpan(new android.text.style.ForegroundColorSpan(color), start, row.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        raceSessions.setText(row);
        racePanel.setVisibility(View.VISIBLE);
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false;
        int icon = R.drawable.ic_wifi;
        // The diya's flame: 0 is out (offline), 1 to 4 follow the Wi-Fi signal, Ethernet burns steady at 4.
        int flame = 0;
        boolean steady = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                icon = R.drawable.ic_wifi_off;
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
                flame = 4;
                steady = true;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                flame = info != null ? Math.max(1, Math.min(4, WifiManager.calculateSignalLevel(info.getRssi(), 5))) : 3;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpn || vpnDown) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The pill shows only the icon, so the badge comes alone after it. The space stays: a line holding only
            // the span gets no font metrics and collapses.
            SpannableString label = new SpannableString(" " + badge);
            label.setSpan(span, 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            btnWifi.setText(label);
        } else {
            btnWifi.setText("");
        }
        // Icon only (the network's name stays in the popup), widened into a pill when a VPN badge is showing.
        btnWifi.setContentDescription(text);
        ViewGroup.LayoutParams wifiLp = btnWifi.getLayoutParams();
        int wifiWidth = vpn || vpnDown ? ViewGroup.LayoutParams.WRAP_CONTENT : (int) (42 * density);
        if (wifiLp.width != wifiWidth) {
            wifiLp.width = wifiWidth;
            btnWifi.setLayoutParams(wifiLp);
            btnWifi.setCompoundDrawablePadding(vpn || vpnDown ? (int) (6 * density) : 0);
        }
        if (festive && diya != null) {
            if (online && flame == 0) flame = 3;   // online some other way (cellular, USB tethering)
            diya.setSteady(steady);
            diya.setFlame(flame);
        } else {
            btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        }
    }

    // ---- festive skin ------------------------------------------------------------------------------

    /**
     * Swaps the pill icons for the festival's: a bell for Inputs that swings when focused, a diya for the
     * network whose flame follows the connection, and a marigold for Settings that turns when focused.
     */
    private void applyFestiveSkin(TextView inputs, TextView settings) {
        bell = new BellDrawable(density);
        marigold = new MarigoldDrawable(density);
        diya = new DiyaDrawable(density);
        bell.setReducedMotion(reducedMotion);
        marigold.setReducedMotion(reducedMotion);
        diya.setReducedMotion(reducedMotion);
        setFestiveIcon(inputs, bell);
        setFestiveIcon(btnWifi, diya);
        setFestiveIcon(settings, marigold);
        addFocusAction(inputs, bell::ring);
        addFocusAction(settings, marigold::turn);
    }

    private static void setFestiveIcon(TextView pill, Drawable icon) {
        // The pills tint their icons to follow focus; the festive ones keep their own colours.
        pill.setCompoundDrawableTintList(null);
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
    }

    /** Runs {@code action} when the view gains focus, keeping any focus listener it already had. */
    private static void addFocusAction(View view, Runnable action) {
        View.OnFocusChangeListener previous = view.getOnFocusChangeListener();
        view.setOnFocusChangeListener((v, focused) -> {
            if (previous != null) previous.onFocusChange(v, focused);
            if (focused) action.run();
        });
    }

    private void playPetalShower() {
        if (isFinishing() || isDestroyed()) return;
        PetalShowerView shower = new PetalShowerView(this);
        root.addView(shower, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        shower.play(60, 2200, () -> root.removeView(shower));
    }

    private static int liftTowardsWhite(int argb, float amount) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r += (int) ((255 - r) * amount);
        g += (int) ((255 - g) * amount);
        b += (int) ((255 - b) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    private void openNetworkPill() {
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        open(i != null ? i : systemIntent(Settings.ACTION_WIFI_SETTINGS));
    }


    /**
     * China-market MIUI TV firmware answers no standard Settings intents at all; open Xiaomi's own
     * settings app (com.xiaomi.mitv.settings) there, so the Settings and Wi-Fi pills keep working.
     * On firmware with a standard settings handler the behaviour is unchanged.
     */
    private Intent systemIntent(String standardAction) {
        Intent i = new Intent(standardAction);
        if (i.resolveActivity(getPackageManager()) != null) return i;
        i = new Intent().setComponent(new ComponentName(
                "com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"));
        if (i.resolveActivity(getPackageManager()) != null) return i;
        return new Intent(standardAction);   // nothing found: let open() show the can't-open toast
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                // Inputs hidden in Settings > Inputs are left out of the menu, so they don't count here either.
                if (info.isPassthroughInput() && !info.isHidden(this)) {
                    any = true;
                    break;
                }
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9): a
     *  service, or an activity on sets whose panel is a dialog (BYINTEK U4's QuickSourceActivity). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        try {
            if (getPackageManager().resolveActivity(i, 0) != null) {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Switches to a passthrough input (HDMI, AV). The standard way is ACTION_VIEW on its channel URI,
     *  which the Live TV app handles; where nothing takes that, the configured `input_action` is sent
     *  with the same URI instead (XGIMI's HDMI player only answers com.xgimi.action.hdmiPlayer). */
    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        // A configured source panel and no Live TV row to offer next to it: the panel is the whole menu.
        // The BYINTEK U4 needs this, as its TvInputManager lists three HDMI ports and an AV input it doesn't have.
        if (!sourcePanelService.isEmpty() && liveTvIntent() == null) {
            openSourcePanel();
            return;
        }
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        boolean tunerIsAnalog = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // An HDMI-CEC device (an Apple TV, a soundbar) gets an input of its own whose parent is the
            // port it sits on, so the port and the device would each get a row (Vestel MT7332: "HDMI 1"
            // and "HDMI Apple TV"). The device takes its port's row instead, and a bare port is listed
            // only when nothing on it speaks CEC.
            Set<String> inputIds = new HashSet<>();
            for (TvInputInfo info : inputs) inputIds.add(info.getId());
            Map<String, List<TvInputInfo>> cecDevices = new HashMap<>();
            for (TvInputInfo info : inputs) {
                String parent = info.getParentId();
                if (parent == null || !inputIds.contains(parent)) continue;
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                List<TvInputInfo> onPort = cecDevices.get(parent);
                if (onPort == null) cecDevices.put(parent, onPort = new ArrayList<>());
                onPort.add(info);
            }
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    // Among tuners of the same kind, skip an analog input when another is present.
                    // MediaTek names them AnalogInputService and TunerInputService; alphabetical order
                    // would pick the analog one, and Live TV then opens a dead ATV source.
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    boolean analog = info.getId().toLowerCase(Locale.ROOT).contains("analog");
                    boolean better = tunerId == null
                            || (hw && !tunerIsHw)
                            || (hw == tunerIsHw && tunerIsAnalog && !analog)
                            || (hw == tunerIsHw && analog == tunerIsAnalog
                                    && info.getId().compareTo(tunerId) < 0);
                    if (better) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                        tunerIsAnalog = analog;
                    }
                }
                // Inputs hidden in the TV's Settings > Inputs stay out of the menu too.
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                // A CEC device's row is added with its port, below.
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                CharSequence custom = info.loadCustomLabel(this);
                boolean named = custom != null && custom.length() > 0 && !custom.toString().equals(label);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                List<TvInputInfo> devices = cecDevices.get(info.getId());
                if (devices != null) {
                    // "Apple TV · HDMI 1": the device's own name (or the one given to it or to its port in
                    // Settings > Inputs), then the port. Opening the device's input also makes it the CEC
                    // active source, which wakes it.
                    for (TvInputInfo device : devices) {
                        CharSequence name = device.loadCustomLabel(this);
                        if (name == null || name.length() == 0) name = named ? custom : device.loadLabel(this);
                        String deviceLabel = name == null || name.length() == 0 ? label : name + " \u00B7 " + label;
                        final String deviceId = device.getId();
                        int state = hdmiStateBogus ? -1 : tim.getInputState(deviceId);
                        addInputRow(rows, popup, R.drawable.ic_input, deviceLabel, state, () -> openInput(deviceId));
                    }
                    continue;
                }
                // The name given in Settings > Inputs (e.g. "MacBook Pro") goes first, the port after it.
                if (named) label = custom + " \u00B7 " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            // Tune that input only when the Live TV package itself handles the URI.
            // The unrestricted input player also resolves it (that is how HDMI opens), but on the
            // AIWA AI PONT SA that player stays black, and the Live TV package does not handle the
            // URI at all, which used to toast "Not available on this TV". Keep the leanback activity
            // then: it is the same path as the Live TV tile. Do not drop the package restriction.
            Intent tuned = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId),
                            "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
            if (tuned.resolveActivity(getPackageManager()) != null) liveTv = tuned;
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, Math.min(panel.getMeasuredWidth(), (int) (360 * density))));

        int alignment = getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.END : Gravity.START;
        dropIn(popup, panel, anchor, alignment);
        rows.getChildAt(0).requestFocus();
    }

    private PopupWindow glassPopup(View panel, int width) {
        // Liquid Glass menus: the blurred wallpaper under a dark veil. Drawn once per focus move, like the pills.
        // With advanced effects the menus are the dock's clear refracting glass; otherwise the darker veiled glass.
        if (liquidGlass) {
            panel.setBackground(advancedEffects ? LiquidGlass.clearPanel(20 * density, density)
                    : LiquidGlass.panel(20 * density, density));
        }
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        panel.setAlpha(0f);
        if (advancedEffects && animations) {
            // Unfold from the pill, the glass redrawn on every frame so the wallpaper behind it never jumps.
            panel.setPivotY(0f);
            panel.setScaleY(0.92f);
            panel.setTranslationY(-8 * density);
            popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
            panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                    .setUpdateListener(a -> panel.invalidate()).start();
        } else {
            // A plain fade in place: the glass is drawn once, where the menu stays, so nothing shifts under it.
            popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
            panel.animate().alpha(1f).setDuration(animations ? 200 : 0).start();
        }
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    // ---- launcher settings and module cards -------------------------------------------------------

    /** Keep a normal Settings click for the TV; the launcher's choices live behind hold OK. */
    // ---- online wallpapers (Unsplash) ------------------------------------------------------------------

    /**
     * When online wallpapers are on and the rotation is due (or {@code now}), fetch the next picture and fade to it.
     * Called from onResume and the minute tick, so it only ever runs while Home is on screen.
     */
    private void refreshOnlineWallpaper(boolean now) {
        if (!OnlineWallpapers.active(this)) {
            showOnlineCredit(null);
            return;
        }
        showOnlineCredit(OnlineWallpapers.credit(this));
        if (!now && !OnlineWallpapers.due(this)) return;
        OnlineWallpapers.fetch(this, picture -> {
            if (picture == null || isFinishing()) return;
            wallpaperPlayer.showOnline(picture);
            showOnlineCredit(OnlineWallpapers.credit(this));
        });
    }

    /** "Photo: author · licence · Wikimedia Commons", small at the top right under the pills, as the licence asks. Gone otherwise. */
    private void showOnlineCredit(String name) {
        TextView credit = findViewById(R.id.onlineCredit);
        if (credit == null) return;
        credit.setVisibility(name == null ? View.GONE : View.VISIBLE);
        if (name != null) credit.setText(getString(R.string.online_credit, name));
    }

    private String stepperLabel(int minutes) {
        return "\u2039  " + intervalLabel(minutes) + "  \u203A";
    }

    private String intervalLabel(int minutes) {
        if (minutes <= 0) return getString(R.string.online_never);
        return minutes >= 1440 ? getString(R.string.online_daily)
                : minutes >= 60 ? getString(R.string.online_hours, minutes / 60)
                : getString(R.string.online_minutes, minutes);
    }

    /** A menu row with a glass toggle on the right; OK flips it and reports the new state. */
    private View toggleRow(String title, boolean on, java.util.function.Consumer<Boolean> changed) {
        View row = menuRow(title, "");
        row.findViewById(R.id.status).setVisibility(View.GONE);
        GlassToggle toggle = new GlassToggle(this);
        toggle.setChecked(on, false);
        ((LinearLayout) row).addView(toggle);
        row.getLayoutParams().height = (int) (48 * density);
        if (changed != null) {
            row.setOnClickListener(v -> {
                toggle.setChecked(!toggle.isChecked(), true);
                changed.accept(toggle.isChecked());
            });
        }
        return row;
    }

    /**
     * Glass lettering for the greeting, clock and date: white fading towards the bottom like frosted glass lit from
     * above, with a soft shadow under it; "glass_thin" also uses the thinnest weight. Drawn once per text change.
     */
    private void applyClockStyle(String style) {
        if (style.equals("normal")) return;
        int[] ids = {R.id.greeting, R.id.clock, R.id.ampm, R.id.date, R.id.shamsiDate};
        for (int id : ids) {
            TextView t = findViewById(id);
            if (t == null) continue;
            if (style.equals("glass_thin") && id == R.id.clock) t.setTypeface(getResources().getFont(R.font.outfit_extralight));
            t.setTextColor(0xFFFFFFFF);
            t.setShadowLayer(10 * density, 0, 2 * density, 0x55000000);
            t.addOnLayoutChangeListener((v, l, top, r, b, ol, ot, or, ob) -> {
                if (b - top == ob - ot && ((TextView) v).getPaint().getShader() != null) return;
                ((TextView) v).getPaint().setShader(new LinearGradient(0, 0, 0, b - top,
                        new int[]{0xFFFFFFFF, 0xCCFFFFFF, 0x80FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                v.invalidate();
            });
        }
    }

    /**
     * Wallpaper "None": there is no picture for the glass to blur, so it takes what is behind it, the background colour
     * and the app colour glow, and blurs that as the blur setting says. Rebuilt a moment after the glow settles on a
     * new colour (a small bitmap), not on every frame of its fade.
     */
    private final Runnable glowBackdrop = () -> {
        if (!liquidGlass || glowOff || isFinishing()) return;
        int w = 480, h = 270;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(backgroundColor);
        Drawable g = ambient.getBackground();
        if (g != null) {
            // Draw the glow scaled down, without touching its own bounds: the glow view on screen keeps its full size.
            c.save();
            c.scale(w / (float) Math.max(1, ambient.getWidth()), h / (float) Math.max(1, ambient.getHeight()));
            c.saveLayerAlpha(0, 0, ambient.getWidth(), ambient.getHeight(), Math.round(255 * ambient.getAlpha()));
            g.draw(c);
            c.restore();
            c.restore();
        }
        LiquidGlass.setBackdrop(b, getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels, 0, Extras.glassBlur(this));
        invalidateGlass();
    };

    private void scheduleGlowBackdrop() {
        if (!liquidGlass || glowOff) return;
        handler.removeCallbacks(glowBackdrop);
        handler.postDelayed(glowBackdrop, 150);
    }

    private GlintView uiGlint;
    /** The last arrow pressed (-1, 0 or 1 on each axis) and when; for the side the focus light enters from. */
    private int lastMoveX, lastMoveY;
    private long lastMoveAt;

    /** The move behind the focus change that is happening now: the last arrow, if it was pressed just now. */
    private boolean recentMove() {
        return SystemClock.uptimeMillis() - lastMoveAt < 600 && (lastMoveX != 0 || lastMoveY != 0);
    }

    /** The views that get the glass glow on focus: the top pills (the widget cards only cross-fade their glass). */
    private boolean litView(View v) {
        if (v == null) return false;
        int id = v.getId();
        return id == R.id.btnInputs || id == R.id.btnWifi || id == R.id.btnCalendar || id == R.id.btnNight
                || id == R.id.btnReorder || id == R.id.btnSettings || litCard(v)
                || HomeWidgets.LIT_PILL.equals(v.getTag());
    }

    /** The widgets row's cards: the light takes their 22dp corners (and zooms with them under advanced effects). */
    private static boolean litCard(View v) {
        return v.getId() == R.id.btcCard || v.getId() == R.id.playerCard || HomeWidgets.LIT_CARD.equals(v.getTag());
    }

    /** Puts the glow on {@code to} at its size and corners, and fades it in. */
    private void lightUp(View from, View to) {
        if (uiGlint == null) return;
        litNow = null;
        if (!litView(to) || !to.isShown() || !to.isAttachedToWindow()) {
            uiGlint.animate().cancel();
            uiGlint.animate().alpha(0f).setDuration(animations ? 90 : 0).start();
            return;
        }
        Rect r = new Rect(0, 0, to.getWidth(), to.getHeight());
        try {
            root.offsetDescendantRectToMyCoords(to, r);
        } catch (IllegalArgumentException notOnHome) {
            // a view of the Home being rebuilt (recreate) or of another window: no glow for it
            return;
        }
        int pad = (int) (6 * density);   // GlintView keeps 6dp round the slab for nothing else to draw in
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) uiGlint.getLayoutParams();
        lp.width = r.width() + 2 * pad;
        lp.height = r.height() + 2 * pad;
        uiGlint.setLayoutParams(lp);
        uiGlint.setTranslationX(r.left - pad);
        uiGlint.setTranslationY(r.top - pad);
        boolean pill = !litCard(to);
        litNow = to;
        uiGlint.setRadius(pill ? r.height() / 2f : 22 * density);
        // A widget card zooms a little with advanced effects: the light zooms with it, from the same centre.
        float scale = !pill && advancedEffects && animations ? 1.04f : 1f;
        uiGlint.setPivotX(lp.width / 2f);
        uiGlint.setPivotY(lp.height / 2f);
        uiGlint.setScaleX(scale);
        uiGlint.setScaleY(scale);
        // The pills get no travelling light: a soft glass glow at their top that fades in with focus.
        uiGlint.sweep(0f, 0f, false);
        uiGlint.animate().cancel();
        uiGlint.setAlpha(0f);
        uiGlint.animate().alpha(1f).setDuration(animations ? 120 : 0).start();
    }

    /** The view the focus light is on, while it is on one. */
    private View litNow;

    /** The widgets row slid: the light moves with the focused card (only moved, nothing redrawn). */
    private void followGlint() {
        if (litNow == null || uiGlint == null || !litNow.isAttachedToWindow()) return;
        Rect r = new Rect(0, 0, litNow.getWidth(), litNow.getHeight());
        try {
            root.offsetDescendantRectToMyCoords(litNow, r);
        } catch (IllegalArgumentException gone) {
            return;
        }
        int pad = (int) (6 * density);
        uiGlint.setTranslationX(r.left - pad);
        uiGlint.setTranslationY(r.top - pad);
    }

    /** Repaints every Liquid Glass surface after the blurred wallpaper behind them changed. */
    private void invalidateGlass() {
        for (int id : new int[]{R.id.btnInputs, R.id.btnWifi, R.id.btnCalendar, R.id.btnNight, R.id.btnReorder, R.id.btnSettings}) {
            View v = findViewById(id);
            if (v != null) v.invalidate();
        }
        grid.invalidateItemDecorations();
        if (widgets != null) widgets.invalidate();
    }

    private void showSettingsMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (200 * density));
        addSettingsMenuRow(rows, popup, R.drawable.ic_home_settings, R.string.launcher_settings, () -> startActivityForResult(
                new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST));
        addSettingsMenuRow(rows, popup, R.drawable.ic_widgets, R.string.section_widgets, this::showWidgetsMenu);
        addSettingsMenuRow(rows, popup, R.drawable.ic_picture, R.string.section_wallpaper, this::showWallpaperMenu);
        if (dockRow) addSettingsMenuRow(rows, popup, R.drawable.ic_apps, R.string.dock_size, this::showDockSizeMenu);
        // One mode for both: the dock is the first row, so carrying an app up into it is how the dock is edited.
        addSettingsMenuRow(rows, popup, R.drawable.ic_swap_vert, R.string.organize_apps, this::toggleReorder);
        // Only when an installed app offers a card; with none the row only led to "none installed".
        if (moduleCards && !cards.isEmpty()) {
            addSettingsMenuRow(rows, popup, R.drawable.ic_widgets, R.string.module_cards, this::showModuleCards);
        }
        addSettingsMenuRow(rows, popup, R.drawable.ic_tv, R.string.tv_settings, () -> open(systemIntent(Settings.ACTION_SETTINGS)));
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (200 * density), panel.getMeasuredWidth()));
        // Hebrew puts Settings at the left of the header: grow the menu inward from it.
        dropIn(popup, panel, anchor, getResources().getBoolean(R.bool.rtl_menu_bar)
                ? Gravity.START : Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    // ---- quick menus under Settings: widgets, wallpaper, dock size --------------------------------------

    /** A glass dropdown with a heading under the Settings pill; rows scroll past about eight. */
    private PopupWindow showListMenu(int heading, List<View> items, View focus, Runnable onDismiss) {
        return showListMenu(heading, items, focus, onDismiss, findViewById(R.id.btnSettings),
                getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.START : Gravity.END);
    }

    private PopupWindow showListMenu(int heading, List<View> items, View focus, Runnable onDismiss, View anchor,
                                     int gravity) {
        View panel = getLayoutInflater().inflate(R.layout.popup_list, root, false);
        ((TextView) panel.findViewById(R.id.heading)).setText(heading);
        LinearLayout rows = panel.findViewById(R.id.rows);
        for (View item : items) rows.addView(item);
        int width = (int) (280 * density);
        PopupWindow popup = glassPopup(panel, width);
        // At most about eight rows, and never past the bottom of the screen: a menu hanging from a widget starts
        // lower than one hanging from the Settings pill.
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int room = getResources().getDisplayMetrics().heightPixels - at[1] - anchor.getHeight() - (int) (80 * density);
        int maxRows = Math.max((int) (3 * 42 * density), Math.min((int) (8.5f * 42 * density), room));
        rows.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        if (rows.getMeasuredHeight() > maxRows) {
            ScrollView scroll = panel.findViewById(R.id.scroll);
            scroll.getLayoutParams().height = maxRows;
        }
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
            if (onDismiss != null) onDismiss.run();
        });
        dropIn(popup, panel, anchor, gravity);
        (focus != null ? focus : items.get(0)).requestFocus();
        return popup;
    }

    /** A menu row: a title and a short status on the right (On/Off, a tick). */
    private View menuRow(String title, String status) {
        View row = getLayoutInflater().inflate(R.layout.item_input, root, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView mark = row.findViewById(R.id.status);
        mark.setText(status);
        // The tick of the current choice: blue and larger, so it reads on the white focused row as on the glass.
        if ("✓".equals(status)) {
            mark.setTextColor(0xFF0A84FF);
            mark.setTextSize(20);
            mark.setTypeface(getResources().getFont(R.font.outfit_medium));
        }
        return row;
    }

    /** Widgets: each one switched on or off in place; Home rebuilds once the menu closes. */
    private void showWidgetsMenu() {
        String[] keys = {Extras.BTC_TICKER, Extras.NOW_PLAYING, Extras.CONTINUE_WATCHING, Extras.CALENDAR};
        int[] titles = {R.string.widget_btc, R.string.widget_music, R.string.widget_continue, R.string.widget_calendar};
        boolean[] values = {Extras.btcTicker(this), Extras.nowPlaying(this), Extras.continueWatching(this),
                Extras.calendar(this)};
        boolean[] changed = {false};
        List<View> items = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            final int k = i;
            View row = menuRow(getString(titles[i]), "");
            row.findViewById(R.id.status).setVisibility(View.GONE);
            GlassToggle toggle = new GlassToggle(this);
            toggle.setChecked(values[i], false);
            ((LinearLayout) row).addView(toggle);
            row.getLayoutParams().height = (int) (48 * density);
            row.setOnClickListener(v -> {
                values[k] = !values[k];
                getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit().putBoolean(keys[k], values[k]).apply();
                // Continue watching off: what it wrote down goes too.
                if (keys[k].equals(Extras.CONTINUE_WATCHING) && !values[k]) WatchHistory.clear(this);
                toggle.setChecked(values[k], true);
                changed[0] = true;
            });
            items.add(row);
        }
        // The order of the cards in the widgets row: its own small menu.
        boolean[] reorder = {false};
        View order = menuRow(getString(R.string.widget_order), "\u203A");
        order.setOnClickListener(v -> {
            reorder[0] = true;
            if (openPopup != null) openPopup.dismiss();
        });
        items.add(order);
        showListMenu(R.string.section_widgets, items, null, () -> {
            if (reorder[0]) {
                // The toggles are saved already; the row is rebuilt when the order menu closes.
                if (changed[0]) Extras.bumpRevision(this);
                handler.post(() -> showWidgetOrderMenu(changed[0]));
                return;
            }
            if (!changed[0]) return;
            Extras.bumpRevision(this);
            recreate();
        });
    }

    /**
     * The widgets row's order: OK picks a widget up (it turns blue), Up and Down carry it, OK puts it down. Home is
     * rebuilt in the new order when the menu closes.
     */
    private void showWidgetOrderMenu(boolean rebuild) {
        List<String> order = Extras.widgetOrder(this);
        List<View> items = new ArrayList<>();
        String[] carried = {null};
        boolean[] moved = {false};
        for (String w : order) {
            int title = w.equals("market") ? R.string.widget_btc : w.equals("player") ? R.string.widget_music
                    : R.string.widget_continue;
            View row = menuRow(getString(title), "\u2195");
            TextView status = row.findViewById(R.id.status);
            row.setOnClickListener(v -> {
                carried[0] = carried[0] == null ? w : null;
                status.setText(carried[0] != null ? "\u25B2\u25BC" : "\u2195");
                TextView name = row.findViewById(R.id.title);
                if (carried[0] != null) name.setTextColor(0xFF0A84FF);
                else name.setTextColor(getColorStateList(R.color.pill_text));
            });
            row.setOnKeyListener((v, keyCode, event) -> {
                if (carried[0] == null || !w.equals(carried[0])) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
                ViewGroup parent = (ViewGroup) v.getParent();
                int at = parent.indexOfChild(v);
                int to = at + (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                // Only among the widgets: the heading or anything else in the list stays put.
                int first = Integer.MAX_VALUE;
                for (View item : items) first = Math.min(first, parent.indexOfChild(item));
                if (to < first || to >= first + items.size()) return true;
                parent.removeView(v);
                parent.addView(v, to);
                v.requestFocus();
                int i = order.indexOf(w);
                order.remove(i);
                order.add(i + (to - at), w);
                moved[0] = true;
                return true;
            });
            items.add(row);
        }
        showListMenu(R.string.widget_order, items, null, () -> {
            if (moved[0]) Extras.setWidgetOrder(this, order);
            if (!moved[0] && !rebuild) return;
            Extras.bumpRevision(this);
            recreate();
        });
    }

    /**
     * Wallpaper, in sections, each with its icon: on top None (OLED black) and Background from icon (adaptive), then
     * Rotating wallpaper, whose settings and Change now unfold under it, then Colours (the gradients, folded), then the
     * pictures one by one. Moving through the list previews each wallpaper behind the menu with a cross-fade; OK keeps
     * it, Back puts the old one back.
     */
    private void showWallpaperMenu() {
        SharedPreferences prefs = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        String current = prefs.getString(Config.WALLPAPER_CHOICE_KEY, null);
        boolean[] picked = {false};    // another wallpaper chosen: Home is rebuilt around it
        boolean[] refetch = {false};   // rotating categories changed: the next picture comes from them
        List<View> items = new ArrayList<>();
        View[] focus = {null};
        List<WallpaperChoices.Choice> all = WallpaperChoices.all(this);
        java.util.Map<String, WallpaperChoices.Choice> byValue = new java.util.HashMap<>();
        for (WallpaperChoices.Choice c : all) byValue.put(c.value, c);

        java.util.function.BiFunction<WallpaperChoices.Choice, Integer, View> pick = (choice, icon) -> {
            boolean isCurrent = choice.value.equals(current);
            View row = menuRow(choice.label, isCurrent ? "✓" : "");
            menuIcon(row, icon);
            if (isCurrent) focus[0] = row;
            row.setOnFocusChangeListener((v, has) -> {
                if (has) wallpaperPlayer.preview(choice);
            });
            row.setOnClickListener(v -> {
                picked[0] = true;
                prefs.edit().putString(Config.WALLPAPER_CHOICE_KEY, choice.value).apply();
                Extras.bumpRevision(this);
                if (openPopup != null) openPopup.dismiss();
            });
            return row;
        };

        // None (OLED black) and Background from icon, on top
        items.add(pick.apply(byValue.get(WallpaperChoices.OLED), R.drawable.ic_oled));
        items.add(pick.apply(byValue.get(WallpaperChoices.NONE), R.drawable.ic_adaptive));

        // Rotating wallpaper: its own settings and Change now fold under it
        boolean rotating = OnlineWallpapers.CHOICE.equals(current);
        WallpaperChoices.Choice online = byValue.get(OnlineWallpapers.CHOICE);
        View rot = menuRow(online.label, rotating ? "✓" : "");
        menuIcon(rot, R.drawable.ic_rotate);
        if (rotating) focus[0] = rot;
        List<View> rotSettings = new ArrayList<>();
        View now = menuRow(getString(R.string.online_change_now), "");
        now.setPaddingRelative(now.getPaddingStart() + (int) (16 * density), 0, now.getPaddingEnd(), 0);
        now.setOnClickListener(v -> {
            if (openPopup != null) openPopup.dismiss();
            if (OnlineWallpapers.active(this)) refreshOnlineWallpaper(true);
        });
        rotSettings.add(now);
        rotSettings.addAll(rotatingRows(refetch));
        rot.setOnFocusChangeListener((v, has) -> {
            if (has) wallpaperPlayer.preview(online);
        });
        rot.setOnClickListener(v -> {
            LinearLayout list = (LinearLayout) v.getParent();
            if (!OnlineWallpapers.CHOICE.equals(prefs.getString(Config.WALLPAPER_CHOICE_KEY, null))) {
                // Turning rotation on: tick it and unfold its settings so categories can be picked now.
                picked[0] = true;
                prefs.edit().putString(Config.WALLPAPER_CHOICE_KEY, OnlineWallpapers.CHOICE).apply();
                Extras.bumpRevision(this);
                for (int i = 0; i < list.getChildCount(); i++) {
                    TextView status = list.getChildAt(i).findViewById(R.id.status);
                    if (status != null && "✓".contentEquals(status.getText())) status.setText("");
                }
                ((TextView) v.findViewById(R.id.status)).setText("✓");
            }
            fold(list, v, rotSettings, rotSettings.get(0).getParent() == null);
        });
        items.add(rot);
        if (rotating) items.addAll(rotSettings);

        // Upload from phone: a QR code for the phone to send a picture, which becomes the wallpaper as it arrives
        View upload = menuRow(getString(R.string.upload_phone), "");
        menuIcon(upload, R.drawable.ic_qr);
        upload.setOnClickListener(v -> {
            if (openPopup != null) openPopup.dismiss();
            handler.postDelayed(this::showUploadQr, 200);
        });
        items.add(upload);

        // Colours: the gradients, folded under one row
        List<View> colours = new ArrayList<>();
        // NASA and Retro pictures (by file name prefix), each in its own folder like the colours
        List<View> nasa = new ArrayList<>();
        List<View> retro = new ArrayList<>();
        List<View> loose = new ArrayList<>();
        List<View> uploads = new ArrayList<>();
        for (WallpaperChoices.Choice c : all) {
            if (c.value.equals(WallpaperChoices.OLED) || c.value.equals(WallpaperChoices.NONE)
                    || c.value.equals(OnlineWallpapers.CHOICE)) continue;
            String name = c.value.toLowerCase(java.util.Locale.ROOT);
            boolean colour = c.value.startsWith(BuiltinWallpapers.PREFIX)
                    && BuiltinWallpapers.isColour(c.value.substring(BuiltinWallpapers.PREFIX.length()));
            List<View> into = colour ? colours : name.startsWith(WallpaperChoices.NASA) ? nasa
                    : name.startsWith(WallpaperChoices.RETRO) ? retro : name.startsWith(PhoneUpload.PREFIX) ? uploads : loose;
            View row = pick.apply(c, colour ? R.drawable.ic_palette : R.drawable.ic_picture);
            if (into != loose) row.setPaddingRelative(row.getPaddingStart() + (int) (16 * density), 0, row.getPaddingEnd(), 0);
            if (into == uploads) addDeleteButton(row, c, uploads, picked);
            into.add(row);
        }
        if (!nasa.isEmpty()) {
            // The credit, quietly at the foot of the folder: small, dim and never focused.
            View credit = menuRow(getString(R.string.wallpaper_nasa_credit), "");
            credit.setFocusable(false);
            credit.setAlpha(0.4f);
            ((TextView) credit.findViewById(R.id.title)).setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
            nasa.add(credit);
        }
        folder(items, getString(R.string.wallpaper_uploads), R.drawable.ic_upload, uploads, focus);
        folder(items, getString(R.string.wallpaper_colours), R.drawable.ic_palette, colours, focus);
        folder(items, getString(R.string.wallpaper_nasa), R.drawable.ic_space, nasa, focus);
        folder(items, getString(R.string.wallpaper_retro), R.drawable.ic_retro, retro, focus);

        // The other pictures, one by one: built-in scenes and the theme's own
        items.addAll(loose);

        showListMenu(R.string.section_wallpaper, items, focus[0], () -> {
            // New categories should show at once, not at the next rotation.
            if (refetch[0]) OnlineWallpapers.expire(this);
            if (picked[0]) {
                // Let the last fade finish before Home is rebuilt around the new picture.
                handler.postDelayed(this::recreate, 340);
            } else {
                wallpaperPlayer.endPreview();
                if (refetch[0] && OnlineWallpapers.active(this)) refreshOnlineWallpaper(true);
            }
        });
    }

    /**
     * Adds a folder row to a menu: {@code children} unfold under it on OK. It starts open when one of them is the
     * current (focused) row, and is left out when it has nothing in it.
     */
    private void folder(List<View> items, String label, int icon, List<View> children, View[] focus) {
        if (children.isEmpty()) return;
        boolean open = children.contains(focus[0]);
        View row = menuRow(label, open ? "⌄" : "›");
        menuIcon(row, icon);
        row.setOnClickListener(v -> {
            if (children.isEmpty()) return;
            boolean opening = children.get(0).getParent() == null;
            fold((LinearLayout) v.getParent(), v, children, opening);
            ((TextView) v.findViewById(R.id.status)).setText(opening ? "⌄" : "›");
        });
        items.add(row);
        if (open) items.addAll(children);
    }

    /**
     * Puts an X at the end of an uploaded picture's row: Right (Left in a right-to-left language) reaches it and OK
     * deletes the picture. A deleted wallpaper that was in use gives way to OLED black when the menu closes.
     */
    private void addDeleteButton(View row, WallpaperChoices.Choice choice, List<View> rows, boolean[] picked) {
        ImageView x = new ImageView(this);
        x.setImageResource(R.drawable.ic_close);
        x.setBackgroundResource(R.drawable.close_bg);
        int size = (int) (30 * density), pad = (int) (7 * density);
        x.setPadding(pad, pad, pad, pad);
        x.setFocusable(true);
        x.setContentDescription(getString(R.string.wallpaper_delete));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginStart((int) (6 * density));
        ((LinearLayout) row).addView(x, lp);
        // A focused row turns white: the X goes dark on it, like the row's text, so it stays in sight.
        View.OnFocusChangeListener rowFocus = row.getOnFocusChangeListener();
        row.setOnFocusChangeListener((v, has) -> {
            if (rowFocus != null) rowFocus.onFocusChange(v, has);
            x.setImageTintList(android.content.res.ColorStateList.valueOf(has ? 0xFF111111 : 0xFFFFFFFF));
            if (has) x.getBackground().mutate().setTint(0x1F000000);
            else x.getBackground().setTintList(null);
        });
        boolean rtl = getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int toX = rtl ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
        int toRow = rtl ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
        row.setOnKeyListener((v, code, e) -> {
            if (code != toX) return false;
            if (e.getAction() == KeyEvent.ACTION_DOWN) x.requestFocus();
            return true;
        });
        x.setOnKeyListener((v, code, e) -> {
            if (code == toX) return true;
            if (code != toRow) return false;
            if (e.getAction() == KeyEvent.ACTION_DOWN) row.requestFocus();
            return true;
        });
        x.setOnClickListener(v -> {
            LinearLayout list = (LinearLayout) row.getParent();
            if (list == null) return;
            //noinspection ResultOfMethodCallIgnored
            choice.file.delete();
            int at = list.indexOfChild(row);
            rows.remove(row);
            list.removeView(row);
            if (rows.isEmpty()) list.removeViewAt(--at);   // the folder's own row goes with its last picture
            SharedPreferences prefs = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
            if (choice.value.equals(prefs.getString(Config.WALLPAPER_CHOICE_KEY, null))) {
                prefs.edit().putString(Config.WALLPAPER_CHOICE_KEY, WallpaperChoices.OLED).apply();
                Extras.bumpRevision(this);
                picked[0] = true;
            }
            View next = list.getChildAt(Math.min(at, list.getChildCount() - 1));
            if (next != null) next.requestFocus();
        });
    }

    /**
     * Upload from phone: a QR code to a page on this TV (see PhoneUpload). A picture sent from it is saved with the
     * other uploads and becomes the wallpaper at once; the page stops working when this closes.
     */
    private void showUploadQr() {
        File dir = Config.dir(this);
        boolean[] uploaded = {false};
        PhoneUpload server = dir == null ? null : PhoneUpload.start(this, new File(dir, "wallpapers"), file -> {
            getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit()
                    .putString(Config.WALLPAPER_CHOICE_KEY, file.getName()).apply();
            Extras.bumpRevision(this);
            uploaded[0] = true;
            if (openPopup != null) openPopup.dismiss();
        });
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (12 * density);
        box.setPadding(pad, pad / 2, pad, pad);
        box.setFocusable(true);
        android.graphics.Bitmap code = server == null ? null : PhoneUpload.qr(server.url(), (int) (196 * density));
        if (code != null) {
            ImageView qr = new ImageView(this);
            qr.setImageBitmap(code);
            qr.setScaleType(ImageView.ScaleType.CENTER);
            qr.setBackground(roundedWhite(14 * density));
            qr.setClipToOutline(true);
            box.addView(qr, new LinearLayout.LayoutParams(code.getWidth(), code.getHeight()));
        }
        TextView hint = new TextView(this);
        hint.setText(server == null ? R.string.upload_no_network : R.string.upload_tv_hint);
        hint.setTextColor(0xD9FFFFFF);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, pad, 0, 0);
        box.addView(hint);
        if (server != null) {
            TextView address = new TextView(this);
            address.setText(server.url().replaceFirst("^http://", ""));
            address.setTextColor(0x73FFFFFF);
            address.setTextSize(11);
            address.setGravity(Gravity.CENTER);
            address.setPadding(0, pad / 2, 0, 0);
            box.addView(address);
        }
        showListMenu(R.string.upload_phone, java.util.Collections.singletonList(box), box, () -> {
            if (server != null) server.stop();
            if (uploaded[0]) recreate();
        });
    }

    private static android.graphics.drawable.GradientDrawable roundedWhite(float radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(0xFFFFFFFF);
        d.setCornerRadius(radius);
        return d;
    }

    /** Unfolds {@code children} right under {@code header} in a menu list, or folds them away. */
    private void fold(LinearLayout list, View header, List<View> children, boolean open) {
        if (open) {
            int at = list.indexOfChild(header);
            for (int i = 0; i < children.size(); i++) {
                if (children.get(i).getParent() == null) list.addView(children.get(i), at + 1 + i);
            }
            children.get(0).requestFocus();
        } else {
            for (View child : children) list.removeView(child);
            header.requestFocus();
        }
    }

    /** Shows {@code icon} at the start of a menu row (rows are made without one). */
    private void menuIcon(View row, int icon) {
        ImageView image = row.findViewById(R.id.icon);
        image.setImageResource(icon);
        image.setVisibility(View.VISIBLE);
    }

    /** Rotating wallpaper's own rows, set in under its choice: a toggle per category, then the interval. */
    private List<View> rotatingRows(boolean[] changed) {
        SharedPreferences prefs = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        List<View> rows = new ArrayList<>();
        int indent = (int) (16 * density);
        List<String> enabled = OnlineWallpapers.categories(this);
        for (String[] category : OnlineWallpapers.ALL) {
            String id = category[0];
            int label = getResources().getIdentifier("online_cat_" + id, "string", getPackageName());
            View row = toggleRow(label != 0 ? getString(label) : id, enabled.contains(id), on -> {
                OnlineWallpapers.setCategory(this, id, on);
                changed[0] = true;
            });
            row.setPaddingRelative(row.getPaddingStart() + indent, 0, row.getPaddingEnd(), 0);
            rows.add(row);
        }
        // A stepper: Left and Right move through the intervals (OK steps forward), shown as "‹ 30 min ›" in the
        // row's own text size so the change is visible at sofa distance.
        View every = menuRow(getString(R.string.online_every), "");
        every.setPaddingRelative(every.getPaddingStart() + indent, 0, every.getPaddingEnd(), 0);
        TextView value = every.findViewById(R.id.status);
        value.setTextSize(15f);
        value.setTextColor(getColorStateList(R.color.pill_text));
        value.setText(stepperLabel(OnlineWallpapers.intervalMinutes(this)));
        java.util.function.IntConsumer step = by -> {
            int[] all = OnlineWallpapers.INTERVALS;
            int now = OnlineWallpapers.intervalMinutes(this), at = 0;
            for (int i = 0; i < all.length; i++) if (all[i] == now) at = i;
            int next = all[(at + by + all.length) % all.length];
            prefs.edit().putInt(OnlineWallpapers.INTERVAL, next).apply();
            value.setText(stepperLabel(next));
            value.setScaleX(0.85f);
            value.setScaleY(0.85f);
            value.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
        };
        every.setOnClickListener(v -> step.accept(1));
        every.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) step.accept(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            return true;
        });
        rows.add(every);
        return rows;
    }

    /**
     * OK on the market card: show or hide the line, and pick what it follows, one asset or up to five it rotates
     * through: crypto pairs from Binance (plus any pair found with Search) or indices and gold from Yahoo Finance.
     * Hangs under the card; every change shows on the card at once.
     */
    private void showMarketMenu() {
        SharedPreferences prefs = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        List<String> picked = new ArrayList<>();
        for (MarketTicker.Asset a : Extras.marketAssets(this)) picked.add(a.id);
        List<View> items = new ArrayList<>();
        items.add(toggleRow(getString(R.string.market_show_chart), Extras.marketChart(this), on -> {
            prefs.edit().putBoolean(Extras.MARKET_CHART, on).apply();
            widgets.reloadMarket();
        }));
        TextView hint = (TextView) menuHeading(getString(R.string.market_pick_hint, MarketTicker.MAX_PICKED));
        hint.setAllCaps(false);
        items.add(hint);
        // Built-in crypto, then the pairs found with Search, then indices and gold: searched pairs are crypto too.
        List<MarketTicker.Asset> all = new ArrayList<>();
        for (MarketTicker.Asset a : MarketTicker.ALL) if (a.source == MarketTicker.Source.BINANCE) all.add(a);
        all.addAll(MarketTicker.custom(this));
        for (MarketTicker.Asset a : MarketTicker.ALL) if (a.source != MarketTicker.Source.BINANCE) all.add(a);
        View focus = null;
        boolean cryptoDone = false;
        items.add(menuHeading(getString(R.string.market_crypto)));
        for (MarketTicker.Asset asset : all) {
            if (asset.source == MarketTicker.Source.YAHOO && !cryptoDone) {
                // The search row ends the crypto list; indices and gold follow under their own title.
                items.add(marketSearchRow(picked));
                items.add(menuHeading(getString(R.string.market_indices)));
                cryptoDone = true;
            }
            View row = toggleRow(asset.label, picked.contains(asset.id), null);
            GlassToggle toggle = (GlassToggle) ((LinearLayout) row).getChildAt(((LinearLayout) row).getChildCount() - 1);
            row.setOnClickListener(v -> {
                boolean on = !picked.contains(asset.id);
                if (on && picked.size() >= MarketTicker.MAX_PICKED) {
                    Toast.makeText(this, getString(R.string.market_max, MarketTicker.MAX_PICKED), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!on && picked.size() == 1) return;   // the card always follows something
                if (on) picked.add(asset.id);
                else picked.remove(asset.id);
                toggle.setChecked(on, true);
                Extras.setMarketAssets(this, picked);
                widgets.reloadMarket();
            });
            if (focus == null && picked.contains(asset.id)) focus = row;
            items.add(row);
        }
        if (!cryptoDone) items.add(marketSearchRow(picked));
        showListMenu(R.string.market_widget, items, focus, () -> widgets.focusMarket(), widgets.marketCard(), Gravity.START);
    }

    /**
     * "Search pair…": the on-screen keyboard takes a coin ("pepe"), Binance confirms it trades against USDT, and the
     * pair joins the list and the card.
     */
    private View marketSearchRow(List<String> picked) {
        View row = menuRow(getString(R.string.market_search), "");
        row.setOnClickListener(v -> {
            android.widget.EditText input = new android.widget.EditText(this);
            input.setSingleLine(true);
            input.setHint(R.string.market_search_hint);
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            android.app.AlertDialog.Builder dialog = new android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.market_search)
                    .setView(input)
                    .setPositiveButton(R.string.market_add, (d, w) -> {
                        String typed = input.getText().toString();
                        new Thread(() -> {
                            String symbol = MarketTicker.validateBinance(typed);
                            runOnUiThread(() -> {
                                if (symbol == null) {
                                    Toast.makeText(this, getString(R.string.market_not_found, typed.trim()),
                                            Toast.LENGTH_LONG).show();
                                    return;
                                }
                                MarketTicker.addCustom(this, symbol);
                                MarketTicker.Asset asset = MarketTicker.find(this, symbol);
                                String id = asset != null ? asset.id : symbol;
                                if (!picked.contains(id)) {
                                    if (picked.size() >= MarketTicker.MAX_PICKED) picked.remove(0);
                                    picked.add(id);
                                }
                                Extras.setMarketAssets(this, picked);
                                widgets.reloadMarket();
                                if (openPopup != null) openPopup.dismiss();
                                Toast.makeText(this, getString(R.string.market_added,
                                        asset != null ? asset.label : symbol), Toast.LENGTH_SHORT).show();
                            });
                        }, "market-search").start();
                    })
                    .setNegativeButton(android.R.string.cancel, null);
            // When the dialog closes Android hands focus back to whatever tile it finds, which can scroll the dock
            // off its place: put the dock back and return focus to the market card.
            dialog.setOnDismissListener(d -> handler.postDelayed(() -> {
                if (isFinishing()) return;
                focusTile(lastFocused % COLUMNS);
                widgets.focusMarket();
            }, 250));
            dialog.show();
        });
        return row;
    }

    /** A small section title inside a menu list; not focusable. */
    private View menuHeading(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setAllCaps(true);
        t.setTextSize(10f);
        t.setLetterSpacing(0.16f);
        t.setTextColor(0x80FFFFFF);
        t.setTypeface(getResources().getFont(R.font.outfit_medium));
        int h = (int) (12 * density);
        t.setPadding(h, (int) (10 * density), h, (int) (4 * density));
        t.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    /** How many apps the dock (and each grid row) holds: 4 to 7, as in Launcher settings. */
    private void showDockSizeMenu() {
        List<View> items = new ArrayList<>();
        View focus = null;
        for (int n = 4; n <= 7; n++) {
            final int value = n;
            View row = menuRow(getString(R.string.dock_size_value, n), n == COLUMNS ? "✓" : "");
            if (n == COLUMNS) focus = row;
            row.setOnClickListener(v -> {
                if (openPopup != null) openPopup.dismiss();
                if (value == COLUMNS) return;
                getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit().putInt("columns", value).apply();
                Extras.bumpRevision(this);
                recreate();
            });
            items.add(row);
        }
        showListMenu(R.string.dock_size, items, focus, null);
    }

    // ---- calendar ---------------------------------------------------------------------------------

    /** Events that start within the chosen number of days (or are on now). */
    private int comingCount() {
        long until = System.currentTimeMillis() + Extras.calendarDays(this) * 24L * 60 * 60 * 1000;
        int n = 0;
        for (CalendarEvents.Event e : upcoming) if (e.begin < until) n++;
        return n;
    }

    /**
     * The pill: the icon alone in its circle when nothing is coming, the icon and the count ("3", "9+") as a
     * pill when something is.
     */
    private void updateCalendarCount() {
        int n = comingCount();
        ViewGroup.LayoutParams lp = btnCalendar.getLayoutParams();
        if (n == 0) {
            btnCalendar.setText("");
            lp.width = (int) (42 * density);
            btnCalendar.setPaddingRelative((int) (10 * density), 0, (int) (10 * density), 0);
            btnCalendar.setCompoundDrawablePadding(0);
        } else {
            btnCalendar.setText(n > 9 ? "9+" : String.valueOf(n));
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            btnCalendar.setPaddingRelative((int) (12 * density), 0, (int) (16 * density), 0);
            btnCalendar.setCompoundDrawablePadding((int) (7 * density));
        }
        btnCalendar.setLayoutParams(lp);
        btnCalendar.setContentDescription(getString(R.string.calendar_heading) + (n > 0 ? ": " + n : ""));
    }

    /**
     * The calendar pill's menu. On top, two day counts: how far ahead the list below reaches, and how near an event
     * must be to count on the pill. Below, the events in that reach, each with its calendar's colour and when it is;
     * changing the reach redraws the list in place.
     */
    private void showCalendarMenu() {
        SharedPreferences prefs = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        List<View> items = new ArrayList<>();
        List<View> eventRows = new ArrayList<>();
        View reach = dayStepper(R.string.calendar_list_days, Extras.CALENDAR_LIST_DAYS, Extras::calendarListDays, () -> {
            LinearLayout list = (LinearLayout) eventRows.get(0).getParent();
            int at = list.indexOfChild(eventRows.get(0));
            for (View old : eventRows) list.removeView(old);
            eventRows.clear();
            eventRows.addAll(calendarRows());
            for (int i = 0; i < eventRows.size(); i++) list.addView(eventRows.get(i), at + i);
        });
        items.add(reach);
        // The pill's count follows when the menu closes: resizing the pill now would move the menu hanging from it.
        items.add(dayStepper(R.string.calendar_count_days, Extras.CALENDAR_DAYS, Extras::calendarDays, null));
        eventRows.addAll(calendarRows());
        items.addAll(eventRows);
        PopupWindow popup = showListMenu(R.string.calendar_heading, items, null, this::updateCalendarCount, btnCalendar,
                getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.START : Gravity.END);
        // The event rows are wider than a settings menu: titles and times side by side.
        popup.update((int) (440 * density), popup.getHeight());
    }

    /** The events that start within the list's reach (or are on now); "No upcoming events" when there are none. */
    private List<View> calendarRows() {
        List<View> rows = new ArrayList<>();
        long until = System.currentTimeMillis() + Extras.calendarListDays(this) * 24L * 60 * 60 * 1000;
        for (CalendarEvents.Event e : upcoming) {
            if (e.begin >= until) continue;
            View row = menuRow(e.title, CalendarEvents.when(this, e));
            ImageView dot = row.findViewById(R.id.icon);
            dot.setVisibility(View.VISIBLE);
            dot.setImageTintList(null);
            GradientDrawable round = new GradientDrawable();
            round.setShape(GradientDrawable.OVAL);
            round.setColor(e.color | 0xFF000000);
            int d = (int) (10 * density);
            round.setSize(d, d);
            dot.setImageDrawable(round);
            dot.setScaleType(ImageView.ScaleType.CENTER);
            ((TextView) row.findViewById(R.id.status)).setTextSize(12f);
            rows.add(row);
        }
        if (rows.isEmpty()) rows.add(menuRow(getString(R.string.calendar_none), ""));
        return rows;
    }

    /** A "Title  ‹ 3 days ›" row: Left and Right change it by one day, 1 to 30; OK steps forward. */
    private View dayStepper(int title, String key, java.util.function.ToIntFunction<Context> current, Runnable after) {
        SharedPreferences prefs = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        View row = menuRow(getString(title), "");
        TextView value = row.findViewById(R.id.status);
        value.setTextSize(15f);
        value.setTextColor(getColorStateList(R.color.pill_text));
        Runnable show = () -> {
            int days = current.applyAsInt(this);
            value.setText("\u2039  " + getResources().getQuantityString(R.plurals.calendar_days, days, days) + "  \u203A");
        };
        show.run();
        java.util.function.IntConsumer step = by -> {
            int next = Math.max(1, Math.min(Extras.CALENDAR_MAX_DAYS, current.applyAsInt(this) + by));
            prefs.edit().putInt(key, next).apply();
            show.run();
            if (after != null) after.run();
        };
        row.setOnClickListener(v -> step.accept(1));
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) step.accept(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            return true;
        });
        return row;
    }

    // ---- night mode -------------------------------------------------------------------------------

    /** The moon pill's panel: night mode on or off, and the dim and warm bars, applied as they move. */
    private void showNightPanel() {
        View panel = getLayoutInflater().inflate(R.layout.popup_night, root, false);
        PopupWindow popup = glassPopup(panel, (int) (380 * density));
        GlassSlider dim = panel.findViewById(R.id.nightDim);
        GlassSlider warm = panel.findViewById(R.id.nightWarm);
        dim.setIcon(R.drawable.ic_brightness);
        warm.setIcon(R.drawable.ic_warm);
        dim.setMax(NightLight.MAX_DIM);
        dim.setValue(NightLight.dim(this));
        warm.setValue(NightLight.warm(this));
        boolean[] on = {NightLight.on(this)};
        // The on/off row: the same glass switch as the widget menus, with the moon in front.
        View toggle = toggleRow(getString(R.string.night_mode), on[0], value -> {
            on[0] = value;
            NightLight.set(this, value, dim.getValue(), warm.getValue());
        });
        ImageView moon = toggle.findViewById(R.id.icon);
        moon.setVisibility(View.VISIBLE);
        moon.setImageResource(R.drawable.ic_moon);
        GlassToggle onSwitch = (GlassToggle) ((LinearLayout) toggle).getChildAt(((LinearLayout) toggle).getChildCount() - 1);
        LinearLayout panelRows = (LinearLayout) panel;
        View oldToggle = panel.findViewById(R.id.nightToggle);
        panelRows.addView(toggle, panelRows.indexOfChild(oldToggle));
        panelRows.removeView(oldToggle);
        if (!NightLight.canDraw(this)) {
            TextView hint = panel.findViewById(R.id.nightHint);
            hint.setText(R.string.night_needs_permission);
            hint.setVisibility(View.VISIBLE);
        }
        GlassSlider.OnChange moved = value -> {
            // Moving a bar means "use it": the switch turns on with it.
            if (!on[0]) {
                on[0] = true;
                onSwitch.setChecked(true, true);
            }
            NightLight.set(this, true, dim.getValue(), warm.getValue());
        };
        dim.setOnChange(moved);
        warm.setOnChange(moved);
        // The TV's real backlight, apart from night mode: only on a TV that runs Android (see RealBrightness).
        GlassSlider real = panel.findViewById(R.id.realBrightness);
        real.setIcon(R.drawable.ic_brightness);
        TextView realHint = panel.findViewById(R.id.realHint);
        if (RealBrightness.supported(this)) {
            real.setValue(RealBrightness.get(this));
            real.setOnChange(value -> RealBrightness.set(this, value));
        } else {
            real.setValue(0);
            real.setFocusable(false);
            real.setAlpha(0.4f);
            realHint.setText(RealBrightness.hasPanel(this) ? R.string.night_real_needs_grant : R.string.night_real_hdmi);
            realHint.setVisibility(View.VISIBLE);
        }
        dropIn(popup, panel, btnNight, getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.START : Gravity.END);
        toggle.requestFocus();
    }

    private void addSettingsMenuRow(LinearLayout rows, PopupWindow popup, int icon, int title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        // The OK-up after opening the menu must not activate its first row.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        rows.addView(row);
    }

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()
                || reordering) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the tile reload stays outside
            restoreFocus();   // the tiles changed under the prompt; this also chains the next prompt
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnKeep;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnShow;
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        keep.requestFocus();
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reloadTiles();
    }

    /** Dropdown under the Settings pill (hold OK): every discovered card, its package and key; OK toggles Shown and Hidden. */
    private void showModuleCards() {
        if (cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(this, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
    }

    private void showModuleCardsNow() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        List<Cards.Card> listed = new ArrayList<>(cards);
        Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                // The list stays open; the decision is for the card as it is now, not as it was listed.
                Cards.Card now = cardFor(pkg);
                if (now == null) return null;
                decideCard(now, now.state != Cards.State.SHOWN);
                // Every row, not only this one: a toggle can move another card across the limit of 8.
                updateCardStates(rows);
                return null;
            }, null));
            rows.addView(row);
        }
        updateCardStates(rows);
        // Scroll inside the panel instead of running off the bottom of a 720p screen.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * density);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density))));
        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list from the current card list. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor((String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }
}
