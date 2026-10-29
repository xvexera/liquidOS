package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * The widgets row, which draws the wallpaper sample of its cards' Liquid Glass itself, under them. Everything of a card
 * that does not depend on where it is (the glass's tint, sheen, rim and veil, see LiquidGlass.rowGlass, and the
 * content) stays in its cached layer, only moved while the row slides; the sample, the wallpaper right behind the card,
 * is one textured round rect per card redrawn here on every frame at its place on screen. While the row slides it is
 * the plain blur; where it stops, the refraction (see LiquidGlass.sliding).
 */
public class GlassRow extends LinearLayout {
    private static final class Slab {
        final View view;
        final float radius;
        /** A widget card, under a dark veil so small coloured figures stay legible; else a round button. */
        final boolean card;
        final LiquidGlass.Paints paints;

        Slab(View view, float radius, boolean card, float density) {
            this.view = view;
            this.radius = radius;
            this.card = card;
            paints = new LiquidGlass.Paints(density);
        }
    }

    private final List<Slab> slabs = new ArrayList<>();
    private final Rect r = new Rect();
    private final RectF rect = new RectF();
    private final float density;

    public GlassRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
    }

    /** The views to draw glass under: {@code cards} with 22dp corners and a veil, {@code buttons} round. */
    void setGlass(List<View> cards, List<View> buttons) {
        slabs.clear();
        for (View v : cards) slabs.add(new Slab(v, 22 * density, true, density));
        for (View v : buttons) slabs.add(new Slab(v, 0f, false, density));
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        float inset = 0.6f * density;   // keeps the rim stroke inside the card, as LiquidGlass.Surface does
        for (Slab s : slabs) {
            View v = s.view;
            float alpha = v.getAlpha();
            if (!v.isShown() || alpha <= 0f || v.getWidth() == 0) continue;
            r.set(0, 0, v.getWidth(), v.getHeight());
            offsetDescendantRectToMyCoords(v, r);
            rect.set(r);
            // A zoomed card (advanced effects): its glass grows with it, from the same centre.
            float scale = v.getScaleX();
            if (scale != 1f) {
                float cx = rect.left + v.getPivotX(), cy = rect.top + v.getPivotY();
                rect.set(cx - (cx - rect.left) * scale, cy - (cy - rect.top) * scale,
                        cx + (rect.right - cx) * scale, cy + (rect.bottom - cy) * v.getScaleY());
            }
            rect.inset(inset, inset);
            // Off screen (slid away): nothing to draw.
            if (canvas.quickReject(rect, Canvas.EdgeType.BW)) continue;
            float radius = s.card ? s.radius * scale : Math.min(rect.width(), rect.height()) / 2f;
            int saved = alpha < 1f ? canvas.saveLayerAlpha(rect, Math.round(alpha * 255)) : -1;
            LiquidGlass.sample(canvas, rect, radius, this, s.paints, !LiquidGlass.sliding);
            if (saved >= 0) canvas.restoreToCount(saved);
        }
        super.dispatchDraw(canvas);
    }

}
