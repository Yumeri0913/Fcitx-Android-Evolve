/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.data.pinyin.dict.GooglePinyinUserDict
import org.fcitx.fcitx5.android.data.pinyin.dict.GooglePinyinUserDict.Entry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class GooglePinyinUserDictTest {

    private val bomLE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())

    /** what Google Pinyin 4.5 writes on export */
    private fun googleExport(vararg lines: String): ByteArray =
        bomLE + (GooglePinyinUserDict.HEADER + "\n" + lines.joinToString("") { "$it\n" })
            .toByteArray(Charsets.UTF_16LE)

    @Test
    fun parsesGoogleExport() {
        val bytes = googleExport(
            "你好\t5\tni hao",
            "hello\t3\t\\0 hello",
            "长城\t2\tchang cheng\ttx=1",
            "绿色\t1\tlv se",
        )
        assertTrue(GooglePinyinUserDict.looksLikeGooglePinyin(bytes))
        val result = GooglePinyinUserDict.parse(bytes)
        assertEquals(
            listOf(
                Entry("你好", listOf("ni", "hao"), 5),
                Entry("长城", listOf("chang", "cheng"), 2),
                Entry("绿色", listOf("lv", "se"), 1),
            ),
            result.entries
        )
        // the English entry is not a pinyin word
        assertEquals(1, result.skipped)
    }

    @Test
    fun fieldOrderAndSeparatorsAreLenient() {
        val text = """
            ; comment
            ni'hao 你好 3
            7	zhong|guo	中国
            略微	lue'wei
            女	1	nü
        """.trimIndent()
        val result = GooglePinyinUserDict.parse(text)
        assertEquals(
            listOf(
                Entry("你好", listOf("ni", "hao"), 3),
                Entry("中国", listOf("zhong", "guo"), 7),
                Entry("略微", listOf("lve", "wei"), 1),
                Entry("女", listOf("nv"), 1),
            ),
            result.entries
        )
        assertEquals(0, result.skipped)
    }

    @Test
    fun skipsInvalidEntries() {
        val result = GooglePinyinUserDict.parse(
            listOf(
                "你好\t1\tni",          // syllable count mismatch
                "卡拉OK\t1\tka la o k", // non-Han characters
                "测试\t1\tce shix",     // invalid syllable
                "\t1\tni hao",          // no word
                "你好\t1",               // no pinyin
                "你好\t2\tni hao",
                "你好\t9\tni'hao",       // duplicate keeps max count
            ).joinToString("\n")
        )
        assertEquals(listOf(Entry("你好", listOf("ni", "hao"), 9)), result.entries)
        assertEquals(5, result.skipped)
    }

    @Test
    fun decodesByBomOrFallsBackToGbk() {
        val text = "你好\t1\tni hao\n"
        val gbk = text.toByteArray(Charset.forName("GBK"))
        assertEquals(text, GooglePinyinUserDict.decode(gbk))
        val utf8 = text.toByteArray(Charsets.UTF_8)
        assertEquals(text, GooglePinyinUserDict.decode(utf8))
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
        assertEquals(text, GooglePinyinUserDict.decode(be))
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8
        assertEquals(text, GooglePinyinUserDict.decode(utf8Bom))
    }

    @Test
    fun doesNotMistakeLibimeTextForGoogle() {
        val libime = "你好 ni'hao 0\n中国 zhong'guo 0\n".toByteArray()
        assertFalse(GooglePinyinUserDict.looksLikeGooglePinyin(libime))
        val gbkTsv = "你好\t3\tni hao\n中国\t1\tzhong guo\n".toByteArray(Charset.forName("GBK"))
        assertTrue(GooglePinyinUserDict.looksLikeGooglePinyin(gbkTsv))
    }

    @Test
    fun writesGoogleFormatThatRoundTrips() {
        val entries = listOf(Entry("你好", listOf("ni", "hao"), 5), Entry("长城", listOf("chang", "cheng")))
        val bytes = GooglePinyinUserDict.toGooglePinyinBytes(entries)
        val expected = bomLE + (
            "# User dictionary for Google Pinyin Input\n" +
                "你好\t5\tni hao\n" +
                "长城\t1\tchang cheng\n"
            ).toByteArray(Charsets.UTF_16LE)
        assertArrayEquals(expected, bytes)
        assertEquals(entries, GooglePinyinUserDict.parse(bytes).entries)
    }

    @Test
    fun syllableTableMatchesLibime() {
        val s = GooglePinyinUserDict.VALID_SYLLABLES
        assertEquals(422, s.size)
        listOf("a", "zhuang", "lv", "nve", "lve", "er", "ng", "m").forEach { assertTrue(it, it in s) }
        listOf("lue", "nue", "v", "ü", "shix").forEach { assertFalse(it, it in s) }
    }
}
