package com.ketvoice.app.automix

import org.junit.Assert.*
import org.junit.Test

/**
 * Ketmix 7.1 on made-up analyses: the cut budget ([CutBudget]), false endings and hidden titles ([Endings]),
 * no stretch under a voice, steady grids only ([BlendFit.steady]) and radio changes for pairs far apart ([PairFit]).
 */
class KetmixBudgetTest {
    private val duration = 200_000L
    private fun outro(bpm: Float = 120f, from: Long = 155_000, fall: Long = 185_000, end: Long = 199_000, fade: Boolean = false,
                      hard: Boolean = false, confidence: Float = .9f, loudness: Float = -14f, gapFrom: Long = -1, gapUntil: Long = -1,
                      activity: Float = 0f) =
        SegmentAnalysis(from, duration - from, bpm, from.toDouble(), confidence, -1, loudness, from, end, from,
            if (hard) end else fall, fade, hard, gapFromMs = gapFrom, gapUntilMs = gapUntil, activity = activity)
    private fun intro(bpm: Float = 120f, confidence: Float = .9f, loudness: Float = -14f, activity: Float = 0f) =
        SegmentAnalysis(0, 40_000, bpm, 500.0, confidence, -1, loudness, 300, 40_000, 8_000, 40_000, activity = activity)
    private fun plan(a: SegmentAnalysis, b: SegmentAnalysis, intensity: Int = 1, sungUntil: Long? = null, sungFrom: Long? = null,
                     style: MixStyle = MixStyle.Auto) =
        TransitionPlanner.plan(MixFacts(duration, a, vocalEndMs = sungUntil), MixFacts(duration, b, vocalStartMs = sungFrom),
            MixContext(), MixSettings(intensity, style))

    @Test fun theDjSettingLeavesOutEightBarsOfAnEndingAtMost() {
        // A long reading (75 s) whose singing ends early: the DJ setting used to leave out up to 45 s of it.
        val long = outro(from = 120_000)
        val dj = plan(long, intro(), intensity = 2, sungUntil = 125_000)
        assertEquals(MixKind.Beatmatch, dj.kind)
        assertTrue("lost ${CutBudget.lostMs(dj, long)} ms", CutBudget.lostMs(dj, long) <= 16_000L)
        assertEquals(16_000L, CutBudget.skippableMs(long, MixSettings(2)))
        assertEquals("a slow title counts its bars on its own pulse", 8 * 4 * 750L, CutBudget.skippableMs(outro(80f), MixSettings(2)))
    }

    @Test fun theIncomingTitleNeverStartsPastItsFirstWords() {
        val a = outro(); val b = intro()
        val p = MixPlan(MixKind.Filter, outMs = 190_000, inMs = 20_000, overlapMs = 8_000)
        assertEquals("first words skipped", CutBudget.broken(p, a, b, MixFacts(duration, b, vocalStartMs = 10_000), 1f))
        assertNull("an instrumental intro of 10 bars may be skipped", CutBudget.broken(p, a, b, MixFacts(duration, b), 1f))
        assertEquals("intro skipped", CutBudget.broken(p.copy(inMs = 33_000), a, b, MixFacts(duration, b), 1f))
        assertEquals(MixKind.None, CutBudget.keep(p, a, b, MixFacts(duration, b, vocalStartMs = 10_000), 1f).kind)
        // Ketmix's own plans keep to it.
        for (sungFrom in listOf(300L, 2_000L, 9_000L)) for (bpm in listOf(120f, 123f, 150f)) {
            val q = plan(outro(), intro(bpm), sungFrom = sungFrom, sungUntil = 175_000)
            assertTrue("$sungFrom $bpm", q.mixes)
            assertTrue("$sungFrom $bpm: in at ${q.inMs}", q.inMs <= sungFrom + 150)
        }
    }

