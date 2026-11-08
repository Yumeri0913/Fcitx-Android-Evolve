/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import androidx.annotation.CallSuper
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.GestureType
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.OnGestureListener
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.leftToRightOf
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.rightToLeftOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

abstract class BaseKeyboard(
    context: Context,
    protected val theme: Theme,
    private val keyLayout: List<List<KeyDef>>
) : ConstraintLayout(context) {

    var keyActionListener: KeyActionListener? = null

    private val prefs = AppPrefs.getInstance()

    private val popupOnKeyPress by prefs.keyboard.popupOnKeyPress
    private val expandKeypressArea by prefs.keyboard.expandKeypressArea
    private val swipeSymbolDirection by prefs.keyboard.swipeSymbolDirection

    private val spaceSwipeMoveCursor = prefs.keyboard.spaceSwipeMoveCursor
    private val spaceKeys = mutableListOf<KeyView>()
    private val spaceSwipeChangeListener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        spaceKeys.forEach {
            it.swipeEnabled = v
        }
    }

    private val spatialCorrection by prefs.keyboard.spatialCorrection

    private val hapticOnRepeat by prefs.keyboard.hapticOnRepeat

    var popupActionListener: PopupActionListener? = null

    private val selectionSwipeThreshold = dp(10f)

    // Google Pinyin: space scrub moves one character per 8pt, starting 200ms after touch down
    private val spaceSwipeThreshold = dp(18f)
    private val spaceSwipeStartDelay = 200L

    // Google Pinyin: slide sensitivity NORMAL = 16dp
    private val inputSwipeThreshold = dp(16f)

    // Google Pinyin: a touch is cancelled 0.3 inch beyond the key
    private val keyOutsideTolerance = 0.3f * resources.displayMetrics.xdpi

    /**
     * Letter keys, for [SpatialModel]
     */
    private val alphabetKeyViews = mutableListOf<Pair<Char, KeyView>>()

    // a rather large threshold effectively disables swipe of the direction
    private val disabledSwipeThreshold = dp(800f)

    private val keyRows: List<ConstraintLayout>

    init {
        isMotionEventSplittingEnabled = true
        keyRows = keyLayout.map { row ->
            val keyViews = row.map(::createKeyView)
            constraintLayout Row@{
                isMotionEventSplittingEnabled = true
                var totalWidth = 0f
                keyViews.forEachIndexed { index, view ->
                    add(view, lParams {
                        centerVertically()
                        if (index == 0) {
                            leftOfParent()
                            horizontalChainStyle = LayoutParams.CHAIN_PACKED
                        } else {
                            leftToRightOf(keyViews[index - 1])
                        }
                        if (index == keyViews.size - 1) {
                            rightOfParent()
                            // for RTL
                            horizontalChainStyle = LayoutParams.CHAIN_PACKED
                        } else {
                            rightToLeftOf(keyViews[index + 1])
                        }
                        val def = row[index]
                        matchConstraintPercentWidth = def.appearance.percentWidth
                    })
                    row[index].appearance.percentWidth.let {
                        // 0f means fill remaining space, thus does not need expanding
                        totalWidth += if (it != 0f) it else 1f
                    }
                }
                if (expandKeypressArea && totalWidth < 1f) {
                    val free = (1f - totalWidth) / 2f
                    keyViews.first().apply {
                        updateLayoutParams<LayoutParams> {
                            matchConstraintPercentWidth += free
                        }
                        layoutMarginLeft = free / (row.first().appearance.percentWidth + free)
                    }
                    keyViews.last().apply {
                        updateLayoutParams<LayoutParams> {
                            matchConstraintPercentWidth += free
                        }
                        layoutMarginRight = free / (row.last().appearance.percentWidth + free)
                    }
                }
            }
        }
        keyRows.forEachIndexed { index, row ->
            add(row, lParams {
                if (index == 0) topOfParent()
                else below(keyRows[index - 1])
                if (index == keyRows.size - 1) bottomOfParent()
                else above(keyRows[index + 1])
                centerHorizontally()
            })
        }
        spaceSwipeMoveCursor.registerOnChangeListener(spaceSwipeChangeListener)
    }

    private fun createKeyView(def: KeyDef): KeyView {
        return when (def.appearance) {
            is KeyDef.Appearance.AltText -> AltTextKeyView(context, theme, def.appearance)
            is KeyDef.Appearance.ImageText -> ImageTextKeyView(context, theme, def.appearance)
            is KeyDef.Appearance.Text -> TextKeyView(context, theme, def.appearance)
            is KeyDef.Appearance.Image -> ImageKeyView(context, theme, def.appearance)
        }.apply {
            soundEffect = when (def) {
                is SpaceKey -> InputFeedbacks.SoundEffect.SpaceBar
                is MiniSpaceKey -> InputFeedbacks.SoundEffect.SpaceBar
                is BackspaceKey -> InputFeedbacks.SoundEffect.Delete
                is ReturnKey -> InputFeedbacks.SoundEffect.Return
                else -> InputFeedbacks.SoundEffect.Standard
            }
            slideSwitchable = def is AlphabetKey || def is AlphabetDigitKey ||
                    def is SymbolKey || def is NumPadKey
            commitOnNextPointer = slideSwitchable || def is SpaceKey ||
                    def is BackspaceKey || def is CommaKey
            if (slideSwitchable) {
                outsideTolerance = keyOutsideTolerance
            }
            if (def is AlphabetKey) {
                def.character.lowercase().singleOrNull()?.let { alphabetKeyViews.add(it to this) }
            }
            if (def is SpaceKey) {
                spaceKeys.add(this)
                swipeEnabled = spaceSwipeMoveCursor.getValue()
                swipeRepeatEnabled = true
                swipeStartDelay = spaceSwipeStartDelay
                swipeThresholdX = spaceSwipeThreshold
                swipeThresholdY = disabledSwipeThreshold
                onGestureListener = OnGestureListener { view, event ->
                    when (event.type) {
                        GestureType.Move -> when (val count = event.countX) {
                            0 -> false
                            else -> {
                                val sym =
                                    if (count > 0) FcitxKeyMapping.FcitxKey_Right else FcitxKeyMapping.FcitxKey_Left
                                val action = KeyAction.SymAction(KeySym(sym), KeyStates.Virtual)
                                repeat(count.absoluteValue) {
                                    onAction(action)
                                    if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                                }
                                true
                            }
                        }
                        else -> false
                    }
                }
            } else if (def is BackspaceKey) {
                swipeEnabled = true
                swipeRepeatEnabled = true
                swipeThresholdX = selectionSwipeThreshold
                swipeThresholdY = disabledSwipeThreshold
                onGestureListener = OnGestureListener { view, event ->
                    when (event.type) {
                        GestureType.Move -> {
                            val count = event.countX
                            if (count != 0) {
                                onAction(KeyAction.MoveSelectionAction(count))
                                if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                                true
                            } else false
                        }
                        GestureType.Up -> {
                            onAction(KeyAction.DeleteSelectionAction(event.totalX))
                            false
                        }
                        else -> false
                    }
                }
            }
            def.behaviors.forEach {
                when (it) {
                    is KeyDef.Behavior.Press -> {
                        setOnClickListener { view ->
                            onAction(withTouchAlternatives(it.action, def, view as KeyView))
                        }
                    }
                    is KeyDef.Behavior.LongPress -> {
                        setOnLongClickListener { _ ->
                            onAction(it.action)
                            true
                        }
                    }
                    is KeyDef.Behavior.Repeat -> {
                        repeatEnabled = true
                        onRepeatListener = { view ->
                            onAction(it.action)
                            if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                        }
                    }
                    is KeyDef.Behavior.Swipe -> {
                        swipeEnabled = true
                        swipeThresholdX = disabledSwipeThreshold
                        swipeThresholdY = inputSwipeThreshold
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        onGestureListener = OnGestureListener { view, event ->
                            when (event.type) {
                                GestureType.Up -> {
                                    if (!event.consumed && swipeSymbolDirection.checkY(event.totalY)) {
                                        onAction(it.action)
                                        true
                                    } else {
                                        false
                                    }
                                }
                                else -> false
                            } || oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Behavior.DoubleTap -> {
                        doubleTapEnabled = true
                        onDoubleTapListener = { _ ->
                            onAction(it.action)
                        }
                    }
                }
            }
            def.popup?.forEach {
                when (it) {
                    // TODO: gesture processing middleware
                    is KeyDef.Popup.Menu -> {
                        setOnLongClickListener { view ->
                            view as KeyView
                            onPopupAction(PopupAction.ShowMenuAction(view.id, it, view.bounds))
                            // do not consume this LongClick gesture
                            false
                        }
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        swipeEnabled = true
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            when (event.type) {
                                GestureType.Move -> {
                                    onPopupChangeFocus(view.id, event.x, event.y)
                                }
                                GestureType.Up -> {
                                    onPopupTrigger(view.id)
                                }
                                else -> false
                            } || oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Popup.Keyboard -> {
                        setOnLongClickListener { view ->
                            view as KeyView
                            onPopupAction(PopupAction.ShowKeyboardAction(view.id, it, view.bounds))
                            // do not consume this LongClick gesture
                            false
                        }
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        swipeEnabled = true
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            when (event.type) {
                                GestureType.Move -> {
                                    onPopupChangeFocus(view.id, event.x, event.y)
                                }
                                GestureType.Up -> {
                                    onPopupTrigger(view.id)
                                }
                                else -> false
                            } || oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Popup.AltPreview -> {
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            if (popupOnKeyPress) {
                                when (event.type) {
                                    GestureType.Down -> onPopupAction(
                                        PopupAction.PreviewAction(view.id, it.content, view.bounds)
                                    )
                                    GestureType.Move -> {
                                        val triggered = swipeSymbolDirection.checkY(event.totalY)
                                        val text = if (triggered) it.alternative else it.content
                                        onPopupAction(
                                            PopupAction.PreviewUpdateAction(view.id, text)
                                        )
                                    }
                                    GestureType.Up -> {
                                        onPopupAction(PopupAction.DismissAction(view.id))
                                    }
                                }
                            }
                            // never consume gesture in preview popup
                            oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Popup.Preview -> {
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            if (popupOnKeyPress) {
                                when (event.type) {
                                    GestureType.Down -> onPopupAction(
                                        PopupAction.PreviewAction(view.id, it.content, view.bounds)
                                    )
                                    GestureType.Up -> {
                                        onPopupAction(PopupAction.DismissAction(view.id))
                                    }
                                    else -> {}
                                }
                            }
                            // never consume gesture in preview popup
                            oldOnGestureListener.onGesture(view, event)
                        }
                    }
                }
            }
        }
    }

    private class TouchTarget(val view: KeyView, val hitRect: Rect, val downX: Float, val downY: Float)

    /**
     * HashMap of [PointerId (Int)][MotionEvent.getPointerId] to [TouchTarget]
     * for custom touch event dispatching
     */
    private val touchTargets = hashMapOf<Int, TouchTarget>()

    private fun releaseAllTouchTargets() {
        touchTargets.forEach {
            val keyView = it.value.view
            keyView.cancelGestures()
            onPopupAction(PopupAction.DismissAction(keyView.id))
        }
        touchTargets.clear()
    }

    private fun findTouchTarget(event: MotionEvent, pointerIndex: Int): TouchTarget? {
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        val x0 = x.roundToInt()
        val y0 = y.roundToInt()
        val rowHitRect = Rect()
        val row = keyRows.find {
            it.getHitRect(rowHitRect)
            rowHitRect.contains(x0, y0)
        } ?: return null
        val x1 = x0 - rowHitRect.left
        val y1 = y0 - rowHitRect.top
        val keyHitRect = Rect()
        val key = row.children.filterIsInstance<KeyView>().find {
            it.getHitRect(keyHitRect)
            keyHitRect.contains(x1, y1)
        } ?: return null
        keyHitRect.offset(rowHitRect.left, rowHitRect.top)
        return TouchTarget(key, keyHitRect, x, y)
    }

    private fun dispatchMotionEventToTarget(
        event: MotionEvent,
        action: Int,
        pointerIndex: Int,
        target: TouchTarget
    ) {
        val childX = event.getX(pointerIndex) - target.hitRect.left
        val childY = event.getY(pointerIndex) - target.hitRect.top
        val e = MotionEvent.obtain(
            event.downTime, event.eventTime, action,
            childX, childY, event.getPressure(pointerIndex), event.getSize(pointerIndex),
            event.metaState, event.xPrecision, event.yPrecision,
            event.deviceId, event.edgeFlags
        )
        target.view.dispatchTouchEvent(e)
        e.recycle()
    }

    /**
     * Google Pinyin style rollover: when another finger touches down, keys held by other fingers
     * are committed right away, so fast two-thumb typing keeps the order of key presses.
     * Keys showing a long press popup, repeating or in a gesture are left alone.
     */
    private fun commitHeldKeys(event: MotionEvent) {
        val iterator = touchTargets.entries.iterator()
        while (iterator.hasNext()) {
            val (pid, target) = iterator.next()
            if (!target.view.commitOnNextPointer || !target.view.canSlideAway) continue
            val index = event.findPointerIndex(pid)
            if (index < 0) continue
            dispatchMotionEventToTarget(event, MotionEvent.ACTION_UP, index, target)
            iterator.remove()
        }
    }

    /**
     * Google Pinyin style sliding: when the finger leaves a letter key for another one, the press
     * moves to the key under the finger instead of being lost. A vertical slide in the direction
     * of the swipe symbol stays on the key, so swipe input still works.
     */
    private fun maybeSlideToAnotherKey(event: MotionEvent, index: Int, target: TouchTarget): Boolean {
        val view = target.view
        if (!view.slideSwitchable || !view.canSlideAway) return false
        val x = event.getX(index)
        val y = event.getY(index)
        if (target.hitRect.contains(x.roundToInt(), y.roundToInt())) return false
        val dx = x - target.downX
        val dy = y - target.downY
        if (view.swipeEnabled && dy.absoluteValue >= dx.absoluteValue &&
            swipeSymbolDirection != SwipeSymbolDirection.Disabled &&
            (dy > 0) == (swipeSymbolDirection == SwipeSymbolDirection.Down)
        ) return false
        val newTarget = findTouchTarget(event, index) ?: return false
        if (newTarget.view === view || !newTarget.view.slideSwitchable) return false
        dispatchMotionEventToTarget(event, MotionEvent.ACTION_CANCEL, index, target)
        onPopupAction(PopupAction.DismissAction(view.id))
        newTarget.view.markNextPressSlidIn()
        touchTargets[event.getPointerId(index)] = newTarget
        dispatchMotionEventToTarget(event, MotionEvent.ACTION_DOWN, index, newTarget)
        return true
    }

    /**
     * Attach the keys a letter key touch may have meant, see [SpatialModel].
     */
    private fun withTouchAlternatives(action: KeyAction, def: KeyDef, view: KeyView): KeyAction {
        if (!spatialCorrection || action !is KeyAction.FcitxKeyAction || def !is AlphabetKey) {
            return action
        }
        // Google Pinyin skips the spatial model when the finger slid onto the key
        if (view.pressSlidIn) return action
        val char = def.character.lowercase().singleOrNull() ?: return action
        val keys = alphabetKeyViews.mapNotNull { (c, v) ->
            val row = v.parent as? View ?: return@mapNotNull null
            val left = (row.left + v.left).toFloat()
            val top = (row.top + v.top).toFloat()
            SpatialModel.Key(c, left, top, left + v.width, top + v.height)
        }
        val row = view.parent as? View ?: return action
        val x = row.left + view.left + view.lastUpX
        val y = row.top + view.top + view.lastUpY
        val dm = resources.displayMetrics
        val alternatives = SpatialModel(keys, dm.xdpi, dm.ydpi).alternatives(char, x, y)
            ?: return action
        return action.copy(alternatives = alternatives)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // intercept ACTION_DOWN and all following events will go to parent's onTouchEvent,
        // so that we can move a touch between keys and order multi-touch key presses
        return ev.actionMasked == MotionEvent.ACTION_DOWN || super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                releaseAllTouchTargets()
                val pid = event.getPointerId(0)
                val target = findTouchTarget(event, 0) ?: return false
                touchTargets[pid] = target
                dispatchMotionEventToTarget(event, MotionEvent.ACTION_DOWN, 0, target)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                commitHeldKeys(event)
                val i = event.actionIndex
                val pid = event.getPointerId(i)
                val target = findTouchTarget(event, i) ?: return true
                touchTargets[pid] = target
                dispatchMotionEventToTarget(event, MotionEvent.ACTION_DOWN, i, target)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val target = touchTargets[pid] ?: continue
                    if (maybeSlideToAnotherKey(event, i, target)) continue
                    dispatchMotionEventToTarget(event, MotionEvent.ACTION_MOVE, i, target)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                val pid = event.getPointerId(i)
                val target = touchTargets[pid] ?: return true
                dispatchMotionEventToTarget(event, MotionEvent.ACTION_UP, i, target)
                touchTargets.remove(pid)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val pid = event.getPointerId(0)
                val target = touchTargets[pid]
                if (target == null) {
                    releaseAllTouchTargets()
                    return true
                }
                dispatchMotionEventToTarget(event, MotionEvent.ACTION_UP, 0, target)
                touchTargets.remove(pid)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseAllTouchTargets()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    @CallSuper
    protected open fun onAction(
        action: KeyAction,
        source: KeyActionListener.Source = KeyActionListener.Source.Keyboard
    ) {
        keyActionListener?.onKeyAction(action, source)
    }

    @CallSuper
    protected open fun onPopupAction(action: PopupAction) {
        popupActionListener?.onPopupAction(action)
    }

    private fun onPopupChangeFocus(viewId: Int, x: Float, y: Float): Boolean {
        val changeFocusAction = PopupAction.ChangeFocusAction(viewId, x, y)
        popupActionListener?.onPopupAction(changeFocusAction)
        return changeFocusAction.outResult
    }

    private fun onPopupTrigger(viewId: Int): Boolean {
        val triggerAction = PopupAction.TriggerAction(viewId)
        // ask popup keyboard whether there's a pending KeyAction
        onPopupAction(triggerAction)
        val action = triggerAction.outAction ?: return false
        onAction(action, KeyActionListener.Source.Popup)
        onPopupAction(PopupAction.DismissAction(viewId))
        return true
    }

    open fun onAttach() {
        // do nothing by default
    }

    init {
        // Google Pinyin labels the key going back to letters "返回" (back)
        findViewById<TextKeyView>(org.fcitx.fcitx5.android.R.id.button_back_to_text)?.mainText?.text =
            context.getString(org.fcitx.fcitx5.android.R.string.back_to_text_keyboard)
    }

    open fun onReturnDrawableUpdate(@DrawableRes returnDrawable: Int) {
        // do nothing by default
    }

    open fun onPunctuationUpdate(mapping: Map<String, String>) {
        // do nothing by default
    }

    open fun onInputMethodUpdate(ime: InputMethodEntry) {
        // do nothing by default
    }

    /**
     * Whether there is text being composed (e.g. pinyin typed but not committed yet)
     */
    open fun onPreeditEmptyStateUpdate(empty: Boolean) {
        // do nothing by default
    }

    open fun onDetach() {
        releaseAllTouchTargets()
    }

}