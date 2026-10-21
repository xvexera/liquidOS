package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * The light on the focused tile, like the sheen on a tvOS icon: when focus arrives, a soft spot of light enters from
 * the side it came from, crosses the tile at its brightest and settles as a faint glow near the top.
 *
 * The view sits over the glass highlight at the same place and size and is composited with additive blending
 * (PLUS): it adds light to the artwork instead of laying white over it, so colours brighten and keep their
 * saturation, the closest a SDR interface gets to an HDR highlight. It is its own small hardware layer (one tile),
 * redrawn only while the light moves, about two thirds of a second per focus change.
 */
final class GlintView extends View {
    /** Strength at the crossing and at rest, as the white added at the spot's centre (0..1). */
    private static final float PEAK = 0.85f, REST = 0.22f;
    /** Resting spot: towards the top, slightly right of centre, as if lit from above. */
    private static final float REST_X = 0.62f, REST_Y = 0.18f;

    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private final float density;
    private RadialGradient spot;
    private RadialGradient vignette;
    private final Matrix vignetteMatrix = new Matrix();
    private float radius;
    private float cx = REST_X, cy = REST_Y, strength = REST;
    private ValueAnimator sweep;

    GlintView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        radius = 20 * density;
        Paint layer = new Paint();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) layer.setBlendMode(BlendMode.PLUS);
        else layer.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        setLayerType(LAYER_TYPE_HARDWARE, layer);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
    }

    void setRadius(float px) {
        radius = px;
        invalidate();
    }

    /**
     * Plays the light across the tile. {@code dx, dy}: where focus came from relative to this tile, as the sign of
     * the move (-1, 0 or 1 each); 0, 0 is a first focus and only brightens in place.
     */
    void sweep(float dx, float dy, boolean animate) {
        if (!animate) {
            if (sweep != null) sweep.cancel();
            cx = REST_X;
            cy = REST_Y;
            strength = REST;
            invalidate();
            return;
        }
        // Enter from the side the move came from: moving right, the light comes in from the left edge. A move while
        // the light is still crossing (scrolling fast) does not restart it at the edge: it keeps going from where it
        // is and how bright it is, so a quick run along the row reads as one light gliding over the tiles.
        boolean running = sweep != null && sweep.isRunning();
        if (sweep != null) sweep.removeAllUpdateListeners();
        float fromX = running ? Math.max(-0.4f, Math.min(1.4f, cx - dx * 0.35f))
                : dx == 0 && dy == 0 ? REST_X : REST_X - dx * 0.95f;
        float fromY = running ? Math.max(-0.4f, Math.min(1.2f, cy - dy * 0.3f))
                : dx == 0 && dy == 0 ? REST_Y + 0.25f : REST_Y - dy * 0.9f;
        float fromS = running ? strength : -1f;
        if (sweep != null) sweep.cancel();
        sweep = ValueAnimator.ofFloat(0f, 1f);
        sweep.setDuration(650);
        sweep.setInterpolator(new DecelerateInterpolator(1.4f));
        sweep.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            cx = fromX + (REST_X - fromX) * t;
            cy = fromY + (REST_Y - fromY) * t;
            // Brightest while crossing the middle, then easing down to the resting glow.
            float bell = (float) Math.sin(Math.PI * Math.min(1f, t * 1.25f));
            if (fromS >= 0f) {
                // Carried on: from the current brightness, still swelling a little, down to rest.
                strength = fromS + (REST - fromS) * t + (PEAK - fromS) * 0.4f * Math.max(0f, bell);
            } else {
                strength = REST + (PEAK - REST) * Math.max(0f, bell);
            }
            invalidate();
        });
        sweep.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // One unit-radius white spot, moved and scaled by the matrix each frame: no shader built per frame.
        spot = new RadialGradient(0, 0, 1f, new int[]{0xFFFFFFFF, 0x66FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        vignette = new RadialGradient(0, 0, 1.18f, new int[]{0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP);
        // The spot, kept only as far as the vignette lets it: DST_IN takes the spot's colour and the vignette's alpha.
        light.setShader(new android.graphics.ComposeShader(spot, vignette, android.graphics.PorterDuff.Mode.DST_IN));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (spot == null || strength <= 0f) return;
        float inset = 6f * density;   // the glass slab's inset, so the light covers the tile, not the glow
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float r = Math.max(rect.width(), rect.height()) * 0.6f;
        matrix.setScale(r, r * 0.9f);
        matrix.postTranslate(rect.left + rect.width() * cx, rect.top + rect.height() * cy);
        spot.setLocalMatrix(matrix);
        // The light fades out before the tile's edge (an oval vignette): on see-through glass tiles a light cut by
        // the tile's outline showed as a bright square while it crossed.
        vignetteMatrix.setScale(rect.width() / 2f, rect.height() / 2f);
        vignetteMatrix.postTranslate(rect.centerX(), rect.centerY());
        vignette.setLocalMatrix(vignetteMatrix);
        light.setAlpha(Math.round(255 * Math.min(1f, strength)));
        canvas.drawRoundRect(rect, radius, radius, light);
    }
}
