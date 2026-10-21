package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Rotating wallpapers from Wikimedia Commons' Featured pictures, in the categories the owner switches on. No account
 * or key: Commons' public API lists a category, one landscape photo is picked at random, and Commons' own servers
 * scale it to 1920 px wide (a few hundred KB). One file is kept in the launcher's private storage with its author
 * and licence, shown as the credit the licence asks for. Fetched only while Home is on screen and the rotation is
 * due, so nothing runs in the background.
 */
final class OnlineWallpapers {
    /** The wallpaper choice (Config.WALLPAPER_CHOICE_KEY) that turns online wallpapers on. */
    static final String CHOICE = "online";
    static final String CATEGORIES = "online_categories";
    static final String INTERVAL = "online_interval_minutes";
    private static final String LAST_FETCH = "online_last_fetch";
    private static final String CREDIT = "online_credit";
    private static final String RECENT = "online_recent";
    /** Minutes between pictures; 0 never rotates by itself, only with Change wallpaper now. */
    static final int[] INTERVALS = {0, 15, 30, 60, 180, 1440};

    private static final String API = "https://commons.wikimedia.org/w/api.php";
    /** Wikimedia asks every API client to say who it is. */
    private static final String USER_AGENT = "CleanYourTV-launcher/2.5 (https://github.com/Gathin23/cleanyourtv)";
    private static final int WIDTH = 1920;

    /**
     * Category id (its label is the string online_cat_&lt;id&gt;) and the Commons Featured pictures categories behind
     * it, "|"-separated. Each was checked to hold dozens to hundreds of landscape photos of at least 1920 px.
     */
    static final String[][] ALL = {
            {"nature", "Featured pictures of landscapes"},
            {"mountains", "Featured pictures of mountains|Featured pictures of volcanoes"},
            {"ocean", "Featured pictures of coasts|Featured pictures of beaches|Featured pictures of islands"},
            {"space", "Featured pictures of astronomy|Featured pictures of galaxies|Featured pictures of the Milky Way"},
            {"sunsets", "Featured pictures of sunsets|Featured pictures of clouds"},
            {"cities", "Featured pictures of cityscapes|Featured pictures of skylines"},
            {"architecture", "Featured pictures of architecture"},
            {"forest", "Featured pictures of forests|Featured pictures of waterfalls"},
            {"lakes", "Featured pictures of lakes"},
            {"flowers", "Featured pictures of flowers"},
    };

    private static final String TAG = "TvHome.Wallpaper";
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static volatile boolean fetching;

    private OnlineWallpapers() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    static boolean active(Context ctx) {
        return CHOICE.equals(prefs(ctx).getString(Config.WALLPAPER_CHOICE_KEY, null));
    }

    /** The picture on disk, or null before the first download. */
    static File current(Context ctx) {
        File f = new File(ctx.getFilesDir(), "online-wallpaper.jpg");
        return f.isFile() && f.length() > 0 ? f : null;
    }

    /** "Author · licence" of the picture on screen, or null. */
    static String credit(Context ctx) {
        return prefs(ctx).getString(CREDIT, null);
    }

    /** The switched-on categories; landscapes, mountains and space until the owner picks. */
    static List<String> categories(Context ctx) {
        String saved = prefs(ctx).getString(CATEGORIES, "nature,mountains,space");
        List<String> list = new ArrayList<>();
        for (String id : saved.split(",")) {
            id = id.trim();
            if (!id.isEmpty() && known(id)) list.add(id);
        }
        return list;
    }

    private static boolean known(String id) {
        for (String[] c : ALL) if (c[0].equals(id)) return true;
        return false;
    }

    static void setCategory(Context ctx, String id, boolean on) {
        List<String> list = categories(ctx);
        list.remove(id);
        if (on) list.add(id);
        prefs(ctx).edit().putString(CATEGORIES, String.join(",", list)).apply();
    }

    static int intervalMinutes(Context ctx) {
        return prefs(ctx).getInt(INTERVAL, 60);
    }

    /** Makes the next check fetch a new picture, as after a change of categories. */
    static void expire(Context ctx) {
        prefs(ctx).edit().remove(LAST_FETCH).apply();
    }

