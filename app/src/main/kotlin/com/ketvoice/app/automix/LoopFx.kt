package com.ketvoice.app.automix

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * The loop of a « boucle » blend (6.9), in positions of the outgoing title (µs): the bar from [fromUs], [lengthUs]
 * long, is played again and again from its end until [untilUs], then the title's own sound takes over again.
 */
internal data class BarLoop(val fromUs: Long, val lengthUs: Long, val untilUs: Long)

/** The loop a plan asks of its outgoing deck, if any. */
internal fun MixPlan.barLoop(): BarLoop? =
    if (loopUs <= 0L || loopFromUs < 0L) null else BarLoop(loopFromUs, loopUs, (outMs + overlapMs) * 1000L)

/**
 * Plays a [BarLoop] on a deck's 16-bit PCM, sample-accurately: the bar is recorded as it plays the first time,
 * each repeat starts on the frame nearest its exact place on the beat grid (so that repeats never drift from
 * the grid the incoming title follows), and every seam, as well as the release, is a 4 ms equal-power
 * crossfade with the sound that would have come next, so that no click is heard.
 *
 * A loop whose bar was not heard whole and in one piece (a seek into it) is not played: the title goes on as it is.
 */
internal class LoopFx(private val rate: Int, private val channels: Int) {
    private var spec: BarLoop? = null
    private var fromFrame = 0L
    private var length = 0.0
    private var frames = 0
    private var untilFrame = 0L
    private val fade = maxOf(1, rate * FADE_MS / 1000)
    private var record = ShortArray(0)
    private var recorded = 0
    private var nextFrame = Long.MIN_VALUE
    private var out: ByteBuffer? = null

    /** After a jump in the title: what was recorded belongs elsewhere. */
    fun reset() { recorded = 0; nextFrame = Long.MIN_VALUE }

    private fun configure(loop: BarLoop) {
        spec = loop
        fromFrame = Math.round(loop.fromUs * rate / 1_000_000.0)
        length = loop.lengthUs * rate / 1_000_000.0
        frames = kotlin.math.ceil(length).toInt()
        untilFrame = Math.round(loop.untilUs * rate / 1_000_000.0)
        record = ShortArray((frames + fade) * channels)
        reset()
    }

    /**
     * [input] holds frames from [startUs] in the title. Returns [input] itself when nothing changes, else a buffer
     * of its own with the loop in place ([input] is then read to its end).
     */
    fun process(input: ByteBuffer, startUs: Long, loop: BarLoop?): ByteBuffer {
        if (loop == null || loop.lengthUs <= 0L) { if (spec != null) { spec = null; reset() }; return input }
        if (loop != spec) configure(loop)
        val count = input.remaining() / (2 * channels)
        val announced = Math.round(startUs * rate / 1_000_000.0)
        // Frames are counted from the first buffer on: positions in µs lose a fraction of a frame per buffer.
        val first = if (nextFrame != Long.MIN_VALUE && abs(announced - nextFrame) <= rate / 500) nextFrame else announced
        if (nextFrame != Long.MIN_VALUE && first != nextFrame) recorded = 0
        nextFrame = first + count
        if (first + count <= fromFrame || first >= untilFrame + fade) return input
        val src = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val base = src.position()
        val bytes = count * 2 * channels
        val target = out?.takeIf { it.capacity() >= bytes } ?: ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN).also { out = it }
        target.clear()
        for (i in 0 until count) {
            val f = first + i
            val k = f - fromFrame
            if (k >= 0 && k < frames + fade && k == recorded.toLong()) {
                for (c in 0 until channels) record[recorded * channels + c] = src.getShort(base + 2 * (i * channels + c))
                recorded++
            }
            for (c in 0 until channels) target.putShort(sample(f, c, src.getShort(base + 2 * (i * channels + c))))
        }
        target.flip()
        input.position(input.limit())
        return target
    }

    private fun repeatStart(j: Long): Long = fromFrame + (j * length).roundToLong()

    private fun sample(f: Long, c: Int, real: Short): Short {
        if (f < repeatStart(1) || f >= untilFrame + fade) return real
        var j = floor((f - fromFrame) / length).toLong().coerceAtLeast(1L)
        if (f < repeatStart(j)) j--
        if (f >= repeatStart(j + 1)) j++
        val start = repeatStart(j)
        val k = (f - start).toInt()
        val previous = (start - repeatStart(j - 1)).toInt()
        // The bar must have been heard whole and in one piece: recorded to its end, or right now (the first seam).
        val complete = recorded >= frames + fade || recorded.toLong() == f - fromFrame + 1
        if (!complete || k >= recorded || (k < fade && previous + k >= recorded)) return real
        var v = record[k * channels + c].toFloat()
        if (k < fade) {
            // The seam: the bar's start comes in while what followed the previous repeat goes out.
            val w = (k + .5f) / fade
            v = v * sin(PI / 2 * w).toFloat() + record[(previous + k) * channels + c] * cos(PI / 2 * w).toFloat()
        }
        if (f >= untilFrame) {
            val w = (f - untilFrame + .5f) / fade
            v = v * cos(PI / 2 * w).toFloat() + real * sin(PI / 2 * w).toFloat()
        }
        return v.coerceIn(-32768f, 32767f).toInt().toShort()
    }

    private companion object { const val FADE_MS = 4 }
}

