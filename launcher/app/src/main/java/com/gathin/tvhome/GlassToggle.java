package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * An on/off switch in the iOS 26 manner: a glass track that fills green when on, and a knob drawn as a small
 * lens (white body, bright rim, sheen) that stretches while it slides across and settles round again. Drawn
 * with a handful of round rects, no bitmaps or shaders beyond two gradients, and only redrawn while it moves.
 * It reads the menu row's focus (duplicateParentState): on the white focused row the off track turns dark so it
 * still shows.
 */
final class GlassToggle extends View {
    private static final int ON_COLOR = 0xFF30D158;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private boolean checked;
    /** 0 off, 1 on; in between while sliding. */
    private float position;
    /** 0 at rest, up to 1 mid-slide: how far the knob is stretched into a lens. */
    private float stretch;
    private ValueAnimator animator;

    GlassToggle(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setDuplicateParentStateEnabled(true);
        setFocusable(false);
        trackRim.setStyle(Paint.Style.STROKE);
        trackRim.setStrokeWidth(density);
        knobRim.setStyle(Paint.Style.STROKE);
        knobRim.setStrokeWidth(1.2f * density);
        knobRim.setColor(0xE6FFFFFF);
        shadow.setColor(0x38000000);
        knob.setColor(0xFFFFFFFF);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(Math.round(50 * density), Math.round(30 * density));
    }

    boolean isChecked() {
        return checked;
    }

    void setChecked(boolean on, boolean animate) {
        if (animator != null) animator.cancel();
        checked = on;
        float target = on ? 1f : 0f;
        if (!animate) {
            position = target;
            stretch = 0f;
            invalidate();
            return;
        }
        float from = position;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(320);
        // Quick start, soft landing with a hint of overshoot, like the tvOS/iOS switch.
        animator.setInterpolator(new PathInterpolator(0.3f, 1.35f, 0.4f, 1f));
        animator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            position = from + (target - from) * t;
            float raw = a.getAnimatedFraction();
            stretch = (float) Math.sin(Math.PI * Math.min(1f, raw * 1.15f));
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    private boolean onWhiteRow() {
        for (int s : getDrawableState()) if (s == android.R.attr.state_focused) return true;
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float pad = 1.5f * density;
        float r = (h - 2 * pad) / 2f;
        rect.set(pad, pad, w - pad, h - pad);
        boolean white = onWhiteRow();

        // Track: glass when off (dark glass on the white focused row), filling green as the knob crosses.
        int off = white ? 0x2E000000 : 0x38FFFFFF;
        float p = Math.max(0f, Math.min(1f, position));
        track.setColor(blend(off, ON_COLOR, p));
        canvas.drawRoundRect(rect, r, r, track);
        sheen.setShader(new LinearGradient(0, rect.top, 0, rect.centerY(), 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, r, r, sheen);
        trackRim.setColor(white ? 0x26000000 : 0x4DFFFFFF);
        canvas.drawRoundRect(rect, r, r, trackRim);

        // Knob: a round lens that stretches sideways (and flattens a touch) mid-slide.
        float inset = 2.5f * density;
        float kr = r - inset;
        float kw = kr * (1f + 0.45f * stretch), kh = kr * (1f - 0.06f * stretch);
        float travel = (rect.width() - 2 * inset - 2 * kr);
        float cx = rect.left + inset + kr + travel * position;
        float cy = rect.centerY();
        canvas.drawRoundRect(cx - kw, cy - kh + density, cx + kw, cy + kh + 1.5f * density, kh, kh, shadow);
        rect.set(cx - kw, cy - kh, cx + kw, cy + kh);
        knob.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, 0xFFFFFFFF, 0xFFE9ECF1, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, kh, kh, knob);
        knobRim.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, 0xFFFFFFFF, 0x66C9D2DC, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, kh, kh, knobRim);
    }

    private static int blend(int a, int b, float t) {
        int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return Math.round(aa + (ba - aa) * t) << 24 | Math.round(ar + (br - ar) * t) << 16
                | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
    }
}
