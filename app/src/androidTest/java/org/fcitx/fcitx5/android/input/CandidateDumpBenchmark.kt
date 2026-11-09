/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.core.Fcitx
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.KeyAlternatives
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.ScancodeMapping
import org.fcitx.fcitx5.android.data.pinyin.GooglePinyinUserData
import org.fcitx.fcitx5.android.data.pinyin.dict.GooglePinyinUserDict
import org.junit.Assume.assumeTrue
import org.junit.Test
import timber.log.Timber
import java.io.File

/**
 * Benchmark helper, skipped unless its input exists: types each query of
 * `/data/local/tmp/fcitx-benchmark/queries.tsv` into fcitx pinyin and writes the top candidates
 * to `<external files>/benchmark/<out>`. Used to compare fcitx with Google Pinyin on a real
 * Google Pinyin user dictionary (`user-dictionary.txt` next to the queries, imported into fresh
 * user data first), see `scripts/google-pinyin-benchmark.py`. Inputs are read through the shell,
 * as the app cannot see files pushed by adb.
 *
 * Query line: `id<TAB>keys[<TAB>alternatives]`, where alternatives has one `;` separated item per
 * key, each empty or like `q-3.0,a-3.2` (key and natural log likelihood, the typed key first).
 * Output line: `id<TAB>candidate<TAB>candidate...`
 */
class CandidateDumpBenchmark {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val benchDir get() = File(context.getExternalFilesDir(null)!!, "benchmark")
    private val inputDir = "/data/local/tmp/fcitx-benchmark"

    private fun readInput(name: String): ByteArray? {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cat $inputDir/$name")
        val bytes = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        return bytes.takeIf { it.isNotEmpty() }
    }
    private val pinyinDir get() = File(context.getExternalFilesDir(null)!!, "data/pinyin")

    private fun parseAlternatives(field: String?, keys: String): List<KeyAlternatives?> {
        if (field.isNullOrEmpty()) return keys.map { null }
        return field.split(';').map { item ->
            if (item.isEmpty()) return@map null
            val pairs = item.split(',').map { it[0] to it.substring(1).toFloat() }
            KeyAlternatives(pairs.map { it.first }.joinToString(""), pairs.map { it.second }.toFloatArray())
        }
    }

    @Test
    fun dump(): Unit = runBlocking {
        val queries = readInput("queries.tsv")
        assumeTrue("no $inputDir/queries.tsv", queries != null)
        val limit = args.getString("limit")?.toInt() ?: 10
        benchDir.mkdirs()
        val out = File(benchDir, args.getString("out") ?: "candidates.tsv")

        // fresh user data with only the Google Pinyin user dictionary
        val files = listOf("user.dict", "user.history", "user.english", "user.typed")
            .map { File(pinyinDir, it) }
        val backups = files.associateWith { it.takeIf { f -> f.exists() }?.readBytes() }
        files.forEach { it.delete() }
        pinyinDir.mkdirs()
        readInput("user-dictionary.txt")?.let {
            val parsed = GooglePinyinUserDict.parse(it)
            GooglePinyinUserData.mergeFiles(files[0], files[1], parsed.entries)
            GooglePinyinUserData.mergeEnglish(files[2], parsed.english)
            if (args.getString("typed") != "false") GooglePinyinUserData.mergeTyped(files[3], parsed.typed)
            Timber.i("benchmark: imported ${parsed.entries.size} words, ${parsed.english.size} English")
        }

        // tuning knobs of the pinyin engine, read when it loads
        args.keySet().filter { it.startsWith("FCITX_") }.forEach {
            android.system.Os.setenv(it, args.getString(it), true)
        }
        val scope = MainScope()
        val events = Channel<FcitxEvent<*>>(capacity = Channel.UNLIMITED)
        val fcitx = Fcitx(context)
        val collector = fcitx.eventFlow.onEach { events.send(it) }.launchIn(scope)
        fcitx.start()
        try {
            withTimeout(10_000) { events.receiveAsFlow().filterIsInstance<FcitxEvent.ReadyEvent>().first() }
            // candidate events of every key would pile up, and debug logging of them is slow
            collector.cancel()
            Timber.i("benchmark: ready")
            Timber.uprootAll()
            fcitx.setEnabledIme(arrayOf("keyboard-us", "pinyin"))
            fcitx.activate(1, context.packageName)
            fcitx.focus(true)
            fcitx.activateIme("pinyin")
            withTimeout(60_000) {
                while (true) {
                    fcitx.reset()
                    "nihao".forEach { fcitx.sendKey(it) }
                    if (fcitx.getCandidates(0, 10).isNotEmpty()) break
                    delay(500)
                }
            }
            val start = System.currentTimeMillis()
            var n = 0
            out.bufferedWriter().use { w ->
                String(queries!!, Charsets.UTF_8).lineSequence().forEach { line ->
                    val fields = line.split('\t')
                    if (fields.size < 2 || fields[1].isEmpty()) return@forEach
                    val (id, keys) = fields
                    val alternatives = parseAlternatives(fields.getOrNull(2), keys)
                    val candidates = runBlocking {
                        fcitx.reset()
                        keys.forEachIndexed { i, c ->
                            val alt = alternatives.getOrNull(i)
                            if (alt == null) fcitx.sendKey(c)
                            else fcitx.sendKeyWithAlternatives(
                                c.toString(), KeyStates.Virtual.states, ScancodeMapping.charToScancode(c), alt
                            )
                        }
                        fcitx.getCandidates(0, limit)
                    }
                    w.append(id)
                    candidates.forEach { w.append('\t').append(it.text.replace('\t', ' ')) }
                    w.append('\n')
                    if (++n % 1000 == 0) android.util.Log.i("FcitxBench", "benchmark: $n queries")
                }
            }
            fcitx.reset()
            android.util.Log.i("FcitxBench", "benchmark: $n queries in ${System.currentTimeMillis() - start} ms -> ${out.path}")
        } finally {
            if (fcitx.isReady) fcitx.stop()
            scope.cancel()
            backups.forEach { (file, bytes) -> if (bytes == null) file.delete() else file.writeBytes(bytes) }
        }
    }
}
