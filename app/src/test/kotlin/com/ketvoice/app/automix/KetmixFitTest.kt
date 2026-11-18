package com.ketvoice.app.automix

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * What Ketmix decides for a pair of titles from how well they go together (tempo, key, level, voices, bars,
 * album joins), on made-up analyses: the rules of [BlendFit] and of [TransitionPlanner] that go with them.
 */
class KetmixFitTest {
    private val duration = 200_000L
    private val cMajor = 0
    private val fSharpMajor = 6

    /** Last 45 s of a 200 s title at [bpm], beats on the second, with an outro from [fall]. */
    private fun outro(bpm: Float = 120f, fall: Long = 185_000, end: Long = 199_000, fade: Boolean = false, hard: Boolean = false,
                      bar: Int = -1, loudness: Float = -14f, key: Int = MusicalKey.UNREAD, phrase: Double = -1.0, confidence: Float = .9f) =
        SegmentAnalysis(155_000, 45_000, bpm, 155_000.0, confidence, bar, loudness, 155_000, end, 155_000,
            if (hard) end else fall, fade, hard, key, if (key >= 0) .8f else 0f, phrase)
    private fun intro(bpm: Float = 120f, start: Long = 300, rise: Long = 8_000, bar: Int = -1, loudness: Float = -14f,
                      key: Int = MusicalKey.UNREAD, confidence: Float = .9f) =
        SegmentAnalysis(0, 40_000, bpm, 500.0, confidence, bar, loudness, start, 40_000, rise, 40_000, key = key,
            keyStrength = if (key >= 0) .8f else 0f)
    private fun plan(a: SegmentAnalysis?, b: SegmentAnalysis?, context: MixContext = MixContext(), intensity: Int = 1,
                     sungUntil: Long? = null, sungFrom: Long? = null) =
        TransitionPlanner.plan(MixFacts(duration, a, vocalEndMs = sungUntil), MixFacts(duration, b, vocalStartMs = sungFrom),
            context, MixSettings(intensity))

    @Test fun titlesThatFitAreBlendedInTimeForSixteenBars() {
        val plan = plan(outro(120f, key = cMajor), intro(122f, key = cMajor), sungUntil = 165_000)
        assertEquals(MixKind.Beatmatch, plan.kind)
        assertEquals("64 beats at 120 BPM", 32_000L, plan.overlapMs)
        assertEquals("the incoming title is played at the outgoing tempo", 120f / 122f, plan.rate, 1e-4f)
        assertTrue("the bass changes hands", plan.swapMs in plan.outMs..(plan.outMs + plan.overlapMs))
        assertFalse(plan.clash)
        assertEquals("levels alike: neither is touched", 1f, plan.gain, 1e-4f); assertEquals(1f, plan.outGain, 1e-4f)
    }

    @Test fun aPairThatOnlyJustFitsGetsAShorterBlend() {
        // 5.8 % apart: still in time (balanced allows 6 %), but the stretch is heard, so 8 bars and no more.
        val stretched = plan(outro(120f), intro(127f), sungUntil = 165_000)
        assertEquals(MixKind.Beatmatch, stretched.kind)
        assertEquals("32 beats at 120 BPM", 16_000L, stretched.overlapMs)
        // 8 dB apart in level: 8 bars too.
        val uneven = plan(outro(120f, loudness = -10f), intro(120f, loudness = -18f), sungUntil = 165_000)
        assertEquals(MixKind.Beatmatch, uneven.kind); assertEquals(16_000L, uneven.overlapMs)
        // Keys that clash: 4 bars at most.
        val clashing = plan(outro(120f, key = cMajor), intro(120f, key = fSharpMajor), sungUntil = 165_000)
        assertEquals(MixKind.Beatmatch, clashing.kind); assertTrue(clashing.clash); assertTrue(clashing.overlapMs <= 8_000L)

        val balanced = MixSettings(1)
        assertArrayEquals(intArrayOf(64, 32, 16, 8), BlendFit.counts(balanced, .02f, clash = false, loop = false, levelGapDb = 2f))
        assertArrayEquals("exactly 4 % is still a long blend", intArrayOf(64, 32, 16, 8), BlendFit.counts(balanced, .04f, false, false, 0f))
        assertArrayEquals(intArrayOf(32, 16, 8), BlendFit.counts(balanced, .05f, false, false, 0f))
        assertArrayEquals(intArrayOf(32, 16, 8), BlendFit.counts(balanced, 0f, false, false, 7f))
        assertArrayEquals(intArrayOf(16, 8), BlendFit.counts(balanced, 0f, clash = true, loop = false, levelGapDb = 0f))
        assertArrayEquals(intArrayOf(16, 8), BlendFit.counts(balanced, 0f, clash = false, loop = true, levelGapDb = 0f))
        assertArrayEquals("discreet never went past 8 bars", intArrayOf(32, 16, 8), BlendFit.counts(MixSettings(0), 0f, false, false, 0f))
    }

