package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.HorizontalScrollView;

/**
 * The widgets row's scroller. HorizontalScrollView jumps to a card that takes focus off screen; this one glides there
 * instead, eased like the grid's own scroll, and keeps a little of the next card in view so the row reads as one that
 * goes on.
 */
public class GlideScrollView extends HorizontalScrollView {
    private static final long GLIDE_MS = 320;
    private ValueAnimator glide;

    public GlideScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** No jump: {@link #requestChildFocus} glides instead. */
    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        return 0;
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        if (focused != null) reveal(focused);
    }

    /** Brings {@code v} fully into view, with room to spare, by a short eased slide. */
    private void reveal(View v) {
        if (getChildCount() == 0 || getWidth() == 0) return;
        Rect r = new Rect(0, 0, v.getWidth(), v.getHeight());
        offsetDescendantRectToMyCoords(v, r);   // in content coordinates: the scroll is not counted
        int margin = Math.round(48 * getResources().getDisplayMetrics().density);
        int from = getScrollX();
        int left = from + getPaddingLeft();
        int right = from + getWidth() - getPaddingRight();
        int target = from;
        if (r.left - margin < left) target = r.left - margin - getPaddingLeft();
        else if (r.right + margin > right) target = r.right + margin - getWidth() + getPaddingRight();
        int max = Math.max(0, getChildAt(0).getWidth() + getPaddingLeft() + getPaddingRight() - getWidth());
        target = Math.max(0, Math.min(max, target));
        if (glide != null) glide.cancel();
        if (target == from) return;
        glide = ValueAnimator.ofInt(from, target);
        glide.setDuration(GLIDE_MS);
        glide.setInterpolator(new DecelerateInterpolator(1.6f));
        glide.addUpdateListener(a -> scrollTo((int) a.getAnimatedValue(), 0));
        glide.start();
    }
}
