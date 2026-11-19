package com.ketvoice.app.automix

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What Ketmix knows about a stretch of a title (its first or last seconds). Every time
 * is a position in the title, in milliseconds.
 */
internal data class SegmentAnalysis(
    val startMs: Long,
    val lengthMs: Long,
    /** 0 when no steady tempo was found. */
    val bpm: Float = 0f,
    /** Position of one beat; the others are every [beatMs] before and after it. */
    val beatAtMs: Double = 0.0,
    /** 0 = no beat to rely on, 1 = a metronome. */
    val confidence: Float = 0f,
    /** Beats from [beatAtMs] to a first beat of a bar (0..3), or -1 when bars could not be told apart. */
    val barOffset: Int = -1,
    /** Level of the loud part, dBFS. */
    val loudnessDb: Float = -90f,
    /** First and last sound that is not silence. */
    val soundStartMs: Long = startMs,
    val soundEndMs: Long = startMs + lengthMs,
    /** Where the level reaches that of the body of the title (end of a quiet intro). */
    val riseMs: Long = startMs,
    /** From where the level stays under that of the body (start of an outro). */
    val fallMs: Long = startMs + lengthMs,
    /** The title ends on a fade rather than on a last note. */
    val fadeOut: Boolean = false,
    /** The title is at full level until its last note. */
    val hardEnding: Boolean = false,
    /** See [MusicalKey]: 0..23, [MusicalKey.NONE] when the music does not say, [MusicalKey.UNREAD] when it was not looked for. */
    val key: Int = MusicalKey.UNREAD,
    val keyStrength: Float = 0f,
    /**
     * Start of a phrase: a first beat of a bar from which the music changes every
     * [PHRASE_BARS] bars (a part comes in or drops out); -1 when no such grid stands out.
     */
    val phraseAtMs: Double = -1.0,
    /**
     * The tempo of the body of the title this stretch was read against (see [BeatAnalysis.Hint]);
     * 0 when it was read on its own.
     */
    val tempoHint: Float = 0f,
    /** Who read the beats: 0 Ketmix's signal analysis, [BEAT_THIS] the Beat This! model, [BEAT_TRIED] the model was run and the signal analysis kept. */
    val analyser: Int = 0,
    /**
     * 7.1: the last silence of 2 s or more inside the stretch that the music comes back after (a false ending, or
     * a hidden title after a long silence): from where to where, -1 when there is none. A mix never starts in it.
     */
    val gapFromMs: Long = -1,
    val gapUntilMs: Long = -1,
    /** 7.1: how busy the sound is (mean spectral flux of its audible part, see [OnsetTracker]); 0 when unknown. */
    val activity: Float = 0f
) {
    val beatMs: Float get() = if (bpm > 0f) 60_000f / bpm else 0f
    val hasBeat: Boolean get() = bpm > 0f && confidence >= MIN_CONFIDENCE

    /** The beat at or after [positionMs]. */
    fun beatAtOrAfter(positionMs: Double): Double {
        if (bpm <= 0f) return positionMs
        val period = beatMs.toDouble()
        return beatAtMs + ceil((positionMs - beatAtMs) / period - 1e-6) * period
    }

    val hasPhrases: Boolean get() = phraseAtMs >= 0.0 && bpm > 0f && barOffset >= 0

    /** Length of a phrase on this title's grid. */
    val phraseMs: Double get() = beatMs.toDouble() * 4 * PHRASE_BARS

    /** The phrase starts between [fromMs] and [untilMs], earliest first. */
    fun phraseStarts(fromMs: Double, untilMs: Double): List<Double> {
        if (!hasPhrases || untilMs < fromMs) return emptyList()
        val period = phraseMs
        var at = phraseAtMs + kotlin.math.ceil((fromMs - phraseAtMs) / period - 1e-6) * period
        val found = ArrayList<Double>()
        while (at <= untilMs + 1e-6 && found.size < 64) { found += at; at += period }
        return found
    }

    /** The first beat of a bar at or after [positionMs]; a plain beat when bars are unknown. */
    fun barAtOrAfter(positionMs: Double): Double {
        if (bpm <= 0f || barOffset < 0) return beatAtOrAfter(positionMs)
        val bar = beatMs.toDouble() * 4
        val first = beatAtMs + barOffset * beatMs.toDouble()
        return first + ceil((positionMs - first) / bar - 1e-6) * bar
    }

    companion object {
        const val MIN_CONFIDENCE = .45f
        const val BEAT_THIS = 1
        const val BEAT_TRIED = 2
        /** Pop, dance and hip-hop are built on phrases of eight bars. */
        const val PHRASE_BARS = 8
    }
}

