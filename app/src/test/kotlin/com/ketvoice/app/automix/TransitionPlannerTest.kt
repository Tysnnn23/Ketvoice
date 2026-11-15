package com.ketvoice.app.automix

import com.ketvoice.app.data.MusicApi
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class TransitionPlannerTest {
    private val duration = 200_000L
    /** Last 45 s of a 200 s title at [bpm], beats on the second, with an outro from [fall]. */
    private fun outro(bpm: Float = 120f, fall: Long = 185_000, end: Long = 199_000, fade: Boolean = false, hard: Boolean = false,
                      confidence: Float = .9f, bar: Int = -1, loudness: Float = -14f, key: Int = MusicalKey.UNREAD, phrase: Double = -1.0) =
        SegmentAnalysis(155_000, 45_000, bpm, 155_000.0, confidence, bar, loudness, 155_000, end, 155_000,
            if (hard) end else fall, fade, hard, key, if (key >= 0) .8f else 0f, phrase)
    private fun intro(bpm: Float = 120f, start: Long = 300, rise: Long = 8_000, confidence: Float = .9f, bar: Int = -1,
                      loudness: Float = -14f, key: Int = MusicalKey.UNREAD) =
        SegmentAnalysis(0, 40_000, bpm, 500.0, confidence, bar, loudness, start, 40_000, rise, 40_000, key = key,
            keyStrength = if (key >= 0) .8f else 0f)
    private fun plan(a: SegmentAnalysis?, b: SegmentAnalysis?, context: MixContext = MixContext(), intensity: Int = 1,
                     sungUntil: Long? = null, sungFrom: Long? = null, outDuration: Long = duration, inDuration: Long = duration) =
        TransitionPlanner.plan(MixFacts(outDuration, a, vocalEndMs = sungUntil), MixFacts(inDuration, b, vocalStartMs = sungFrom),
            context, MixSettings(intensity))
    private fun onBeat(position: Long, origin: Double, bpm: Float): Boolean {
        val beats = (position - origin) / (60_000.0 / bpm)
        return abs(beats - Math.round(beats)) * (60_000.0 / bpm) < 2.0
    }

    @Test fun someListeningIsNeverMixed() {
        assertEquals("Albums are left alone only when the listener says so", MixKind.None,
            plan(outro(), intro(), MixContext(sameAlbumInOrder = true, albumsMixed = false)).kind)
        assertEquals("...and mixed by default", MixKind.Beatmatch, plan(outro(), intro(), MixContext(sameAlbumInOrder = true), sungUntil = 170_000).kind)
        assertEquals(MixKind.None, plan(outro(), intro(), MixContext(repeatOne = true)).kind)
        assertEquals(MixKind.None, plan(outro(), intro(), MixContext(stopAfterCurrent = true)).kind)
        assertEquals(MixKind.None, plan(outro(), intro(), MixContext(exactAudio = true)).kind)
        assertEquals("An interlude is left alone", MixKind.None, plan(outro(), intro(), inDuration = 25_000).kind)
        assertEquals(MixKind.None, plan(outro(), intro(), outDuration = 20_000).kind)
        assertFalse(plan(outro(), intro(), MixContext(repeatOne = true)).mixes)
    }

    @Test fun closeTemposAreMixedOnTheBeat() {
        val plan = plan(outro(120f), intro(124f), sungUntil = 170_000)
        assertEquals(MixKind.Beatmatch, plan.kind)
        assertEquals("The incoming title follows the outgoing tempo", 120f / 124f, plan.rate, 1e-4f)
        assertEquals("64 beats at 120 BPM", 32_000L, plan.overlapMs)
        assertTrue("The mix starts on a beat of the outgoing title", onBeat(plan.outMs, 155_000.0, 120f))
        assertTrue("The incoming title enters on its first beat", onBeat(plan.inMs, 500.0, 124f))
        assertEquals(500L, plan.inMs)
        assertTrue("At most two bars under the last sung words (${plan.outMs})", plan.outMs >= 170_300 - 4_000)
        assertTrue("Both are heard until the outgoing title really ends", plan.outMs + plan.overlapMs <= 199_000)
        assertEquals("the voice that ends steps back", 170_300L, plan.sungUntilMs)
        assertTrue("The bass changes hands on a beat in the middle", onBeat(plan.swapMs, 155_000.0, 120f) &&
            abs(plan.swapMs - (plan.outMs + plan.overlapMs / 2)) <= 500)
        assertEquals(plan.inMs + (32_000 * plan.rate).toLong().toDouble(), plan.inEndMs.toDouble(), 2.0)
    }

    @Test fun aTempoReadTwiceAsFastIsTheSamePulse() {
        val plan = plan(outro(140f), intro(70f), sungUntil = 165_000)
        assertEquals(MixKind.Beatmatch, plan.kind)
        assertEquals(1f, plan.rate, 1e-4f)
        // 140 BPM is a bar pulse already; 64 beats of it.
        assertEquals((64 * 60_000f / 140f).toLong().toDouble(), plan.overlapMs.toDouble(), 2.0)
        val fast = plan(outro(174f), intro(87f), sungUntil = 160_000)
        assertEquals("174 BPM is counted on its 87 BPM pulse", (32 * 60_000f / 87f).toLong().toDouble(), fast.overlapMs.toDouble(), 2.0)
    }

    @Test fun tempoRangeFollowsTheIntensity() {
        // The common tempo slides from one title's to the other's during the blend: each is only stretched while it is the quiet one.
        assertEquals(.06f, MixSettings(0).tempoRange, 0f); assertEquals(.14f, MixSettings(1).tempoRange, 0f); assertEquals(.18f, MixSettings(2).tempoRange, 0f)
        assertEquals("5 % apart: in time, even discreet", MixKind.Beatmatch, plan(outro(120f), intro(126f), intensity = 0, sungUntil = 170_000).kind)
        assertEquals("7 % apart: balanced plays them in time", MixKind.Beatmatch, plan(outro(120f), intro(128.4f), sungUntil = 170_000).kind)
        assertEquals("discreet does not, and does without an echo", MixKind.Filter, plan(outro(120f), intro(128.4f), intensity = 0, sungUntil = 170_000).kind)
        // 138 into 122 BPM (13 % apart), a pair a listener reported as never played in time: balanced does now.
        val reported = plan(outro(138f), intro(122f), sungUntil = 170_000)
        assertEquals(MixKind.Beatmatch, reported.kind); assertEquals(138f / 122f, reported.rate, 1e-3f)
        assertEquals("15 % apart: only DJ; balanced leaves on an echo", MixKind.Echo, plan(outro(120f), intro(142f), sungUntil = 170_000).kind)
        assertEquals(MixKind.Beatmatch, plan(outro(120f), intro(142f), intensity = 2, sungUntil = 170_000).kind)
        assertEquals("25 % apart: nobody stretches that far", MixKind.Echo, plan(outro(120f), intro(150f), intensity = 2, sungUntil = 170_000).kind)
    }

    @Test fun titlesThatCannotShareATempoPartOnAnEcho() {
        // 120 and 150 BPM, an ending that winds down from 185 s: the cut is on the last beat of the body.
        val plan = plan(outro(120f), intro(150f), sungUntil = 170_000)
        assertEquals(MixKind.Echo, plan.kind)
        assertTrue("on a beat of the outgoing title", onBeat(plan.outMs, 155_000.0, 120f))
        assertEquals("where the body ends", 185_000L, plan.outMs)
        assertEquals("the echo rings for four beats", 2_000L, plan.overlapMs)
        assertEquals("the next title keeps its own tempo", 1f, plan.rate, 0f)
        assertEquals("and enters on its first beat", 500L, plan.inMs)
        assertEquals(500f, plan.beatMs, 0f)
        assertTrue(plan.beatTied)
        // Bars known: the cut is on a first beat (beats 1, 5, 9… of the grid here).
        val barred = plan(outro(120f, bar = 1), intro(150f), sungUntil = 170_000)
        assertEquals(1L, Math.floorMod(Math.round((barred.outMs - 155_000.0) / 500.0), 4L))
        assertTrue(barred.outMs in 183_250..185_250)
        // Never while somebody still sings, never over a fade, and not when it would leave out more than the setting allows.
        assertNotEquals(MixKind.Echo, plan(outro(120f), intro(150f), sungUntil = 198_000).kind)
        assertEquals(MixKind.Filter, plan(outro(120f, fall = 187_000, fade = true), intro(150f)).kind)
        assertEquals("A 40 s quiet ending is played, then crossed", MixKind.Filter,
            plan(outro(120f, fall = 159_000), intro(150f)).kind)
        // A title that ends at full level keeps its last note, which rings on past the end; the DJ setting cuts on the bar.
        val rung = plan(outro(120f, hard = true), intro(150f), sungUntil = 180_000)
        assertEquals(MixKind.Echo, rung.kind); assertTrue(rung.tail); assertTrue(rung.cutMs >= 199_000L)
        assertEquals(MixKind.Trim, plan(outro(120f, hard = true), intro(150f), intensity = 0, sungUntil = 180_000).kind)
        val dj = plan(outro(120f, hard = true), intro(150f), intensity = 2, sungUntil = 180_000)
        assertEquals(MixKind.Echo, dj.kind)
        assertEquals("the echo takes the place of the last four beats", 197_000L, dj.outMs)
    }

    @Test fun aMixStartsWhereAPhraseOfTheOutgoingTitleStarts() {
        // Without phrases the 64-beat mix starts at 163 s; phrases every 16 s from 165 s move it there.
        val plain = plan(outro(120f, bar = 0), intro(120f, bar = 0), sungUntil = 165_000)
        assertEquals(163_000L, plain.outMs); assertFalse(plain.onPhrase)
        val phrased = plan(outro(120f, bar = 0, phrase = 165_000.0), intro(120f, bar = 0), sungUntil = 165_000)
        assertEquals(MixKind.Beatmatch, phrased.kind)
        assertEquals(165_000L, phrased.outMs); assertTrue(phrased.onPhrase)
        assertEquals("64 beats still", 32_000L, phrased.overlapMs)
        // Never earlier than two bars before the singing ends, nor so late that the overlap leaves the title.
        val tooLate = plan(outro(120f, bar = 0, phrase = 187_000.0), intro(120f, bar = 0), sungUntil = 165_000)
        assertTrue(tooLate.outMs + tooLate.overlapMs <= 199_000); assertTrue(tooLate.outMs >= 165_300 - 4_000)
        // An echo leaves on the phrase boundary just before the end of the body.
        val echo = plan(outro(120f, bar = 0, phrase = 167_000.0), intro(150f), sungUntil = 165_000)
        assertEquals(MixKind.Echo, echo.kind); assertEquals(183_000L, echo.outMs); assertTrue(echo.onPhrase)
        // The incoming title enters on its downbeat when that is its next beat.
        assertEquals(500L, plan(outro(120f, bar = 0), intro(120f, bar = 0), sungUntil = 165_000).inMs)
        assertEquals("a downbeat three beats later is not waited for", 500L, plan(outro(120f, bar = 0), intro(120f, bar = 3), sungUntil = 165_000).inMs)
        assertEquals(1_000L, plan(outro(120f, bar = 0), intro(120f, bar = 1), sungUntil = 165_000).inMs)
    }

    @Test fun aTitleOnRepeatIsMixedIntoItsOwnStart() {
        assertEquals("without the setting, repeat is left alone", MixKind.None, plan(outro(120f), intro(120f), MixContext(repeatOne = true)).kind)
        val loop = plan(outro(120f), intro(120f), MixContext(repeatOne = true, loop = true), sungUntil = 170_000)
        assertEquals(MixKind.Beatmatch, loop.kind)
        assertTrue(loop.loop); assertEquals(1f, loop.rate, 0f)
        assertEquals("a title over itself overlaps for 16 beats at most", 8_000L, loop.overlapMs)
        assertEquals("en boucle · calé sur le tempo", MixWords.describe(loop))
        assertEquals("calé sur le tempo", MixWords.describe(loop.copy(loop = false)))
        // A loop that ends on a last note restarts on the beat after it.
        val hard = plan(outro(120f, hard = true), intro(120f), MixContext(repeatOne = true, loop = true))
        assertEquals(MixKind.Trim, hard.kind); assertTrue(hard.loop && hard.onBeat)
    }

    @Test fun keysThatClashAreNotLeftToOverlapForLong() {
        // C major into F sharp major: as far apart as two keys get.
        val clash = plan(outro(120f, key = 0), intro(120f, key = 6), sungUntil = 165_000)
        assertEquals(MixKind.Beatmatch, clash.kind)
        assertTrue(clash.clash)
        assertEquals("16 beats instead of 64", 8_000L, clash.overlapMs)
        // C major into G major, or into A minor: neighbours, the full length.
        for (friend in listOf(7, 12 + 9, 0)) {
            val plan = plan(outro(120f, key = 0), intro(120f, key = friend), sungUntil = 165_000)
            assertFalse(plan.clash); assertEquals(32_000L, plan.overlapMs)
        }
        // A key that was not read, or that the music does not give, is no reason to shorten anything.
        assertFalse(plan(outro(120f, key = 0), intro(120f, key = MusicalKey.NONE), sungUntil = 165_000).clash)
        assertFalse(plan(outro(120f), intro(120f, key = 6), sungUntil = 165_000).clash)
        // What was measured travels with the plan, for the status line.
        assertEquals(120f, clash.fromBpm, 0f); assertEquals(0, clash.fromKey); assertEquals(6, clash.toKey)
    }

    @Test fun twoTransitionsInARowDoNotSoundAlike() {
        val first = plan(outro(120f), intro(120f), sungUntil = 170_000)
        assertEquals(0, first.variant)
        val second = plan(outro(120f), intro(120f), MixContext(lastVariant = first.variant), sungUntil = 170_000)
        assertEquals(1, second.variant)
        assertEquals(0, plan(outro(120f), intro(120f), MixContext(lastVariant = 1), sungUntil = 170_000).variant)
        val faded = plan(outro(0f, fall = 187_000, fade = true, confidence = 0f), intro(0f, confidence = 0f), MixContext(lastVariant = 0))
        assertEquals(MixKind.Filter, faded.kind); assertEquals(1, faded.variant)
    }

    @Test fun tooLateForTheBeatATransitionThatNeedsNoneIsPlanned() {
        // The same two titles that would be played in time: without their beats, a crossing of filters at the very end.
        val late = plan(outro(120f), intro(120f), MixContext(beats = false), sungUntil = 170_000)
        assertEquals(MixKind.Filter, late.kind); assertFalse(late.beatTied)
        assertEquals("at the end of the title", 199_000L, late.outMs + late.overlapMs)
        assertEquals(MixKind.Filter, plan(outro(120f), intro(150f), MixContext(beats = false), sungUntil = 170_000).kind)
        val trim = plan(outro(120f, hard = true), intro(120f), MixContext(beats = false))
        assertEquals(MixKind.Trim, trim.kind); assertFalse(trim.onBeat)
    }

    @Test fun withoutTheDecksProcessorsOnlyATrimOrAPlainFadeIsPlanned() {
        val plain = MixContext(effects = false)
        assertEquals(MixKind.Crossfade, plan(outro(120f), intro(120f), plain, sungUntil = 170_000).kind)
        assertEquals(MixKind.Crossfade, plan(outro(120f), intro(150f), plain, sungUntil = 170_000).kind)
        assertEquals(MixKind.Crossfade, plan(outro(0f, confidence = 0f), intro(0f, confidence = 0f), plain).kind)
        val trim = plan(outro(120f, hard = true), intro(120f), plain)
        assertEquals(MixKind.Trim, trim.kind); assertFalse(trim.onBeat)
    }

    @Test fun theOverlapShrinksToTheRoomLeftAfterTheSinging() {
        // Singing until 12 s before the end: even two bars under the last words, 32 beats (16 s) do not fit, 16 do.
        val plan = plan(outro(120f), intro(120f), sungUntil = 187_000)
        assertEquals(MixKind.Beatmatch, plan.kind)
        assertEquals(8_000L, plan.overlapMs)
        assertTrue(plan.outMs >= 187_300 - 4_000)
        // Singing until the very end: 8 beats under the last words, the voice stepping back; discreet waits for the end of the singing.
        val late = plan(outro(120f), intro(120f), sungUntil = 198_000)
        assertEquals(MixKind.Beatmatch, late.kind)
        assertEquals(4_000L, late.overlapMs); assertTrue(late.sungUntilMs > late.outMs)
        assertEquals(MixKind.Filter, plan(outro(120f), intro(120f), intensity = 0, sungUntil = 198_000).kind)
    }

    @Test fun howMuchOfAnInstrumentalEndingIsSkippedDependsOnTheIntensity() {
        val discreet = plan(outro(120f), intro(120f), intensity = 0, sungUntil = 160_000)
        val balanced = plan(outro(120f), intro(120f), intensity = 1, sungUntil = 160_000)
        val dj = plan(outro(120f), intro(120f), intensity = 2, sungUntil = 160_000)
        assertEquals("Discreet plays the ending to its last bar", 199_000L, discreet.outMs + discreet.overlapMs)
        assertEquals("32 beats at most when discreet", 16_000L, discreet.overlapMs)
        assertEquals(0L, MixSettings(0).skippableOutroMs)
        assertTrue("Balanced leaves out 16 s of an instrumental ending at most", balanced.outMs + balanced.overlapMs >= 199_000 - 16_000)
        assertTrue("DJ mixes from four bars before the singing ends, here from the start of what was read (${dj.outMs})", dj.outMs in 155_000..156_000)
        assertEquals("DJ mixes over 64 beats", 32_000L, dj.overlapMs)
    }

    @Test fun withoutLyricsTheMixWaitsForTheOutro() {
        val plan = plan(outro(120f, fall = 181_000), intro(120f), intensity = 2)
        assertEquals(MixKind.Beatmatch, plan.kind)
        assertTrue("From where the level drops (${plan.outMs})", plan.outMs in 181_000..181_600)
        // An incoming title that sings at once is held back to a shorter overlap, then to a crossfade.
        val early = plan(outro(120f, fall = 181_000), intro(120f), sungFrom = 5_000)
        assertEquals(MixKind.Beatmatch, early.kind)
        assertEquals("8 beats: the voice arrives when the other title is nearly gone", 4_000L, early.overlapMs)
        // One that sings at once is not blended at all: the outgoing title leaves on an echo, on the beat.
        assertEquals(MixKind.Echo, plan(outro(120f, fall = 181_000), intro(120f), sungFrom = 1_200).kind)
        assertEquals(MixKind.Filter, plan(outro(120f, fall = 181_000), intro(120f), intensity = 0, sungFrom = 1_200).kind)
    }

    @Test fun aLastNoteAtFullLevelRingsOnPastTheEnd() {
        // Not discreet: the next title starts where a trim starts it, the last note plays whole and its echo
        // rings for four beats past the end of the title.
        val rung = plan(outro(120f, hard = true), intro(150f))
        assertEquals(MixKind.Echo, rung.kind)
        assertTrue(rung.tail && rung.onBeat)
        assertEquals("the title's own sound is cut after its last note", maxOf(199_000L, rung.outMs), rung.cutMs)
        assertTrue("the next title starts within a beat of the last note (${rung.outMs})", rung.outMs in 198_500..199_300)
        assertEquals("four beats of echo after the cut", rung.cutMs + 2_000L, rung.outMs + rung.overlapMs)
        // A beat read without full confidence (41 %) still gives the echo its length; none at all, 120 BPM.
        val unsure = plan(outro(130f, hard = true, confidence = .41f), intro(148f, confidence = .21f))
        assertEquals(MixKind.Echo, unsure.kind); assertEquals(60_000f / 130f, unsure.beatMs, .5f)
        val none = plan(outro(0f, hard = true, confidence = 0f), intro(0f, confidence = 0f))
        assertEquals(MixKind.Echo, none.kind); assertEquals(500f, none.beatMs, 0f)
        // The echo is played out on silence by the outgoing deck: within what it may ring past the end.
        assertTrue(rung.outMs + rung.overlapMs <= duration + 6_000L)
        // A title on repeat restarts on the beat after its last note, without an echo over itself.
        assertEquals(MixKind.Trim, plan(outro(120f, hard = true), intro(120f), MixContext(repeatOne = true, loop = true)).kind)
    }

    @Test fun aLastNoteAtFullLevelIsNeverCovered() {
        val plan = plan(outro(120f, hard = true), intro(0f, confidence = 0f), intensity = 0)
        assertEquals(MixKind.Trim, plan.kind)
        assertTrue(plan.overlapMs <= 300)
        assertTrue("The next title starts as the last note ends", abs(plan.outMs + plan.overlapMs / 2 - 199_000) <= 10)
        assertEquals(260L, plan.inMs)
        assertFalse(plan.onBeat)
        // Both have a beat: the first beat of the next title (at 500 ms) lands on the beat that follows the last note.
        val timed = plan(outro(120f, hard = true), intro(150f), intensity = 0)
        assertEquals(MixKind.Trim, timed.kind)
        assertTrue(timed.onBeat)
        assertTrue("on the outgoing title's grid", onBeat(timed.outMs + (500 - timed.inMs), 155_000.0, 120f))
        assertTrue("within a beat of the last note (${timed.outMs})", timed.outMs in 198_500..199_300)
        // Same tempo but singing until a hard ending: still no mix over it.
        assertEquals(MixKind.Trim, plan(outro(120f, hard = true), intro(120f), intensity = 0, sungUntil = 198_500).kind)
        assertEquals("rung out otherwise", MixKind.Echo, plan(outro(120f, hard = true), intro(120f), sungUntil = 198_500).kind)
    }

    @Test fun aNextTitleThatDoesNotSingYetRisesUnderTheLastSungWords() {
        // 120 vs 188 BPM and no beat in the next intro; the outgoing title sings to 190 s of a 191 s end.
        val a = outro(120f, fall = 191_000, end = 191_000, confidence = .89f)
        val b = intro(0f, confidence = 0f)
        val alone = plan(a, b, sungUntil = 190_000)
        assertEquals("without knowing when the next title sings: 2.5 s", 2_500L, alone.overlapMs)
        val under = plan(a, b, sungUntil = 190_000, sungFrom = 27_000)
        assertEquals(MixKind.Filter, under.kind)
        assertEquals("0.7 s after the singing plus 6 s under it", 6_700L, under.overlapMs)
        assertEquals("the outgoing voice steps back", 190_300L, under.sungUntilMs)
        assertEquals("DJ: 10 s under it", 10_700L, plan(a, b, intensity = 2, sungUntil = 190_000, sungFrom = 27_000).overlapMs)
        assertEquals("discreet waits for the end of the singing", 2_500L, plan(a, b, intensity = 0, sungUntil = 190_000, sungFrom = 27_000).overlapMs)
        // A next title that sings soon is not brought in under the outgoing voice for longer than its own silence.
        assertEquals(3_500L, plan(a, b, sungUntil = 190_000, sungFrom = 5_300).overlapMs)
        // The voice steps back on the outgoing deck.
        val automation = DeckAutomation.outgoing(under)
        assertTrue(automation.usesVocalCut)
        assertEquals(0f, automation.vocalCut.at(under.outMs * 1000L), 1e-3f)
        assertEquals(DeckAutomation.VOICE_DOWN, automation.vocalCut.at((under.outMs + 3_000L) * 1000L), 1e-3f)
    }

    @Test fun aFadeOutIsCrossedWhileItIsStillHeard() {
        val plan = plan(outro(0f, fall = 187_000, end = 199_000, fade = true, confidence = 0f), intro(0f, confidence = 0f))
        assertEquals("Not a plain fade: the two cross by their spectra", MixKind.Filter, plan.kind)
        assertEquals("The incoming title is fully in before the last, silent quarter of the fade", 196_000L, plan.outMs + plan.overlapMs)
        assertEquals("The fade is only 12 s long: the crossing keeps to it", 8_000L, plan.overlapMs)
        assertEquals("While the title fades by itself its level is left alone", .55f, plan.hold, 0f)
        assertEquals(300L, plan.inMs)
        assertEquals("no steady beat", plan.reason)
    }

    @Test fun twoVoicesDoNotOverlapForLong() {
        val calm = plan(outro(0f, confidence = 0f), intro(0f, confidence = 0f), intensity = 2, sungUntil = 180_000, sungFrom = 600)
        assertEquals("A title without a beat dissolves", MixKind.Wash, calm.kind)
        assertEquals("The ending is instrumental: the long crossing stays", 11_000L, calm.overlapMs)
        val clash = plan(outro(0f, confidence = 0f), intro(0f, confidence = 0f), intensity = 2, sungUntil = 198_000, sungFrom = 600)
        assertEquals(2_500L, clash.overlapMs)
    }

    @Test fun aLouderIncomingTitleIsHeldBackNotBoosted() {
        assertEquals(.5f, plan(outro(loudness = -20f), intro(loudness = -8f), sungUntil = 170_000).gain, 1e-3f)
        assertEquals(1f, plan(outro(loudness = -8f), intro(loudness = -20f), sungUntil = 170_000).gain, 0f)
        assertEquals(.708f, plan(outro(loudness = -14f), intro(loudness = -11f), sungUntil = 170_000).gain, 2e-3f)
    }

    @Test fun barsMeetWhenBothAreKnown() {
        // Outgoing bars start on beats 2, 6, 10… of its grid; the incoming title enters on the third beat of a bar.
        val plan = plan(outro(120f, bar = 2), intro(120f, bar = 2), sungUntil = 170_000)
        val outBeat = Math.round((plan.outMs - 155_000.0) / 500.0)
        assertEquals("Outgoing beat is the same beat of its bar as the incoming one", ((0 - 2) % 4 + 4) % 4, (((outBeat - 2) % 4) + 4).toInt() % 4)
    }

    @Test fun withoutAnalysisAShortCrossfadeStillWorks() {
        val plan = plan(null, null)
        assertEquals(MixKind.Crossfade, plan.kind)
        assertEquals(5_333L, plan.overlapMs)
        assertEquals(0L, plan.inMs)
        assertEquals(duration - 250, plan.outMs + plan.overlapMs)
        assertEquals(MixKind.Crossfade, plan(outro(), null).kind)
    }

    @Test fun skipSilenceKeepsOffTheBeat() {
        // Skipping silences moves the positions the beats were measured at: nothing is tied to them.
        val plan = plan(outro(120f), intro(120f), MixContext(skipSilence = true), sungUntil = 170_000)
        assertEquals(MixKind.Filter, plan.kind); assertFalse(plan.beatTied)
        assertFalse(plan(outro(120f, hard = true), intro(120f), MixContext(skipSilence = true)).onBeat)
    }

    @Test fun singingIsReadFromSynchronisedLyrics() {
        val lines = listOf(MusicApi.LyricLine(-1, "Untimed"), MusicApi.LyricLine(4_000, "♪"), MusicApi.LyricLine(12_000, "First words"),
            MusicApi.LyricLine(150_000, "the very last line here", listOf(MusicApi.LyricWord(150_000, "the"), MusicApi.LyricWord(152_400, "here", 153_100))),
            MusicApi.LyricLine(160_000, "…"))
        assertEquals(12_000L, Vocals.startMs(lines))
        assertEquals(153_100L, Vocals.endMs(lines, 200_000))
        val plain = listOf(MusicApi.LyricLine(100_000, "four words in it"))
        assertEquals(100_000L + 1_500 + 4 * 320, Vocals.endMs(plain, 200_000))
        assertEquals("Never past the end", 101_000L, Vocals.endMs(plain, 101_000))
        assertNull(Vocals.startMs(listOf(MusicApi.LyricLine(-1, "plain text"))))
        assertNull(Vocals.endMs(emptyList(), 1))
    }

    @Test fun anAlbumWithNoGapBetweenItsTitlesIsLeftAlone() {
        val abrupt = outro(120f, hard = true, end = 199_900)
        val immediate = intro(120f, start = 20, rise = 200)
        val joined = plan(abrupt, immediate, MixContext(sameAlbumInOrder = true))
        assertEquals(MixKind.None, joined.kind); assertEquals("gapless album", joined.reason)
        // The same two titles in a playlist, not as neighbours of an album, are mixed as any others.
        assertNotEquals(MixKind.None, plan(abrupt, immediate, MixContext(sameAlbumInOrder = false), sungUntil = 190_000).kind)
        // An album whose titles are separated by a little silence is mixed.
        assertNotEquals(MixKind.None, plan(outro(120f, hard = true, end = 197_000), intro(), MixContext(sameAlbumInOrder = true)).kind)
    }

    @Test fun withoutLyricsALongMixNeverStartsBeforeTheOutro() {
        // 64 beats (32 s) would start in the body of the title: the planner goes down to a count that fits the outro.
        val plan = plan(outro(120f, fall = 181_000), intro(120f), intensity = 2)
        assertEquals(MixKind.Beatmatch, plan.kind)
        assertTrue("starts in the outro (${plan.outMs})", plan.outMs >= 181_000)
        assertEquals(16_000L, plan.overlapMs)
    }

    @Test fun theListenerCanAskForOneStyleOfTransition() {
        fun styled(style: MixStyle, a: SegmentAnalysis = outro(120f), b: SegmentAnalysis = intro(120f), sung: Long? = 170_000) =
            TransitionPlanner.plan(MixFacts(duration, a, vocalEndMs = sung), MixFacts(duration, b), MixContext(), MixSettings(1, style))
        assertEquals(MixKind.Beatmatch, styled(MixStyle.Auto).kind)
        assertEquals(MixKind.Beatmatch, styled(MixStyle.Tempo).kind)
        // Matched to the tempo never leaves on an echo: a crossing of filters when the tempos are too far apart.
        assertEquals(MixKind.Filter, styled(MixStyle.Tempo, b = intro(150f)).kind)
        assertEquals(MixKind.Echo, styled(MixStyle.Echo).kind)
        assertEquals(MixKind.Filter, styled(MixStyle.Filters).kind)
        assertEquals("filters, even for music without a beat", MixKind.Filter,
            styled(MixStyle.Filters, outro(0f, confidence = 0f), intro(0f, confidence = 0f), null).kind)
        assertEquals(MixKind.Crossfade, styled(MixStyle.Fade).kind)
        // An echo needs a beat: without one, the automatic choice is made.
        assertEquals(MixKind.Wash, styled(MixStyle.Echo, outro(0f, confidence = 0f), intro(0f, confidence = 0f), null).kind)
        // Whatever the style, a last note is never covered and singing is never mixed over.
        assertEquals(MixKind.Trim, styled(MixStyle.Fade, a = outro(120f, hard = true)).kind)
        assertTrue(styled(MixStyle.Echo, sung = 196_000).let { it.kind != MixKind.Echo })
        assertEquals(MixStyle.Auto, MixStyle.of(9)); assertEquals(MixStyle.Fade, MixStyle.of(4))
    }

    @Test fun theMomentTheBassChangesHandsIsKnownForTheStrip() {
        val blend = plan(outro(120f), intro(120f), sungUntil = 170_000)
        val share = MixWords.swapShare(blend)
        assertEquals((blend.swapMs - blend.outMs).toFloat() / blend.overlapMs, share, 1e-4f)
        assertTrue(share in .4f..0.6f)
        assertEquals(0f, MixWords.swapShare(plan(outro(120f), intro(150f), sungUntil = 170_000)), 0f)
        assertEquals(-1f, MixWords.swapShare(plan(outro(0f, confidence = 0f), intro(0f, confidence = 0f))), 0f)
        assertEquals(-1f, MixWords.swapShare(MixPlan(MixKind.None)), 0f)
    }
}
