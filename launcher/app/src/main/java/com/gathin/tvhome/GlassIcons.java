package com.gathin.tvhome;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;

/**
 * The "Glass" icon pack: each app's logo alone, without its own background, on a light frosted glass tile, and the
 * glass rim (bright where the light hits the top left and the bottom right) that every tile gets in every pack.
 *
 * The logo is taken from what the app already has on the TV, never bundled: its banner with the background colour
 * keyed out when that background is plain (TV wordmarks), else the foreground layer of its adaptive icon, else its
 * icon. Grey and black parts of a logo turn white, so dark wordmarks read on glass. Each logo is made once per app.
 */
final class GlassIcons {
    /**
     * The logos made so far, as bitmaps: each tile gets its own Drawable round them. A cached Drawable would keep a
     * reference to the last view it was drawn in (its callback), and through it the whole Home of before a rebuild.
     */
    private static final Map<String, Bitmap> logos = new HashMap<>();
    /** The TV's Settings app: its tile shows the tvOS gear of the top bar's Settings pill, in every pack. */
    static final String SETTINGS_PKG = "com.android.tv.settings";

    private GlassIcons() {
    }

    static void clear() {
        logos.clear();
    }

    /** The logo for this app, made on first use; null when it has neither a banner nor an icon. */
    static Drawable logo(Resources res, String key, Drawable banner, Drawable icon) {
        if (key.startsWith(SETTINGS_PKG + "/")) return res.getDrawable(R.drawable.ic_settings, null);
        if (logos.containsKey(key)) {
            Bitmap cached = logos.get(key);
            return cached == null ? null : new BitmapDrawable(res, cached);
        }
        Bitmap made = null;
        try {
            if (banner != null) made = keyedBanner(banner);
            if (made == null && icon instanceof AdaptiveIconDrawable) {
                made = foreground((AdaptiveIconDrawable) icon);
            }
            if (made == null && icon != null) made = render(icon, 160, 160);
            if (made == null && banner != null) made = render(banner, 320, 180);
        } catch (RuntimeException | OutOfMemoryError ignored) {
            made = null;
        }
        logos.put(key, made);
        return made == null ? null : new BitmapDrawable(res, made);
    }

    private static Bitmap render(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Rect old = new Rect(d.getBounds());
        d.setBounds(0, 0, w, h);
        d.draw(c);
        d.setBounds(old);
        return b;
    }

