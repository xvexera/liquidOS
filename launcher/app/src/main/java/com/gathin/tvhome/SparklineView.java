package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** The BTC card's 24 hour line: a stroke in the trend colour over a fill that fades to nothing. */
public class SparklineView extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path area = new Path();
    private float[] values;
    private int color = 0xFF34C759;

    public SparklineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.6f * getResources().getDisplayMetrics().density);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    void setValues(float[] values, int color) {
        this.values = values;
        this.color = color;
        rebuild();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuild();
    }

    private void rebuild() {
        path.reset();
        area.reset();
        int w = getWidth(), h = getHeight();
        if (values == null || values.length < 2 || w == 0 || h == 0) return;
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        float range = max - min == 0 ? 1 : max - min;
        float pad = line.getStrokeWidth();
        for (int i = 0; i < values.length; i++) {
            float x = pad + (w - 2 * pad) * i / (values.length - 1);
            float y = pad + (h - 2 * pad) * (1 - (values[i] - min) / range);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        area.set(path);
        area.lineTo(w - pad, h);
        area.lineTo(pad, h);
        area.close();
        line.setColor(color);
        fill.setShader(new LinearGradient(0, 0, 0, h, (color & 0x00FFFFFF) | 0x59000000,
                color & 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (path.isEmpty()) return;
        canvas.drawPath(area, fill);
        canvas.drawPath(path, line);
    }
}
