/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.TwoStatePreference
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.data.InputFeedbacks.InputFeedbackMode
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightMode
import org.fcitx.fcitx5.android.input.keyboard.LangSwitchBehavior
import org.fcitx.fcitx5.android.input.keyboard.SlideSensitivity
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.toast

/**
 * Settings laid out like Google Pinyin 4.x (输入法 / 键盘 / 字典), mapped onto fcitx options,
 * for quickly setting up the keyboard. Items of Google Pinyin without an fcitx counterpart
 * (glide typing, handwriting, contacts, sync, ...) are left out.
 */
class QuickSettingsFragment : PaddingPreferenceFragment() {

    private val viewModel: MainViewModel by activityViewModels()

    private val kbd = AppPrefs.getInstance().keyboard

    /** fcitx pinyin addon config ("cfg" part), loaded asynchronously */
    private var pinyinCfg: RawConfig? = null
    private var chttransCfg: RawConfig? = null
    private var enabledIme: List<String> = emptyList()

    private fun PreferenceGroup.category(title: String, block: PreferenceCategory.() -> Unit) {
        val category = PreferenceCategory(context).apply {
            this.title = title
            isIconSpaceReserved = false
        }
        addPreference(category)
        category.block()
    }

    private fun PreferenceGroup.switch(
        title: String,
        summary: String? = null,
        checked: () -> Boolean,
        onChange: (Boolean) -> Unit
    ) = MySwitchPreference(context).also {
        it.key = "quick_settings:$title"
        it.isPersistent = false
        it.isIconSpaceReserved = false
        it.isSingleLineTitle = false
        it.title = title
        it.summary = summary
        it.isChecked = checked()
        it.setOnPreferenceChangeListener { _, v ->
            onChange(v as Boolean)
            true
        }
        addPreference(it)
    }

    private fun PreferenceGroup.list(
        title: String,
        entries: List<Pair<String, String>>,
        current: () -> String,
        onChange: (String) -> Unit
    ) = ListPreference(context).also {
        // list dialogs find their preference by key
        it.key = "quick_settings:$title"
        it.isPersistent = false
        it.isIconSpaceReserved = false
        it.isSingleLineTitle = false
        it.title = title
        it.dialogTitle = title
        it.entries = entries.map { e -> e.first }.toTypedArray()
        it.entryValues = entries.map { e -> e.second }.toTypedArray()
        it.value = current()
        it.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        it.setOnPreferenceChangeListener { _, v ->
            onChange(v as String)
            true
        }
        addPreference(it)
    }

    /** Reuse the UI fcitx defines for an AppPrefs item, with Google Pinyin's title */
    private fun PreferenceGroup.managed(
        provider: ManagedPreferenceProvider,
        pref: ManagedPreference<*>,
        title: String
    ): Preference? {
        val ui = provider.managedPreferencesUi.find { it.key == pref.key } ?: return null
        return ui.createUi(context).also {
            it.title = title
            addPreference(it)
        }
    }

    private fun Preference.dependsOn(enabled: () -> Boolean, vararg masters: Preference) {
        isEnabled = enabled()
        masters.forEach { master ->
            val old = master.onPreferenceChangeListener
            master.setOnPreferenceChangeListener { p, v ->
                val accepted = old?.onPreferenceChange(p, v) ?: true
                // evaluate after the new value is applied
                view?.post { isEnabled = enabled() }
                accepted
            }
        }
    }

    /* ========== fcitx config access ========== */

    private fun RawConfig.bool(path: String): Boolean =
        path.split('/').fold(this as RawConfig?) { c, n -> c?.findByName(n) }?.value == "True"

    private fun RawConfig.setBool(path: String, value: Boolean) {
        val node = path.split('/').fold(this) { c, n -> c.getOrCreate(n) }
        node.value = if (value) "True" else "False"
    }

    private fun updatePinyin(block: RawConfig.() -> Unit) {
        val cfg = pinyinCfg ?: return
        cfg.block()
        lifecycleScope.launch { viewModel.fcitx.runOnReady { setImConfig("pinyin", cfg) } }
    }

    private suspend fun FcitxAPI.loadFcitxState() {
        pinyinCfg = getImConfig("pinyin").findByName("cfg")
        chttransCfg = getAddonConfig("chttrans").findByName("cfg")
        enabledIme = enabledIme().map { it.uniqueName }
    }

    /* ========== 拼音方案 ========== */

    private val shuangpinProfiles = listOf(
        "双拼 - 微软" to "MS",
        "双拼 - 紫光" to "Ziguang",
        "双拼 - 拼音加加" to "PinyinJiajia",
        "双拼 - 智能ABC" to "ABC",
        "双拼 - 自然码" to "Ziranma",
        "双拼 - 小鹤双拼" to "Xiaohe",
    )

