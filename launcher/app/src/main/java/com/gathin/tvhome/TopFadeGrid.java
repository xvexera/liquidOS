package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;

import androidx.recyclerview.widget.RecyclerView;

/**
 * The app grid. Scrolled, its rows go on up past its top edge to just under the widgets (Home lets it draw there, see
 * setFadeTop) and fade out on the way, instead of being cut at the edge. Only that strip goes through an offscreen
 * layer, and only while the grid is scrolled; the rest draws as usual.
 */
public class TopFadeGrid extends RecyclerView {
    /** Where the rows stop, in this view's coordinates (negative: above it); NO_FADE: at its own top, cut. */
    static final int NO_FADE = Integer.MIN_VALUE;
    private int fadeTop = NO_FADE;
    private int fadeLength;
    private final Paint fade = new Paint();

    public TopFadeGrid(Context context, AttributeSet attrs) {
        super(context, attrs);
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
    }

    /** The rows end at {@code top} (this view's coordinates) and fade in over {@code length} below it. */
    void setFadeTop(int top, int length) {
        if (top == fadeTop && length == fadeLength) return;
        fadeTop = top;
        fadeLength = length;
        // Opaque at the top (erases everything), clear at the bottom (keeps it): DST_OUT.
        fade.setShader(new LinearGradient(0, top, 0, top + length, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
        invalidate();
    }

    // onDraw on its own is meant: the strip needs the decorations under the rows again, not the whole view.
    @android.annotation.SuppressLint("WrongCall")
    @Override
    public void draw(Canvas canvas) {
        if (fadeTop == NO_FADE) {
            super.draw(canvas);
            return;
        }
        int w = getWidth(), h = getHeight();
        int strip = fadeTop + fadeLength;
        if (computeVerticalScrollOffset() <= 0) {
            // At rest nothing reaches the strip: draw as usual, cut where the rows stop.
            canvas.save();
            canvas.clipRect(0, fadeTop, w, h);
            super.draw(canvas);
            canvas.restore();
            return;
        }
        // Below the strip: as usual.
        canvas.save();
        canvas.clipRect(0, strip, w, h);
        super.draw(canvas);
        canvas.restore();
        // The strip: into its own small layer, then faded out towards the top. Only what reaches it is drawn again:
        // the decorations under the rows (the dock band) and the tiles that cross it.
        int saved = canvas.saveLayer(0, fadeTop, w, strip, null);
        onDraw(canvas);
        long time = getDrawingTime();
        for (int i = 0; i < getChildCount(); i++) {
            android.view.View child = getChildAt(i);
            float top = child.getY(), bottom = top + child.getHeight();
            if (child.getVisibility() == VISIBLE && bottom > fadeTop && top < strip) drawChild(canvas, child, time);
        }
        canvas.drawRect(0, fadeTop, w, strip, fade);
        canvas.restoreToCount(saved);
    }
}
