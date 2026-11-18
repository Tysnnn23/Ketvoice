package com.ketvoice.app.automix

import org.junit.Assert.*
import org.junit.Test

/** The details of a transition set in the DJ editor (6.9): kept with the pair, played by the decks, and nothing changed by default. */
class MixShapeTest {
    private val duration = 200_000L
    private fun outro(bpm: Float = 120f, fade: Boolean = false) =
        SegmentAnalysis(155_000, 45_000, bpm, 155_000.0, .9f, 0, -14f, 155_000, 199_000, 155_000, 185_000, fade, false, phraseAtMs = 155_000.0)
    private fun intro(bpm: Float = 122f) = SegmentAnalysis(0, 40_000, bpm, 500.0, .9f, 0, -14f, 300, 40_000, 8_000, 40_000)
    private val out = MixEditor.Side("a", duration, analysis = outro())
    private val into = MixEditor.Side("b", duration, analysis = intro())
    private fun plan(choice: MixEditor.Choice, a: MixEditor.Side = out, b: MixEditor.Side = into) = MixEditor.plan(a, b, choice, 1, MixStyle.Auto)

    /** Samples every curve of both decks over the transition, to compare two plans as heard. */
    private fun heard(plan: MixPlan): List<Float> {
        val o = DeckAutomation.outgoing(plan); val i = DeckAutomation.incoming(plan)
        val from = (plan.outMs - 2_000L) * 1000L; val until = (plan.outMs + plan.overlapMs + 9_000L) * 1000L
        return (from..until step 50_000L).flatMap { t ->
            listOf(o.gain.at(t), o.highPassHz.at(t), o.lowPassHz.at(t), o.midDb.at(t), i.gain.at(t), i.highPassHz.at(t), i.lowPassHz.at(t), i.midDb.at(t))
        }
    }

    @Test fun theDefaultShapeKeepsEveryPlanAsItWas() {
        val cases = listOf(
            out to into,
            MixEditor.Side("a", duration, analysis = outro(bpm = 90f)) to into,
            MixEditor.Side("a", duration, analysis = outro(fade = true)) to into,
            MixEditor.Side("a", duration) to MixEditor.Side("b", duration))
        for ((a, b) in cases) for (style in MixStyle.values()) {
            val old = TransitionPlanner.plan(MixFacts(a.durationMs, a.analysis), MixFacts(b.durationMs, b.analysis), MixContext(), MixSettings(1, style))
            val now = MixEditor.plan(a, b, MixEditor.Choice(style = style), 1, MixStyle.Auto)
            assertEquals("$style", old, now)
            assertSame(old, MixShaping.apply(old, MixShape()))
            if (old.mixes) assertEquals(heard(old), heard(now))
        }
    }

    @Test fun bassFilterAndTempoChangeThePlan() {
        val auto = plan(MixEditor.Choice())
        assertEquals(MixKind.Beatmatch, auto.kind)
        assertTrue(auto.swapMs >= 0)
        assertEquals(-1L, plan(MixEditor.Choice(shape = MixShape(bass = BassSwap.Off))).swapMs)
        assertEquals(1, plan(MixEditor.Choice(shape = MixShape(filter = FilterSweep.LowPass))).variant)
        assertEquals(0, plan(MixEditor.Choice(shape = MixShape(filter = FilterSweep.HighPass))).variant)
        assertNotEquals("no tempo change: 120 and 122 BPM are not matched", MixKind.Beatmatch,
            plan(MixEditor.Choice(shape = MixShape(tempo = false))).kind)
        // A plain fade asked for a bass swap gets one, half-way; a high-pass turns it into a crossing of filters.
        val fade = plan(MixEditor.Choice(style = MixStyle.Fade))
        assertEquals(MixKind.Crossfade, fade.kind)
        val swapped = plan(MixEditor.Choice(style = MixStyle.Fade, shape = MixShape(bass = BassSwap.Medium)))
        assertEquals(fade.outMs + fade.overlapMs / 2, swapped.swapMs)
        val o = DeckAutomation.outgoing(swapped)
        assertEquals("bass kept before", 0f, o.highPassHz.at((fade.outMs + 500L) * 1000L), 0f)
        assertEquals("gone after", DeckAutomation.BASS_HZ, o.highPassHz.at((fade.outMs + fade.overlapMs - 200L) * 1000L), 1f)
        assertEquals(MixKind.Filter, plan(MixEditor.Choice(style = MixStyle.Fade, shape = MixShape(filter = FilterSweep.HighPass))).kind)
        assertEquals(MixKind.Crossfade, plan(MixEditor.Choice(style = MixStyle.Filters, shape = MixShape(filter = FilterSweep.Off))).kind)
    }

    @Test fun theVolumeCurveAndTheLengthOfTheSwapReachTheDecks() {
        val fade = plan(MixEditor.Choice(style = MixStyle.Fade))
        val linear = plan(MixEditor.Choice(style = MixStyle.Fade, shape = MixShape(volume = VolumeCurve.Linear)))
        val middle = (fade.outMs + fade.overlapMs / 2) * 1000L
        assertEquals("a straight line meets at half", .5f, DeckAutomation.outgoing(linear).gain.at(middle), .02f)
        assertNotEquals(DeckAutomation.outgoing(fade).gain.at(middle), DeckAutomation.outgoing(linear).gain.at(middle), .02f)
        val short = plan(MixEditor.Choice())
        val long = plan(MixEditor.Choice(shape = MixShape(bass = BassSwap.Long)))
        assertEquals(short.swapMs, long.swapMs)
        val before = (short.swapMs - 3 * short.beatMs.toLong()) * 1000L
        assertEquals("a short swap has not started 3 beats before", 0f, DeckAutomation.outgoing(short).highPassHz.at(before), 0f)
        assertTrue("a long one has", DeckAutomation.outgoing(long).highPassHz.at(before) > 28f)
    }

    @Test fun theShapeIsKeptWithThePairAndOldChoicesStillRead() {
        for (code in 0..MixShape.MAX_CODE) assertEquals(code, MixShape.of(code)!!.code())
        assertNull(MixShape.of(MixShape.MAX_CODE + 1))
        val shape = MixShape(VolumeCurve.Fast, BassSwap.Long, FilterSweep.HighPass, tempo = false)
        val choice = PairChoice(MixStyle.Tempo, 16_000L, shape)
        assertEquals(choice, MixPointStore.decodePair(MixPointStore.encodePair(choice)))
        assertEquals("saved before 6.9", PairChoice(MixStyle.Echo, 9_000L), MixPointStore.decodePair("2,9000"))
        assertEquals("a default shape is written as before", "2,9000", MixPointStore.encodePair(PairChoice(MixStyle.Echo, 9_000L)))
        assertFalse("a shape alone is a choice", PairChoice(shape = shape).empty)
        assertNull(MixPointStore.decodePair("2,9000,999"))
        MixPointStore.use(MapPointsBackend())
        MixPointStore.setPair("a", "b", choice)
        assertEquals(MixEditor.Choice(-1, -1, 16_000L, MixStyle.Tempo, shape), MixEditor.remembered("a", duration, "b", duration, pair = true))
        assertTrue(MixPointStore.export().all { (k, v) -> MixPointStore.validEntry(k, v) })
        MixPointStore.use(null)
    }
}
