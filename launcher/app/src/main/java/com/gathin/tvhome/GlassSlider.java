package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * A level control in the manner of the iOS volume bar, laid on its side: a thick rounded glass track that fills white
 * up to the value, with its icon inside at the start and the percentage at the end. With focus it grows a little and
 * gains a bright rim and glow, so it is plain which bar Left and Right will move from across the room. The fill
 * glides to each new value.
 */
public class GlassSlider extends View {
    interface OnChange {
        /** The value after a Left or Right press. */
        void onChange(int value);
    }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private Drawable icon;
    private int max = 100;
    private int value;
    private int step = 5;
    /** The value the fill shows, gliding towards {@code value}. */
    private float shown;
    private ValueAnimator glide;
    private OnChange listener;
    /**
     * Where Left and Right must still move focus (a settings screen in columns): OK starts editing, the bar turns blue,
     * Left and Right change the value, and OK or Back finish. Off, Left and Right always change the value.
     */
    private boolean needsEdit;
    private boolean editing;
    private static final int EDIT_COLOR = 0xFF0A84FF;

    public GlassSlider(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setFocusable(true);
        rim.setStyle(Paint.Style.STROKE);
        glow.setStyle(Paint.Style.STROKE);
        text.setTextSize(14 * getResources().getDisplayMetrics().scaledDensity);
        text.setTypeface(getResources().getFont(R.font.outfit_medium));
        text.setTextAlign(Paint.Align.RIGHT);
    }

    void setIcon(int res) {
        icon = getContext().getDrawable(res);
        if (icon != null) icon = icon.mutate();
        invalidate();
    }

    void setMax(int max) {
        this.max = Math.max(1, max);
        invalidate();
    }

    void setStep(int step) {
        this.step = Math.max(1, step);
    }

    void setValue(int v) {
        value = Math.max(0, Math.min(max, v));
        shown = value;
        invalidate();
    }

    int getValue() {
        return value;
    }

    void setOnChange(OnChange listener) {
        this.listener = listener;
    }

    void setNeedsEdit(boolean on) {
        needsEdit = on;
    }

    private void setEditing(boolean on) {
        if (editing == on) return;
        editing = on;
        playSoundEffect(android.view.SoundEffectConstants.CLICK);
        invalidate();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (needsEdit) {
            boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
            if (ok) {
                if (event.getRepeatCount() == 0) setEditing(!editing);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK && editing) {
                setEditing(false);
                return true;
            }
            if (!editing) return super.onKeyDown(keyCode, event);   // arrows move focus, as on any row
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int next = Math.max(0, Math.min(max, value + (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? step : -step)));
            if (next != value) {
                value = next;
                animateTo(next);
                if (listener != null) listener.onChange(next);
            }
            return true;   // the ends of the bar do not let focus wander sideways out of the panel
        }
        return super.onKeyDown(keyCode, event);
    }

    private void animateTo(int target) {
        if (glide != null) glide.cancel();
        glide = ValueAnimator.ofFloat(shown, target);
        glide.setDuration(160);
        glide.setInterpolator(new DecelerateInterpolator());
        glide.addUpdateListener(a -> {
            shown = (float) a.getAnimatedValue();
            invalidate();
        });
        glide.start();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(gainFocus, direction, previous);
        if (!gainFocus) editing = false;
        animate().scaleX(gainFocus ? 1.02f : 1f).scaleY(gainFocus ? 1.05f : 1f).setDuration(160).start();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        boolean focused = isFocused();
        float inset = 6 * density;   // room for the glow
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float r = rect.height() / 2f;

        if (focused) {
            for (int i = 1; i <= 3; i++) {
                glow.setStrokeWidth(i * 2f * density);
                glow.setColor((editing ? 0x70 / i : 0x40 / i) << 24 | (editing ? EDIT_COLOR & 0xFFFFFF : 0xFFFFFF));
                float o = i * density;
                canvas.drawRoundRect(rect.left - o, rect.top - o, rect.right + o, rect.bottom + o, r + o, r + o, glow);
            }
        }
        // Track: dark glass, a little lighter with focus.
        track.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                focused ? 0x52FFFFFF : 0x2EFFFFFF, focused ? 0x2EFFFFFF : 0x14FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, r, r, track);

        // Fill: white up to the value, with a soft sheen; clipped from the full round rect so both ends stay round.
        // The value is a percentage: 65 fills 65% of the bar even where the top of the range is lower (dim stops at 90).
        float fraction = Math.max(0f, Math.min(1f, shown / 100f));
        float fillRight = rect.left + rect.width() * fraction;
        if (fraction > 0f) {
            fill.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                    focused ? 0xFFFFFFFF : 0xE6FFFFFF, focused ? 0xFFE6E9EE : 0xCCDDE1E6, Shader.TileMode.CLAMP));
            canvas.save();
            canvas.clipRect(rect.left, rect.top, Math.max(fillRight, rect.left + 2 * r * Math.min(1f, fraction * 8)),
                    rect.bottom);
            canvas.drawRoundRect(rect, r, r, fill);
            canvas.restore();
        }

        rim.setStrokeWidth((editing ? 3f : focused ? 2f : 1f) * density);
        rim.setColor(editing ? EDIT_COLOR : focused ? 0xF2FFFFFF : 0x40FFFFFF);
        canvas.drawRoundRect(rect, r, r, rim);

        // Icon at the start and the percentage at the end, dark where the white fill is under them.
        if (icon != null) {
            int size = Math.round(rect.height() * 0.5f);
            int left = Math.round(rect.left + r - size / 2f + 4 * density);
            int top = Math.round(rect.centerY() - size / 2f);
            icon.setBounds(left, top, left + size, top + size);
            icon.setTint(fillRight > left + size / 2f ? 0xFF1C1C1E : 0xFFFFFFFF);
            icon.draw(canvas);
        }
        String percent = value + "%";
        float textX = rect.right - r * 0.8f;
        text.setColor(fillRight > textX - text.measureText(percent) / 2 ? 0xFF1C1C1E : 0xE6FFFFFF);
        canvas.drawText(percent, textX, rect.centerY() - (text.descent() + text.ascent()) / 2, text);
    }
}
