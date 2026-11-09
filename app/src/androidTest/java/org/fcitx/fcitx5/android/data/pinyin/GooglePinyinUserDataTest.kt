/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.core.Fcitx
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.data.pinyin.dict.GooglePinyinUserDict.Entry
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import timber.log.Timber
import java.io.File

/**
 * Verifies that words imported from Google Pinyin behave like words fcitx learned itself:
 * ranked by Google Pinyin usage, re-ranked by daily typing, exported with usage counts,
 * and removable with "forget candidate".
 */
class GooglePinyinUserDataTest {

    private companion object {
        val context get() = InstrumentationRegistry.getInstrumentation().targetContext
        val pinyinDir get() = File(context.getExternalFilesDir(null)!!, "data/pinyin").also { it.mkdirs() }
        val userDict get() = File(pinyinDir, "user.dict")
        val userHistory get() = File(pinyinDir, "user.history")
        val userTyped get() = File(pinyinDir, "user.typed")
        val userEnglish get() = File(pinyinDir, "user.english")

        lateinit var fcitx: Fcitx
        val events = Channel<FcitxEvent<*>>(capacity = Channel.UNLIMITED)
        val scope = MainScope()

        // keep user data of the app under test
        val backups = mutableMapOf<File, ByteArray?>()

        // two made-up words with the same pinyin, absent from system dictionaries
        val rare = Entry("鼗鼙", listOf("tao", "pi"), 1)
        val frequent = Entry("洮貔", listOf("tao", "pi"), 100)

        @BeforeClass
        @JvmStatic
        fun setup() {
            listOf(userDict, userHistory, userTyped, userEnglish).forEach {
                backups[it] = it.takeIf { f -> f.exists() }?.readBytes()
            }
            fcitx = Fcitx(context)
            fcitx.eventFlow.onEach { events.send(it) }.launchIn(scope)
        }

        @AfterClass
        @JvmStatic
        fun cleanup() {
            if (fcitx.isReady) fcitx.stop()
            backups.forEach { (file, bytes) -> if (bytes == null) file.delete() else file.writeBytes(bytes) }
            scope.cancel()
        }
    }

    private fun tempDir() = File(context.cacheDir, "gpy-test-${System.nanoTime()}").also { it.mkdirs() }

    @Test
    fun mergeAndReadFiles() {
        val dir = tempDir()
        val dict = File(dir, "user.dict")
        val history = File(dir, "user.history")
        val extB = Entry("𠮷野", listOf("ji", "ye"), 2) // 𠮷野, outside BMP
        val invalid = Entry("测", listOf("xx"), 3)

        val first = GooglePinyinUserData.mergeFiles(dict, history, listOf(rare, frequent, extB, invalid))
        assertEquals(GooglePinyinUserData.MergeResult(added = 3, existing = 0, invalid = 1), first)
        assertTrue(dict.exists() && history.exists())

        var words = GooglePinyinUserData.readWords(dict, history).associateBy { it.word }
        assertEquals(setOf(rare.word, frequent.word, extB.word), words.keys)
        // history repeats = 1 + floor(log2(count)), capped at 5
        assertEquals(1, words.getValue(rare.word).count)
        assertEquals(5, words.getValue(frequent.word).count)
        assertEquals(2, words.getValue(extB.word).count)
        assertEquals(listOf("ji", "ye"), words.getValue(extB.word).syllables)

        val second = GooglePinyinUserData.mergeFiles(dict, history, listOf(frequent.copy(count = 1)))
        assertEquals(GooglePinyinUserData.MergeResult(added = 0, existing = 1, invalid = 0), second)
        words = GooglePinyinUserData.readWords(dict, history).associateBy { it.word }
        assertEquals(6, words.getValue(frequent.word).count)

        // without history every count is 1
        assertTrue(GooglePinyinUserData.readWords(dict, File(dir, "missing")).all { it.count == 1 })
        dir.deleteRecursively()
    }

    private suspend inline fun <reified T : FcitxEvent<*>> awaitEvent(): T =
        withTimeout(10_000) { events.receiveAsFlow().filterIsInstance<T>().first() }

    private suspend fun startFcitx() {
        while (events.tryReceive().isSuccess) { /* drop stale events */ }
        fcitx.start()
        awaitEvent<FcitxEvent.ReadyEvent>()
        fcitx.setEnabledIme(arrayOf("keyboard-us", "pinyin"))
        // keys are dropped unless an input context is active and focused
        fcitx.activate(1, context.packageName)
        fcitx.focus(true)
        fcitx.activateIme("pinyin")
        // pinyin addon and its dictionaries load lazily and asynchronously
        withTimeout(30_000) {
            while (true) {
                type("nihao")
                if (candidates().isNotEmpty()) break
                delay(500)
            }
        }
        fcitx.reset()
    }

    private suspend fun type(str: String) {
        fcitx.reset()
        str.forEach { fcitx.sendKey(it); delay(30) }
        delay(200)
    }

    private suspend fun candidates(): List<String> =
        fcitx.getCandidates(0, 300).map { it.text }

    private suspend fun rankOf(word: String): Int {
        type("taopi")
        val list = candidates()
        Timber.i("taopi candidates: ${list.take(20)} (total ${list.size})")
        return list.indexOf(word)
    }

    @Test
    fun importedWordsLearnLikeGooglePinyin(): Unit = runBlocking {
        // start from empty user data, import as Google Pinyin import does
        listOf(userDict, userHistory, userTyped, userEnglish).forEach { it.delete() }
        GooglePinyinUserData.mergeFiles(userDict, userHistory, listOf(rare, frequent))
        startFcitx()

        // 1. imported words are candidates, ranked by Google Pinyin usage
        var rareRank = rankOf(rare.word)
        var frequentRank = rankOf(frequent.word)
        Timber.i("after import: rare=$rareRank frequent=$frequentRank")
        assertTrue("imported words should be candidates", rareRank >= 0 && frequentRank >= 0)
        assertTrue("higher Google count ranks first", frequentRank < rareRank)

        // 2. daily typing re-ranks them
        repeat(8) {
            val idx = rankOf(rare.word)
            fcitx.select(idx)
            assertEquals(rare.word, awaitEvent<FcitxEvent.CommitStringEvent>().data.text)
        }
        rareRank = rankOf(rare.word)
        frequentRank = rankOf(frequent.word)
        Timber.i("after typing: rare=$rareRank frequent=$frequentRank")
        assertTrue("word typed more often moves up", rareRank < frequentRank)

        // 3. export reflects usage in fcitx
        fcitx.save()
        var words = GooglePinyinUserData.readWords(userDict, userHistory).associateBy { it.word }
        Timber.i("exported: $words")
        assertTrue(words.getValue(rare.word).count > words.getValue(frequent.word).count)

        // 4. imported words can be forgotten like learned words
        val idx = rankOf(frequent.word)
        val forget = fcitx.getCandidateActions(idx).find { it.text.contains("Forget", true) || it.text.contains("忘") }
        assertNotNull("forget action", forget)
        fcitx.triggerCandidateAction(idx, forget!!.id)
        delay(200)
        fcitx.reset()
        fcitx.save()
        words = GooglePinyinUserData.readWords(userDict, userHistory).associateBy { it.word }
        assertFalse("forgotten word removed from user dict", frequent.word in words)
        assertTrue(rare.word in words)
        fcitx.stop()
    }
}
