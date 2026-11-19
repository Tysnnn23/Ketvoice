package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test

/** 7.2 « Pour toi »: each rule of the score, then the order it gives. */
class RecommenderTest {
    private fun t(id: String, artist: String, title: String = id) = Track(id, title, artist, "", durationMs = 200_000)
    private fun skip(n: Int, artist: String) = TasteSignals.Skip(n, 1_000L, SearchRank.mainArtist(artist))

    private val known = listOf(t("d1", "Damso"), t("d2", "Damso"), t("n1", "Ninho"), t("j1", "Jul"))
    private val base = Recommender.Signals(plays = mapOf("d1" to 20, "d2" to 5, "n1" to 4, "j1" to 1), known = known)

    @Test fun theMostPlayedArtistHasTheHighestAffinityAndSkipsCountAgainst() {
        val aff = Recommender.affinity(base)
        assertEquals(1.0, aff.getValue("damso"), 1e-9)
        assertTrue(aff.getValue("ninho") > aff.getValue("jul"))
        val refused = Recommender.affinity(base.copy(skips = mapOf("x" to skip(4, "Jul"))))
        assertTrue("four early skips outweigh one play", refused.getValue("jul") < 0)
    }

    @Test fun eachReasonIsExplained() {
        val s = Recommender.score(t("d3", "Damso"), base.copy(liked = setOf("d3"), recent = listOf("d3")))
        assertEquals(setOf("artist", "liked", "heard-now"), s.why.map { it.first }.toSet())
        assertEquals(s.why.sumOf { it.second }, s.score, 1e-9)
    }

    @Test fun aLikedArtistComesFirstAndARefusedTitleLast() {
        val candidates = listOf(t("x1", "Unknown"), t("n9", "Ninho"), t("d9", "Damso"), t("s1", "Damso", "Skipped one"))
        val signals = base.copy(skips = mapOf("s1" to skip(3, "Damso")))
        val order = Recommender.rank(candidates, signals).map { it.track.id }
        assertEquals("d9", order.first())
        assertEquals("s1", order.last())
        assertTrue(order.indexOf("n9") < order.indexOf("x1"))
    }

    @Test fun whatWasJustHeardWaits() {
        val order = Recommender.rank(listOf(t("d1", "Damso"), t("d2", "Damso")), base.copy(recent = listOf("d1"))).map { it.track.id }
        assertEquals(listOf("d2", "d1"), order)
    }

    @Test fun blockedArtistsNeverAppearAndDuplicatesAreDropped() {
        val candidates = listOf(t("d1", "Damso"), t("yt:d1", "Damso", "d1"), t("b1", "Blocked Guy"))
        val signals = base.copy(blocked = setOf(MediaOverrides.normalize("Blocked Guy")))
        assertEquals(listOf("d1"), Recommender.rank(candidates, signals).map { it.track.id })
    }

    @Test fun oneArtistDoesNotFillTheRow() {
        val many = (1..8).map { t("dd$it", "Damso") } + listOf(t("n5", "Ninho"), t("j5", "Jul"))
        val order = Recommender.rank(many, base, limit = 6, perArtist = 3).map { it.track.artist }
        assertEquals(3, order.count { it == "Damso" })
        assertTrue("Ninho" in order && "Jul" in order)
        // Variety: the second artist comes before the third title of the first one.
        assertTrue(order.indexOf("Ninho") < order.lastIndexOf("Damso"))
    }

    @Test fun onlyAnEarlyManualSkipCounts() {
        assertTrue(TasteSignals.isEarly(5_000, 200_000))
        assertFalse("past 30 s", TasteSignals.isEarly(45_000, 200_000))
        assertFalse("past a third of a short title", TasteSignals.isEarly(25_000, 60_000))
        val once = TasteSignals.withSkip(emptyMap(), t("a", "Jul"), now = 10)
        val twice = TasteSignals.withSkip(once, t("a", "Jul"), now = 20)
        assertEquals(2, twice.getValue("a").count)
        assertEquals("jul", twice.getValue("a").artist)
        val back = TasteSignals.decode(TasteSignals.encode(twice), now = 30)
        assertEquals(twice, back)
        assertTrue("forgotten after 60 days", TasteSignals.decode(TasteSignals.encode(twice), now = 61L * 24 * 3600 * 1000).isEmpty())
    }

    @Test fun topArtistsLoseWhatTheListenerSkipped() {
        val counts = mapOf("d1" to 3, "n1" to 2)
        val plain = PersonalMixes.topArtists(known, counts, emptySet()).map { it.name }
        assertEquals("Damso", plain.first())
        val skipped = PersonalMixes.topArtists(known, counts, emptySet(), mapOf("d1" to skip(2, "Damso"))).map { it.name }
        assertEquals("Ninho", skipped.first())
    }
}
