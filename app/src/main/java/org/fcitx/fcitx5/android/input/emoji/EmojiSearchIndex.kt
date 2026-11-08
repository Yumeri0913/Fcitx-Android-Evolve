/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.emoji

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * Emoji keyword search over fcitx5's emoji module data (CLDR annotations), e.g. zh.dict and
 * en.dict from fcitx5/src/modules/emoji/data.
 *
 * File format: little endian uint32 uncompressed size, then zlib data containing
 * uint32 emoji count, and for each emoji: string emoji, uint32 annotation count, strings.
 * Strings are uint32 length + UTF-8 bytes.
 */
class EmojiSearchIndex private constructor(
    /** emojis in data order (CLDR order, roughly by popularity within groups) */
    private val emojis: List<String>,
    /** lower case annotation -> indexes into [emojis] */
    private val annotations: Map<String, IntArray>
) {

    val size get() = emojis.size

    /**
     * Emojis matching [query], best first: annotation equal to the query, then annotations
     * starting with it, then annotations containing it; ties keep data order.
     */
    fun search(query: String, limit: Int = 64): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val best = HashMap<Int, Int>()
        annotations.forEach { (annotation, indexes) ->
            val rank = when {
                annotation == q -> 0
                annotation.startsWith(q) -> 1
                annotation.contains(q) -> 2
                else -> return@forEach
            }
            indexes.forEach { i -> best.merge(i, rank, ::minOf) }
        }
        return best.entries
            .sortedWith(compareBy({ it.value }, { it.key }))
            .take(limit)
            .map { emojis[it.key] }
    }

    companion object {
        fun parse(bytes: ByteArray): EmojiSearchIndex {
            require(bytes.size >= 4) { "emoji data too short" }
            val expected = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val data = ByteArray(expected)
            val inflater = Inflater()
            try {
                inflater.setInput(bytes, 4, bytes.size - 4)
                var n = 0
                while (n < expected && !inflater.finished()) {
                    val read = inflater.inflate(data, n, expected - n)
                    if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    n += read
                }
                require(n == expected) { "corrupted emoji data" }
            } finally {
                inflater.end()
            }
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            fun string(): String {
                val len = buf.int
                val s = String(data, buf.position(), len, Charsets.UTF_8)
                buf.position(buf.position() + len)
                return s
            }
            val count = buf.int
            val emojis = ArrayList<String>(count)
            val map = HashMap<String, MutableList<Int>>()
            repeat(count) { index ->
                emojis.add(string())
                repeat(buf.int) {
                    map.getOrPut(string().lowercase()) { mutableListOf() }.add(index)
                }
            }
            return EmojiSearchIndex(emojis, map.mapValues { it.value.toIntArray() })
        }
    }
}

/**
 * Searches Chinese and English annotations together, Chinese results first.
 */
class EmojiSearcher(private val indexes: List<EmojiSearchIndex>) {
    fun search(query: String, limit: Int = 64): List<String> {
        val result = LinkedHashSet<String>()
        for (index in indexes) {
            result.addAll(index.search(query, limit))
            if (result.size >= limit) break
        }
        return result.take(limit)
    }
}
