/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.emoji

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.rippleDrawable
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenterVertical
import splitties.views.horizontalPadding

/**
 * Gboard style emoji search: a search box in the bar, a row of matching emojis, and a full
 * keyboard below. Text typed (including pinyin converted to Chinese) goes into the search box
 * instead of the editor; tapping a result inserts it into the editor.
 */
class EmojiSearchWindow : InputWindow.ExtendedInputWindow<EmojiSearchWindow>(),
    InputBroadcastReceiver, FcitxInputMethodService.TextSink {

    private val service by manager.inputMethodService()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val popup: PopupComponent by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()

    private val query = StringBuilder()

    private lateinit var resultsRow: LinearLayout
    private lateinit var keyboard: TextKeyboard

    private val keyActionListener = KeyActionListener { action, source ->
        // leaving to symbol/emoji/number keyboards ends the search
        commonKeyActionListener.listener.onKeyAction(action, source)
    }

    override val title: String = ""

    override val takesKeyboardInput = true

    // created on first use: onAttached may run before the bar asks for its extension
    private val queryView: TextView by lazy {
        context.textView {
            textSize = 16f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.START
            gravity = gravityCenterVertical
            setTextColor(theme.keyTextColor)
            setHintTextColor(theme.altKeyTextColor)
            hint = context.getString(R.string.emoji_search_hint)
            horizontalPadding = dp(4)
        }
    }

    override fun onCreateBarExtension(): View = queryView

    override fun onCreateView(): View = context.verticalLayout {
        resultsRow = horizontalLayout { gravity = gravityCenterVertical }
        add(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(resultsRow, LinearLayout.LayoutParams(wrapContent, matchParent))
        }, lParams(matchParent, dp(RESULTS_HEIGHT)))
        keyboard = TextKeyboard(context, theme)
        add(keyboard, lParams(matchParent, 0) { weight = 1f })
    }

    private fun updateResults() {
        queryView.text = query
        resultsRow.removeAllViews()
        val emojis = if (query.isEmpty()) emptyList() else EmojiSearch.search(query.toString())
        if (query.isNotEmpty() && emojis.isEmpty()) {
            resultsRow.addView(context.textView {
                text = context.getString(R.string.emoji_search_no_result)
                setTextColor(theme.altKeyTextColor)
                horizontalPadding = dp(12)
            })
            return
        }
        emojis.forEach { emoji ->
            resultsRow.addView(context.textView {
                text = emoji
                textSize = 26f
                gravity = Gravity.CENTER
                background = rippleDrawable(theme.keyPressHighlightColor)
                setOnClickListener {
                    InputFeedbacks.hapticFeedback(it)
                    service.commitTextToEditor(emoji)
                }
            }, LinearLayout.LayoutParams(context.dp(RESULTS_HEIGHT), LinearLayout.LayoutParams.MATCH_PARENT))
        }
    }

    /* ========== TextSink ========== */

    override fun commit(text: String) {
        query.append(text)
        updateResults()
    }

    override fun deleteBackward() {
        if (query.isEmpty()) return
        query.delete(query.offsetByCodePoints(query.length, -1), query.length)
        updateResults()
    }

    /* ========== lifecycle ========== */

    override fun onAttached() {
        query.clear()
        service.textSink = this
        keyboard.keyActionListener = keyActionListener
        keyboard.popupActionListener = popup.listener
        keyboard.onAttach()
        keyboard.onReturnDrawableUpdate(returnKeyDrawable.resourceId)
        keyboard.onInputMethodUpdate(fcitx.runImmediately { inputMethodEntryCached })
        updateResults()
    }

    override fun onDetached() {
        // drop pinyin being composed for the search box
        fcitx.launchOnReady { it.reset() }
        service.textSink = null
        keyboard.onDetach()
        keyboard.keyActionListener = null
        keyboard.popupActionListener = null
        popup.dismissAll()
    }

    /* ========== broadcasts for the embedded keyboard ========== */

    override fun onImeUpdate(ime: InputMethodEntry) {
        keyboard.onInputMethodUpdate(ime)
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        keyboard.onPunctuationUpdate(mapping)
    }

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        keyboard.onPreeditEmptyStateUpdate(empty)
    }

    override fun onReturnKeyDrawableUpdate(resourceId: Int) {
        keyboard.onReturnDrawableUpdate(resourceId)
    }

    companion object {
        private const val RESULTS_HEIGHT = 44
    }
}

/**
 * Lazily loaded Chinese + English emoji keyword data shipped in assets/emoji
 */
object EmojiSearch {
    private val searcher by lazy {
        EmojiSearcher(listOf("zh", "en").mapNotNull { lang ->
            runCatching {
                appContext.assets.open("emoji/$lang.dict").use { EmojiSearchIndex.parse(it.readBytes()) }
            }.getOrNull()
        })
    }

    fun search(query: String): List<String> = searcher.search(query)

    /** Parse the data off the main thread before the first search */
    fun preload() {
        Thread { searcher }.start()
    }
}
