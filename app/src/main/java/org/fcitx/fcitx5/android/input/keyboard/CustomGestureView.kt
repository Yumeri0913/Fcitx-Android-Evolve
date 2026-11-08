/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

open class CustomGestureView(ctx: Context) : FrameLayout(ctx) {

    enum class SwipeAxis { X, Y }

    enum class GestureType { Down, Move, Up }

    data class Event(
        val type: GestureType,
        val consumed: Boolean,
        val x: Float,
        val y: Float,
        val countX: Int,
        val countY: Int,
        val totalX: Int,
        val totalY: Int
    )

    fun interface OnGestureListener {
        fun onGesture(view: View, event: Event): Boolean

        companion object {
            val Empty = OnGestureListener { _, _ -> false }
        }
    }

    private val lifecycleScope by lazy {
        findViewTreeLifecycleOwner()?.lifecycleScope!!
    }

    @Volatile
    private var touchMovedOutside = false

    @Volatile
    private var longPressTriggered = false
    var longPressEnabled = false
    private var longPressJob: Job? = null

    @Volatile
    var longPressFeedbackEnabled = true

    @Volatile
    private var repeatStarted = false
    var repeatEnabled = false
    private var repeatJob: Job? = null

    var swipeEnabled = false
    var swipeRepeatEnabled = false
    var swipeThresholdX = 24f
    var swipeThresholdY = 24f

    private var swipeRepeatTriggered = false
    private var swipeLastX = -1f
    private var swipeLastY = -1f
    private var swipeXUnconsumed = 0f
    private var swipeYUnconsumed = 0f
    private var swipeTotalX = 0
    private var swipeTotalY = 0
    private var gestureConsumed = false

    var doubleTapEnabled = false
    private var lastClickTime = 0L
    private var maybeDoubleTap = false

    var onDoubleTapListener: ((View) -> Unit)? = null
    var onRepeatListener: ((View) -> Unit)? = null
    var onGestureListener: OnGestureListener? = null

    var soundEffect: InputFeedbacks.SoundEffect = InputFeedbacks.SoundEffect.Standard

    private val touchSlop: Float = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    /**
     * How far (px) a touch may leave the view and still count as a tap on release.
     */
    var outsideTolerance: Float = touchSlop

    /**
     * Same as [outsideTolerance] but vertically. Google Pinyin letter keys never cancel on a
     * vertical drag: up types the letter, down types the swipe symbol.
     */
    var outsideToleranceY: Float = touchSlop

    /**
     * Disable swipe for the next press only (e.g. space pressed right after another finger lifted)
     */
    var suppressSwipeForNextPress = false
    private var swipeSuppressed = false

    /**
     * Google Pinyin space: the long press timer restarts while the finger keeps drifting
     * horizontally, so a moving finger does not open the long press action
     */
    var restartLongPressOnDrift = false
    private var lastDriftX = 0f
    private val driftThreshold = ctx.resources.displayMetrics.density * 1.1f // ~0.5pt

    /**
     * Swipe distance is not consumed until this long (ms) after touch down, so a quick tap that
     * drifts a little is still a tap. Movement in the meantime is consumed once the delay passes.
     */
    var swipeStartDelay = 0L
    private var downTime = 0L

    /**
     * Delay (ms) before key repeat starts, independent from long press delay.
     */
    var repeatStartDelay = RepeatStartDelay

    private var nextPressSlidIn = false

    /**
     * Whether the current press started by sliding onto this view from another key, rather than
     * touching down on it. Such presses use a longer long press delay.
     */
    var pressSlidIn = false
        private set

    /**
     * Position (in view coordinates) of the last touch release.
     */
    var lastUpX = 0f
        private set
    var lastUpY = 0f
        private set

    /**
     * Whether the finger can leave this key for another one without breaking a gesture
     * (long press popup, key repeat, swipe repeat or a consumed gesture).
     */
    val canSlideAway: Boolean
        get() = !longPressTriggered && !repeatStarted && !swipeRepeatTriggered && !gestureConsumed

