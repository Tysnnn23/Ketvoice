package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test

class PersonalMixesTest {
    private fun track(id: String, artist: String, title: String = "Title $id", cover: String? = "https://img/$id") =
        Track(id, title, artist, "Album", coverUrl = cover)

    private val known = (1..6).map { track("a$it", "Alpha") } + (1..5).map { track("b$it", "Beta, Guest") } +
        (1..4).map { track("c$it", "Gamma feat. Someone") } + (1..12).map { track("x$it", "Solo $it") }
    private val counts = known.associate { it.id to when {
        it.id.startsWith("a") -> 5; it.id.startsWith("b") -> 3; it.id.startsWith("c") -> 2; else -> 1 } }

    @Test fun nothingIsProposedBeforeEnoughMusicWasPlayed() {
        val few = known.take(3)
        assertTrue(PersonalMixes.propose(few, few, few.associate { it.id to 3 }, emptySet(), seed = 1).isEmpty())
        assertTrue(PersonalMixes.propose(emptyList(), emptyList(), emptyMap(), emptySet(), seed = 1).isEmpty())
    }

    @Test fun theFavouriteArtistAlwaysLeadsAndCardsAreCapped() {
        for (seed in 1L..20L) {
            val cards = PersonalMixes.propose(known, known.takeLast(5), counts, emptySet(), seed)
            assertTrue(cards.size in 1..PersonalMixes.MAX_CARDS)
            assertEquals(MixCard("artist:Alpha", MixKind.Artist, "Alpha", cards.first().covers), cards.first())
            assertEquals(cards.size, cards.map { it.id }.distinct().size)
        }
    }

    @Test fun proposalsAreStableForOneSeedAndRotateBetweenSeeds() {
        val first = PersonalMixes.propose(known, known.takeLast(5), counts, emptySet(), seed = 7)
        assertEquals(first, PersonalMixes.propose(known, known.takeLast(5), counts, emptySet(), seed = 7))
        val styles = (1L..40L).map { seed ->
            PersonalMixes.propose(known, known.takeLast(5), counts, emptySet(), seed).first { it.kind == MixKind.Style }.artist
        }.toSet()
        assertTrue("The style mix takes turns among the next favourites: $styles", styles.size >= 2)
        assertFalse("The lead artist keeps its own mix, not the style one", "Alpha" in styles)
    }

    @Test fun artistsAreCountedByTheirFirstCreditAndFavouritesWeighMore() {
        val artists = PersonalMixes.topArtists(known, counts, favorites = setOf("c1", "c2", "c3", "c4"))
        assertEquals(listOf("Alpha", "Beta", "Gamma"), artists.take(3).map { it.name })
        assertEquals(30, artists[0].plays)
        assertEquals(15, artists[1].plays)
        assertEquals(12, artists[2].plays)
    }

    @Test fun onRepeatAndRediscoverUseOnlyTheListenersOwnHistory() {
        val repeat = PersonalMixes.onRepeat(known, counts)
        assertTrue(repeat.all { (counts[it.id] ?: 0) >= 3 })
        assertEquals("a1", repeat.first().id)
        val recent = known.take(8)
        val again = PersonalMixes.rediscover(known, recent, counts, seed = 3)
        assertTrue(again.isNotEmpty())
        assertTrue(again.none { it.id in recent.map(Track::id) })
        assertEquals(again, PersonalMixes.rediscover(known, recent, counts, seed = 3))
    }

    @Test fun aBlendStartsFamiliarAddsDiscoveriesAndNeverRepeatsARecording() {
        val mine = (1..5).map { track("m$it", "Alpha", "Song $it") }
        val theirs = listOf(track("r0", "Alpha", "Song 1")) + (1..40).map { track("r$it", "Alpha", "New $it") }
        val mix = PersonalMixes.blend(mine, theirs, limit = 20)
        assertEquals(20, mix.size)
        assertEquals(listOf("m1", "m2", "m3"), mix.take(3).map { it.id })
        assertTrue("The catalogue duplicate of a known recording is dropped", mix.none { it.id == "r0" })
        assertTrue(mix.any { it.id.startsWith("r") } && mix.map { it.id }.containsAll(mine.map { it.id }))
        assertEquals(mine, PersonalMixes.blend(mine, emptyList()))
    }
}