    /**
     * The banner with its background made transparent, when the background is one plain colour: measured along the
     * edges, where a TV banner has no logo. A photo or a strong gradient gives back null.
     */
    private static Bitmap keyedBanner(Drawable banner) {
        int w = 320, h = 180;
        Bitmap b = render(banner, w, h);
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        long r = 0, g = 0, bl = 0;
        int n = 0;
        int[] edge = new int[2 * (w + h)];
        for (int x = 0; x < w; x++) {
            edge[n++] = px[x];
            edge[n++] = px[(h - 1) * w + x];
        }
        for (int y = 0; y < h; y++) {
            edge[n++] = px[y * w];
            edge[n++] = px[y * w + w - 1];
        }
        for (int i = 0; i < n; i++) {
            r += Color.red(edge[i]);
            g += Color.green(edge[i]);
            bl += Color.blue(edge[i]);
        }
        int br = (int) (r / n), bg = (int) (g / n), bb = (int) (bl / n);
        int far = 0;
        for (int i = 0; i < n; i++) if (distance(edge[i], br, bg, bb) > 40) far++;
        if (far > n * 0.08f) return null;   // not a plain background

        int[] orig = px.clone();
        int visible = 0;
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            float d = distance(c, br, bg, bb);
            // White drawn inside a coloured shape (SmartTube's S on red, Stremio's play mark) is cut out too, as
            // YouTube's triangle is: in white on glass it would otherwise vanish into the shape around it.
            if (d > 30f && isWhite(c) && enclosedByColour(orig, w, h, i % w, i / w, br, bg, bb)) {
                px[i] = 0;
                continue;
            }
            // Soft edge: within 30 of the background it is gone, beyond 70 it is all logo.
            float a = Math.max(0f, Math.min(1f, (d - 30f) / 40f)) * (Color.alpha(c) / 255f);
            if (a <= 0f) {
                px[i] = 0;
                continue;
            }
            visible++;
            px[i] = whiten(c, a);
        }
        if (visible < px.length * 0.02f) return null;   // nothing left of a logo
        // Grow each cut-out over the light, anti-aliased pixels at its edge, twice, so a cut letter is clean rather
        // than dotted.
        for (int pass = 0; pass < 2; pass++) {
            boolean[] grow = new boolean[px.length];
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    int i = y * w + x;
                    if (px[i] == 0 || Math.min(Color.red(orig[i]), Math.min(Color.green(orig[i]), Color.blue(orig[i]))) < 140) continue;
                    if (distance(orig[i], br, bg, bb) < 70f) continue;
                    boolean nextToCut = (px[i - 1] == 0 && isWhite(orig[i - 1])) || (px[i + 1] == 0 && isWhite(orig[i + 1]))
                            || (px[i - w] == 0 && isWhite(orig[i - w])) || (px[i + w] == 0 && isWhite(orig[i + w]));
                    grow[i] = nextToCut;
                }
            }
            for (int i = 0; i < px.length; i++) if (grow[i]) px[i] = 0;
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
        return trim(b);
    }

    /** The adaptive icon's foreground layer: the logo without the icon's background, cropped to its safe area. */
    private static Bitmap foreground(AdaptiveIconDrawable icon) {
        Drawable fg = icon.getForeground();
        if (fg == null) return null;
        int size = 216;   // 108dp canvas at 2x; the logo lives in the middle 72dp
        Bitmap full = render(fg, size, size);
        int inset = size / 6;
        Bitmap safe = Bitmap.createBitmap(full, inset, inset, size - 2 * inset, size - 2 * inset);
        int[] px = new int[safe.getWidth() * safe.getHeight()];
        safe = safe.copy(Bitmap.Config.ARGB_8888, true);
        safe.getPixels(px, 0, safe.getWidth(), 0, 0, safe.getWidth(), safe.getHeight());
        int visible = 0;
        for (int i = 0; i < px.length; i++) {
            if (Color.alpha(px[i]) == 0) continue;
            visible++;
            px[i] = whiten(px[i], Color.alpha(px[i]) / 255f);
        }
        if (visible < px.length * 0.02f) return null;
        safe.setPixels(px, 0, safe.getWidth(), 0, 0, safe.getWidth(), safe.getHeight());
        return trim(safe);
    }

    /**
     * Every part of the logo becomes white, keeping only its shape and edges: the monochrome look of tvOS glass
     * icons. Holes keyed out of the logo (YouTube's play triangle, Spotify's bars) stay holes and show the glass.
     */
    private static int whiten(int c, float alpha) {
        return Color.argb(Math.round(alpha * 245), 255, 255, 255);
    }

    private static boolean isWhite(int c) {
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        return min > 200 && max - min < 40;
    }

    /** Saturated, logo (not background) colour a few pixels away in all four directions. */
    private static boolean enclosedByColour(int[] px, int w, int h, int x, int y, int br, int bg, int bb) {
        int[][] steps = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int[] st : steps) {
            boolean found = false;
            for (int k = 1; k <= 40 && !found; k++) {
                int nx = x + st[0] * k, ny = y + st[1] * k;
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) return false;
                int c = px[ny * w + nx];
                if (isWhite(c)) continue;
                if (distance(c, br, bg, bb) < 50f) return false;   // reached the background: not enclosed
                int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
                int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
                found = max > 60 && (max - min) / (float) max > 0.35f;
                if (!found) return false;
            }
            if (!found) return false;
        }
        return true;
    }

    private static float distance(int c, int r, int g, int b) {
        int dr = Color.red(c) - r, dg = Color.green(c) - g, db = Color.blue(c) - b;
        return (float) Math.sqrt(dr * dr + dg * dg + db * db);
    }

    /** Crops away fully transparent margins so the logo can be centred and sized by its own shape. */
    private static Bitmap trim(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int left = w, right = -1, top = h, bottom = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (Color.alpha(px[y * w + x]) > 24) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        if (right < left || bottom < top) return null;
        return Bitmap.createBitmap(b, left, top, right - left + 1, bottom - top + 1);
    }

    /** The light frosted glass tile under a logo: a white sheen that fades down, over the wallpaper. */
    static Drawable tile(float density) {
        return new Base(density);
    }

    /** The glass rim of every tile: bright at the top left, faint along the sides, a second light at the bottom right. */
    /** {@code intensity} 0 to 100 (60 is the designed look); 0 gives no rim at all. */
    static Drawable rim(float density, int intensity) {
        return intensity <= 0 ? null : new Rim(density, intensity / 60f);
    }

    private static final class Base extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float density;

        Base(float density) {
            this.density = density;
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            fill.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom, 0x52FFFFFF, 0x24FFFFFF,
                    Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas canvas) {
            rect.set(getBounds());
            float r = AppAdapter.cornerRadius(getBounds().width(), density);
            canvas.drawRoundRect(rect, r, r, fill);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * The glass rim, scaled to the tile: its line is about 1.2% of the tile's width, so it reads the same at any column
     * count. The light runs round it by angle (a sweep): strongest at the top left and bottom right corners, nearly
     * gone at the other two, like light catching the edge of a glass slab. Inside it a blurred band (the thickness of
     * the glass), outside it a hairline of shade so the rim still shows over a bright wallpaper.
     */
    private static final class Rim extends Drawable {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bevel = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float density;
        /** Strength: 1 is the designed look, up to about 1.7 at 100. */
        private final float k;

        Rim(float density, float strength) {
            this.density = density;
            this.k = strength;
            line.setStyle(Paint.Style.STROKE);
            bevel.setStyle(Paint.Style.STROKE);
            shade.setStyle(Paint.Style.STROKE);
            shade.setColor(0x38000000);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            float w = b.width(), h = b.height();
            // Thicker as well as brighter as the strength goes up.
            float t = Math.max(1.2f, w * 0.012f * (0.6f + 0.4f * k));
            line.setStrokeWidth(t);
            bevel.setStrokeWidth(t * 2.6f);
            bevel.setMaskFilter(new android.graphics.BlurMaskFilter(t * 1.4f, android.graphics.BlurMaskFilter.Blur.NORMAL));
            shade.setStrokeWidth(Math.max(1f, t * 0.45f));
            // Corner angles of this rectangle (a wide tile's corners are not at 45 degrees), clockwise from 3 o'clock.
            float a = (float) Math.toDegrees(Math.atan2(h, w)) / 360f;
            float[] at = {0f, a, 0.5f - a, 0.5f + a, 1f - a, 1f};
            int[] lit = {0x99FFFFFF, 0xF2FFFFFF, 0x1FFFFFFF, 0xFFFFFFFF, 0x2EFFFFFF, 0x99FFFFFF};
            int[] soft = {0x26FFFFFF, 0x59FFFFFF, 0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF, 0x26FFFFFF};
            line.setShader(new android.graphics.SweepGradient(b.exactCenterX(), b.exactCenterY(), scale(lit), at));
            bevel.setShader(new android.graphics.SweepGradient(b.exactCenterX(), b.exactCenterY(), scale(soft), at));
            shade.setColor(Math.min(255, Math.round(0x38 * Math.min(1.4f, k))) << 24);
        }

        /** The colours' alpha times the strength, capped at opaque. */
        private int[] scale(int[] colours) {
            int[] out = new int[colours.length];
            for (int i = 0; i < colours.length; i++) {
                int a = Math.min(255, Math.round((colours[i] >>> 24) * k));
                out[i] = a << 24 | (colours[i] & 0xFFFFFF);
            }
            return out;
        }

        @Override
        public void draw(Canvas canvas) {
            float r = AppAdapter.cornerRadius(getBounds().width(), density);
            float s = shade.getStrokeWidth() / 2f;
            rect.set(getBounds());
            rect.inset(s, s);
            canvas.drawRoundRect(rect, r - s, r - s, shade);
            float t = line.getStrokeWidth();
            rect.set(getBounds());
            rect.inset(s * 2 + t / 2f, s * 2 + t / 2f);
            float lr = Math.max(0f, r - s * 2 - t / 2f);
            canvas.drawRoundRect(rect, lr, lr, line);
            float bi = bevel.getStrokeWidth() / 2f;
            rect.inset(bi, bi);
            float br = Math.max(0f, lr - bi);
            canvas.drawRoundRect(rect, br, br, bevel);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
