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


import android.util.SparseIntArray;

import com.github.eka2l1.emu.overlay.VirtualKeyboard;
import com.github.eka2l1.util.SparseIntArrayAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;

import java.io.File;


public class ProfileModel {
    /**
     * True if this is a new profile (not yet saved to file)
     */
    public final transient boolean isNew;

    public transient File dir;

    @SerializedName("Version")
    public int version;

    @SerializedName("ScreenBackgroundColor")
    public int screenBackgroundColor;

    @SerializedName("ScreenBackgroundImageOpacity")
    public int screenBackgroundImageOpacity;

    @SerializedName("ScreenBackgroundImageKeepAspectRatio")
    public boolean screenBackgroundImageKeepAspectRatio;
    @SerializedName("ScreenScaleRatio")
    public int screenScaleRatio;

    @SerializedName("Orientation")
    public int orientation;

    @SerializedName("ScreenScaleType")
    public int screenScaleType;

    @SerializedName("ScreenGravity")
    public int screenGravity;

    /**
     * Use free-form screen layout. When true, the four corner offsets below
     * (screenCustomX1/Y1/X2/Y2, normalized 0..1) describe the screen rect
     * instead of {@link #screenGravity}. The native side will draw the
     * screen at that exact rectangle.
     */
    @SerializedName("ScreenCustomLayout")
    public boolean screenCustomLayout;

    /**
     * Normalized top-left corner of the screen rect (0..1 of window size).
     * Only used when {@link #screenCustomLayout} is true.
     */
    @SerializedName("ScreenCustomX1")
    public float screenCustomX1;

    /** Normalized top-left corner Y. */
    @SerializedName("ScreenCustomY1")
    public float screenCustomY1;

    /** Normalized bottom-right corner X. */
    @SerializedName("ScreenCustomX2")
    public float screenCustomX2;

    /** Normalized bottom-right corner Y. */
    @SerializedName("ScreenCustomY2")
    public float screenCustomY2;

    @SerializedName("ScreenShowNotch")
    public boolean screenShowNotch;

    @SerializedName("TouchInput")
    public boolean touchInput;

    @SerializedName("ShowKeyboard")
    public boolean showKeyboard;

    @SerializedName("VirtualKeyboardType")
    public int vkType;

    @SerializedName("ButtonShape")
    public int vkButtonShape;

    @SerializedName("VirtualKeyboardAlpha")
    public int vkAlpha;

    @SerializedName("VirtualKeyboardFeedback")
    public boolean vkFeedback;

    @SerializedName("VirtualKeyboardDelay")
    public int vkHideDelay;

    @SerializedName("VirtualKeyboardColorBackground")
    public int vkBgColor;

    @SerializedName("VirtualKeyboardColorBackgroundSelected")
    public int vkBgColorSelected;

    @SerializedName("VirtualKeyboardColorForeground")
    public int vkFgColor;

    @SerializedName("VirtualKeyboardColorForegroundSelected")
    public int vkFgColorSelected;

    @SerializedName("VirtualKeyboardColorOutline")
    public int vkOutlineColor;

    @JsonAdapter(SparseIntArrayAdapter.class)
    @SerializedName("KeyMappings")
    public SparseIntArray keyMappings;

    @SuppressWarnings("unused") // Gson uses default constructor if present
    public ProfileModel() {
        isNew = false;
    }

    public ProfileModel(File dir) {
        this.dir = dir;
        this.isNew = true;
        version = 1;
        screenBackgroundColor = 0xD0D0D0;
        screenBackgroundImageOpacity = 50;
        screenBackgroundImageKeepAspectRatio = true;
        screenScaleType = 1;
        screenGravity = 1;
        screenCustomLayout = false;
        screenCustomX1 = 0.0f;
        screenCustomY1 = 0.0f;
        screenCustomX2 = 1.0f;
        screenCustomY2 = 1.0f;
        screenScaleRatio = 100;
        screenShowNotch = false;

        showKeyboard = true;
        touchInput = true;

        vkButtonShape = VirtualKeyboard.ROUND_RECT_SHAPE;
        vkAlpha = 64;

        vkBgColor = 0xD0D0D0;
        vkFgColor = 0x000080;
        vkBgColorSelected = 0x000080;
        vkFgColorSelected = 0xFFFFFF;
        vkOutlineColor = 0xFFFFFF;
    }
}
