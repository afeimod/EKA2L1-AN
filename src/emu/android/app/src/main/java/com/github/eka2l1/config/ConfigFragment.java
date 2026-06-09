/*
 * Copyright (c) 2021 EKA2L1 Team
 * Copyright (c) 2020 Yury Kharchenko
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

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Spanned;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.github.eka2l1.BuildConfig;
import com.github.eka2l1.R;
import com.github.eka2l1.emu.Emulator;
import com.github.eka2l1.emu.EmulatorActivity;
import com.github.eka2l1.settings.AppDataStore;
import com.github.eka2l1.settings.KeyMapperFragment;
import com.github.eka2l1.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import yuku.ambilwarna.AmbilWarnaDialog;

import static com.github.eka2l1.emu.Constants.*;

public class ConfigFragment extends Fragment implements View.OnClickListener {
    private final ActivityResultLauncher<String[]> openBackgroundImageLauncher = registerForActivityResult(
            FileUtils.getFilePicker(),
            this::onOpenBackgroundImageResult);

    protected ScrollView rootContainer;
    protected EditText etScreenRefreshRate;
    protected EditText etSystemTimeDelay;
    protected CompoundButton cbShouldChildInherit;

    protected EditText etScreenBack;
    protected Button cmdViewScreenBgImg;
    protected SeekBar sbBgImgOpacity;
    protected EditText etBgImgOpacityValue;
    protected SeekBar sbScaleRatio;
    protected EditText etScaleRatioValue;
    protected Spinner spOrientation;
    protected Spinner spScaleType;
    protected CompoundButton cbScreenCustomLayout;
    protected Button cmdEditScreenPosition;
    protected TextView tvUpscaleShader;
    protected Spinner spUpscaleShader;

    protected CompoundButton cbShowNotch;
    protected CompoundButton cbUseShaderForUpscale;
    protected CompoundButton cbShowKeyboard;
    private View rootInputConfig;
    private View groupVkConfig;
    protected CompoundButton cbVKFeedback;
    protected CompoundButton cbTouchInput;
    protected CompoundButton cbBgImgKeepAspectRatio;

    private Spinner spVKType;
    private Spinner spButtonsShape;
    protected SeekBar sbVKAlpha;
    protected TextView tvVKAlphaDefaultValue;
    protected EditText etVKHideDelay;
    protected EditText etVKFore;
    protected EditText etVKBack;
    protected EditText etVKSelFore;
    protected EditText etVKSelBack;
    protected EditText etVKOutline;
    protected SeekBar sbVKForeAlpha;
    protected SeekBar sbVKBackAlpha;
    protected SeekBar sbVKSelForeAlpha;
    protected SeekBar sbVKSelBackAlpha;
    protected SeekBar sbVKOutlineAlpha;
    protected TextView tvVKForeAlphaValue;
    protected TextView tvVKBackAlphaValue;
    protected TextView tvVKSelForeAlphaValue;
    protected TextView tvVKSelBackAlphaValue;
    protected TextView tvVKOutlineAlphaValue;

    private File keylayoutFile;
    private ProfileModel params;
    private boolean isProfile;
    private File configDir;
    private String defProfile;
    private boolean needShow;
    private AppDataStore dataStore;
    private long uid;
    private boolean compatChanged;

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setHasOptionsMenu(true);
        ActionBar actionBar = ((AppCompatActivity) getActivity()).getSupportActionBar();
        if (!(getActivity() instanceof ConfigActivity)) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        Bundle args = getArguments();
        String action = args.getString(KEY_ACTION, "");
        isProfile = ACTION_EDIT_PROFILE.equals(action);
        needShow = isProfile || ACTION_EDIT.equals(action);
        if (isProfile) {
            String path = args.getString(KEY_PROFILE_NAME, "");
            boolean create = args.getBoolean(KEY_PROFILE_CREATE);
            if (create) {
                Bundle result = new Bundle();
                result.putString(KEY_NAME, path);
                getParentFragmentManager().setFragmentResult(KEY_PROFILE_CREATED, result);
            }

            configDir = new File(Emulator.getProfilesDir(), path);
            actionBar.setTitle(path);
            ConstraintLayout systemPropertiesLayout = view.findViewById(R.id.rootSystemProperties);
            systemPropertiesLayout.setVisibility(View.GONE);
        } else {
            uid = args.getLong(KEY_APP_UID, -1);
            String uidStr = Long.toHexString(uid).toUpperCase();
            actionBar.setTitle(args.getString(KEY_APP_NAME));
            configDir = new File(Emulator.getConfigsDir(), uidStr);
            dataStore = AppDataStore.getAppStore(uidStr);
        }
        configDir.mkdirs();

        defProfile = AppDataStore.getAndroidStore().getString(PREF_DEFAULT_PROFILE, null);
        params = ProfilesManager.loadConfigOrDefault(configDir, defProfile);
        if (!params.isNew && !needShow) {
            startApp();
            getParentFragmentManager().popBackStackImmediate();
            return;
        }
        loadKeyLayout();
        getParentFragmentManager().setFragmentResultListener(KEY_PROFILE_LOADED, this, (requestKey, bundle) -> {
            loadParams(true);
        });
    }

    private SeekBar.OnSeekBarChangeListener getConnectedWithEditTextSeekbarChangeListener(EditText edit) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) edit.setText(String.valueOf(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
    }

    /**
     * Listener that mirrors a SeekBar's progress (treated as 0..max, displayed
     * as 0..100%) into a companion TextView. Used for the per-color alpha
     * sliders where there is no EditText round-trip.
     */
    private SeekBar.OnSeekBarChangeListener getAlphaLabelSeekBarChangeListener(TextView label) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int max = seekBar.getMax();
                if (max <= 0) max = 255;
                int percent = Math.round(progress * 100f / max);
                label.setText(getString(R.string.pref_opacity_percent, percent));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
    }

    /**
     * Maps a stored alpha byte (0..255) onto the slider's 0..100 percent
     * range so users see and tweak values as intuitive percentages.
     */
    private static int alphaToPercent(int alpha) {
        if (alpha < 0) alpha = 0;
        if (alpha > 255) alpha = 255;
        return Math.round(alpha * 100f / 255f);
    }

    /** Inverse of {@link #alphaToPercent}. */
    private static int percentToAlpha(int percent) {
        if (percent < 0) percent = 0;
        if (percent > 100) percent = 100;
        return Math.round(percent * 255f / 100f);
    }

    private TextWatcher getConnectedTextWatcher(EditText edit, SeekBar seek) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                int length = s.length();
                if (length > 3) {
                    if (start >= 3) {
                        edit.getText().delete(3, length);
                    } else {
                        int st = start + count;
                        int end = st + (before == 0 ? count : before);
                        edit.getText().delete(st, Math.min(end, length));
                    }
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (s.length() == 0) return;
                try {
                    int progress = Integer.parseInt(s.toString());
                    if (progress <= 100) {
                        seek.setProgress(progress);
                    } else {
                        s.replace(0, s.length(), "100");
                    }
                } catch (NumberFormatException e) {
                    s.clear();
                }
            }
        };
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_config, container, false);
        rootContainer = view.findViewById(R.id.configRoot);
        etScreenRefreshRate = view.findViewById(R.id.etScreenRefreshRate);
        etSystemTimeDelay = view.findViewById(R.id.etSystemTimeDelay);
        cbShouldChildInherit = view.findViewById(R.id.cbShouldChildInherit);

        etScreenBack = view.findViewById(R.id.etScreenBack);
        cmdViewScreenBgImg = view.findViewById(R.id.cmdScreenViewBgImg);
        spScaleType = view.findViewById(R.id.spScaleType);
        cbScreenCustomLayout = view.findViewById(R.id.cbScreenCustomLayout);
        cmdEditScreenPosition = view.findViewById(R.id.cmdEditScreenPosition);
        sbBgImgOpacity = view.findViewById(R.id.sbScreenBgImgOpacity);
        etBgImgOpacityValue = view.findViewById(R.id.etScreenBgImgOpacityValue);
        sbScaleRatio = view.findViewById(R.id.sbScaleRatio);
        etScaleRatioValue = view.findViewById(R.id.etScaleRatioValue);
        spOrientation = view.findViewById(R.id.spOrientation);
        tvUpscaleShader = view.findViewById(R.id.tvUpscaleShader);
        spUpscaleShader = view.findViewById(R.id.spUpscaleShader);
        cbUseShaderForUpscale = view.findViewById(R.id.cbShouldUseShaderForUpscale);
        cbShowNotch = view.findViewById(R.id.cbShowNotch);
        cbBgImgKeepAspectRatio = view.findViewById(R.id.cbKeepBgImgAspect);

        rootInputConfig = view.findViewById(R.id.rootInputConfig);
        cbTouchInput = view.findViewById(R.id.cbTouchInput);
        cbShowKeyboard = view.findViewById(R.id.cbIsShowKeyboard);
        groupVkConfig = view.findViewById(R.id.groupVkConfig);
        cbVKFeedback = view.findViewById(R.id.cbVKFeedback);

        spVKType = view.findViewById(R.id.spVKType);
        spButtonsShape = view.findViewById(R.id.spButtonsShape);
        sbVKAlpha = view.findViewById(R.id.sbVKAlpha);
        tvVKAlphaDefaultValue = view.findViewById(R.id.tvVKAlphaDefaultValue);
        etVKHideDelay = view.findViewById(R.id.etVKHideDelay);
        etVKFore = view.findViewById(R.id.etVKFore);
        etVKBack = view.findViewById(R.id.etVKBack);
        etVKSelFore = view.findViewById(R.id.etVKSelFore);
        etVKSelBack = view.findViewById(R.id.etVKSelBack);
        etVKOutline = view.findViewById(R.id.etVKOutline);
        sbVKForeAlpha = view.findViewById(R.id.sbVKForeAlpha);
        sbVKBackAlpha = view.findViewById(R.id.sbVKBackAlpha);
        sbVKSelForeAlpha = view.findViewById(R.id.sbVKSelForeAlpha);
        sbVKSelBackAlpha = view.findViewById(R.id.sbVKSelBackAlpha);
        sbVKOutlineAlpha = view.findViewById(R.id.sbVKOutlineAlpha);
        tvVKForeAlphaValue = view.findViewById(R.id.tvVKForeAlphaValue);
        tvVKBackAlphaValue = view.findViewById(R.id.tvVKBackAlphaValue);
        tvVKSelForeAlphaValue = view.findViewById(R.id.tvVKSelForeAlphaValue);
        tvVKSelBackAlphaValue = view.findViewById(R.id.tvVKSelBackAlphaValue);
        tvVKOutlineAlphaValue = view.findViewById(R.id.tvVKOutlineAlphaValue);

        view.findViewById(R.id.cmdScreenBack).setOnClickListener(this);
        view.findViewById(R.id.cmdScreenBgImg).setOnClickListener(this);
        view.findViewById(R.id.cmdScreenClearBgImg).setOnClickListener(this);
        view.findViewById(R.id.cmdKeyMappings).setOnClickListener(this);
        view.findViewById(R.id.cmdVKBack).setOnClickListener(this);
        view.findViewById(R.id.cmdVKFore).setOnClickListener(this);
        view.findViewById(R.id.cmdVKSelBack).setOnClickListener(this);
        view.findViewById(R.id.cmdVKSelFore).setOnClickListener(this);
        view.findViewById(R.id.cmdVKOutline).setOnClickListener(this);
        cmdViewScreenBgImg.setOnClickListener(this);
        cmdEditScreenPosition.setOnClickListener(this);

        // Load shaders
        File upscaleShaderDir = new File(Emulator.getEmulatorDir() + "resources/upscale");
        File []upscaleShaderFiles = upscaleShaderDir.listFiles(file -> file.getPath().endsWith(".frag"));

        List<String> upscaleShaderNames = new ArrayList<>();
        upscaleShaderNames.add(getString(R.string.pref_screen_default_shader));

        if (upscaleShaderFiles != null) {
            for (File upscaleShaderFile: upscaleShaderFiles) {
                String singleName = upscaleShaderFile.getName();
                singleName = singleName.substring(0, singleName.length() - 5);
                upscaleShaderNames.add(singleName);
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(),
                android.R.layout.simple_spinner_dropdown_item,
                upscaleShaderNames);

        spUpscaleShader.setAdapter(adapter);

        etScreenRefreshRate.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (etScreenRefreshRate.isFocused()) compatChanged = true;
            }
        });
        etSystemTimeDelay.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (etSystemTimeDelay.isFocused()) compatChanged = true;
            }
        });
        cbShouldChildInherit.setOnCheckedChangeListener((buttonView, isChecked) -> compatChanged = true);

        sbBgImgOpacity.setOnSeekBarChangeListener(getConnectedWithEditTextSeekbarChangeListener(etBgImgOpacityValue));
        etBgImgOpacityValue.addTextChangedListener(getConnectedTextWatcher(etBgImgOpacityValue, sbBgImgOpacity));

        sbScaleRatio.setOnSeekBarChangeListener(getConnectedWithEditTextSeekbarChangeListener(etScaleRatioValue));
        etScaleRatioValue.addTextChangedListener(getConnectedTextWatcher(etScaleRatioValue, sbScaleRatio));

        // Each per-color alpha slider drives a TextView showing percent.
        sbVKForeAlpha.setOnSeekBarChangeListener(getAlphaLabelSeekBarChangeListener(tvVKForeAlphaValue));
        sbVKBackAlpha.setOnSeekBarChangeListener(getAlphaLabelSeekBarChangeListener(tvVKBackAlphaValue));
        sbVKSelForeAlpha.setOnSeekBarChangeListener(getAlphaLabelSeekBarChangeListener(tvVKSelForeAlphaValue));
        sbVKSelBackAlpha.setOnSeekBarChangeListener(getAlphaLabelSeekBarChangeListener(tvVKSelBackAlphaValue));
        sbVKOutlineAlpha.setOnSeekBarChangeListener(getAlphaLabelSeekBarChangeListener(tvVKOutlineAlphaValue));
        sbVKAlpha.setOnSeekBarChangeListener(getAlphaLabelSeekBarChangeListener(tvVKAlphaDefaultValue));

        cbShowNotch.setOnCheckedChangeListener((buttonView, isChecked) -> {

        });

        cbUseShaderForUpscale.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                tvUpscaleShader.setVisibility(View.VISIBLE);
                spUpscaleShader.setVisibility(View.VISIBLE);
            } else {
                tvUpscaleShader.setVisibility(View.GONE);
                spUpscaleShader.setVisibility(View.GONE);
            }

            compatChanged = true;
        });
        spUpscaleShader.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                compatChanged = true;
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
            }
        });
        cbShowKeyboard.setOnClickListener((b) -> {
            View.OnLayoutChangeListener onLayoutChangeListener = new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    View focus = rootContainer.findFocus();
                    if (focus != null) focus.clearFocus();
                    v.scrollTo(0, rootInputConfig.getTop());
                    v.removeOnLayoutChangeListener(this);
                }
            };
            rootContainer.addOnLayoutChangeListener(onLayoutChangeListener);
            groupVkConfig.setVisibility(cbShowKeyboard.isChecked() ? View.VISIBLE : View.GONE);
        });
        etScreenBack.addTextChangedListener(new ColorTextWatcher(etScreenBack));
        etVKFore.addTextChangedListener(new ColorTextWatcher(etVKFore));
        etVKBack.addTextChangedListener(new ColorTextWatcher(etVKBack));
        etVKSelFore.addTextChangedListener(new ColorTextWatcher(etVKSelFore));
        etVKSelBack.addTextChangedListener(new ColorTextWatcher(etVKSelBack));
        etVKOutline.addTextChangedListener(new ColorTextWatcher(etVKOutline));
        return view;
    }

    private void loadKeyLayout() {
        File file = new File(configDir, Emulator.APP_KEY_LAYOUT_FILE);
        keylayoutFile = file;
        if (isProfile || file.exists()) {
            return;
        }
        if (defProfile == null) {
            return;
        }
        File defaultKeyLayoutFile = new File(Emulator.getProfilesDir() + defProfile, Emulator.APP_KEY_LAYOUT_FILE);
        if (!defaultKeyLayoutFile.exists()) {
            return;
        }
        try {
            FileUtils.copyFileUsingChannel(defaultKeyLayoutFile, file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onPause() {
        if (needShow && configDir != null) {
            saveParams();
        }
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (needShow) {
            loadParams(true);
        }
    }

    private int parseInt(String s) {
        return parseInt(s, 10);
    }

    private int parseInt(String s, int radix) {
        int result;
        try {
            result = Integer.parseInt(s, radix);
        } catch (NumberFormatException e) {
            result = 0;
        }
        return result;
    }

    @SuppressLint("SetTextI18n")
    public void loadParams(boolean reloadFromFile) {
        if (reloadFromFile) {
            params = ProfilesManager.loadConfigOrDefault(configDir, defProfile);
        }
        if (!isProfile) {
            etScreenRefreshRate.setText(dataStore.getString("fps", "60"));
            etSystemTimeDelay.setText(dataStore.getString("time-delay", "0"));
            cbShouldChildInherit.setChecked(dataStore.getBoolean("should-child-inherit-setting", true));

            cbUseShaderForUpscale.setChecked(!dataStore.getString("screen-upscale-method", "0").equals("0"));
            if (!cbUseShaderForUpscale.isChecked()) {
                tvUpscaleShader.setVisibility(View.GONE);
                spUpscaleShader.setVisibility(View.GONE);
            } else {
                tvUpscaleShader.setVisibility(View.VISIBLE);
                spUpscaleShader.setVisibility(View.VISIBLE);
            }

            String shaderName = dataStore.getString("filter-shader-path", "");
            spUpscaleShader.setSelection(0);
            if (!shaderName.isEmpty()) {
                for (int i = 1; i < spUpscaleShader.getCount(); i++) {
                    String spinnerShaderName = (String)spUpscaleShader.getItemAtPosition(i);
                    if (spinnerShaderName.equals(shaderName)) {
                        spUpscaleShader.setSelection(i);
                        break;
                    }
                }
            }
        } else {
            tvUpscaleShader.setVisibility(View.GONE);
            spUpscaleShader.setVisibility(View.GONE);
        }

        etScreenBack.setText(String.format("%06X", params.screenBackgroundColor));
        sbScaleRatio.setProgress(params.screenScaleRatio);
        etBgImgOpacityValue.setText(Integer.toString(params.screenBackgroundImageOpacity));
        etScaleRatioValue.setText(Integer.toString(params.screenScaleRatio));
        spOrientation.setSelection(params.orientation);
        spScaleType.setSelection(params.screenScaleType);
        cbScreenCustomLayout.setChecked(params.screenCustomLayout);
        cbShowNotch.setChecked(params.screenShowNotch);
        cbBgImgKeepAspectRatio.setChecked(params.screenBackgroundImageKeepAspectRatio);

        File backgroundCheckFile = ProfilesManager.getBackgroundFile(configDir);

        if (backgroundCheckFile.exists()) {
            cmdViewScreenBgImg.setText(R.string.pref_background_image_view);
        }

        boolean showVk = params.showKeyboard;
        cbShowKeyboard.setChecked(showVk);
        groupVkConfig.setVisibility(showVk ? View.VISIBLE : View.GONE);
        cbVKFeedback.setChecked(params.vkFeedback);
        cbTouchInput.setChecked(params.touchInput);

        spVKType.setSelection(params.vkType);
        spButtonsShape.setSelection(params.vkButtonShape);
        sbVKAlpha.setProgress(alphaToPercent(params.vkAlpha));
        tvVKAlphaDefaultValue.setText(getString(R.string.pref_opacity_percent, sbVKAlpha.getProgress()));
        int vkHideDelay = params.vkHideDelay;
        if (vkHideDelay > 0) {
            etVKHideDelay.setText(Integer.toString(vkHideDelay));
        }

        etVKBack.setText(String.format("%06X", params.vkBgColor));
        etVKFore.setText(String.format("%06X", params.vkFgColor));
        etVKSelBack.setText(String.format("%06X", params.vkBgColorSelected));
        etVKSelFore.setText(String.format("%06X", params.vkFgColorSelected));
        etVKOutline.setText(String.format("%06X", params.vkOutlineColor));

        // Per-color alpha sliders. Convert from 0..255 stored range into the
        // 0..100 range the sliders expose, so the user sees a familiar percent.
        sbVKForeAlpha.setProgress(alphaToPercent(params.getEffectiveFgAlpha()));
        sbVKBackAlpha.setProgress(alphaToPercent(params.getEffectiveBgAlpha()));
        sbVKSelForeAlpha.setProgress(alphaToPercent(params.getEffectiveFgAlphaSelected()));
        sbVKSelBackAlpha.setProgress(alphaToPercent(params.getEffectiveBgAlphaSelected()));
        sbVKOutlineAlpha.setProgress(alphaToPercent(params.getEffectiveOutlineAlpha()));
        // SeekBar.setProgress doesn't fire the change listener, so mirror the
        // percent into the label TextViews explicitly here.
        tvVKForeAlphaValue.setText(getString(R.string.pref_opacity_percent, sbVKForeAlpha.getProgress()));
        tvVKBackAlphaValue.setText(getString(R.string.pref_opacity_percent, sbVKBackAlpha.getProgress()));
        tvVKSelForeAlphaValue.setText(getString(R.string.pref_opacity_percent, sbVKSelForeAlpha.getProgress()));
        tvVKSelBackAlphaValue.setText(getString(R.string.pref_opacity_percent, sbVKSelBackAlpha.getProgress()));
        tvVKOutlineAlphaValue.setText(getString(R.string.pref_opacity_percent, sbVKOutlineAlpha.getProgress()));
        // Mirror the legacy global slider's initial value into its label.
        tvVKAlphaDefaultValue.setText(getString(R.string.pref_opacity_percent,
                alphaToPercent(params.vkAlpha)));

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // Disable notch option on older version
            cbShowNotch.setVisibility(View.GONE);
        }
    }

    private void saveParams() {
        try {
            try {
                params.screenBackgroundColor = Integer.parseInt(etScreenBack.getText().toString(), 16);
            } catch (NumberFormatException ignored) {
            }
            params.screenBackgroundImageOpacity = sbBgImgOpacity.getProgress();
            params.screenScaleRatio = sbScaleRatio.getProgress();
            params.orientation = spOrientation.getSelectedItemPosition();
            params.screenCustomLayout = cbScreenCustomLayout.isChecked();
            params.screenScaleType = spScaleType.getSelectedItemPosition();
            params.screenShowNotch = cbShowNotch.isChecked();
            params.screenBackgroundImageKeepAspectRatio = cbBgImgKeepAspectRatio.isChecked();

            params.showKeyboard = cbShowKeyboard.isChecked();
            params.vkFeedback = cbVKFeedback.isChecked();
            params.touchInput = cbTouchInput.isChecked();

            params.vkType = spVKType.getSelectedItemPosition();
            params.vkButtonShape = spButtonsShape.getSelectedItemPosition();
            params.vkAlpha = percentToAlpha(sbVKAlpha.getProgress());
            params.vkHideDelay = parseInt(etVKHideDelay.getText().toString());
            // Per-color alpha sliders: convert the displayed 0..100 percent
            // back into a 0..255 alpha byte the emulator understands.
            params.vkFgAlpha = percentToAlpha(sbVKForeAlpha.getProgress());
            params.vkBgAlpha = percentToAlpha(sbVKBackAlpha.getProgress());
            params.vkFgAlphaSelected = percentToAlpha(sbVKSelForeAlpha.getProgress());
            params.vkBgAlphaSelected = percentToAlpha(sbVKSelBackAlpha.getProgress());
            params.vkOutlineAlpha = percentToAlpha(sbVKOutlineAlpha.getProgress());
            try {
                params.vkBgColor = Integer.parseInt(etVKBack.getText().toString(), 16);
            } catch (Exception ignored) {
            }
            try {
                params.vkFgColor = Integer.parseInt(etVKFore.getText().toString(), 16);
            } catch (Exception ignored) {
            }
            try {
                params.vkBgColorSelected = Integer.parseInt(etVKSelBack.getText().toString(), 16);
            } catch (Exception ignored) {
            }
            try {
                params.vkFgColorSelected = Integer.parseInt(etVKSelFore.getText().toString(), 16);
            } catch (Exception ignored) {
            }
            try {
                params.vkOutlineColor = Integer.parseInt(etVKOutline.getText().toString(), 16);
            } catch (Exception ignored) {
            }
            ProfilesManager.saveConfig(params);

            if (!isProfile && compatChanged) {
                dataStore.putString("fps", etScreenRefreshRate.getText().toString());
                dataStore.putString("time-delay", etSystemTimeDelay.getText().toString());
                dataStore.putBoolean("should-child-inherit-setting", cbShouldChildInherit.isChecked());
                dataStore.putString("screen-upscale-method", cbUseShaderForUpscale.isChecked() ? "1" : "0");
                String toSaveFilterName = "Default";
                if (spUpscaleShader.getSelectedItemPosition() != 0) {
                    toSaveFilterName = (String)spUpscaleShader.getSelectedItem();
                }
                dataStore.putString("filter-shader-path", toSaveFilterName);
                dataStore.save();
                Emulator.updateAppSetting((int) uid);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.config, menu);
        if (isProfile) {
            menu.findItem(R.id.action_start).setVisible(false);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_start) {
            startApp();
        } else if (itemId == R.id.action_reset_settings) {
            params = new ProfileModel(configDir);
            loadParams(false);
        } else if (itemId == R.id.action_reset_layout) {//noinspection ResultOfMethodCallIgnored
            keylayoutFile.delete();
            loadKeyLayout();
        } else if (itemId == R.id.action_load_profile) {
            LoadProfileAlert.newInstance(keylayoutFile.getParent())
                    .show(getParentFragmentManager(), "load_profile");
        } else if (itemId == R.id.action_save_profile) {
            saveParams();
            SaveProfileAlert.getInstance(keylayoutFile.getParent())
                    .show(getParentFragmentManager(), "save_profile");
        } else if (itemId == android.R.id.home) {
            getParentFragmentManager().popBackStackImmediate();
        }
        return super.onOptionsItemSelected(item);
    }

    private void startApp() {
        Intent intent = new Intent(getContext(), EmulatorActivity.class);
        intent.putExtras(requireArguments());
        startActivity(intent);
    }

    private void onOpenBackgroundImageResult(String result) {
        if (result != null) {
            // TODO: Check if the file is actually valid or not
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    FileUtils.copyFileFromURI(getContext(), result, ProfilesManager.getBackgroundFile(configDir));
                } else {
                    FileUtils.copyFileUsingChannel(new File(result), ProfilesManager.getBackgroundFile(configDir));
                }
            } catch (IOException ex) {
                Toast.makeText(getContext(), R.string.pref_background_image_fail_to_set, Toast.LENGTH_LONG).show();
                return;
            }

            cmdViewScreenBgImg.setText(R.string.pref_background_image_view);
        }
    }

    private void previewBackgroundImage() {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_VIEW);
        intent.setDataAndType(FileProvider.getUriForFile(getContext(), BuildConfig.APPLICATION_ID + ".provider", ProfilesManager.getBackgroundFile(configDir)), "image/*");
        intent.setFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        startActivity(intent);
    }

    private void clearBackgroundImage() {
        File backgroundFile = ProfilesManager.getBackgroundFile(configDir);
        if (backgroundFile.exists()) {
            backgroundFile.delete();
            cmdViewScreenBgImg.setText(R.string.pref_background_image_empty);
        }
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.cmdScreenBack) {
            // Screen background color does not carry an alpha channel in this
            // project (the native side expects 24-bit RGB), so we deliberately
            // keep its picker untouched and don't route through showAlphaPicker.
            showColorPicker(etScreenBack, null, null);
        } else if (id == R.id.cmdScreenBgImg) {
            openBackgroundImageLauncher.launch(new String[]{ ".bmp", ".png", ".jpg", ".jpeg" });
        } else if (id == R.id.cmdScreenViewBgImg) {
            previewBackgroundImage();
        } else if (id == R.id.cmdScreenClearBgImg) {
            clearBackgroundImage();
        } else if (id == R.id.cmdVKBack) {
            showColorPicker(etVKBack, sbVKBackAlpha, tvVKBackAlphaValue);
        } else if (id == R.id.cmdVKFore) {
            showColorPicker(etVKFore, sbVKForeAlpha, tvVKForeAlphaValue);
        } else if (id == R.id.cmdVKSelFore) {
            showColorPicker(etVKSelFore, sbVKSelForeAlpha, tvVKSelForeAlphaValue);
        } else if (id == R.id.cmdVKSelBack) {
            showColorPicker(etVKSelBack, sbVKSelBackAlpha, tvVKSelBackAlphaValue);
        } else if (id == R.id.cmdVKOutline) {
            showColorPicker(etVKOutline, sbVKOutlineAlpha, tvVKOutlineAlphaValue);
        } else if (id == R.id.cmdKeyMappings) {
            KeyMapperFragment keyMapperFragment = new KeyMapperFragment();
            Bundle args = new Bundle();
            args.putString(KEY_CONFIG_PATH, configDir.getAbsolutePath());
            keyMapperFragment.setArguments(args);
            getParentFragmentManager().beginTransaction()
                    .replace(R.id.container, keyMapperFragment)
                    .addToBackStack(null)
                    .commit();
        } else if (id == R.id.cmdEditScreenPosition) {
            // First, persist the current selection so the editor sees the
            // exact same values we'd hand to the emulator. Then launch the
            // free-form layout editor.
            saveParams();
            android.content.Intent intent = new android.content.Intent(getContext(),
                    com.github.eka2l1.config.ScreenPositionActivity.class);
            intent.putExtra(ScreenPositionActivity.EXTRA_CONFIG_DIR, configDir.getAbsolutePath());
            startActivity(intent);
        }
    }

    private void showColorPicker(EditText et, SeekBar alphaBar, TextView alphaLabel) {
        // Build the AmbilWarnaDialog with supportsAlpha=true so the library
        // exposes its internal HSV alpha controls and returns ARGB from
        // getColor(). Initial color is (currentAlpha << 24) | currentRGB so
        // re-opening the dialog preserves the user's last alpha choice.
        int currentRgb;
        try {
            currentRgb = parseInt(et.getText().toString().trim(), 16) & 0xFFFFFF;
        } catch (NumberFormatException ignored) {
            currentRgb = 0;
        }
        int initialAlpha = alphaBar != null ? percentToAlpha(alphaBar.getProgress()) : 0xFF;
        int initialColor = (initialAlpha << 24) | currentRgb;

        AmbilWarnaDialog.OnAmbilWarnaListener colorListener = new AmbilWarnaDialog.OnAmbilWarnaListener() {
            @Override
            public void onOk(AmbilWarnaDialog dialog, int color) {
                int rgb = color & 0xFFFFFF;
                int alpha = (color >>> 24) & 0xFF;
                et.setText(String.format("%06X", rgb));
                ColorDrawable drawable = (ColorDrawable) et.getCompoundDrawablesRelative()[2];
                drawable.setColor(color);
                if (alphaBar != null) {
                    int percent = alphaToPercent(alpha);
                    alphaBar.setProgress(percent);
                    // alphaLabel is refreshed by the listener registered in
                    // onViewCreated, but SeekBar.setProgress doesn't fire
                    // listeners, so mirror the new value explicitly.
                    if (alphaLabel != null) {
                        alphaLabel.setText(getString(R.string.pref_opacity_percent, percent));
                    }
                }
            }

            @Override
            public void onCancel(AmbilWarnaDialog dialog) {
            }
        };

        AmbilWarnaDialog dialog = new AmbilWarnaDialog(getContext(), initialColor, true, colorListener);

        // Reach into the AlertDialog the library built and graft our alpha
        // SeekBar under the (left arrow right) preview strip. This way the
        // user gets RGB picking + alpha adjustment inside a single dialog,
        // matching the layout they were used to before.
        if (alphaBar != null) {
            injectAlphaSeekBar(dialog, alphaBar);
        }
        dialog.show();
    }

    /**
     * Adds a 0..100 percent alpha slider just below the preview strip of the
     * given AmbilWarnaDialog. The slider drives the dialog's internal alpha
     * channel via {@link AmbilWarnaDialog#setAlpha(int)} so the alpha overlay
     * swatch and the cursor stay in sync with what the user is picking.
     */
    private void injectAlphaSeekBar(AmbilWarnaDialog dialog, SeekBar sourceAlphaBar) {
        AlertDialog alertDialog = dialog.getDialog();
        if (alertDialog == null) return;

        // The preview strip lives in ambilwarna_state inside the library's
        // RelativeLayout view container. Add a SeekBar as a sibling below it.
        ViewGroup stateView = alertDialog.findViewById(R.id.ambilwarna_state);
        if (!(stateView.getParent() instanceof ViewGroup)) return;
        ViewGroup viewContainer = (ViewGroup) stateView.getParent();

        SeekBar seek = new SeekBar(getContext());
        seek.setMax(100);
        seek.setProgress(sourceAlphaBar.getProgress());

        // Wrap the slider in a vertical LinearLayout together with a tiny
        // percent label so the user can read the current value live.
        LinearLayout wrapper = new LinearLayout(getContext());
        wrapper.setOrientation(LinearLayout.HORIZONTAL);
        wrapper.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        wrapper.setPadding(pad, pad, pad, 0);

        TextView label = new TextView(getContext());
        label.setText(getString(R.string.pref_vk_alpha));
        label.setTextColor(android.graphics.Color.parseColor("#FFB2B2B2"));
        label.setTextSize(14f);

        TextView value = new TextView(getContext());
        value.setText(getString(R.string.pref_opacity_percent, sourceAlphaBar.getProgress()));
        value.setTextColor(android.graphics.Color.parseColor("#FFB2B2B2"));
        value.setTextSize(14f);
        value.setMinWidth((int) (48 * getResources().getDisplayMetrics().density));
        value.setGravity(android.view.Gravity.END);

        LinearLayout.LayoutParams labelLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams seekLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f);
        LinearLayout.LayoutParams valueLp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        wrapper.addView(label, labelLp);
        wrapper.addView(seek, seekLp);
        wrapper.addView(value, valueLp);

        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.BELOW, R.id.ambilwarna_state);
        lp.addRule(RelativeLayout.CENTER_HORIZONTAL);

        viewContainer.addView(wrapper, lp);

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                value.setText(getString(R.string.pref_opacity_percent, progress));
                if (fromUser) {
                    // Push the new alpha into the AmbilWarnaDialog so its
                    // overlay swatch and cursor stay in sync.
                    dialog.setAlpha(percentToAlpha(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private static class ColorTextWatcher implements TextWatcher {
        private final EditText editText;
        private final ColorDrawable drawable;

        ColorTextWatcher(EditText editText) {
            this.editText = editText;
            int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32,
                    editText.getResources().getDisplayMetrics());
            ColorDrawable colorDrawable = new ColorDrawable();
            colorDrawable.setBounds(0, 0, size, size);
            editText.setCompoundDrawablesRelative(null, null, colorDrawable, null);
            drawable = colorDrawable;
            editText.setFilters(new InputFilter[]{this::filter});
        }

        private CharSequence filter(CharSequence src, int ss, int se, Spanned dst, int ds, int de) {
            StringBuilder sb = new StringBuilder(se - ss);
            for (int i = ss; i < se; i++) {
                char c = src.charAt(i);
                if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')) {
                    sb.append(c);
                } else if (c >= 'a' && c <= 'f') {
                    sb.append((char) (c - 32));
                }
            }
            return sb;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (s.length() > 6) {
                if (start >= 6) editText.getText().delete(6, s.length());
                else {
                    int st = start + count;
                    int end = st + (before == 0 ? count : before);
                    editText.getText().delete(st, Math.min(end, s.length()));
                }
            }
        }

        @Override
        public void afterTextChanged(Editable s) {
            if (s.length() == 0) return;
            try {
                int color = Integer.parseInt(s.toString(), 16);
                drawable.setColor(color | Color.BLACK);
            } catch (NumberFormatException e) {
                drawable.setColor(Color.BLACK);
                s.clear();
            }
        }
    }
}
