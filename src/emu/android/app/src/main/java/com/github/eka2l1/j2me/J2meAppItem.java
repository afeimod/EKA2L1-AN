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

import android.graphics.Bitmap;

/**
 * In-memory representation of an installed J2ME / MIDlet entry.
 *
 * The native side stores the canonical record in {@code j2me\applist.db};
 * this DTO is what we hand to the UI for rendering and to
 * {@link com.github.eka2l1.emu.Emulator#launchJ2meApp(long)} for
 * dispatching a run.
 *
 * Keeping it deliberately small — we don't try to mirror the full
 * native app_entry struct because the UI only needs (id, title, author,
 * version, icon). Anything else is fetched lazily on demand.
 */
public class J2meAppItem {
    private final long appId;
    private final String title;
    private final String author;
    private final String version;
    private final String iconPath;
    private Bitmap iconBitmap;

    public J2meAppItem(long appId, String title, String author, String version, String iconPath) {
        this.appId = appId;
        this.title = title == null ? "" : title;
        this.author = author == null ? "" : author;
        this.version = version == null ? "" : version;
        this.iconPath = iconPath == null ? "" : iconPath;
    }

    public long getAppId() {
        return appId;
    }

    public String getTitle() {
        return title;
    }

    public String getAuthor() {
        return author;
    }

    public String getVersion() {
        return version;
    }

    /** Relative icon path inside the emulator data dir, or "" if absent. */
    public String getIconPath() {
        return iconPath;
    }

    public Bitmap getIconBitmap() {
        return iconBitmap;
    }

    public void setIconBitmap(Bitmap iconBitmap) {
        this.iconBitmap = iconBitmap;
    }

    @Override
    public String toString() {
        return "J2meAppItem{id=" + appId + ", title=" + title +
                ", version=" + version + ", author=" + author + "}";
    }
}