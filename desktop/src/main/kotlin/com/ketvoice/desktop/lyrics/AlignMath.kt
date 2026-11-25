package com.ketvoice.desktop.lyrics

import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.util.Romanizer
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The arithmetic of the word sync by AI, pure: Whisper's word-timing recipe (`whisper.timing.find_alignment`: the
 * alignment heads' cross-attention, softmax over the window's frames, normalised over the tokens, a median filter of
 * 7, averaged, then DTW), how a song is cut into windows, how lyric lines become words and tokens, and how the times
 * found are made into the lines the lyrics view draws. Checked against Whisper's own code on fixtures (AlignMathTest).
 */
object AlignMath {
    const val RATE = 16_000
    const val WINDOW_SAMPLES = 30 * RATE
    /** Encoder frames per second (Whisper's TOKENS_PER_SECOND). */
    const val FRAMES_PER_SECOND = 50
    const val AUDIO_FRAMES = 1500
    /** `<|startoftranscript|>`, the language and `<|transcribe|>`; `<|notimestamps|>` follows them. */
    const val SOT_LEN = 3
    const val MEDIAN = 7
    /** A window never holds more words than this many tokens (Whisper's decoder takes 448 in all). */
    const val MAX_TEXT_TOKENS = 220
    /** A window's audio before its first line (a line's time is often a little late). */
    const val LEAD_MS = 400L
    const val MAX_WINDOW_MS = 29_000L

    /**
     * Word starts and ends (seconds into the window) from the decoder's [cross]-attention ([heads] × [tokens] × 1500,
     * row-major), for a window of [melFrames] log-mel frames (samples / 160) and words of [wordLengths] tokens. The
     * tokens are `SOT, language, transcribe, notimestamps, text…, EOT`. Whisper's recipe starts the first word at the
     * window's start; with [onsetFirst] it starts where the attention that predicts it first rises instead (lyrics
     * without times, whose windows may start before the singing).
     */
    fun wordTimes(cross: FloatArray, heads: Int, tokens: Int, melFrames: Int, wordLengths: List<Int>, onsetFirst: Boolean = false): List<Pair<Double, Double>> {
        val frames = (melFrames / 2).coerceIn(1, AUDIO_FRAMES)
        val w = normalised(cross, heads, tokens, frames)
        val rows = tokens - 1 - SOT_LEN
        // Mean over the heads of the rows from notimestamps to the last text token; DTW runs on its opposite.
        val cost = Array(rows) { r -> DoubleArray(frames) { f -> -(0 until heads).sumOf { h -> w[h][r + SOT_LEN][f].toDouble() }.toFloat() / heads.toDouble() } }
        // find_alignment's mean is float32: keep that rounding.
        for (row in cost) for (f in row.indices) row[f] = row[f].toFloat().toDouble()
        val (textIdx, timeIdx) = dtw(cost)
        val jumps = ArrayList<Double>(rows)
        for (k in textIdx.indices) if (k == 0 || textIdx[k] != textIdx[k - 1]) jumps += timeIdx[k].toDouble() / FRAMES_PER_SECOND
        val bounds = wordLengths.runningFold(0) { a, b -> a + b }
        val times = wordLengths.indices.map { i -> jumps[bounds[i]] to jumps[bounds[i + 1]] }.toMutableList()
        if (onsetFirst && times.isNotEmpty()) {
            val until = (times[0].second * FRAMES_PER_SECOND).toInt().coerceIn(1, frames)
            times[0] = onset(cost[0], until) / FRAMES_PER_SECOND.toDouble() to times[0].second
        }
        return times
    }

    /** The first frame before [until] where the attention ([cost] is its opposite) is halfway up to its peak. */
    internal fun onset(cost: DoubleArray, until: Int): Int {
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (f in 0 until until) { val v = -cost[f]; if (v < lo) lo = v; if (v > hi) hi = v }
        val half = lo + (hi - lo) / 2
        return (0 until until).firstOrNull { -cost[it] >= half } ?: 0
    }

    /** Softmax over the frames, normalised over the tokens (population deviation), then the median filter: [h][t][f]. */
    internal fun normalised(cross: FloatArray, heads: Int, tokens: Int, frames: Int): Array<Array<FloatArray>> {
        val out = Array(heads) { h -> Array(tokens) { t -> softmax(cross, (h * tokens + t) * AUDIO_FRAMES, frames) } }
        for (h in 0 until heads) for (f in 0 until frames) {
            var mean = 0.0
            for (t in 0 until tokens) mean += out[h][t][f]
            mean /= tokens
            var variance = 0.0
            for (t in 0 until tokens) { val d = out[h][t][f] - mean; variance += d * d }
            val std = sqrt(variance / tokens)
            for (t in 0 until tokens) out[h][t][f] = ((out[h][t][f] - mean) / std).toFloat()
        }
        for (h in 0 until heads) for (t in 0 until tokens) out[h][t] = median(out[h][t], MEDIAN)
        return out
    }

