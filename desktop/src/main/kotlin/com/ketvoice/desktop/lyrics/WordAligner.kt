package com.ketvoice.desktop.lyrics

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.stems.OrtAccel
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** What the aligner needs from the model: one encoder pass per window, one decoder pass per text (tests fake it). */
interface AlignModel : Closeable {
    /** `features` of 30 s of 16 kHz mono (zero-padded). */
    fun encode(window: FloatArray): FloatArray
    /** The alignment heads' cross-attention, each next token's probability, and the language logits. */
    fun decode(tokens: LongArray, features: FloatArray): Decoded
    val heads: Int
    fun tokens(text: String): List<Int>

    class Decoded(val cross: FloatArray, val nextProbs: FloatArray, val languageLogits: FloatArray)
}

/** Whisper small through ONNX Runtime (`ketvoice-align-v1`), on the GPU when Ketmix's check allowed it. */
class WhisperModel(encoder: File, decoder: File, vocab: File, threads: Int = OrtAccel.threads(background = true)) : AlignModel {
    private val env = OrtEnvironment.getEnvironment()
    private val enc: OrtSession = OrtAccel.session(encoder, threads)
    private val dec: OrtSession = OrtAccel.session(decoder, threads)
    private val bpe = WhisperTokens.load(vocab)
    override val heads = 10

    override fun tokens(text: String) = bpe.encode(text)

    override fun encode(window: FloatArray): FloatArray {
        require(window.size == AlignMath.WINDOW_SAMPLES)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(window), longArrayOf(window.size.toLong())).use { audio ->
            enc.run(mapOf("audio" to audio)).use { out -> return (out[0] as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } } }
        }
    }

    override fun decode(tokens: LongArray, features: FloatArray): AlignModel.Decoded {
        OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), longArrayOf(1, tokens.size.toLong())).use { t ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(features), longArrayOf(1, AlignMath.AUDIO_FRAMES.toLong(), features.size / AlignMath.AUDIO_FRAMES.toLong())).use { f ->
                dec.run(mapOf("tokens" to t, "features" to f)).use { out ->
                    fun floats(i: Int) = (out[i] as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                    return AlignModel.Decoded(floats(0), floats(1), floats(2))
                }
            }
        }
    }

    override fun close() { runCatching { enc.close() }; runCatching { dec.close() } }
}

/**
 * Times the words of lyrics that have line times only (or none) on the voice of the song: Whisper reads each window of
 * the voice with the window's known words, and [AlignMath] turns its attention into word times. Never transcribes.
 */
class WordAligner(private val model: AlignModel) {
    /** One window's words, aligned: per line index, its pieces' times. */
    private class Heard(val lines: Map<Int, List<AlignMath.Timed>>, val probabilities: List<Double>)

    /**
     * [lines] word-timed on [voice] (16 kHz mono, the whole song), or null when the text is not what is sung.
     * [cancelled] is asked between windows.
     */
    fun align(lines: List<MusicApi.LyricLine>, voice: FloatArray, cancelled: () -> Boolean = { false }): List<MusicApi.LyricLine>? {
        if (lines.none { it.timeMs >= 0 }) return alignUntimed(lines, voice, cancelled)
        val durationMs = voice.size * 1000L / AlignMath.RATE
        val tokenCounts = HashMap<Int, List<List<Int>>>()
        fun tokensOf(i: Int) = tokenCounts.getOrPut(i) { AlignMath.pieces(lines[i].text).mapIndexed { k, p -> model.tokens(AlignMath.spoken(p, k)) } }
        val windows = AlignMath.windows(lines, durationMs) { i -> tokensOf(i).sumOf { it.size } }
        val language = language(lines, voice, windows.firstOrNull()?.startMs ?: 0L)
        val found = HashMap<Int, List<AlignMath.Timed>>()
        val probabilities = ArrayList<Double>()
        for (w in windows) {
            if (cancelled()) return null
            val heard = heard(voice, w.startMs, w.endMs, w.lines, ::tokensOf, language) ?: continue
            found += heard.lines; probabilities += heard.probabilities
        }
        val timed = lines.indices.map { i ->
            val nextStart = lines.drop(i + 1).firstOrNull { it.timeMs >= 0 }?.timeMs ?: durationMs
            found[i]?.let { AlignMath.timedLine(lines[i], it, nextStart) } ?: lines[i]
        }
        return timed.takeIf { AlignMath.acceptable(probabilities, timed.count { it.words.size >= 2 }, lines.count { AlignMath.pieces(it.text).size >= 2 }) }
    }

