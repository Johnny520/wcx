package h.Hchat.hooks.api.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Shared Hchat Agent mark used by injected menus and the floating entry. */
public final class HchatAgentIconDrawable extends Drawable {
    public enum Frame {
        ROUNDED_RECTANGLE,
        CIRCLE
    }

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frameRect = new RectF();
    private final Frame frame;

    public HchatAgentIconDrawable(int color, Frame frame) {
        this.frame = frame;
        strokePaint.setColor(color);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public void draw(Canvas canvas) {
        android.graphics.Rect bounds = getBounds();
        float size = Math.min(bounds.width(), bounds.height());
        if (size <= 0f) return;
        float left = bounds.left + (bounds.width() - size) / 2f;
        float top = bounds.top + (bounds.height() - size) / 2f;
        float stroke = Math.max(2.0f, size * 0.055f);
        strokePaint.setStrokeWidth(stroke);

        frameRect.set(
                left + size * 0.18f,
                top + size * 0.18f,
                left + size * 0.82f,
                top + size * 0.82f
        );
        if (frame == Frame.CIRCLE) {
            canvas.drawOval(frameRect, strokePaint);
        } else {
            float radius = size * 0.14f;
            canvas.drawRoundRect(frameRect, radius, radius, strokePaint);
        }

        float hLeft = left + size * 0.38f;
        float hRight = left + size * 0.62f;
        float hTop = top + size * 0.35f;
        float hMid = top + size * 0.50f;
        float hBottom = top + size * 0.65f;
        canvas.drawLine(hLeft, hTop, hLeft, hBottom, strokePaint);
        canvas.drawLine(hRight, hTop, hRight, hBottom, strokePaint);
        canvas.drawLine(hLeft, hMid, hRight, hMid, strokePaint);
    }

    @Override
    public void setAlpha(int alpha) {
        strokePaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        strokePaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return 96;
    }

    @Override
    public int getIntrinsicHeight() {
        return 96;
    }
}