/**
 * « Boucle » (6.9, a Ketmix exclusive among music apps): a title that fades out is not blended over its own
 * fade. Its last clean bars before the fade — after its last sung line, the voice known from lyrics or
 * separation, never guessed — are looped on the beat grid while the next title comes in, in time with them,
 * and the bass changes hands over the loop. Only on a beat read with confidence and bars told apart, never
 * twice running, and never with the discreet setting (the fade it replaces is a part of the title left out).
 */
internal object LoopBlend {
    /** The beat must be read at least this surely: a loop off its grid stutters. */
    const val CONFIDENCE = .7f
    /** Shorter blends are not worth a loop. */
    private const val MIN_BEATS = 16
    private const val END_MARGIN_MS = 600L

    private fun pulse(a: SegmentAnalysis): Double {
        var beat = a.beatMs.toDouble()
        while (60_000.0 / beat >= 160.0) beat *= 2.0
        while (60_000.0 / beat < 80.0) beat /= 2.0
        return beat
    }

    /** The loop's start and length (ms), or null when this ending has no clean bar to loop. */
    fun bar(a: SegmentAnalysis, sungUntilMs: Long?, beats: Int): Pair<Double, Double>? {
        if (sungUntilMs == null || !a.fadeOut || a.barOffset < 0 || a.confidence < CONFIDENCE || a.bpm <= 0f) return null
        val length = beats * pulse(a)
        val barMs = 4.0 * a.beatMs
        val firstBar = a.beatAtMs + a.barOffset * a.beatMs.toDouble()
        val earliest = maxOf(sungUntilMs.toDouble(), a.startMs.toDouble())
        val latest = a.fallMs - length
        if (latest < earliest) return null
        // The last bar that leaves the whole loop before the fade; a phrase start in that window is better still.
        val phrase = a.phraseStarts(earliest, latest).lastOrNull()
        val start = phrase ?: (firstBar + floor((latest - firstBar) / barMs + 1e-9) * barMs)
        return if (start < earliest - 1e-6) null else start to length
    }

    fun plan(a: SegmentAnalysis, b: SegmentAnalysis, out: MixFacts, settings: MixSettings, rate: Float, gain: Float, end: Long,
             sungUntilMs: Long?, clash: Boolean, variant: Int, counts: IntArray): MixPlan? {
        if (settings.skippableOutroMs <= 0L || clash || !b.hasBeat) return null
        for (beats in intArrayOf(8, 4)) {
            val (start, length) = bar(a, sungUntilMs, beats) ?: continue
            // What the loop stands in for (the fade) is left out: counted against what this setting may skip.
            if (end - (start + length) > settings.skippableOutroMs) continue
            val beat = pulse(a)
            val room = out.durationMs - END_MARGIN_MS - start
            val count = counts.firstOrNull { it >= MIN_BEATS && it * beat <= room } ?: continue
            val overlap = (count * beat).roundToLong()
            val firstBeat = b.beatAtOrAfter(b.soundStartMs - 20.0)
            val firstBar = if (b.barOffset >= 0) b.barAtOrAfter(b.soundStartMs - 20.0) else firstBeat
            val inMs = (if (firstBar - firstBeat <= b.beatMs * 1.05) firstBar else firstBeat).roundToLong().coerceAtLeast(0L)
            val outMs = start.roundToLong()
            return MixPlan(MixKind.Beatmatch, outMs = outMs, inMs = inMs, overlapMs = overlap, rate = rate,
                swapMs = BlendFit.swapBeat(a, outMs + overlap / 2.0).roundToLong(), gain = gain, outGain = BlendFit.outGain(a, b),
                beatMs = beat.toFloat(), reason = "loop $beats beats", variant = variant,
                onPhrase = a.phraseStarts(start - 1.0, start + 1.0).isNotEmpty(),
                loopFromUs = (start * 1000.0).roundToLong(), loopUs = (length * 1000.0).roundToLong())
        }
        return null
    }
}
