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
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.github.eka2l1.R;

import java.io.File;

/**
 * Stand-alone free-form screen layout editor. The user drags the four
 * corners and/or the whole rectangle to position the emulated screen
 * anywhere on the device window. The chosen rectangle is persisted as
 * four normalized coordinates (0..1) on the active profile's
 * {@link ProfileModel}.
 *
 * <p>This is also reachable in-game via the emulator options menu so
 * the user can see the actual game output (and any custom background)
 * while adjusting the rectangle.</p>
 */
public class ScreenPositionActivity extends Activity {

    public static final String EXTRA_CONFIG_DIR = "config_dir";

    private File configDir;
    private ProfileModel params;
    private ScreenPositionEditor editor;
    private TextView hint;

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

        editor = findViewById(R.id.spEditor);
        hint = findViewById(R.id.spHint);
        Button done = findViewById(R.id.spDone);
        Button reset = findViewById(R.id.spReset);

        // Seed from the profile. If the user has never opened the
        // editor we get the default (0,0)→(1,1), in which case show a
        // small inset so the four handles are visible.
        float x1 = params.screenCustomX1;
        float y1 = params.screenCustomY1;
        float x2 = params.screenCustomX2;
        float y2 = params.screenCustomY2;
        if (x1 == 0f && y1 == 0f && x2 == 1f && y2 == 1f) {
            x1 = 0.15f; y1 = 0.15f; x2 = 0.85f; y2 = 0.85f;
        }
        editor.setRect(x1, y1, x2, y2);

        editor.setListener((nx1, ny1, nx2, ny2, confirm) -> {
            if (confirm) {
                hint.setVisibility(View.GONE);
            }
        });

        done.setOnClickListener(v -> {
            params.screenCustomLayout = true;
            params.screenCustomX1 = editor.getX1();
            params.screenCustomY1 = editor.getY1();
            params.screenCustomX2 = editor.getX2();
            params.screenCustomY2 = editor.getY2();
            ProfilesManager.saveConfig(params);
            Toast.makeText(this, R.string.pref_screen_position_saved, Toast.LENGTH_SHORT).show();
            finish();
        });

        reset.setOnClickListener(v -> {
            editor.resetToDefault();
            hint.setVisibility(View.VISIBLE);
        });
    }
}