internal object BeatAnalysis {
    const val MIN_BPM = 62f
    const val MAX_BPM = 190f
    private const val MIN_SECONDS = 8f

    internal class Tempo(val bpm: Float, val periodFrames: Float, val phaseFrames: Float, val confidence: Float, val barOffset: Int)

    /**
     * [thorough] also checks that the tempo holds from one half of the stretch to the other: a
     * band that speeds up or slows down cannot be kept in time for long, and is not trusted to.
     */
    /**
     * The tempo of the body of a title, where its drums play: what its intro and its ending are
     * read against. An intro without drums, or an ending where only hats are left, cannot tell a
     * tempo from one that is 3/2 or 2/3 of it; the body can, and a produced title keeps its tempo.
     */
    class Hint(val bpm: Float, val confidence: Float)

    fun analyze(envelope: OnsetEnvelope, startMs: Long, thorough: Boolean = false, hint: Hint? = null): SegmentAnalysis {
        val lengthMs = (envelope.seconds * 1000).toLong()
        if (envelope.size < 8) return SegmentAnalysis(startMs, lengthMs)
        val shape = structure(envelope)
        val usable = hint?.takeIf { it.bpm in MIN_BPM..MAX_BPM && it.confidence >= SegmentAnalysis.MIN_CONFIDENCE }
        val alone = tempo(envelope)
        val hinted = usable?.let { tempo(envelope, it) }
        // A grid at the body's tempo that the attacks of this stretch support is taken over a reading of
        // its own, unless that reading is sure of a tempo that is no relative of the body's (the title changes tempo).
        val ownTempo = usable != null && alone != null && alone.confidence >= OWN_TEMPO_CONFIDENCE &&
            RELATIVES.none { abs(alone.bpm / (usable.bpm * it) - 1f) <= .03f }
        val followed = hinted != null && hinted.confidence >= SegmentAnalysis.MIN_CONFIDENCE && !ownTempo
        val tempo = (if (followed) hinted else alone?.let { if (thorough) steadied(it, envelope) else it })
        val (key, keyStrength) = if (envelope.keyRead) MusicalKey.estimate(envelope.chroma) else MusicalKey.UNREAD to 0f
        fun ms(frame: Float) = startMs + (envelope.timeOf(frame) * 1000).toLong()
        return SegmentAnalysis(
            startMs = startMs, lengthMs = lengthMs,
            bpm = tempo?.bpm ?: 0f,
            beatAtMs = tempo?.let { startMs + envelope.timeOf(it.phaseFrames) * 1000.0 } ?: 0.0,
            confidence = tempo?.confidence ?: 0f,
            barOffset = tempo?.barOffset ?: -1,
            loudnessDb = shape.loudnessDb,
            soundStartMs = ms(shape.soundStart.toFloat()).coerceAtLeast(startMs),
            soundEndMs = ms(shape.soundEnd.toFloat()).coerceAtMost(startMs + lengthMs),
            riseMs = ms(shape.rise.toFloat()).coerceAtLeast(startMs),
            fallMs = ms(shape.fall.toFloat()).coerceAtMost(startMs + lengthMs),
            fadeOut = shape.fadeOut, hardEnding = shape.hardEnding, key = key, keyStrength = keyStrength,
            phraseAtMs = tempo?.takeIf { it.barOffset >= 0 && it.confidence >= SegmentAnalysis.MIN_CONFIDENCE }
                ?.let { phrase(envelope, it) }?.let { startMs + envelope.timeOf(it) * 1000.0 } ?: -1.0,
            tempoHint = if (followed) usable?.bpm ?: 0f else 0f,
            gapFromMs = if (shape.gapFrom >= 0) ms(shape.gapFrom.toFloat()) else -1L,
            gapUntilMs = if (shape.gapUntil >= 0) ms(shape.gapUntil.toFloat()) else -1L,
            activity = shape.activity)
    }