    /**
     * Mark the next ACTION_DOWN as a finger sliding onto this view from another key.
     */
    fun markNextPressSlidIn() {
        nextPressSlidIn = true
    }

    init {
        // disable system sound effect and haptic feedback
        isSoundEffectsEnabled = false
        isHapticFeedbackEnabled = false
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) {
            isPressed = false
        }
    }

    private fun pointInView(x: Float, y: Float): Boolean {
        return -outsideTolerance <= x &&
                -outsideToleranceY <= y &&
                x < (width + outsideTolerance) &&
                y < (height + outsideToleranceY)
    }

    private fun resetState() {
        touchMovedOutside = false
        if (longPressEnabled) {
            longPressTriggered = false
            longPressJob?.cancel()
            longPressJob = null
        }
        if (repeatEnabled) {
            repeatStarted = false
            repeatJob?.cancel()
            repeatJob = null
        }
        if (swipeEnabled) {
            if (swipeRepeatEnabled) {
                swipeRepeatTriggered = false
            }
            swipeXUnconsumed = 0f
            swipeYUnconsumed = 0f
            swipeTotalX = 0
            swipeTotalY = 0
            gestureConsumed = false
        }
        // double tap state should be preserved on touch up
    }

    fun cancelGestures() {
        isPressed = false
        resetState()
        // reset double tap state on cancel
        if (doubleTapEnabled) {
            maybeDoubleTap = false
            lastClickTime = 0
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressSlidIn = nextPressSlidIn
                nextPressSlidIn = false
                swipeSuppressed = suppressSwipeForNextPress
                suppressSwipeForNextPress = false
                if (!isEnabled) return false
                downTime = SystemClock.uptimeMillis()
                drawableHotspotChanged(x, y)
                isPressed = true
                InputFeedbacks.hapticFeedback(this)
                InputFeedbacks.soundEffect(soundEffect)
                dispatchGestureEvent(GestureType.Down, x, y)
                lastDriftX = x
                if (longPressEnabled) startLongPressTimer()
                if (repeatEnabled) {
                    repeatJob?.cancel()
                    repeatJob = lifecycleScope.launch {
                        delay(repeatStartDelay)
                        repeatStarted = true
                        var lastTriggerTime: Long
                        while (isActive && isEnabled) {
                            lastTriggerTime = SystemClock.uptimeMillis()
                            onRepeatListener?.invoke(this@CustomGestureView)
                            val t = lastTriggerTime + RepeatInterval - SystemClock.uptimeMillis()
                            if (t > 0) delay(t)
                        }
                    }
                }
                if (swipeEnabled) {
                    swipeLastX = x
                    swipeLastY = y
                }
            }
            MotionEvent.ACTION_UP -> {
                lastUpX = x
                lastUpY = y
                isPressed = false
                InputFeedbacks.hapticFeedback(this, longPress = true, keyUp = true)
                dispatchGestureEvent(GestureType.Up, event.x, event.y)
                val shouldPerformClick = !(touchMovedOutside ||
                        longPressTriggered ||
                        repeatStarted ||
                        swipeRepeatTriggered ||
                        gestureConsumed)
                resetState()
                if (shouldPerformClick) {
                    if (doubleTapEnabled) {
                        val now = System.currentTimeMillis()
                        if (maybeDoubleTap && now - lastClickTime <= longPressDelay) {
                            maybeDoubleTap = false
                            onDoubleTapListener?.invoke(this)
                        } else {
                            maybeDoubleTap = true
                            performClick()
                        }
                        lastClickTime = now
                    } else {
                        performClick()
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isEnabled) return false
                drawableHotspotChanged(x, y)
                if (!touchMovedOutside && !pointInView(x, y)) {
                    touchMovedOutside = true
                    if (longPressEnabled) {
                        longPressJob?.cancel()
                        longPressJob = null
                    }
                    if (repeatEnabled) {
                        repeatJob?.cancel()
                        repeatJob = null
                    }
                    if (repeatStarted || !swipeEnabled) {
                        isPressed = false
                    }
                }
                if (restartLongPressOnDrift && longPressEnabled && !longPressTriggered &&
                    longPressJob != null && abs(x - lastDriftX) >= driftThreshold
                ) {
                    lastDriftX = x
                    startLongPressTimer()
                }
                if (!swipeEnabled || swipeSuppressed || longPressTriggered || repeatStarted) return true
                if (swipeStartDelay > 0 && SystemClock.uptimeMillis() - downTime < swipeStartDelay) {
                    // keep accumulating from the last consumed position
                    return true
                }
                val countX = consumeSwipe(x, SwipeAxis.X)
                val countY = consumeSwipe(y, SwipeAxis.Y)
                dispatchGestureEvent(GestureType.Move, x, y, countX, countY)
                swipeLastX = x
                swipeLastY = y
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dispatchGestureEvent(GestureType.Up, event.x, event.y)
                cancelGestures()
                return true
            }
        }
        return true
    }

    private fun dispatchGestureEvent(
        type: GestureType,
        x: Float,
        y: Float,
        countX: Int = 0,
        countY: Int = 0
    ) {
        val event = Event(type, gestureConsumed, x, y, countX, countY, swipeTotalX, swipeTotalY)
        val consumed = onGestureListener?.onGesture(this, event) ?: return
        if (consumed && !gestureConsumed) {
            gestureConsumed = true
        }
    }

    private fun consumeSwipe(current: Float, axis: SwipeAxis): Int {
        val unconsumed: Float
        val threshold: Float
        when (axis) {
            SwipeAxis.X -> {
                unconsumed = current - swipeLastX + swipeXUnconsumed
                threshold = swipeThresholdX
            }
            SwipeAxis.Y -> {
                unconsumed = current - swipeLastY + swipeYUnconsumed
                threshold = swipeThresholdY
            }
        }
        val remains: Float = unconsumed % threshold
        val count: Int = (unconsumed / threshold).toInt()
        if (count != 0) {
            if (swipeRepeatEnabled && !swipeRepeatTriggered) {
                swipeRepeatTriggered = true
            }
            if (longPressEnabled && !longPressTriggered) {
                longPressJob?.cancel()
                longPressJob = null
            }
            if (repeatEnabled && !repeatStarted) {
                repeatJob?.cancel()
                repeatJob = null
            }
        }
        when (axis) {
            SwipeAxis.X -> {
                swipeXUnconsumed = remains
                swipeTotalX += count
            }
            SwipeAxis.Y -> {
                swipeYUnconsumed = remains
                swipeTotalY += count
            }
        }
        return count
    }

    private fun startLongPressTimer() {
        longPressJob?.cancel()
        longPressJob = lifecycleScope.launch {
            val factor = if (pressSlidIn) SlidInLongPressFactor else 1f
            delay((longPressDelay * factor).toLong())
            if (longPressFeedbackEnabled) {
                InputFeedbacks.hapticFeedback(this@CustomGestureView, true)
            }
            longPressTriggered = performLongClick()
        }
    }

    override fun setOnLongClickListener(l: OnLongClickListener?) {
        longPressEnabled = l != null
        super.setOnLongClickListener(l)
    }

    companion object {
        val longPressDelay by AppPrefs.getInstance().keyboard.longPressDelay
        const val RepeatInterval = 50L

        /**
         * Same as Google Pinyin: key repeat starts 400ms after touch down
         */
        const val RepeatStartDelay = 400L

        /**
         * Same as Google Pinyin: long press takes 1.6x longer on a key the finger slid onto
         */
        const val SlidInLongPressFactor = 1.6f
    }
}