    @Test fun temposTooFarApartAreNeverStretched() {
        // 120 into 150 BPM, the outgoing title fading out: a crossing within that fade, each at its own speed.
        val faded = plan(outro(120f, fall = 187_000, fade = true), intro(150f))
        assertEquals(MixKind.Filter, faded.kind)
        assertEquals(1f, faded.rate, 0f); assertEquals(-1L, faded.swapMs)
        assertTrue("within the ending (${faded.outMs})", faded.outMs >= 187_000 - 2_500)
        assertTrue("short: ${faded.overlapMs}", faded.overlapMs in 2_500L..8_000L)
        // The same without the decks' effects: a plain crossfade.
        val plain = plan(outro(120f, fall = 187_000, fade = true), intro(150f), MixContext(effects = false))
        assertEquals(MixKind.Crossfade, plain.kind); assertEquals(1f, plain.rate, 0f)
        // A title that keeps its beat to the end is left on the beat, never played in time with the other.
        val cut = plan(outro(138f), intro(122f), sungUntil = 170_000)
        assertTrue("${cut.kind}", cut.kind == MixKind.Echo || cut.kind == MixKind.Rise)
        assertEquals(1f, cut.rate, 0f)
        // Nothing known of either title: a short plain crossfade at the very end.
        val unknown = plan(null, null)
        assertEquals(MixKind.Crossfade, unknown.kind); assertTrue(unknown.overlapMs <= 6_000L)
        assertTrue(unknown.outMs + unknown.overlapMs <= duration)
    }

    @Test fun anAlbumThatRunsOnAcrossItsTitlesIsNotMixed() {
        // A concert: the applause (well under the level of the music) runs to the last sample and on into the next title.
        val applause = outro(120f, fall = 190_000, end = 199_950)
        val next = intro(120f, start = 10, rise = 6_000)
        val album = MixContext(sameAlbumInOrder = true)
        val joined = plan(applause, next, album, sungUntil = 185_000)
        assertEquals(MixKind.None, joined.kind); assertEquals("gapless album", joined.reason)
        // A last note at full level into a start at full level, as before.
        assertEquals("gapless album", plan(outro(120f, hard = true, end = 199_900), intro(120f, start = 20, rise = 200), album).reason)
        // The same two titles met in a playlist are mixed as any others.
        assertTrue(plan(applause, next, MixContext(sameAlbumInOrder = false), sungUntil = 185_000).mixes)
        // An album title that fades out, or that is followed by a little silence, is mixed.
        assertTrue(plan(outro(120f, fall = 190_000, end = 199_950, fade = true), next, album, sungUntil = 185_000).mixes)
        assertTrue(plan(outro(120f, fall = 190_000, end = 198_500), next, album, sungUntil = 185_000).mixes)
        assertTrue(plan(applause, intro(120f, start = 900), album, sungUntil = 185_000).mixes)
        // A symphony played in order: no steady beat on either side of the join, some silence between the movements.
        val movement = outro(0f, fall = 190_000, end = 196_000, confidence = 0f)
        val nextMovement = intro(0f, start = 1_200, confidence = 0f)
        assertEquals("album in order", plan(movement, nextMovement, album).reason)
        assertTrue("the same two pieces in a playlist cross within the ending", plan(movement, nextMovement).mixes)
        assertTrue("an album with a beat is still mixed", plan(outro(120f), intro(120f, start = 1_200), album, sungUntil = 185_000).mixes)
        // And the listener who wants albums left alone gets every album title as it is.
        assertEquals("album in order", plan(outro(), intro(), MixContext(sameAlbumInOrder = true, albumsMixed = false)).reason)
    }

    @Test fun silenceAtTheEndAndAtTheStartIsLeftOut() {
        // 9 s of silence after the last sound, 2.5 s before the first: the mix is over when the sound is, and starts on music.
        val quietEnd = outro(120f, fall = 180_000, end = 191_000)
        val lateStart = intro(120f, start = 2_500)
        val blend = plan(quietEnd, lateStart, sungUntil = 160_000)
        assertEquals(MixKind.Beatmatch, blend.kind)
        assertTrue("over with the sound (${blend.outMs + blend.overlapMs})", blend.outMs + blend.overlapMs <= 191_000)
        assertTrue("on the first beat after the silence (${blend.inMs})", blend.inMs in 2_500L..3_000L)
        val fade = plan(outro(120f, fall = 180_000, end = 191_000, fade = true), intro(150f, start = 2_500))
        assertTrue(fade.mixes)
        // A fade is almost silent in its last quarter: the next title is in before that.
        assertTrue("before the fade is gone (${fade.outMs + fade.overlapMs})", fade.outMs + fade.overlapMs <= 180_000 + 11_000 * 3 / 4)
        assertTrue("starts on music (${fade.inMs})", fade.inMs >= 2_500L - 40L)
        // The silence itself is never played: the outgoing title is left long before its file ends.
        assertTrue(blend.outMs < 191_000 && fade.outMs < 191_000)
    }

