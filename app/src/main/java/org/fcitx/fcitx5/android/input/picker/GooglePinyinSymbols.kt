/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.picker.PickerData.Category

/**
 * Chinese symbol keyboard of Google Pinyin 4.5.2 (keyboard_non_prime_symbol_zh):
 * categories 常用, 括号, 箭头, 数学符号, 数字, 图形 after recently used,
 * 28 symbols per page in rows of 10/10/8, and its long press options.
 * Generated from the app's softkeys definitions; keep the order as is.
 */
object GooglePinyinSymbols {

    val Symbol: List<Pair<Category, Array<String>>> = listOf(
        // 常用, 84
        Category("1?#", R.drawable.symbol_number_punctuation) to arrayOf(
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
            "!", "@", "#", "\$", "%", "^", "&", "*", "(", ")",
            "“", "”", "=", "、", "：", "；", "？", "！", "～", "+",
            "-", "/", "\\", "|", "[", "]", "{", "}", "…", "·",
            "<", ">", "—", "_", "‘", "’", "'", "\"", "`", ":",
            ";", "?", "~", "／", "¡", "¿", "￥", "€", "£", "¢",
            "©", "®", "™", "‖", "‗", "‚", "„", "†", "‡", "‥",
            "※", "‼", "⁇", "⁈", "⁉", "℃", "℉", "°", "§", "№",
            "¤", "µ", "々", "〒",
        ),
        // 括号, 56
        Category("( )", R.drawable.symbol_bracket) to arrayOf(
            "(", ")", "[", "]", "{", "}", "（", "）", "［", "］",
            "｛", "｝", "❨", "❩", "❲", "❳", "❴", "❵", "‘", "’",
            "“", "”", "❛", "❜", "❝", "❞", "<", ">", "〈", "〉",
            "《", "》", "〔", "〕", "【", "】", "〘", "〙", "「", "」",
            "『", "』", "︵", "︶", "︷", "︸", "︹", "︺", "︻", "︼",
            "︽", "︾", "︿", "﹀", "﹁", "﹂",
        ),
        // 箭头, 84
        Category("⇄", R.drawable.symbol_arrow) to arrayOf(
            "←", "↑", "→", "↓", "↔", "↕", "↖", "↗", "↘", "↙",
            "↚", "↛", "↜", "↝", "↞", "↟", "↠", "↡", "↢", "↣",
            "↤", "↥", "↦", "↧", "↨", "↩", "↪", "↫", "↬", "↭",
            "↮", "↯", "↰", "↱", "↲", "↳", "↴", "↵", "↶", "↷",
            "↸", "↹", "↺", "↻", "↼", "↽", "↾", "↿", "⇀", "⇁",
            "⇂", "⇃", "⇄", "⇅", "⇆", "⇇", "⇈", "⇉", "⇊", "⇋",
            "⇌", "⇍", "⇎", "⇏", "⇐", "⇑", "⇒", "⇓", "⇔", "⇕",
            "⇖", "⇗", "⇘", "⇙", "⇚", "⇛", "⇜", "⇝", "⇞", "⇟",
            "⇠", "⇡", "⇢", "⇣",
        ),
        // 数学符号, 112
        Category("±", R.drawable.symbol_math) to arrayOf(
            "+", "-", "×", "÷", "±", "=", "≠", "∼", "≈", "≡",
            "<", "≤", "≦", "≪", "≮", ">", "≥", "≧", "≫", "≯",
            "⊕", "⊗", "%", "‰", "∀", "∂", "∃", "∅", "∆", "∇",
            "∈", "∉", "∋", "∌", "∏", "∑", "√", "∝", "∞", "∟",
            "∠", "∧", "∨", "∣", "∥", "∩", "∪", "∫", "∬", "∮",
            "∴", "∵", "∶", "∷", "⊂", "⊃", "⊄", "⊅", "⊆", "⊇",
            "∪", "∩", "ⁿ", "α", "β", "γ", "δ", "ε", "ζ", "η",
            "θ", "ι", "κ", "λ", "μ", "ν", "ξ", "ο", "π", "ρ",
            "ς", "σ", "τ", "υ", "φ", "χ", "ψ", "ω", "Α", "Β",
            "Γ", "Δ", "Ε", "Ζ", "Η", "Θ", "Ι", "Κ", "Λ", "Μ",
            "Ν", "Ξ", "Ο", "Π", "Ρ", "Σ", "Τ", "Υ", "Φ", "Χ",
            "Ψ", "Ω",
        ),
        // 数字, 84
        Category("①", R.drawable.symbol_ordinal) to arrayOf(
            "①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩",
            "⑴", "⑵", "⑶", "⑷", "⑸", "⑹", "⑺", "⑻", "⑼", "⑽",
            "❶", "❷", "❸", "❹", "❺", "❻", "❼", "❽", "❾", "❿",
            "㈠", "㈡", "㈢", "㈣", "㈤", "㈥", "㈦", "㈧", "㈨", "㈩",
            "Ⅰ", "Ⅱ", "Ⅲ", "Ⅳ", "Ⅴ", "Ⅵ", "Ⅶ", "Ⅷ", "Ⅸ", "Ⅹ",
            "Ⅺ", "Ⅻ", "Ⅼ", "Ⅽ", "Ⅾ", "Ⅿ", "ⅰ", "ⅱ", "ⅲ", "ⅳ",
            "ⅴ", "ⅵ", "ⅶ", "ⅷ", "ⅸ", "ⅹ", "ⅺ", "ⅻ", "ⅼ", "ⅽ",
            "ⅾ", "ⅿ", "¹", "²", "³", "⁴", "₁", "₂", "₃", "₄",
            "½", "⅓", "⅔", "㊣",
        ),
        // 图形, 112
        Category("◇", R.drawable.symbol_other) to arrayOf(
            "⚫", "⚪", "●", "○", "■", "□", "⬛", "⬜", "★", "☆",
            "◆", "◇", "▲", "△", "▶", "▷", "▼", "▽", "◀", "◁",
            "◐", "◑", "♀", "♂", "♤", "♡", "♧", "♢", "♠", "♥",
            "♣", "♦", "•", "❖", "✓", "✔", "✕", "✖", "✗", "✘",
            "✙", "✚", "✛", "✜", "✝", "✞", "✟", "✠", "✡", "✢",
            "✣", "✤", "✥", "✦", "✧", "✨", "✩", "✪", "✫", "✬",
            "✭", "✮", "✯", "✰", "✱", "✲", "✳", "✴", "✵", "✶",
            "✷", "✸", "✹", "✺", "✻", "✼", "✽", "✾", "✿", "❀",
            "❁", "❂", "❃", "❄", "❅", "❆", "❇", "❈", "❉", "❊",
            "❋", "❌", "❍", "❎", "❏", "❐", "❑", "❒", "◈", "◉",
            "◊", "○", "◢", "◣", "◤", "◥", "❢", "❣", "❤", "卍",
            "卐", "囍",
        )
    )

