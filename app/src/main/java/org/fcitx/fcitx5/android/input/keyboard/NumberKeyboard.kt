/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupAction
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.imageResource

/**
 * Google Pinyin 4.x digit keyboard (keyboard_non_prime_digit): a scrollable column of + - * / ( )
 * showing four at a time on the left, 1-9 in the middle, # space backspace on the right, and
 * 返回 , @?# 0 = . return at the bottom. Columns weigh 149/702/149. Symbols are
 * committed as is, never as full width punctuation.
 */
@SuppressLint("ViewConstructor")
class NumberKeyboard(
    context: Context,
    theme: Theme,
) : BaseKeyboard(context, theme, Layout) {

    companion object {
        const val Name = "Number"

        private const val SIDE = 0.149f
        private const val DIGIT = 0.234f

        /** Google Pinyin's left panel, four visible at a time */
        private val LeftPanel = listOf(
            NumPadTextKey("+", percentWidth = 1f),
            NumPadTextKey("-", percentWidth = 1f),
            NumPadTextKey("*", percentWidth = 1f),
            NumPadTextKey("/", percentWidth = 1f),
            NumPadTextKey("(", percentWidth = 1f),
            NumPadTextKey(")", percentWidth = 1f),
        )
        private const val LEFT_PANEL_VISIBLE = 4

        /** Room for the left panel, which is laid over it */
        private fun panelSpace() = KeyDef(
            KeyDef.Appearance.Text("", textSize = 16f, percentWidth = SIDE, border = KeyDef.Appearance.Border.Off),
            emptySet()
        )

        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                panelSpace(),
                NumPadKey("1", 0xffb1, 30f, DIGIT),
                NumPadKey("2", 0xffb2, 30f, DIGIT),
                NumPadKey("3", 0xffb3, 30f, DIGIT),
                NumPadTextKey("#", percentWidth = SIDE),
            ),
            listOf(
                panelSpace(),
                NumPadKey("4", 0xffb4, 30f, DIGIT),
                NumPadKey("5", 0xffb5, 30f, DIGIT),
                NumPadKey("6", 0xffb6, 30f, DIGIT),
                MiniSpaceKey(SIDE)
            ),
            listOf(
                panelSpace(),
                NumPadKey("7", 0xffb7, 30f, DIGIT),
                NumPadKey("8", 0xffb8, 30f, DIGIT),
                NumPadKey("9", 0xffb9, 30f, DIGIT),
                BackspaceKey(SIDE)
            ),
            listOf(
                // Digit keyboard bottom row: 150/100/100/300/100/100/150
                LayoutSwitchKey("返回", TextKeyboard.Name, viewId = R.id.button_back_to_text),
                NumPadTextKey(","),
                LayoutSwitchKey("@?#", PickerWindow.Key.Symbol.name, 0.1f, KeyDef.Appearance.Variant.AltForeground),
                NumPadKey("0", 0xffb0, 30f, 0.3f),
                NumPadTextKey("=", variant = KeyDef.Appearance.Variant.AltForeground),
                NumPadTextKey("."),
                ReturnKey()
            )
        )
    }

    val backspace: ImageKeyView by lazy { findViewById(R.id.button_backspace) }
    val space: ImageKeyView by lazy { findViewById(R.id.button_mini_space) }
    val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }

    private val leftPanelKeys = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        LeftPanel.forEach { addView(createKeyView(it), LinearLayout.LayoutParams(matchParent, 0)) }
    }

    private val leftPanel = ScrollView(context).apply {
        isVerticalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(leftPanelKeys, LinearLayout.LayoutParams(matchParent, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    init {
        // over the panel spaces of the first three rows
        add(leftPanel, lParams(0, 0) {
            topToTop = keyRows[0].id
            bottomToBottom = keyRows[2].id
            startOfParent()
            matchConstraintPercentWidth = SIDE
        })
        leftPanel.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top == oldBottom - oldTop) return@addOnLayoutChangeListener
            val height = (bottom - top) / LEFT_PANEL_VISIBLE
            leftPanelKeys.children.forEach { it.updateLayoutParams { this.height = height } }
        }
    }

    private val leftPanelRect = Rect()

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // the left panel scrolls and handles its own keys
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            leftPanel.getHitRect(leftPanelRect)
            if (leftPanelRect.contains(ev.x.toInt(), ev.y.toInt())) return false
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`.img.imageResource = returnDrawable
    }

    @SuppressLint("MissingSuperCall")
    override fun onPopupAction(action: PopupAction) {
        // leave empty on purpose to disable popup in NumberKeyboard
    }

}
