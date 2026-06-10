/*
 * Copyright (c) 2024 EKA2L1 Team
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
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;

import com.github.eka2l1.emu.Emulator;
import com.github.eka2l1.emu.Keycode;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Eight-direction on-screen joystick.
 *
 * <p>The stick has a fixed {@code radius} (computed from the screen size) and
 * a draggable knob the user can pull in any direction. While the user holds
 * a finger inside the stick area the current direction is dispatched to the
 * emulator by computing the angle of the touch point relative to the centre
 * and snapping it to the closest of eight 45&deg; sectors (the threshold
 * between sectors is 22.5&deg;). On release every direction key that is
 * still down is released.
 *
 * <p>The joystick reuses the same Overlay contract as the virtual keyboard
 * so it composes with {@link OverlayView}, the same
 * {@link com.github.eka2l1.emu.overlay.VirtualKeyboard.LayoutListener layout-changed
 * listener} hook is used to notify callers (e.g. the activity) that the
 * layout was modified and should be persisted.
 *
 * <p>While editing the stick is selected, dragged, scaled and deleted via
 * the same code path as a regular key. Magnetic snapping to other overlays
 * is intentionally disabled — the user is in full control of where the
 * stick lives.
 */
public class Joystick implements Overlay {

    private static final String TAG = Joystick.class.getName();

    /**
     * Direction index that maps to the key codes returned by
     * {@link #directionKeys(int)}. Indices are stored on disk so they must
     * stay stable.
     */
    public static final int DIR_UP = 0;
    public static final int DIR_UP_RIGHT = 1;
    public static final int DIR_RIGHT = 2;
    public static final int DIR_DOWN_RIGHT = 3;
    public static final int DIR_DOWN = 4;
    public static final int DIR_DOWN_LEFT = 5;
    public static final int DIR_LEFT = 6;
    public static final int DIR_UP_LEFT = 7;

    /** Default key code per direction. Caller can override per-stick. */
    public static final int[] DEFAULT_DIRECTION_KEYS = new int[]{
            Keycode.KEY_UP,
            Keycode.KEY_UP,      // up + right are emitted as two keys below
            Keycode.KEY_RIGHT,
            Keycode.KEY_DOWN,
            Keycode.KEY_DOWN,
            Keycode.KEY_LEFT,
            Keycode.KEY_LEFT,
            Keycode.KEY_UP
    };

    /**
     * Optional second key code emitted at the same time as the primary.
     * Used for diagonal directions: e.g. up-right emits KEY_UP + KEY_RIGHT.
     */
    public static final int[] DEFAULT_DIRECTION_SECONDARY = new int[]{
            0,
            Keycode.KEY_RIGHT,
            0,
            Keycode.KEY_RIGHT,
            0,
            Keycode.KEY_LEFT,
            0,
            Keycode.KEY_LEFT
    };

    /** Edit/layout modes mirrored from VirtualKeyboard so the activity code
     *  can use one constant table for the two overlay types. */
    public static final int LAYOUT_EOF = VirtualKeyboard.LAYOUT_EOF;
    public static final int LAYOUT_KEYS = VirtualKeyboard.LAYOUT_KEYS;
    public static final int LAYOUT_SCALES = VirtualKeyboard.LAYOUT_SCALES;

    /** Layout change contract — same shape as VirtualKeyboard's. */
    public interface LayoutListener {
        void layoutChanged(Joystick joystick);
    }

    /** Owns the eight direction key codes. Lets callers customise mapping. */
    public static class DirectionMap {
        public int[] primary;
        public int[] secondary;

        public DirectionMap() {
            primary = DEFAULT_DIRECTION_KEYS.clone();
            secondary = DEFAULT_DIRECTION_SECONDARY.clone();
        }

