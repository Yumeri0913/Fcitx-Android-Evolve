/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin.dict

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Reader/writer for the user dictionary text file produced by Google Pinyin for Android
 * (Settings -> Dictionary -> Export user dictionary, `GooglePinyinInput/user-dictionary.txt`),
 * and converter from/to libime's text dictionary format.
 *
 * Google Pinyin export format:
 * - UTF-16LE with BOM, `\n` line endings
 * - first line `# User dictionary for Google Pinyin Input`
 * - one entry per line: `word<TAB>count<TAB>pin yin[<TAB>tx=N]`
 * - English entries mark their tokens with a language marker, e.g. `hello\t3\t\\0 hello`
 *
 * Google Pinyin's importer is lenient: BOM decides the charset (GBK without BOM), lines
 * starting with `#` or `;` are comments, fields are separated by TAB (or a single space when
 * the line has no TAB), and the order of the first three fields does not matter.
 * [parse] follows the same rules.
 *
 * libime text format: `word pin'yin [cost]`, separated by whitespace.
 */
object GooglePinyinUserDict {

    const val HEADER = "# User dictionary for Google Pinyin Input"

    /** Default file name used by Google Pinyin for export/import */
    const val DEFAULT_FILE_NAME = "user-dictionary.txt"

    /** Name prefix of libime dictionaries converted from a Google Pinyin user dictionary */
    const val IMPORTED_DICT_PREFIX = "谷歌拼音_"

    data class Entry(val word: String, val syllables: List<String>, val count: Int = 1) {
        val libimePinyin: String get() = syllables.joinToString("'")
        val googlePinyin: String get() = syllables.joinToString(" ")
    }

    data class ParseResult(
        val entries: List<Entry>,
        /** non-comment lines that could not be converted (English words, invalid pinyin...) */
        val skipped: Int
    )

    /**
     * Full pinyin syllables accepted by libime without fuzzy flags,
     * extracted from libime `getPinyinMap()` (entries with `PinyinFuzzyFlag::None`).
     */
    val VALID_SYLLABLES: Set<String> = (
            "a ai an ang ao ba bai ban bang bao bei ben beng bi bian biang biao bie bin bing bo bong bu "+
            "ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou chu "+
            "chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo da dai dan dang dao "+
            "de dei den deng di dia dian diao die din ding diu dong dou du duan dui dun duo e ei en eng "+
            "er fa fan fang fei fen feng fiao fo fou fu ga gai gan gang gao ge gei gen geng gong gou gu "+
            "gua guai guan guang gui gun guo ha hai han hang hao he hei hen heng hong hou hu hua huai "+
            "huan huang hui hun huo ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun ka kai "+
            "kan kang kao ke kei ken keng kong kou ku kua kuai kuan kuang kui kun kuo la lai lan lang lao "+
            "le lei leng li lia lian liang liao lie lin ling liu lo long lou lu luan lun luo lv lve m ma "+
            "mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu n na nai nan nang "+
            "nao ne nei nen neng ng ni nia nian niang niao nie nin ning niu nong nou nu nuan nun nuo nv "+
            "nve o ou pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu qi qia qian "+
            "qiang qiao qie qin qing qiong qiu qu quan que qun r ran rang rao re ren reng ri rong rou ru "+
            "rua ruan rui run ruo sa sai san sang sao se sen seng sha shai shan shang shao she shei shen "+
            "sheng shi shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo ta "+
            "tai tan tang tao te tei teng ti tian tiao tie ting tong tou tu tuan tui tun tuo wa wai wan "+
            "wang wei wen weng wo wong wu xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun "+
            "ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun za zai zan zang zao ze zei zen "+
            "zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang "+
            "zhui zhun zhuo zi zong zou zu zuan zui zun zuo "
            ).split(' ').filter { it.isNotEmpty() }.toHashSet()