    private fun currentScheme(): String =
        if ("shuangpin" in enabledIme) pinyinCfg?.findByName("ShuangpinProfile")?.value ?: "Ziranma"
        else QUANPIN

    private fun applyScheme(scheme: String) {
        val chinese = if (scheme == QUANPIN) "pinyin" else "shuangpin"
        val list = enabledIme.filter { it != "pinyin" && it != "shuangpin" }.toMutableList()
        // keep English keyboard first (inactive input method), Chinese right after it
        val index = list.indexOf("keyboard-us").let { if (it >= 0) it + 1 else 0 }
        list.add(index, chinese)
        enabledIme = list
        if (scheme != QUANPIN) {
            pinyinCfg?.getOrCreate("ShuangpinProfile")?.value = scheme
        }
        val cfg = pinyinCfg
        lifecycleScope.launch {
            viewModel.fcitx.runOnReady {
                if (cfg != null) setImConfig("pinyin", cfg)
                setEnabledIme(list.toTypedArray())
            }
        }
    }

    /* ========== 繁体中文 (chttrans) ========== */

    private fun chttransEnabledIms(): List<String> =
        chttransCfg?.findByName("EnabledIM")?.subItems?.map { it.value } ?: emptyList()

    private fun setTraditional(enabled: Boolean) {
        val cfg = chttransCfg ?: return
        val ims = chttransEnabledIms().filter { it != "pinyin" && it != "shuangpin" } +
                if (enabled) listOf("pinyin", "shuangpin") else emptyList()
        cfg.getOrCreate("EnabledIM").subItems =
            ims.mapIndexed { i, im -> RawConfig(i.toString(), im) }.toTypedArray()
        lifecycleScope.launch { viewModel.fcitx.runOnReady { setAddonConfig("chttrans", cfg) } }
    }

    /* ========== 模糊拼音 ========== */

    /** Google Pinyin fuzzy pairs and whether they are on when fuzzy pinyin gets enabled */
    private val fuzzyPairs = listOf(
        Triple("z = zh", "Z_ZH", true),
        Triple("c = ch", "C_CH", true),
        Triple("s = sh", "S_SH", true),
        Triple("l = n", "L_N", false),
        Triple("f = h", "F_H", false),
        Triple("r = l", "L_R", false),
        Triple("an = ang", "AN_ANG", true),
        Triple("en = eng", "EN_ENG", true),
        Triple("in = ing", "IN_ING", true),
        Triple("ian = iang", "IAN_IANG", false),
        Triple("uan = uang", "UAN_UANG", false),
    )

