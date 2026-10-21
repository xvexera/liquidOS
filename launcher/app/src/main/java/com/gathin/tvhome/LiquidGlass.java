package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;

/**
 * Liquid Glass surfaces: each one shows a blurred copy of the wallpaper that lies behind it, under a thin
 * white tint, a sheen across its top and a rim that is bright at the top edge and fades towards the bottom.
 *
 * The blur is not a live RenderEffect: the wallpaper is shrunk to a few dozen pixels, box-blurred once and
 * stretched back with bilinear filtering whenever it changes. Every surface then samples that one small
 * bitmap at its own position on screen, which costs a textured round rect per surface per frame.
 *
 * On Android 13 and newer the sample goes through a small AGSL shader that bends the backdrop near the rim, the way
 * a thick glass edge refracts what is behind it, splits the colours a little there and adds a specular light on
 * the edges that face up. Older TVs keep the plain blur.
 */
final class LiquidGlass {
    private static Bitmap backdrop;
    private static float backdropScale = 1f;
    /** Bumped whenever the backdrop changes, so cached surfaces (the dock band) know to redraw. */
    private static int generation;

    private LiquidGlass() {
    }

    /**
     * Rebuilds the blurred backdrop from the wallpaper as it is drawn: centre-cropped to the screen and dimmed.
     * A null wallpaper removes it, and surfaces fall back to tint and rim only.
     */
    static void setBackdrop(Bitmap wallpaper, int screenW, int screenH, int dimPercent, int blurLevel) {
        backdrop = null;
        generation++;
        if (wallpaper == null || wallpaper.isRecycled() || screenW <= 0 || screenH <= 0) return;
        // blurLevel is 0 to 100: the wallpaper is shrunk 2 to 36 times before the box blur and the stretch back. At 0 it
        // is kept at half the screen and not blurred at all, so the glass shows the picture nearly sharp and the rim
        // bends the real image, the clear tvOS look; it costs a 2 MB bitmap instead of a few kilobytes.
        int percent = Math.max(0, Math.min(100, blurLevel));
        int factor = 2 + Math.round(percent * 0.34f);
        int w = Math.max(4, screenW / factor), h = Math.max(4, screenH / factor);
        try {
            Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(small);
            float scale = Math.max((float) w / wallpaper.getWidth(), (float) h / wallpaper.getHeight());
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate((w - wallpaper.getWidth() * scale) / 2f, (h - wallpaper.getHeight() * scale) / 2f);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(wallpaper, m, p);
            if (dimPercent > 0) c.drawColor((Math.min(90, dimPercent) * 255 / 100) << 24);
            if (percent >= 10) boxBlur(small, percent >= 70 ? 2 : 1);
            backdrop = small;
            backdropScale = factor;
        } catch (OutOfMemoryError ignored) {
            backdrop = null;
        }
    }

    /** The small blurred copy of the wallpaper, or null: Launcher settings stretches it as its backdrop. */
    static Bitmap backdrop() {
        return backdrop;
    }

    static int generation() {
        return generation;
    }

    static boolean hasBackdrop() {
        return backdrop != null;
    }

    /**
     * The rim refraction. {@code d} is the signed distance to the rounded rect (negative inside); within
     * {@code bezel} of the edge the backdrop is sampled further inward, the more the closer to the rim, and the
     * three channels at slightly different depths.
     */
    private static final String REFRACTION = ""
            + "uniform shader backdrop;\n"
            + "uniform float2 center;\n"
            + "uniform float2 halfSize;\n"
            + "uniform float radius;\n"
            + "uniform float bezel;\n"
            + "uniform float strength;\n"
            + "float sdf(float2 p) {\n"
            + "  float2 q = abs(p - center) - halfSize + radius;\n"
            + "  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;\n"
            + "}\n"
            + "half4 main(float2 c) {\n"
            + "  float d = sdf(c);\n"
            + "  float edge = clamp(1.0 + d / bezel, 0.0, 1.0);\n"
            + "  float2 g = float2(sdf(c + float2(1.0, 0.0)) - sdf(c - float2(1.0, 0.0)),\n"
            + "                    sdf(c + float2(0.0, 1.0)) - sdf(c - float2(0.0, 1.0)));\n"
            + "  float2 n = g / max(length(g), 0.0001);\n"
            + "  float k = edge * edge;\n"
            + "  float2 off = -n * strength * k;\n"
            + "  half3 col = half3(backdrop.eval(c + off * 1.04).r, backdrop.eval(c + off).g,\n"
            + "                    backdrop.eval(c + off * 0.96).b);\n"
            + "  col = mix(col, col * 1.10 + 0.025, k);\n"
            + "  float light = max(dot(n, normalize(float2(-0.45, -1.0))), 0.0);\n"
            + "  col += half(pow(edge, 5.0) * light * 0.55);\n"
            + "  return half4(col, 1.0);\n"
            + "}\n";

