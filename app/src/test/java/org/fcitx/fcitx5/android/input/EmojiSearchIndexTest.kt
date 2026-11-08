/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.input.emoji.EmojiSearchIndex
import org.fcitx.fcitx5.android.input.emoji.EmojiSearcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmojiSearchIndexTest {

    private fun dict(lang: String): EmojiSearchIndex {
        // unit tests run with the app module as working directory
        val file = File("../lib/fcitx5/src/main/cpp/fcitx5/src/modules/emoji/data/$lang.dict")
        return EmojiSearchIndex.parse(file.readBytes())
    }

    private val zh by lazy { dict("zh") }
    private val en by lazy { dict("en") }

    @Test
    fun loadsCldrData() {
        assertTrue(zh.size > 3000)
        assertTrue(en.size > 3000)
    }

    @Test
    fun chineseExactMatchFirst() {
        val cat = zh.search("猫")
        assertEquals("🐱", cat.first())
        assertTrue("😂" in zh.search("笑哭了"))
        assertEquals("👍", zh.search("真棒").first())
    }

    @Test
    fun englishPrefixAndCaseInsensitive() {
        assertTrue("🐱" in en.search("Cat"))
        val ranked = en.search("smil")
        assertTrue(ranked.isNotEmpty())
        assertTrue(en.search("thumbs").contains("👍"))
    }

    @Test
    fun combinedSearchPrefersChinese() {
        val searcher = EmojiSearcher(listOf(zh, en))
        assertEquals("🐱", searcher.search("猫").first())
        assertTrue("🐱" in searcher.search("cat"))
        assertTrue(searcher.search("   ").isEmpty())
        assertTrue(searcher.search("笑", limit = 5).size <= 5)
    }
}
