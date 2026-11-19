package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test

/** 7.2 search relevance: a fixed set of results from several sources, in the order they arrived. */
class SearchRankTest {
    private fun t(id: String, title: String, artist: String, album: String = "", ms: Long = 200_000, isrc: String? = null) =
        Track(id, title, artist, album, durationMs = ms, isrc = isrc)

    // As the sources answered: YouTube first, then the API, then a custom source.
    private val arrived = listOf(
        t("yt:a", "Halo (Karaoke Version)", "Sing King"),
        t("yt:b", "Halo Theme", "Halo Orchestra"),
        t("yt:c", "Single Ladies", "Beyoncé"),
        t("yt:d", "Halo", "Beyoncé", "I Am... Sasha Fierce", 261_000),
        t("um:e", "Halo", "Beyonce", "I Am... Sasha Fierce", 261_500),
        t("yt:f", "Halo (Slowed)", "Beyoncé"),
        t("yt:g", "Halo - Live", "Beyoncé"),
        t("cs:h", "Djadja", "Aya Nakamura"),
        t("cs:i", "Pookie", "Aya Nakamura"),
        t("yt:j", "Copines", "Aya Nakamura"),
        t("yt:k", "Ayaya", "Some DJ"),
        t("yt:l", "Bande organisée", "Jul"),
    )

    private fun ids(query: String, taste: SearchRank.Taste = SearchRank.Taste()) = SearchRank.rank(query, arrived, taste).map { it.id }

    @Test fun anExactTitleComesFirstAndUnaskedVersionsGoAfterTheOriginal() {
        val order = ids("halo")
        assertEquals(setOf("yt:d", "um:e"), order.take(2).toSet())
        assertTrue("karaoke after the original", order.indexOf("yt:a") > order.indexOf("um:e"))
        assertTrue("slowed after the original", order.indexOf("yt:f") > order.indexOf("um:e"))
        assertTrue("a title that does not contain the query goes last", order.indexOf("yt:c") > order.indexOf("yt:a"))
    }

    @Test fun titleAndArtistTogetherFindTheRecording() {
        assertEquals("yt:d", ids("beyonce halo").first())
        assertEquals("yt:d", ids("Halo Beyoncé").first())
    }

    @Test fun accentsAndCaseAreIgnored() {
        assertEquals("yt:l", ids("BANDE ORGANISEE").first())
        assertEquals(SearchRank.fold("Beyoncé – Halo!"), "beyonce halo")
    }

    @Test fun anArtistQueryBringsTheArtistsTitlesAndIsRecognised() {
        val order = ids("aya nakamura")
        assertEquals(setOf("cs:h", "cs:i", "yt:j"), order.take(3).toSet())
        assertTrue(SearchRank.namesArtist("Aya Nakamura", SearchRank.rank("aya nakamura", arrived)))
        assertFalse(SearchRank.namesArtist("djadja", SearchRank.rank("djadja", arrived)))
        val artists = listOf("Aya Nakamura Fans", "DJ Aya", "Aya Nakamura")
        assertEquals("Aya Nakamura", SearchRank.artistsFirst("aya nakamura", artists) { it }.first())
    }

    @Test fun shortQueriesTolerateOneTypo() {
        assertEquals("cs:h", ids("djaja").first())
        assertEquals("cs:i", ids("pooki").first())
        assertEquals("yt:j", ids("copnies").first())
        assertTrue(SearchRank.distance("copnies", "copines", 1) <= 1)
        assertTrue(SearchRank.distance("abc", "xyz", 1) > 1)
    }

    @Test fun whatTheListenerPlaysAndLikesIsLiftedAmongEqualMatches() {
        assertEquals("yt:d", ids("halo").first())
        val taste = SearchRank.Taste(plays = mapOf("um:e" to 12), liked = setOf("um:e"))
        assertEquals("um:e", ids("halo", taste).first())
        // Taste never lifts a poor text match above an exact one.
        val fan = SearchRank.Taste(plays = mapOf("yt:c" to 50), liked = setOf("yt:c"))
        assertTrue(ids("halo", fan).indexOf("yt:c") > 1)
    }

    @Test fun theSameRecordingFromTwoSourcesIsOneResult() {
        val groups = SearchGroups.group(SearchRank.rank("halo", arrived))
        val halo = groups.first()
        assertEquals(listOf("yt:d", "um:e"), halo.tracks.map { it.id })
        assertEquals("live and slowed versions stay their own results", arrived.size - 1, groups.size)
    }
}