        public DirectionMap(int[] primary, int[] secondary) {
            this.primary = primary.clone();
            this.secondary = secondary.clone();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof DirectionMap)) return false;
            DirectionMap other = (DirectionMap) o;
            if (primary.length != other.primary.length) return false;
            if (secondary.length != other.secondary.length) return false;
            for (int i = 0; i < primary.length; i++) {
                if (primary[i] != other.primary[i]) return false;
                if (secondary[i] != other.secondary[i]) return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int h = primary.length;
            for (int v : primary) h = 31 * h + v;
            for (int v : secondary) h = 31 * h + v;
            return h;
        }
    }

    /** Geometry of the joystick: an enclosing square plus a draggable knob. */
    private final RectF bounds = new RectF();
    private float knobRadius;
    private float centerX;
    private float centerY;
    private float knobX;
    private float knobY;

    /** Scaling factor relative to the default radius. Editable like a key. */
    private float scale = 1.0f;

    private boolean visible = true;
    private boolean selected;

    /** Active pointer (during press/drag/release) — 1 finger only. */
    private int activePointer = -1;

    /** Direction that is currently held down (-1 = none). */
    private int activeDirection = -1;

    /** Per-direction key mapping. */
    private DirectionMap directionMap = new DirectionMap();

    /** Paints reused across frames. */
    private final Paint fillPaint = new Paint();
    private final Paint strokePaint = new Paint();
    private final Paint knobPaint = new Paint();
    private final Paint textPaint = new Paint();

    /** Edit state, mirrors VirtualKeyboard's layoutEditMode. */
    private int layoutEditMode = LAYOUT_EOF;
    private float dragOffsetX;
    private float dragOffsetY;
    private float prevScale;
    private boolean scaling;

    /** Host view for repaint requests. */
    private View overlayView;

    /** Hooked up by the activity to persist the layout. */
    private LayoutListener listener;

    /** Optional screen bounds, used to clamp position when editing. */
    private RectF screen;
    private RectF virtualScreen;

    public Joystick(Context context) {
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        knobPaint.setStyle(Paint.Style.FILL);

        Typeface tf = Typeface.createFromAsset(
                context.getAssets(), "Roboto-Regular.ttf");
        textPaint.setTypeface(tf);
        textPaint.setTextAlign(Paint.Align.CENTER);
        float size = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 14,
                context.getResources().getDisplayMetrics());
        textPaint.setTextSize(size);
    }

    public void setDirectionMap(DirectionMap map) {
        if (map == null) return;
        this.directionMap = map;
        notifyChanged();
    }

    public DirectionMap getDirectionMap() {
        return directionMap;
    }

    public void setScale(float scale) {
        float clamped = Math.max(0.4f, Math.min(3.0f, scale));
        if (Math.abs(clamped - this.scale) < 1e-4f) return;
        this.scale = clamped;
        applyScale();
        notifyChanged();
    }

    public float getScale() {
        return scale;
    }

    public RectF getBounds() {
        return bounds;
    }

    /** Compute the default radius from the current screen size. */
    private float defaultRadius() {
        if (screen == null) return 120f;
        float maxSize = Math.max(screen.width(), screen.height());
        return maxSize / 6f;
    }

    private void applyScale() {
        float r = defaultRadius() * scale;
        knobRadius = r * 0.35f;
        // Keep the bounds square, centred on the current knob.
        bounds.set(knobX - r, knobY - r, knobX + r, knobY + r);
        centerX = knobX;
        centerY = knobY;
    }

    /** Place the stick at the given centre coordinates. */
    public void placeAt(float cx, float cy) {
        this.knobX = cx;
        this.knobY = cy;
        applyScale();
    }

    @Override
    public void resize(RectF screen, RectF virtualScreen) {
        // If we have never been positioned, drop the stick in the bottom-left.
        if (this.screen == null || (knobX == 0 && knobY == 0)) {
            float r = (screen.width() / 6f) * scale;
            knobX = r * 1.4f;
            knobY = screen.height() - r * 1.4f;
        }
        this.screen = screen;
        this.virtualScreen = virtualScreen;
        applyScale();
        repaint();
    }

    private void notifyChanged() {
        if (listener != null) listener.layoutChanged(this);
    }

    @Override
    public boolean keyPressed(int keyCode) {
        return false;
    }

    @Override
    public boolean keyRepeated(int keyCode) {
        return false;
    }

    @Override
    public boolean keyReleased(int keyCode) {
        return false;
    }

    @Override
    public boolean pointerPressed(int pointer, float x, float y) {
        if (!visible) return false;
        if (layoutEditMode == LAYOUT_KEYS) {
            if (bounds.contains(x, y)) {
                selected = true;
                dragOffsetX = x - bounds.left;
                dragOffsetY = y - bounds.top;
                repaint();
                return true;
            }
            return false;
        }
        if (layoutEditMode == LAYOUT_SCALES) {
            if (bounds.contains(x, y)) {
                selected = true;
                dragOffsetX = x;
                dragOffsetY = y;
                prevScale = scale;
                scaling = true;
                repaint();
                return true;
            }
            return false;
        }
        // Normal play mode: any touch within the bounding circle starts a drag.
        if (insideStick(x, y)) {
            activePointer = pointer;
            updateDirection(x, y, true);
            repaint();
            return true;
        }
        return false;
    }

    @Override
    public boolean pointerDragged(int pointer, float x, float y) {
        if (!visible) return false;
        if (layoutEditMode == LAYOUT_KEYS) {
            if (selected && pointer == activePointerOrZero()) {
                float nx = x - dragOffsetX + (bounds.width() / 2f);
                float ny = y - dragOffsetY + (bounds.height() / 2f);
                clampToScreen(nx, ny);
                applyScale();
                notifyChanged();
                repaint();
                return true;
            }
            return false;
        }
        if (layoutEditMode == LAYOUT_SCALES) {
            if (selected && scaling) {
                float dx = x - dragOffsetX;
                float dy = dragOffsetY - y;
                float delta = (Math.abs(dx) > Math.abs(dy)) ? dx : dy;
                float next = prevScale + delta / Math.max(screen.width(), screen.height()) * 4f;
                setScale(next);
                repaint();
                return true;
            }
            return false;
        }
        // Play mode.
        if (activePointer == pointer) {
            updateDirection(x, y, true);
            repaint();
            return true;
        }
        return false;
    }

    @Override
    public boolean pointerReleased(int pointer, float x, float y) {
        if (!visible) return false;
        if (layoutEditMode == LAYOUT_KEYS) {
            if (selected && (activePointerOrZero() == pointer)) {
                selected = false;
                repaint();
                return true;
            }
            return false;
        }
        if (layoutEditMode == LAYOUT_SCALES) {
            if (selected && scaling) {
                selected = false;
                scaling = false;
                repaint();
                return true;
            }
            return false;
        }
        if (activePointer == pointer) {
            activePointer = -1;
            releaseActiveDirection();
            repaint();
            return true;
        }
        return false;
    }

    @Override
    public void show() {
        visible = true;
        repaint();
    }

    @Override
    public void hide() {
        // The stick doesn't auto-hide, but we still clear any held direction
        // so the emulator isn't stuck thinking a key is down.
        releaseActiveDirection();
        visible = false;
        repaint();
    }

    @Override
    public void paint(Canvas canvas) {
        if (!visible) return;

        int ringColor = 0x40000080;
        int innerColor = selected ? 0x80000080 : 0x30000080;
        int knobColor = activeDirection >= 0 ? 0xFF000080 : 0x80000080;

        fillPaint.setColor(innerColor);
        canvas.drawCircle(centerX, centerY,
                Math.max(knobRadius * 2.2f, bounds.width() / 2f - 4),
                fillPaint);

        strokePaint.setColor(ringColor);
        strokePaint.setStrokeWidth(3f);
        canvas.drawCircle(centerX, centerY,
                Math.max(knobRadius * 2.2f, bounds.width() / 2f - 4),
                strokePaint);

        knobPaint.setColor(knobColor);
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint);

        if (selected || layoutEditMode != LAYOUT_EOF) {
            textPaint.setColor(0xFFFFFFFF);
            canvas.drawText("JOY", centerX, centerY, textPaint);
        }
    }

    public void setLayoutEditMode(int mode) {
        // Echo the change back through the listener so persistence stays in
        // sync with the keyboard. The listener is responsible for writing.
        if ((layoutEditMode != LAYOUT_EOF) && (mode == LAYOUT_EOF)) {
            notifyChanged();
        }
        layoutEditMode = mode;
        repaint();
    }

    public int getLayoutEditMode() {
        return layoutEditMode;
    }

    public void setView(View view) {
        overlayView = view;
    }

    public void setLayoutListener(LayoutListener listener) {
        this.listener = listener;
    }

    /** LayoutListener contract from VirtualKeyboard, so callers can use a
     *  shared listener type if they want. Provided as an explicit setter too. */
    public void setLayoutChangeListener(LayoutListener listener) {
        this.listener = listener;
    }

    /** Repaint request — safe to call from any thread. */
    public void repaint() {
        if (overlayView != null) {
            overlayView.postInvalidate();
        }
    }

    // ---------------- internal helpers ----------------

    private int activePointerOrZero() {
        return activePointer == -1 ? 0 : activePointer;
    }

    private boolean insideStick(float x, float y) {
        float dx = x - centerX;
        float dy = y - centerY;
        float outer = Math.max(knobRadius * 2.2f, bounds.width() / 2f - 4);
        return dx * dx + dy * dy <= outer * outer;
    }

    private void clampToScreen(float newX, float newY) {
        if (screen == null) return;
        float r = bounds.width() / 2f;
        if (newX - r < screen.left) newX = screen.left + r;
        if (newX + r > screen.right) newX = screen.right - r;
        if (newY - r < screen.top) newY = screen.top + r;
        if (newY + r > screen.bottom) newY = screen.bottom - r;
        // Caller assigns back into knobX/knobY.
        knobX = newX;
        knobY = newY;
    }

    /** Returns the direction index for a touch point relative to centre, or
     *  -1 if the point is inside the dead-zone (small circle around centre). */
    private int directionFor(float x, float y) {
        float dx = x - centerX;
        float dy = y - centerY;
        float dist = (float) Math.hypot(dx, dy);
        float deadZone = knobRadius * 0.4f;
        if (dist < deadZone) return -1;
        // atan2 returns radians in [-PI, PI]; convert to degrees in [0, 360).
        double deg = Math.toDegrees(Math.atan2(dy, dx));
        if (deg < 0) deg += 360.0;
        // Eight sectors of 45° centred on the cardinal directions:
        //   Up        = -22.5 .. 22.5
        //   Up-right  = 22.5 .. 67.5
        //   Right     = 67.5 .. 112.5
        //   ...
        // Add 22.5 so we shift the sector boundaries to land exactly between
        // the cardinal directions.
        double shifted = (deg + 22.5) % 360.0;
        int sector = (int) (shifted / 45.0); // 0..7
        // Sector 0..7 starting at "right" going clockwise. We want our
        // direction enum order (UP, UP_RIGHT, RIGHT, DOWN_RIGHT, DOWN,
        // DOWN_LEFT, LEFT, UP_LEFT). The right direction is at degree 0 →
        // shifted is 22.5 → sector 0. Map sector 0 to RIGHT, then rotate
        // counter-clockwise by one to land on UP.
        // Right(0), DownRight(1), Down(2), DownLeft(3),
        // Left(4), UpLeft(5), Up(6), UpRight(7).
        switch (sector) {
            case 0: return DIR_RIGHT;
            case 1: return DIR_DOWN_RIGHT;
            case 2: return DIR_DOWN;
            case 3: return DIR_DOWN_LEFT;
            case 4: return DIR_LEFT;
            case 5: return DIR_UP_LEFT;
            case 6: return DIR_UP;
            case 7: return DIR_UP_RIGHT;
            default: return -1;
        }
    }

    private void pressDirection(int dir) {
        if (dir < 0 || dir >= directionMap.primary.length) return;
        Emulator.pressKey(directionMap.primary[dir], 0);
        if (directionMap.secondary[dir] != 0) {
            Emulator.pressKey(directionMap.secondary[dir], 0);
        }
    }

    private void releaseDirection(int dir) {
        if (dir < 0 || dir >= directionMap.primary.length) return;
        Emulator.pressKey(directionMap.primary[dir], 1);
        if (directionMap.secondary[dir] != 0) {
            Emulator.pressKey(directionMap.secondary[dir], 1);
        }
    }

    private void releaseActiveDirection() {
        if (activeDirection >= 0) {
            releaseDirection(activeDirection);
            activeDirection = -1;
        }
    }

    private void updateDirection(float x, float y, boolean initial) {
        int dir = directionFor(x, y);
        knobX = x;
        knobY = y;
        // Clamp the knob visually so it stays inside the ring.
        float dx = knobX - centerX;
        float dy = knobY - centerY;
        float dist = (float) Math.hypot(dx, dy);
        float maxR = Math.max(knobRadius * 2.2f, bounds.width() / 2f - 4) - knobRadius;
        if (dist > maxR) {
            float k = maxR / dist;
            knobX = centerX + dx * k;
            knobY = centerY + dy * k;
        }
        if (dir != activeDirection) {
            releaseActiveDirection();
            activeDirection = dir;
            if (dir >= 0) pressDirection(dir);
        }
    }

    // ---------------- persistence ----------------

    private static final int STICK_SIGNATURE = 0x4A4F5900; // "JOY\0"
    private static final int STICK_VERSION = 1;

    /** Layout block id used by VirtualKeyboard's outer writeLayout. */
    public static final int LAYOUT_JOYSTICK = 3;

    public void writeLayout(DataOutputStream dos) throws IOException {
        dos.writeInt(STICK_SIGNATURE);
        dos.writeInt(STICK_VERSION);
        dos.writeFloat(centerX);
        dos.writeFloat(centerY);
        dos.writeFloat(scale);
        dos.writeInt(directionMap.primary.length);
        for (int v : directionMap.primary) dos.writeInt(v);
        for (int v : directionMap.secondary) dos.writeInt(v);
    }

    /**
     * Write a self-describing block for use inside a multi-overlay layout
     * file. The block starts with {@code (LAYOUT_JOYSTICK, length)} where
     * {@code length} covers everything that follows up to but not including
     * the next block header, so a reader can skip a block it doesn't
     * recognise.
     */
    public void writeJoystickBlock(DataOutputStream dos) throws IOException {
        dos.writeInt(LAYOUT_JOYSTICK);
        // Length placeholder — compute payload size now that we know the
        // size of each field. 4 (signature) + 4 (version) + 4*3 (floats) +
        // 4 (count) + 4*2*8 (8 primary + 8 secondary ints) = 96.
        int payloadLen = 4 + 4 + 12 + 4 + directionMap.primary.length * 4
                + directionMap.secondary.length * 4;
        dos.writeInt(payloadLen);
        writeLayout(dos);
    }

    public void readLayout(DataInputStream dis) throws IOException {
        if (dis.readInt() != STICK_SIGNATURE) {
            throw new IOException("joystick signature not found");
        }
        int version = dis.readInt();
        if (version != STICK_VERSION) {
            // Forward-compatible: skip the rest by reading what we know.
            throw new IOException("incompatible joystick version");
        }
        float cx = dis.readFloat();
        float cy = dis.readFloat();
        float sc = dis.readFloat();
        int count = dis.readInt();
        int[] primary = new int[count];
        int[] secondary = new int[count];
        for (int i = 0; i < count; i++) primary[i] = dis.readInt();
        for (int i = 0; i < count; i++) secondary[i] = dis.readInt();
        directionMap = new DirectionMap(primary, secondary);
        scale = sc;
        placeAt(cx, cy);
    }
}