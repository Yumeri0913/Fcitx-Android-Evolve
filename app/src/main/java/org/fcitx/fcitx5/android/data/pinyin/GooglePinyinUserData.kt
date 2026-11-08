/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.pinyin.dict.GooglePinyinUserDict
import org.fcitx.fcitx5.android.data.pinyin.dict.LibIMEDictionary
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorArg
import timber.log.Timber
import java.io.File

/**
 * Moves words between a Google Pinyin user dictionary and fcitx pinyin's own user data,
 * so that imported words behave exactly like words fcitx learned itself:
 * - they live in `user.dict`, can be removed with "forget word", and are exported again
 * - their Google Pinyin usage counts are replayed into `user.history`, which ranks candidates
 * - daily typing keeps updating `user.history`, and export reports those usage counts
 *
 * File operations are implemented in native code (`googlepinyin/userdata.cpp`).
 */
object GooglePinyinUserData {

    init {
        // normally already loaded by Fcitx; loading again is a no-op
        System.loadLibrary("native-lib")
    }

    private val pinyinDir: File
        get() = File(appContext.getExternalFilesDir(null)!!, "data/pinyin").also { it.mkdirs() }

    private val userDict get() = File(pinyinDir, "user.dict")
    private val userHistory get() = File(pinyinDir, "user.history")

    data class ImportStats(
        /** words parsed from the Google Pinyin file */
        val parsed: Int,
        /** lines that are not pinyin words (English words, invalid pinyin, ...) */
        val skipped: Int,
        /** words newly added to fcitx user dictionary */
        val added: Int,
        /** words already in fcitx user dictionary */
        val existing: Int
    )

    private fun toTsv(entries: List<GooglePinyinUserDict.Entry>): ByteArray =
        entries.joinToString("") { "${it.word}\t${it.libimePinyin}\t${it.count}\n" }
            .toByteArray(Charsets.UTF_8)

    private fun fromTsv(bytes: ByteArray): List<GooglePinyinUserDict.Entry> =
        String(bytes, Charsets.UTF_8).lineSequence().mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size < 3 || fields[0].isEmpty() || fields[1].isEmpty()) return@mapNotNull null
            GooglePinyinUserDict.Entry(
                fields[0],
                fields[1].split('\'').filter { it.isNotEmpty() },
                fields[2].toIntOrNull()?.coerceAtLeast(1) ?: 1
            )
        }.toList()

    data class MergeResult(val added: Int, val existing: Int, val invalid: Int)

    /**
     * Add [entries] to [userDict] and replay their counts into [history].
     * Files must not be in use by a running fcitx.
     */
    internal fun mergeFiles(
        userDict: File,
        history: File,
        entries: List<GooglePinyinUserDict.Entry>
    ): MergeResult {
        val (added, existing, invalid) =
            nativeMerge(userDict.absolutePath, history.absolutePath, toTsv(entries)).toList()
        return MergeResult(added, existing, invalid)
    }

    /** Words of a binary libime dictionary, with usage counts taken from [history] */
    internal fun readWords(dict: File, history: File): List<GooglePinyinUserDict.Entry> =
        if (dict.exists()) fromTsv(nativeReadUserWords(dict.absolutePath, history.absolutePath))
        else emptyList()

    /**
     * Import a Google Pinyin user dictionary into fcitx user data.
     * Fcitx is stopped while files are rewritten; caller should ask fcitx to save first.
     * Must not be called on the main thread.
     */
    fun import(bytes: ByteArray): ImportStats {
        val parsed = GooglePinyinUserDict.parse(bytes)
        if (parsed.entries.isEmpty()) errorArg(R.string.google_pinyin_dict_no_entry)
        val (added, existing, invalid) = FcitxDaemon.withFcitxStopped {
            mergeFiles(userDict, userHistory, parsed.entries)
        }
        Timber.i("Google Pinyin import: parsed=${parsed.entries.size}, skipped=${parsed.skipped}, added=$added, existing=$existing, invalid=$invalid")
        return ImportStats(parsed.entries.size, parsed.skipped + invalid, added, existing)
    }

    /**
     * Export fcitx user words in Google Pinyin user dictionary format.
     * Counts come from fcitx usage history. Dictionaries created by the previous
     * implementation (imported as extra dictionaries named [GooglePinyinUserDict.IMPORTED_DICT_PREFIX])
     * are included as well.
     * Caller should ask fcitx to save first. Must not be called on the main thread.
     *
     * @return file content and number of words
     */
    fun export(): Pair<ByteArray, Int> {
        val sources = buildList {
            add(userDict)
            PinyinDictManager.listDictionaries().forEach {
                if (it is LibIMEDictionary && it.isEnabled &&
                    it.name.startsWith(GooglePinyinUserDict.IMPORTED_DICT_PREFIX)
                ) add(it.file)
            }
        }
        val merged = LinkedHashMap<Pair<String, String>, GooglePinyinUserDict.Entry>()
        sources.forEach { src ->
            readWords(src, userHistory).forEach { e ->
                val key = e.word to e.libimePinyin
                val old = merged[key]
                if (old == null || old.count < e.count) merged[key] = e
            }
        }
        val entries = merged.values.toList()
        return GooglePinyinUserDict.toGooglePinyinBytes(entries) to entries.size
    }

    @JvmStatic
    private external fun nativeMerge(userDict: String, history: String, tsv: ByteArray): IntArray

    @JvmStatic
    private external fun nativeReadUserWords(dict: String, history: String): ByteArray
}
