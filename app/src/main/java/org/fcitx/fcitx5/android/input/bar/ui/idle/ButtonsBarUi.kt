/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import androidx.annotation.DrawableRes
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.borderlessRippleDrawable
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view

class ButtonsBarUi(override val ctx: Context, private val theme: Theme) : Ui {

    override val root = view(::FlexboxLayout) {
        alignItems = AlignItems.CENTER
        justifyContent = JustifyContent.SPACE_AROUND
    }

    private fun toolButton(@DrawableRes icon: Int) = ToolButton(ctx, icon, theme).also {
        val size = ctx.dp(40)
        root.addView(it, FlexboxLayout.LayoutParams(size, size))
    }

    /**
     * Google Pinyin style "中/英" switch, toggles between the active input method
     * and the inactive (keyboard) one.
     */
    val langToggleButton = LangToggleButton(ctx, theme).also {
        val size = ctx.dp(40)
        root.addView(it, FlexboxLayout.LayoutParams(size, size))
    }

    val undoButton = toolButton(R.drawable.ic_baseline_undo_24).apply {
        contentDescription = ctx.getString(R.string.undo)
    }

    val redoButton = toolButton(R.drawable.ic_baseline_redo_24).apply {
        contentDescription = ctx.getString(R.string.redo)
    }

    val cursorMoveButton = toolButton(R.drawable.ic_cursor_move).apply {
        contentDescription = ctx.getString(R.string.text_editing)
    }

    val clipboardButton = toolButton(R.drawable.ic_clipboard).apply {
        contentDescription = ctx.getString(R.string.clipboard)
    }

    /**
     * Quick entry to theme (palette) settings, otherwise behind "more"
     */
    val themeButton = toolButton(R.drawable.ic_baseline_palette_24).apply {
        contentDescription = ctx.getString(R.string.theme)
    }

    val moreButton = toolButton(R.drawable.ic_baseline_more_horiz_24).apply {
        contentDescription = ctx.getString(R.string.status_area)
    }

}

class LangToggleButton(ctx: Context, theme: Theme) : CustomGestureView(ctx) {

    private val label = textView {
        isClickable = false
        isFocusable = false
        gravity = Gravity.CENTER
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(theme.altKeyTextColor)
        text = LABEL_CHINESE
    }

    init {
        background = borderlessRippleDrawable(theme.keyPressHighlightColor, dp(20))
        add(label, lParams(matchParent, matchParent))
        contentDescription = ctx.getString(R.string.toggle_chinese_english)
    }

    fun updateIme(ime: InputMethodEntry) {
        label.text = labelOf(ime)
    }

    companion object {
        const val LABEL_CHINESE = "中"
        const val LABEL_ENGLISH = "英"

        fun labelOf(ime: InputMethodEntry): String = when {
            ime.languageCode.startsWith("zh") -> LABEL_CHINESE
            ime.uniqueName.startsWith("keyboard-") -> LABEL_ENGLISH
            ime.label.isNotBlank() -> ime.label.take(2)
            else -> LABEL_ENGLISH
        }
    }
}
