/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

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
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyAlternatives
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.ScancodeMapping
import org.fcitx.fcitx5.android.data.pinyin.GooglePinyinUserData
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import timber.log.Timber
import java.io.File

/**
 * Pinyin engine behavior added for Google Pinyin like typing:
 * keyboard correction priced by touch position (including vertical neighbors),
 * undoing what was learned from a commit when BackSpace follows it,
 * and learning English words committed as raw input with Return.
 */
class PinyinTouchCorrectionTest {

    private companion object {
        val context get() = InstrumentationRegistry.getInstrumentation().targetContext
        val pinyinDir get() = File(context.getExternalFilesDir(null)!!, "data/pinyin").also { it.mkdirs() }
        val userDict get() = File(pinyinDir, "user.dict")
        val userHistory get() = File(pinyinDir, "user.history")
        val userEnglish get() = File(pinyinDir, "user.english")
        val userTyped get() = File(pinyinDir, "user.typed")

        lateinit var fcitx: Fcitx
        val events = Channel<FcitxEvent<*>>(capacity = Channel.UNLIMITED)
        val scope = MainScope()
        val backups = mutableMapOf<File, ByteArray?>()

        @BeforeClass
        @JvmStatic
        fun setup() {
            listOf(userDict, userHistory, userEnglish, userTyped).forEach {
                backups[it] = it.takeIf { f -> f.exists() }?.readBytes()
                it.delete()
            }
            fcitx = Fcitx(context)
            fcitx.eventFlow.onEach { events.send(it) }.launchIn(scope)
            fcitx.start()
            runBlocking {
                withTimeout(10_000) { events.receiveAsFlow().filterIsInstance<FcitxEvent.ReadyEvent>().first() }
                fcitx.setEnabledIme(arrayOf("keyboard-us", "pinyin"))
                fcitx.activate(1, context.packageName)
                fcitx.focus(true)
                fcitx.activateIme("pinyin")
                withTimeout(30_000) {
                    while (true) {
                        fcitx.reset()
                        "nihao".forEach { fcitx.sendKey(it) }
                        delay(200)
                        if (fcitx.getCandidates(0, 10).isNotEmpty()) break
                        delay(500)
                    }
                }
                fcitx.reset()
            }
        }

        @AfterClass
        @JvmStatic
        fun cleanup() {
            if (fcitx.isReady) fcitx.stop()
            backups.forEach { (file, bytes) -> if (bytes == null) file.delete() else file.writeBytes(bytes) }
            scope.cancel()
        }
    }

    private suspend fun key(c: Char, alternatives: KeyAlternatives? = null) {
        if (alternatives == null) {
            fcitx.sendKey(c)
        } else {
            fcitx.sendKeyWithAlternatives(
                c.toString(), KeyStates.Virtual.states, ScancodeMapping.charToScancode(c), alternatives
            )
        }
    }

    private suspend fun candidates(): List<String> {
        delay(150)
        return fcitx.getCandidates(0, 50).map { it.text }
    }

    /** Type [input], with [alternatives] for the character at [at] */
    private suspend fun typeWith(input: String, at: Int, alternatives: KeyAlternatives?): List<String> {
        fcitx.reset()
        input.forEachIndexed { i, c -> key(c, if (i == at) alternatives else null) }
        return candidates().also { Timber.i("$input $alternatives -> ${it.take(8)}") }
    }

    @Test
    fun verticalNeighborNeedsTouchData(): Unit = runBlocking {
        // "q" is right above "a": nihqo should become 你好 only when the touch was near "a"
        val plain = typeWith("nihqo", -1, null)
        assertFalse(plain.take(5).toString(), "你好" in plain.take(5))

        val nearBorder = typeWith("nihqo", 3, KeyAlternatives("qaw", floatArrayOf(-3.0f, -3.2f, -9f)))
        assertTrue(nearBorder.take(3).toString(), "你好" in nearBorder.take(3))

        // deep into "q", "a" 10^4.4 times less likely: no cap on the penalty like Google Pinyin,
        // nothing else makes sense of "hqo"
        val deep = typeWith("nihqo", 3, KeyAlternatives("qa", floatArrayOf(-3.0f, -13.2f)))
        assertTrue(deep.take(3).toString(), "你好" in deep.take(3))

        // "a" not near the touch at all
        val farAway = typeWith("nihqo", 3, KeyAlternatives("qw", floatArrayOf(-3.0f, -5.0f)))
        assertFalse(farAway.take(5).toString(), "你好" in farAway.take(5))
        fcitx.reset()
    }

    @Test
    fun touchPositionDecidesRowNeighborCorrection(): Unit = runBlocking {
        // nihai is valid by itself (你还), 你好 needs i -> o
        val border = typeWith("nihai", 4, KeyAlternatives("io", floatArrayOf(-3.0f, -3.05f)))
        val center = typeWith("nihai", 4, KeyAlternatives("io", floatArrayOf(-3.0f, -11.0f)))
        val borderRank = border.indexOf("你好")
        val centerRank = center.indexOf("你好")
        assertTrue("border=$border", borderRank in 0..2)
        assertTrue("border=$borderRank center=$centerRank", centerRank == -1 || centerRank > borderRank)
        fcitx.reset()
    }

