/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.testing.TestLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Touch behavior of the letter keyboard, checked by feeding motion events to a laid out
 * [TextKeyboard]: sliding between keys, multi-touch rollover order, edge key area and
 * touch alternatives for spatial correction.
 */
class KeyboardTouchTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private lateinit var keyboard: TextKeyboard
    private val actions = mutableListOf<KeyAction>()
    private val density get() = context.resources.displayMetrics.density

    @Before
    fun setup() {
        instrumentation.runOnMainSync {
            keyboard = TextKeyboard(context, ThemeManager.activeTheme)
            keyboard.setViewTreeLifecycleOwner(TestLifecycleOwner(Lifecycle.State.RESUMED))
            keyboard.keyActionListener = KeyActionListener { action, _ -> actions.add(action) }
            val w = context.resources.displayMetrics.widthPixels
            val h = (209 * density).toInt()
            keyboard.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            keyboard.layout(0, 0, w, h)
        }
    }

    /** Rect of the letter key in keyboard coordinates */
    private fun keyRect(letter: Char): Rect {
        for (i in 0 until keyboard.childCount) {
            val row = keyboard.getChildAt(i) as ViewGroup
            for (j in 0 until row.childCount) {
                val v = row.getChildAt(j) as? TextKeyView ?: continue
                if (v.mainText.text.toString().equals(letter.toString(), ignoreCase = true)) {
                    return Rect(row.left + v.left, row.top + v.top, row.left + v.right, row.top + v.bottom)
                }
            }
        }
        error("no key $letter")
    }

    private var downTime = 0L
    private val pointers = linkedMapOf<Int, Pair<Float, Float>>()

    private fun send(action: Int, actionPointerId: Int) {
        val ids = pointers.keys.toList()
        val props = ids.map { id -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = ids.map { id ->
            MotionEvent.PointerCoords().apply { x = pointers[id]!!.first; y = pointers[id]!!.second; pressure = 1f; size = 1f }
        }
        val index = ids.indexOf(actionPointerId)
        val masked = when (action) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP ->
                action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            else -> action
        }
        val e = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), masked, ids.size,
            props.toTypedArray(), coords.toTypedArray(), 0, 0, 1f, 1f, 0, 0, 0, 0
        )
        instrumentation.runOnMainSync { keyboard.dispatchTouchEvent(e) }
        e.recycle()
    }

    private fun down(id: Int, x: Float, y: Float) {
        pointers[id] = x to y
        if (pointers.size == 1) {
            downTime = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN, id)
        } else {
            send(MotionEvent.ACTION_POINTER_DOWN, id)
        }
    }

    private fun move(id: Int, x: Float, y: Float) {
        pointers[id] = x to y
        send(MotionEvent.ACTION_MOVE, id)
    }

    private fun up(id: Int) {
        if (pointers.size == 1) send(MotionEvent.ACTION_UP, id)
        else send(MotionEvent.ACTION_POINTER_UP, id)
        pointers.remove(id)
    }

    private fun tap(x: Float, y: Float) {
        down(0, x, y)
        up(0)
    }

    private fun typed() = actions.filterIsInstance<KeyAction.FcitxKeyAction>().map { it.act }

    @Test
    fun tapAtCenterGivesTouchedKeyWithAlternatives() {
        val g = keyRect('g')
        tap(g.exactCenterX(), g.exactCenterY())
        assertEquals(listOf("g"), typed())
        val alt = (actions.single() as KeyAction.FcitxKeyAction).alternatives
        assertNotNull(alt)
        assertEquals('g', alt!!.keys[0])
    }

    @Test
    fun tapOnBorderMakesNeighborNearlyAsLikely() {
        val g = keyRect('g')
        tap(g.right - 2f, g.exactCenterY())
        val alt = (actions.single() as KeyAction.FcitxKeyAction).alternatives!!
        assertEquals("g", alt.keys.substring(0, 1))
        assertEquals('h', alt.keys[1])
        assertTrue(alt.toString(), alt.scores[0] - alt.scores[1] < 0.5f)
    }

    @Test
    fun slidingToAnotherKeyTypesThatKey() {
        val g = keyRect('g')
        val h = keyRect('h')
        down(0, g.exactCenterX(), g.exactCenterY())
        move(0, g.right + 5f, g.exactCenterY())
        move(0, h.exactCenterX(), h.exactCenterY())
        up(0)
        assertEquals(listOf("h"), typed())
        // spatial model is skipped for slid-in keys, like Google Pinyin
        assertNull((actions.single() as KeyAction.FcitxKeyAction).alternatives)
    }

    @Test
    fun swipeDownStillTypesSymbol() {
        val g = keyRect('g')
        down(0, g.exactCenterX(), g.exactCenterY())
        move(0, g.exactCenterX(), g.exactCenterY() + 20 * density)
        move(0, g.exactCenterX(), g.bottom + 30 * density)
        up(0)
        assertEquals(listOf("="), typed())
    }

    @Test
    fun rolloverKeepsPressOrder() {
        val a = keyRect('a')
        val s = keyRect('s')
        down(0, a.exactCenterX(), a.exactCenterY())
        down(1, s.exactCenterX(), s.exactCenterY())
        // second finger lifts first, order must still be a, s
        up(1)
        up(0)
        assertEquals(listOf("a", "s"), typed())
    }

    @Test
    fun edgeOfMiddleRowBelongsToEdgeKeys() {
        val a = keyRect('a')
        val l = keyRect('l')
        tap(2f, a.exactCenterY())
        tap(keyboard.width - 2f, l.exactCenterY())
        assertEquals(listOf("a", "l"), typed())
    }

    private fun viewRect(id: Int): Rect {
        val v = keyboard.findViewById<View>(id)
        val row = v.parent as View
        return Rect(row.left + v.left, row.top + v.top, row.left + v.right, row.top + v.bottom)
    }

    @Test
    fun shiftTypesSeparatorWhileComposing() {
        val caps = viewRect(org.fcitx.fcitx5.android.R.id.button_caps)
        instrumentation.runOnMainSync { keyboard.onPreeditEmptyStateUpdate(false) }
        tap(caps.exactCenterX(), caps.exactCenterY())
        assertEquals(listOf("'"), typed())
        // not composing: shift is shift again, the next letter is upper case
        instrumentation.runOnMainSync { keyboard.onPreeditEmptyStateUpdate(true) }
        actions.clear()
        tap(caps.exactCenterX(), caps.exactCenterY())
        val g = keyRect('g')
        Thread.sleep(400) // avoid the shift double tap window
        tap(g.exactCenterX(), g.exactCenterY())
        assertEquals(listOf("G"), typed())
    }

    @Test
    fun swipeIsDecidedWhereTheFingerLifts() {
        val g = keyRect('g')
        // past the threshold on the way, but lifted only 10 dp below where it went down
        down(0, g.exactCenterX(), g.exactCenterY())
        move(0, g.exactCenterX(), g.exactCenterY() + 20 * density)
        move(0, g.exactCenterX(), g.exactCenterY() + 10 * density)
        up(0)
        assertEquals(listOf("g"), typed())
    }

    @Test
    fun mostlySidewaysDragIsNoSwipe() {
        val g = keyRect('g')
        // 17 dp down but 18 dp sideways: vertical has to dominate
        down(0, g.exactCenterX(), g.exactCenterY())
        move(0, g.exactCenterX() + 18 * density, g.exactCenterY() + 17 * density)
        up(0)
        assertEquals(listOf("g"), typed())
    }

    private fun syms(sym: Int) = actions.count {
        it is KeyAction.SymAction && it.sym.sym == sym
    }

    @Test
    fun letterVerticalDragNeverSwitchesRows() {
        val g = keyRect('g')
        val t = keyRect('t')
        down(0, g.exactCenterX(), g.exactCenterY())
        move(0, g.exactCenterX(), g.top - 5f)
        move(0, g.exactCenterX(), t.exactCenterY())
        up(0)
        // dragging up types the letter itself, never the key above
        assertEquals(listOf("g"), typed())
    }

    @Test
    fun letterDragFarDownStillTypesSymbol() {
        val g = keyRect('g')
        down(0, g.exactCenterX(), g.exactCenterY())
        move(0, g.exactCenterX(), g.exactCenterY() + 30 * density)
        move(0, g.exactCenterX(), keyboard.height + 120 * density)
        up(0)
        assertEquals(listOf("="), typed())
    }

    @Test
    fun gapBetweenKeysIsHysteresis() {
        val g = keyRect('g')
        val h = keyRect('h')
        down(0, g.exactCenterX(), g.exactCenterY())
        // inside the cell of h but not yet on its visible key
        move(0, h.left + 1f, g.exactCenterY())
        up(0)
        assertEquals(listOf("g"), typed())
    }

    @Test
    fun lettersTypedWhileHoldingShiftAreUpperCase() {
        val caps = viewRect(org.fcitx.fcitx5.android.R.id.button_caps)
        val g = keyRect('g')
        val h = keyRect('h')
        val j = keyRect('j')
        down(0, caps.exactCenterX(), caps.exactCenterY())
        down(1, g.exactCenterX(), g.exactCenterY()); up(1)
        down(1, h.exactCenterX(), h.exactCenterY()); up(1)
        up(0)
        Thread.sleep(400)
        tap(j.exactCenterX(), j.exactCenterY())
        // released shift after chording: back to lower case
        assertEquals(listOf("G", "H", "j"), typed())
    }

    @Test
    fun capsLockStaysWhileComposing() {
        val caps = viewRect(org.fcitx.fcitx5.android.R.id.button_caps)
        val g = keyRect('g')
        tap(caps.exactCenterX(), caps.exactCenterY())
        Thread.sleep(100)
        tap(caps.exactCenterX(), caps.exactCenterY())
        Thread.sleep(400)
        // composing upper case is typing English: shift stays caps lock, not the separator
        instrumentation.runOnMainSync { keyboard.onPreeditEmptyStateUpdate(false) }
        tap(g.exactCenterX(), g.exactCenterY())
        assertEquals(listOf("G"), typed())
        // a tap unlocks, then shift is the separator again
        tap(caps.exactCenterX(), caps.exactCenterY())
        Thread.sleep(400)
        tap(g.exactCenterX(), g.exactCenterY())
        tap(caps.exactCenterX(), caps.exactCenterY())
        assertEquals(listOf("G", "g", "'"), typed())
        instrumentation.runOnMainSync { keyboard.onPreeditEmptyStateUpdate(true) }
    }

    @Test
    fun longPressOnSeparatorLocksCaps() {
        val caps = viewRect(org.fcitx.fcitx5.android.R.id.button_caps)
        val g = keyRect('g')
        val h = keyRect('h')
        instrumentation.runOnMainSync { keyboard.onPreeditEmptyStateUpdate(false) }
        down(0, caps.exactCenterX(), caps.exactCenterY())
        Thread.sleep(800)
        up(0)
        Thread.sleep(400)
        tap(g.exactCenterX(), g.exactCenterY())
        tap(h.exactCenterX(), h.exactCenterY())
        // no separator typed, letters upper case for mixed input
        assertEquals(listOf("G", "H"), typed())
        tap(caps.exactCenterX(), caps.exactCenterY())
        instrumentation.runOnMainSync { keyboard.onPreeditEmptyStateUpdate(true) }
    }

    @Test
    fun shiftDoubleTapLocks() {
        val caps = viewRect(org.fcitx.fcitx5.android.R.id.button_caps)
        val g = keyRect('g')
        val h = keyRect('h')
        tap(caps.exactCenterX(), caps.exactCenterY())
        Thread.sleep(100)
        tap(caps.exactCenterX(), caps.exactCenterY())
        Thread.sleep(400)
        tap(g.exactCenterX(), g.exactCenterY())
        tap(h.exactCenterX(), h.exactCenterY())
        assertEquals(listOf("G", "H"), typed())
    }

    @Test
    fun secondFingerStopsRepeatingDelete() {
        val del = viewRect(org.fcitx.fcitx5.android.R.id.button_backspace)
        val a = keyRect('a')
        down(0, del.exactCenterX(), del.exactCenterY())
        Thread.sleep(700)
        val repeated = syms(0xff08)
        assertTrue("repeat started: $repeated", repeated > 0)
        down(1, a.exactCenterX(), a.exactCenterY())
        Thread.sleep(400)
        up(1)
        up(0)
        assertEquals(repeated, syms(0xff08))
        assertEquals(listOf("a"), typed())
    }

    private fun scrubSpace(): Pair<Int, Int> {
        val space = viewRect(org.fcitx.fcitx5.android.R.id.button_space)
        down(0, space.exactCenterX(), space.exactCenterY())
        Thread.sleep(250)
        for (i in 1..5) {
            move(0, space.exactCenterX() + i * 12 * density, space.exactCenterY())
            Thread.sleep(30)
        }
        up(0)
        return syms(0x20) to syms(0xff53)
    }

    @Test
    fun spaceScrubIsInhibitedRightAfterTyping() {
        val a = keyRect('a')
        tap(a.exactCenterX(), a.exactCenterY())
        // within 150ms of the release: a plain space, cursor does not move
        val (spaces, rights) = scrubSpace()
        assertEquals(1, spaces)
        assertEquals(0, rights)
        actions.clear()
        Thread.sleep(300)
        val (spaces2, rights2) = scrubSpace()
        assertEquals(0, spaces2)
        assertTrue("cursor moved: $rights2", rights2 > 0)
    }
}
