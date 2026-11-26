package com.ketvoice.app.automix

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 7.6 ketmix76: an offline two-deck render of a planned transition through the real [DeckDsp], on synthetic titles
 * whose tempo, first beat and bass are known, and the objective measures of how it sounds:
 *  - beat alignment at the blend (ms between the incoming title's real beats and the outgoing title's),
 *  - loudness continuity (BS.1770 K-weighted short-term loudness, 3 s windows: the largest dip or bulge, in LU,
 *    against the levels before and after the transition),
 *  - low-end clash (time both decks carry their bass, under 120 Hz, within 6 dB of their own full level),
 *  - true peak of the sum (4x oversampled, dBTP) and time over full scale,
 *  - clicks and zipper noise (each deck's automation applied to a pure low tone: whatever comes out above 4 kHz).
 * The two decks are summed in float, as the system mixer does before it clamps.
 */
internal object MixBench {
    const val RATE = 44_100

    /** A stretch of a title: [samples] start at [startMs] of a title of [durationMs]. */
    class Title(val samples: FloatArray, val startMs: Long, val durationMs: Long, val bpm: Float, val firstBeatMs: Double) {
        val beatMs: Double get() = 60_000.0 / bpm
        fun at(ms: Double): Float {
            val x = (ms - startMs) * RATE / 1000.0
            val i = floor(x).toInt()
            if (i < 0 || i + 1 >= samples.size) return 0f
            val f = (x - i).toFloat()
            return samples[i] + (samples[i + 1] - samples[i]) * f
        }
    }

    enum class Ending { Fade, Hard, Plain, Outro }

    /** A groove at [bpm] (kick, hats, pad from [Synth.groove]) with a held bass at [bassHz] and a layer that changes every 8 bars. */
    fun groove(bpm: Float, startMs: Long, seconds: Float, durationMs: Long = 200_000L, firstBeatAt: Float = .25f, bassHz: Double = 55.0,
               ending: Ending = Ending.Plain, introBars: Int = 0, level: Float = .8f, seed: Int = 7, bassPhase: Double = 0.0): Title {
        val s = Synth.groove(RATE, seconds, bpm, firstBeatAt, seed = seed, gain = level)
        val beat = 60f / bpm
        val bar = 4 * beat
        for (i in s.indices) {
            val t = i / RATE.toFloat()
            val phrase = floor((t - firstBeatAt) / (8 * bar)).toInt()
            val intro = t < firstBeatAt + introBars * bar
            if (intro) { s[i] *= .32f; continue }
            s[i] += level * (.16f * sin(2 * PI * bassHz * t + bassPhase).toFloat() + (if (phrase % 2 == 0) .05f * sin(2 * PI * 440.0 * t).toFloat() else 0f))
        }
        shapeEnding(s, ending, firstBeatAt, bar, seconds)
        return Title(s, startMs, durationMs, bpm, startMs + firstBeatAt * 1000.0)
    }

    private fun shapeEnding(s: FloatArray, ending: Ending, firstBeatAt: Float, bar: Float, seconds: Float) {
        when (ending) {
            Ending.Fade -> {
                // An outro: its last 16 bars a little thinner and quieter, then a fade over the last 6 s.
                val outro = ((seconds - 6f - 16 * bar) * RATE).toInt().coerceAtLeast(0)
                for (i in outro until s.size) s[i] *= .6f
                val from = ((seconds - 6f) * RATE).toInt(); for (i in from until s.size) s[i] *= 1f - (i - from).toFloat() / (s.size - from)
            }
            Ending.Hard -> {
                val bars = floor((seconds - 4f - firstBeatAt) / bar).toInt()
                val stop = ((firstBeatAt + bars * bar + .35f) * RATE).toInt()
                for (i in stop until s.size) s[i] *= if (i < stop + 400) 1f - (i - stop) / 400f else 0f
            }
            Ending.Outro -> {
                // A thinner, quieter outro of 16 bars that stops on its last bar, without a fade.
                val outro = ((seconds - 3f - 16 * bar) * RATE).toInt().coerceAtLeast(0)
                for (i in outro until s.size) s[i] *= .5f
                val stop = ((seconds - 3f) * RATE).toInt(); for (i in stop until s.size) s[i] *= if (i < stop + 2_000) 1f - (i - stop) / 2_000f else 0f
            }
            Ending.Plain -> Unit
        }
    }

    fun beatless(startMs: Long, seconds: Float, durationMs: Long = 200_000L): Title {
        val s = Synth.rubato(RATE, seconds)
        val from = ((seconds - 10f) * RATE).toInt(); for (i in from until s.size) s[i] *= 1f - (i - from).toFloat() / (s.size - from)
        return Title(s, startMs, durationMs, 0f, 0.0)
    }

    fun analysis(t: Title): SegmentAnalysis = BeatAnalysis.analyze(Synth.envelope(t.samples, RATE), t.startMs, thorough = true)

    class Render(val from: Double, val a: FloatArray, val b: FloatArray, val rawA: FloatArray) {
        val sum = FloatArray(a.size) { a[it] + b[it] }
        fun frameAt(ms: Double) = ((ms - from) * RATE / 1000.0).toInt().coerceIn(0, a.size - 1)
    }

    /** True peak where both play, the two decks left unpaired so that [MixBus] does not guard their sum. */
    fun unguardedPeakDb(plan: MixPlan, a: Title, b: Title): Double {
        val r = render(plan, a, b, guarded = false)
        return truePeakDb(r.sum.copyOfRange(r.frameAt(plan.outMs - 200.0), r.frameAt(plan.outMs + plan.overlapMs + 200.0) + 16))
    }

    /** Both decks, through their own automation, on the outgoing title's clock, played side by side as on a phone. */
    fun render(plan: MixPlan, a: Title, b: Title, guarded: Boolean = true): Render {
        MixBus.clear()
        val from = (plan.outMs - plan.buildMs - 6_000L).toDouble()
        val to = (plan.outMs + plan.overlapMs + (if (plan.tail) TransitionPlanner.MAX_TAIL_MS else 0L) + 12_000L).toDouble()
        val frames = ((to - from) * RATE / 1000.0).toInt()
        val rawA = FloatArray(frames) { a.at(from + it * 1000.0 / RATE) }
        val bFrom = plan.inMs + (from - plan.outMs) * plan.rate
        val rawB = FloatArray((frames * plan.rate).toInt() + 2) { b.at(bFrom + it * 1000.0 / RATE) }
        val outgoing = DeckAutomation.outgoing(plan).let { if (guarded) it else it.copyWith(pair = null) }
        val incoming = DeckAutomation.incoming(plan).let { if (guarded) it else it.copyWith(pair = null) }
        val deckA = Deck(rawA, from, outgoing); val deckB = Deck(rawB, bFrom, incoming)
        val chunk = 1_024
        while (!deckA.done || !deckB.done) { deckA.step(chunk); deckB.step((chunk * plan.rate).toInt().coerceAtLeast(1)) }
        val ownB = deckB.out
        val outB = FloatArray(frames) { i ->
            val x = i * plan.rate.toDouble(); val k = floor(x).toInt()
            if (k + 1 >= ownB.size) 0f else ownB[k] + (ownB[k + 1] - ownB[k]) * (x - k).toFloat()
        }
        return Render(from, deckA.out, outB, rawA)
    }

    /** [mono] played as a stereo deck from position [fromMs] through [automation]; channel 0 comes back, in -1..1. */
    fun deck(mono: FloatArray, fromMs: Double, automation: DeckAutomation): FloatArray =
        Deck(mono, fromMs, automation).apply { while (!done) step(2_048) }.out

    class Deck(private val mono: FloatArray, private val fromMs: Double, private val automation: DeckAutomation) {
        private val dsp = DeckDsp(RATE, 2)
        val out = FloatArray(mono.size)
        private var at = 0
        val done: Boolean get() = at >= mono.size

        fun step(frames: Int) {
            val n = min(frames, mono.size - at)
            if (n <= 0) return
            val input = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) { val v = (mono[at + i].coerceIn(-1f, 1f) * 32_767f).toInt().toShort(); input.putShort(v); input.putShort(v) }
            input.flip()
            val output = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
            dsp.process(input, output, (fromMs * 1000.0).toLong() + at * 1_000_000L / RATE, automation)
            output.flip()
            for (i in 0 until n) { out[at + i] = output.short / 32_768f; output.short }
            at += n
        }
    }

    // ---- Measures ----

    /** Mean and largest distance (ms) from each real beat of the incoming title in the blend to the outgoing title's grid. */
    fun alignment(plan: MixPlan, a: Title, b: Title): Pair<Double, Double>? {
        if (plan.kind != MixKind.Beatmatch || a.bpm <= 0f || b.bpm <= 0f) return null
        val period = min(a.beatMs, b.beatMs / plan.rate).let { p -> if (a.beatMs / p > 1.5) a.beatMs / 2 else p }
        val errors = ArrayList<Double>()
        var k = 0
        while (true) {
            val inBeat = b.firstBeatMs + k++ * b.beatMs
            if (inBeat > plan.inEndMs) break
            if (inBeat < plan.inMs) continue
            val onOut = plan.outMs + (inBeat - plan.inMs) / plan.rate
            val phase = ((onOut - a.firstBeatMs) % period + period) % period
            errors += min(phase, period - phase)
        }
        if (errors.isEmpty()) return null
        return errors.average() to errors.max()
    }

    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        fun run(x: FloatArray): FloatArray {
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            return FloatArray(x.size) { i ->
                val y = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = x[i].toDouble(); y2 = y1; y1 = y; y.toFloat()
            }
        }
    }

    private fun kWeighted(x: FloatArray): FloatArray {
        var k = tan(PI * 1681.974450955533 / RATE); val q = .7071752369554196
        val vh = Math.pow(10.0, 3.999843853973347 / 20.0); val vb = Math.pow(vh, .4996667741545416)
        var a0 = 1 + k / q + k * k
        val shelf = Biquad((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
        k = tan(PI * 38.13547087602444 / RATE); val q2 = .5003270373238773
        a0 = 1 + k / q2 + k * k
        val high = Biquad(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q2 + k * k) / a0)
        return high.run(shelf.run(x))
    }

    private fun butter(hz: Double, highPass: Boolean): Biquad {
        val w = 2 * PI * hz / RATE; val alpha = sin(w) / (2 * .7071); val c = kotlin.math.cos(w); val a0 = 1 + alpha
        return if (highPass) Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        else Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
    }

    /** Short-term loudness (LUFS, two identical channels) of the 3 s ending at each 100 ms step. */
    internal fun shortTerm(x: FloatArray): DoubleArray {
        val k = kWeighted(x)
        val prefix = DoubleArray(k.size + 1).also { for (i in k.indices) it[i + 1] = it[i] + k[i].toDouble() * k[i] }
        val window = 3 * RATE; val hop = RATE / 10
        val steps = max(0, (k.size - window) / hop + 1)
        return DoubleArray(steps) { s -> val p = (prefix[s * hop + window] - prefix[s * hop]) / window; -.691 + 10 * log10(2 * p + 1e-12) }
    }

    /**
     * The largest dip or bulge (LU) of the summed short-term loudness against a straight line from the outgoing title's own
     * level where the transition starts (its raw sound, so that an outro's own fall is not counted) to the level once the
     * incoming title has settled.
     */
    fun loudnessStep(r: Render, plan: MixPlan): Double {
        val st = shortTerm(r.sum); val raw = shortTerm(r.rawA)
        val hop = RATE / 10; val window = 3 * RATE
        fun startingAt(ms: Double) = (r.frameAt(ms) / hop).coerceIn(0, st.size - 1)
        val start = (plan.outMs - plan.buildMs).toDouble(); val end = (plan.outMs + plan.overlapMs).toDouble() + if (plan.tail) 2_000.0 else 0.0
        val s0 = startingAt(start); val s1 = startingAt(end + 6_000.0)
        val pre = raw[s0]; val post = st[s1]
        var worst = 0.0; var note = ""
        for (s in s0..s1) {
            val line = pre + (post - pre) * (s - s0) / max(1, s1 - s0)
            if (abs(st[s] - line) > worst) { worst = abs(st[s] - line); note = String.format(java.util.Locale.ROOT, "%+.1f at %.1fs", st[s] - line, (s * hop + window / 2) / RATE.toDouble() + r.from / 1000 - plan.outMs / 1000.0) }
        }
        lastLoudness = String.format(java.util.Locale.ROOT, "pre %.1f post %.1f worst %s", pre, post, note)
        return worst
    }

    var lastLoudness = ""

    /** Milliseconds during which both decks carry their bass within 6 dB of their own full level. */
    fun lowClashMs(r: Render, plan: MixPlan): Int {
        val la = butter(120.0, false).let { it.run(butter(120.0, false).run(r.a)) }
        val lb = butter(120.0, false).let { it.run(butter(120.0, false).run(r.b)) }
        val win = RATE / 20
        fun rms(x: FloatArray, from: Int, n: Int): Double { var s = 0.0; for (i in from until min(x.size, from + n)) s += x[i].toDouble() * x[i]; return sqrt(s / n) }
        val start = r.frameAt((plan.outMs - plan.buildMs).toDouble())
        val refA = rms(la, max(0, start - 4 * RATE), 3 * RATE)
        val refB = rms(lb, la.size - 4 * RATE, 3 * RATE)
        var clash = 0
        var i = 0
        while (i + win <= la.size) { if (rms(la, i, win) > refA * .5 && rms(lb, i, win) > refB * .5) clash++; i += win }
        return clash * 50
    }

    /**
     * 9.1.1: the deepest dip of the summed bass (under 120 Hz, 50 ms windows) within a beat of the bass swap, in dB under
     * the quieter of the two bass lines heard alone (two beats before the swap, the outgoing; two after, the incoming).
     * Two bass lines on the same note cancel each other where both are half up.
     */
    fun bassDipDb(r: Render, plan: MixPlan): Double {
        val low = butter(120.0, false).run(butter(120.0, false).run(r.sum))
        val win = RATE / 20
        val beat = plan.beatMs.toDouble()
        fun rms(fromMs: Double): Double {
            val from = r.frameAt(fromMs); var s = 0.0
            for (i in from until min(low.size, from + win)) s += low[i].toDouble() * low[i]
            return sqrt(s / win)
        }
        fun mean(fromMs: Double, toMs: Double): Double {
            var at = fromMs; var s = 0.0; var n = 0
            while (at + 50.0 <= toMs) { s += rms(at); n++; at += 50.0 }
            return s / max(1, n)
        }
        val swap = plan.swapMs.toDouble()
        val ref = min(mean(swap - 3 * beat, swap - 1.5 * beat), mean(swap + 1.5 * beat, swap + 3 * beat))
        var worst = Double.MAX_VALUE
        var at = swap - beat
        while (at + 50.0 <= swap + beat) { worst = min(worst, rms(at)); at += 10.0 }
        return 20 * log10(ref / max(worst, 1e-9))
    }

    /** 4x oversampled peak of the sum, dBTP. */
    fun truePeakDb(x: FloatArray): Double {
        val taps = 8
        var peak = 0.0
        for (i in taps until x.size - taps) {
            peak = max(peak, abs(x[i].toDouble()))
            for (p in 1..3) {
                val f = p / 4.0; var v = 0.0
                for (t in -taps + 1..taps) {
                    val d = t - f; val sinc = sin(PI * d) / (PI * d); val win = .5 + .5 * kotlin.math.cos(PI * d / taps)
                    v += x[i + t] * sinc * win
                }
                peak = max(peak, abs(v))
            }
        }
        return 20 * log10(peak + 1e-12)
    }

    fun overMs(x: FloatArray): Double = x.count { abs(it) >= 1f } * 1000.0 / RATE

    /** The loudest 2 ms of what is left above 4 kHz once a low tone went through [automation] (dBFS): clicks and zipper noise. */
    fun clickDb(automation: DeckAutomation, fromMs: Double, toMs: Double): Double {
        val quiet = DeckAutomation(automation.gain, automation.highPassHz, automation.lowPassHz, automation.dry, automation.echo, automation.reverb,
            automation.echoDelayUs, automation.echoFeedback, automation.resonance, automation.vocalCut, Curve.flat(0f), Curve.flat(0f),
            automation.vocalSolo, automation.midDb)
        val frames = ((toMs - fromMs) * RATE / 1000.0).toInt()
        val tone = FloatArray(frames) { val t = fromMs / 1000.0 + it.toDouble() / RATE; (.45 * sin(2 * PI * 110.0 * t) + .2 * sin(2 * PI * 330.0 * t)).toFloat() }
        val hf = butter(4_000.0, true).run(butter(4_000.0, true).run(butter(4_000.0, true).run(deck(tone, fromMs, quiet))))
        val win = RATE / 500
        var worst = 1e-12
        var i = RATE / 10
        while (i + win <= hf.size) { var s = 0.0; for (j in i until i + win) s += hf[j].toDouble() * hf[j]; worst = max(worst, sqrt(s / win)); i += win / 2 }
        return 20 * log10(worst)
    }

    class Score(val name: String, val plan: MixPlan, val align: Pair<Double, Double>?, val loudness: Double, val clashMs: Int,
                val peakDb: Double, val overMs: Double, val clickDb: Double, val loudnessNote: String = "") {
        override fun toString(): String = String.format(java.util.Locale.ROOT, "| %-22s | %-9s | %-11s | %5.2f | %5d | %6.2f | %5.1f | %6.1f | %s", name,
            plan.kind.name + (if (plan.buildMs == 0L && plan.kind == MixKind.Rise) "/cut" else ""),
            align?.let { String.format(java.util.Locale.ROOT, "%.1f / %.1f", it.first, it.second) } ?: "—", loudness, clashMs, peakDb, overMs, clickDb, plan.reason + " ov=" + plan.overlapMs + " " + loudnessNote)
    }

    fun score(name: String, plan: MixPlan, a: Title, b: Title): Score {
        val r = render(plan, a, b)
        val start = (plan.outMs - plan.buildMs - 1_000L).toDouble()
        val end = (plan.outMs + plan.overlapMs + 9_000L).toDouble()
        val inStart = plan.inMs - 1_000.0
        val inEnd = plan.inEndMs + 9_000.0
        val click = max(clickDb(DeckAutomation.outgoing(plan), start, end + if (plan.tail) TransitionPlanner.MAX_TAIL_MS else 0L),
            clickDb(DeckAutomation.incoming(plan), max(0.0, inStart), inEnd))
        return Score(name, plan, alignment(plan, a, b), loudnessStep(r, plan), lowClashMs(r, plan), truePeakDb(r.sum.copyOfRange(r.frameAt(plan.outMs - 200.0), r.frameAt(plan.outMs + plan.overlapMs + 200.0) + 16)),
            overMs(r.sum), click, lastLoudness)
    }
}
