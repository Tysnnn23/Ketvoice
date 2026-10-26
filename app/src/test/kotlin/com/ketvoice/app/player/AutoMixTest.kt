package com.ketvoice.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AutoMixTest {
    private fun analysis(id: String, bpm: Float = 0f, confidence: Float = 0f,
                         rms: Float = .18f, vocalRisk: Float = 0f, quiet: Long = 0L) =
        AutoMixAnalysis(id, AutoMixAnalyzer.HEAD, 3, 180_000, 0L, bpm, confidence,
            rms = rms, vocalRisk = vocalRisk, quietTailMs = quiet)

    @Test fun automaticFallsBackAndNeverExceedsLimit() {
        for (level in 0..3) {
            val plan = AutoMixPlanner.plan(level, 6_000L, true, null, null, 15_000L)
            assertTrue(plan.fadeMs in 700L..6_000L)
            assertFalse(plan.beatSynced)
            assertTrue(plan.reason.isNotBlank())
        }
    }

    @Test fun customMaximumAlwaysCapsTempoMix() {
        val outgoing = analysis("a", 120f, .8f, quiet = 2_000L)
        val incoming = analysis("b", 121f, .9f)
        val plan = AutoMixPlanner.plan(3, 1_000L, false, outgoing, incoming, 20_000L)
        assertEquals(1_000L, plan.fadeMs)
        assertTrue(plan.beatSynced)
        assertTrue(plan.incomingStartMs < incoming.beatMs)
    }

    @Test fun highVocalRiskShortensAutomaticOverlap() {
        val clean = AutoMixPlanner.plan(3, 12_000L, true,
            analysis("a"), analysis("b", vocalRisk = .1f), 20_000L)
        val voice = AutoMixPlanner.plan(3, 12_000L, true,
            analysis("a"), analysis("b", vocalRisk = .85f), 20_000L)
        assertTrue(voice.fadeMs < clean.fadeMs)
    }

    @Test fun quietSectionBoundaryCanMoveMixStartWithoutExtendingTheFade() {
        val rate = 1_000
        val audio = FloatArray(rate * 12) { if (it < rate * 7) .18f else .012f }
        val start = AutoMixDsp.structureStartMs(audio, rate, 168_000L, 180_000L)
        assertEquals(175_000L, start)
        val outgoing = analysis("a").copy(durationMs = 180_000L, structureStartMs = start)
        val plan = AutoMixPlanner.plan(2, 8_000L, true, outgoing, analysis("b"), 18_000L)
        assertEquals(5_000L, plan.leadMs)
        assertTrue(plan.fadeMs <= plan.leadMs)
        assertTrue(plan.reason.contains("structure"))
    }

    @Test fun equalPowerCrossfadeIsContinuous() {
        val start = AutoMixPlanner.fadeGain(0f, AutoMixCurve.EQUAL_POWER)
        val middle = AutoMixPlanner.fadeGain(.5f, AutoMixCurve.EQUAL_POWER)
        val end = AutoMixPlanner.fadeGain(1f, AutoMixCurve.EQUAL_POWER)
        assertEquals(1f, start.first, .001f)
        assertEquals(0f, start.second, .001f)
        assertTrue(middle.first in .69f.. .72f)
        assertTrue(middle.second in .69f.. .72f)
        assertEquals(0f, end.first, .001f)
        assertEquals(1f, end.second, .001f)
    }

    @Test fun bpmAndOutroAreMeasuredFromPcm() {
        val rate = 4_000
        val bpm = 120f
        val samples = FloatArray(rate * 12)
        for (index in samples.indices) {
            val phase = index % (rate / 2)
            if (index < rate * 11 && phase < 110) {
                samples[index] = (.9 * sin(2 * PI * 90 * index / rate) *
                    (1.0 - phase / 110.0)).toFloat()
            }
        }
        val onset = AutoMixDsp.onsetEnvelope(samples, 50)
        val detected = AutoMixDsp.tempo(onset, rate / 50f)
        assertTrue("${detected.bpm}", detected.bpm in 115f..125f)
        assertTrue(AutoMixDsp.quietTailMs(samples, rate) >= 900L)
        assertTrue(AutoMixDsp.peak(samples) > .6f)
        assertTrue(AutoMixDsp.rms(samples) > 0f)
    }

    @Test fun silenceGivesNoTempoOrGainExplosion() {
        val samples = FloatArray(20_000)
        val beat = AutoMixDsp.tempo(AutoMixDsp.onsetEnvelope(samples, 200), 20f)
        assertEquals(0f, beat.bpm)
        assertEquals(0f, AutoMixDsp.rms(samples))
        assertEquals(0f, AutoMixDsp.vocalRisk(samples, 4_000))
    }
}
