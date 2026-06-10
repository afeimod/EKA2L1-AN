/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2017-2018 Nikita Shakarun
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.eka2l1.emu.overlay;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;

import com.github.eka2l1.emu.Emulator;
import com.github.eka2l1.emu.Keycode;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


public class VirtualKeyboard implements Overlay, Runnable {

    private static final String TAG = VirtualKeyboard.class.getName();
    private static final String ARROW_LEFT = "\u2190";
    private static final String ARROW_UP = "\u2191";
    private static final String ARROW_RIGHT = "\u2192";
    private static final String ARROW_DOWN = "\u2193";
    private static final String ARROW_UP_LEFT = "\u2196";
    private static final String ARROW_UP_RIGHT = "\u2197";
    private static final String ARROW_DOWN_LEFT = "\u2199";
    private static final String ARROW_DOWN_RIGHT = "\u2198";

    public interface LayoutListener {
        void layoutChanged(VirtualKeyboard vk);
    }

    protected static class VirtualKey {

        private RectF rect;
        private int keyCode, secondKeyCode;
        private final String label;
        private boolean selected;
        private boolean visible;
        boolean opaque = true;
        private int corners = 0;

        /** Back-reference to the enclosing keyboard. Static nested
         *  classes don't have an implicit reference to the outer
         *  instance, so the owner is set explicitly when the key is
         *  created. The key needs access to the shared colour table,
         *  the shape and the paint objects — all of which live on the
         *  outer instance. */
        VirtualKeyboard owner;

        VirtualKey(int keyCode, String label) {
            this.keyCode = keyCode;
            this.label = label;
            this.visible = true;
            rect = new RectF();
        }

        VirtualKey(int keyCode, int secondKeyCode, String label) {
            this(keyCode, label);
            this.secondKeyCode = secondKeyCode;
        }

        int getKeyCode() {
            return keyCode;
        }

        int getSecondKeyCode() {
            return secondKeyCode;
        }

        void setSelected(boolean flag) {
            selected = flag;
        }

        boolean isSelected() {
            return selected;
        }

        public void setVisible(boolean flag) {
            visible = flag;
        }

        public boolean isVisible() {
            return visible;
        }

        public RectF getRect() {
            return rect;
        }

        void resize(float width, float height) {
            rect.right = rect.left + width;
            rect.bottom = rect.top + height;
        }

        public boolean contains(float x, float y) {
            return visible && rect.contains(x, y);
        }

        public void paint(Canvas g) {
            int bgColor;
            int fgColor;
            if (selected) {
                bgColor = owner.colors[BACKGROUND_SELECTED];
                fgColor = owner.colors[FOREGROUND_SELECTED];
            } else {
                bgColor = owner.colors[BACKGROUND];
                fgColor = owner.colors[FOREGROUND];
            }
            int olColor = owner.colors[OUTLINE];
            if (opaque) {
                bgColor |= 0xFF000000;
                fgColor |= 0xFF000000;
                olColor |= 0xFF000000;
            }
            owner.fillPaint.setColor(bgColor);
            owner.textPaint.setColor(fgColor);
            owner.drawPaint.setColor(olColor);

            switch (owner.shape) {
                case ROUND_RECT_SHAPE:
                    g.drawRoundRect(rect, corners, corners, owner.fillPaint);
                    g.drawRoundRect(rect, corners, corners, owner.drawPaint);
                    break;
                case RECT_SHAPE:
                    g.drawRect(rect, owner.fillPaint);
                    g.drawRect(rect, owner.drawPaint);
                    break;
                case OVAL_SHAPE:
                    g.drawArc(rect, 0, 360, false, owner.fillPaint);
                    g.drawArc(rect, 0, 360, false, owner.drawPaint);
                    break;
            }
            g.drawText(label, rect.centerX(), rect.centerY() - owner.textCenterOffset, owner.textPaint);
        }

        public String getLabel() {
            return label;
        }