    /** One surface's refraction shader, or null below Android 13. */
    private static Object newRefraction() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null;
        try {
            return new RuntimeShader(REFRACTION);
        } catch (RuntimeException compileFailed) {
            return null;   // a GPU driver that cannot run it keeps the plain blur
        }
    }

    /** Three passes of a box blur, which together come close to a gaussian, on a bitmap of a few thousand pixels. */
    private static void boxBlur(Bitmap bitmap, int radius) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int[] px = new int[w * h];
        int[] tmp = new int[w * h];
        bitmap.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            blurLine(px, tmp, w, h, radius, true);
            blurLine(tmp, px, w, h, radius, false);
        }
        bitmap.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void blurLine(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < len; i++) {
                int rs = 0, gs = 0, bs = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int j = Math.max(0, Math.min(len - 1, i + k));
                    int c = in[horizontal ? line * w + j : j * w + line];
                    rs += (c >> 16) & 0xFF;
                    gs += (c >> 8) & 0xFF;
                    bs += c & 0xFF;
                    n++;
                }
                out[horizontal ? line * w + i : i * w + line] = 0xFF000000 | (rs / n) << 16 | (gs / n) << 8 | (bs / n);
            }
        }
    }

    /** A plain resting surface, for widget cards. */
    static Surface surface(float radiusPx, float density) {
        return new Surface(radiusPx, density, false);
    }

    /**
     * Menus and dropdowns: the same glass under a dark veil, so rows of white text read over any wallpaper; with no
     * wallpaper behind it, an almost opaque dark slab like the old glass_panel.
     */
    static Surface panel(float radiusPx, float density) {
        Surface s = new Surface(radiusPx, density, false);
        s.veil = true;
        return s;
    }

    /** Resting glass, a brighter glass while focused. */
    static Drawable focusableSurface(float radiusPx, float density) {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused}, new Surface(radiusPx, density, true));
        d.addState(new int[0], new Surface(radiusPx, density, false));
        return d;
    }

    /**
     * The widget cards: focusable glass under a light dark veil, lighter than a menu's, so small coloured figures
     * (a green +2.4%) stay legible over a bright sky while the card still reads as the same glass as the pills.
     */
    static Drawable widgetCard(float radiusPx, float density) {
        StateListDrawable d = new StateListDrawable();
        for (boolean focused : new boolean[]{true, false}) {
            Surface s = new Surface(radiusPx, density, focused);
            s.veil = true;
            s.veilColor = focused ? 0x330A0A0E : 0x470A0A0E;
            d.addState(focused ? new int[]{android.R.attr.state_focused} : new int[0], s);
        }
        return d;
    }

    /** The top bar pill: solid white while focused (as before), blue while reorder mode is on, glass otherwise. */
    static Drawable pill(float density) {
        float radius = 21 * density;
        StateListDrawable d = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0xF5FFFFFF);
        focused.setCornerRadius(radius);
        d.addState(new int[]{android.R.attr.state_focused}, focused);
        GradientDrawable activated = new GradientDrawable();
        activated.setColor(0x596EC1FF);
        activated.setStroke(Math.round(1.5f * density), 0xFF6EC1FF);
        activated.setCornerRadius(radius);
        d.addState(new int[]{android.R.attr.state_activated}, activated);
        d.addState(new int[0], new Surface(radius, density, false));
        return d;
    }

    /**
     * Draws one glass slab into {@code rect} (in {@code host}'s coordinates). Shared by the drawables and by
     * the dock band, which DockDecoration draws straight onto the grid's canvas.
     */
    static void draw(Canvas canvas, RectF rect, float radius, View host, Paints p, boolean bright) {
        draw(canvas, rect, radius, host, p, bright, true);
    }

    /**
     * {@code refract} false draws the plain blur only: a single textured round rect, cheap enough for every frame of
     * a scroll, and still sampled where the surface is on screen at that frame.
     */
    static void draw(Canvas canvas, RectF rect, float radius, View host, Paints p, boolean bright, boolean refract) {
        Bitmap bg = backdrop;
        if (bg != null && host != null) {
            if (p.shaderBitmap != bg) {
                p.shader = new BitmapShader(bg, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                // Inside a RuntimeShader the paint's filter flag does not reach the child: without this the
                // few dozen pixels of the backdrop show as blocks instead of a smooth blur.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    p.shader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                }
                p.shaderBitmap = bg;
                p.blur.setShader(p.shader);
            }
            // On screen, not in the window: menus are windows of their own, placed over Home.
            host.getLocationOnScreen(p.loc);
            p.matrix.setScale(backdropScale, backdropScale);
            p.matrix.postTranslate(-p.loc[0], -p.loc[1]);
            p.shader.setLocalMatrix(p.matrix);
            if (refract && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && p.refraction != null
                    && canvas.isHardwareAccelerated()) {
                RuntimeShader r = (RuntimeShader) p.refraction;
                r.setInputShader("backdrop", p.shader);
                r.setFloatUniform("center", rect.centerX(), rect.centerY());
                r.setFloatUniform("halfSize", rect.width() / 2f, rect.height() / 2f);
                r.setFloatUniform("radius", Math.min(radius, Math.min(rect.width(), rect.height()) / 2f));
                float bezel = Math.min(p.bezel, Math.min(rect.width(), rect.height()) / 2f);
                r.setFloatUniform("bezel", bezel);
                r.setFloatUniform("strength", bezel * 0.9f);
                p.lens.setShader(r);
                canvas.drawRoundRect(rect, radius, radius, p.lens);
            } else {
                canvas.drawRoundRect(rect, radius, radius, p.blur);
            }
        }
        float h = rect.height();
        if (p.sizedFor != h || p.sizedTop != rect.top || p.bright != bright || p.tintedOver != (backdrop != null)) {
            p.tintedOver = backdrop != null;
            p.sizedFor = h;
            p.sizedTop = rect.top;
            p.bright = bright;
            // Over a wallpaper the refraction already reads as glass; a strong white tint only greys it.
            boolean over = backdrop != null;
            int top = bright ? 0x5CFFFFFF : over ? 0x1AFFFFFF : 0x2EFFFFFF;
            int bottom = bright ? 0x33FFFFFF : over ? 0x05FFFFFF : 0x0FFFFFFF;
            p.tint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, top, bottom, Shader.TileMode.CLAMP));
            p.sheen.setShader(new LinearGradient(0, rect.top, 0, rect.top + h * 0.5f,
                    bright ? 0x4DFFFFFF : 0x29FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            p.rim.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                    bright ? 0xE6FFFFFF : 0x99FFFFFF, bright ? 0x40FFFFFF : 0x1AFFFFFF, Shader.TileMode.CLAMP));
        }
        canvas.drawRoundRect(rect, radius, radius, p.tint);
        canvas.save();
        canvas.clipRect(rect.left, rect.top, rect.right, rect.top + h * 0.5f);
        canvas.drawRoundRect(rect, radius, radius, p.sheen);
        canvas.restore();
        canvas.drawRoundRect(rect, radius, radius, p.rim);
    }

    /** The paints one surface keeps, so drawing allocates nothing until its size changes. */
    static final class Paints {
        final Paint blur = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint lens = new Paint(Paint.ANTI_ALIAS_FLAG);
        /** A RuntimeShader on Android 13+, else null; Object so this class loads on older TVs. */
        final Object refraction = newRefraction();
        /** How far in from the rim the glass bends the backdrop. */
        float bezel;
        boolean tintedOver;
        final Matrix matrix = new Matrix();
        final int[] loc = new int[2];
        BitmapShader shader;
        Bitmap shaderBitmap;
        float sizedFor = -1f, sizedTop;
        boolean bright;

        Paints(float density) {
            bezel = 12 * density;
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(1.2f * density);
        }
    }

    /** A View background that draws one glass slab over its bounds. */
    static final class Surface extends Drawable {
        private final float radius;
        private final boolean bright;
        private final Paints paints;
        private final RectF rect = new RectF();
        private final float inset;
        /** panel() and widgetCard(): a dark layer between the glass and what sits on it. */
        boolean veil;
        int veilColor = 0x6B0A0A0E;
        private final Paint veilPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Surface(float radius, float density, boolean bright) {
            this.radius = radius;
            this.bright = bright;
            paints = new Paints(density);
            inset = 0.6f * density;   // keeps the rim stroke inside the bounds
        }

        @Override
        public void draw(Canvas canvas) {
            rect.set(getBounds());
            rect.inset(inset, inset);
            Callback cb = getCallback();
            // A StateListDrawable is the callback of the surfaces it holds; climb to the view.
            while (cb instanceof Drawable) cb = ((Drawable) cb).getCallback();
            if (veil && backdrop == null) {
                veilPaint.setColor(0xF21C1C20);
                canvas.drawRoundRect(rect, radius, radius, veilPaint);
            }
            LiquidGlass.draw(canvas, rect, radius, cb instanceof View ? (View) cb : null, paints, bright);
            if (veil && backdrop != null) {
                veilPaint.setColor(veilColor);
                canvas.drawRoundRect(rect, radius, radius, veilPaint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