    private suspend fun commitTaoPi() {
        fcitx.reset()
        "taopi".forEach { key(it) }
        val first = candidates()
        fcitx.select(first.indexOf("洮").also { assertTrue(first.toString(), it >= 0) })
        val second = candidates()
        fcitx.select(second.indexOf("貔").also { assertTrue(second.toString(), it >= 0) })
        val commit = withTimeout(5_000) {
            events.receiveAsFlow().filterIsInstance<FcitxEvent.CommitStringEvent>().first()
        }
        assertEquals("洮貔", commit.data.text)
    }

    private suspend fun learnedWords(): Set<String> {
        fcitx.save()
        return GooglePinyinUserData.readWords(userDict, userHistory).map { it.word }.toSet()
    }

    @Test
    fun backspaceRightAfterCommitUndoesLearning(): Unit = runBlocking {
        while (events.tryReceive().isSuccess) { /* drop stale events */ }
        assertFalse("洮貔" in learnedWords())
        // a new word is learned on commit, and rejected by BackSpace right after it
        commitTaoPi()
        fcitx.sendKey(FcitxKeyMapping.FcitxKey_BackSpace, KeyStates.Virtual.states)
        delay(150)
        assertFalse("洮貔" in learnedWords())

        // any other key in between keeps what was learned
        commitTaoPi()
        key('a')
        fcitx.reset()
        fcitx.sendKey(FcitxKeyMapping.FcitxKey_BackSpace, KeyStates.Virtual.states)
        delay(150)
        assertTrue("洮貔" in learnedWords())
        fcitx.reset()
    }

    @Test
    fun committedWordComesFirstForWhatWasTyped(): Unit = runBlocking {
        while (events.tryReceive().isSuccess) { /* drop stale events */ }
        assertFalse(typeWith("taopi", -1, null).firstOrNull() == "洮貔")
        commitTaoPi()
        key('a')
        fcitx.reset()
        // like Google Pinyin, the word committed for "taopi" comes first
        assertEquals("洮貔", typeWith("taopi", -1, null).first())
        // also when the last key hit "o" right next to "i"
        val typo = typeWith("taopo", 4, KeyAlternatives("oi", floatArrayOf(-3.0f, -3.4f)))
        assertTrue(typo.toString(), "洮貔" in typo.take(3))

        // "Forget candidate" forgets it for the input, and for pinyin, like it never was
        typeWith("taopi", -1, null)
        val forget = fcitx.getCandidateActions(0).first { it.text == "Forget candidate" || it.text == "忘记候选词" }
        fcitx.triggerCandidateAction(0, forget.id)
        assertFalse("洮貔" in learnedWords())
        assertFalse(typeWith("taopi", -1, null).firstOrNull() == "洮貔")
        fcitx.reset()
    }

    private suspend fun commitFor(input: String, word: String) {
        val candidates = typeWith(input, -1, null)
        while (events.tryReceive().isSuccess) { /* drop stale events */ }
        fcitx.select(candidates.indexOf(word).also { assertTrue(candidates.toString(), it >= 0) })
        val commit = withTimeout(5_000) {
            events.receiveAsFlow().filterIsInstance<FcitxEvent.CommitStringEvent>().first()
        }
        assertEquals(word, commit.data.text)
    }

    @Test
    fun recentlyCommittedWordComesFirst(): Unit = runBlocking {
        val before = typeWith("xg", -1, null)
        val (used, recent) = before.filter { it.length == 2 }.drop(1).take(2)
        repeat(3) { commitFor("xg", used) }
        assertEquals(used, typeWith("xg", -1, null).first())
        // committed less often, but just now
        commitFor("xg", recent)
        val again = typeWith("xg", -1, null)
        assertEquals(again.toString(), listOf(recent, used), again.take(2))

        listOf(recent, used).forEach { word ->
            val index = typeWith("xg", -1, null).indexOf(word)
            val forget = fcitx.getCandidateActions(index).first { it.text == "Forget candidate" || it.text == "忘记候选词" }
            fcitx.triggerCandidateAction(index, forget.id)
        }
        // forgotten like they were never committed, by the pinyin engine too
        val after = typeWith("xg", -1, null)
        assertEquals(after.toString(), before.take(3), after.take(3))
        fcitx.reset()
    }

    private suspend fun commitRaw(input: String) {
        fcitx.reset()
        while (events.tryReceive().isSuccess) { /* drop stale events */ }
        input.forEach { key(it) }
        delay(100)
        fcitx.sendKey(FcitxKeyMapping.FcitxKey_Return, KeyStates.Virtual.states)
        val commit = withTimeout(5_000) {
            events.receiveAsFlow().filterIsInstance<FcitxEvent.CommitStringEvent>().first()
        }
        assertEquals(input, commit.data.text)
    }

    @Test
    fun returnCommittedEnglishIsLearned(): Unit = runBlocking {
        val word = "fcitxy"
        assertFalse(word in typeWith(word, -1, null))

        // rejected by BackSpace right after the commit
        commitRaw(word)
        fcitx.sendKey(FcitxKeyMapping.FcitxKey_BackSpace, KeyStates.Virtual.states)
        delay(150)
        assertFalse(word in typeWith(word, -1, null))

        // kept once another key follows, offered right after the first candidate
        commitRaw(word)
        key('a')
        fcitx.reset()
        val again = typeWith(word, -1, null)
        assertTrue(again.toString(), again.indexOf(word) in 0..1)
        // and completed from a prefix
        val prefix = typeWith("fcit", -1, null)
        assertTrue(prefix.toString(), word in prefix.take(5))

        fcitx.save()
        assertTrue(userEnglish.readText(), userEnglish.readLines().any { it.startsWith("$word\t") })
        fcitx.reset()
    }
}
