package com.ketvoice.app.util

/**
 * Pronunciation under each lyric line (7.5, owner: « Paroles en romanisation »).
 *
 * Android's own ICU transforms do the work (no new library): kana, pinyin with tone marks,
 * Cyrillic, Greek, Arabic, Hebrew, Devanagari and Thai. Korean uses a small Revised Romanization
 * of its own (ICU's Hangul transform is not RR). Japanese kanji are not read: a reading dictionary
 * (Kuromoji) would add tens of MB to the APK, and ICU's Han transform gives Chinese readings, so a
 * Japanese line with kanji is left without pronunciation. Lines already in Latin script get none.
 */
object Romanizer {
    enum class Script { Latin, Kana, Han, Hangul, Cyrillic, Greek, Arabic, Hebrew, Devanagari, Thai, Other }

    /** One entry per line, null where nothing is shown. A song with kana anywhere is read as Japanese. */
    fun lines(texts: List<String>): List<String?> {
        val japanese = texts.any { line -> line.any { scriptOf(it) == Script.Kana } }
        return texts.map { line(it, japanese) }
    }

    fun line(text: String, japanese: Boolean = text.any { scriptOf(it) == Script.Kana }): String? {
        val scripts = text.mapNotNullTo(HashSet()) { c -> scriptOf(c).takeIf { it != Script.Latin && it != Script.Other } }
        if (scripts.isEmpty()) return null
        // Kanji in a Japanese song: ICU would give the Chinese reading, which is wrong. Nothing rather than a wrong reading.
        if (japanese && Script.Han in scripts) return null
        var out = text
        if (Script.Hangul in scripts) out = Hangul.romanize(out)
        for (script in scripts) {
            if (script == Script.Hangul) continue
            out = Icu.apply(out, transformsFor(script, text)) ?: return null
        }
        return capitals(tidy(out), text).takeIf { it.isNotBlank() && it != text }
    }

    internal fun transformsFor(script: Script, text: String): List<String> = when (script) {
        Script.Kana -> listOf("Hiragana-Latin; Katakana-Latin")
        Script.Han -> listOf("Han-Latin")
        Script.Cyrillic -> listOf(if (text.any { it in "іїєґІЇЄҐ" }) "Ukrainian-Latin/BGN" else "Russian-Latin/BGN", "Cyrillic-Latin")
        Script.Greek -> listOf("Greek-Latin/UNGEGN", "Greek-Latin")
        Script.Arabic -> listOf("Arabic-Latin")
        Script.Hebrew -> listOf("Hebrew-Latin")
        Script.Devanagari -> listOf("Devanagari-Latin")
        Script.Thai -> listOf("Thai-Latin")
        else -> emptyList()
    }

    fun scriptOf(c: Char): Script {
        val code = c.code
        return when {
            c in 'a'..'z' || c in 'A'..'Z' || code in 0x00C0..0x024F || code in 0x1E00..0x1EFF -> Script.Latin
            code in 0x3040..0x30FF || code in 0x31F0..0x31FF || code in 0xFF66..0xFF9F -> if (code == 0x30FB || code == 0x30FC) Script.Other else Script.Kana
            code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF -> Script.Han
            code in 0xAC00..0xD7A3 || code in 0x1100..0x11FF || code in 0x3130..0x318F -> Script.Hangul
            code in 0x0400..0x052F -> Script.Cyrillic
            code in 0x0370..0x03FF || code in 0x1F00..0x1FFF -> Script.Greek
            code in 0x0600..0x06FF || code in 0x0750..0x077F -> Script.Arabic
            code in 0x0590..0x05FF -> Script.Hebrew
            code in 0x0900..0x097F -> Script.Devanagari
            code in 0x0E00..0x0E7F -> Script.Thai
            else -> Script.Other
        }
    }

    /** « Я » alone becomes « YA » in ICU; unless the line itself shouts, such a word is written « Ya ». */
    private fun capitals(out: String, source: String): String {
        if (Regex("""\p{Lu}{2}""").containsMatchIn(source)) return out
        return Regex("""\b(\p{Lu})(\p{Lu}+)\b""").replace(out) { it.groupValues[1] + it.groupValues[2].lowercase() }
    }

    /** Japanese and Chinese punctuation become their Latin forms; spaces are evened out. */
    private fun tidy(s: String): String = s
        .replace('、', ',').replace('。', '.').replace('「', '"').replace('」', '"').replace('！', '!').replace('？', '?')
        .replace('，', ',').replace('　', ' ').replace('・', ' ')
        // UNGEGN marks Greek η/ω with a line below; spoken Greek reads them i and o.
        .replace("̱", "")
        .replace(Regex("\\s+"), " ").trim()

    /** android.icu (API 29+, every flavour's minimum is above it). Instances are cached; a missing id is skipped. */
    internal object Icu {
        private val cache = HashMap<String, android.icu.text.Transliterator?>()

        fun apply(text: String, ids: List<String>): String? {
            if (ids.isEmpty()) return text
            for (id in ids) {
                val t = synchronized(cache) {
                    cache.getOrPut(id) { runCatching { android.icu.text.Transliterator.getInstance(id) }.getOrNull() }
                } ?: continue
                return synchronized(t) { t.transliterate(text) }
            }
            return null
        }
    }

    /** Revised Romanization of Korean, with liaison, nasal assimilation and the double ㄹ. */
    internal object Hangul {
        private val initials = arrayOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
        private val medials = arrayOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
        private val finals = arrayOf("", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p", "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t")
        // A final carried over onto a following silent ㅇ (liaison).
        private val linked = arrayOf("", "g", "kk", "gs", "n", "nj", "n", "d", "r", "lg", "lm", "lb", "ls", "lt", "lp", "r", "m", "b", "bs", "s", "ss", "ng", "j", "ch", "k", "t", "p", "")
        private val kClass = setOf(1, 2, 3, 9, 24)
        private val tClass = setOf(7, 19, 20, 22, 23, 25, 27)
        private val pClass = setOf(14, 17, 18, 26)

        private fun parts(c: Char): IntArray? {
            val i = c.code - 0xAC00
            if (i !in 0..11171) return null
            return intArrayOf(i / 588, (i % 588) / 28, i % 28)
        }

        fun romanize(text: String): String {
            val out = StringBuilder()
            var carriedInitial: String? = null
            for ((index, c) in text.withIndex()) {
                val p = parts(c)
                if (p == null) { out.append(c); carriedInitial = null; continue }
                val next = text.getOrNull(index + 1)?.let(::parts)
                out.append(carriedInitial ?: initials[p[0]]).append(medials[p[1]])
                carriedInitial = null
                val f = p[2]
                out.append(when {
                    f == 0 -> ""
                    next == null -> finals[f]
                    next[0] == 11 -> linked[f]
                    next[0] == 5 && (f == 8 || f == 4) -> { carriedInitial = "l"; "l" }
                    next[0] == 2 || next[0] == 6 -> when (f) { in kClass -> "ng"; in tClass -> "n"; in pClass -> "m"; else -> finals[f] }
                    else -> finals[f]
                })
            }
            return out.toString()
        }
    }
}