    /**
     * Where the phrases start, as a frame: the bar of each group of eight where the level
     * and the spectrum change the most, when one of the eight positions clearly stands out.
     */
    internal fun phrase(envelope: OnsetEnvelope, tempo: Tempo): Float? {
        val bar = tempo.periodFrames * 4f
        val first = tempo.phaseFrames + tempo.barOffset * tempo.periodFrames
        val bars = ((envelope.size - 1 - first) / bar).toInt()
        if (bars < SegmentAnalysis.PHRASE_BARS + 4) return null
        // Level and spectral activity of each bar.
        val level = DoubleArray(bars); val activity = DoubleArray(bars)
        for (i in 0 until bars) {
            val from = (first + i * bar).toInt().coerceAtLeast(0)
            val until = (first + (i + 1) * bar).toInt().coerceAtMost(envelope.size)
            var power = 0.0; var flux = 0.0
            for (f in from until until) { power += envelope.level[f]; flux += envelope.flux[f] }
            val n = (until - from).coerceAtLeast(1)
            level[i] = 10.0 * log10(power / n + 1e-12); activity[i] = flux / n
        }
        val meanActivity = activity.average().coerceAtLeast(1e-9)
        // How much each bar differs from the one before: a part coming in or dropping out.
        val change = DoubleArray(bars)
        for (i in 1 until bars) change[i] = abs(level[i] - level[i - 1]) / 3.0 + abs(activity[i] - activity[i - 1]) / meanActivity
        val score = DoubleArray(SegmentAnalysis.PHRASE_BARS)
        val counts = IntArray(SegmentAnalysis.PHRASE_BARS)
        for (i in 1 until bars) { score[i % SegmentAnalysis.PHRASE_BARS] += change[i]; counts[i % SegmentAnalysis.PHRASE_BARS]++ }
        for (p in score.indices) score[p] /= counts[p].coerceAtLeast(1)
        val best = score.indices.maxByOrNull { score[it] } ?: return null
        val others = score.indices.filter { it != best }.map { score[it] }.average()
        // A grid that never changes has no phrases to tell, however its rounding errors compare.
        if (score[best] < PHRASE_MIN_CHANGE || (others > 1e-9 && score[best] / others < PHRASE_CONTRAST)) return null
        return first + best * bar
    }
    private const val PHRASE_CONTRAST = 1.6
    private const val PHRASE_MIN_CHANGE = .25

    /** A tempo that is not the same in both halves of the stretch loses the confidence long mixes need. */
    private fun steadied(whole: Tempo, envelope: OnsetEnvelope): Tempo {
        val half = envelope.size / 2
        if (half < envelope.fps * MIN_SECONDS) return whole
        val first = tempo(envelope.slice(0, half)) ?: return whole
        val second = tempo(envelope.slice(half, envelope.size)) ?: return whole
        if (first.confidence < SegmentAnalysis.MIN_CONFIDENCE || second.confidence < SegmentAnalysis.MIN_CONFIDENCE) return whole
        val drift = abs(foldedRatio(first.bpm, second.bpm) - 1f)
        return if (drift <= MAX_DRIFT) whole
            else Tempo(whole.bpm, whole.periodFrames, whole.phaseFrames, whole.confidence * .5f, whole.barOffset)
    }
    private const val MAX_DRIFT = .012f

    // ---------------------------------------------------------------- tempo and beats

    /** Flux above its own local average: what stands out as an attack. */
    private fun accents(values: FloatArray, fps: Float): FloatArray {
        val n = values.size
        val half = max(2, (fps * .35f).toInt())
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + values[i]
        return FloatArray(n) { i ->
            val a = max(0, i - half); val b = min(n, i + half + 1)
            max(0f, values[i] - ((prefix[b] - prefix[a]) / (b - a)).toFloat())
        }
    }

    private fun at(values: FloatArray, position: Float): Float {
        if (position < 0f || position > values.size - 1) return 0f
        val i = position.toInt()
        if (i >= values.size - 1) return values[values.size - 1]
        val t = position - i
        return values[i] * (1f - t) + values[i + 1] * t
    }

