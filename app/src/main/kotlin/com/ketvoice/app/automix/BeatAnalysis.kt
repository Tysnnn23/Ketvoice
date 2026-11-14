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
 * What Automix knows about a stretch of a title (its first or last seconds). Every time
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
    val hardEnding: Boolean = false
) {
    val beatMs: Float get() = if (bpm > 0f) 60_000f / bpm else 0f
    val hasBeat: Boolean get() = bpm > 0f && confidence >= MIN_CONFIDENCE

    /** The beat at or after [positionMs]. */
    fun beatAtOrAfter(positionMs: Double): Double {
        if (bpm <= 0f) return positionMs
        val period = beatMs.toDouble()
        return beatAtMs + ceil((positionMs - beatAtMs) / period - 1e-6) * period
    }

    /** The first beat of a bar at or after [positionMs]; a plain beat when bars are unknown. */
    fun barAtOrAfter(positionMs: Double): Double {
        if (bpm <= 0f || barOffset < 0) return beatAtOrAfter(positionMs)
        val bar = beatMs.toDouble() * 4
        val first = beatAtMs + barOffset * beatMs.toDouble()
        return first + ceil((positionMs - first) / bar - 1e-6) * bar
    }

    companion object { const val MIN_CONFIDENCE = .45f }
}

internal object BeatAnalysis {
    const val MIN_BPM = 62f
    const val MAX_BPM = 190f
    private const val MIN_SECONDS = 8f

    internal class Tempo(val bpm: Float, val periodFrames: Float, val phaseFrames: Float, val confidence: Float, val barOffset: Int)

    fun analyze(envelope: OnsetEnvelope, startMs: Long): SegmentAnalysis {
        val lengthMs = (envelope.seconds * 1000).toLong()
        if (envelope.size < 8) return SegmentAnalysis(startMs, lengthMs)
        val shape = structure(envelope)
        val tempo = tempo(envelope)
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
            fadeOut = shape.fadeOut, hardEnding = shape.hardEnding)
    }

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

    fun tempo(envelope: OnsetEnvelope): Tempo? {
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
        var bpm = MIN_BPM
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
        for (factor in RELATIVES) {
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
                val confidence = ((fit.recall - .45f) / .4f).coerceIn(0f, 1f) * ((fit.f - .25f) / .3f).coerceIn(0f, 1f)
                // Bars: the beat of four that carries the most bass attacks.
                val sums = FloatArray(4)
                var beat = 0; var position = phase
                while (position < n - 1) { sums[beat % 4] += at(bass, position); beat++; position += period }
                val order = sums.indices.sortedByDescending { sums[it] }
                val barOffset = if (beat >= 16 && sums[order[0]] > 0f && (sums[order[0]] - sums[order[1]]) / sums[order[0]] >= .18f) order[0] else -1
                chosen = Tempo(fps * 60f / period, period, phase, confidence, barOffset)
            }
        }
        return chosen
    }

    private val RELATIVES = floatArrayOf(1f, 2f, .5f, 1.5f, 2f / 3f, 4f / 3f, .75f)

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
                         val fadeOut: Boolean, val hardEnding: Boolean)

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
        return Shape(loudness, soundStart, soundEnd, rise, fall, fadeOut, hardEnding)
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