    private fun softmax(values: FloatArray, from: Int, count: Int): FloatArray {
        var max = Float.NEGATIVE_INFINITY
        for (i in 0 until count) max = maxOf(max, values[from + i])
        val out = FloatArray(count)
        var sum = 0.0
        for (i in 0 until count) { val e = exp((values[from + i] - max).toDouble()); out[i] = e.toFloat(); sum += e }
        for (i in 0 until count) out[i] = (out[i] / sum).toFloat()
        return out
    }

    /** Median of [width] values around each one, the ends mirrored (torch's reflect padding); short rows stay as they are. */
    fun median(x: FloatArray, width: Int): FloatArray {
        val pad = width / 2
        if (x.size <= pad) return x
        fun at(i: Int): Float = x[when { i < 0 -> -i; i >= x.size -> 2 * (x.size - 1) - i; else -> i }]
        val window = FloatArray(width)
        return FloatArray(x.size) { i ->
            for (k in 0 until width) window[k] = at(i - pad + k)
            window.sort(); window[pad]
        }
    }

    /** Whisper's `dtw_cpu` and `backtrace` (float32 costs, the same tie order): the path's rows and columns. */
    fun dtw(x: Array<DoubleArray>): Pair<IntArray, IntArray> {
        val n = x.size; val m = x[0].size
        val cost = Array(n + 1) { FloatArray(m + 1) { Float.POSITIVE_INFINITY } }
        val trace = Array(n + 1) { ByteArray(m + 1) { -1 } }
        cost[0][0] = 0f
        for (j in 1..m) for (i in 1..n) {
            val c0 = cost[i - 1][j - 1]; val c1 = cost[i - 1][j]; val c2 = cost[i][j - 1]
            val (c, t) = when { c0 < c1 && c0 < c2 -> c0 to 0; c1 < c0 && c1 < c2 -> c1 to 1; else -> c2 to 2 }
            cost[i][j] = (x[i - 1][j - 1] + c).toFloat()
            trace[i][j] = t.toByte()
        }
        for (j in 0..m) trace[0][j] = 2
        for (i in 0..n) trace[i][0] = 1
        val rows = ArrayList<Int>(); val cols = ArrayList<Int>()
        var i = n; var j = m
        while (i > 0 || j > 0) {
            rows += i - 1; cols += j - 1
            when (trace[i][j].toInt()) { 0 -> { i--; j-- }; 1 -> i--; 2 -> j--; else -> error("dtw trace") }
        }
        return rows.reversed().toIntArray() to cols.reversed().toIntArray()
    }

    /* ── Words and tokens ── */

    /**
     * A line's words as the lyrics view times them: each run of text with the spaces after it; Chinese and Japanese
     * runs one character at a time (they are sung that way and have no spaces).
     */
    fun pieces(text: String): List<String> = Regex("\\S+\\s*").findAll(text.trim()).flatMap { m ->
        val word = m.value
        if (word.none(::ideographic)) sequenceOf(word)
        else sequence {
            val sb = StringBuilder()
            for (c in word) {
                if (ideographic(c) && sb.isNotEmpty() && sb.any(::ideographic)) { yield(sb.toString()); sb.clear() }
                sb.append(c)
            }
            if (sb.isNotEmpty()) yield(sb.toString())
        }
    }.toList()

    private fun ideographic(c: Char): Boolean = Romanizer.scriptOf(c).let { it == Romanizer.Script.Han || it == Romanizer.Script.Kana }

    /** The text a piece is encoded from: a space before words (as Whisper writes them), none before a character. */
    fun spoken(piece: String, index: Int): String {
        val t = piece.trim()
        return if (t.isNotEmpty() && ideographic(t[0]) && index > 0) t else " $t"
    }

    /* ── Windows ── */

    /** A stretch of the song aligned at once: [lines] are indices into the song's lines. */
    data class Window(val startMs: Long, val endMs: Long, val lines: List<Int>)

    /**
     * Windows over line-timed lyrics: each line runs from a little before its time to the next line's time (at most
     * a second per word and three seconds more, so an instrumental after it is left out); consecutive lines share a
     * window while it stays under 29 s and [MAX_TEXT_TOKENS].
     */
    fun windows(lines: List<MusicApi.LyricLine>, durationMs: Long, tokensOf: (Int) -> Int): List<Window> {
        val timed = lines.indices.filter { lines[it].timeMs >= 0 && lines[it].text.isNotBlank() }
        val out = ArrayList<Window>()
        var group = ArrayList<Int>(); var start = 0L; var end = 0L; var tokens = 0
        for ((k, i) in timed.withIndex()) {
            val line = lines[i]
            val next = timed.getOrNull(k + 1)?.let { lines[it].timeMs } ?: durationMs.takeIf { it > line.timeMs } ?: (line.timeMs + 8_000)
            val lineEnd = minOf(next, line.timeMs + 3_000 + 1_000L * pieces(line.text).size)
            val lineStart = (line.timeMs - LEAD_MS).coerceAtLeast(0L)
            val count = tokensOf(i)
            if (group.isNotEmpty() && (lineEnd - start > MAX_WINDOW_MS || tokens + count > MAX_TEXT_TOKENS)) {
                out += Window(start, end, group); group = ArrayList(); tokens = 0
            }
            if (group.isEmpty()) start = lineStart
            group += i; tokens += count
            end = minOf(lineEnd, start + MAX_WINDOW_MS)
        }
        if (group.isNotEmpty()) out += Window(start, end, group)
        return out
    }