    private fun fuzzyEnabled() = fuzzyPairs.any { pinyinCfg?.bool("Fuzzy/${it.second}") == true }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        lifecycleScope.launch {
            viewModel.fcitx.runOnReady { loadFcitxState() }
            buildScreen()
        }
    }

    private fun buildScreen() {
        val screen = preferenceScreen
        screen.removeAll()

        screen.category("输入法") {
            managed(kbd, kbd.doubleSpacePeriod, "双击空格键可插入句号")
            managed(kbd, kbd.spaceSwipeMoveCursor, "启用滑行光标控制功能")?.summary = "滑动空格键即可移动光标"
        }

        screen.category("中文输入法") {
            list(
                "拼音方案",
                listOf("全拼" to QUANPIN) + shuangpinProfiles,
                { currentScheme() },
                ::applyScheme
            )
            switch("繁体中文", null, { "pinyin" in chttransEnabledIms() }, ::setTraditional)
            switch("建议英语单词", "在输入拼音时建议热门英语单词", { pinyinCfg?.bool("SpellEnabled") == true }) {
                updatePinyin { setBool("SpellEnabled", it) }
            }
            switch("智能更正", "错别字智能更正", { kbd.spatialCorrection.getValue() }) {
                kbd.spatialCorrection.setValue(it)
                updatePinyin { getOrCreate("Fuzzy").getOrCreate("Correction").value = if (it) "QWERTY" else "None" }
            }
            switch("中文联想", "根据上一个字词提供建议", { pinyinCfg?.bool("Prediction") == true }) {
                updatePinyin { setBool("Prediction", it) }
            }
            // off like Google Pinyin: v stays pinyin, so vpn, vip, vivo are typed like any word
            switch("V 键快速输入", "开头的 v 进入快速输入，关闭时 v 照常打拼音", {
                pinyinCfg?.bool("VAsQuickphrase") == true
            }) {
                updatePinyin { setBool("VAsQuickphrase", it) }
            }
            val master = switch("模糊拼音", null, { fuzzyEnabled() }) { on ->
                // like Google Pinyin, turning it on enables its default pairs
                updatePinyin { fuzzyPairs.forEach { (_, key, default) -> setBool("Fuzzy/$key", on && default) } }
                view?.post { buildScreen() }
            }
            fuzzyPairs.forEach { (title, key, _) ->
                switch(title, null, { pinyinCfg?.bool("Fuzzy/$key") == true }) { on ->
                    updatePinyin { setBool("Fuzzy/$key", on) }
                }.dependsOn({ master.isChecked }, master)
            }
        }

        screen.category("键盘") {
            addPreference("主题背景") { navigateWithAnim(SettingsRoute.Theme) }
            val sound = switch("按键声音", null, {
                kbd.soundOnKeyPress.getValue() == InputFeedbackMode.Enabled
            }) {
                kbd.soundOnKeyPress.setValue(if (it) InputFeedbackMode.Enabled else InputFeedbackMode.Disabled)
            }
            managed(kbd, kbd.soundOnKeyPressVolume, "按键音量")?.dependsOn({ sound.isChecked }, sound)
            val vibrate = switch("按键时振动", null, {
                kbd.hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled
            }) {
                kbd.hapticOnKeyPress.setValue(if (it) InputFeedbackMode.Enabled else InputFeedbackMode.Disabled)
            }
            managed(kbd, kbd.buttonPressVibrationMilliseconds, "按键振动强度")
                ?.dependsOn({ vibrate.isChecked }, vibrate)
            managed(kbd, kbd.popupOnKeyPress, "按键时弹出显示字符")
            managed(kbd, kbd.showVoiceInputButton, "显示语音输入按钮")
            switch("显示英语键盘", null, { "keyboard-us" in enabledIme }) { on ->
                val list = enabledIme.filter { it != "keyboard-us" }.toMutableList()
                if (on) list.add(0, "keyboard-us")
                enabledIme = list
                lifecycleScope.launch { viewModel.fcitx.runOnReady { setEnabledIme(list.toTypedArray()) } }
            }
            val langKey = managed(kbd, kbd.showLangSwitchKey, "显示语言切换键")
            switch("切换到其他输入法", "语言切换键可切换到其他输入法", {
                kbd.langSwitchKeyBehavior.getValue() == LangSwitchBehavior.NextInputMethodApp
            }) {
                kbd.langSwitchKeyBehavior.setValue(
                    if (it) LangSwitchBehavior.NextInputMethodApp else LangSwitchBehavior.ToggleActivate
                )
            }.also { p -> if (langKey != null) p.dependsOn({ (langKey as TwoStatePreference).isChecked }, langKey) }
            list(
                "键盘高度",
                listOf("低" to "90", "中等偏低" to "95", "正常" to "100", "中等偏高" to "105", "高" to "110"),
                {
                    if (kbd.keyboardHeightMode.getValue() == KeyboardHeightMode.GooglePinyin)
                        kbd.googlePinyinHeightRatio.getValue().toString()
                    else "100"
                }
            ) {
                kbd.keyboardHeightMode.setValue(KeyboardHeightMode.GooglePinyin)
                kbd.googlePinyinHeightRatio.setValue(it.toInt())
            }
            list(
                "滑动输入的敏感度",
                SlideSensitivity.entries.map { getString(it.stringRes) to it.name },
                { kbd.slideSensitivity.getValue().name }
            ) {
                kbd.slideSensitivity.setValue(SlideSensitivity.valueOf(it))
            }
            managed(kbd, kbd.longPressDelay, "长按按键的延迟时间")
        }

        screen.category("字典") {
            addPreference("导入或导出用户字典", "谷歌拼音用户字典的导入与导出") {
                navigateWithAnim(SettingsRoute.PinyinDict())
            }
            addPreference("清除用户字典", "删除输入时学到的全部词语") {
                AlertDialog.Builder(requireContext())
                    .setTitle("清除用户字典")
                    .setMessage("将删除输入时学到的全部词语和使用记录，此操作无法撤销。")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        lifecycleScope.launch {
                            viewModel.fcitx.runOnReady { setAddonSubConfig("pinyin", "clearalldict") }
                            requireContext().toast(R.string.done)
                        }
                    }
                    .show()
            }
            addPreference("编辑自定义短语") { navigateWithAnim(SettingsRoute.PinyinCustomPhrase) }
        }

        screen.addPreference(Preference(requireContext()).apply {
            isIconSpaceReserved = false
            isSelectable = false
            summary = "未列出的谷歌拼音设置（滑行输入、手写、单手模式、联系人、同步等）在 Fcitx 中没有对应功能。更多选项见主设置页。"
        })

    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            AppPrefs.getInstance().syncToDeviceEncryptedStorage()
        }
        super.onStop()
    }

    companion object {
        private const val QUANPIN = "quanpin"
    }
}