    @Test fun noMixStartsInTheSilenceOfAFalseEnding() {
        // The band stops at 180 s, 6 s of silence, then plays on to 199 s; read across the silence, it looked like a fade.
        val a = outro(fall = 170_000, fade = true, gapFrom = 180_000, gapUntil = 186_000)
        val p = plan(a, intro())
        assertTrue(p.mixes)
        assertTrue("after the silence (${p.kind} at ${p.outMs})", p.outMs >= 186_000)
        assertTrue(p.reason, p.reason.endsWith("after a silence"))
        // A short piece after the silence (a hidden title's last seconds, a last chord) is let end whole.
        val short = plan(outro(fall = 170_000, gapFrom = 180_000, gapUntil = 194_000), intro())
        assertTrue("${short.kind} at ${short.outMs}", short.kind == MixKind.Trim || short.kind == MixKind.Echo)
        assertTrue(short.outMs >= 194_000)
        // Without a silence, the same reading mixes inside the fade as before.
        assertTrue(plan(outro(fall = 170_000, fade = true), intro()).outMs < 186_000)
    }

    @Test fun silencesAreFoundAndKept() {
        val fps = 10f
        val db = FloatArray(200) { -20f }
        for (i in 120 until 150) db[i] = -80f // 3 s of silence
        for (i in 160 until 170) db[i] = -80f // 1 s: a break, not a silence
        assertEquals(120..150, BeatAnalysis.lastGap(db, 0, 199, -60f, fps))
        assertNull(BeatAnalysis.lastGap(db, 0, 119, -60f, fps))
        val a = outro(gapFrom = 180_000, gapUntil = 186_000, activity = 3.5f)
        val back = AnalysisStore.decode(AnalysisStore.encode(a))!!
        assertEquals(180_000L, back.gapFromMs); assertEquals(186_000L, back.gapUntilMs); assertEquals(3.5f, back.activity, 1e-6f)
        val old = AnalysisStore.encode(outro()).apply { remove("gf"); remove("gu"); remove("ac") }
        assertEquals(-1L, AnalysisStore.decode(old)!!.gapUntilMs)
    }

    @Test fun aStretchedTitleIsNeverBlendedUnderAVoice() {
        // 4 % off its tempo: the blend waits for the last sung words instead of starting under them.
        val far = plan(outro(), intro(125f), sungUntil = 170_000)
        assertEquals(MixKind.Beatmatch, far.kind)
        assertTrue("from the end of the singing (${far.outMs})", far.outMs >= 170_300)
        assertFalse(CutBudget.stretchedUnderVoice(far, MixFacts(duration), 1f))
        // 2 % is not heard: it may still start under them.
        val near = plan(outro(), intro(122.4f), sungUntil = 170_000)
        assertEquals(MixKind.Beatmatch, near.kind)
        assertTrue(near.outMs < 170_000)
        // An incoming voice is heard only once the blend is over.
        val early = plan(outro(fall = 181_000), intro(126.3f), sungFrom = 5_000)
        if (early.kind == MixKind.Beatmatch) assertTrue("in until ${early.inEndMs}", early.inEndMs <= 5_000)
    }

    @Test fun anUnsteadyGridIsNotPlayedInTime() {
        assertEquals(MixKind.Beatmatch, plan(outro(), intro()).kind)
        val shaky = plan(outro(confidence = .5f), intro(confidence = .5f))
        assertTrue(shaky.mixes)
        assertNotEquals(MixKind.Beatmatch, shaky.kind)
    }

    @Test fun titlesFarApartChangeOverAsOnTheRadio() {
        // A quiet ballad without a steady beat into a loud, busy title.
        val ballad = outro(bpm = 0f, confidence = 0f, loudness = -22f, activity = 2f)
        val loud = intro(loudness = -10f, activity = 8f)
        assertTrue(PairFit.distance(ballad, loud) >= PairFit.RADIO)
        val radio = plan(ballad, loud)
        assertEquals(MixKind.Crossfade, radio.kind)
        assertEquals(PairFit.RADIO_FADE_MS, radio.overlapMs)
        assertEquals("far apart", radio.reason)
        // Alike, or a style the listener chose: blended as before.
        assertTrue(PairFit.distance(outro(activity = 4f), intro(activity = 5f)) < 1f)
        assertNotEquals("far apart", plan(ballad, loud, style = MixStyle.Filters).reason)
    }
}
