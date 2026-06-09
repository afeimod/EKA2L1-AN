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

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.github.eka2l1.R;

import java.io.File;

/**
 * Free-form screen layout editor. The user drags the four corners and/or
 * the whole rectangle to position the emulated screen anywhere on the
 * device window. The chosen rectangle is persisted as four normalized
 * coordinates (0..1) on the active profile's {@link ProfileModel}.
 *
 * <p>This replaces the legacy "screen gravity" spinner. The native side
 * ({@code launcher::draw}) consumes the four floats and skips the gravity
 * calculation when the free-form layout is enabled.</p>
 */
public class ScreenPositionActivity extends Activity {

    public static final String EXTRA_CONFIG_DIR = "config_dir";

    /** Minimum normalized size of the screen rectangle (so it never collapses). */
    private static final float MIN_SIZE = 0.05f;

    private FrameLayout canvas;
    private TextView hint;
    private File configDir;
    private ProfileModel params;

    /** Normalized (0..1) coordinates of the screen rect, top-left + bottom-right. */
    private float x1, y1, x2, y2;

    /** Edge / corner currently being dragged. {@code -1} means body / nothing. */
    private int activeHandle = -1;
    private static final int HANDLE_NONE = -1;
    private static final int HANDLE_BODY = 0;
    private static final int HANDLE_TL = 1;
    private static final int HANDLE_TR = 2;
    private static final int HANDLE_BL = 3;
    private static final int HANDLE_BR = 4;

    /** Pixel radius of the corner hit-target. */
    private static final int HANDLE_RADIUS_PX = 80;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_screen_position);

        String path = getIntent().getStringExtra(EXTRA_CONFIG_DIR);
        if (path == null) {
            finish();
            return;
        }
        configDir = new File(path);
        params = ProfilesManager.loadConfig(configDir);
        if (params == null) {
            finish();
            return;
        }

        // Make sure all four corners are valid before we start editing.
        x1 = clamp01(params.screenCustomX1);
        y1 = clamp01(params.screenCustomY1);
        x2 = clamp01(params.screenCustomX2);
        y2 = clamp01(params.screenCustomY2);
        if (x2 - x1 < MIN_SIZE) x2 = clamp01(x1 + MIN_SIZE);
        if (y2 - y1 < MIN_SIZE) y2 = clamp01(y1 + MIN_SIZE);
        // Sensible default if the profile was just initialised.
        if (x1 == 0f && y1 == 0f && x2 == 1f && y2 == 1f) {
            // Slight inset so the user sees four distinct handles.
            x1 = 0.15f; y1 = 0.15f; x2 = 0.85f; y2 = 0.85f;
        }

        canvas = findViewById(R.id.spCanvas);
        hint = findViewById(R.id.spHint);
        Button done = findViewById(R.id.spDone);
        Button reset = findViewById(R.id.spReset);

        // The drawing view must be created here, not as a field
        // initialiser, because the activity has no Context until
        // super.onCreate(...) has finished. Field initialisation runs
        // before that, so `new View(this)` would NPE.
        final Paint rectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        rectPaint.setColor(Color.parseColor("#88E91E63"));
        rectPaint.setStyle(Paint.Style.FILL);

        final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        handlePaint.setColor(Color.parseColor("#E91E63"));
        handlePaint.setStyle(Paint.Style.FILL);

        final Paint handleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        handleStroke.setColor(Color.WHITE);
        handleStroke.setStyle(Paint.Style.STROKE);
        handleStroke.setStrokeWidth(4f);

        editor = new View(this) {
            @Override
            protected void onDraw(Canvas c) {
                super.onDraw(c);
                int w = getWidth();
                int h = getHeight();
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
        };

        done.setOnClickListener(v -> {
            params.screenCustomLayout = true;
            params.screenCustomX1 = x1;
            params.screenCustomY1 = y1;
            params.screenCustomX2 = x2;
            params.screenCustomY2 = y2;
            ProfilesManager.saveConfig(params);
            Toast.makeText(this, R.string.pref_screen_position_saved, Toast.LENGTH_SHORT).show();
            finish();
        });

        reset.setOnClickListener(v -> {
            x1 = 0.15f; y1 = 0.15f; x2 = 0.85f; y2 = 0.85f;
            editor.invalidate();
        });

        editor.setOnTouchListener((v, event) -> {
            int w = v.getWidth();
            int h = v.getHeight();
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
                    hint.setVisibility(View.GONE);
                    v.invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (activeHandle == HANDLE_NONE) return false;
                    applyDrag(nx, ny);
                    v.invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    activeHandle = HANDLE_NONE;
                    return true;
                }
                default:
                    return false;
            }
        });
    }

    /**
     * The custom view that actually draws the rect + handles.
     * <p>Initialised lazily inside {@link #onCreate}; a field
     * initialiser would NPE because the activity has no Context yet
     * until {@code super.onCreate} runs.</p>
     */
    private View editor;

    @Override
    protected void onResume() {
        super.onResume();
        canvas.addView(editor,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private int pickHandle(float nx, float ny) {
        float dx = Math.abs(nx - x1);
        float dy = Math.abs(ny - y1);
        float dRight = Math.abs(nx - x2);
        float dBottom = Math.abs(ny - y2);

        float tol = 0.06f; // 6% of canvas size
        if (dx < tol && dy < tol) return HANDLE_TL;
        if (dRight < tol && dy < tol) return HANDLE_TR;
        if (dx < tol && dBottom < tol) return HANDLE_BL;
        if (dRight < tol && dBottom < tol) return HANDLE_BR;

        // Inside the rect → drag whole body.
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
