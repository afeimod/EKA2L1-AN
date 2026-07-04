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

import com.github.eka2l1.emu.Emulator;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin convenience layer on top of {@link Emulator#getJ2meApps()} for
 * the J2ME UI. All heavy lifting — descriptor parsing, DB persistence,
 * icon extraction — happens in native code; this class just shapes the
 * raw "id|title|author|version|iconPath" strings into {@link J2meAppItem}
 * instances.
 */
public class J2meAppRepository {

    private final String emulatorDir;

    public J2meAppRepository(String emulatorDir) {
        this.emulatorDir = emulatorDir;
    }

    public List<J2meAppItem> getInstalledApps() {
        final String[] raw;
        try {
            raw = Emulator.getJ2meApps();
        } catch (Throwable t) {
            // Native side may not be initialised yet (e.g. user opens the
            // dialog before the bootstrap completes). Returning an empty
            // list is much friendlier than crashing the fragment.
            return new ArrayList<>();
        }
        if (raw == null) {
            return new ArrayList<>();
        }

        final List<J2meAppItem> items = new ArrayList<>(raw.length);
        for (final String entry : raw) {
            // pipe-delimited; the native side splits with std::string's
            // find, so '|' is reserved and we are safe.
            final String[] parts = entry.split("\\|", -1);
            if (parts.length < 5) {
                continue;
            }
            long id;
            try {
                id = Long.parseLong(parts[0]);
            } catch (NumberFormatException nfe) {
                continue;
            }
            items.add(new J2meAppItem(id, parts[1], parts[2], parts[3], parts[4]));
        }
        return items;
    }

    /**
     * Resolve an icon path returned by the native side into an absolute
     * File inside the emulator data directory. The native code stores
     * icons under {@code j2me\<name>_<version>_<iconfile>}, so the
     * returned path is relative — prepend the emulator root.
     */
    public File resolveIconFile(J2meAppItem item) {
        if (item.getIconPath() == null || item.getIconPath().isEmpty()) {
            return null;
        }
        final File f = new File(emulatorDir, item.getIconPath());
        return f.exists() ? f : null;
    }
}