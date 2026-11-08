/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.reloadPinyinDict
import org.fcitx.fcitx5.android.data.pinyin.GooglePinyinUserData
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictManager
import org.fcitx.fcitx5.android.data.pinyin.dict.BuiltinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.GooglePinyinUserDict
import org.fcitx.fcitx5.android.data.pinyin.dict.LibIMEDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.ui.common.BaseDynamicListUi
import org.fcitx.fcitx5.android.ui.common.OnItemChangedListener
import org.fcitx.fcitx5.android.ui.main.EditDeleteMenuProvider
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.MainViewModel.ButtonMode
import org.fcitx.fcitx5.android.utils.NaiveDustman
import org.fcitx.fcitx5.android.utils.importErrorDialog
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.notificationManager
import org.fcitx.fcitx5.android.utils.queryFileName
import org.fcitx.fcitx5.android.utils.toast
import java.util.concurrent.atomic.AtomicBoolean

class PinyinDictionaryFragment : Fragment(), OnItemChangedListener<PinyinDictionary> {

    private val args by lazyRoute<SettingsRoute.PinyinDict>()

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var launcher: ActivityResultLauncher<String>

    private lateinit var googleExportLauncher: ActivityResultLauncher<String>

    private val dustman = NaiveDustman<Boolean>()

    private val busy: AtomicBoolean = AtomicBoolean(false)

    private var uiInitialized = false

