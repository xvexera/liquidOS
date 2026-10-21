package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Random;

/**
 * Wallpapers drawn by the launcher itself, so there is nothing to download or license: soft colour fields in the
 * spirit of the tvOS and macOS gradients, and space scenes (Earth from orbit, a nebula, the Milky Way, the Moon).
 * Drawn once at half the screen size into the cache folder and read back from there; the wallpaper view
 * stretches them, which only softens gradients and haze further.
 */
final class BuiltinWallpapers {
    static final String PREFIX = "builtin:";
    /** Bump when a picture's drawing changes, so cached copies are drawn again. */
    private static final int VERSION = 3;

    private static final String[] SPACE = {"tierra", "nebulosa", "via_lactea", "luna"};

    /** Name, base top colour, base bottom colour, then blobs as {colour, x, y, radius} in fractions of the width. */
    private static final Object[][] GRADIENTS = {
            {"aurora", 0xFF050B1A, 0xFF02040A, new float[][]{
                    {0xFF1FB89A, 0.18f, 0.30f, 0.55f}, {0xFF6A3DE8, 0.78f, 0.22f, 0.50f},
                    {0xFF0E4FD6, 0.55f, 0.95f, 0.60f}}},
            {"oceano", 0xFF03111F, 0xFF01060D, new float[][]{
                    {0xFF0A84FF, 0.25f, 0.85f, 0.65f}, {0xFF35D0E8, 0.82f, 0.30f, 0.45f},
                    {0xFF1A2F8F, 0.50f, 0.10f, 0.50f}}},
            {"atardecer", 0xFF1A0612, 0xFF080208, new float[][]{
                    {0xFFFF7A2E, 0.20f, 0.90f, 0.55f}, {0xFFE0337A, 0.70f, 0.70f, 0.55f},
                    {0xFF5B1FA8, 0.85f, 0.10f, 0.45f}}},
            {"bosque", 0xFF04130C, 0xFF010604, new float[][]{
                    {0xFF2FAF5C, 0.15f, 0.75f, 0.55f}, {0xFF0F6E5A, 0.75f, 0.30f, 0.60f},
                    {0xFFB8C94A, 0.60f, 1.00f, 0.35f}}},
            {"grafito", 0xFF101114, 0xFF050506, new float[][]{
                    {0xFF5A606B, 0.25f, 0.25f, 0.55f}, {0xFF2C3038, 0.80f, 0.80f, 0.60f},
                    {0xFF8A8F99, 0.70f, 0.15f, 0.30f}}},
    };

    private BuiltinWallpapers() {
    }

    /** Space scenes first: they are the ones that look most like the Apple TV. */
    static String[] names() {
        String[] names = new String[SPACE.length + GRADIENTS.length];
        System.arraycopy(SPACE, 0, names, 0, SPACE.length);
        for (int i = 0; i < GRADIENTS.length; i++) names[SPACE.length + i] = (String) GRADIENTS[i][0];
        return names;
    }

    static boolean exists(String name) {
        for (String n : names()) if (n.equals(name)) return true;
        return false;
    }

    /** The picture from the cache, drawing (and caching) it first when missing. Null for an unknown name. */
    static Bitmap load(String name, int width, int height, File cacheDir) {
        File cached = cacheDir == null ? null
                : new File(cacheDir, "wallpaper-" + name + "-v" + VERSION + "-" + width + "x" + height + ".png");
        if (cached != null && cached.isFile()) {
            Bitmap b = BitmapFactory.decodeFile(cached.getPath());
            if (b != null) return b;
        }
        Bitmap b = render(name, width, height);
        if (b != null && cached != null) {
            try (FileOutputStream out = new FileOutputStream(cached)) {
                b.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception ignored) {
                // drawn again next time
            }
        }
        return b;
    }

