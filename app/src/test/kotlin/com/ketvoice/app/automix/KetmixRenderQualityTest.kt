package com.ketvoice.app.automix

import com.ketvoice.app.automix.MixBench.Ending
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 7.6 ketmix76: objective measures of Ketmix's automatic transitions on synthetic titles of known tempo, phase
 * and bass (see [MixBench]). Prints one table row per fixture (in the test's standard output) and holds each
 * measure to what a DJ would accept.
 */
class KetmixRenderQualityTest {
    private val outStart = 150_000L

    private fun out(bpm: Float, ending: Ending = Ending.Fade, bass: Double = 55.0, level: Float = .8f) =
        MixBench.groove(bpm, outStart, 50f, firstBeatAt = .31f, bassHz = bass, ending = ending, level = level)
    private fun into(bpm: Float, intro: Int = 0, bass: Double = 49.0, level: Float = .8f) =
        MixBench.groove(bpm, 0L, 50f, firstBeatAt = .12f, bassHz = bass, introBars = intro, level = level, seed = 11)

    private fun plan(a: MixBench.Title, b: MixBench.Title, style: MixStyle = MixStyle.Auto, context: MixContext = MixContext(),
                     sung: Boolean = true, intensity: Int = 1): MixPlan {
        // What the app knows from lyrics or separated voices: the outgoing title sings until its outro, the incoming one from its 17th bar.
        val outro = if (a.bpm > 0f) (a.startMs + 44_000 - 16 * 4 * a.beatMs).toLong() else a.startMs + 30_000
        val voice = if (b.bpm > 0f) (b.firstBeatMs + 16 * 4 * b.beatMs).toLong() else 30_000L
        val fa = MixFacts(a.durationMs, MixBench.analysis(a), vocalEndMs = outro.takeIf { sung })
        val fb = MixFacts(b.durationMs, MixBench.analysis(b), vocalStartMs = voice.takeIf { sung })
        return TransitionPlanner.plan(fa, fb, context, MixSettings(intensity, style))
    }

    private fun fixtures(): List<Triple<String, MixBench.Title, MixBench.Title>> = listOf(
        Triple("blend 124>124", out(124f), into(124f)),
        Triple("blend 124>128", out(124f), into(128f)),
        Triple("far 128>96", out(128f), into(96f)),
        Triple("hard end 120>120", out(120f, Ending.Hard), into(120f)),
        Triple("beatless>groove", MixBench.beatless(outStart, 50f), into(110f)),
        Triple("quiet in 124>124", out(124f), into(124f, level = .45f)),
        Triple("loud 126>126", out(126f, level = 1.25f), into(126f, level = 1.25f)),
        Triple("loud far 128>96", out(128f, level = 1.25f), into(96f, level = 1.25f)))

    private val styles = listOf(MixStyle.Tempo, MixStyle.Echo, MixStyle.Filters, MixStyle.Fade, MixStyle.Rise)

    private fun scores(): List<MixBench.Score> {
        val rows = ArrayList<MixBench.Score>()
        for ((name, a, b) in fixtures()) plan(a, b).takeIf { it.mixes }?.let { rows += MixBench.score(name, it, a, b) }
        val a = out(124f); val b = into(126f)
        for (style in styles) plan(a, b, style).takeIf { it.mixes }?.let { rows += MixBench.score(style.name.lowercase() + " 124>126", it, a, b) }
        val intro = into(126f, intro = 8)
        for (style in listOf(MixStyle.Cut, MixStyle.Drop)) plan(a, intro, style).takeIf { it.mixes }?.let { rows += MixBench.score(style.name.lowercase() + " 124>126 intro", it, a, intro) }
        plan(a, b, MixStyle.Voices).takeIf { it.mixes }?.let { rows += MixBench.score("voices 124>126", it, a, b) }
        return rows
    }

    @Test fun automaticTransitionsMeasureWell() {
        val rows = scores()
        println("| fixture                | kind      | align ms    | LU    | lowms | dBTP   | >FSms | clickdB |")
        rows.forEach(::println)
        // The same loud blends without the guard on the sum (the decks not paired), for comparison.
        for ((name, a, b) in fixtures().filter { it.first.startsWith("loud") })
            rows.firstOrNull { it.name == name }?.let { println("$name without the bus guard: " + MixBench.unguardedPeakDb(it.plan, a, b) + " dBTP") }
        for (row in rows) {
            row.align?.let { assertTrue("${row.name}: beats meet within 12 ms (${it.second})", it.second <= 12.0) }
            assertTrue("${row.name}: no click nor zipper noise (${row.clickDb} dBFS above 4 kHz)", row.clickDb <= -55.0)
            assertTrue("${row.name}: the sum stays under full scale where both play (${row.peakDb} dBTP)", row.peakDb <= -.3)
            assertTrue("${row.name}: never over full scale", row.overMs == 0.0)
            assertTrue("${row.name}: the bass changes hands, never two at once for long (${row.clashMs} ms)", row.clashMs <= 250)
            // A cut is a step from one title to the other by nature; a crossing into a fade starts from its faded level.
            if (!row.plan.cut && row.plan.kind != MixKind.Crossfade)
                assertTrue("${row.name}: no hole nor bulge past 2.8 LU (${row.loudness})", row.loudness <= 2.8)
        }
        // 7.7: both titles of a long blend meet at 0.86 where the bass changes hands, not 0.72
        // (3.44 LU before), and the outgoing side of a crossing of spectra keeps its power (2.70 LU before).
        for (name in listOf("hard end 120>120", "beatless>groove", "far 128>96"))
            rows.first { it.name == name }.let { assertTrue("$name: no hole past 2.6 LU (${it.loudness})", it.loudness <= 2.6) }
    }

    @Test fun twoDecksInTimeDoNotDriftApartOverALongBlend() {
        // Nothing corrects the incoming deck once it is heard: its speed alone keeps the kicks together to the end.
        var exact = 0.0; var onGrid = 0.0
        for (bpm in 90..140) for (delta in -6..6) {
            val plan = MixPlan(MixKind.Beatmatch, outMs = 0, overlapMs = 32_000, rate = bpm.toFloat() / (bpm + delta))
            exact = maxOf(exact, DeckSync.driftMs(plan, DeckSync.speedOf(plan, 1f)))
            onGrid = maxOf(onGrid, DeckSync.driftMs(plan, DeckSync.stepped(plan.rate)))
        }
        println("drift over a 64-beat blend at 120 BPM: $exact ms (on the 0.002 grid before 7.7: $onGrid ms)")
        assertTrue("beats stay within 1 ms ($exact)", exact <= 1.0)
        val fast = MixPlan(MixKind.Beatmatch, outMs = 0, overlapMs = 32_000, rate = .97f)
        assertTrue("a title left 2 % fast still meets the next one in time",
            DeckSync.driftMs(fast, DeckSync.speedOf(fast, 1.02f), outgoing = 1.02f) <= 1.0)
    }

    @Test fun leftToKetmixADropTakesItsTurnWhenTheNextTitleHasOne() {
        val a = out(128f, Ending.Outro); val b = into(96f, intro = 8)
        // The DJ setting: this ending stops at full level, which only it cuts.
        val first = plan(a, b, intensity = 2)
        assertTrue("far tempos and a clear drop: ${first.reason}", first.cut && first.reason == "drop")
        assertTrue("its entry is the drop, a downbeat after the quiet intro", first.inMs > 8 * 4 * b.beatMs - 300)
        val again = plan(a, b, context = MixContext(lastExit = 2), intensity = 2)
        assertTrue("never two cuts running: ${again.kind} ${again.reason}", !again.cut)
        val noIntro = plan(a, into(96f), intensity = 2)
        assertTrue("no drop without an intro: ${noIntro.reason}", noIntro.reason != "drop")
        assertTrue("the discreet setting does without", !TransitionPlanner.plan(MixFacts(a.durationMs, MixBench.analysis(a)),
            MixFacts(b.durationMs, MixBench.analysis(b)), MixContext(), MixSettings(0)).cut)
    }

    @Test fun twoLoudDecksTogetherStayUnderFullScaleAndOneAloneIsUntouched() {
        val tone = FloatArray(MixBench.RATE * 2) { (.95 * kotlin.math.sin(2 * Math.PI * 100.0 * it / MixBench.RATE)).toFloat() }
        fun peak(x: FloatArray, from: Int) = (from until x.size).maxOf { kotlin.math.abs(x[it]) }
        val pair = MixPlan(MixKind.Crossfade, outMs = 0, overlapMs = 2_000)
        MixBus.clear()
        val x = MixBench.Deck(tone, 0.0, DeckAutomation(Curve.flat(1f), Curve.flat(0f), pair = pair))
        val y = MixBench.Deck(tone, 0.0, DeckAutomation(Curve.flat(1f), Curve.flat(0f), pair = pair))
        while (!x.done) { x.step(1_024); y.step(1_024) }
        val sum = FloatArray(tone.size) { x.out[it] + y.out[it] }
        assertTrue("1.9 of full scale brought under -1 dBFS (${peak(sum, MixBench.RATE / 4)})", peak(sum, MixBench.RATE / 4) <= MixBus.CEILING + .01f)
        assertEquals("both decks keep their balance", peak(x.out, MixBench.RATE), peak(y.out, MixBench.RATE), .01f)
        MixBus.clear()
        val alone = MixBench.deck(tone, 0.0, DeckAutomation(Curve.flat(1f), Curve.flat(0f), pair = pair))
        assertEquals("a deck alone is left as it is", .95f, peak(alone, 0), .002f)
    }

    @Test fun theNewStylesArePlayedAsAsked() {
        val a = out(124f); val b = into(126f)
        val cut = plan(a, b, MixStyle.Cut)
        assertTrue("a cut on a bar: $cut", cut.cut && cut.buildMs == 0L && cut.overlapMs <= cut.beatMs + 1)
        val voices = plan(a, b, MixStyle.Voices)
        assertTrue("voices handed over: $voices", voices.stems && voices.kind == MixKind.Beatmatch && voices.voiceFromMs > voices.inMs)
        assertEquals("relais des voix", MixWords.label(voices))
        assertEquals("coupe sur le temps", MixWords.label(cut))
        assertEquals(MixStyle.Voices, MixStyle.of(8)); assertEquals(MixStyle.Bridge, MixStyle.of(9)); assertEquals(MixStyle.Auto, MixStyle.of(10))
    }
}