    static boolean due(Context ctx) {
        long last = prefs(ctx).getLong(LAST_FETCH, 0);
        if (current(ctx) == null) return true;
        int minutes = intervalMinutes(ctx);
        return minutes > 0 && System.currentTimeMillis() - last >= minutes * 60_000L;
    }

    /**
     * Online photos are taken for themselves, not for white text over them: a snowfield or a bright sky would wash the
     * clock out. Darkens only the bands where Home puts things, each by how bright that band is (12% on a night sky,
     * up to 65% on snow): the top, with the clock, the pills, the widgets and the credit, and the bottom, with the
     * focused app's name, the dock and the grid. The middle of the picture keeps its own light, and a soft ramp
     * joins the bands to it. The glass blurs the darkened picture, so pills and widgets stay legible too.
     */
    static Bitmap tame(Bitmap picture) {
        if (picture == null) return null;
        Bitmap probe = Bitmap.createScaledBitmap(picture, 32, 18, true);
        int w = probe.getWidth(), h = probe.getHeight();
        double top = 0, bottom = 0;
        int topRows = Math.round(h * TOP_BAND), bottomFrom = Math.round(h * BOTTOM_BAND);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = probe.getPixel(x, y);
                double l = 0.2126 * ((c >> 16) & 0xFF) + 0.7152 * ((c >> 8) & 0xFF) + 0.0722 * (c & 0xFF);
                if (y < topRows) top += l;
                if (y >= bottomFrom) bottom += l;
            }
        }
        if (probe != picture) probe.recycle();
        int topAlpha = dimFor(top / (w * topRows * 255.0));
        int bottomAlpha = dimFor(bottom / (w * (h - bottomFrom) * 255.0));
        Bitmap out = picture.isMutable() ? picture : picture.copy(picture.getConfig(), true);
        if (out == null) return picture;
        if (out != picture) picture.recycle();
        float H = out.getHeight();
        Paint shade = new Paint(Paint.DITHER_FLAG);
        shade.setShader(new LinearGradient(0, 0, 0, H,
                new int[]{topAlpha << 24, topAlpha << 24, 0, 0, bottomAlpha << 24, bottomAlpha << 24},
                new float[]{0f, TOP_BAND * 0.6f, TOP_BAND, MIDDLE_END, BOTTOM_BAND + 0.08f, 1f},
                Shader.TileMode.CLAMP));
        new Canvas(out).drawRect(0, 0, out.getWidth(), H, shade);
        return out;
    }

    /** Fractions of the screen height: the top band ends at 40%, the bottom band starts fading in from 46%. */
    private static final float TOP_BAND = 0.40f, MIDDLE_END = 0.46f, BOTTOM_BAND = 0.54f;

    /** Darkening as alpha for a band of this brightness (0 black to 1 white). */
    private static int dimFor(double luma) {
        double dim = Math.max(0.12, Math.min(0.65, 0.25 + (luma - 0.35) * 1.0));
        return (int) Math.round(dim * 255);
    }

    interface Done {
        /** On the main thread: the new picture, or null when nothing could be fetched. */
        void onDone(File picture);
    }

    /** Downloads the next picture in the background, unless one is already on its way. */
    static void fetch(Context context, Done done) {
        Context ctx = context.getApplicationContext();
        List<String> cats = categories(ctx);
        if (cats.isEmpty() || fetching) {
            if (done != null) done.onDone(null);
            return;
        }
        fetching = true;
        Handler main = new Handler(Looper.getMainLooper());
        worker.execute(() -> {
            File result = null;
            try {
                result = download(ctx, cats.get(new Random().nextInt(cats.size())));
            } catch (Exception failed) {
                // offline or Commons busy: keep the picture we have and try at the next rotation
                android.util.Log.w(TAG, "Rotation skipped: " + failed);
            } finally {
                fetching = false;
            }
            File picture = result;
            if (done != null) main.post(() -> done.onDone(picture));
        });
    }

    private static File download(Context ctx, String id) throws Exception {
        Random random = new Random();
        String[] commonsCats = {"Featured pictures of landscapes"};
        for (String[] c : ALL) if (c[0].equals(id)) commonsCats = c[1].split("\\|");
        String category = commonsCats[random.nextInt(commonsCats.length)];

        // 1. The category's files with their sizes only (light), keeping wide pictures at least screen-wide.
        JSONObject pages = json(API + "?action=query&format=json&generator=categorymembers&gcmtype=file&gcmlimit=200"
                + "&prop=imageinfo&iiprop=size&gcmtitle=" + enc("Category:" + category)).optJSONObject("query");
        if (pages == null) throw new IllegalStateException("empty category");
        pages = pages.getJSONObject("pages");
        List<String> recent = new ArrayList<>(Arrays.asList(prefs(ctx).getString(RECENT, "").split("\n")));
        List<String> wide = new ArrayList<>();
        for (Iterator<String> it = pages.keys(); it.hasNext(); ) {
            JSONObject page = pages.getJSONObject(it.next());
            JSONArray info = page.optJSONArray("imageinfo");
            if (info == null) continue;
            int w = info.getJSONObject(0).optInt("width"), h = info.getJSONObject(0).optInt("height");
            String title = page.optString("title");
            if (w >= WIDTH && h > 0 && w >= h * 1.3f && !recent.contains(title)) wide.add(title);
        }
        if (wide.isEmpty()) throw new IllegalStateException("no landscape pictures");
        String title = wide.get(random.nextInt(wide.size()));

        // 2. That one file: Commons' 1920 px rendition and its author and licence.
        JSONObject file = json(API + "?action=query&format=json&prop=imageinfo&iiprop=url|extmetadata"
                + "&iiextmetadatafilter=Artist|LicenseShortName&iiurlwidth=" + WIDTH + "&titles=" + enc(title))
                .getJSONObject("query").getJSONObject("pages");
        JSONObject info = file.getJSONObject(file.keys().next()).getJSONArray("imageinfo").getJSONObject(0);
        String thumb = info.optString("thumburl", info.optString("url"));
        JSONObject meta = info.optJSONObject("extmetadata");
        String artist = meta == null ? "" : plain(meta.optJSONObject("Artist"));
        String licence = meta == null ? "" : plain(meta.optJSONObject("LicenseShortName"));

        byte[] image = get(thumb, 8 * 1024 * 1024);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(image, 0, image.length, bounds);
        if (bounds.outWidth <= 0) throw new IllegalStateException("not an image");
        File tmp = new File(ctx.getFilesDir(), "online-wallpaper.tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(image);
        }
        File target = new File(ctx.getFilesDir(), "online-wallpaper.jpg");
        if (!tmp.renameTo(target)) throw new IllegalStateException("could not keep the picture");

        // Remember the last 30 so a rotation does not bring the same picture back soon.
        recent.add(0, title);
        while (recent.size() > 30) recent.remove(recent.size() - 1);
        String credit = artist.isEmpty() ? licence : licence.isEmpty() ? artist : artist + " · " + licence;
        prefs(ctx).edit().putLong(LAST_FETCH, System.currentTimeMillis())
                .putString(CREDIT, credit.isEmpty() ? "Wikimedia Commons" : credit)
                .putString(RECENT, String.join("\n", recent)).apply();
        android.util.Log.i(TAG, "New wallpaper from " + category + " (every " + intervalMinutes(ctx) + " min)");
        return target;
    }

    /** extmetadata values are HTML ("<a href=…>Name</a>"): the text, trimmed to one short line. */
    private static String plain(JSONObject field) {
        if (field == null) return "";
        String text = Html.fromHtml(field.optString("value", ""), Html.FROM_HTML_MODE_LEGACY).toString()
                .replaceAll("\\s+", " ").trim();
        return text.length() > 60 ? text.substring(0, 57) + "…" : text;
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static JSONObject json(String url) throws Exception {
        return new JSONObject(new String(get(url, 1024 * 1024), StandardCharsets.UTF_8));
    }

    private static byte[] get(String address, int limit) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", USER_AGENT);
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (out.size() + n > limit) throw new IllegalStateException("response too large");
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