    static Bitmap render(String name, int width, int height) {
        if (width <= 0 || height <= 0 || !exists(name)) return null;
        try {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bitmap);
            switch (name) {
                case "tierra": earth(c, width, height); break;
                case "nebulosa": nebula(c, width, height); break;
                case "via_lactea": milkyWay(c, width, height); break;
                case "luna": moon(c, width, height); break;
                default: gradient(c, width, height, gradientStyle(name)); break;
            }
            // No darkened top left any more: the widgets there are glass like the pills, and a dark corner under
            // them made them look like a different material. Text contrast comes from the wallpaper dim setting.
            return bitmap;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private static Object[] gradientStyle(String name) {
        for (Object[] s : GRADIENTS) if (s[0].equals(name)) return s;
        return GRADIENTS[0];
    }

    private static Paint paint() {
        return new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    }

    private static void gradient(Canvas c, int w, int h, Object[] s) {
        Paint p = paint();
        p.setShader(new LinearGradient(0, 0, 0, h, (int) s[1], (int) s[2], Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        for (float[] blob : (float[][]) s[3]) glow(c, w, h, (int) blob[0], blob[1] * w, blob[2] * h, blob[3] * w, 0x8C);
    }

    /** A soft round light of {@code color}, {@code alpha} at its centre and nothing at {@code r}. */
    private static void glow(Canvas c, int w, int h, int color, float x, float y, float r, int alpha) {
        Paint p = paint();
        int rgb = color & 0x00FFFFFF;
        p.setShader(new RadialGradient(x, y, r, new int[]{rgb | alpha << 24, rgb | (alpha * 2 / 5) << 24, rgb},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
    }

    /** Stars: mostly faint pinpricks, a few brighter ones with a halo, a hint of colour in some. */
    private static void stars(Canvas c, int w, int h, Random rnd, int count, float maxY) {
        Paint p = paint();
        Paint halo = paint();
        halo.setMaskFilter(new BlurMaskFilter(3f, BlurMaskFilter.Blur.NORMAL));
        int[] tints = {0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFCFE0FF, 0xFFFFE9C9};
        for (int i = 0; i < count; i++) {
            float x = rnd.nextFloat() * w, y = rnd.nextFloat() * h * maxY;
            float m = rnd.nextFloat();
            int tint = tints[rnd.nextInt(tints.length)] & 0x00FFFFFF;
            float size = m > 0.985f ? 1.3f : m > 0.9f ? 0.8f : 0.5f;
            int alpha = (int) (60 + 195 * Math.pow(rnd.nextFloat(), 2.2));
            if (m > 0.985f) {
                halo.setColor(tint | 0x66000000);
                c.drawCircle(x, y, size * 3f, halo);
                alpha = 255;
            }
            p.setColor(tint | alpha << 24);
            c.drawCircle(x, y, size, p);
        }
    }

    /** Earth's night side from orbit: the limb low on the screen, a thin blue atmosphere and sunrise on its edge. */
    private static void earth(Canvas c, int w, int h) {
        Random rnd = new Random(7);
        c.drawColor(0xFF010205);
        stars(c, w, h, rnd, w * h / 900, 0.75f);
        float r = w * 1.35f, cx = w * 0.62f, cy = h * 0.78f + r;
        // Atmosphere: a wide faint glow, then a thin bright band hugging the limb.
        Paint p = paint();
        p.setShader(new RadialGradient(cx, cy, r * 1.06f, new int[]{0x00000000, 0x0018A0FF, 0x5530B4FF, 0x00000000},
                new float[]{0f, 0.935f, 0.948f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 1.06f, p);
        p.setShader(new RadialGradient(cx, cy, r * 1.012f, new int[]{0x00000000, 0xCC7FD4FF, 0x00000000},
                new float[]{0f, 0.985f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 1.012f, p);
        // The planet: dark ocean with a faint lit crescent along the top.
        p.setShader(new RadialGradient(cx, cy - r * 0.02f, r, new int[]{0xFF020509, 0xFF04101F, 0xFF0B3A6E},
                new float[]{0f, 0.97f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        // City lights scattered over the night side near the limb.
        Paint lights = paint();
        for (int i = 0; i < 260; i++) {
            double a = -Math.PI / 2 + (rnd.nextFloat() - 0.5f) * 0.55f;
            float depth = r * (0.94f + rnd.nextFloat() * 0.055f);
            float x = cx + (float) Math.cos(a) * depth, y = cy + (float) Math.sin(a) * depth;
            if (x < 0 || x > w || y > h) continue;
            lights.setColor(0x00FFC46B | (40 + rnd.nextInt(120)) << 24);
            c.drawCircle(x, y, 0.5f + rnd.nextFloat() * 0.6f, lights);
        }
        // Sunrise: a warm flare where the limb meets the right of the screen.
        glow(c, w, h, 0xFFFFB25C, w * 0.97f, h * 0.80f, w * 0.30f, 0x99);
        glow(c, w, h, 0xFFFFF1D6, w * 0.985f, h * 0.81f, w * 0.06f, 0xE6);
    }

    /** A nebula: overlapping soft clouds of magenta, violet and teal over a dense star field. */
    private static void nebula(Canvas c, int w, int h) {
        Random rnd = new Random(11);
        c.drawColor(0xFF03020A);
        int[] colours = {0xFF8E2DE2, 0xFFE0337A, 0xFF2B6CE0, 0xFF19B5A5, 0xFFB54AE0};
        for (int i = 0; i < 26; i++) {
            float x = w * (0.35f + rnd.nextFloat() * 0.65f), y = h * (0.1f + rnd.nextFloat() * 0.9f);
            glow(c, w, h, colours[rnd.nextInt(colours.length)], x, y, w * (0.08f + rnd.nextFloat() * 0.22f),
                    30 + rnd.nextInt(45));
        }
        // Dark dust lanes through the clouds.
        for (int i = 0; i < 8; i++) {
            float x = w * (0.4f + rnd.nextFloat() * 0.6f), y = h * rnd.nextFloat();
            glow(c, w, h, 0xFF000000, x, y, w * (0.05f + rnd.nextFloat() * 0.1f), 90);
        }
        stars(c, w, h, rnd, w * h / 500, 1f);
    }

    /** The Milky Way as a diagonal band of haze, dust and crowded stars. */
    private static void milkyWay(Canvas c, int w, int h) {
        Random rnd = new Random(23);
        c.drawColor(0xFF02030A);
        stars(c, w, h, rnd, w * h / 1100, 1f);
        // Along the band: from the bottom left to the top right.
        float x0 = w * 0.05f, y0 = h * 1.05f, x1 = w * 1.0f, y1 = -h * 0.1f;
        for (int i = 0; i < 40; i++) {
            float t = rnd.nextFloat();
            float x = x0 + (x1 - x0) * t + (rnd.nextFloat() - 0.5f) * w * 0.08f;
            float y = y0 + (y1 - y0) * t + (rnd.nextFloat() - 0.5f) * h * 0.12f;
            int colour = t > 0.35f && t < 0.6f ? 0xFFFFD9A8 : 0xFFB9C8FF;
            glow(c, w, h, colour, x, y, w * (0.05f + rnd.nextFloat() * 0.09f), 18 + rnd.nextInt(26));
        }
        for (int i = 0; i < 18; i++) {
            float t = rnd.nextFloat();
            float x = x0 + (x1 - x0) * t + (rnd.nextFloat() - 0.5f) * w * 0.03f;
            float y = y0 + (y1 - y0) * t + (rnd.nextFloat() - 0.5f) * h * 0.04f;
            glow(c, w, h, 0xFF000000, x, y, w * (0.015f + rnd.nextFloat() * 0.04f), 110);
        }
        Paint p = paint();
        for (int i = 0; i < w * h / 160; i++) {
            float t = rnd.nextFloat();
            float spread = (float) rnd.nextGaussian() * 0.06f;
            float x = x0 + (x1 - x0) * t + spread * w * 0.6f;
            float y = y0 + (y1 - y0) * t + spread * h;
            p.setColor(0x00FFFFFF | (30 + rnd.nextInt(150)) << 24);
            c.drawCircle(x, y, 0.45f + rnd.nextFloat() * 0.35f, p);
        }
    }

    /** A large Moon on the right, lit from the right, with soft maria and a few craters. */
    private static void moon(Canvas c, int w, int h) {
        Random rnd = new Random(31);
        c.drawColor(0xFF010103);
        stars(c, w, h, rnd, w * h / 1400, 1f);
        float r = h * 0.62f, cx = w * 0.86f, cy = h * 0.55f;
        glow(c, w, h, 0xFFBFD3FF, cx, cy, r * 1.5f, 0x26);
        c.save();
        android.graphics.Path disc = new android.graphics.Path();
        disc.addCircle(cx, cy, r, android.graphics.Path.Direction.CW);
        c.clipPath(disc);
        Paint p = paint();
        p.setShader(new RadialGradient(cx + r * 0.35f, cy - r * 0.25f, r * 1.5f,
                new int[]{0xFFE9E7E1, 0xFFB9B6AE, 0xFF4A4945}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
        for (int i = 0; i < 9; i++) {   // maria
            float x = cx + (rnd.nextFloat() - 0.4f) * r * 1.2f, y = cy + (rnd.nextFloat() - 0.5f) * r * 1.3f;
            glow(c, w, h, 0xFF55534E, x, y, r * (0.15f + rnd.nextFloat() * 0.25f), 110);
        }
        Paint crater = paint();
        crater.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 40; i++) {
            float x = cx + (rnd.nextFloat() - 0.5f) * r * 1.9f, y = cy + (rnd.nextFloat() - 0.5f) * r * 1.9f;
            float cr = r * (0.01f + rnd.nextFloat() * 0.05f);
            crater.setStrokeWidth(cr * 0.35f);
            crater.setColor(0x33000000);
            c.drawCircle(x + cr * 0.15f, y + cr * 0.15f, cr, crater);
            crater.setColor(0x2EFFFFFF);
            c.drawCircle(x - cr * 0.1f, y - cr * 0.1f, cr * 0.9f, crater);
        }
        // Terminator: the side away from the sun falls off into shadow.
        p.setShader(new LinearGradient(cx - r, cy, cx + r * 0.2f, cy, 0xF0000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        c.restore();
    }
}
