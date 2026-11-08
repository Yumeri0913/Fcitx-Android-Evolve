/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightMode.Companion.googlePinyinBodyHeightDp
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardHeightModeTest {

    @Test
    fun portraitMatchesGooglePinyinDimens() {
        assertEquals(209, googlePinyinBodyHeightDp(360, false))
        assertEquals(209, googlePinyinBodyHeightDp(399, false))
        assertEquals(242, googlePinyinBodyHeightDp(400, false))
        assertEquals(242, googlePinyinBodyHeightDp(411, false))
        assertEquals(287, googlePinyinBodyHeightDp(600, false))
        assertEquals(287, googlePinyinBodyHeightDp(800, false))
    }

    @Test
    fun landscapeMatchesGooglePinyinDimens() {
        assertEquals(162, googlePinyinBodyHeightDp(360, true))
        assertEquals(176, googlePinyinBodyHeightDp(411, true))
        assertEquals(241, googlePinyinBodyHeightDp(600, true))
        assertEquals(328, googlePinyinBodyHeightDp(800, true))
    }
}
