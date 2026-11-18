package com.ketvoice.app.automix

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * From the beats and downbeats of Beat This! to what Ketmix keeps of a stretch ([SegmentAnalysis]), and the
 * rule that decides between that and Ketmix's own analysis: the model's reading is used when it is sure,
 * otherwise the signal analysis stays as it was, so a title is never worse off for the model.
 */
internal object BeatMapping {
    /** The model's reading must be at least this regular to replace the signal analysis. */
    const val MIN_CONFIDENCE = .6f
    private const val MIN_BEATS = 8
    /** A beat within this part of a beat period of the regular grid is on the grid. */
    private const val ON_GRID = .12

    /** A regular grid fitted to beats: time of the beat at the middle of the stretch and the period, in seconds. */
    class Grid(val anchor: Double, val period: Double, val regularity: Double)

    /** Beats of one stretch ([originMs]: where in the title the first frame of the spectrogram is) as a [SegmentAnalysis] built on [base]; null when there is no steady beat. */
    fun segment(times: BeatPost.Times, base: SegmentAnalysis, originMs: Double): SegmentAnalysis? {
        val grid = fit(times.beats) ?: return null
        val folded = fold(grid, times.downbeats, base)
        val bpm = (60.0 / folded.period).toFloat()
        if (bpm !in BeatAnalysis.MIN_BPM..BeatAnalysis.MAX_BPM) return null
        val bar = barOffset(folded, times.downbeats)
        return base.copy(
            bpm = bpm, beatAtMs = originMs + folded.anchor * 1000.0, confidence = folded.regularity.toFloat(),
            barOffset = bar, phraseAtMs = -1.0, analyser = SegmentAnalysis.BEAT_THIS)
    }

    /** Median inter-beat interval, then a least-squares line through the beats that agree with it. */
    fun fit(beats: DoubleArray): Grid? {
        if (beats.size < MIN_BEATS) return null
        val gaps = DoubleArray(beats.size - 1) { beats[it + 1] - beats[it] }.sorted()
        val period = gaps[gaps.size / 2]
        if (period < .25 || period > 1.2) return null
        val index = IntArray(beats.size) { ((beats[it] - beats[0]) / period).roundToInt() }
        val near = beats.indices.filter { abs(beats[it] - (beats[0] + index[it] * period)) < period * .35 }
        if (near.size < MIN_BEATS) return null
        val (a, b) = line(near.map { index[it].toDouble() }, near.map { beats[it] })
        val inliers = beats.indices.count { abs(beats[it] - (a + b * index[it])) < ON_GRID * b }
        val regularity = (inliers.toDouble() / beats.size) * min(1.0, beats.size / 12.0)
        val middle = index[beats.size / 2]
        return Grid(a + b * middle, b, regularity)
    }

    /** Intercept and slope of the least-squares line y = a + b x. */
    private fun line(x: List<Double>, y: List<Double>): Pair<Double, Double> {
        val mx = x.average(); val my = y.average()
        var sxx = 0.0; var sxy = 0.0
        for (i in x.indices) { sxx += (x[i] - mx) * (x[i] - mx); sxy += (x[i] - mx) * (y[i] - my) }
        val b = if (sxx > 0) sxy / sxx else (y.last() - y.first()) / max(1, y.size - 1)
        return (my - b * mx) to b
    }

    /**
     * The model may count beats at half or twice the tempo the signal analysis found (or one outside the
     * range Ketmix mixes in): the grid is brought to the same octave, on a downbeat when beats are dropped.
     */
    internal fun fold(grid: Grid, downbeats: DoubleArray, base: SegmentAnalysis): Grid {
        var period = grid.period
        var anchor = grid.anchor
        val trusted = base.hasBeat && base.confidence >= .55f
        fun bpm() = 60.0 / period
        fun tooFast() = if (trusted) bpm() / base.bpm > 1.5 else bpm() > BeatAnalysis.MAX_BPM
        fun tooSlow() = if (trusted) bpm() / base.bpm < .67 else bpm() < BeatAnalysis.MIN_BPM
        var steps = 0
        while (tooFast() && steps++ < 3) {
            period *= 2
            downbeats.minByOrNull { abs(it - anchor) }?.let { anchor = it }
        }
        while (tooSlow() && steps++ < 3) period /= 2
        return Grid(anchor, period, grid.regularity)
    }

    /** Beats from the grid's anchor to a first beat of a bar (0..3); -1 when bars are not four beats or are not steady. */
    internal fun barOffset(grid: Grid, downbeats: DoubleArray): Int {
        if (downbeats.size < 2) return -1
        val index = downbeats.map { ((it - grid.anchor) / grid.period).roundToInt() }
        val steps = index.zipWithNext { a, b -> b - a }.sorted()
        if (steps[steps.size / 2] != 4) return -1
        val votes = IntArray(4)
        for (i in index) votes[((i % 4) + 4) % 4]++
        val best = votes.indices.maxByOrNull { votes[it] } ?: return -1
        return if (votes[best] >= .7 * index.size) best else -1
    }

    /**
     * Ketmix's own bars, moved onto the model's beats: used when the model found its beats but no steady
     * bars, and the two agree on where the beats are.
     */
    private fun bridge(dsp: SegmentAnalysis, model: SegmentAnalysis): Int {
        if (!dsp.hasBeat || dsp.barOffset < 0) return -1
        val start = dsp.beatAtMs + dsp.barOffset * dsp.beatMs
        val beats = (start - model.beatAtMs) / model.beatMs
        return if (abs(beats - beats.roundToInt()) <= .15) ((beats.roundToInt() % 4) + 4) % 4 else -1
    }

    /** The bar of the phrase grid nearest to the signal analysis' own phrase start, on the model's bars. */
    private fun phrase(dsp: SegmentAnalysis, model: SegmentAnalysis): Double {
        if (dsp.phraseAtMs < 0 || model.barOffset < 0) return -1.0
        val bar = model.beatMs * 4.0
        val first = model.beatAtMs + model.barOffset * model.beatMs
        return first + ((dsp.phraseAtMs - first) / bar).roundToInt() * bar
    }

    /** The reading to keep: the model's when it is sure of itself and does not contradict a sure signal analysis, otherwise [dsp]. */
    fun merge(dsp: SegmentAnalysis, model: SegmentAnalysis?): SegmentAnalysis {
        val tried = dsp.copy(analyser = SegmentAnalysis.BEAT_TRIED)
        if (model == null || model.confidence < MIN_CONFIDENCE) return tried
        val agree = !dsp.hasBeat || abs(model.bpm / dsp.bpm - 1f) <= .03f
        if (!agree && dsp.confidence >= .85f && model.confidence < .8f) return tried
        val bar = if (model.barOffset >= 0) model.barOffset else bridge(dsp, model)
        val adopted = model.copy(barOffset = bar)
        return dsp.copy(
            bpm = adopted.bpm, beatAtMs = adopted.beatAtMs, confidence = adopted.confidence, barOffset = bar,
            phraseAtMs = phrase(dsp, adopted), analyser = SegmentAnalysis.BEAT_THIS)
    }
}
