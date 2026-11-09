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
 * - English words go to `user.english`, the words fcitx pinyin learns from raw input commits
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
    private val userEnglish get() = File(pinyinDir, "user.english")
    private val userTyped get() = File(pinyinDir, "user.typed")

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

    private class EnglishLine(val word: String, val count: Int, val stamp: Long)

    // Same format as fcitx pinyin's englishuserwords.h: word<TAB>count<TAB>stamp
    private fun readEnglishLines(file: File): LinkedHashMap<String, EnglishLine> {
        val result = LinkedHashMap<String, EnglishLine>()
        if (!file.exists()) return result
        file.forEachLine { line ->
            val fields = line.split('\t')
            val word = fields[0]
            val count = fields.getOrNull(1)?.toIntOrNull() ?: return@forEachLine
            if (word.isEmpty() || count <= 0) return@forEachLine
            result[word.lowercase()] =
                EnglishLine(word, count, fields.getOrNull(2)?.toLongOrNull() ?: 0L)
        }
        return result
    }

    /** English words fcitx pinyin learned, with usage counts */
    internal fun readEnglish(file: File): List<GooglePinyinUserDict.EnglishEntry> =
        readEnglishLines(file).values.map { GooglePinyinUserDict.EnglishEntry(it.word, it.count) }

    /**
     * Add English [entries] to the learned English words in [file], keeping the larger count
     * of words already there. Files must not be in use by a running fcitx.
     *
     * @return words added and words already there
     */
    internal fun mergeEnglish(
        file: File,
        entries: List<GooglePinyinUserDict.EnglishEntry>
    ): Pair<Int, Int> {
        val words = readEnglishLines(file)
        var stamp = words.values.maxOfOrNull { it.stamp } ?: 0L
        var added = 0
        var existing = 0
        // more used words count as more recent, like they were typed later
        entries.sortedBy { it.count }.forEach { e ->
            val key = e.word.lowercase()
            val old = words[key]
            if (old == null) {
                added++
                words[key] = EnglishLine(e.word, e.count.coerceAtMost(0xffff), ++stamp)
            } else {
                existing++
                if (e.count > old.count) {
                    words[key] = EnglishLine(old.word, e.count.coerceAtMost(0xffff), old.stamp)
                }
            }
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(buildString {
            words.values.sortedBy { it.stamp }.forEach {
                append(it.word).append('\t').append(it.count).append('\t').append(it.stamp).append('\n')
            }
        })
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("Failed to write ${file.path}")
        }
        return added to existing
    }

    private class TypedLine(val code: String, val word: String, val count: Int, val stamp: Long)

    /**
     * Add [entries] to fcitx pinyin's record of what was typed for which word (same format as
     * typedcodehistory.h: code<TAB>word<TAB>count<TAB>stamp), keeping the larger count of
     * entries already there. Files must not be in use by a running fcitx.
     */
    internal fun mergeTyped(file: File, entries: List<GooglePinyinUserDict.TypedEntry>) {
        val lines = LinkedHashMap<Pair<String, String>, TypedLine>()
        if (file.exists()) file.forEachLine { line ->
            val f = line.split('\t')
            if (f.size < 4) return@forEachLine
            val count = f[2].toIntOrNull() ?: return@forEachLine
            lines[f[0] to f[1]] = TypedLine(f[0], f[1], count, f[3].toLongOrNull() ?: 0L)
        }
        var stamp = lines.values.maxOfOrNull { it.stamp } ?: 0L
        entries.sortedBy { it.count }.forEach { e ->
            val key = e.code to e.word
            val old = lines[key]
            if (old == null) {
                lines[key] = TypedLine(e.code, e.word, e.count, ++stamp)
            } else if (e.count > old.count) {
                lines[key] = TypedLine(old.code, old.word, e.count, old.stamp)
            }
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(buildString {
            lines.values.sortedBy { it.stamp }.forEach {
                append(it.code).append('\t').append(it.word).append('\t')
                    .append(it.count).append('\t').append(it.stamp).append('\n')
            }
        })
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("Failed to write ${file.path}")
        }
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
        if (parsed.entries.isEmpty() && parsed.english.isEmpty() && parsed.typed.isEmpty()) {
            errorArg(R.string.google_pinyin_dict_no_entry)
        }
        val (merged, english) = FcitxDaemon.withFcitxStopped {
            val merged = if (parsed.entries.isEmpty()) MergeResult(0, 0, 0)
            else mergeFiles(userDict, userHistory, parsed.entries)
            mergeTyped(userTyped, parsed.typed)
            merged to mergeEnglish(userEnglish, parsed.english)
        }
        val (added, existing, invalid) = merged
        Timber.i("Google Pinyin import: parsed=${parsed.entries.size}, english=${parsed.english.size}, skipped=${parsed.skipped}, added=$added+${english.first}, existing=$existing+${english.second}, invalid=$invalid")
        return ImportStats(
            parsed.entries.size + parsed.english.size,
            parsed.skipped + invalid,
            added + english.first,
            existing + english.second
        )
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
        val english = readEnglish(userEnglish)
        return GooglePinyinUserDict.toGooglePinyinBytes(entries, english) to entries.size + english.size
    }

    @JvmStatic
    private external fun nativeMerge(userDict: String, history: String, tsv: ByteArray): IntArray

    @JvmStatic
    private external fun nativeReadUserWords(dict: String, history: String): ByteArray
}
