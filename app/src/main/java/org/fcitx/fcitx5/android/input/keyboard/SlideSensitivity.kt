/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/**
 * Google Pinyin "滑动输入的敏感度": multiplies the swipe distance needed for swipe input
 */
enum class SlideSensitivity(override val stringRes: Int, val distanceRatio: Float) :
    ManagedPreferenceEnum {
    Low(R.string.slide_sensitivity_low, 3.0f),
    MediumLow(R.string.slide_sensitivity_medium_low, 1.5f),
    Normal(R.string.slide_sensitivity_normal, 1.0f),
    MediumHigh(R.string.slide_sensitivity_medium_high, 0.8f),
    High(R.string.slide_sensitivity_high, 0.5f);
}