    private val BOM_UTF8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val BOM_UTF16LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val BOM_UTF16BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    private val pinyinRegex = Regex("^'?[a-z\u00fc]+(?:[ '|][a-z\u00fc]+)*'?$")
    private val countRegex = Regex("^[0-9]+,?$")
    private val englishRegex = Regex("^[A-Za-z]+(?:[-'][A-Za-z]+)*$")
    private val languageMarkerRegex = Regex("\\\\[0-9]+")
    private val syllableSeparator = Regex("[ '|]+")

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    /**
     * Decode bytes the same way Google Pinyin does: by BOM, falling back to UTF-8 if the content
     * is valid UTF-8, otherwise GBK (Google Pinyin's own default for files without BOM).
     */
    fun decode(bytes: ByteArray): String = when {
        bytes.startsWith(BOM_UTF16LE) ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.startsWith(BOM_UTF16BE) ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        bytes.startsWith(BOM_UTF8) ->
            String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        else -> try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("GBK"))
        }
    }

    /**
     * Whether the bytes look like a Google Pinyin user dictionary rather than a libime text
     * dictionary: either UTF-16 BOM, the export header, or TAB separated `word count pinyin`.
     */
    fun looksLikeGooglePinyin(bytes: ByteArray): Boolean {
        if (bytes.startsWith(BOM_UTF16LE) || bytes.startsWith(BOM_UTF16BE)) return true
        val text = decode(bytes)
        val lines = text.lineSequence()
            .map { it.trim('\uFEFF', '\r') }
            .filter { it.isNotBlank() }
            .take(50)
            .toList()
        if (lines.any { it.startsWith(HEADER) }) return true
        val data = lines.filterNot { it.startsWith("#") || it.startsWith(";") }
        if (data.isEmpty()) return false
        return data.count { line ->
            val fields = line.split('\t')
            fields.size >= 3 && fields.take(3).any { countRegex.matches(it.trim()) }
        } * 2 > data.size
    }

    private fun isHan(cp: Int): Boolean {
        val script = Character.UnicodeScript.of(cp)
        return script == Character.UnicodeScript.HAN || cp == 0x3007 // 〇
    }

    private fun containsHan(s: String): Boolean = s.codePoints().anyMatch { isHan(it) }

    private fun hanLength(s: String): Int? {
        var n = 0
        val it = s.codePoints().iterator()
        while (it.hasNext()) {
            if (!isHan(it.nextInt())) return null
            n++
        }
        return n
    }

    /** Normalize one syllable to libime spelling (ü -> v, lue/nue -> lve/nve) */
    fun normalizeSyllable(s: String): String {
        val v = s.lowercase().replace('\u00fc', 'v')
        return when (v) {
            "lue" -> "lve"
            "nue" -> "nve"
            else -> v
        }
    }

    private fun splitFields(line: String): List<String> =
        if (line.contains('\t')) line.split('\t') else line.split(' ')

    /** Parse a Google Pinyin user dictionary (already decoded) */
    fun parse(text: String): ParseResult {
        val merged = LinkedHashMap<Pair<String, String>, Entry>()
        var skipped = 0
        text.lineSequence().forEach { raw ->
            val line = raw.trim('\uFEFF', '\r', '\n')
            if (line.isBlank() || line.startsWith("#") || line.startsWith(";")) return@forEach
            val entry = parseLine(line)
            if (entry == null) {
                skipped++
                return@forEach
            }
            val key = entry.word to entry.libimePinyin
            val old = merged[key]
            merged[key] = if (old == null) entry else old.copy(count = maxOf(old.count, entry.count))
        }
        return ParseResult(merged.values.toList(), skipped)
    }

    fun parse(bytes: ByteArray): ParseResult = parse(decode(bytes))

    private fun parseLine(line: String): Entry? {
        val fields = splitFields(line)
        var word: String? = null
        var pinyin: String? = null
        var count: Int? = null
        var other: String? = null
        fields.take(3).forEach { f ->
            val field = f.trim()
            when {
                field.isEmpty() -> {}
                // language markers like "\0" mean English (or mixed) tokens: not for pinyin
                languageMarkerRegex.containsMatchIn(field) -> return null
                containsHan(field) -> word = field
                pinyinRegex.matches(field) && pinyin == null -> pinyin = field
                countRegex.matches(field) ->
                    count = field.trimEnd(',').toIntOrNull()?.coerceAtLeast(1) ?: 1
                englishRegex.matches(field) -> return null
                else -> other = field
            }
        }
        val w = word ?: other ?: return null
        val p = pinyin ?: return null
        val syllables = p.trim('\'').split(syllableSeparator)
            .filter { it.isNotEmpty() }
            .map { normalizeSyllable(it) }
        if (syllables.isEmpty() || syllables.any { it !in VALID_SYLLABLES }) return null
        if (hanLength(w) != syllables.size) return null
        return Entry(w, syllables, count ?: 1)
    }

    /** Convert entries to libime text dictionary content */
    fun toLibimeText(entries: List<Entry>): String = buildString {
        entries.forEach { append(it.word).append(' ').append(it.libimePinyin).append(" 0\n") }
    }

    private fun unescapeLibimeValue(token: String): String {
        if (token.length < 2 || !token.startsWith('"') || !token.endsWith('"')) return token
        val inner = token.substring(1, token.length - 1)
        val sb = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length) {
                when (val n = inner[i + 1]) {
                    'n' -> sb.append('\n')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /**
     * Parse libime text dictionary content (as produced by `PinyinDictionary::saveText`,
     * `word pin'yin cost` per line).
     */
    fun parseLibimeText(text: String): List<Entry> {
        val result = LinkedHashMap<Pair<String, String>, Entry>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val (wordToken, rest) = if (line.startsWith('"')) {
                // quoted word, find the closing quote that is not escaped
                var end = 1
                while (end < line.length) {
                    if (line[end] == '\\') end += 2
                    else if (line[end] == '"') break
                    else end++
                }
                if (end >= line.length) return@forEach
                line.substring(0, end + 1) to line.substring(end + 1).trim()
            } else {
                val sp = line.indexOfFirst { it.isWhitespace() }
                if (sp < 0) return@forEach
                line.substring(0, sp) to line.substring(sp + 1).trim()
            }
            val pinyin = rest.split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotEmpty() }
                ?: return@forEach
            val word = unescapeLibimeValue(wordToken)
            val syllables = pinyin.split('\'').filter { it.isNotEmpty() }
            if (word.isEmpty() || syllables.isEmpty()) return@forEach
            val e = Entry(word, syllables, 1)
            result.putIfAbsent(word to e.libimePinyin, e)
        }
        return result.values.toList()
    }

    /** Serialize entries to Google Pinyin user dictionary bytes (UTF-16LE with BOM) */
    fun toGooglePinyinBytes(entries: List<Entry>): ByteArray {
        val text = buildString {
            append(HEADER).append('\n')
            entries.forEach {
                append(it.word).append('\t')
                    .append(it.count.coerceAtLeast(1)).append('\t')
                    .append(it.googlePinyin).append('\n')
            }
        }
        return BOM_UTF16LE + text.toByteArray(Charsets.UTF_16LE)
    }
}