    private val ui: BaseDynamicListUi<PinyinDictionary> by lazy {
        object : BaseDynamicListUi<PinyinDictionary>(
            requireContext(),
            Mode.Custom(),
            PinyinDictManager.listDictionaries(),
            initCheckBox = { entry ->
                if (entry is LibIMEDictionary) {
                    isChecked = entry.isEnabled
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) entry.enable() else entry.disable()
                        ui.updateItem(ui.indexItem(entry), entry)
                    }
                } else {
                    isChecked = true
                    isEnabled = false
                }
            }
        ) {
            init {
                enableUndo = false
                addTouchCallback()
                // since FAB is always shown in this fragment,
                // set shouldShowFab to true to hide it when entering multi select mode
                shouldShowFab = true
                fab.setOnClickListener {
                    launcher.launch("*/*")
                }
                setViewModel(viewModel)
                removable = { e -> e !is BuiltinDictionary }
            }

            override fun updateFAB() {
                // do nothing
            }

            override fun showEntry(x: PinyinDictionary): String = x.name
        }.also {
            uiInitialized = true
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        createNotificationChannel()
        registerLauncher()
        ui.addOnItemChangedListener(this)
        resetDustman()
        return ui.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        args.uri?.let { importFromUri(Uri.parse(it)) }
        super.onViewCreated(view, savedInstanceState)
        viewModel.toolbarButton.value =
            if (ui.entries.isNotEmpty()) ButtonMode.EDIT else ButtonMode.NONE
        requireActivity().addMenuProvider(
            EditDeleteMenuProvider(
                buttonMode = viewModel.toolbarButton,
                editButtonAction = { ui.enterMultiSelect(requireActivity().onBackPressedDispatcher) },
                deleteButtonAction = { ui.deleteSelected(); ui.exitMultiSelect() },
                menuHost = requireActivity(),
                lifecycleOwner = viewLifecycleOwner,
            ),
            viewLifecycleOwner,
            Lifecycle.State.STARTED
        )
        requireActivity().addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menu.item(R.string.import_google_pinyin_user_dict) {
                        launcher.launch("*/*")
                    }
                    menu.item(R.string.export_google_pinyin_user_dict) {
                        googleExportLauncher.launch(GooglePinyinUserDict.DEFAULT_FILE_NAME)
                    }
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false
            },
            viewLifecycleOwner,
            Lifecycle.State.STARTED
        )
    }

    /**
     * Merge a Google Pinyin user dictionary into fcitx's own user dictionary and history,
     * so that the words are learned, ranked and forgotten like words typed in fcitx.
     */
    private suspend fun importGooglePinyinUserDict(bytes: ByteArray) {
        val ctx = requireContext()
        val nm = ctx.notificationManager
        val id = IMPORT_ID++
        NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_library_books_24)
            .setContentTitle(getString(R.string.pinyin_dict))
            .setContentText(getString(R.string.import_google_pinyin_user_dict))
            .setOngoing(true)
            .setProgress(100, 0, true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build().let { nm.notify(id, it) }
        try {
            // flush words learned so far, they would be lost when fcitx is stopped otherwise
            viewModel.fcitx.runOnReady { save() }
            val stats = withContext(Dispatchers.IO) { GooglePinyinUserData.import(bytes) }
            ctx.toast(
                getString(
                    R.string.google_pinyin_dict_imported,
                    stats.added, stats.existing, stats.skipped
                )
            )
        } catch (e: Exception) {
            ctx.importErrorDialog(e)
        }
        nm.cancel(id)
    }

    private fun exportGooglePinyinUserDict(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.launch {
            try {
                // flush user.dict and user.history to disk before reading them
                viewModel.fcitx.runOnReady { save() }
                val count = withContext(Dispatchers.IO) {
                    val (bytes, count) = GooglePinyinUserData.export()
                    ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                    count
                }
                ctx.toast(getString(R.string.google_pinyin_dict_exported, count))
            } catch (e: Exception) {
                ctx.importErrorDialog(e)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getText(R.string.pinyin_dict),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = CHANNEL_ID }
            requireContext().notificationManager.createNotificationChannel(channel)
        }
    }

    private fun registerLauncher() {
        launcher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null)
                importFromUri(uri)
        }
        googleExportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
                if (uri != null)
                    exportGooglePinyinUserDict(uri)
            }
    }

    private fun importFromUri(uri: Uri) {
        val ctx = requireContext()
        val cr = ctx.contentResolver
        val nm = ctx.notificationManager
        lifecycleScope.launch {
            val id = IMPORT_ID++
            val fileName = cr.queryFileName(uri) ?: return@launch
            if (PinyinDictionary.Type.fromFileName(fileName) == null) {
                ctx.importErrorDialog(R.string.invalid_dict)
                return@launch
            }
            if (fileName.endsWith(".${PinyinDictionary.Type.Text.ext}")) {
                val bytes = withContext(Dispatchers.IO) {
                    cr.openInputStream(uri)!!.use { it.readBytes() }
                }
                if (GooglePinyinUserDict.looksLikeGooglePinyin(bytes)) {
                    importGooglePinyinUserDict(bytes)
                    return@launch
                }
            }
            val entryName = fileName.substringBeforeLast('.')
            if (ui.entries.any { it.name == entryName }) {
                ctx.importErrorDialog(R.string.dict_already_exists)
                return@launch
            }
            NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_library_books_24)
                .setContentTitle(getString(R.string.pinyin_dict))
                .setContentText("${getString(R.string.importing)} $entryName")
                .setOngoing(true)
                .setProgress(100, 0, true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build().let { nm.notify(id, it) }
            try {
                val imported = withContext(Dispatchers.IO) {
                    val inputStream = cr.openInputStream(uri)!!
                    PinyinDictManager.importFromInputStream(inputStream, fileName).getOrThrow()
                }
                ui.addItem(item = imported)
            } catch (e: Exception) {
                ctx.importErrorDialog(e)
            }
            nm.cancel(id)
        }
    }

    private fun reloadDict() {
        if (!dustman.dirty) return
        resetDustman()
        // Save the reference to NotificationManager, because reloadDict() could be called
        // right before the Fragment detached from Activity, and at the time reload completes,
        // Fragment is no longer attached to a Context, thus unable to cancel the notification.
        val nm = requireContext().notificationManager
        lifecycleScope.launch {
            if (busy.compareAndSet(false, true)) {
                val id = RELOAD_ID++
                NotificationCompat.Builder(requireContext(), CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_baseline_library_books_24)
                    .setContentTitle(getString(R.string.pinyin_dict))
                    .setContentText(getString(R.string.reloading))
                    .setOngoing(true)
                    .setProgress(100, 0, true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build().let { nm.notify(id, it) }
                viewModel.fcitx.runOnReady {
                    reloadPinyinDict()
                }
                nm.cancel(id)
                busy.set(false)
            }
        }
    }

    private fun resetDustman() {
        dustman.reset(ui.entries.mapNotNull { it as? LibIMEDictionary }
            .associate { it.name to it.isEnabled })
    }

    override fun onItemAdded(idx: Int, item: PinyinDictionary) {
        item as LibIMEDictionary
        dustman.addOrUpdate(item.name, item.isEnabled)
    }

    override fun onItemRemoved(idx: Int, item: PinyinDictionary) {
        item as LibIMEDictionary
        item.file.delete()
        dustman.remove(item.name)
    }

    override fun onItemRemovedBatch(indexed: List<Pair<Int, PinyinDictionary>>) {
        batchRemove(indexed)
    }

    override fun onItemUpdated(idx: Int, old: PinyinDictionary, new: PinyinDictionary) {
        new as LibIMEDictionary
        dustman.addOrUpdate(new.name, new.isEnabled)
    }

    override fun onStop() {
        reloadDict()
        if (uiInitialized) {
            ui.exitMultiSelect()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (uiInitialized) {
            ui.removeItemChangedListener()
        }
        super.onDestroy()
    }

    companion object {
        private var RELOAD_ID = 0
        private var IMPORT_ID = 0
        const val CHANNEL_ID = "pinyin_dict"
    }
}