    /**
     * Lyrics without times: windows follow one another from the first sung moment; each takes the next lines (more
     * than it can hold) and keeps those that end well before its end, then the next window starts after them.
     */
    private fun alignUntimed(lines: List<MusicApi.LyricLine>, voice: FloatArray, cancelled: () -> Boolean): List<MusicApi.LyricLine>? {
        val todo = lines.indices.filter { lines[it].text.isNotBlank() }
        val tokens = todo.associateWith { i -> AlignMath.pieces(lines[i].text).mapIndexed { k, p -> model.tokens(AlignMath.spoken(p, k)) } }
        val durationMs = voice.size * 1000L / AlignMath.RATE
        var cursor = VoiceActivity.nextVoiced(voice, 0L)
        val language = language(lines, voice, cursor)
        val out = lines.toMutableList()
        val probabilities = ArrayList<Double>()
        var at = 0
        while (at < todo.size && cursor < durationMs - 1_000) {
            if (cancelled()) return null
            val take = ArrayList<Int>(); var count = 0
            while (at + take.size < todo.size && count < AlignMath.MAX_TEXT_TOKENS * 2 / 3) { val i = todo[at + take.size]; take += i; count += tokens.getValue(i).sumOf { it.size } }
            val start = (cursor - 200).coerceAtLeast(0L); val end = minOf(start + AlignMath.MAX_WINDOW_MS, durationMs)
            val heard = heard(voice, start, end, take, { tokens.getValue(it) }, language, untimed = true) ?: return null
            val last = at + take.size == todo.size
            val kept = take.takeWhile { i -> heard.lines[i]?.lastOrNull()?.let { it.endMs < end - 3_000 || (last && end >= durationMs - 500) } == true }.ifEmpty { take.take(1) }
            for ((k, i) in kept.withIndex()) {
                val words = heard.lines[i] ?: continue
                val nextStart = kept.getOrNull(k + 1)?.let { heard.lines[it]?.firstOrNull()?.startMs } ?: words.last().endMs
                val line = lines[i].copy(timeMs = words.first().startMs)
                out[i] = AlignMath.timedLine(line, words, nextStart) ?: line
            }
            probabilities += heard.probabilities
            at += kept.size
            cursor = VoiceActivity.nextVoiced(voice, heard.lines[kept.last()]?.lastOrNull()?.endMs ?: end)
        }
        if (at < todo.size) return null
        return out.takeIf { AlignMath.acceptable(probabilities, out.count { it.words.size >= 2 }, lines.count { AlignMath.pieces(it.text).size >= 2 }) }
    }

    /** One window aligned: the voice from [startMs] (at most 30 s), the words of [lineIds]. */
    private fun heard(voice: FloatArray, startMs: Long, endMs: Long, lineIds: List<Int>, tokensOf: (Int) -> List<List<Int>>, language: String,
                      untimed: Boolean = false): Heard? {
        val from = (startMs * AlignMath.RATE / 1000).toInt().coerceIn(0, voice.size)
        val until = (endMs * AlignMath.RATE / 1000).toInt().coerceIn(from, minOf(voice.size, from + AlignMath.WINDOW_SAMPLES))
        if (until - from < AlignMath.RATE / 2) return null
        val window = FloatArray(AlignMath.WINDOW_SAMPLES).also { voice.copyInto(it, 0, from, until) }
        val words = lineIds.flatMap { i -> tokensOf(i).map { i to it } }
        val text = words.flatMap { it.second }
        if (text.isEmpty() || text.size > 440) return null
        val sequence = longArrayOf(WhisperTokens.SOT.toLong(), (WhisperTokens.languageToken(language) ?: WhisperTokens.FIRST_LANGUAGE).toLong(),
            WhisperTokens.TRANSCRIBE.toLong(), WhisperTokens.NO_TIMESTAMPS.toLong()) + text.map { it.toLong() } + WhisperTokens.EOT.toLong()
        val out = model.decode(sequence, model.encode(window))
        val times = AlignMath.wordTimes(out.cross, model.heads, sequence.size, (until - from) / 160, words.map { it.second.size }, onsetFirst = untimed)
        var token = 0
        val timed = words.mapIndexed { k, (_, toks) ->
            val p = (0 until toks.size).map { out.nextProbs.getOrElse(AlignMath.SOT_LEN + token + it) { 0f }.toDouble() }.average()
            token += toks.size
            AlignMath.Timed(startMs + (times[k].first * 1000).toLong(), startMs + (times[k].second * 1000).toLong(), p)
        }
        val byLine = LinkedHashMap<Int, MutableList<AlignMath.Timed>>()
        words.forEachIndexed { k, (line, _) -> byLine.getOrPut(line) { ArrayList() } += timed[k] }
        return Heard(byLine, timed.map { it.probability })
    }

    /** The lyrics' language: narrowed by their script, then picked by the model on the first sung window. */
    private fun language(lines: List<MusicApi.LyricLine>, voice: FloatArray, atMs: Long): String {
        val allowed = AlignMath.allowedLanguages(lines.map { it.text })
        if (allowed.size == 1) return allowed[0]
        val from = (atMs * AlignMath.RATE / 1000).toInt().coerceIn(0, voice.size)
        val window = FloatArray(AlignMath.WINDOW_SAMPLES).also { voice.copyInto(it, 0, from, minOf(voice.size, from + AlignMath.WINDOW_SAMPLES)) }
        val logits = model.decode(longArrayOf(WhisperTokens.SOT.toLong()), model.encode(window)).languageLogits
        return AlignMath.pickLanguage(logits, allowed)
    }
}

/** Where the voice is: a simple level gate over 20 ms frames of the separated voice. */
object VoiceActivity {
    private const val FRAME = AlignMath.RATE / 50
    private const val THRESHOLD = 0.01f

    /** The first moment at or after [fromMs] where the voice is heard (or [fromMs] when it never is). */
    fun nextVoiced(voice: FloatArray, fromMs: Long): Long {
        var i = (fromMs * AlignMath.RATE / 1000).toInt().coerceAtLeast(0)
        while (i + FRAME <= voice.size) {
            var sum = 0.0
            for (k in i until i + FRAME) sum += voice[k] * voice[k]
            if (kotlin.math.sqrt(sum / FRAME) > THRESHOLD) return i * 1000L / AlignMath.RATE
            i += FRAME
        }
        return fromMs
    }
}
