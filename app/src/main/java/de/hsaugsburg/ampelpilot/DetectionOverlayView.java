package de.hsaugsburg.ampelpilot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Transparent overlay view that draws detection rectangles on top of the camera preview.
 */
public class DetectionOverlayView extends View {

    private final Paint greenPaint;
    private final Paint redPaint;
    private final List<RectF> greenRects = new ArrayList<>();
    private final List<RectF> redRects = new ArrayList<>();
    private int sourceWidth = 1;
    private int sourceHeight = 1;

    public DetectionOverlayView(Context context) {
        this(context, null);
    }

    public DetectionOverlayView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DetectionOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        greenPaint = new Paint();
        greenPaint.setColor(0xFF00FF00);
        greenPaint.setStyle(Paint.Style.STROKE);
        greenPaint.setStrokeWidth(6f);

        redPaint = new Paint();
        redPaint.setColor(0xFFFF0000);
        redPaint.setStyle(Paint.Style.STROKE);
        redPaint.setStrokeWidth(6f);
    }

    /**
     * Update the detection results to draw.
     * @param greens List of green detection rectangles (in source image coordinates)
     * @param reds List of red detection rectangles (in source image coordinates)
     * @param srcWidth Width of the source analysis image
     * @param srcHeight Height of the source analysis image
     */
    public void setDetections(List<RectF> greens, List<RectF> reds, int srcWidth, int srcHeight) {
        synchronized (this) {
            greenRects.clear();
            greenRects.addAll(greens);
            redRects.clear();
            redRects.addAll(reds);
            sourceWidth = srcWidth;
            sourceHeight = srcHeight;
        }
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float viewWidth = getWidth();
        float viewHeight = getHeight();

        synchronized (this) {
            float scaleX = viewWidth / (float) sourceWidth;
            float scaleY = viewHeight / (float) sourceHeight;

            for (RectF r : greenRects) {
                canvas.drawRect(r.left * scaleX, r.top * scaleY,
                        r.right * scaleX, r.bottom * scaleY, greenPaint);
            }
            for (RectF r : redRects) {
                canvas.drawRect(r.left * scaleX, r.top * scaleY,
                        r.right * scaleX, r.bottom * scaleY, redPaint);
            }
        }
    }
}
