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
}