    @Test fun neitherTitleJumpsOutOfTheBlend() {
        // The incoming title is 6 dB louder: it is held back, and only comes to its own level afterwards.
        val louder = plan(outro(120f, loudness = -16f), intro(120f, loudness = -10f), sungUntil = 165_000)
        assertEquals(MixKind.Beatmatch, louder.kind)
        assertEquals(.501f, louder.gain, .005f); assertEquals(1f, louder.outGain, 0f)
        // The incoming title is 3 dB quieter: the outgoing one is led down to it where they meet.
        val quieter = plan(outro(120f, loudness = -11f), intro(120f, loudness = -14f), sungUntil = 165_000)
        assertEquals(1f, quieter.gain, 0f); assertEquals(.708f, quieter.outGain, .005f)
        // Never by more than 4.4 dB, and never when a level was not measured.
        assertEquals(BlendFit.OUT_FLOOR, BlendFit.outGain(outro(loudness = -6f), intro(loudness = -20f)), 0f)
        assertEquals(1f, BlendFit.outGain(outro(loudness = -90f), intro(loudness = -14f)), 0f)
        assertEquals(1f, BlendFit.outGain(outro(loudness = -14f), intro(loudness = -90f)), 0f)
        assertEquals(0f, BlendFit.levelGapDb(outro(loudness = -90f), intro(loudness = -14f)), 0f)

        // What the decks play: where the bass changes hands both are at the same loudness.
        fun meeting(p: MixPlan): Pair<Float, Float> {
            val swapIn = ((p.inMs + (p.swapMs - p.outMs) * p.rate.toDouble()) * 1000.0).toLong()
            return DeckAutomation.outgoing(p).gain.at(p.swapMs * 1000L) to DeckAutomation.incoming(p).gain.at(swapIn)
        }
        val (outLoud, inLoud) = meeting(louder)
        assertEquals("-16 dB + outgoing gain = -10 dB + incoming gain", -16f + db(outLoud), -10f + db(inLoud), .3f)
        val (outQuiet, inQuiet) = meeting(quieter)
        assertEquals(-11f + db(outQuiet), -14f + db(inQuiet), .3f)
        assertTrue("the outgoing title is only led down, never up", outQuiet < outLoud && outLoud <= .73f)
        // Once the blend is over the incoming title is back at its own level, reached without a step.
        val incoming = DeckAutomation.incoming(louder).gain
        val endUs = louder.inEndMs * 1000L
        assertEquals(louder.gain, incoming.at(endUs), .02f)
        assertEquals(1f, incoming.at(endUs + 9_000_000L), 1e-3f)
        var previous = incoming.at(endUs)
        for (step in 1..90) {
            val now = incoming.at(endUs + step * 100_000L)
            assertTrue("no jump while it settles ($previous -> $now)", now >= previous - 1e-4f && now - previous < .02f)
            previous = now
        }
    }

    @Test fun aBuildIsOnlyPlayedIntoATitleThatCanFollowIt() {
        // Tempos too far apart; the last exit was an echo, so a build is due.
        val due = MixContext(lastExit = 1)
        assertEquals(MixKind.Rise, plan(outro(138f), intro(122f), due, sungUntil = 170_000).kind)
        // Into a title 8 dB quieter the drop would fall flat: the outgoing title leaves on an echo instead.
        val flat = plan(outro(138f, loudness = -9f), intro(122f, loudness = -17f), due, sungUntil = 170_000)
        assertEquals(MixKind.Echo, flat.kind)
        assertEquals("the quieter title is not turned up, nor the other one cut down at once", 1f, flat.gain, 0f)
        assertTrue(BlendFit.carriesADrop(outro(loudness = -12f), intro(loudness = -15f)))
        assertTrue(BlendFit.carriesADrop(outro(loudness = -12f), intro(loudness = -6f)))
        assertFalse(BlendFit.carriesADrop(outro(loudness = -12f), intro(loudness = -17f)))
        assertTrue("a level that was not measured rules nothing out", BlendFit.carriesADrop(outro(loudness = -90f), intro(loudness = -17f)))
    }

    private fun db(gain: Float) = (20.0 * Math.log10(gain.toDouble())).toFloat()

