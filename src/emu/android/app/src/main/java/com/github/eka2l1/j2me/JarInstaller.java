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

package com.github.eka2l1.j2me;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.github.eka2l1.emu.Emulator;
import com.github.eka2l1.util.FileUtils;

import java.io.File;
import java.io.IOException;

import io.reactivex.Single;
import io.reactivex.SingleOnSubscribe;
import io.reactivex.schedulers.Schedulers;

/**
 * Installs a user-picked JAR/JAD into the S60v1 KMID runner.
 *
 * Flow:
 * 1. The user picks a JAR via FilteredFilePickerActivity (any URI scheme).
 * 2. We copy the source bytes into the app-private emulator directory
 *    under a stable name so the JNI bridge can read it with plain
 *    {@code fopen()}. The native code handles content:// URIs but only
 *    via the storage helper path — for the install descriptor parser
 *    we prefer a local file because it sidesteps a class of SAF
 *    permission issues on Android 13+.
 * 3. We hand the local path to {@link Emulator#installJ2meApp} which
 *    delegates to {@code eka2l1::j2me::install} and returns the
 *    {@code install_error} code.
 *
 * On success we emit a {@link Emulator.J2meInstallResult}; on failure
 * we propagate the {@link Emulator.J2meInstallException} so the UI
 * fragment can show a precise, code-mapped message.
 */
public final class JarInstaller {

    private static final String TAG = "JarInstaller";

    /** Filename used inside the emulator dir for staged JARs. */
    private static final String STAGE_PREFIX = "staged_";

    private JarInstaller() {
        // utility
    }

    /**
     * Build the install Single. Always runs on the IO scheduler — the
     * file copy and the JNI call both must not happen on the UI thread.
     *
     * @param context  Used for ContentResolver access.
     * @param emulatorDir Absolute path to the emulator data dir; the
     *                   staged JAR lives there temporarily.
     * @param sourceUri Either a file:// or content:// URI returned by
     *                  the picker. file:// paths are used directly if
     *                  accessible; everything else is copied.
     */
    public static Single<Emulator.J2meInstallResult> install(
            final Context context,
            final String emulatorDir,
            final Uri sourceUri) {

        return Single.create((SingleOnSubscribe<Emulator.J2meInstallResult>) emitter -> {
            final File stageRoot = new File(emulatorDir);
            if (!stageRoot.exists() && !stageRoot.mkdirs()) {
                emitter.onError(new IOException("Cannot create emulator dir: " + emulatorDir));
                return;
            }

            final File stagedJar = stageInLocalFile(context, emulatorDir, sourceUri);
            if (stagedJar == null) {
                emitter.onError(new IOException("Cannot stage JAR from " + sourceUri));
                return;
            }

            Log.i(TAG, "Staged JAR at " + stagedJar.getAbsolutePath() +
                    " (size=" + stagedJar.length() + ")");

            // Hand the local path to the JNI side. Errors are surfaced
            // through Emulator.J2meInstallException, which carries the
            // install_error code for the UI to map.
            try {
                final long[] outAppId = new long[1];
                final String[] outInfo = new String[]{"", "", ""};
                final int err = Emulator.installJ2meApp(stagedJar.getAbsolutePath(), outAppId, outInfo);
                if (err != Emulator.INSTALL_J2ME_SUCCESS) {
                    emitter.onError(new Emulator.J2meInstallException(err));
                    return;
                }
                emitter.onSuccess(new Emulator.J2meInstallResult(
                        outAppId[0], outInfo[0], outInfo[1], outInfo[2]));
            } catch (Throwable t) {
                // Defensive — the JNI call could in theory raise on a
                // broken native state. Convert to a J2meInstallException
                // with the platform code so the UI handles it the same.
                Log.e(TAG, "installJ2meApp threw", t);
                emitter.onError(new Emulator.J2meInstallException(
                        Emulator.INSTALL_J2ME_ERROR_PLATFORM));
            } finally {
                // Best-effort cleanup. We *could* keep the staged file
                // around for a retry, but it just bloats the data dir;
                // a failed install usually means the user will pick a
                // different file next.
                try {
                    //noinspection ResultOfMethodCallIgnored
                    stagedJar.delete();
                } catch (Throwable ignored) {
                }
            }
        }).subscribeOn(Schedulers.io());
    }

    /**
     * Make a local copy of the user-picked JAR.
     *
     * - For {@code file://} URIs whose path actually exists on disk
     *   (legacy storage case on Android <=9), we skip the copy and use
     *   the source path directly to avoid wasting I/O.
     * - For {@code content://} URIs (SAF on Android 10+) we always copy
     *   because the native side's content URI helper needs the
     *   ContentResolver reference we don't easily expose across JNI for
     *   install-time.
     */
    private static File stageInLocalFile(final Context context, final String emulatorDir, final Uri uri) {
        if (uri == null) {
            return null;
        }

        // Legacy direct-path shortcut: file:// URIs that already point
        // at something readable on disk.
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            final String p = uri.getPath();
            if (p != null) {
                final File existing = new File(p);
                if (existing.exists() && existing.canRead() && existing.length() > 0) {
                    return existing;
                }
            }
        }

        // Otherwise copy into the emulator data dir.
        final String suffix = guessExtension(uri);
        final File staged = new File(emulatorDir,
                STAGE_PREFIX + System.currentTimeMillis() + suffix);
        try {
            FileUtils.copyFileFromURI(context, uri.toString(), staged);
        } catch (IOException ioe) {
            Log.w(TAG, "Failed to stage " + uri + ": " + ioe.getMessage());
            //noinspection ResultOfMethodCallIgnored
            staged.delete();
            return null;
        }
        if (!staged.exists() || staged.length() == 0) {
            //noinspection ResultOfMethodCallIgnored
            staged.delete();
            return null;
        }
        return staged;
    }

    private static String guessExtension(final Uri uri) {
        final String s = uri.toString().toLowerCase();
        if (s.endsWith(".jad")) {
            return ".jad";
        }
        if (s.endsWith(".kjx")) {
            return ".kjx";
        }
        return ".jar";
    }
}