    fun tempo(envelope: OnsetEnvelope, hint: Hint? = null): Tempo? {
        val fps = envelope.fps
        val n = envelope.size
        if (n < fps * MIN_SECONDS) return null
        val onsets = accents(envelope.flux, fps)
        var total = 0.0
        for (v in onsets) total += v
        if (total / n < 1e-4) return null
        val attacks = peaks(onsets)
        if (attacks.size < 8) return null

        // How much the accents repeat after each delay.
        val maxLag = min(n / 2, ceil(fps * 60f / MIN_BPM * 4f).toInt() + 2)
        val repeat = FloatArray(maxLag + 1)
        for (lag in 1..maxLag) {
            var sum = 0.0
            val count = n - lag
            for (i in 0 until count) sum += onsets[i] * onsets[i + lag]
            repeat[lag] = (sum / count).toFloat()
        }
        var first = 0f; var firstScore = Float.NEGATIVE_INFINITY
        var bpm = if (hint != null) MAX_BPM + 1f else MIN_BPM
        if (hint != null) { first = hint.bpm; firstScore = 1f }
        while (bpm <= MAX_BPM) {
            val period = fps * 60f / bpm
            val score = (at(repeat, period) + .6f * at(repeat, 2 * period) + .4f * at(repeat, 3 * period) + .3f * at(repeat, 4 * period)) * prior(bpm)
            if (score > firstScore) { firstScore = score; first = bpm }
            bpm += .25f
        }
        if (firstScore <= 0f) return null

        // The repetition alone cannot tell a tempo from one that is 3/2 or 2/3 of it when hats
        // sit between the beats. Each relative is tried against the attacks themselves: the
        // right grid has an attack on its beats and leaves the fewest attacks outside it.
        val bass = accents(envelope.low, fps)
        var chosen: Tempo? = null; var chosenFit = -1f
        for (factor in if (hint != null) ONLY else RELATIVES) {
            val candidate = first * factor
            if (candidate < MIN_BPM || candidate > MAX_BPM) continue
            val coarse = fps * 60f / candidate
            var period = coarse; var phase = 0f; var top = -1f
            var p = coarse * .98f
            val step = coarse * .0005f
            while (p <= coarse * 1.02f) {
                var phi = 0f
                while (phi < p) {
                    val energy = comb(onsets, p, phi)
                    if (energy > top) { top = energy; period = p; phase = phi }
                    phi += .5f
                }
                p += step
            }
            // Hats and snares often fall between the beats and weigh as much as the kick: when
            // the bass has a clear pulse of its own at this tempo, the beats are where it hits.
            var bassPhase = 0f; var bassTop = -1f
            var probe = 0f
            while (probe < period) {
                val energy = comb(bass, period, probe)
                if (energy > bassTop) { bassTop = energy; bassPhase = probe }
                probe += .5f
            }
            if (contrast(bass, period, bassTop) >= 1.2f) phase = bassPhase

            val fit = fit(attacks, period, phase, n)
            val score = fit.f + .04f * prior(fps * 60f / period)
            if (score > chosenFit) {
                chosenFit = score
                val own = ((fit.recall - .45f) / .4f).coerceIn(0f, 1f) * ((fit.f - .25f) / .3f).coerceIn(0f, 1f)
                // The tempo is known from the body of the title: this stretch only has to place the beats,
                // which a few attacks on the grid do. Without any, the grid is not trusted.
                val confidence = if (hint != null && own >= HINTED_MIN_CONFIDENCE) max(own, min(hint.confidence, HINTED_CONFIDENCE)) else own
                // Bars: the beat of four that carries the most bass attacks.
                val sums = FloatArray(4)
                var beat = 0; var position = phase
                while (position < n - 1) { sums[beat % 4] += at(bass, position); beat++; position += period }
                val order = sums.indices.sortedByDescending { sums[it] }
                val barOffset = if (beat >= 16 && sums[order[0]] > 0f && (sums[order[0]] - sums[order[1]]) / sums[order[0]] >= .18f) order[0]
                    // No bass that tells the bars apart: the first beat of a bar is where the sound changes most from one beat to the next.
                    else if (beat >= 16) downbeatByChange(envelope, period, phase) else -1
                chosen = Tempo(fps * 60f / period, period, phase, confidence, barOffset)
            }
        }
        return chosen
    }

    private val RELATIVES = floatArrayOf(1f, 2f, .5f, 1.5f, 2f / 3f, 4f / 3f, .75f)
    private val ONLY = floatArrayOf(1f)
    /** What a stretch's own attacks must give for a grid at the body's tempo to be placed on them. */
    const val HINTED_MIN_CONFIDENCE = .15f
    /** The most a stretch read against the body is trusted when its own attacks say less. */
    const val HINTED_CONFIDENCE = .6f
    private const val OWN_TEMPO_CONFIDENCE = .8f

