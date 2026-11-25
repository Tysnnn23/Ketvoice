package com.ketvoice.desktop.ui

import com.ketvoice.app.automix.DeckAutomation
import com.ketvoice.app.automix.MixContext
import com.ketvoice.app.automix.MixFacts
import com.ketvoice.app.automix.MixPlan
import com.ketvoice.app.automix.MixSettings
import com.ketvoice.app.automix.MixWords
import com.ketvoice.app.automix.SegmentAnalysis
import com.ketvoice.app.automix.TransitionPlanner
import com.ketvoice.app.automix.Wave
import com.ketvoice.app.automix.WaveBuilder
import com.ketvoice.app.data.Track
import com.ketvoice.desktop.ketmix.LiveMix
import kotlin.math.exp
import kotlin.math.sin

/** A transition in time between two titles at 124 and 125 BPM, as the live mix view gets it, with drum-like waveforms. */
internal object LiveMixFixtures {
    private const val DURATION = 200_000L
    val from = Track("live-a", "Nuit claire", "Nora Vale", "", durationMs = DURATION)
    val to = Track("live-b", "Sous la jetée", "Nora Vale", "", durationMs = DURATION)

    val outro = SegmentAnalysis(startMs = 150_000L, lengthMs = 50_000L, bpm = 124f, beatAtMs = 150_000.0, confidence = .9f, barOffset = 0,
        loudnessDb = -9f, soundEndMs = 199_000L, riseMs = 150_000L, fallMs = 192_000L, key = 9, keyStrength = .8f, phraseAtMs = 150_000.0, activity = .5f)
    val intro = SegmentAnalysis(startMs = 0L, lengthMs = 45_000L, bpm = 125f, beatAtMs = 0.0, confidence = .9f, barOffset = 0, loudnessDb = -9f,
        soundStartMs = 0L, riseMs = 8_000L, fallMs = 45_000L, key = 9, keyStrength = .8f, phraseAtMs = 0.0, activity = .5f)

    val plan: MixPlan by lazy { TransitionPlanner.plan(MixFacts(DURATION, outro), MixFacts(DURATION, intro), MixContext(), MixSettings()) }

    /** [share] of the way through the crossing (negative: that many seconds before it). */
    fun frame(share: Float = .62f): LiveMix.Frame {
        val p = plan
        val outPos = p.outMs + (p.overlapMs * share).toLong()
        val inPos = p.inMs + ((outPos - p.outMs).coerceAtLeast(0) * p.rate).toLong()
        val into = LiveMix.mixProgress(p, outPos)
        val outGain = DeckAutomation.outgoing(p).gain
        val inGain = DeckAutomation.incoming(p).gain
        return LiveMix.Frame(
            LiveMix.deck(from.title, outPos, outro, wave(outPos, 124f, 1), 1f - into, .2f) { ms -> outGain.at(ms * 1000L) },
            LiveMix.deck(to.title, inPos, intro, wave(inPos, 125f, 2), into, .9f) { ms -> inGain.at(ms * 1000L) },
            into, true, MixWords.label(p), p, (p.outMs - outPos).coerceAtLeast(0L))
    }

    /** A kick on every beat over a softer bed, around [atMs]. */
    private fun wave(atMs: Long, bpm: Float, seed: Int): Wave {
        val rate = 8_000
        val start = (atMs - LiveMix.BEFORE_MS - 1_000L).coerceAtLeast(0L)
        val frames = rate * 14
        val beat = 60_000.0 / bpm
        val samples = ShortArray(frames) { i ->
            val ms = start + i * 1000.0 / rate
            val inBeat = (ms % beat) / beat
            val kick = exp(-inBeat * 9.0) * (if (((ms / beat).toLong() % 4L) == 0L) 1.0 else .7)
            val bed = .22 + .12 * sin(ms / (900.0 + seed * 170.0))
            val v = (kick * .75 + bed * sin(i * (.31 + seed * .07))) * 30_000
            v.coerceIn(-32_000.0, 32_000.0).toInt().toShort()
        }
        return WaveBuilder(rate, 1, start).apply { accept(samples) }.build()
    }
}
