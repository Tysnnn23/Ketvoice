package com.ketvoice.app.automix

import org.junit.Assert.*
import org.junit.Test

class KetmixFiveElevenTest {
    private val blend = MixPlan(MixKind.Beatmatch, outMs = 180_000, inMs = 500, overlapMs = 16_000, rate = 1f,
        swapMs = 188_000, gain = .9f, beatMs = 500f, fromKey = 3, toKey = 3, voiceFromMs = 3_000)

    @Test fun mashupNeedsTempoKeysAndAnEarlyVoice() {
        assertTrue(Mashup.fits(blend))
        assertFalse(Mashup.fits(blend.copy(clash = true)))
        assertFalse(Mashup.fits(blend.copy(fromKey = MusicalKey.UNREAD)))
        assertFalse(Mashup.fits(blend.copy(voiceFromMs = -1)))
        // A voice that only sings after the blend would leave the listener with an instrumental thinning out.
        assertFalse(Mashup.fits(blend.copy(voiceFromMs = 12_000)))
        assertFalse(Mashup.fits(blend.copy(kind = MixKind.Filter)))
        assertFalse(Mashup.fits(blend.copy(loop = true)))
    }

    @Test fun mashupPlaysTheIncomingVoiceAloneUntilTheBassChangesHands() {
        val plan = blend.copy(stems = true, mashup = true)
        val incoming = DeckAutomation.incoming(plan)
        val outgoing = DeckAutomation.outgoing(plan)
        assertEquals(1f, incoming.vocalSolo.at(2_000_000), 1e-3f)
        assertEquals(1f, incoming.vocalSolo.at(6_000_000), 1e-3f)
        // The swap is 8 s into the blend: from there the whole title plays.
        assertEquals(0f, incoming.vocalSolo.at(10_500_000), 1e-3f)
        assertFalse(incoming.usesVocalCut)
        // The outgoing title is its instrumental for the whole blend.
        assertEquals(1f, outgoing.vocalCut.at(181_000_000), 1e-3f)
        assertEquals(1f, outgoing.vocalCut.at(195_000_000), 1e-3f)
        // Without the flag nothing of it happens.
        assertFalse(DeckAutomation.incoming(blend.copy(stems = true)).usesVocalSolo)
    }

    @Test fun voiceFactsTakeTheCautiousSide() {
        assertEquals(200_000L, VoiceFacts.end(lyrics = 190_000L, separated = 200_000L))
        assertEquals(190_000L, VoiceFacts.end(lyrics = 190_000L, separated = null))
        assertEquals(4_000L, VoiceFacts.start(lyrics = 9_000L, separated = 4_000L))
        assertNull(VoiceFacts.start(null, null))
    }

    private fun measured(bpm: Float, key: Int) = SegmentAnalysis(startMs = 0, lengthMs = 40_000, bpm = bpm, confidence = .9f, key = key)

    @Test fun smartQueuePicksTheTitleThatGoesBest() {
        val out = measured(120f, 0)
        // The next title is far in tempo and clashes; the third is a match.
        assertEquals(2, SmartQueue.pick(out, listOf(measured(97f, 6), null, measured(121f, 0), measured(140f, 0))))
        // Already a good match next: nothing moves for a marginal gain.
        assertEquals(0, SmartQueue.pick(out, listOf(measured(122f, 0), measured(120f, 0))))
        // Nothing measured: nothing moves.
        assertEquals(0, SmartQueue.pick(out, listOf(null, null)))
        // An unmeasured next title only gives way to a real match.
        assertEquals(1, SmartQueue.pick(out, listOf(null, measured(120f, 0))))
        assertEquals(0, SmartQueue.pick(out, listOf(null, measured(104f, 6))))
        // Half and double tempo are the same pulse.
        assertEquals(1, SmartQueue.pick(out, listOf(measured(100f, 0), measured(60f, 0))))
    }

    @Test fun analysisIsFoundUnderANeighbouringSecond() {
        AnalysisStore.put("near-test", 200_950, false, SegmentAnalysis(startMs = 160_000, lengthMs = 40_900, bpm = 120f, confidence = .9f))
        assertNotNull(AnalysisStore.outro("near-test", 201_010))
        assertNull(AnalysisStore.outro("near-test", 203_500))
    }
}