        @NonNull
        public String toString() {
            return "[" + label + ": " + rect.left + ", " + rect.top + ", " + rect.right + ", " + rect.bottom + "]";
        }

        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + keyCode;
            result = prime * result + secondKeyCode;
            return result;
        }
    }


    /**
     * A {@link VirtualKey} subclass that renders and behaves as a virtual
     * 8-direction joystick. It is owned by the enclosing
     * {@link VirtualKeyboard} just like the regular keys, which means it
     * inherits the entire editing UX for free:
     *
     * <ul>
     *     <li>It can be shown or hidden via
     *         {@link VirtualKeyboard#setKeyVisibility(int, boolean)} — the
     *         "Hide buttons" dialog picks it up automatically.</li>
     *     <li>It can be dragged and scaled exactly like any other key in
     *         edit / scale mode.</li>
     *     <li>It picks up its colours from the same {@code colors[]} table
     *         the keyboard uses, including the per-color alpha values.</li>
     *     <li>It is persisted in the same {@code VirtualKeyboardLayout}
     *         file the keyboard writes to.</li>
     * </ul>
     *
     * <p>Eight-direction dispatch: the angle of the touch point relative
     * to the centre is snapped to one of eight 45&deg; sectors (the
     * threshold between sectors is 22.5&deg;). The result is converted
     * into the key codes defined by {@link DirectionMap} (default:
     * UP/DOWN/LEFT/RIGHT plus a second key for diagonals). On release
     * every direction key that is still held is released.</p>
     *
     * <p>Snap-to-neighbour has been intentionally disabled for joysticks
     * — the stick is positioned freely and only clamped to the screen
     * rectangle.</p>
     */
    protected static class JoystickKey extends VirtualKey {

        /** Direction index — used both internally and on disk, so the
         *  order is fixed. */
        public static final int DIR_UP = 0;
        public static final int DIR_UP_RIGHT = 1;
        public static final int DIR_RIGHT = 2;
        public static final int DIR_DOWN_RIGHT = 3;
        public static final int DIR_DOWN = 4;
        public static final int DIR_DOWN_LEFT = 5;
        public static final int DIR_LEFT = 6;
        public static final int DIR_UP_LEFT = 7;

        public static final int[] DEFAULT_PRIMARY = {
                Keycode.KEY_UP, Keycode.KEY_UP,   Keycode.KEY_RIGHT,
                Keycode.KEY_DOWN, Keycode.KEY_DOWN, Keycode.KEY_LEFT,
                Keycode.KEY_LEFT,  Keycode.KEY_UP
        };
        public static final int[] DEFAULT_SECONDARY = {
                0, Keycode.KEY_RIGHT, 0,
                Keycode.KEY_RIGHT, 0, Keycode.KEY_LEFT,
                0, Keycode.KEY_LEFT
        };

        /** Owns the eight direction key codes. */
        public static class DirectionMap {
            public final int[] primary;
            public final int[] secondary;

            public DirectionMap() {
                this(DEFAULT_PRIMARY, DEFAULT_SECONDARY);
            }

            public DirectionMap(int[] primary, int[] secondary) {
                this.primary = primary.clone();
                this.secondary = secondary.clone();
            }

            public void writeTo(DataOutputStream dos) throws IOException {
                dos.writeInt(primary.length);
                for (int v : primary) dos.writeInt(v);
                for (int v : secondary) dos.writeInt(v);
            }

            public static DirectionMap readFrom(DataInputStream dis) throws IOException {
                int n = dis.readInt();
                int[] p = new int[n];
                int[] s = new int[n];
                for (int i = 0; i < n; i++) p[i] = dis.readInt();
                for (int i = 0; i < n; i++) s[i] = dis.readInt();
                return new DirectionMap(p, s);
            }
        }

        private DirectionMap directionMap = new DirectionMap();

        /** Back-reference to the enclosing keyboard. Static nested
         *  classes don't have an implicit reference to the outer
         *  instance, so the owner is set explicitly when the joystick
         *  is created. */
        private VirtualKeyboard owner;

        public void setOwner(VirtualKeyboard owner) {
            this.owner = owner;
        }

        /** Direction that is currently held down (-1 = none). */
        private int activeDirection = -1;

        /** Knob position relative to the centre, in pixels. */
        private float knobOffsetX;
        private float knobOffsetY;

        /** Reusable paint objects, allocated lazily. */
        private Paint stickFillPaint;
        private Paint stickRingPaint;
        private Paint stickKnobPaint;
        private Paint stickLabelPaint;

        public JoystickKey() {
            super(0, "Joystick");
            setVisible(false); // hidden until the user adds it via the menu
        }

        public void setDirectionMap(DirectionMap map) {
            if (map == null) return;
            this.directionMap = map;
        }

        public DirectionMap getDirectionMap() {
            return directionMap;
        }

        @Override
        public boolean contains(float x, float y) {
            if (!isVisible()) return false;
            // Circular hit-test matching the visual ring.
            float cx = getRect().centerX();
            float cy = getRect().centerY();
            float r = Math.min(getRect().width(), getRect().height()) / 2f;
            float dx = x - cx;
            float dy = y - cy;
            return dx * dx + dy * dy <= r * r;
        }

        @Override
        public void paint(Canvas g) {
            if (!isVisible()) return;
            ensurePaints();

            float cx = getRect().centerX() + knobOffsetX;
            float cy = getRect().centerY() + knobOffsetY;
            float r = Math.min(getRect().width(), getRect().height()) / 2f;

            // Pick colours from the shared palette (filled alpha = 0x40 so
            // the underlying midlet is visible through the ring).
            int bg = owner.colors[BACKGROUND];
            int ring = owner.colors[OUTLINE];
            int knob = owner.colors[isSelected() ? BACKGROUND_SELECTED : BACKGROUND];
            int fg = owner.colors[FOREGROUND];

            stickFillPaint.setColor((bg & 0x00FFFFFF) | 0x40000000);
            stickRingPaint.setColor(ring | 0xFF000000);
            stickKnobPaint.setColor(knob | 0xFF000000);
            stickLabelPaint.setColor(fg | 0xFF000000);

            g.drawCircle(cx, cy, r, stickFillPaint);
            stickRingPaint.setStrokeWidth(3f);
            g.drawCircle(cx, cy, r, stickRingPaint);
            g.drawCircle(cx + knobOffsetX, cy + knobOffsetY, r * 0.35f, stickKnobPaint);

            stickLabelPaint.setTextAlign(Paint.Align.CENTER);
            stickLabelPaint.setTextSize(r * 0.35f);
            g.drawText("JOY", cx, cy + r * 0.12f, stickLabelPaint);
        }

        private void ensurePaints() {
            if (stickFillPaint != null) return;
            stickFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            stickFillPaint.setStyle(Paint.Style.FILL);

            stickRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            stickRingPaint.setStyle(Paint.Style.STROKE);

            stickKnobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            stickKnobPaint.setStyle(Paint.Style.FILL);

            stickLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            stickLabelPaint.setStyle(Paint.Style.FILL);
        }

        /** Compute the direction index for a touch point, or -1 in the
         *  dead-zone. */
        private int directionFor(float x, float y) {
            float cx = getRect().centerX() + knobOffsetX;
            float cy = getRect().centerY() + knobOffsetY;
            float dx = x - cx;
            float dy = y - cy;
            float dist = (float) Math.hypot(dx, dy);
            float r = Math.min(getRect().width(), getRect().height()) / 2f;
            // Smaller dead-zone so even a small finger press on the
            // rim registers. The previous 0.4 was far too aggressive:
            // a fingertip landing on the inner 40% of the stick would
            // get dead-zoned out and never trigger a direction.
            float deadZone = r * 0.15f;
            if (dist < deadZone) return -1;
            double deg = Math.toDegrees(Math.atan2(dy, dx));
            if (deg < 0) deg += 360.0;
            double shifted = (deg + 22.5) % 360.0;
            int sector = (int) (shifted / 45.0);
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

        /** Update knob position while clamping inside the ring. */
        private void updateKnob(float x, float y) {
            float cx = getRect().centerX();
            float cy = getRect().centerY();
            float r = Math.min(getRect().width(), getRect().height()) / 2f;
            knobOffsetX = x - cx;
            knobOffsetY = y - cy;
            float dist = (float) Math.hypot(knobOffsetX, knobOffsetY);
            if (dist > r * 0.65f) {
                float k = (r * 0.65f) / dist;
                knobOffsetX *= k;
                knobOffsetY *= k;
            }
        }

        /** Last time a press event was emitted (SystemClock.uptimeMillis).
         *  Used to auto-repeat the held direction so Symbian games that
         *  only act on each discrete down/up pair still receive repeated
         *  key events while the user holds the stick off-centre. */
        private long lastPressTime = 0L;
        /** Auto-repeat interval for held direction, in ms. */
        private static final long REPEAT_INTERVAL_MS = 220L;

        /** Called by VirtualKeyboard when the user touches inside this
         *  stick. */
        public void onPointerPressed(float x, float y) {
            int dir = directionFor(x, y);
            updateKnob(x, y);
            // Always treat a fresh press as a press-then-release pair so
            // games see a discrete "click" event even if the user keeps
            // the finger down. Symbian input is event-based, not
            // state-based, so without the release a one-shot key
            // press would never trigger a second "step".
            if (activeDirection >= 0) {
                releaseActiveDirection();
            }
            activeDirection = dir;
            lastPressTime = android.os.SystemClock.uptimeMillis();
            if (dir >= 0) {
                pressDirection(dir);
                releaseDirection(dir);
            }
        }

        /** Called by VirtualKeyboard while the finger drags. */
        public void onPointerDragged(float x, float y) {
            int dir = directionFor(x, y);
            updateKnob(x, y);
            if (dir != activeDirection) {
                // The user has moved the stick to a new direction: release
                // the old one and press the new one immediately.
                if (activeDirection >= 0) {
                    releaseActiveDirection();
                }
                activeDirection = dir;
                lastPressTime = android.os.SystemClock.uptimeMillis();
                if (dir >= 0) {
                    pressDirection(dir);
                    releaseDirection(dir);
                }
            } else if (dir >= 0) {
                // Same direction as before: re-emit press/release if the
                // auto-repeat interval has elapsed. This makes the stick
                // behave like a real D-pad — holding it keeps the
                // character walking rather than just one step.
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastPressTime >= REPEAT_INTERVAL_MS) {
                    lastPressTime = now;
                    pressDirection(dir);
                    releaseDirection(dir);
                }
            }
        }

        /** Called by VirtualKeyboard when the finger lifts. */
        public void onPointerReleased() {
            releaseActiveDirection();
            lastPressTime = 0L;
            knobOffsetX = 0;
            knobOffsetY = 0;
        }

        /** Force-release any held direction. Used when the keyboard hides. */
        public void releaseAll() {
            releaseActiveDirection();
            lastPressTime = 0L;
            knobOffsetX = 0;
            knobOffsetY = 0;
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

        /** Per-instance persistent layout. The keyboard writes a single
         *  LAYOUT_JOYSTICKS block for all joysticks; each sub-record is
         *  self-describing. */
        public void writeLayout(DataOutputStream dos) throws IOException {
            dos.writeInt(0x4A4F5901); // "JOY" + version
            dos.writeFloat(getRect().left);
            dos.writeFloat(getRect().top);
            dos.writeFloat(getRect().right);
            dos.writeFloat(getRect().bottom);
            dos.writeBoolean(isVisible());
            directionMap.writeTo(dos);
        }

        public void readLayout(DataInputStream dis) throws IOException {
            int sig = dis.readInt();
            if (sig != 0x4A4F5901) {
                throw new IOException("unexpected joystick signature 0x"
                        + Integer.toHexString(sig));
            }
            float l = dis.readFloat();
            float t = dis.readFloat();
            float r = dis.readFloat();
            float b = dis.readFloat();
            getRect().set(l, t, r, b);
            setVisible(dis.readBoolean());
            directionMap = DirectionMap.readFrom(dis);
        }
    }
    private static final int KEYBOARD_SIZE = 25;


    static final int SCREEN = -1;

    static final int KEY_NUM1 = 0;
    static final int KEY_NUM2 = 1;
    static final int KEY_NUM3 = 2;
    static final int KEY_NUM4 = 3;
    static final int KEY_NUM5 = 4;
    static final int KEY_NUM6 = 5;
    static final int KEY_NUM7 = 6;
    static final int KEY_NUM8 = 7;
    static final int KEY_NUM9 = 8;
    static final int KEY_NUM0 = 9;
    static final int KEY_STAR = 10;
    static final int KEY_POUND = 11;
    static final int KEY_SOFT_LEFT = 12;
    static final int KEY_SOFT_RIGHT = 13;
    static final int KEY_DIAL = 14;
    static final int KEY_CANCEL = 15;
    static final int KEY_UP_LEFT = 16;
    static final int KEY_UP = 17;
    static final int KEY_UP_RIGHT = 18;
    static final int KEY_LEFT = 19;
    static final int KEY_RIGHT = 20;
    static final int KEY_DOWN_LEFT = 21;
    static final int KEY_DOWN = 22;
    static final int KEY_DOWN_RIGHT = 23;
    static final int KEY_FIRE = 24;

    private static final int LAYOUT_SIGNATURE = 0x564B4C00;
    private static final int LAYOUT_OLD_VERSION = 1;
    private static final int LAYOUT_VERSION = 2;

    private static final int NUM_VARIANTS = 4;

    public static final int LAYOUT_EOF = -1;
    public static final int LAYOUT_KEYS = 0;
    public static final int LAYOUT_SCALES = 1;
    public static final int LAYOUT_COLORS = 2;
    /**
     * Block id for the joystick list. The block contains a 4-byte count
     * followed by that many self-describing joystick records.
     */
    public static final int LAYOUT_JOYSTICKS = 3;

    private int delay = -1;
    protected int shape;

    public static final int CUSTOMIZABLE_TYPE = 0;
    public static final int PHONE_DIGITS_TYPE = 1;
    public static final int PHONE_ARROWS_TYPE = 2;

    public static final int OVAL_SHAPE = 0;
    public static final int RECT_SHAPE = 1;
    public static final int ROUND_RECT_SHAPE = 2;

    public static final int BACKGROUND = 0;
    public static final int FOREGROUND = 1;
    public static final int BACKGROUND_SELECTED = 2;
    public static final int FOREGROUND_SELECTED = 3;
    public static final int OUTLINE = 4;

    /** Visible to package — {@link JoystickKey} reads colours from the
     *  same table the keyboard uses. */
    int[] colors = {
            0xD0D0D0,
            0x000080,
            0x000080,
            0xFFFFFF,
            0xFFFFFF
    };

    private static final int SCALE_JOYSTICK = 0;
    private static final int SCALE_SOFT_KEYS = 1;
    private static final int SCALE_DIAL_KEYS = 2;
    private static final int SCALE_DIGITS = 3;
    private static final int SCALE_FIRE_KEY = 4;

    private static final float SCALE_SNAP_RADIUS = 0.05f;

    private float[] keyScales = {
            1,
            1,
            1,
            0.75f,
            1.5f
    };

    private int[][] keyScaleGroups = {
            {
                    KEY_UP_LEFT,
                    KEY_UP,
                    KEY_UP_RIGHT,
                    KEY_LEFT,
                    KEY_RIGHT,
                    KEY_DOWN_LEFT,
                    KEY_DOWN,
                    KEY_DOWN_RIGHT
            },
            {
                    KEY_SOFT_LEFT,
                    KEY_SOFT_RIGHT
            },
            {
                    KEY_DIAL,
                    KEY_CANCEL,
            },
            {
                    KEY_NUM1,
                    KEY_NUM2,
                    KEY_NUM3,
                    KEY_NUM4,
                    KEY_NUM5,
                    KEY_NUM6,
                    KEY_NUM7,
                    KEY_NUM8,
                    KEY_NUM9,
                    KEY_NUM0,
                    KEY_STAR,
                    KEY_POUND
            },
            {
                    KEY_FIRE
            }
    };

    private View overlayView;
    private boolean obscuresVirtualScreen;
    private boolean feedback;
    private boolean forceOpacity;
    private static final int FEEDBACK_DURATION = 50;

    private boolean visible, hiding, skip;
    private final Object waiter = new Object();
    private Thread hider;

    private int[] snapOrigins;
    private int[] snapModes;
    private PointF[] snapOffsets;
    protected boolean[] snapValid;
    private int[] snapStack;

    private int layoutEditMode;
    private int editedIndex;
    private float offsetX, offsetY;
    private float prevScale;

    protected RectF screen;
    protected RectF virtualScreen;
    private float keySize;
    private float snapRadius;

    protected VirtualKey[] keypad;
    private VirtualKey[] associatedKeys;

    /**
     * Tracks which joystick (if any) is currently being driven by a given
     * pointer in play mode. Indexed by pointer id; null means no active
     * joystick for that finger.
     */
    private JoystickKey[] joystickAssoc;

    /** Tracks the joystick being dragged/scaled in edit/scale mode. */
    private int editedJoystickIndex = -1;
    private float joyDragOffsetX;
    private float joyDragOffsetY;
    private float joyPrevScale;

    /**
     * Joysticks are stored separately from the fixed keypad array so the
     * user can add and remove them at runtime. Each one is a full
     * {@link JoystickKey} (a {@link VirtualKey} subclass) and behaves
     * exactly like a regular key for editing, scaling, hiding, deleting
     * and persistence.
     */
    protected final List<JoystickKey> joystickKeys = new ArrayList<>();

    protected LayoutListener listener;

    private Paint drawPaint = new Paint();
    private Paint fillPaint = new Paint();
    private Paint textPaint = new Paint();
    private float textCenterOffset;

    public VirtualKeyboard(Context context) {
        this(context, 1);
    }

    public VirtualKeyboard(Context context, int variant) {
        keypad = new VirtualKey[KEYBOARD_SIZE];
        associatedKeys = new VirtualKey[10]; // the average user usually has no more than 10 fingers...
        joystickAssoc = new JoystickKey[10]; // same limit, one stick per finger

        for (int i = KEY_NUM1; i < 9; i++) {
            keypad[i] = mkKey(Keycode.KEY_NUM1 + i, Integer.toString(1 + i));
        }

        keypad[KEY_NUM0] = mkKey(Keycode.KEY_NUM0, "0");
        keypad[KEY_STAR] = mkKey(Keycode.KEY_STAR, "*");
        keypad[KEY_POUND] = mkKey(Keycode.KEY_POUND, "#");

        keypad[KEY_SOFT_LEFT] = mkKey(Keycode.KEY_SOFT_LEFT, "L");
        keypad[KEY_SOFT_RIGHT] = mkKey(Keycode.KEY_SOFT_RIGHT, "R");

        keypad[KEY_DIAL] = mkKey(Keycode.KEY_SEND, "D");
        keypad[KEY_CANCEL] = mkKey(Keycode.KEY_CLEAR, "C");

        keypad[KEY_UP_LEFT] = mkKey(Keycode.KEY_UP, Keycode.KEY_LEFT, ARROW_UP_LEFT);
        keypad[KEY_UP] = mkKey(Keycode.KEY_UP, ARROW_UP);
        keypad[KEY_UP_RIGHT] = mkKey(Keycode.KEY_UP, Keycode.KEY_RIGHT, ARROW_UP_RIGHT);

        keypad[KEY_LEFT] = mkKey(Keycode.KEY_LEFT, ARROW_LEFT);
        keypad[KEY_RIGHT] = mkKey(Keycode.KEY_RIGHT, ARROW_RIGHT);

        keypad[KEY_DOWN_LEFT] = mkKey(Keycode.KEY_DOWN, Keycode.KEY_LEFT, ARROW_DOWN_LEFT);
        keypad[KEY_DOWN] = mkKey(Keycode.KEY_DOWN, ARROW_DOWN);
        keypad[KEY_DOWN_RIGHT] = mkKey(Keycode.KEY_DOWN, Keycode.KEY_RIGHT, ARROW_DOWN_RIGHT);

        keypad[KEY_FIRE] = mkKey(Keycode.KEY_FIRE, "F");

        snapOrigins = new int[keypad.length];
        snapModes = new int[keypad.length];
        snapOffsets = new PointF[keypad.length];
        snapValid = new boolean[keypad.length];
        snapStack = new int[keypad.length];

        drawPaint.setStyle(Paint.Style.STROKE);
        fillPaint.setStyle(Paint.Style.FILL);
        Resources res = context.getResources();
        Typeface typeface = Typeface.createFromAsset(res.getAssets(), "Roboto-Regular.ttf");
        textPaint.setTypeface(typeface);
        float size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22, res.getDisplayMetrics());
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(size);
        float ascent = textPaint.ascent();
        float descent = textPaint.descent();
        textCenterOffset = (descent + ascent) / 2;

        resetLayout(variant);
        layoutEditMode = LAYOUT_EOF;
        visible = true;
        hider = new Thread(this, "MIDletVirtualKeyboard");
        hider.start();
    }

    protected void resetLayout(int variant) {
        switch (variant) {
            case 0:
                keyScales[SCALE_JOYSTICK] = 1;
                keyScales[SCALE_SOFT_KEYS] = 1;
                keyScales[SCALE_DIAL_KEYS] = 1;
                keyScales[SCALE_DIGITS] = 1;
                keyScales[SCALE_FIRE_KEY] = 1;

                setSnap(KEY_DOWN_RIGHT, SCREEN, RectSnap.INT_SOUTHEAST);
                setSnap(KEY_DOWN, KEY_DOWN_RIGHT, RectSnap.EXT_WEST);
                setSnap(KEY_DOWN_LEFT, KEY_DOWN, RectSnap.EXT_WEST);
                setSnap(KEY_LEFT, KEY_DOWN_LEFT, RectSnap.EXT_NORTH);
                setSnap(KEY_RIGHT, KEY_DOWN_RIGHT, RectSnap.EXT_NORTH);
                setSnap(KEY_UP_RIGHT, KEY_RIGHT, RectSnap.EXT_NORTH);
                setSnap(KEY_UP, KEY_UP_RIGHT, RectSnap.EXT_WEST);
                setSnap(KEY_UP_LEFT, KEY_UP, RectSnap.EXT_WEST);
                setSnap(KEY_FIRE, KEY_DOWN_RIGHT, RectSnap.EXT_NORTHWEST);
                setSnap(KEY_SOFT_LEFT, KEY_UP_LEFT, RectSnap.EXT_NORTH);
                setSnap(KEY_SOFT_RIGHT, KEY_UP_RIGHT, RectSnap.EXT_NORTH);

                setSnap(KEY_STAR, SCREEN, RectSnap.INT_SOUTHWEST);
                setSnap(KEY_NUM0, KEY_STAR, RectSnap.EXT_EAST);
                setSnap(KEY_POUND, KEY_NUM0, RectSnap.EXT_EAST);
                setSnap(KEY_NUM7, KEY_STAR, RectSnap.EXT_NORTH);
                setSnap(KEY_NUM8, KEY_NUM7, RectSnap.EXT_EAST);
                setSnap(KEY_NUM9, KEY_NUM8, RectSnap.EXT_EAST);
                setSnap(KEY_NUM4, KEY_NUM7, RectSnap.EXT_NORTH);
                setSnap(KEY_NUM5, KEY_NUM4, RectSnap.EXT_EAST);
                setSnap(KEY_NUM6, KEY_NUM5, RectSnap.EXT_EAST);
                setSnap(KEY_NUM1, KEY_NUM4, RectSnap.EXT_NORTH);
                setSnap(KEY_NUM2, KEY_NUM1, RectSnap.EXT_EAST);
                setSnap(KEY_NUM3, KEY_NUM2, RectSnap.EXT_EAST);
                setSnap(KEY_DIAL, KEY_NUM1, RectSnap.EXT_NORTH);
                setSnap(KEY_CANCEL, KEY_NUM3, RectSnap.EXT_NORTH);

                for (int i = KEY_NUM1; i < KEYBOARD_SIZE; i++) {
                    keypad[i].setVisible(true);
                }
                keypad[KEY_DIAL].setVisible(false);
                keypad[KEY_CANCEL].setVisible(false);
                break;
            case 1:
                keyScales[SCALE_JOYSTICK] = 1;
                keyScales[SCALE_SOFT_KEYS] = 1;
                keyScales[SCALE_DIAL_KEYS] = 1;
                keyScales[SCALE_DIGITS] = 1;
                keyScales[SCALE_FIRE_KEY] = 1;

                setSnap(KEY_DOWN_LEFT, SCREEN, RectSnap.INT_SOUTHWEST);
                setSnap(KEY_DOWN, KEY_DOWN_LEFT, RectSnap.EXT_EAST);
                setSnap(KEY_DOWN_RIGHT, KEY_DOWN, RectSnap.EXT_EAST);
                setSnap(KEY_LEFT, KEY_DOWN_LEFT, RectSnap.EXT_NORTH);
                setSnap(KEY_RIGHT, KEY_DOWN_RIGHT, RectSnap.EXT_NORTH);
                setSnap(KEY_UP_RIGHT, KEY_RIGHT, RectSnap.EXT_NORTH);
                setSnap(KEY_UP, KEY_UP_RIGHT, RectSnap.EXT_WEST);
                setSnap(KEY_UP_LEFT, KEY_UP, RectSnap.EXT_WEST);
                setSnap(KEY_FIRE, KEY_DOWN_RIGHT, RectSnap.EXT_NORTHWEST);
                setSnap(KEY_SOFT_LEFT, KEY_UP_LEFT, RectSnap.EXT_NORTH);
                setSnap(KEY_SOFT_RIGHT, KEY_UP_RIGHT, RectSnap.EXT_NORTH);

                setSnap(KEY_POUND, SCREEN, RectSnap.INT_SOUTHEAST);
                setSnap(KEY_NUM0, KEY_POUND, RectSnap.EXT_WEST);
                setSnap(KEY_STAR, KEY_NUM0, RectSnap.EXT_WEST);
                setSnap(KEY_NUM7, KEY_STAR, RectSnap.EXT_NORTH);
                setSnap(KEY_NUM8, KEY_NUM7, RectSnap.EXT_EAST);
                setSnap(KEY_NUM9, KEY_NUM8, RectSnap.EXT_EAST);
                setSnap(KEY_NUM4, KEY_NUM7, RectSnap.EXT_NORTH);
                setSnap(KEY_NUM5, KEY_NUM4, RectSnap.EXT_EAST);
                setSnap(KEY_NUM6, KEY_NUM5, RectSnap.EXT_EAST);
                setSnap(KEY_NUM1, KEY_NUM4, RectSnap.EXT_NORTH);
                setSnap(KEY_NUM2, KEY_NUM1, RectSnap.EXT_EAST);
                setSnap(KEY_NUM3, KEY_NUM2, RectSnap.EXT_EAST);
                setSnap(KEY_DIAL, KEY_NUM1, RectSnap.EXT_NORTH);
                setSnap(KEY_CANCEL, KEY_NUM3, RectSnap.EXT_NORTH);

                for (int i = KEY_NUM1; i < KEYBOARD_SIZE; i++) {
                    keypad[i].setVisible(true);
                }
                keypad[KEY_DIAL].setVisible(false);
                keypad[KEY_CANCEL].setVisible(false);
                break;
            case 2:
                keyScales[SCALE_JOYSTICK] = 1;
                keyScales[SCALE_SOFT_KEYS] = 1;
                keyScales[SCALE_DIAL_KEYS] = 1;
                keyScales[SCALE_DIGITS] = 1;
                keyScales[SCALE_FIRE_KEY] = 1;

                setSnap(KEY_DOWN, SCREEN, RectSnap.INT_SOUTH);
                setSnap(KEY_DOWN_RIGHT, KEY_DOWN, RectSnap.EXT_EAST);
                setSnap(KEY_DOWN_LEFT, KEY_DOWN, RectSnap.EXT_WEST);
                setSnap(KEY_LEFT, KEY_DOWN_LEFT, RectSnap.EXT_NORTH);
                setSnap(KEY_RIGHT, KEY_DOWN_RIGHT, RectSnap.EXT_NORTH);
                setSnap(KEY_UP_RIGHT, KEY_RIGHT, RectSnap.EXT_NORTH);
                setSnap(KEY_UP, KEY_UP_RIGHT, RectSnap.EXT_WEST);
                setSnap(KEY_UP_LEFT, KEY_UP, RectSnap.EXT_WEST);
                setSnap(KEY_FIRE, KEY_DOWN_RIGHT, RectSnap.EXT_NORTHWEST);
                setSnap(KEY_SOFT_LEFT, KEY_UP_LEFT, RectSnap.EXT_WEST);
                setSnap(KEY_SOFT_RIGHT, KEY_UP_RIGHT, RectSnap.EXT_EAST);

                for (int i = KEY_NUM1; i < KEY_SOFT_LEFT; i++) {
                    keypad[i].setVisible(false);
                }
                for (int i = KEY_SOFT_LEFT; i < KEYBOARD_SIZE; i++) {
                    keypad[i].setVisible(true);
                }
                keypad[KEY_DIAL].setVisible(false);
                keypad[KEY_CANCEL].setVisible(false);
                break;
            case 3:
                keyScales[SCALE_JOYSTICK] = 1;
                keyScales[SCALE_SOFT_KEYS] = 1;
                keyScales[SCALE_DIAL_KEYS] = 1;
                keyScales[SCALE_DIGITS] = 1;
                keyScales[SCALE_FIRE_KEY] = 1;

                setSnap(KEY_SOFT_LEFT, KEY_NUM1, RectSnap.EXT_WEST);
                setSnap(KEY_SOFT_RIGHT, KEY_NUM3, RectSnap.EXT_EAST);

                setSnap(KEY_STAR, KEY_NUM0, RectSnap.EXT_WEST);
                setSnap(KEY_NUM0, SCREEN, RectSnap.INT_SOUTH);
                setSnap(KEY_POUND, KEY_NUM0, RectSnap.EXT_EAST);
                setSnap(KEY_NUM7, KEY_STAR, RectSnap.EXT_NORTH);
                setSnap(KEY_NUM8, KEY_NUM7, RectSnap.EXT_EAST);
                setSnap(KEY_NUM9, KEY_NUM8, RectSnap.EXT_EAST);
                setSnap(KEY_NUM4, KEY_NUM7, RectSnap.EXT_NORTH);
                setSnap(KEY_NUM5, KEY_NUM4, RectSnap.EXT_EAST);
                setSnap(KEY_NUM6, KEY_NUM5, RectSnap.EXT_EAST);
                setSnap(KEY_NUM1, KEY_NUM4, RectSnap.EXT_NORTH);
                setSnap(KEY_NUM2, KEY_NUM1, RectSnap.EXT_EAST);
                setSnap(KEY_NUM3, KEY_NUM2, RectSnap.EXT_EAST);

                for (int i = KEY_NUM1; i < KEY_DIAL; i++) {
                    keypad[i].setVisible(true);
                }
                for (int i = KEY_DIAL; i < KEYBOARD_SIZE; i++) {
                    keypad[i].setVisible(false);
                }
                break;
        }
    }

    protected int getLayoutNum() {
        return NUM_VARIANTS;
    }

    public String[] getLayoutNames() {
        int num = getLayoutNum();
        String[] names = new String[num];
        for (int i = 0; i < num; i++) {
            names[i] = String.valueOf(i + 1);
        }
        return names;
    }

    public void setLayout(int layoutVariant) {
        resetLayout(layoutVariant);
        for (int group = 0; group < keyScaleGroups.length; group++) {
            resizeKeyGroup(group);
        }
        snapKeys();
        repaint();
        listener.layoutChanged(this);
    }

    public void writeLayout(DataOutputStream dos) throws IOException {
        dos.writeInt(LAYOUT_SIGNATURE);
        dos.writeInt(LAYOUT_VERSION);
        dos.writeInt(LAYOUT_KEYS);
        dos.writeInt(keypad.length * 20 + 4);
        dos.writeInt(keypad.length);
        for (int i = 0; i < keypad.length; i++) {
            dos.writeInt(keypad[i].hashCode());
            dos.writeBoolean(keypad[i].isVisible());
            dos.writeInt(snapOrigins[i]);
            dos.writeInt(snapModes[i]);
            dos.writeFloat(snapOffsets[i].x);
            dos.writeFloat(snapOffsets[i].y);
        }
        dos.writeInt(LAYOUT_SCALES);
        dos.writeInt(keyScales.length * 4 + 4);
        dos.writeInt(keyScales.length);
        for (float keyScale : keyScales) {
            dos.writeFloat(keyScale);
        }
        dos.writeInt(LAYOUT_COLORS);
        dos.writeInt(colors.length * 4 + 4);
        dos.writeInt(colors.length);
        for (int color : colors) {
            dos.writeInt(color);
        }
        // Joystick block: a 4-byte count followed by that many records.
        // Each record is self-describing (signature inside JoystickKey.writeLayout)
        // so future format additions stay backwards compatible. We skip
        // emitting the block entirely when there are no joysticks, which
        // keeps the file format identical to legacy saves for users that
        // never enabled the feature.
        if (!joystickKeys.isEmpty()) {
            dos.writeInt(LAYOUT_JOYSTICKS);
            // The reader uses the count + each record's inner signature
            // to delimit, so we don't need a block length header here.
            dos.writeInt(0); // reserved length placeholder, ignored on read
            dos.writeInt(joystickKeys.size());
            for (JoystickKey j : joystickKeys) {
                j.writeLayout(dos);
            }
        }
        dos.writeInt(LAYOUT_EOF);
        dos.writeInt(0);
    }

    // Reflection helpers removed — the joystick block doesn't need them
    // because the inner record signatures already delimit the records.

    public void readLayout(DataInputStream dis) throws IOException {
        if (dis.readInt() != LAYOUT_SIGNATURE) {
            throw new IOException("file signature not found");
        }
        int version = dis.readInt();
        if (version != LAYOUT_VERSION && version != LAYOUT_OLD_VERSION) {
            throw new IOException("incompatible file version");
        }
        while (true) {
            int block = dis.readInt();
            int length = dis.readInt();
            if (block == LAYOUT_EOF) {
                break;
            }
            int count;
            switch (block) {
                case LAYOUT_KEYS:
                    count = dis.readInt();
                    int hash;
                    boolean found;
                    for (int i = 0; i < count; i++) {
                        hash = dis.readInt();
                        found = false;
                        for (int key = 0; key < keypad.length; key++) {
                            if (keypad[key].hashCode() == hash) {
                                if (version == LAYOUT_VERSION) {
                                    keypad[key].setVisible(dis.readBoolean());
                                }
                                snapOrigins[key] = dis.readInt();
                                snapModes[key] = dis.readInt();
                                snapOffsets[key].x = dis.readFloat();
                                snapOffsets[key].y = dis.readFloat();
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            dis.skip(16);
                        }
                    }
                    break;
                case LAYOUT_SCALES:
                    count = dis.readInt();
                    if (count == keyScales.length) {
                        for (int i = 0; i < count; i++) {
                            keyScales[i] = dis.readFloat();
                        }
                    } else {
                        dis.skip(count * 4);
                    }
                    break;
                case LAYOUT_COLORS:
                    count = dis.readInt();
                    if (count == colors.length) {
                        for (int i = 0; i < count; i++) {
                            colors[i] = dis.readInt();
                        }
                    } else {
                        dis.skip(count * 4);
                    }
                    break;
                case LAYOUT_JOYSTICKS:
                    // Block format: 4-byte length (ignored), 4-byte count,
                    // then that many joystick records. Each record
                    // self-delimits via its 4-byte signature, so a malformed
                    // length header can't break us.
                    dis.readInt(); // reserved length
                    int joyCount = dis.readInt();
                    joystickKeys.clear();
                    for (int i = 0; i < joyCount; i++) {
                        JoystickKey j = new JoystickKey();
                        j.setOwner(this);
                        try {
                            j.readLayout(dis);
                        } catch (IOException ioe) {
                            // One bad record shouldn't take down the whole
                            // layout — just stop reading.
                            break;
                        }
                        joystickKeys.add(j);
                    }
                    break;
                default:
                    dis.skip(length);
                    break;
            }
        }
    }

    public String[] getKeyNames() {
        // Regular keys first (unchanged order), then one entry per joystick
        // so the "Hide buttons" dialog sees them all.
        int total = KEYBOARD_SIZE + joystickKeys.size();
        String[] names = new String[total];
        for (int i = 0; i < KEYBOARD_SIZE; i++) {
            names[i] = keypad[i].getLabel();
        }
        for (int i = 0; i < joystickKeys.size(); i++) {
            names[KEYBOARD_SIZE + i] = "Joystick " + (i + 1);
        }
        return names;
    }

    public boolean[] getKeyVisibility() {
        int total = KEYBOARD_SIZE + joystickKeys.size();
        boolean[] states = new boolean[total];
        for (int i = 0; i < KEYBOARD_SIZE; i++) {
            states[i] = !keypad[i].isVisible();
        }
        for (int i = 0; i < joystickKeys.size(); i++) {
            states[KEYBOARD_SIZE + i] = !joystickKeys.get(i).isVisible();
        }
        return states;
    }

    public void setKeyVisibility(int id, boolean hidden) {
        if (id >= 0 && id < KEYBOARD_SIZE) {
            keypad[id].setVisible(!hidden);
        } else if (id >= KEYBOARD_SIZE && id < KEYBOARD_SIZE + joystickKeys.size()) {
            joystickKeys.get(id - KEYBOARD_SIZE).setVisible(!hidden);
        } else {
            return;
        }
        snapKeys();
        repaint();
        if (listener != null) listener.layoutChanged(this);
    }

    /**
     * Create a fresh joystick centred at {@code (cx, cy)} with the given
     * diameter (pixels). The new stick is added to the end of the editor
     * list, made visible and persisted via the layout listener.
     *
     * @return the index of the new joystick in the {@link #joystickKeys}
     *         list, or -1 if creation failed.
     */
    public int addJoystick(float cx, float cy, float diameter) {
        if (diameter <= 0) return -1;
        JoystickKey j = new JoystickKey();
        j.setOwner(this);
        j.getRect().set(cx - diameter / 2f, cy - diameter / 2f,
                        cx + diameter / 2f, cy + diameter / 2f);
        j.setVisible(true);
        joystickKeys.add(j);
        snapKeys();
        repaint();
        if (listener != null) listener.layoutChanged(this);
        return joystickKeys.size() - 1;
    }

    /** Remove a joystick added via {@link #addJoystick}. */
    public void removeJoystick(int index) {
        if (index < 0 || index >= joystickKeys.size()) return;
        JoystickKey j = joystickKeys.remove(index);
        j.releaseAll();
        snapKeys();
        repaint();
        if (listener != null) listener.layoutChanged(this);
    }

    public int getJoystickCount() {
        return joystickKeys.size();
    }

    public JoystickKey getJoystick(int index) {
        if (index < 0 || index >= joystickKeys.size()) return null;
        return joystickKeys.get(index);
    }

    /**
     * Lookup hook so a {@link JoystickKey} can ask its owning keyboard
     * "am I currently opaque?". {@link #snapKeys()} sets this flag on
     * every regular key. Joysticks get the flag mirrored to their own
     * {@code opaque} field.
     */
    public boolean isKeyOpaque(VirtualKey key) {
        if (key == null) return false;
        return key.opaque;
    }

    public void setLayoutListener(LayoutListener listener) {
        this.listener = listener;
    }

    protected void setSnap(int key, int origin, int mode) {
        snapOrigins[key] = origin;
        snapModes[key] = mode;
        snapOffsets[key] = new PointF();
        snapValid[key] = false;
    }

    /**
     * Construct a regular key and wire it up to the owning keyboard in
     * one step. {@link VirtualKey} is a static nested class so it has no
     * implicit reference to the enclosing {@link VirtualKeyboard}; this
     * helper injects the owner so paint() can reach the shared colour
     * table, shape and paint objects.
     */
    private VirtualKey mkKey(int keyCode, String label) {
        VirtualKey k = new VirtualKey(keyCode, label);
        k.owner = this;
        return k;
    }

    private VirtualKey mkKey(int keyCode, int secondKeyCode, String label) {
        VirtualKey k = new VirtualKey(keyCode, secondKeyCode, label);
        k.owner = this;
        return k;
    }

    private boolean findSnap(int target, int origin) {
        snapModes[target] = RectSnap.getSnap(keypad[target].getRect(), keypad[origin].getRect(), snapRadius, RectSnap.COARSE_MASK, true);
        if (snapModes[target] != RectSnap.NO_SNAP) {
            snapOrigins[target] = origin;
            snapOffsets[target].set(0, 0);
            for (VirtualKey aKeypad : keypad) {
                origin = snapOrigins[origin];
                if (origin == SCREEN) {
                    return true;
                }
            }
        }
        return false;
    }

    private void snapKey(int key, int level) {
        if (level >= snapStack.length) {
            Log.d(TAG, "Snap loop detected: ");
            for (int i = 1; i < snapStack.length; i++) {
                System.out.print(snapStack[i]);
                System.out.print(", ");
            }
            Log.d(TAG, String.valueOf(key));
            return;
        }
        snapStack[level] = key;
        if (snapOrigins[key] == SCREEN) {
            RectSnap.snap(keypad[key].getRect(), screen, snapModes[key], snapOffsets[key]);
            snapValid[key] = true;
        } else {
            if (!snapValid[snapOrigins[key]]) {
                snapKey(snapOrigins[key], level + 1);
            }
            RectSnap.snap(keypad[key].getRect(), keypad[snapOrigins[key]].getRect(), snapModes[key], snapOffsets[key]);
            snapValid[key] = true;
        }
    }

    protected void snapKeys() {
        obscuresVirtualScreen = false;
        for (int i = 0; i < keypad.length; i++) {
            snapKey(i, 0);
            VirtualKey key = keypad[i];
            if (key.isVisible() && RectF.intersects(key.getRect(), virtualScreen)) {
                obscuresVirtualScreen = true;
                key.opaque = false;
            } else {
                key.opaque = true;
            }
            key.corners = (int) (Math.min(key.getRect().width(), key.getRect().height()) * 0.25F);
        }
        for (VirtualKey key : keypad) {
            key.opaque &= !obscuresVirtualScreen || forceOpacity;
        }
        // Joysticks live outside the snap graph entirely — they only need
        // their opaque flag refreshed so they pick up the per-color alpha.
        for (JoystickKey j : joystickKeys) {
            j.opaque = !(j.isVisible() && RectF.intersects(j.getRect(), virtualScreen))
                    || forceOpacity;
        }
    }

    /** Clamp a rect to the visible screen so a key never escapes the
     *  surface while the user is dragging it. Used for both regular keys
     *  and joysticks in edit mode. */
    private void clampToScreen(RectF rect) {
        if (screen == null) return;
        if (rect.left < screen.left) {
            rect.offset(screen.left - rect.left, 0);
        }
        if (rect.top < screen.top) {
            rect.offset(0, screen.top - rect.top);
        }
        if (rect.right > screen.right) {
            rect.offset(screen.right - rect.right, 0);
        }
        if (rect.bottom > screen.bottom) {
            rect.offset(0, screen.bottom - rect.bottom);
        }
    }

    /**
     * Mark every key that depends on {@code anchor} (directly or transitively)
     * as needing re-resolution. Used after a manual drag so the snap chain
     * picks up the new position on the next {@link #snapKeys()}.
     */
    private void invalidateSnapDependents(int anchor) {
        for (int i = 0; i < snapOrigins.length; i++) {
            if (i == anchor) continue;
            int origin = snapOrigins[i];
            int safety = snapOrigins.length;
            while (origin != SCREEN && origin != anchor && safety-- > 0) {
                int next = snapOrigins[origin];
                if (next == origin) break;
                origin = next;
            }
            if (origin == anchor) {
                snapValid[i] = false;
            }
        }
        snapValid[anchor] = false;
    }

    private void highlightGroup(int group) {
        for (VirtualKey aKeypad : keypad) {
            aKeypad.setSelected(false);
        }
        if (group >= 0) {
            for (int key = 0; key < keyScaleGroups[group].length; key++) {
                keypad[keyScaleGroups[group][key]].setSelected(true);
            }
        }
    }

    public void setLayoutEditMode(int mode) {
        if ((layoutEditMode != LAYOUT_EOF) && (mode == LAYOUT_EOF) && listener != null) {
            listener.layoutChanged(this);
        }
        layoutEditMode = mode;
        editedIndex = -1;
        editedJoystickIndex = -1;
        switch (mode) {
            case LAYOUT_SCALES:
                editedIndex = 0;
                highlightGroup(0);
                break;
            default:
                highlightGroup(-1);
                break;
        }
        show();
    }

    private void resizeKey(int key, float size) {
        keypad[key].resize(size, size);
        snapValid[key] = false;
    }

    private void resizeKeyGroup(int group) {
        float size = keySize * keyScales[group];
        for (int key = 0; key < keyScaleGroups[group].length; key++) {
            resizeKey(keyScaleGroups[group][key], size);
        }
    }

    @Override
    public void resize(RectF screen, RectF virtualScreen) {
        this.screen = screen;
        this.virtualScreen = virtualScreen;
        float width = screen.width();
        float height = screen.height();
        boolean landscape = width > height;
        float maxSize = Math.max(screen.width(), screen.height());
        float minSize = Math.min(screen.width(), screen.height());
        boolean nonWide = maxSize / minSize < 2;
        snapRadius = keyScales[0];
        for (int i = 1; i < keyScales.length; i++) {
            if (keyScales[i] < snapRadius) {
                snapRadius = keyScales[i];
            }
        }
        if (nonWide || landscape) {
            keySize = maxSize / 12F;
        } else {
            keySize = minSize / 6.5F;
        }
        snapRadius = keySize * snapRadius / 4;
        for (int group = 0; group < keyScaleGroups.length; group++) {
            resizeKeyGroup(group);
        }
        snapKeys();
        repaint();
    }

    public void paint(Canvas canvas) {
        if (visible) {
            for (VirtualKey key : keypad) {
                if (key.visible) {
                    key.paint(canvas);
                }
            }
            for (JoystickKey j : joystickKeys) {
                if (j.isVisible()) {
                    j.paint(canvas);
                }
            }
        }
    }

    protected void repaint() {
        overlayView.postInvalidate();
    }

    /**
     * Check if we have processed the pointer touch.
     * <p>
     * The pointer touch is not processed if it is on the virtual screen:
     * in this case, it will be handled by the midlet.
     * But clicking outside the virtual screen is not transmitted
     * to the midlet for optimization purposes.
     *
     * @param x the touch coordinates
     * @param y the touch coordinates
     * @return true, if the touch point is on the virtual screen
     */
    private boolean checkPointerHandled(float x, float y) {
        return !virtualScreen.contains(x, y);
    }

    @Override
    public boolean pointerPressed(int pointer, float x, float y) {
        if (skip) {
            return checkPointerHandled(x, y);
        }

        switch (layoutEditMode) {
            case LAYOUT_EOF:
                if (pointer > associatedKeys.length) {
                    return checkPointerHandled(x, y);
                }
                // Joysticks get first crack at the event because their
                // circular hit-test is more forgiving than a rectangular
                // key sitting next to them.
                for (JoystickKey j : joystickKeys) {
                    if (j.isVisible() && j.contains(x, y)) {
                        joystickAssoc[pointer] = j;
                        j.setSelected(true);
                        j.onPointerPressed(x, y);
                        repaint();
                        return true;
                    }
                }
                for (VirtualKey aKeypad : keypad) {
                    if (aKeypad.contains(x, y)) {
                        vibrate();
                        associatedKeys[pointer] = aKeypad;
                        aKeypad.setSelected(true);
                        Emulator.pressKey(associatedKeys[pointer].getKeyCode(), 0);
                        if (associatedKeys[pointer].getSecondKeyCode() != 0) {
                            Emulator.pressKey(associatedKeys[pointer].getSecondKeyCode(), 0);
                        }
                        repaint();
                        return true;
                    }
                }
                break;
            case LAYOUT_KEYS:
                editedIndex = -1;
                editedJoystickIndex = -1;
                // Joysticks first.
                for (int i = 0; i < joystickKeys.size(); i++) {
                    JoystickKey j = joystickKeys.get(i);
                    if (j.isVisible() && j.contains(x, y)) {
                        editedJoystickIndex = i;
                        joyDragOffsetX = x - j.getRect().left;
                        joyDragOffsetY = y - j.getRect().top;
                        j.setSelected(true);
                        break;
                    }
                }
                if (editedJoystickIndex < 0) {
                    for (int i = 0; i < keypad.length; i++) {
                        if (keypad[i].contains(x, y)) {
                            editedIndex = i;
                            RectF rect = keypad[i].getRect();
                            offsetX = x - rect.left;
                            offsetY = y - rect.top;
                            break;
                        }
                    }
                }
                break;
            case LAYOUT_SCALES:
                // Joysticks first.
                int joyIndex = -1;
                for (int i = 0; i < joystickKeys.size(); i++) {
                    if (joystickKeys.get(i).isVisible() && joystickKeys.get(i).contains(x, y)) {
                        joyIndex = i;
                        break;
                    }
                }
                if (joyIndex >= 0) {
                    editedJoystickIndex = joyIndex;
                    JoystickKey j = joystickKeys.get(joyIndex);
                    j.setSelected(true);
                    joyDragOffsetX = x;
                    joyDragOffsetY = y;
                    // Use the joystick's current diameter / default as the
                    // "prevScale" so the gesture feels 1:1 with the keyboard.
                    joyPrevScale = Math.min(j.getRect().width(), j.getRect().height());
                    repaint();
                    break;
                }
                int index = -1;
                for (int group = 0; group < keyScaleGroups.length && index < 0; group++) {
                    for (int key = 0; key < keyScaleGroups[group].length && index < 0; key++) {
                        if (keypad[keyScaleGroups[group][key]].contains(x, y)) {
                            index = group;
                        }
                    }
                }
                if (index >= 0) {
                    editedIndex = index;
                    highlightGroup(index);
                    repaint();
                }
                offsetX = x;
                offsetY = y;
                prevScale = keyScales[editedIndex];
                break;
        }
        return checkPointerHandled(x, y);
    }

    @Override
    public boolean pointerDragged(int pointer, float x, float y) {
        if (skip) {
            return checkPointerHandled(x, y);
        }
        switch (layoutEditMode) {
            case LAYOUT_EOF:
                if (pointer > associatedKeys.length) {
                    return checkPointerHandled(x, y);
                }
                if (joystickAssoc[pointer] != null) {
                    JoystickKey j = joystickAssoc[pointer];
                    if (!j.contains(x, y)) {
                        j.onPointerReleased();
                        j.setSelected(false);
                        joystickAssoc[pointer] = null;
                        repaint();
                        return pointerPressed(pointer, x, y);
                    }
                    j.onPointerDragged(x, y);
                    repaint();
                    return true;
                }
                if (associatedKeys[pointer] == null) {
                    return pointerPressed(pointer, x, y);
                } else if (!associatedKeys[pointer].contains(x, y)) {
                    Emulator.pressKey(associatedKeys[pointer].getKeyCode(), 1);
                    if (associatedKeys[pointer].getSecondKeyCode() != 0) {
                        Emulator.pressKey(associatedKeys[pointer].getSecondKeyCode(), 1);
                    }
                    associatedKeys[pointer].setSelected(false);
                    associatedKeys[pointer] = null;
                    repaint();
                    pointerPressed(pointer, x, y);

                    return true;
                }
                break;
            case LAYOUT_KEYS:
                if (editedJoystickIndex >= 0) {
                    JoystickKey j = joystickKeys.get(editedJoystickIndex);
                    RectF rect = j.getRect();
                    float w = rect.width();
                    float h = rect.height();
                    float newLeft = x - joyDragOffsetX;
                    float newTop = y - joyDragOffsetY;
                    rect.set(newLeft, newTop, newLeft + w, newTop + h);
                    clampToScreen(rect);
                    repaint();
                    break;
                }
                if (editedIndex >= 0) {
                    RectF rect = keypad[editedIndex].getRect();
                    rect.offsetTo(x - offsetX, y - offsetY);
                    // Magnetic snapping to neighbouring keys has been
                    // intentionally disabled — the user is in full control
                    // of where each key sits. We still clamp to the screen
                    // so a key never escapes the surface.
                    snapModes[editedIndex] = RectSnap.NO_SNAP;
                    snapOrigins[editedIndex] = SCREEN;
                    snapOffsets[editedIndex].set(0, 0);
                    snapValid[editedIndex] = false;
                    clampToScreen(rect);
                    invalidateSnapDependents(editedIndex);
                    repaint();
                }
                break;
            case LAYOUT_SCALES:
                if (editedJoystickIndex >= 0) {
                    JoystickKey j = joystickKeys.get(editedJoystickIndex);
                    float dx = x - joyDragOffsetX;
                    float dy = joyDragOffsetY - y;
                    float delta = (Math.abs(dx) > Math.abs(dy)) ? dx : dy;
                    float ref = Math.max(screen != null ? screen.width() : 1080f, 1f);
                    float newSize = joyPrevScale + delta / ref * 4f;
                    newSize = Math.max(48f, Math.min(ref * 0.5f, newSize));
                    RectF rect = j.getRect();
                    float cx = rect.centerX();
                    float cy = rect.centerY();
                    rect.set(cx - newSize / 2f, cy - newSize / 2f,
                             cx + newSize / 2f, cy + newSize / 2f);
                    clampToScreen(rect);
                    repaint();
                    break;
                }
                float keydx = x - offsetX;
                float keydy = offsetY - y;
                float keyDelta;
                if (Math.abs(keydx) > Math.abs(keydy)) {
                    keyDelta = keydx;
                } else {
                    keyDelta = keydy;
                }
                float scale = prevScale + keyDelta / Math.max(screen.width(), screen.height());
                if (Math.abs(1 - scale) <= SCALE_SNAP_RADIUS) {
                    scale = 1;
                } else {
                    for (int i = 0; i < keyScales.length; i++) {
                        if (i != editedIndex && Math.abs(keyScales[i] - scale) <= SCALE_SNAP_RADIUS) {
                            scale = keyScales[i];
                            break;
                        }
                    }
                }
                keyScales[editedIndex] = scale;
                resizeKeyGroup(editedIndex);
                snapKeys();
                repaint();
                break;
        }
        return checkPointerHandled(x, y);
    }

    @Override
    public boolean pointerReleased(int pointer, float x, float y) {
        if (skip) {
            skip = false;
            return checkPointerHandled(x, y);
        }
        if (layoutEditMode == LAYOUT_EOF) {
            if (pointer > associatedKeys.length) {
                return checkPointerHandled(x, y);
            }
            if (joystickAssoc[pointer] != null) {
                joystickAssoc[pointer].onPointerReleased();
                joystickAssoc[pointer].setSelected(false);
                joystickAssoc[pointer] = null;
                repaint();
                return true;
            }
            if (associatedKeys[pointer] != null) {
                Emulator.pressKey(associatedKeys[pointer].getKeyCode(), 1);
                if (associatedKeys[pointer].getSecondKeyCode() != 0) {
                    Emulator.pressKey(associatedKeys[pointer].getSecondKeyCode(), 1);
                }
                associatedKeys[pointer].setSelected(false);
                associatedKeys[pointer] = null;
                repaint();

                return true;
            }
        } else if (layoutEditMode == LAYOUT_KEYS) {
            if (editedJoystickIndex >= 0) {
                joystickKeys.get(editedJoystickIndex).setSelected(false);
                editedJoystickIndex = -1;
                if (listener != null) listener.layoutChanged(this);
                repaint();
                return true;
            }
            editedIndex = -1;
            if (listener != null) listener.layoutChanged(this);
        } else if (layoutEditMode == LAYOUT_SCALES) {
            if (editedJoystickIndex >= 0) {
                joystickKeys.get(editedJoystickIndex).setSelected(false);
                editedJoystickIndex = -1;
                if (listener != null) listener.layoutChanged(this);
                repaint();
                return true;
            }
            if (listener != null) listener.layoutChanged(this);
        }
        return checkPointerHandled(x, y);
    }

    @Override
    public void show() {
        synchronized (waiter) {
            if (hiding) {
                hider.interrupt();
            }
        }
        visible = true;
        repaint();
    }

    @Override
    public void hide() {
        if (delay > 0 && obscuresVirtualScreen) {
            synchronized (waiter) {
                waiter.notifyAll();
            }
        }
        // Make sure no joystick stays "pressed" while the keyboard is
        // hidden — otherwise the game keeps receiving a held direction
        // even though the user lifted their finger.
        for (JoystickKey j : joystickKeys) {
            j.releaseAll();
        }
    }

    @Override
    public void run() {
        try {
            while (true) {
                synchronized (waiter) {
                    hiding = false;
                    waiter.notifyAll();
                    waiter.wait();
                    hiding = true;
                }
                try {
                    if (delay > 0) {
                        Thread.sleep(delay);
                    }
                    visible = false;
                    skip = true;
                    repaint();
                } catch (InterruptedException ie) {
                    ie.printStackTrace();
                }
            }
        } catch (InterruptedException ie) {
            ie.printStackTrace();
        }
    }

    @Override
    public boolean keyPressed(int keyCode) {
        for (VirtualKey aKeypad : keypad) {
            if (aKeypad.getKeyCode() == keyCode && aKeypad.getSecondKeyCode() == 0) {
                aKeypad.setSelected(true);
                repaint();
                break;
            }
        }
        return false;
    }

    @Override
    public boolean keyRepeated(int keyCode) {
        return false;
    }

    @Override
    public boolean keyReleased(int keyCode) {
        for (VirtualKey aKeypad : keypad) {
            if (aKeypad.getKeyCode() == keyCode && aKeypad.getSecondKeyCode() == 0) {
                aKeypad.setSelected(false);
                repaint();
                break;
            }
        }
        return false;
    }

    private void vibrate() {
        if (feedback) Emulator.vibrate(FEEDBACK_DURATION);
    }

    public void setHideDelay(int delay) {
        this.delay = delay;
    }

    public void setColor(int color, int value) {
        colors[color] = value;
    }

    public void setHasHapticFeedback(boolean feedback) {
        this.feedback = feedback;
    }

    public void setButtonShape(int shape) {
        this.shape = shape;
    }

    public void setView(View view) {
        overlayView = view;
    }

}