    /**
     * After Davies and Plumbley (2006): the spectrum and level of each beat compared with the
     * beat before, folded over the four positions of a bar; the position where most comes in is
     * the first beat. -1 when no position stands out.
     */
    internal fun downbeatByChange(envelope: OnsetEnvelope, period: Float, phase: Float): Int {
        val beats = ((envelope.size - 1 - phase) / period).toInt()
        if (beats < 16) return -1
        val level = DoubleArray(beats); val activity = DoubleArray(beats)
        for (i in 0 until beats) {
            val from = (phase + i * period).toInt().coerceAtLeast(0)
            val until = (phase + (i + 1) * period).toInt().coerceAtMost(envelope.size)
            var power = 0.0; var flux = 0.0
            for (f in from until until) { power += envelope.level[f]; flux += envelope.flux[f] }
            val n = (until - from).coerceAtLeast(1)
            level[i] = 10.0 * log10(power / n + 1e-12); activity[i] = flux / n
        }
        val meanActivity = activity.average().coerceAtLeast(1e-9)
        val sums = DoubleArray(4); val counts = IntArray(4)
        for (i in 1 until beats) {
            // What comes in counts, not what dies away: a crash on the one would otherwise mark the two as well.
            sums[i % 4] += max(0.0, level[i] - level[i - 1]) / 3.0 + max(0.0, activity[i] - activity[i - 1]) / meanActivity
            counts[i % 4]++
        }
        for (k in 0 until 4) sums[k] /= counts[k].coerceAtLeast(1)
        val best = sums.indices.maxByOrNull { sums[it] } ?: return -1
        val others = sums.indices.filter { it != best }.map { sums[it] }.average()
        return if (sums[best] >= .2 && others > 1e-9 && sums[best] / others >= 1.35) best else -1
    }

    /** Frames where an attack stands out: local maxima above a quarter of the strong ones. */
    private fun peaks(onsets: FloatArray): IntArray {
        val positive = onsets.filter { it > 0f }.sorted()
        if (positive.isEmpty()) return IntArray(0)
        val threshold = .25f * positive[(positive.size * .95f).toInt().coerceAtMost(positive.size - 1)]
        val found = ArrayList<Int>()
        for (i in 1 until onsets.size - 1) {
            if (onsets[i] >= threshold && onsets[i] >= onsets[i - 1] && onsets[i] > onsets[i + 1]) found += i
        }
        return found.toIntArray()
    }

    private class Fit(val recall: Float, val f: Float)

    /** How well a grid and the attacks agree: beats that have an attack, attacks that are on a beat. */
    private fun fit(attacks: IntArray, period: Float, phase: Float, frames: Int): Fit {
        val tolerance = max(1.5f, period * .08f)
        val firstAttack = attacks.first(); val lastAttack = attacks.last()
        var beats = 0; var beatsHit = 0
        var position = phase
        var cursor = 0
        while (position <= frames - 1) {
            if (position >= firstAttack - tolerance && position <= lastAttack + tolerance) {
                beats++
                while (cursor < attacks.size && attacks[cursor] < position - tolerance) cursor++
                if (cursor < attacks.size && attacks[cursor] <= position + tolerance) beatsHit++
            }
            position += period
        }
        var onGrid = 0
        for (attack in attacks) {
            val beatsFromPhase = (attack - phase) / period
            if (abs(beatsFromPhase - floor(beatsFromPhase + .5f)) * period <= tolerance) onGrid++
        }
        if (beats == 0) return Fit(0f, 0f)
        val recall = beatsHit.toFloat() / beats
        val precision = onGrid.toFloat() / attacks.size
        return Fit(recall, if (recall + precision > 0f) 2f * recall * precision / (recall + precision) else 0f)
    }

    private fun contrast(onsets: FloatArray, period: Float, top: Float): Float {
        var mean = 0f; var count = 0
        var phi = 0f
        while (phi < period) { mean += comb(onsets, period, phi); count++; phi += .5f }
        mean /= max(1, count)
        return if (mean > 1e-9f) (top - mean) / mean else 0f
    }

    private fun comb(onsets: FloatArray, period: Float, phase: Float): Float {
        var sum = 0f; var count = 0
        var position = phase
        val last = onsets.size - 1
        while (position <= last) { sum += at(onsets, position); count++; position += period }
        return if (count == 0) 0f else sum / count
    }

    /** Most music sits around 120 BPM; the prior only settles ties between a tempo and its double or half. */
    private fun prior(bpm: Float): Float {
        val octaves = ln(bpm / 118f) / ln(2f)
        return exp(-.5f * (octaves / .8f) * (octaves / .8f))
    }

    // ---------------------------------------------------------------- level, intro and outro

    internal class Shape(val loudnessDb: Float, val soundStart: Int, val soundEnd: Int, val rise: Int, val fall: Int,
                         val fadeOut: Boolean, val hardEnding: Boolean,
                         val gapFrom: Int = -1, val gapUntil: Int = -1, val activity: Float = 0f)

