/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.data.pinyin.dict.BuiltinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.GooglePinyinUserDict
import org.fcitx.fcitx5.android.data.pinyin.dict.LibIMEDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.TextDictionary
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorArg
import org.fcitx.fcitx5.android.utils.errorState
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream

object PinyinDictManager {

    private val pinyinDicDir = File(
        appContext.getExternalFilesDir(null)!!, "data/pinyin/dictionaries"
    ).also { it.mkdirs() }

    private val builtinPinyinDictDir = File(
        DataManager.dataDir, "usr/share/fcitx5/pinyin/dictionaries"
    )

    private val nativeDir = File(appContext.applicationInfo.nativeLibraryDir)

    private val scel2org5 by lazy { File(nativeDir, scel2org5Name) }

    fun listDictionaries(): List<PinyinDictionary> {
        val builtin = mutableListOf<PinyinDictionary>()
        builtinPinyinDictDir.listFiles()?.forEach {
            if (it.extension == PinyinDictionary.Type.LibIME.ext) {
                builtin.add(BuiltinDictionary(it))
            }
        }
        builtin.sortBy { it.name }
        val user = mutableListOf<PinyinDictionary>()
        pinyinDicDir.listFiles()?.forEach {
            PinyinDictionary.new(it)?.let { dict ->
                if (dict is LibIMEDictionary) {
                    user.add(dict)
                }
            }
        }
        user.sortBy { it.name }
        return builtin + user
    }

    /**
     * @param googlePinyin non-null when the file was a Google Pinyin user dictionary
     */
    data class ImportResult(
        val dictionary: LibIMEDictionary,
        val googlePinyin: GooglePinyinUserDict.ParseResult? = null
    )

    /** fcitx pinyin user dictionary, holding words learned while typing */
    private val userDictFile get() = File(pinyinDicDir.parentFile, "user.dict")

    fun importFromFile(file: File): Result<ImportResult> = runCatching {
        if (file.extension == PinyinDictionary.Type.Text.ext) {
            val bytes = file.readBytes()
            if (GooglePinyinUserDict.looksLikeGooglePinyin(bytes)) {
                return@runCatching importGooglePinyinUserDict(file.nameWithoutExtension, bytes)
            }
        }
        val raw =
            PinyinDictionary.new(file) ?: errorArg(R.string.exception_dict_filename, file.path)
        // convert to libime format in dictionaries dir
        // preserve original file name
        val new = raw.toLibIMEDictionary(
            File(
                pinyinDicDir,
                file.nameWithoutExtension + ".${PinyinDictionary.Type.LibIME.ext}"
            )
        )
        Timber.d("Converted $raw to $new")
        ImportResult(new)
    }

    /**
     * Convert a Google Pinyin user dictionary into an extra libime dictionary named
     * [GooglePinyinUserDict.IMPORTED_DICT_PREFIX] + [name].
     */
    private fun importGooglePinyinUserDict(name: String, bytes: ByteArray): ImportResult {
        val parsed = GooglePinyinUserDict.parse(bytes)
        if (parsed.entries.isEmpty()) errorArg(R.string.google_pinyin_dict_no_entry)
        val dictName = GooglePinyinUserDict.IMPORTED_DICT_PREFIX + name
        val ext = PinyinDictionary.Type.LibIME.ext
        val dest = File(pinyinDicDir, "$dictName.$ext")
        if (dest.exists() || File(pinyinDicDir, "$dictName.$ext.${LibIMEDictionary.DISABLE}").exists()) {
            errorState(R.string.dict_already_exists)
        }
        val txt = File(appContext.cacheDir, "$dictName.${PinyinDictionary.Type.Text.ext}")
        try {
            txt.writeText(GooglePinyinUserDict.toLibimeText(parsed.entries))
            val dict = TextDictionary(txt).toLibIMEDictionary(dest)
            Timber.d("Imported Google Pinyin user dictionary: ${parsed.entries.size} entries, ${parsed.skipped} skipped")
            return ImportResult(dict, parsed)
        } finally {
            txt.delete()
        }
    }

    private fun readLibimeEntries(dict: File): List<GooglePinyinUserDict.Entry> {
        val txt = File(appContext.cacheDir, "export_${dict.name}.${PinyinDictionary.Type.Text.ext}")
        return try {
            pinyinDictConv(dict.absolutePath, txt.absolutePath, MODE_BIN_TO_TXT)
            GooglePinyinUserDict.parseLibimeText(txt.readText())
        } finally {
            txt.delete()
        }
    }

    /**
     * Collect fcitx pinyin user words (`user.dict`) together with enabled dictionaries that were
     * imported from Google Pinyin, and serialize them in Google Pinyin user dictionary format.
     * Caller should make fcitx save its state first, so that `user.dict` is up-to-date.
     *
     * @return file content and number of entries
     */
    fun exportGooglePinyinUserDict(): Pair<ByteArray, Int> {
        val sources = buildList {
            if (userDictFile.exists()) add(userDictFile)
            listDictionaries().forEach {
                if (it is LibIMEDictionary && it.isEnabled &&
                    it.name.startsWith(GooglePinyinUserDict.IMPORTED_DICT_PREFIX)
                ) add(it.file)
            }
        }
        val entries = LinkedHashMap<Pair<String, String>, GooglePinyinUserDict.Entry>()
        sources.forEach { src ->
            readLibimeEntries(src).forEach { entries.putIfAbsent(it.word to it.libimePinyin, it) }
        }
        return GooglePinyinUserDict.toGooglePinyinBytes(entries.values.toList()) to entries.size
    }

    fun importFromInputStream(stream: InputStream, name: String): Result<ImportResult> {
        val tempFile = File(appContext.cacheDir, name)
        tempFile.outputStream().use {
            stream.copyTo(it)
        }
        val new = importFromFile(tempFile)
        tempFile.delete()
        return new
    }

    fun sougouDictConv(src: String, dest: String) {
        val process = Runtime.getRuntime()
            .exec(
                arrayOf(scel2org5.absolutePath, "-o", dest, src),
                arrayOf("LD_LIBRARY_PATH=${nativeDir.absolutePath}")
            )
        process.waitFor()
        if (process.exitValue() != 0) {
            throw IOException(process.errorStream.bufferedReader().readText())
        }
    }

    @JvmStatic
    external fun pinyinDictConv(src: String, dest: String, mode: Boolean)

    const val MODE_BIN_TO_TXT = true
    const val MODE_TXT_TO_BIN = false
    private const val scel2org5Name = "libscel2org5.so"

}