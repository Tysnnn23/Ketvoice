package com.ketvoice.app.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 9.1.1 (owner, listening on Windows and Android: « 70 % du temps les musiques au lieu de faire de l'automix elles font
 * juste un écho »): the planner run over a realistic spread of pairs, one listening after another as the app plays them
 * (its variety and turn-taking carried from each transition to the next), and the share of each kind on the automatic
 * style. Tempos 60–180 BPM in genre clusters with half/double-time relations, keys read or not, beat confidences as
 * real tracks give them, levels, endings that fade, stop at full level or thin out, voices near the ends and intros.
 */
class KetmixMixShareTest {
    private val duration = 210_000L

    private data class Genre(val from: Float, val until: Float, val confidence: ClosedFloatingPointRange<Float>)
    private val genres = listOf(
        Genre(70f, 100f, .35f..0.9f),   // rap, read at its own pulse
        Genre(140f, 180f, .35f..0.9f),  // trap read at double time
        Genre(95f, 130f, .4f..0.95f),   // pop
        Genre(118f, 130f, .6f..0.98f),  // house
        Genre(168f, 178f, .5f..0.95f),  // drum and bass
        Genre(60f, 85f, .2f..0.7f))     // ballads, often rubato

    private fun Random.between(from: Float, until: Float) = from + nextFloat() * (until - from)
    private fun Random.chance(p: Double) = nextDouble() < p

    private fun tempo(r: Random, genre: Genre): Float {
        val bpm = r.between(genre.from, genre.until)
        // A tempo read at half or double the pulse it is felt at, as analysers often do.
        return when { r.chance(.12) && bpm < 95f -> bpm * 2f; r.chance(.12) && bpm > 120f -> bpm / 2f; else -> bpm }
    }

    private fun key(r: Random) = if (r.chance(.35)) MusicalKey.UNREAD else r.nextInt(24)

    private fun outro(r: Random, genre: Genre): SegmentAnalysis {
        val start = duration - 45_000L
        val ending = r.nextInt(100)
        val hard = ending < 30
        val fade = ending in 30..64
        val end = if (hard) duration - r.nextLong(300, 1_500) else duration - r.nextLong(200, 800)
        val fall = when { hard -> end; fade -> end - r.nextLong(10_000, 26_000); else -> end - r.nextLong(6_000, 30_000) }
        val bpm = tempo(r, genre)
        val k = key(r)
        return SegmentAnalysis(start, 45_000, bpm, start + r.nextDouble(0.0, 400.0), r.between(genre.confidence.start, genre.confidence.endInclusive),
            if (r.chance(.6)) r.nextInt(4) else -1, r.between(-17f, -8f), start, end, start, fall, fade, hard, k, if (k >= 0) .7f else 0f,
            if (r.chance(.4)) start + r.nextDouble(0.0, 2_000.0) else -1.0, activity = r.between(.5f, 2f))
    }

    private fun intro(r: Random, genre: Genre): SegmentAnalysis {
        val soundStart = r.nextLong(0, 1_500)
        val k = key(r)
        return SegmentAnalysis(0, 40_000, tempo(r, genre), r.nextDouble(0.0, 600.0), r.between(genre.confidence.start, genre.confidence.endInclusive),
            if (r.chance(.6)) r.nextInt(4) else -1, r.between(-17f, -8f), soundStart, 40_000, soundStart + r.nextLong(0, 20_000), 40_000,
            key = k, keyStrength = if (k >= 0) .7f else 0f, activity = r.between(.5f, 2f))
    }

    /** One pair: most follow one of the same genre, as in a playlist; some cross genres. */
    private fun pair(r: Random): Pair<MixFacts, MixFacts> {
        val g = genres[r.nextInt(genres.size)]
        val a = outro(r, g)
        val b = intro(r, if (r.chance(.65)) g else genres[r.nextInt(genres.size)])
        val sungUntil = if (r.chance(.75)) {
            // A voice that sings to the last note of a title that stops at full level, or up to its outro.
            if (a.hardEnding && r.chance(.5)) a.soundEndMs - r.nextLong(300, 2_500) else a.soundEndMs - r.nextLong(4_000, 40_000)
        } else null
        val sungFrom = if (r.chance(.75)) b.soundStartMs + r.nextLong(500, 26_000) else null
        return MixFacts(duration, a, vocalEndMs = sungUntil) to MixFacts(duration, b, vocalStartMs = sungFrom)
    }

    /** The kinds of a listening of [count] transitions, each planned with what the app remembers of the ones before. */
    private fun listening(count: Int, seed: Int, intensity: Int = 1, style: MixStyle = MixStyle.Auto, energy: Boolean = false): List<MixPlan> {
        val r = Random(seed)
        var lastVariant = -1; var lastExit = 0; var recent = emptyList<MixKind>(); var looped = false
        val plans = ArrayList<MixPlan>()
        repeat(count) {
            val (out, incoming) = pair(r)
            val step = if (energy && r.chance(.5)) r.between(-.3f, .3f) else Float.NaN
            val plan = TransitionPlanner.plan(out, incoming, MixContext(lastVariant = lastVariant, lastExit = lastExit, recentKinds = recent,
                loopedLast = looped, energyStep = step), MixSettings(intensity, style))
            if (!plan.mixes) return@repeat
            plans += plan
            // What Automix.remember keeps.
            if (plan.kind == MixKind.Beatmatch || plan.kind == MixKind.Filter) lastVariant = plan.variant
            if (plan.kind == MixKind.Echo) lastExit = 1
            if (plan.kind == MixKind.Rise) lastExit = 2
            recent = MixVariety.remember(recent, plan)
            looped = plan.loopUs > 0L
        }
        return plans
    }

