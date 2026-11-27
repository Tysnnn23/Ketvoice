package com.ketvoice.app.tv

import com.ketvoice.app.automix.AnalysisDepth
import com.ketvoice.app.automix.AnalysisStore
import com.ketvoice.app.automix.SegmentAnalysis
import com.ketvoice.app.data.Track
import com.ketvoice.app.stems.ModelPack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** « Mixer cette playlist » on the TV (bounded preparation, gentle order) and the rhythm model's offer by tier. */
class TvKetmixTest {
    private fun tracks(vararg bpm: Int) = bpm.mapIndexed { i, _ -> Track("qa:mix$i-${bpm.contentHashCode()}", "Titre $i", "Artiste", "Album", durationMs = 200_000L + i * 1_000L) }

    private fun measure(track: Track, bpm: Float) =
        AnalysisStore.put(track.id, track.durationMs, true, SegmentAnalysis(startMs = 0, lengthMs = 60_000, bpm = bpm, confidence = .9f, key = 0))

    @Test fun aPlaylistIsMeasuredWithinItsBudgetThenGentlyReordered() = runBlocking {
        val bpms = intArrayOf(120, 90, 121, 95, 122, 88, 124, 93, 119, 97, 123, 91)
        val list = tracks(*bpms)
        val asked = mutableListOf<String>()
        val ordered = TvPlaylistMix.prepare(list, { t -> asked += t.id; measure(t, bpms[list.indexOf(t)].toFloat()); true },
            AnalysisDepth.Balanced, { 0L })
        // A balanced analysis measures six titles ahead, never the whole list.
        assertEquals(list.take(6).map { it.id }, asked)
        assertEquals(list.first(), ordered.first())
        assertEquals(list.toSet(), ordered.toSet())
        // 120 is followed by 121 and 122, not by 90.
        assertEquals(listOf(list[0], list[2], list[4]), ordered.take(3))
        val state = TvPlaylistMix.state.value as TvPlaylistMix.State.Preparing
        assertEquals("Préparation du mix… 6/6", TvPlaylistMix.progressLine(state))
    }

    @Test fun titlesAlreadyMeasuredAreNotReadAgainAndNoPlayerStopsTheReading() = runBlocking {
        val list = tracks(100, 101, 102, 103, 104, 105, 106, 107)
        measure(list[0], 100f); measure(list[1], 101f)
        val asked = mutableListOf<String>()
        TvPlaylistMix.prepare(list, { t -> asked += t.id; false }, AnalysisDepth.Balanced, { 0L })
        // The first two were known; the third found no player, so the list plays at once with what is known.
        assertEquals(listOf(list[2].id), asked)
    }

    @Test fun preparationNeverRunsPastItsTimeLimit() = runBlocking {
        val list = tracks(100, 101, 102, 103, 104, 105)
        var now = 0L
        val asked = mutableListOf<String>()
        TvPlaylistMix.prepare(list, { t -> asked += t.id; now += TvPlaylistMix.MAX_MS; true }, AnalysisDepth.Maximum, { now })
        assertEquals(listOf(list[0].id, list[1].id), asked)
    }

    @Test fun theRhythmModelIsOfferedOnPowerfulTvsOnly() {
        assertTrue(TvModelOffer.offered(TvTier.Full))
        assertFalse(TvModelOffer.offered(TvTier.Balanced))
        assertFalse(TvModelOffer.offered(TvTier.Light))
        // Suggested once, while it is not there.
        assertTrue(TvModelOffer.suggest(TvTier.Full, bundled = false, ModelPack.State.Missing, alreadySuggested = false))
        assertFalse(TvModelOffer.suggest(TvTier.Full, bundled = false, ModelPack.State.Missing, alreadySuggested = true))
        assertFalse(TvModelOffer.suggest(TvTier.Full, bundled = false, ModelPack.State.Ready, alreadySuggested = false))
        assertFalse(TvModelOffer.suggest(TvTier.Full, bundled = false, ModelPack.State.Downloading(.4f), alreadySuggested = false))
        assertFalse(TvModelOffer.suggest(TvTier.Full, bundled = true, ModelPack.State.Missing, alreadySuggested = false))
        assertFalse(TvModelOffer.suggest(TvTier.Balanced, bundled = false, ModelPack.State.Missing, alreadySuggested = false))
        assertFalse(TvModelOffer.suggest(TvTier.Light, bundled = false, ModelPack.State.Missing, alreadySuggested = false))
        assertEquals("Prêt", TvModelOffer.value(ModelPack.State.Ready))
        assertEquals("Télécharger", TvModelOffer.value(ModelPack.State.Missing))
        assertTrue(TvModelOffer.value(ModelPack.State.Downloading(.42f)).endsWith("42 %"))
        assertEquals("18 Mo", TvModelOffer.size(18_600_000L))
        // A powerful TV also starts on the deeper analysis; the others stay light.
        assertEquals(AnalysisDepth.Maximum, AnalysisDepth.of(TvTier.Full.ketmixAnalysis))
        assertEquals(AnalysisDepth.Light, AnalysisDepth.of(TvTier.Light.ketmixAnalysis))
    }
}
