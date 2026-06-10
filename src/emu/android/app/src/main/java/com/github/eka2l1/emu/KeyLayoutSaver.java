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

package com.github.eka2l1.emu;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists the on-screen control layout to one or more files in a
 * debounced, atomic, deduplicated way.
 *
 * <p>The existing code wrote the layout file synchronously on every layout
 * change. Dragging a key fires a {@code layoutChanged} callback for every
 * pixel of motion, so the old code was hammering the disk and could
 * truncate the file mid-write. {@link KeyLayoutSaver} fixes three problems:
 *
 * <ol>
 *     <li><b>Debounce.</b> Repeated requests within {@link #DEBOUNCE_MS}
 *         collapse into a single write.</li>
 *     <li><b>Deduplicate.</b> Each file's serialised bytes are hashed;
 *         writes are skipped if nothing actually changed.</li>
 *     <li><b>Atomic.</b> Writes go through a {@code .tmp} sibling first and
 *         are renamed on success, so a crash mid-write can't corrupt the
 *         existing layout.</li>
 * </ol>
 *
 * <p>Callers should:
 * <pre>
 *     saver = new KeyLayoutSaver();
 *     saver.addTarget(keyboardLayoutFile, keyboard::writeLayout);
 *     saver.addTarget(joystickLayoutFile, () -&gt; joystick.writeLayout(dos));
 *     // Any time the layout changes:
 *     saver.requestSave();
 *     // On activity stop / destruction:
 *     saver.flushNow();
 * </pre>
 *
 * <p>One {@link KeyLayoutSaver} instance can drive multiple target files
 * (e.g. the keyboard layout and the joystick layout) so a single
 * {@code layoutChanged} signal persists both at once.
 */
public class KeyLayoutSaver {

    private static final String TAG = "KeyLayoutSaver";

    /** Debounce window: collapse bursts of edits into one disk write. */
    private static final long DEBOUNCE_MS = 400L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final List<Target> targets = new ArrayList<>();
    private boolean destroyed;

    /** Register another file to be persisted when {@link #requestSave()}
     *  fires. The provider is invoked from the main thread. */
    public void addTarget(File targetFile, ByteProvider provider) {
        if (targetFile == null || provider == null) return;
        synchronized (targets) {
            targets.add(new Target(targetFile, provider));
        }
    }

    /** Remove a previously registered target. */
    public void removeTarget(File targetFile) {
        if (targetFile == null) return;
        synchronized (targets) {
            for (int i = targets.size() - 1; i >= 0; i--) {
                if (targetFile.equals(targets.get(i).file)) {
                    targets.remove(i);
                }
            }
        }
    }

    /** Schedule a save for every registered target. Safe to call as often
     *  as you like — it coalesces. */
    public void requestSave() {
        if (destroyed) return;
        handler.removeCallbacks(writeRunnable);
        handler.postDelayed(writeRunnable, DEBOUNCE_MS);
    }

    /** Drop any pending save and write the latest snapshot of every
     *  target synchronously. */
    public void flushNow() {
        if (destroyed) return;
        handler.removeCallbacks(writeRunnable);
        performSave();
    }

    /** Stop scheduling new writes. */
    public void destroy() {
        destroyed = true;
        handler.removeCallbacks(writeRunnable);
    }

    // ---- internals ----

    private final Runnable writeRunnable = new Runnable() {
        @Override
        public void run() {
            performSave();
        }
    };

    private void performSave() {
        List<Target> snapshot;
        synchronized (targets) {
            snapshot = new ArrayList<>(targets);
        }
        for (Target t : snapshot) {
            try {
                t.save();
            } catch (Throwable th) {
                Log.e(TAG, "save failed for " + t.file, th);
            }
        }
    }

    /** Writes {@code bytes} to {@code target} atomically and with dedup. */
    private static void writeAtomically(File target, byte[] bytes, String[] hashRef) {
        if (bytes == null || bytes.length == 0) return;

        String hash = sha1(bytes);
        if (hash.equals(hashRef[0])) {
            // Nothing actually changed since last successful write.
            return;
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.w(TAG, "could not create parent dir: " + parent);
            return;
        }

        File tmp = new File(parent, target.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(bytes);
            fos.flush();
            fos.getFD().sync();
        } catch (IOException e) {
            Log.e(TAG, "write to tmp failed; leaving previous layout untouched", e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }

        if (!tmp.renameTo(target)) {
            try {
                java.nio.file.Files.move(
                        tmp.toPath(),
                        target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Throwable moveErr) {
                Log.e(TAG, "atomic rename failed", moveErr);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return;
            }
        }

        hashRef[0] = hash;
        Log.d(TAG, "layout saved to " + target + " (" + bytes.length + " bytes)");
    }

    private static String sha1(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is mandatory in every JVM — never happens.
            return Integer.toHexString(data.length);
        }
    }

    /** Produces bytes to be written to disk. */
    public interface ByteProvider {
        byte[] produceBytes() throws IOException;
    }

    /** Adapter for callers that want to write directly into a stream. */
    public static ByteProvider fromStream(final StreamWriter writer) {
        return () -> {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
            try (DataOutputStream dos = new DataOutputStream(baos)) {
                writer.writeTo(dos);
            }
            return baos.toByteArray();
        };
    }

    /** Stream-style provider for callers that don't want to manage the
     *  intermediate byte buffer. */
    public interface StreamWriter {
        void writeTo(DataOutputStream dos) throws IOException;
    }

    private static final class Target {
        final File file;
        final ByteProvider provider;
        /** Wrapped in a single-element array so the lambda inside the saver
         *  can mutate it without making it a field on Target. */
        final String[] lastHashRef = new String[]{null};

        Target(File file, ByteProvider provider) {
            this.file = file;
            this.provider = provider;
        }

        void save() throws IOException {
            byte[] bytes = provider.produceBytes();
            writeAtomically(file, bytes, lastHashRef);
        }
    }
}