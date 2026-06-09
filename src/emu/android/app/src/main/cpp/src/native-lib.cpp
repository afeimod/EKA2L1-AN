/*
 * Copyright (c) 2020 EKA2L1 Team
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

#include <jni.h>
#include <string>
#include <thread>
#include <chrono>

#include <android/state.h>
#include <android/thread.h>
#include <common/android/storage.h>
#include <common/fileutils.h>
#include <common/path.h>
#include <drivers/audio/audio.h>
#include <drivers/camera/backend/android/emulator_camera_jni_public.h>
#include <drivers/camera/camera_collection.h>
#include <drivers/camera/backend/android/camera_collection_android.h>
#include <drivers/graphics/graphics.h>

#include <common/android/jniutils.h>

#if EKA2L1_ARCH(ARM)
#include <cpu/12l1r/tests/test_entry.h>
#endif

#define CATCH_CONFIG_RUNNER
#define CATCH_CONFIG_ANDROID_LOGWRITE

#include <catch2/catch.hpp>

ANativeWindow *s_surf;
std::unique_ptr<eka2l1::android::emulator> state;
bool inited = false;

extern "C" jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    eka2l1::common::jni::virtual_machine = vm;

#if EKA2L1_ARCH(ARM)
    eka2l1::arm::r12l1::register_all_tests();
#endif

    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_github_eka2l1_emu_Emulator_setDirectory(
    JNIEnv *env,
    jclass clazz,
    jstring path) {
    const char *cstr = env->GetStringUTFChars(path, nullptr);
    std::string cpath = std::string(cstr);
    env->ReleaseStringUTFChars(path, cstr);

    const auto executable_directory = eka2l1::file_directory(cpath);
    eka2l1::common::set_current_directory(executable_directory);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_eka2l1_emu_Emulator_startNative(
    JNIEnv *env,
    jclass clazz) {
    eka2l1::common::jni::init_classloader();
    eka2l1::common::android::register_storage_callbacks(env);
    eka2l1::drivers::android::register_camera_callbacks(env);

    state = std::make_unique<eka2l1::android::emulator>();
    return emulator_entry(*state);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_eka2l1_emu_Emulator_ensureResourcesExtracted(
    JNIEnv *env,
    jclass clazz) {
    // Called from native code when it detects that the bundled 'resources/'
    // directory is missing on disk. On Android 11+ scoped-storage targets,
    // this typically means the legacy /sdcard/EKA2L1/ location was chosen
    // but is read-only, so the assets copy silently failed. We delegate to
    // Java, which knows the app-private getExternalFilesDir() path that
    // is always writable.
    jclass emulator_cls = eka2l1::common::jni::find_class("com/github/eka2l1/emu/Emulator");
    if (emulator_cls == nullptr) {
        return JNI_FALSE;
    }
    jmethodID mid = env->GetStaticMethodID(emulator_cls, "reExtractBundledResources", "()Z");
    if (mid == nullptr) {
        env->ExceptionClear();
        return JNI_FALSE;
    }
    jboolean ok = env->CallStaticBooleanMethod(emulator_cls, mid);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return JNI_FALSE;
    }
    return ok;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_github_eka2l1_emu_Emulator_getApps(
    JNIEnv *env,
    jclass clazz) {
    if (!state || !state->launcher) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }
    std::vector<std::string> info = state->launcher->get_apps();
    jobjectArray japps = env->NewObjectArray(static_cast<jsize>(info.size()),
        env->FindClass("java/lang/String"),
        nullptr);
    for (jsize i = 0; i < info.size(); ++i)
        env->SetObjectArrayElement(japps, i, env->NewStringUTF(info[i].c_str()));
    return japps;
}

static void redraw_screens_immediately() {
    if (!state || !state->window || !state->graphics_driver || !state->launcher) {
        return;
    }

    // This used to block on graphics_driver->wait_for() here, which held
    // the Android UI thread hostage whenever the graphics driver was
    // behind (the dreaded 'Input dispatching timed out' ANR). We now
    // simply submit the redraw command list and let the graphics thread
    // update present_status asynchronously. The next swap will publish
    // the new frame; we don't need to wait for it before returning to
    // the UI thread.
    eka2l1::drivers::graphics_command_builder builder;
    state->launcher->draw(builder, state->winserv ? state->winserv->get_screens() : nullptr,
                          state->window->window_fb_size().x,
                          state->window->window_fb_size().y);

    state->present_status = -100;
    builder.present(&state->present_status);

    eka2l1::drivers::command_list retrieved = builder.retrieve_command_list();
    state->graphics_driver->submit_command_list(retrieved);
}

extern "C" JNIEXPORT void JNICALL
Java_com_github_eka2l1_emu_Emulator_launchApp(JNIEnv *env, jclass clazz, jint uid) {
    // Launch the real app. Even though the Java side now dispatches this
    // onto a background executor, we still cap the wait on graphics init
    // so a stuck graphics thread can't deadlock the native bridge either.
    if (state) {
        // Wait at most 5s for graphics init. launchApp is heavy, but a
        // fully blocked graphics thread for 5s is still better than an
        // infinite hang.
        state->graphics_init_done.wait_for(static_cast<std::uint64_t>(5) * 1000 * 1000);
    }
    
    // Additional safety check with retry mechanism
    int retry_count = 0;
    while ((!state || !state->launcher || !state->window || !state->graphics_driver) && retry_count < 50) {
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
        retry_count++;
    }
    
    if (!state || !state->launcher || !state->window || !state->graphics_driver) {
        LOG_ERROR(eka2l1::FRONTEND_CMDLINE, "Attempted to launch app but emulator state is not fully initialized!");
        LOG_ERROR(eka2l1::FRONTEND_CMDLINE, "state={}, launcher={}, window={}, graphics_driver={}", 
            (void*)state.get(), 
            (void*)(state ? state->launcher.get() : nullptr),
            (void*)(state ? state->window.get() : nullptr),
            (void*)(state ? state->graphics_driver.get() : nullptr));
        return;
    }
    
    state->launcher->launch_app(uid);
}

extern "C" JNIEXPORT void JNICALL
Java_com_github_eka2l1_emu_Emulator_surfaceChanged(JNIEnv *env, jclass clazz, jobject surface,
    jint width, jint height) {
    if (!state) {
        return;
    }

    // Apply surface and initialize graphics if not already done
    ANativeWindow *new_surf = ANativeWindow_fromSurface(env, surface);
    s_surf = new_surf;

    if (!state->window) {
        // Window not ready yet, wait for graphics thread to initialize.
        // This path is invoked from the Android UI thread; we must not
        // busy-poll for a full second here or the system will throw ANR.
        LOG_INFO(eka2l1::FRONTEND_CMDLINE, "Waiting for graphics initialization...");

        // Wait at most 800ms on the graphics_init_done semaphore. If the
        // graphics thread hasn't signaled by then, give up — the user
        // will see a black screen but the UI thread stays responsive.
        const bool ready = state->graphics_init_done.wait_for(static_cast<std::uint64_t>(800) * 1000);

        if (!ready || !state->window) {
            LOG_ERROR(eka2l1::FRONTEND_CMDLINE, "Graphics initialization timed out from UI thread!");
            if (new_surf) {
                ANativeWindow_release(new_surf);
            }
            s_surf = nullptr;
            return;
        }
        LOG_INFO(eka2l1::FRONTEND_CMDLINE, "Graphics initialized, continuing...");
    }

    state->window->surface_changed(s_surf, width, height);

    if (!inited) {
        init_threads(*state);
        inited = true;
    } else {
        start_threads(*state);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_github_eka2l1_emu_Emulator_surfaceRedrawNeeded(JNIEnv *env, jclass clazz) {
    redraw_screens_immediately();
}

extern "C" JNIEXPORT void JNICALL
Java_com_github_eka2l1_emu_Emulator_surfaceDestroyed(JNIEnv *env, jclass clazz) {
    if (!state) {
        return;
    }

    pause_threads(*state);
    eka2l1::android::abort_pending_input_events();
    ANativeWindow_release(s_surf);
    s_surf = nullptr;
    if (state->window) {
        state->window->surface_changed(s_surf, 0, 0);
    }
}

// Lightweight lock-free input event queue shared between the JNI bridge
// (UI thread) and the os_thread. The UI thread writes via JNI, the
// os_thread drains it inside the main loop. This avoids the JNI bridge
// taking the kernel lock from the UI thread, which used to be the
// primary source of ANRs during touch / key bursts.
static constexpr std::size_t INPUT_RING_CAP = 512;

struct pending_input_event {
    std::int32_t kind_; // 0=key, 1=touch
    std::int32_t a_;
    std::int32_t b_;
    std::int32_t c_;
    std::int32_t d_;
    std::int32_t e_;
};

namespace eka2l1::android::detail {
    std::mutex g_input_ring_mtx;
    std::condition_variable g_input_ring_cv;
    pending_input_event g_input_ring[INPUT_RING_CAP];
    std::size_t g_input_ring_head = 0; // next write index
    std::size_t g_input_ring_tail = 0; // next read index
    std::atomic<bool> g_input_ring_aborted{false};

    static void enqueue_input_event(const pending_input_event &evt) {
        std::lock_guard<std::mutex> lg(g_input_ring_mtx);
        const std::size_t next = (g_input_ring_head + 1) % INPUT_RING_CAP;
        if (next == g_input_ring_tail) {
            // Ring is full — drop the oldest event so we always accept the
            // newest input. Input is per-frame state; coalescing here is
            // safer than blocking the UI thread.
            g_input_ring_tail = (g_input_ring_tail + 1) % INPUT_RING_CAP;
        }
        g_input_ring[g_input_ring_head] = evt;
        g_input_ring_head = next;
        g_input_ring_cv.notify_one();
    }

    static bool dequeue_input_event(pending_input_event &out, const std::chrono::milliseconds &timeout) {
        std::unique_lock<std::mutex> ul(g_input_ring_mtx);
        if (!g_input_ring_cv.wait_for(ul, timeout, []() {
                return g_input_ring_head != g_input_ring_tail || g_input_ring_aborted.load();
            })) {
            return false;
        }
        if (g_input_ring_aborted.load()) {
            return false;
        }
        if (g_input_ring_head == g_input_ring_tail) {
            return false;
        }
        out = g_input_ring[g_input_ring_tail];
        g_input_ring_tail = (g_input_ring_tail + 1) % INPUT_RING_CAP;
        return true;
    }
}

namespace eka2l1::android {
    void drain_pending_input_events_for(emulator &state) {
        pending_input_event evt;
        while (detail::dequeue_input_event(evt, std::chrono::milliseconds(0))) {
            if (evt.kind_ == 0) {
                press_key(state, evt.a_, evt.b_);
            } else if (evt.kind_ == 1) {
                touch_screen(state, evt.a_, evt.b_, evt.c_, evt.d_, evt.e_);
            }
        }
    }

    void abort_pending_input_events() {
        {
            std::lock_guard<std::mutex> lg(detail::g_input_ring_mtx);
            detail::g_input_ring_aborted.store(true);
        }
        detail::g_input_ring_cv.notify_all();
        detail::g_input_ring_aborted.store(false);
        detail::g_input_ring_head = 0;
        detail::g_input_ring_tail = 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_github_eka2l1_emu_Emulator_pressKey(JNIEnv *env, jclass clazz, jint key,
    jint keyState) {
    if (!state) {
        return;
    }
    pending_input_event evt;
    evt.kind_ = 0;
    evt.a_ = key;
    evt.b_ = keyState;
    evt.c_ = 0;
    evt.d_ = 0;
    evt.e_ = 0;
    eka2l1::android::detail::enqueue_input_event(evt);
}

extern "C" JNIEXPORT void JNICALL
Java_com_github_eka2l1_emu_Emulator_touchScreen(JNIEnv *env, jclass clazz, jint x, jint y,
    jint z, jint action, jint pointer_id) {
    if (!state) {
        return;
    }
    pending_input_event evt;
    evt.kind_ = 1;
    evt.a_ = x;
    evt.b_ = y;
    evt.c_ = z;
    evt.d_ = action;
    evt.e_ = pointer_id;
    eka2l1::android::detail::enqueue_input_event(evt);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_github_eka2l1_emu_Emulator_installApp(JNIEnv *env, jclass clazz, jstring path) {
    if (!state || !state->launcher) {
        return -1;
    }
    const char *cstr = env->GetStringUTFChars(path, nullptr);
    std::string cpath = std::string(cstr);
    env->ReleaseStringUTFChars(path, cstr);

    return state->launcher->install_app(cpath);
}

static jobjectArray retrieve_jni_string_array_from_vector(JNIEnv *env, const std::vector<std::string> &strings) {
    jobjectArray jdevices = env->NewObjectArray(static_cast<jsize>(strings.size()),
                                                env->FindClass("java/lang/String"),
                                                nullptr);
    for (jsize i = 0; i < strings.size(); ++i)
        env->SetObjectArrayElement(jdevices, i, env->NewStringUTF(strings[i].c_str()));
    return jdevices;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_github_eka2l1_emu_Emulator_getDevices(
    JNIEnv *env,
    jclass clazz) {
    if (!state || !state->launcher) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }
    return retrieve_jni_string_array_from_vector(env, state->launcher->get_devices());
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_github_eka2l1_emu_Emulator_getDeviceFirmwareCodes(JNIEnv *env, jclass clazz) {
    if (!state || !state->launcher) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }
    return retrieve_jni_string_array_from_vector(env, state->launcher->get_device_firwmare_codes());
}

extern "C" JNIEXPORT void JNICALL
Java_com_github_eka2l1_emu_Emulator_setCurrentDevice(JNIEnv *env, jclass clazz, jint id, jboolean is_temp) {
    if (!state || !state->launcher) {
        return;
    }
    state->launcher->set_current_device(id, is_temp);
}

extern "C" JNIEXPORT void JNICALL
Java_com_github_eka2l1_emu_Emulator_setDeviceName(JNIEnv *env, jclass clazz, jint id, jstring new_name) {
    if (!state || !state->launcher) {
        return;
    }
    const char *cstr = env->GetStringUTFChars(new_name, nullptr);
    state->launcher->set_device_name(id, cstr);
    env->ReleaseStringUTFChars(new_name, cstr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_github_eka2l1_emu_Emulator_rescanDevices(JNIEnv *env, jclass clazz) {
    if (!state || !state->launcher) {
        return;
    }
    state->launcher->rescan_devices();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_github_eka2l1_emu_Emulator_getCurrentDevice(JNIEnv *env, jclass clazz) {
    if (!state || !state->launcher) {
        return -1;
    }
    return state->launcher->get_current_device();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_github_eka2l1_emu_Emulator_installDevice(JNIEnv *env, jclass clazz, jstring rpkg_path,
    jstring rom_path, jboolean install_rpkg) {
    if (!state || !state->launcher) {
        return -1;
    }
    const char *cstr = env->GetStringUTFChars(rpkg_path, nullptr);
    std::string crpkg_path = std::string(cstr);
    env->ReleaseStringUTFChars(rpkg_path, cstr);
    cstr = env->GetStringUTFChars(rom_path, nullptr);
    std::string crom_path = std::string(cstr);
    env->ReleaseStringUTFChars(rom_path, cstr);

    return state->launcher->install_device(crpkg_path, crom_path, install_rpkg);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_github_eka2l1_emu_Emulator_doesRomNeedRPKG(JNIEnv *env, jclass clazz, jstring rom_path) {
    if (!state || !state->launcher) {
        return false;
    }
    const char *cstr = env->GetStringUTFChars(rom_path, nullptr);
    const bool result = state->launcher->does_rom_need_rpkg(cstr);

    env->ReleaseStringUTFChars(rom_path, cstr);
    return result;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_github_eka2l1_emu_Emulator_getPackages(
    JNIEnv *env,
    jclass clazz) {
    if (!state || !state->launcher) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }
    std::vector<std::string> info = state->launcher->get_packages();
    jobjectArray jpackages = env->NewObjectArray(static_cast<jsize>(info.size()),
        env->FindClass("java/lang/String"),
        nullptr);
    for (jsize i = 0; i < info.size(); ++i)
        env->SetObjectArrayElement(jpackages, i, env->NewStringUTF(info[i].c_str()));
    return jpackages;
}

extern "C" JNIEXPORT void JNICALL
Java_com_github_eka2l1_emu_Emulator_uninstallPackage(JNIEnv *env, jclass clazz, jint uid, jint ext_index) {
    if (!state || !state->launcher) {
        return;
    }
    state->launcher->uninstall_package(uid, ext_index);
}

extern "C" JNIEXPORT void JNICALL
Java_com_github_eka2l1_emu_Emulator_mountSdCard(JNIEnv *env, jclass clazz, jstring path) {
    if (!state || !state->launcher) {
        return;
    }
    const char *cstr = env->GetStringUTFChars(path, nullptr);
    std::string cpath = std::string(cstr);
    env->ReleaseStringUTFChars(path, cstr);

    state->launcher->mount_sd_card(cpath);
}

extern "C" JNIEXPORT void JNICALL
Java_com_github_eka2l1_emu_Emulator_loadConfig(JNIEnv *env, jclass clazz) {
    if (!state || !state->launcher) {
        return;
    }
    state->launcher->load_config();
}

extern "C" JNIEXPORT void JNICALL
Java_com_github_eka2l1_emu_Emulator_setLanguage(JNIEnv *env, jclass clazz, jint language_id) {
    if (!state || !state->launcher) {
        return;
    }
    state->launcher->set_language(language_id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_github_eka2l1_emu_Emulator_setRtosLevel(JNIEnv *env, jclass clazz, jint level) {
    if (!state || !state->launcher) {
        return;
    }
    state->launcher->set_rtos_level(level);
}

extern "C" JNIEXPORT void JNICALL
Java_com_github_eka2l1_emu_Emulator_updateAppSetting(JNIEnv *env, jclass clazz, jint uid) {
    if (!state || !state->launcher) {
        return;
    }
    state->launcher->update_app_setting(uid);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_github_eka2l1_emu_Emulator_getAppIcon(JNIEnv *env, jclass clazz, jlong uid) {
    if (!state || !state->launcher) {
        return nullptr;
    }
    jobjectArray jicons = state->launcher->get_app_icon(env, uid);
    return jicons;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_github_eka2l1_emu_Emulator_getLanguageIds(
    JNIEnv *env,
    jclass clazz) {
    if (!state || !state->launcher) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }
    std::vector<std::string> language_ids = state->launcher->get_language_ids();
    jobjectArray jlanguage_ids = env->NewObjectArray(static_cast<jsize>(language_ids.size()),
        env->FindClass("java/lang/String"),
        nullptr);
    for (jsize i = 0; i < language_ids.size(); ++i)
        env->SetObjectArrayElement(jlanguage_ids, i, env->NewStringUTF(language_ids[i].c_str()));
    return jlanguage_ids;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_github_eka2l1_emu_Emulator_getLanguageNames(
    JNIEnv *env,
    jclass clazz) {
    if (!state || !state->launcher) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }
    std::vector<std::string> language_names = state->launcher->get_language_names();
    jobjectArray jlanguage_names = env->NewObjectArray(static_cast<jsize>(language_names.size()),
        env->FindClass("java/lang/String"),
        nullptr);
    for (jsize i = 0; i < language_names.size(); ++i)
        env->SetObjectArrayElement(jlanguage_names, i, env->NewStringUTF(language_names[i].c_str()));
    return jlanguage_names;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_github_eka2l1_emu_Emulator_setScreenParams(JNIEnv *env, jclass clazz,
                                                    jint background_color, jint scale_ratio,
                                                    jint scale_type, jint gravity,
                                                    jstring bg_img_path, jfloat bg_img_opacity,
                                                    jboolean bg_img_keep_aspect) {
    // Backwards-compatible wrapper: the new free-form layout path forwards
    // its own arguments via setScreenParamsEx, so this entry point just
    // disables the custom layout.
    Java_com_github_eka2l1_emu_Emulator_setScreenParamsEx(env, clazz, background_color,
        scale_ratio, scale_type, gravity, 0,
        0.0f, 0.0f, 1.0f, 1.0f, 100.0f,
        bg_img_path, bg_img_opacity, bg_img_keep_aspect);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_github_eka2l1_emu_Emulator_setScreenParamsEx(JNIEnv *env, jclass clazz,
                                                      jint background_color, jint scale_ratio,
                                                      jint scale_type, jint gravity,
                                                      jboolean custom_layout,
                                                      jfloat cx1, jfloat cy1, jfloat cx2, jfloat cy2,
                                                      jfloat custom_scale_ratio,
                                                      jstring bg_img_path, jfloat bg_img_opacity,
                                                      jboolean bg_img_keep_aspect) {
    if (!state || !state->launcher) {
        return;
    }
    const char *cstr = env->GetStringUTFChars(bg_img_path, nullptr);
    std::string cpath = std::string(cstr);
    env->ReleaseStringUTFChars(bg_img_path, cstr);

    state->launcher->set_screen_params_ex(background_color, scale_ratio, scale_type, gravity,
        custom_layout != 0, cx1, cy1, cx2, cy2, custom_scale_ratio,
        cpath, bg_img_opacity, bg_img_keep_aspect != 0);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_github_eka2l1_emu_Emulator_runTest(JNIEnv *env, jclass clazz, jstring test_name) {
    const char *test_name_c = env->GetStringUTFChars(test_name, nullptr);

    const char *arguments[] = {
            "fake.exe",
            test_name_c
    };

    const int argument_count = 2;

    bool result = (Catch::Session().run(argument_count, arguments) == 0);
    env->ReleaseStringUTFChars(test_name, test_name_c);

    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_github_eka2l1_emu_Emulator_submitInput(JNIEnv *env, jclass clazz, jstring text) {
    if (!state || !state->launcher) {
        return;
    }
    const char *cstr = env->GetStringUTFChars(text, nullptr);
    std::string ctext = std::string(cstr);
    state->launcher->on_finished_text_input(ctext, false);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_github_eka2l1_emu_EmulatorCamera_onCaptureImageDelivered(JNIEnv *env, jclass clazz,
                                                                  jint index, jbyteArray raw_data,
                                                                  jint error_code) {
    eka2l1::drivers::camera::collection_android *collection = reinterpret_cast
            <eka2l1::drivers::camera::collection_android *>(eka2l1::drivers::camera::get_collection());

    if (collection) {
        jboolean is_data_copy = false;
        jsize data_size = env->GetArrayLength(raw_data);
        jbyte *data = env->GetByteArrayElements(raw_data, &is_data_copy);
        collection->handle_image_capture_delivered(index, data, static_cast<int>(data_size), error_code);
        env->ReleaseByteArrayElements(raw_data, data, 0);
    }
}
extern "C"
JNIEXPORT void JNICALL
Java_com_github_eka2l1_emu_EmulatorCamera_onFrameViewfinderDelivered(JNIEnv *env, jclass clazz,
                                                                     jint index,
                                                                     jbyteArray raw_data,
                                                                     jint error_code) {
    eka2l1::drivers::camera::collection_android *collection = reinterpret_cast
            <eka2l1::drivers::camera::collection_android *>(eka2l1::drivers::camera::get_collection());

    if (collection) {
        jboolean is_data_copy = false;
        jsize data_size = env->GetArrayLength(raw_data);
        jbyte *data = env->GetByteArrayElements(raw_data, &is_data_copy);
        collection->handle_frame_viewfinder_delivered(index, data, static_cast<int>(data_size), error_code);
        env->ReleaseByteArrayElements(raw_data, data, 0);
    }
}
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_github_eka2l1_emu_EmulatorCamera_doesCameraAllowNewFrame(JNIEnv *env, jclass clazz,
                                                                  jint index) {
    eka2l1::drivers::camera::collection_android *collection = reinterpret_cast
            <eka2l1::drivers::camera::collection_android *>(eka2l1::drivers::camera::get_collection());

    return collection ? collection->reserved_wants_new_frame(index) : false;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_github_eka2l1_emu_Emulator_installNGageGame(JNIEnv *env, jclass clazz, jstring path) {
    if (!state || !state->launcher) {
        return -1;
    }
    const char *cstr = env->GetStringUTFChars(path, nullptr);
    std::string cpath = std::string(cstr);
    env->ReleaseStringUTFChars(path, cstr);

    return state->launcher->install_ngage_game(cpath);
}
extern "C"
JNIEXPORT void JNICALL
Java_com_github_eka2l1_emu_Emulator_submitQuestionDialogResponse(JNIEnv *env, jclass clazz,
                                                                 jint value) {
    if (!state || !state->launcher) {
        return;
    }
    state->launcher->on_question_dialog_finished(value);
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_github_eka2l1_emu_Emulator_getSuccessInstalledLicenseGames(JNIEnv *env, jclass clazz) {
    if (!state || !state->launcher) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }
    return retrieve_jni_string_array_from_vector(env, state->launcher->get_success_installed_license_games());
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_github_eka2l1_emu_Emulator_getFailedInstalledLicenseGames(JNIEnv *env, jclass clazz) {
    if (!state || !state->launcher) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }
    return retrieve_jni_string_array_from_vector(env, state->launcher->get_failed_installed_license_games());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_github_eka2l1_emu_Emulator_installNG2Licenses(JNIEnv *env, jclass clazz, jstring content) {
    if (!state || !state->launcher) {
        return false;
    }
    const char *cstr = env->GetStringUTFChars(content, nullptr);
    std::string content_cpp = std::string(cstr);
    env->ReleaseStringUTFChars(content, cstr);

    return state->launcher->install_ng2_game_licenses(content_cpp);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_github_eka2l1_emu_Emulator_setCurrentMMCID(JNIEnv *env, jclass clazz, jstring new_mmcid) {
    if (!state || !state->launcher) {
        return;
    }
    const char *cstr = env->GetStringUTFChars(new_mmcid, nullptr);
    std::string mmc_id_str = std::string(cstr);
    env->ReleaseStringUTFChars(new_mmcid, cstr);

    state->launcher->set_current_mmc_id(mmc_id_str);
}
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_github_eka2l1_emu_Emulator_saveScreenshotTo(JNIEnv *env, jclass clazz, jstring file_path) {
    if (!state || !state->launcher) {
        return false;
    }
    const char *cstr = env->GetStringUTFChars(file_path, nullptr);
    std::string file_path_std = std::string(cstr);
    env->ReleaseStringUTFChars(file_path, cstr);

    return state->launcher->save_screenshot_to(file_path_std);
}