    @Test fun twoVoicesNeverSingTogether() {
        // The outgoing title sings until 180 s; the incoming one sings half a second after its first beat.
        val early = plan(outro(120f), intro(120f), sungUntil = 180_000, sungFrom = 1_000)
        assertEquals(MixKind.Beatmatch, early.kind)
        val voiceIn = early.outMs + ((1_000 - early.inMs) / early.rate).toLong()
        assertTrue("the incoming voice ($voiceIn) waits for the outgoing one (180 300)", voiceIn >= 180_300 - 250)
        assertTrue("which leaves 8 bars at most", early.overlapMs <= 16_000L)
        // An incoming title that does not sing for a while may still rise under the last sung words.
        val late = plan(outro(120f), intro(120f), sungUntil = 180_000, sungFrom = 30_000)
        assertEquals(MixKind.Beatmatch, late.kind)
        assertTrue("under the last two bars (${late.outMs})", late.outMs in 176_000L until 180_300L)
        assertEquals("the outgoing voice steps back", 180_300L, late.sungUntilMs)
        // Titles that are not played in time: a phrase 0.8 s before the last sung word ends is not worth two voices at once…
        val fading = outro(120f, fall = 187_000, fade = true, bar = 3, phrase = 158_500.0)
        val crossed = plan(fading, intro(150f), sungUntil = 191_000, sungFrom = 500)
        assertEquals(MixKind.Filter, crossed.kind)
        assertEquals("starts as the outgoing voice ends", 191_300L, crossed.outMs); assertFalse(crossed.onPhrase)
        // …while an incoming title whose voice comes after those words is still brought in on that phrase.
        val phrased = plan(fading, intro(150f), sungUntil = 191_000, sungFrom = 3_000)
        assertEquals(190_500L, phrased.outMs); assertTrue(phrased.onPhrase)
        // The rule on its own: none of the lead is left once the incoming voice is there at once.
        val a = outro(120f); val balanced = MixSettings(1)
        assertEquals(4_000L, BlendFit.voiceLeadMs(a, balanced, 500f, null))
        assertEquals(4_000L, BlendFit.voiceLeadMs(a, balanced, 500f, 29_500f))
        assertEquals(1_200L, BlendFit.voiceLeadMs(a, balanced, 500f, 1_200f))
        assertEquals(0L, BlendFit.voiceLeadMs(a, balanced, 500f, -300f))
        assertEquals("a sung last note at full level keeps its ending", 0L, BlendFit.voiceLeadMs(outro(120f, hard = true), balanced, 500f, null))
    }

    @Test fun theIncomingTitlesFirstBarOpensAPhraseOfTheOutgoingOne() {
        // The outgoing title turns a page at 165 s. The incoming one starts on a pickup: its first bar is three beats in.
        val a = outro(120f, bar = 0, phrase = 165_000.0)
        val b = intro(120f, bar = 3)
        val plan = plan(a, b, sungUntil = 165_000)
        assertEquals(MixKind.Beatmatch, plan.kind); assertTrue(plan.onPhrase)
        assertEquals("the pickup is not skipped", 500L, plan.inMs)
        assertEquals("it is started three beats before the phrase", 163_500L, plan.outMs)
        assertEquals("so that its first bar (2 s in) falls on the phrase", 165_000L, plan.outMs + (2_000L - plan.inMs))
        assertEquals("bars meet", BlendFit.barIndex(a, plan.outMs.toDouble()), BlendFit.barIndex(b, plan.inMs.toDouble()))
        assertEquals("the bass changes hands on a first beat", 0, BlendFit.barIndex(a, plan.swapMs.toDouble()))
        assertTrue("in the middle", abs(plan.swapMs - (plan.outMs + plan.overlapMs / 2)) <= 1_000L)

        // An incoming title that starts on its first bar is put on the phrase itself, as before.
        assertEquals(165_000L, plan(a, intro(120f, bar = 0), sungUntil = 165_000).outMs)
        // No room before the phrase (the outgoing title still sings): as many beats into it as into its own bar.
        assertEquals(165_500.0, BlendFit.onPhrase(a, b, 500L, 1f, 165_000.0, earliest = 164_900.0, latest = 167_000.0), 1e-6)
        // Bars unknown, or pulses read an octave apart: the phrase itself.
        assertEquals(165_000.0, BlendFit.onPhrase(a, intro(120f), 500L, 1f, 165_000.0, 150_000.0, 167_000.0), 1e-6)
        assertEquals(165_000.0, BlendFit.onPhrase(a, intro(60f, bar = 3), 500L, 1f, 165_000.0, 150_000.0, 167_000.0), 1e-6)
        // Without bars the bass still changes hands on the beat nearest the middle.
        assertEquals(179_500.0, BlendFit.swapBeat(outro(120f), 179_400.0), 1e-6)
        assertEquals(180_000.0, BlendFit.swapBeat(outro(120f, bar = 2), 179_400.0), 1e-6)
    }
}