    private fun shares(plans: List<MixPlan>): Map<MixKind, Double> =
        MixKind.values().associateWith { k -> plans.count { it.kind == k } * 100.0 / plans.size }

    private fun report(name: String, plans: List<MixPlan>): Map<MixKind, Double> {
        val s = shares(plans)
        println("$name (${plans.size} transitions): " + s.filterValues { it > 0 }.entries.joinToString { "${it.key} %.1f %%".format(java.util.Locale.ROOT, it.value) })
        val echoes = plans.filter { it.kind == MixKind.Echo }.groupingBy { it.reason }.eachCount()
        if (echoes.isNotEmpty()) println("  echoes by reason: $echoes")
        return s
    }

    private fun echoesRunning(plans: List<MixPlan>) = plans.zipWithNext().count { (x, y) -> x.kind == MixKind.Echo && y.kind == MixKind.Echo }

    @Test fun theAutomaticStyleBlendsAndKeepsEchoesRare() {
        for ((name, intensity, energy) in listOf(Triple("balanced", 1, false), Triple("DJ", 2, false), Triple("PC energy", 1, true))) {
            val plans = (1..4).flatMap { listening(150, seed = it * 31 + intensity, intensity = intensity, energy = energy) }
            val s = report(name, plans)
            val echo = s.getValue(MixKind.Echo)
            assertTrue("$name: echo is rare ($echo %)", echo <= 10.0)
            assertEquals("$name: never two echoes running", 0, echoesRunning(plans))
            // The real blends: in time with the bass swap, crossings of spectra or reverberation, plain fades. A title that stops
            // at full level is mixed out before its last hit; the next title on the beat after it (no mix) stays rare.
            val blends = s.getValue(MixKind.Beatmatch) + s.getValue(MixKind.Filter) + s.getValue(MixKind.Wash) + s.getValue(MixKind.Crossfade)
            assertTrue("$name: most transitions are real blends ($blends %)", blends >= 80.0)
            assertTrue("$name: few titles simply follow each other (${s.getValue(MixKind.Trim)} %)", s.getValue(MixKind.Trim) <= 5.0)
            assertTrue("$name: effects stay exceptions", echo + s.getValue(MixKind.Rise) <= 15.0)
        }
    }

    @Test fun leftToKetmixAnEchoOnlyClosesAHardVocalEndingIntoAFarTempo() {
        val start = duration - 45_000L
        val hard = SegmentAnalysis(start, 45_000, 128f, start.toDouble(), .9f, 0, -10f, start, duration - 500, start, duration - 500, false, true)
        val far = SegmentAnalysis(0, 40_000, 92f, 300.0, .9f, 0, -10f, 200, 40_000, 6_000, 40_000)
        fun plan(sungUntil: Long?, recent: List<MixKind> = emptyList(), into: SegmentAnalysis = far) = TransitionPlanner.plan(
            MixFacts(duration, hard, vocalEndMs = sungUntil), MixFacts(duration, into, vocalStartMs = 12_000), MixContext(recentKinds = recent), MixSettings())
        assertEquals("sung to the last note, into a far tempo", MixKind.Echo, plan(duration - 1_500).kind)
        // Otherwise the title is mixed out before its last hit, which is never reached nor covered.
        fun mixedOutEarly(p: MixPlan) = p.kind in setOf(MixKind.Beatmatch, MixKind.Filter, MixKind.Wash, MixKind.Crossfade) &&
            p.outMs + p.overlapMs <= hard.soundEndMs && p.reason.endsWith("before the last hit")
        plan(duration - 1_500, listOf(MixKind.Echo)).let { assertTrue("never two echoes running: $it", mixedOutEarly(it)) }
        plan(null).let { assertTrue("an instrumental ending: $it", mixedOutEarly(it)) }
        plan(duration - 1_500, into = far.copy(bpm = 126f)).let { assertTrue("a close tempo, in time: $it", mixedOutEarly(it) && it.kind == MixKind.Beatmatch) }
        // The discreet setting leaves nothing out: the next title still starts on the beat after the last note.
        assertEquals(MixKind.Trim, TransitionPlanner.plan(MixFacts(duration, hard), MixFacts(duration, far), MixContext(), MixSettings(0)).kind)
    }

    @Test fun anEchoTheListenerChoseIsStillPlayed() {
        val plans = (1..2).flatMap { listening(120, seed = it * 7, style = MixStyle.Echo) }
        val echo = report("Echo style", plans).getValue(MixKind.Echo)
        assertTrue("the echo style keeps its echoes ($echo %)", echo >= 50.0)
    }
}
