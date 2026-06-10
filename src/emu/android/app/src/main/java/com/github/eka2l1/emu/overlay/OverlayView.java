/*
 * Copyright (c) 2021 EKA2L1 Team
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

package com.github.eka2l1.emu.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class OverlayView extends View {
    private final Rect surfaceRect = new Rect();
    private final List<Overlay> overlays = new ArrayList<>();

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int save = canvas.save();
        canvas.translate(surfaceRect.left, surfaceRect.top);
        for (Overlay o : overlays) {
            if (o != null) o.paint(canvas);
        }
        canvas.restoreToCount(save);
    }

    public void setTargetBounds(Rect bounds) {
        surfaceRect.set(bounds);
    }

    /**
     * Replace the list of overlays. The keyboard should always be the first
     * element so it gets first crack at every touch (it has more keys than
     * any other overlay and the user expects the keyboard hit-test to win
     * when both shapes overlap).
     */
    public void setOverlays(List<Overlay> list) {
        overlays.clear();
        if (list != null) overlays.addAll(list);
        invalidate();
    }

    /** Add an overlay to the end of the dispatch order. */
    public void addOverlay(Overlay overlay) {
        if (overlay == null) return;
        overlays.add(overlay);
        invalidate();
    }

    /** Remove a previously added overlay. */
    public void removeOverlay(Overlay overlay) {
        if (overlay == null) return;
        for (Iterator<Overlay> it = overlays.iterator(); it.hasNext(); ) {
            if (it.next() == overlay) {
                it.remove();
                invalidate();
                return;
            }
        }
    }

    /**
     * Dispatch a pointer event to every overlay in order. As soon as one
     * overlay returns {@code true} the event is considered handled and we
     * stop walking the list — this matches the existing semantics where
     * the keyboard gets to swallow touches before they reach the emulator
     * touch screen.
     *
     * <p>Always returns {@code true} so the SurfaceView's OnTouchListener
     * keeps the gesture (Android would otherwise steal subsequent MOVE
     * events).</p>
     */
    public boolean dispatchPointerEvent(int action, int pointerId, float x, float y) {
        // Translate to overlay-local coordinates.
        float lx = x - surfaceRect.left;
        float ly = y - surfaceRect.top;
        boolean handled = false;
        for (Overlay o : overlays) {
            if (o == null) continue;
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    handled = o.pointerPressed(pointerId, lx, ly);
                    break;
                case MotionEvent.ACTION_MOVE:
                    handled = o.pointerDragged(pointerId, lx, ly);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    handled = o.pointerReleased(pointerId, lx, ly);
                    break;
                default:
                    continue;
            }
            if (handled) break;
        }
        return true;
    }

    /**
     * Dispatch a pointer event and return whether any overlay consumed it.
     * Unlike {@link #dispatchPointerEvent(int, int, float, float)} this
     * surfaces the handled flag so callers (e.g. the emulator activity)
     * can decide whether to forward the touch to the Symbian touch screen.
     */
    public boolean dispatchPointerEventHandled(int action, int pointerId, float x, float y) {
        float lx = x - surfaceRect.left;
        float ly = y - surfaceRect.top;
        for (Overlay o : overlays) {
            if (o == null) continue;
            boolean handled = false;
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    handled = o.pointerPressed(pointerId, lx, ly);
                    break;
                case MotionEvent.ACTION_MOVE:
                    handled = o.pointerDragged(pointerId, lx, ly);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    handled = o.pointerReleased(pointerId, lx, ly);
                    break;
                default:
                    continue;
            }
            if (handled) return true;
        }
        return false;
    }

    /** Convenience getter for back-compat with code that previously held a
     *  single overlay reference. Returns the first overlay in the list. */
    public Overlay getFirstOverlay() {
        return overlays.isEmpty() ? null : overlays.get(0);
    }

    /** Back-compat setter: replaces the list with a single overlay. */
    public void setOverlay(Overlay overlay) {
        overlays.clear();
        if (overlay != null) overlays.add(overlay);
        invalidate();
    }
}