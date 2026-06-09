/*
 * Copyright (c) 2024 EKA2L1 Team.
 *
 * This file is part of EKA2L1 project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.github.eka2l1.config;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Reusable overlay that lets the user drag the four corners and/or the
 * whole rectangle of the emulated screen, in normalised 0..1 window
 * coordinates. The current rectangle is exposed via
 * {@link #getX1()}/{...}/{@link #getY2()} and a live
 * {@link Listener} (used by the in-game editor to push the new
 * rectangle to the native side every frame).
 *
 * <p>The view itself is purely visual + touch handling; the caller is
 * responsible for persisting the rectangle and telling the emulator
 * about the new size/position.</p>
 */
public class ScreenPositionEditor extends View {

    /** Minimum normalized size of the screen rect. */
    private static final float MIN_SIZE = 0.05f;

    private static final int HANDLE_NONE = -1;
    private static final int HANDLE_BODY = 0;
    private static final int HANDLE_TL = 1;
    private static final int HANDLE_TR = 2;
    private static final int HANDLE_BL = 3;
    private static final int HANDLE_BR = 4;

    /** Pixel radius of the corner hit-target. */
    private static final int HANDLE_RADIUS_PX = 80;

    /** Normalized 0..1 rectangle: top-left + bottom-right. */
    private float x1, y1, x2, y2;
    private int activeHandle = HANDLE_NONE;

    private final Paint rectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * Optional live callback. Invoked on every drag move. The host can
     * forward the rectangle to the native side so the game is redrawn
     * with the new size/position while the user is still dragging.
     */
    public interface Listener {
        /**
         * Called whenever the rect changes. {@code confirm} is true on
         * touch-up, false while dragging. The host can use that to
         * skip persistent work during the drag and only save on
         * confirm.
         */
        void onRectChanged(float x1, float y1, float x2, float y2, boolean confirm);
    }

    private Listener listener;

    public ScreenPositionEditor(Context context) {
        super(context);
        init();
    }

    public ScreenPositionEditor(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ScreenPositionEditor(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        rectPaint.setColor(Color.parseColor("#88E91E63"));
        rectPaint.setStyle(Paint.Style.FILL);

        handlePaint.setColor(Color.parseColor("#E91E63"));
        handlePaint.setStyle(Paint.Style.FILL);

        handleStroke.setColor(Color.WHITE);
        handleStroke.setStyle(Paint.Style.STROKE);
        handleStroke.setStrokeWidth(4f);

        setClickable(true);
        setFocusable(true);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public float getX1() { return x1; }
    public float getY1() { return y1; }
    public float getX2() { return x2; }
    public float getY2() { return y2; }

    /** Seed the rect from normalised coordinates. Values are clamped. */
    public void setRect(float nx1, float ny1, float nx2, float ny2) {
        x1 = clamp01(nx1);
        y1 = clamp01(ny1);
        x2 = clamp01(nx2);
        y2 = clamp01(ny2);
        if (x2 - x1 < MIN_SIZE) x2 = clamp01(x1 + MIN_SIZE);
        if (y2 - y1 < MIN_SIZE) y2 = clamp01(y1 + MIN_SIZE);
        invalidate();
    }

    /** Reset to a sensible default with a small inset. */
    public void resetToDefault() {
        setRect(0.15f, 0.15f, 0.85f, 0.85f);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float l = x1 * w;
        float t = y1 * h;
        float r = x2 * w;
        float b = y2 * h;

        c.drawRect(new RectF(l, t, r, b), rectPaint);

        float rPx = HANDLE_RADIUS_PX;
        c.drawCircle(l, t, rPx, handlePaint);
        c.drawCircle(r, t, rPx, handlePaint);
        c.drawCircle(l, b, rPx, handlePaint);
        c.drawCircle(r, b, rPx, handlePaint);
        c.drawCircle(l, t, rPx, handleStroke);
        c.drawCircle(r, t, rPx, handleStroke);
        c.drawCircle(l, b, rPx, handleStroke);
        c.drawCircle(r, b, rPx, handleStroke);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return false;

        float nx = event.getX() / w;
        float ny = event.getY() / h;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                activeHandle = pickHandle(nx, ny);
                if (activeHandle == HANDLE_NONE) {
                    return false;
                }
                applyDrag(nx, ny);
                if (listener != null) {
                    listener.onRectChanged(x1, y1, x2, y2, false);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (activeHandle == HANDLE_NONE) return false;
                applyDrag(nx, ny);
                if (listener != null) {
                    listener.onRectChanged(x1, y1, x2, y2, false);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean wasDragging = activeHandle != HANDLE_NONE;
                activeHandle = HANDLE_NONE;
                if (wasDragging && listener != null) {
                    listener.onRectChanged(x1, y1, x2, y2, true);
                }
                return wasDragging;
            }
            default:
                return false;
        }
    }

    private int pickHandle(float nx, float ny) {
        float dx = Math.abs(nx - x1);
        float dy = Math.abs(ny - y1);
        float dRight = Math.abs(nx - x2);
        float dBottom = Math.abs(ny - y2);

        float tol = 0.06f;
        if (dx < tol && dy < tol) return HANDLE_TL;
        if (dRight < tol && dy < tol) return HANDLE_TR;
        if (dx < tol && dBottom < tol) return HANDLE_BL;
        if (dRight < tol && dBottom < tol) return HANDLE_BR;

        if (nx > x1 && nx < x2 && ny > y1 && ny < y2) {
            return HANDLE_BODY;
        }
        return HANDLE_NONE;
    }

    private void applyDrag(float nx, float ny) {
        nx = clamp01(nx);
        ny = clamp01(ny);
        switch (activeHandle) {
            case HANDLE_TL:
                x1 = Math.min(nx, x2 - MIN_SIZE);
                y1 = Math.min(ny, y2 - MIN_SIZE);
                break;
            case HANDLE_TR:
                x2 = Math.max(nx, x1 + MIN_SIZE);
                y1 = Math.min(ny, y2 - MIN_SIZE);
                break;
            case HANDLE_BL:
                x1 = Math.min(nx, x2 - MIN_SIZE);
                y2 = Math.max(ny, y1 + MIN_SIZE);
                break;
            case HANDLE_BR:
                x2 = Math.max(nx, x1 + MIN_SIZE);
                y2 = Math.max(ny, y1 + MIN_SIZE);
                break;
            case HANDLE_BODY: {
                float w = x2 - x1;
                float h = y2 - y1;
                float newX1 = nx - w / 2f;
                float newY1 = ny - h / 2f;
                if (newX1 < 0) newX1 = 0;
                if (newY1 < 0) newY1 = 0;
                if (newX1 + w > 1) newX1 = 1 - w;
                if (newY1 + h > 1) newY1 = 1 - h;
                x1 = newX1;
                y1 = newY1;
                x2 = x1 + w;
                y2 = y1 + h;
                break;
            }
        }
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
