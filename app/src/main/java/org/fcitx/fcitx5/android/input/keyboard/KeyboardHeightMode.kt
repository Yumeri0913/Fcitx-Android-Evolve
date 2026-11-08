/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.keyboard

import android.content.res.Configuration
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class KeyboardHeightMode(override val stringRes: Int) : ManagedPreferenceEnum {
    /** fixed height in dp, same as Google Pinyin 4.x on the same screen size */
    GooglePinyin(R.string.keyboard_height_mode_google_pinyin),

    /** percentage of screen height, configured by keyboard height preferences */
    Percent(R.string.keyboard_height_mode_percent);

    companion object {
        /**
         * Google Pinyin 4.x `keyboard_body_height`: the keys area below its 40dp candidate bar,
         * which corresponds to fcitx's keyboard window below the 40dp KawaiiBar.
         * Values come from the `dimens.xml` files of Google Pinyin 4.5.2.
         */
        fun googlePinyinBodyHeightDp(smallestScreenWidthDp: Int, landscape: Boolean): Int =
            if (landscape) {
                when {
                    smallestScreenWidthDp >= 768 -> 328 // values-sw768dp-land
                    smallestScreenWidthDp >= 600 -> 241 // values-sw600dp-land
                    smallestScreenWidthDp >= 400 -> 176 // values-sw400dp-land
                    else -> 162 // values-land
                }
            } else {
                when {
                    smallestScreenWidthDp >= 600 -> 287 // values-sw600dp
                    smallestScreenWidthDp >= 400 -> 242 // values-sw400dp
                    else -> 209 // values
                }
            }

        fun googlePinyinBodyHeightDp(configuration: Configuration): Int =
            googlePinyinBodyHeightDp(
                configuration.smallestScreenWidthDp,
                configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            )
    }
}