    private fun db(power: Double): Float = (10.0 * log10(power + 1e-12)).toFloat()

    private fun smooth(values: FloatArray, half: Int): FloatArray {
        val n = values.size
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + values[i]
        return FloatArray(n) { i ->
            val a = max(0, i - half); val b = min(n, i + half + 1)
            db((prefix[b] - prefix[a]) / (b - a))
        }
    }

    fun structure(envelope: OnsetEnvelope): Shape {
        val n = envelope.size
        val fps = envelope.fps
        val short = smooth(envelope.level, max(1, (fps * .05f).toInt()))
        val long = smooth(envelope.level, max(2, fps.roundToInt()))
        val audible = long.filter { it > -55f }.sorted()
        if (audible.isEmpty()) return Shape(-90f, 0, 0, 0, 0, fadeOut = false, hardEnding = false)
        val body = audible[(audible.size * .8f).toInt().coerceAtMost(audible.size - 1)]
        val silence = max(-55f, body - 40f)

        var soundStart = 0
        while (soundStart < n - 3 && !(short[soundStart] > silence && short[soundStart + 1] > silence && short[soundStart + 2] > silence)) soundStart++
        var soundEnd = n - 1
        while (soundEnd > 2 && !(short[soundEnd] > silence && short[soundEnd - 1] > silence && short[soundEnd - 2] > silence)) soundEnd--
        if (soundEnd <= soundStart) return Shape(body, 0, n - 1, 0, n - 1, fadeOut = false, hardEnding = false)

        var power = 0.0; var counted = 0
        for (i in soundStart..soundEnd) if (long[i] > body - 20f) { power += envelope.level[i]; counted++ }
        val loudness = if (counted > 0) db(power / counted) else body

        var rise = soundStart
        while (rise < soundEnd && long[rise] < body - 5f) rise++
        var fall = soundEnd
        while (fall > soundStart && long[fall] < body - 6f) fall--
        val tail = (soundEnd - fall) / fps
        val hardEnding = tail < 1.5f
        var fadeOut = false
        if (tail >= 4f) {
            val third = (soundEnd - fall) / 3
            var early = 0.0; var late = 0.0
            for (i in 0 until third) { early += long[fall + i]; late += long[soundEnd - i] }
            fadeOut = late / third < early / third - 6f
        }
        val gap = lastGap(short, soundStart, soundEnd, silence, fps)
        return Shape(loudness, soundStart, soundEnd, rise, fall, fadeOut, hardEnding, gap?.first ?: -1, gap?.last ?: -1,
            activity(envelope, soundStart, soundEnd))
    }

    /** A silence this long inside a stretch, with music after it, is a false ending (or a hidden title), not a pause. */
    const val GAP_SECONDS = 2f

    /** 7.1: the last run of frames under [silence] of [GAP_SECONDS] or more between two sounds, as a range of frames. */
    internal fun lastGap(short: FloatArray, soundStart: Int, soundEnd: Int, silence: Float, fps: Float): IntRange? {
        val needed = (GAP_SECONDS * fps).roundToInt()
        var i = soundEnd
        while (i > soundStart) {
            if (short[i] > silence) { i--; continue }
            var from = i
            while (from > soundStart && short[from - 1] <= silence) from--
            if (i - from + 1 >= needed) return from..(i + 1)
            i = from - 1
        }
        return null
    }

    private fun activity(envelope: OnsetEnvelope, from: Int, until: Int): Float {
        if (until <= from) return 0f
        var sum = 0.0
        for (i in from..until) sum += envelope.flux[i]
        return (sum / (until - from + 1)).toFloat()
    }

    /** Tempos that are the same pulse counted twice as fast or as slow are brought together. */
    fun foldedRatio(fromBpm: Float, toBpm: Float): Float {
        if (fromBpm <= 0f || toBpm <= 0f) return 1f
        var ratio = fromBpm / toBpm
        while (ratio > 1.42f) ratio /= 2f
        while (ratio < .705f) ratio *= 2f
        return ratio
    }

    internal fun nearestBeatError(analysis: SegmentAnalysis, positionMs: Double): Double {
        val period = analysis.beatMs.toDouble()
        if (period <= 0.0) return 0.0
        val beats = (positionMs - analysis.beatAtMs) / period
        return abs(beats - floor(beats + .5)) * period
    }
}
