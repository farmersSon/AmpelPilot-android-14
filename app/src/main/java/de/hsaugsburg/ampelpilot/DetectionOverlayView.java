package de.hsaugsburg.ampelpilot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Transparent overlay view that draws real-time detection bounding boxes,
 * color-coded classes, and confidence percentage badges on top of the camera preview.
 */
public class DetectionOverlayView extends View {

    // [FEATURE: VISUAL_BOUNDING_BOXES_WITH_LABELS]
    public static class Box {
        final RectF rect;
        final String text;
        final int color;

        public Box(RectF rect, String text, int color) {
            this.rect = rect;
            this.text = text;
            this.color = color;
        }
    }

    private final Paint boxPaint;
    private final Paint textBgPaint;
    private final Paint textPaint;

    private final List<Box> boxesToDraw = new ArrayList<>();
    private int sourceWidth = 1;
    private int sourceHeight = 1;

    // High-visibility vivid colors
    private static final int COLOR_RED = 0xFFFF3B30;     // Vivid Red for 'red'
    private static final int COLOR_GREEN = 0xFF34C759;   // Vivid Green for 'green'
    private static final int COLOR_AMBER = 0xFFFF9500;   // Amber/Orange for 'pedestrian Traffic Light'
    private static final int COLOR_DEFAULT = 0xFF007AFF; // Blue fallback

    public DetectionOverlayView(Context context) {
        this(context, null);
    }

    public DetectionOverlayView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DetectionOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(8f);

        textBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textBgPaint.setStyle(Paint.Style.FILL);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(36f);
        textPaint.setFakeBoldText(true);
    }

    // [FEATURE: VISUAL_BOUNDING_BOXES_WITH_LABELS]
    /**
     * Update detections with complete Recognitions (Class label, confidence %, and box).
     */
    public void setRecognitions(List<Classifier.Recognition> recognitions, int srcWidth, int srcHeight) {
        synchronized (this) {
            boxesToDraw.clear();
            sourceWidth = (srcWidth > 0) ? srcWidth : 1;
            sourceHeight = (srcHeight > 0) ? srcHeight : 1;

            if (recognitions != null) {
                for (Classifier.Recognition r : recognitions) {
                    if (r.getLocation() == null) continue;
                    String title = (r.getTitle() != null) ? r.getTitle() : "Ampel";
                    float conf = (r.getConfidence() != null) ? r.getConfidence() : 0f;

                    int color;
                    String displayTitle;
                    if ("red".equalsIgnoreCase(title)) {
                        color = COLOR_RED;
                        displayTitle = "🔴 Rot";
                    } else if ("green".equalsIgnoreCase(title)) {
                        color = COLOR_GREEN;
                        displayTitle = "🟢 Grün";
                    } else if ("pedestrian Traffic Light".equalsIgnoreCase(title)
                            || title.toLowerCase(Locale.ROOT).contains("traffic")) {
                        color = COLOR_AMBER;
                        displayTitle = "🟡 Ampel";
                    } else {
                        color = COLOR_DEFAULT;
                        displayTitle = title;
                    }

                    String labelText = String.format(Locale.US, "%s %.0f%%", displayTitle, conf * 100);
                    boxesToDraw.add(new Box(new RectF(r.getLocation()), labelText, color));
                }
            }
        }
        postInvalidate();
    }

    /** Legacy compatibility method */
    public void setDetections(List<RectF> greens, List<RectF> reds, int srcWidth, int srcHeight) {
        synchronized (this) {
            boxesToDraw.clear();
            sourceWidth = (srcWidth > 0) ? srcWidth : 1;
            sourceHeight = (srcHeight > 0) ? srcHeight : 1;
            if (greens != null) {
                for (RectF r : greens) {
                    boxesToDraw.add(new Box(new RectF(r), "🟢 Grün", COLOR_GREEN));
                }
            }
            if (reds != null) {
                for (RectF r : reds) {
                    boxesToDraw.add(new Box(new RectF(r), "🔴 Rot", COLOR_RED));
                }
            }
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

            for (Box b : boxesToDraw) {
                float left = b.rect.left * scaleX;
                float top = b.rect.top * scaleY;
                float right = b.rect.right * scaleX;
                float bottom = b.rect.bottom * scaleY;

                // 1. Draw bounding box rectangle
                boxPaint.setColor(b.color);
                canvas.drawRect(left, top, right, bottom, boxPaint);

                // 2. Draw label badge with background
                float textWidth = textPaint.measureText(b.text);
                float textHeight = textPaint.getTextSize();
                float padding = 8f;

                float badgeLeft = left;
                float badgeTop = Math.max(0, top - textHeight - padding * 2);
                float badgeRight = left + textWidth + padding * 2;
                float badgeBottom = badgeTop + textHeight + padding * 2;

                textBgPaint.setColor(b.color);
                textBgPaint.setAlpha(220); // semi-transparent badge background
                canvas.drawRect(badgeLeft, badgeTop, badgeRight, badgeBottom, textBgPaint);

                // 3. Draw text inside badge
                canvas.drawText(b.text, badgeLeft + padding, badgeBottom - padding, textPaint);
            }
        }
    }
}