    /* ── Times to lines ── */

    /** A word's place and confidence as aligned: [startMs]/[endMs] in the song. */
    data class Timed(val startMs: Long, val endMs: Long, val probability: Double)

    /**
     * [line] with its words timed from [found] (one per [pieces] of its text). The line keeps its own time, which is
     * also its first word's; the others stay in order, at least 40 ms apart, inside the line ([lineEndMs]); each word
     * ends where the next begins, the last where it was heard to end. Null when the words were not heard (mean
     * probability under [MIN_LINE_PROBABILITY]): the line then keeps line timing.
     */
    fun timedLine(line: MusicApi.LyricLine, found: List<Timed>, lineEndMs: Long): MusicApi.LyricLine? {
        val parts = pieces(line.text)
        if (parts.size < 2 || found.size != parts.size || found.map { it.probability }.average() < MIN_LINE_PROBABILITY) return null
        val starts = LongArray(parts.size)
        val ceiling = lineEndMs.coerceAtLeast(line.timeMs + GAP_MS * parts.size)
        for (i in parts.indices) {
            val floor = if (i == 0) line.timeMs else starts[i - 1] + GAP_MS
            val latest = ceiling - GAP_MS * (parts.size - i)
            starts[i] = if (i == 0) line.timeMs else found[i].startMs.coerceIn(floor, maxOf(floor, latest))
        }
        val lastEnd = found.last().endMs.coerceIn(starts.last() + GAP_MS, maxOf(starts.last() + GAP_MS, ceiling))
        val words = parts.mapIndexed { i, p -> MusicApi.LyricWord(starts[i], p, if (i + 1 < parts.size) starts[i + 1] else lastEnd) }
        return line.copy(words = words, syllableTimed = false)
    }

    /** Below this mean probability, a line's words were not heard. */
    const val MIN_LINE_PROBABILITY = .03
    /** Below this mean probability over the song, the lyrics are not what is sung: nothing is kept. */
    const val MIN_SONG_PROBABILITY = .12
    const val GAP_MS = 40L

    /** Whether a song's result is worth keeping: enough lines timed, and the text heard on the whole. */
    fun acceptable(probabilities: List<Double>, timedLines: Int, lines: Int): Boolean =
        probabilities.isNotEmpty() && probabilities.average() >= MIN_SONG_PROBABILITY && timedLines * 2 >= lines

    /* ── Language ── */

    /** Whisper languages written in each script (the text's script narrows the language the model picks). */
    private val byScript = mapOf(
        Romanizer.Script.Kana to listOf("ja"), Romanizer.Script.Hangul to listOf("ko"), Romanizer.Script.Han to listOf("zh", "ja"),
        Romanizer.Script.Cyrillic to listOf("ru", "uk", "bg", "sr", "be", "mk", "kk", "mn", "tg", "tt", "ba"),
        Romanizer.Script.Greek to listOf("el"), Romanizer.Script.Arabic to listOf("ar", "fa", "ur", "ps", "sd"),
        Romanizer.Script.Hebrew to listOf("he", "yi"), Romanizer.Script.Devanagari to listOf("hi", "mr", "ne", "sa"),
        Romanizer.Script.Thai to listOf("th"))

    private val nonLatin = byScript.values.flatten().toSet() + setOf("ta", "te", "ml", "kn", "bn", "gu", "pa", "si", "km", "lo",
        "my", "bo", "ka", "hy", "am", "as", "yue")

    /** The languages the lyrics' text allows: by its main script (kana anywhere is Japanese); Latin script, the rest. */
    fun allowedLanguages(texts: List<String>): List<String> {
        val counts = HashMap<Romanizer.Script, Int>()
        for (t in texts) for (c in t) Romanizer.scriptOf(c).takeIf { it != Romanizer.Script.Other }?.let { counts[it] = (counts[it] ?: 0) + 1 }
        if ((counts[Romanizer.Script.Kana] ?: 0) > 0) return listOf("ja")
        val main = counts.maxByOrNull { it.value }?.key ?: return WhisperTokens.LANGUAGES
        return byScript[main] ?: WhisperTokens.LANGUAGES.filter { it !in nonLatin }
    }

    /** The most likely of [allowed] from the decoder's language logits (position 0, after `<|startoftranscript|>`). */
    fun pickLanguage(logits: FloatArray, allowed: List<String>): String =
        allowed.maxByOrNull { code -> WhisperTokens.languageToken(code)?.let { logits.getOrNull(it) } ?: Float.NEGATIVE_INFINITY } ?: "en"
}
