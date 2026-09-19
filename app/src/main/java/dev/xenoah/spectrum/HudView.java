package dev.xenoah.spectrum;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import dev.xenoah.spectrum.core.HudRenderer;
import dev.xenoah.spectrum.core.HudState;

final class HudView extends View implements HudRenderer.Surface {
    private final HudRenderer renderer = new HudRenderer();
    private final HudState state;
    private final MainActivity owner;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    private final GestureDetector gestures;
    private Canvas canvas;
    private float scale = 1, offsetX, offsetY;
    private boolean updating;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!updating) return;
            owner.update(); invalidate(); postDelayed(this, 33);
        }
    };

    HudView(Context context, HudState state, final MainActivity owner) {
        super(context); this.state = state; this.owner = owner;
        setBackgroundColor(0xff000000); setFocusable(true); setFocusableInTouchMode(true); setContentDescription("Rokid audio spectrum analyzer");
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                performClick(); owner.touch((e.getX() - offsetX) / scale, (e.getY() - offsetY) / scale); return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) { owner.back(); return true; }
            @Override public void onLongPress(MotionEvent e) { owner.clearPeaks(); }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || b == null) return false;
                float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                if (Math.max(Math.abs(dx), Math.abs(dy)) < 35 * scale) return false;
                float delta = Math.abs(dx) >= Math.abs(dy) ? dx : dy;
                owner.swipe(delta < 0 ? 1 : -1); return true;
            }
        });
    }

    void startUpdates() { if (!updating) { updating = true; post(refresh); } }
    void stopUpdates() { updating = false; removeCallbacks(refresh); }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        scale = Math.min(getWidth() / HudRenderer.WIDTH, getHeight() / HudRenderer.HEIGHT);
        offsetX = (getWidth() - HudRenderer.WIDTH * scale) / 2;
        offsetY = (getHeight() - HudRenderer.HEIGHT * scale) / 2;
        c.save(); c.translate(offsetX, offsetY); c.scale(scale, scale); c.clipRect(0, 0, 480, 400);
        canvas = c; renderer.draw(this, state); canvas = null; c.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent event) { return gestures.onTouchEvent(event); }
    @Override public boolean performClick() { super.performClick(); return true; }

    private void color(int green) { paint.setColor(0xff000000 | (Math.max(0, Math.min(255, green)) << 8)); }
    @Override public void rect(float x, float y, float w, float h, int green) {
        color(green); paint.setStyle(Paint.Style.FILL); canvas.drawRect(x, y, x + w, y + h, paint);
    }
    @Override public void line(float x1, float y1, float x2, float y2, float width, int green) {
        color(green); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(width); canvas.drawLine(x1, y1, x2, y2, paint);
    }
    @Override public void text(String text, float x, float baseline, float size, int green, boolean isBold) {
        color(green); paint.setStyle(Paint.Style.FILL); paint.setTextSize(size); paint.setTypeface(isBold ? bold : regular);
        canvas.drawText(text, x, baseline, paint);
    }
}