    /** Long press options; symbols not listed here have none */
    val LongPress: Map<String, Array<String>> = mapOf(
        "1" to arrayOf("¹", "½", "⅓", "¼", "⅛"),
        "2" to arrayOf("²", "⅔"),
        "3" to arrayOf("³", "¾", "⅜"),
        "4" to arrayOf("⁴"),
        "5" to arrayOf("⅝"),
        "7" to arrayOf("⅞"),
        "0" to arrayOf("∅", "ⁿ"),
        "!" to arrayOf("¡"),
        "\$" to arrayOf("¢", "€", "£", "₱", "¥"),
        "%" to arrayOf("‰"),
        "^" to arrayOf("↑", "↓", "←", "→"),
        "*" to arrayOf("★", "†", "‡"),
        "(" to arrayOf("<", "{", "["),
        ")" to arrayOf(">", "}", "]"),
        "=" to arrayOf("≠", "≈", "∞"),
        "+" to arrayOf("±"),
        "-" to arrayOf("—", "_", "–", "·"),
        "<" to arrayOf("≤", "‹", "«"),
        ">" to arrayOf("≥", "›", "»"),
    )
}

/**
 * Long press options of Google Pinyin's symbol keyboard
 */
class GooglePinyinSymbolPolicy : PickerPolicy {
    override fun filter(raw: String) = true
    override fun transform(raw: String) = raw
    override fun popup(raw: String): KeyDef.Popup.Keyboard? =
        GooglePinyinSymbols.LongPress[raw]?.let { KeyDef.Popup.Keyboard.Explicit(it) }

    override fun invalidateKey() = Unit
}
