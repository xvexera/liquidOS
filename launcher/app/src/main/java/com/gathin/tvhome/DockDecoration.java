package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Draws the tvOS style lighter band behind the first (dock) row and adds a gap below it. */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    /** Liquid Glass band: the blurred wallpaper behind the dock instead of a flat colour. Null when off. */
    private final LiquidGlass.Paints liquid;
    /**
     * Android 10+: the Liquid Glass band recorded into its own node with a compositing layer, so the refraction
     * shader runs once per dock position. Focus springs redraw the grid every frame; they only composite the
     * cached band. Object so this class still loads on Android 8 and 9.
     */
    private Object bandNode;
    private final RectF recorded = new RectF();
    private final int[] recordedAt = new int[2];
    private final int[] hostAt = new int[2];
    private int recordedGeneration = -1;
    /**
     * The grid is scrolling. Re-recording the band runs the refraction shader over all of it, and doing that every
     * frame of a scroll dropped the grid to about 24 fps on the onn; moving the recorded band instead dragged the
     * backdrop it was recorded over along with it, which jumped back when the scroll ended. So while scrolling the
     * band is drawn each frame with the plain blur, sampled where it is on screen (one textured round rect), and the
     * refracting band is recorded once the grid stops.
     */
    private boolean scrolling;
    /** 0..1: how much of the refracting band shows over the plain one, faded in after a scroll ends. */
    private float refractAlpha = 1f;
    private android.animation.ValueAnimator fadeIn;

    /** @param translucent the cinematic shelf's band: the artwork and the glow show through it. */
    DockDecoration(int dockSize, float density, boolean translucent) {
        this(dockSize, density, translucent, false);
    }

    DockDecoration(int dockSize, float density, boolean translucent, boolean liquidGlass) {
        this.dockSize = dockSize;
        this.density = density;
        liquid = liquidGlass ? new LiquidGlass.Paints(density) : null;
        if (liquid != null) liquid.bezel = 18 * density;
        band.setColor(translucent ? 0x8C121014 : 0xFF141416);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(translucent ? 0x26FFFFFF : 0x1FFFFFFF);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            RecyclerView.ViewHolder vh = parent.getChildViewHolder(child);
            View card = vh instanceof AppAdapter.Holder ? ((AppAdapter.Holder) vh).card : child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;
        float pad = 14 * density;
        // The band can only draw inside the grid, and the first and last dock tiles sit 12dp in from its
        // edges: a full 14dp at the sides sliced the rounded ends flat (#454). Keep the sides, with the
        // 1dp edge line, inside the grid; the top and bottom have room.
        float side = Math.max(0f, Math.min(pad, Math.min(rect.left, parent.getWidth() - rect.right) - density));
        rect.inset(-side, -pad);
        float radius = 26 * density;
        if (liquid != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && c.isHardwareAccelerated()) {
                drawCached(c, parent, radius);
            } else {
                LiquidGlass.draw(c, rect, radius, parent, liquid, false);
            }
            return;
        }
        c.drawRoundRect(rect, radius, radius, band);
        c.drawRoundRect(rect, radius, radius, edge);
    }

    /** The grid started or stopped scrolling; once it stops the band is recorded again for where it ended. */
    /** Advanced effects: no scroll shortcut, the band refracts on every frame (smoother look, more GPU). */
    private boolean alwaysRefract;

    void setAlwaysRefract(boolean on) {
        alwaysRefract = on;
    }

    void setScrolling(boolean on, RecyclerView parent) {
        if (alwaysRefract) return;
        if (scrolling == on) return;
        scrolling = on;
        if (fadeIn != null) fadeIn.cancel();
        if (on) {
            refractAlpha = 0f;
            return;
        }
        // The refracting band comes back over about a fifth of a second instead of switching in one frame.
        fadeIn = android.animation.ValueAnimator.ofFloat(0f, 1f);
        fadeIn.setDuration(220);
        fadeIn.addUpdateListener(a -> {
            refractAlpha = (float) a.getAnimatedValue();
            parent.invalidate();
        });
        fadeIn.start();
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private void drawCached(Canvas c, RecyclerView parent, float radius) {
        RenderNode node = (RenderNode) bandNode;
        if (node == null) {
            node = new RenderNode("dockBand");
            node.setUseCompositingLayer(true, null);
            bandNode = node;
        }
        if (scrolling) {
            LiquidGlass.draw(c, rect, radius, parent, liquid, false, false);
            return;
        }
        parent.getLocationInWindow(hostAt);
        // Re-record only when the band moved, resized, or the blurred wallpaper behind it changed.
        if (!rect.equals(recorded) || hostAt[0] != recordedAt[0] || hostAt[1] != recordedAt[1]
                || recordedGeneration != LiquidGlass.generation() || !node.hasDisplayList()) {
            float pad = 2 * density;   // room for the rim's antialiasing
            node.setPosition(0, 0, (int) Math.ceil(rect.width() + 2 * pad), (int) Math.ceil(rect.height() + 2 * pad));
            RecordingCanvas rc = node.beginRecording();
            rc.translate(pad - rect.left, pad - rect.top);
            LiquidGlass.draw(rc, rect, radius, parent, liquid, false);
            node.endRecording();
            recorded.set(rect);
            recordedAt[0] = hostAt[0];
            recordedAt[1] = hostAt[1];
            recordedGeneration = LiquidGlass.generation();
        }
        if (refractAlpha < 1f) LiquidGlass.draw(c, rect, radius, parent, liquid, false, false);
        node.setAlpha(refractAlpha);
        float pad = 2 * density;
        c.save();
        c.translate(rect.left - pad, rect.top - pad);
        c.drawRenderNode(node);
        c.restore();
    }
}
