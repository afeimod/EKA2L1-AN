/*
 * Copyright (c) 2020 EKA2L1 Team
 * Copyright (c) 2019 Kharchenko Yury
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

package com.github.eka2l1.emu;

import static com.github.eka2l1.emu.Constants.*;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.github.eka2l1.R;
import com.github.eka2l1.applist.AppLaunchInfo;
import com.github.eka2l1.config.ConfigActivity;
import com.github.eka2l1.config.ProfileModel;
import com.github.eka2l1.config.ProfilesManager;
import com.github.eka2l1.config.ScreenPositionEditor;
import com.github.eka2l1.emu.overlay.FixedKeyboard;
import com.github.eka2l1.emu.overlay.OverlayView;
import com.github.eka2l1.emu.overlay.VirtualKeyboard;
import com.github.eka2l1.settings.AppDataStore;
import com.github.eka2l1.settings.KeyMapper;
import com.github.eka2l1.util.LogUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;

public class EmulatorActivity extends AppCompatActivity {
    private static final int ORIENTATION_DEFAULT = 0;
    private static final int ORIENTATION_AUTO = 1;
    private static final int ORIENTATION_PORTRAIT = 2;
    private static final int ORIENTATION_LANDSCAPE = 3;
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final ActivityResultLauncher<String[]> permissionsLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            this::onPermissionResult);

    private Semaphore permissionsLauncherDone = new Semaphore(0);

    private Toolbar toolbar;
    private OverlayView overlayView;
    private long uid;
    private boolean launched;
    private boolean statusBarEnabled;
    private boolean actionBarEnabled;
    private VirtualKeyboard keyboard;

    /**
     * Active profile directory. Held in a field because some post-create
     * flows (e.g. the in-game screen position editor) need access to it
     * long after {@link #onCreate} returns.
     */
    private File configDir;

    /**
     * Container of the surface view. Used as the parent for the
     * in-game {@link ScreenPositionEditor} overlay.
     */
    private FrameLayout emulatorContainer;

    /**
     * The in-game screen position editor (when active). Null when the
     * user is not editing the screen rectangle.
     */
    private ScreenPositionEditor inGameEditor;
    private Button inGameEditorDone;
    private float displayWidth;
    private float displayHeight;
    private SparseIntArray androidToSymbian;
    private ProfileModel params;
    private MenuItem actionScreenshot;

    // Single-thread executor for native bridge calls. Anything that talks
    // to the emulator core (launch_app, install, getApps) goes through here
    // so the Android UI thread never blocks on a long native operation,
    // which used to trigger ANR ('Input dispatching timed out').
    private final java.util.concurrent.ExecutorService nativeCallExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "EKA2L1-NativeBridge");
                t.setPriority(Thread.NORM_PRIORITY + 1);
                return t;
            });

    /**
     * Run a native bridge call on a background executor. Use this for
     * any {@code Emulator.*} call that may take more than a few hundred
     * milliseconds (launch_app, installApp, mountSdCard, getApps, etc).
     */
    public void runNativeCall(Runnable r) {
        nativeCallExecutor.execute(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                android.util.Log.e("EKA2L1", "Native bridge call failed", t);
            }
        });
    }

    @Override
    protected void onDestroy() {
        try {
            nativeCallExecutor.shutdown();
        } catch (Throwable ignore) {
        }
        super.onDestroy();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Intent intent = getIntent();
        boolean externalIntent = intent.getBooleanExtra(KEY_APP_IS_SHORTCUT, false) || ACTION_LAUNCH_GAME.equals(intent.getAction())
                || intent.getData() != null;

        boolean launchFromFile = intent.getData() != null;

        if (externalIntent) {
            Emulator.initializeForShortcutLaunch(this);
        }

        AppDataStore dataStore = AppDataStore.getAndroidStore();
        setTheme(dataStore.getString(PREF_THEME, "dark"));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emulator);
        overlayView = findViewById(R.id.overlay);

        String name;
        String deviceCode;

        if (intent.getData() != null) {
            InputStream inputStream = null;

            try {
                inputStream = getContentResolver().openInputStream(intent.getData());
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }

            AppLaunchInfo launchInfo = gson.fromJson(new InputStreamReader(inputStream), AppLaunchInfo.class);

            uid = launchInfo.appUid;
            name = launchInfo.appName;
            deviceCode = launchInfo.deviceCode;

            if (name == null || uid == 0) {
                throw new RuntimeException("Invalid launch info");
            }
        } else {
            uid = intent.getLongExtra(KEY_APP_UID, -1);
            name = intent.getStringExtra(KEY_APP_NAME);
            deviceCode = intent.getStringExtra(KEY_DEVICE_CODE);
        }

        String uidStr = Long.toHexString(uid).toUpperCase();
        File configDir = new File(Emulator.getConfigsDir(), uidStr);
        this.configDir = configDir;
        String defProfile = dataStore.getString(PREF_DEFAULT_PROFILE, null);

        if (externalIntent && (params = ProfilesManager.loadConfig(configDir)) == null) {
            Intent configIntent = new Intent(this, ConfigActivity.class);
            Bundle extras;
            if (launchFromFile) {
                extras = new Bundle();

                extras.putLong(KEY_APP_UID, uid);
                extras.putString(KEY_APP_NAME, name);
                extras.putString(KEY_DEVICE_CODE, deviceCode);
            } else {
                extras = Objects.requireNonNull(intent.getExtras());
            }
            extras.putString(KEY_ACTION, ACTION_EDIT);
            configIntent.putExtras(extras);
            startActivity(configIntent);
            finish();
            return;
        } else {
            params = ProfilesManager.loadConfigOrDefault(configDir, defProfile);
        }

        SurfaceView surfaceView = findViewById(R.id.surface_view);
        emulatorContainer = findViewById(R.id.emulator_container);
        ViewCallbacks callbacks = new ViewCallbacks(surfaceView);
        surfaceView.setFocusableInTouchMode(true);
        surfaceView.setWillNotDraw(true);
        surfaceView.setOnTouchListener(callbacks);
        surfaceView.setOnKeyListener(callbacks);
        surfaceView.getHolder().addCallback(callbacks);
        surfaceView.requestFocus();

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        boolean wakelockEnabled = dataStore.getBoolean(PREF_KEEP_SCREEN, false);
        if (wakelockEnabled) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        actionBarEnabled = dataStore.getBoolean(PREF_ACTIONBAR, false);
        statusBarEnabled = dataStore.getBoolean(PREF_STATUSBAR, false);
        if (!actionBarEnabled) {
            getSupportActionBar().hide();
        }

        Emulator.setContext(this);
        EmulatorCamera.setActivity(this);

        if (deviceCode != null) {
            String []availableDevices = Emulator.getDeviceFirmwareCodes();
            for (int id = 0; id < availableDevices.length; id++) {
                if (availableDevices[id].compareToIgnoreCase(deviceCode) == 0) {
                    Emulator.setCurrentDevice(id, true);
                    break;
                }
            }
        }

        setActionBar(name);
        hideSystemUI();

        Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        displayWidth = display.getWidth();
        displayHeight = display.getHeight();

        androidToSymbian = (params != null && params.keyMappings != null) ? params.keyMappings : KeyMapper.getDefaultKeyMap();

        if (params != null && params.showKeyboard) {
            setVirtualKeyboard(uidStr);
        }
        if (params != null && params.showKeyboard && keyboard instanceof FixedKeyboard) {
            setOrientation(ORIENTATION_PORTRAIT);
        } else if (params != null) {
            setOrientation(params.orientation);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    (params != null && params.screenShowNotch) ?
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES :
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
        }

        final boolean hasBackground = params != null && ProfilesManager.getBackgroundFile(configDir).exists();

        // Always go through the extended setter so that switching the
        // free-form layout on/off doesn't require a separate code path.
        final boolean customLayout = params != null && params.screenCustomLayout;
        final float cx1 = params != null ? params.screenCustomX1 : 0.0f;
        final float cy1 = params != null ? params.screenCustomY1 : 0.0f;
        final float cx2 = params != null ? params.screenCustomX2 : 1.0f;
        final float cy2 = params != null ? params.screenCustomY2 : 1.0f;
        final float customScale = params != null ? params.screenScaleRatio : 100.0f;

        Emulator.setScreenParamsEx(
                params != null ? params.screenBackgroundColor : 0,
                params != null ? params.screenScaleRatio : 100,
                params != null ? params.screenScaleType : 0,
                params != null ? params.screenGravity : 2,
                customLayout,
                cx1, cy1, cx2, cy2,
                customScale,
                hasBackground ? ProfilesManager.getBackgroundPath(configDir.getAbsolutePath()) : "",
                params != null ? Math.max(0.0f, Math.min(params.screenBackgroundImageOpacity / 100.0f, 1.0f)) : 1.0f,
                params != null && hasBackground ? params.screenBackgroundImageKeepAspectRatio : false);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private void setOrientation(int orientation) {
        switch (orientation) {
            case ORIENTATION_AUTO:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
                break;
            case ORIENTATION_PORTRAIT:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                break;
            case ORIENTATION_LANDSCAPE:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                break;
            case ORIENTATION_DEFAULT:
            default:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                break;
        }
    }

    @Override
    public void openOptionsMenu() {
        if (!actionBarEnabled) {
            showSystemUI();
        }
        super.openOptionsMenu();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (actionBarEnabled) {
                showExitConfirmation();
            } else {
                openOptionsMenu();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void setTheme(String theme) {
        if (theme.equals("dark")) {
            setTheme(R.style.AppTheme_NoActionBar);
        } else {
            setTheme(R.style.AppTheme_Light_NoActionBar);
        }
    }

    private void showExitConfirmation() {
        AlertDialog.Builder alertBuilder = new AlertDialog.Builder(this);
        alertBuilder.setTitle(R.string.confirmation_required)
                .setMessage(R.string.force_close_confirmation)
                .setPositiveButton(android.R.string.ok, (d, w) -> Emulator.exitInstance())
                .setNegativeButton(android.R.string.cancel, null);
        alertBuilder.create().show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.emulator, menu);
        if (keyboard != null && !(keyboard instanceof FixedKeyboard)) {
            inflater.inflate(R.menu.emulator_keys, menu);
        }
        actionScreenshot = menu.findItem(R.id.action_screenshot);
        if (getSupportActionBar() != null && !getSupportActionBar().isShowing()) {
            actionScreenshot.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_exit) {
            showExitConfirmation();
        } else if (id == R.id.action_save_log) {
            saveLog();
        } else if (id == R.id.action_screenshot) {
            saveScreenshot();
        } else if (keyboard != null) {
            handleVkOptions(id);
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        EmulatorCamera.handleOrientationChangeForAllInstances();
        super.onConfigurationChanged(newConfig);
    }

    private void saveLog() {
        try {
            LogUtils.writeLog();
            Toast.makeText(this, R.string.log_saved, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
        }
    }

    private void saveScreenshot() {
        String destDir = Emulator.getScreenshotDir();
        File destDirFile = new File(destDir);

        if (!destDirFile.exists()) {
            destDirFile.mkdirs();
        }

        SimpleDateFormat fileNameDateFormat = new SimpleDateFormat("yyyyMMdd-hhmmss");
        String title = getSupportActionBar() != null ? getSupportActionBar().getTitle().toString() : "";
        String fileName = destDir + getString(R.string.screenshot) + "_" + title + "_" + fileNameDateFormat.format(new Date()) + ".png";

        if (Emulator.saveScreenshotTo(fileName)) {
            Toast.makeText(this, getString(R.string.take_screenshot_success, fileName), Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, R.string.take_screenshot_fail, Toast.LENGTH_LONG).show();
        }
    }

    private void handleVkOptions(int id) {
        if (id == R.id.action_layout_edit_mode) {
            keyboard.setLayoutEditMode(VirtualKeyboard.LAYOUT_KEYS);
            Toast.makeText(this, R.string.layout_edit_mode,
                    Toast.LENGTH_SHORT).show();
        } else if (id == R.id.action_layout_scale_mode) {
            keyboard.setLayoutEditMode(VirtualKeyboard.LAYOUT_SCALES);
            Toast.makeText(this, R.string.layout_scale_mode,
                    Toast.LENGTH_SHORT).show();
        } else if (id == R.id.action_layout_edit_finish) {
            keyboard.setLayoutEditMode(VirtualKeyboard.LAYOUT_EOF);
            Toast.makeText(this, R.string.layout_edit_finished,
                    Toast.LENGTH_SHORT).show();
        } else if (id == R.id.action_layout_switch) {
            showSetLayoutDialog();
        } else if (id == R.id.action_hide_buttons) {
            showHideButtonDialog();
        } else if (id == R.id.action_edit_screen_position) {
            toggleInGameScreenPositionEditor();
        }
    }

    /**
     * Show (or remove) the in-game free-form screen position editor.
     * <p>The editor is a transparent overlay placed on top of the
     * SurfaceView. As the user drags the four corners or the screen
     * body, a live callback pushes the new rectangle to
     * {@link Emulator#setScreenParamsEx} so the running game is
     * redrawn with the new size/position immediately — no need to
     * restart the app to see the result.</p>
     */
    private void toggleInGameScreenPositionEditor() {
        if (inGameEditor != null) {
            finishInGameScreenPositionEditor();
            return;
        }
        if (emulatorContainer == null || params == null) {
            return;
        }

        // Switch on the free-form layout. Even before the user touches
        // anything, the live callback below will keep the native side
        // in sync with the editor's current rectangle.
        params.screenCustomLayout = true;

        ScreenPositionEditor editor = new ScreenPositionEditor(this);
        // Seed from current profile, with a small inset on first use
        // so all four handles are reachable on screen.
        float x1 = params.screenCustomX1;
        float y1 = params.screenCustomY1;
        float x2 = params.screenCustomX2;
        float y2 = params.screenCustomY2;
        if (x1 == 0f && y1 == 0f && x2 == 1f && y2 == 1f) {
            x1 = 0.15f; y1 = 0.15f; x2 = 0.85f; y2 = 0.85f;
        }
        editor.setRect(x1, y1, x2, y2);

        // Make sure the editor is on top of the SurfaceView but
        // doesn't intercept events outside its rectangle — the editor
        // itself returns false from onTouch when the user doesn't
        // touch a handle / the body, so the SurfaceView underneath
        // can still receive touches outside the rect.
        FrameLayout.LayoutParams editorLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        emulatorContainer.addView(editor, emulatorContainer.getChildCount(), editorLp);

        // Floating "Done" button so the user can leave edit mode.
        Button done = new Button(this);
        done.setText(R.string.pref_edit_screen_position_finish);
        done.setAllCaps(false);
        FrameLayout.LayoutParams doneLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        doneLp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
        doneLp.bottomMargin = dp(48);
        emulatorContainer.addView(done, emulatorContainer.getChildCount(), doneLp);

        done.setOnClickListener(v -> finishInGameScreenPositionEditor());

        editor.setListener((nx1, ny1, nx2, ny2, confirm) -> pushScreenRectToNative(nx1, ny1, nx2, ny2));

        inGameEditor = editor;
        inGameEditorDone = done;
        Toast.makeText(this, R.string.pref_edit_screen_position_started, Toast.LENGTH_SHORT).show();
    }

    /**
     * Tear down the in-game editor overlay, persist the new rectangle
     * to the profile, and apply it natively.
     */
    private void finishInGameScreenPositionEditor() {
        if (inGameEditor == null) {
            return;
        }
        float x1 = inGameEditor.getX1();
        float y1 = inGameEditor.getY1();
        float x2 = inGameEditor.getX2();
        float y2 = inGameEditor.getY2();

        if (params != null) {
            params.screenCustomLayout = true;
            params.screenCustomX1 = x1;
            params.screenCustomY1 = y1;
            params.screenCustomX2 = x2;
            params.screenCustomY2 = y2;
            ProfilesManager.saveConfig(params);
        }

        pushScreenRectToNative(x1, y1, x2, y2);

        if (emulatorContainer != null) {
            emulatorContainer.removeView(inGameEditor);
            if (inGameEditorDone != null) {
                emulatorContainer.removeView(inGameEditorDone);
            }
        }
        inGameEditor = null;
        inGameEditorDone = null;
        Toast.makeText(this, R.string.pref_screen_position_saved, Toast.LENGTH_SHORT).show();
    }

    /**
     * Forward the four normalized corners to the native renderer. Used
     * by the in-game editor on every drag move.
     */
    private void pushScreenRectToNative(float x1, float y1, float x2, float y2) {
        if (params == null || configDir == null) return;
        final boolean hasBackground = ProfilesManager.getBackgroundFile(configDir).exists();
        Emulator.setScreenParamsEx(
                params.screenBackgroundColor,
                params.screenScaleRatio,
                params.screenScaleType,
                params.screenGravity,
                true,
                x1, y1, x2, y2,
                params.screenScaleRatio,
                hasBackground ? ProfilesManager.getBackgroundPath(configDir.getAbsolutePath()) : "",
                Math.max(0.0f, Math.min(params.screenBackgroundImageOpacity / 100.0f, 1.0f)),
                hasBackground && params.screenBackgroundImageKeepAspectRatio);
    }

    private int dp(int v) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(v * density);
    }

    private void showSetLayoutDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.layout_switch)
                .setSingleChoiceItems(keyboard.getLayoutNames(), -1,
                        (dialogInterface, i) -> keyboard.setLayout(i))
                .setPositiveButton(android.R.string.ok, null);
        builder.show();
    }

    private void showHideButtonDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.hide_buttons)
                .setMultiChoiceItems(keyboard.getKeyNames(), keyboard.getKeyVisibility(),
                        (dialogInterface, i, b) -> keyboard.setKeyVisibility(i, b))
                .setPositiveButton(android.R.string.ok, null);
        builder.show();
    }

    private void setActionBar(String title) {
        ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
        LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) toolbar.getLayoutParams();
        actionBar.setTitle(title);
        layoutParams.height = (int) (getToolBarHeight() / 1.5);
    }

    private int getToolBarHeight() {
        int[] attrs = new int[]{android.R.attr.actionBarSize};
        TypedArray ta = obtainStyledAttributes(attrs);
        int toolBarHeight = ta.getDimensionPixelSize(0, -1);
        ta.recycle();
        return toolBarHeight;
    }

    private void setVirtualKeyboard(String appDirName) {
        if (params == null) {
            return;
        }
        
        int vkType = params.vkType;
        if (vkType == VirtualKeyboard.CUSTOMIZABLE_TYPE) {
            keyboard = new VirtualKeyboard(this);
        } else if (vkType == VirtualKeyboard.PHONE_DIGITS_TYPE) {
            keyboard = new FixedKeyboard(this);
        } else {
            keyboard = new FixedKeyboard(this);
        }
        keyboard.setHideDelay(params.vkHideDelay);
        keyboard.setHasHapticFeedback(params.vkFeedback);
        keyboard.setButtonShape(params.vkButtonShape);

        File keyLayoutFile = new File(Emulator.getConfigsDir(),
                appDirName + Emulator.APP_KEY_LAYOUT_FILE);
        if (keyLayoutFile.exists()) {
            try {
                FileInputStream fis = new FileInputStream(keyLayoutFile);
                DataInputStream dis = new DataInputStream(fis);
                keyboard.readLayout(dis);
                fis.close();
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
        }

        // Per-color alpha: each VK color gets its own alpha byte now. The legacy
        // params.vkAlpha is preserved for backwards compatibility but only
        // acts as a fallback when a color's per-color alpha wasn't set.
        keyboard.setColor(VirtualKeyboard.BACKGROUND, params.getEffectiveBgAlpha() << 24 | params.vkBgColor);
        keyboard.setColor(VirtualKeyboard.FOREGROUND, params.getEffectiveFgAlpha() << 24 | params.vkFgColor);
        keyboard.setColor(VirtualKeyboard.BACKGROUND_SELECTED, params.getEffectiveBgAlphaSelected() << 24 | params.vkBgColorSelected);
        keyboard.setColor(VirtualKeyboard.FOREGROUND_SELECTED, params.getEffectiveFgAlphaSelected() << 24 | params.vkFgColorSelected);
        keyboard.setColor(VirtualKeyboard.OUTLINE, params.getEffectiveOutlineAlpha() << 24 | params.vkOutlineColor);
        overlayView.setOverlay(keyboard);
        keyboard.setView(overlayView);

        keyboard.setLayoutListener(vk -> {
            try {
                File parentFile = new File(Emulator.getConfigsDir(), appDirName);
                if (!parentFile.exists()) {
                    parentFile.mkdirs();
                }
                FileOutputStream fos = new FileOutputStream(keyLayoutFile);
                DataOutputStream dos = new DataOutputStream(fos);
                vk.writeLayout(dos);
                fos.close();
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
        });
    }

    private void hideSystemUI() {
        int flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        if (!statusBarEnabled) {
            flags |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_FULLSCREEN;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void showSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    private void updateScreenSize() {
        RectF screen = new RectF(0, 0, displayWidth, displayHeight);
        if (keyboard != null) {
            keyboard.resize(screen, screen);
        }
    }

    private int convertAndroidKeyCode(int keyCode) {
        return androidToSymbian != null ? androidToSymbian.get(keyCode, Integer.MAX_VALUE) : Integer.MAX_VALUE;
    }

    private void onPermissionResult(Map<String, Boolean> status) {
        permissionsLauncherDone.release();
    }

    public void requestPermissionsAndWait(String[] permissions) throws InterruptedException {
        runOnUiThread(() -> permissionsLauncher.launch(permissions));
        permissionsLauncherDone.acquire();
    }

    private class ViewCallbacks implements View.OnTouchListener, SurfaceHolder.Callback, SurfaceHolder.Callback2, View.OnKeyListener {
        private final View view;
        private final FrameLayout rootView;

        public ViewCallbacks(View view) {
            this.view = view;
            rootView = ((Activity) view.getContext()).findViewById(R.id.emulator_frame);
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            // Ignore it
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Rect offsetViewBounds = new Rect(0, 0, width, height);
            if (rootView != null) {
                rootView.offsetDescendantRectToMyCoords(view, offsetViewBounds);
            }
            if (overlayView != null) {
                overlayView.setTargetBounds(offsetViewBounds);
            }
            displayWidth = width;
            displayHeight = height;
            updateScreenSize();

            Emulator.surfaceChanged(holder.getSurface(), width, height);

            // Delay game launch to ensure graphics are fully initialized
            // This prevents black screen issues caused by race conditions.
            // launchApp() is dispatched onto the background native-call
            // executor so the UI thread is never held up by symsys
            // initialisation (which used to trigger ANR).
            if (!launched) {
                view.post(() -> {
                    if (!launched) {
                        // First ensure keyboard is visible before launching game
                        if (keyboard != null) {
                            keyboard.show();
                        }
                        // Small delay to let surface stabilize
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        runNativeCall(() -> Emulator.launchApp((int) uid));
                        launched = true;
                    }
                });
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Emulator.surfaceDestroyed();
        }

        @Override
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            switch (event.getAction()) {
                case KeyEvent.ACTION_DOWN:
                    return onKeyDown(keyCode, event);
                case KeyEvent.ACTION_UP:
                    return onKeyUp(keyCode, event);
            }
            return false;
        }

        public boolean onKeyDown(int keyCode, KeyEvent event) {
            keyCode = convertAndroidKeyCode(keyCode);
            if (keyCode == Integer.MAX_VALUE) {
                return false;
            }
            if (event.getRepeatCount() == 0) {
                if (keyboard == null || !keyboard.keyPressed(keyCode)) {
                    Emulator.pressKey(keyCode, 0);
                }
            }
            return true;
        }

        public boolean onKeyUp(int keyCode, KeyEvent event) {
            keyCode = convertAndroidKeyCode(keyCode);
            if (keyCode == Integer.MAX_VALUE) {
                return false;
            }
            if (keyboard == null || !keyboard.keyReleased(keyCode)) {
                Emulator.pressKey(keyCode, 1);
            }
            return true;
        }

        @Override
        @SuppressLint("ClickableViewAccessibility")
        public boolean onTouch(View v, MotionEvent event) {
            // Check if params is null or touch input is disabled
            boolean touchEnabled = (params != null) && params.touchInput;
            
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // Only show keyboard if it's not already visible and game is launched
                    if (keyboard != null && launched) {
                        keyboard.show();
                    }
                case MotionEvent.ACTION_POINTER_DOWN:
                    int index = event.getActionIndex();
                    int id = event.getPointerId(index);
                    float x = event.getX(index);
                    float y = event.getY(index);
                    float z = event.getPressure(index) * 0x7FFFFFFF;    // Max pressure
                    if ((keyboard == null || !keyboard.pointerPressed(id, x, y)) && touchEnabled) {
                        Emulator.touchScreen((int) x, (int) y, (int)z, 0, id);
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    int pointerCount = event.getPointerCount();
                    int historySize = event.getHistorySize();
                    for (int h = 0; h < historySize; h++) {
                        for (int p = 0; p < pointerCount; p++) {
                            id = event.getPointerId(p);
                            x = event.getHistoricalX(p, h);
                            y = event.getHistoricalY(p, h);
                            z = event.getHistoricalPressure(p, h) * 0x7FFFFFFF;    // Max pressure
                            if ((keyboard == null || !keyboard.pointerDragged(id, x, y)) && touchEnabled) {
                                Emulator.touchScreen((int) x, (int) y, (int)z, 1, id);
                            }
                        }
                    }
                    for (int p = 0; p < pointerCount; p++) {
                        id = event.getPointerId(p);
                        x = event.getX(p);
                        y = event.getY(p);
                        z = event.getPressure(p) * 0x7FFFFFFF;    // Max pressure
                        if ((keyboard == null || !keyboard.pointerDragged(id, x, y)) && touchEnabled) {
                            Emulator.touchScreen((int) x, (int) y, (int)z, 1, id);
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (keyboard != null) {
                        keyboard.hide();
                    }
                case MotionEvent.ACTION_POINTER_UP:
                    index = event.getActionIndex();
                    id = event.getPointerId(index);
                    x = event.getX(index);
                    y = event.getY(index);
                    if ((keyboard == null || !keyboard.pointerReleased(id, x, y)) && touchEnabled) {
                        Emulator.touchScreen((int) x, (int) y, (int)0, 2, id);
                    }
                    break;
                default:
                    return false;
            }
            return true;
        }

        @Override
        public void surfaceRedrawNeeded(SurfaceHolder surfaceHolder) {
            Emulator.surfaceRedrawNeeded();
        }
    }
}
