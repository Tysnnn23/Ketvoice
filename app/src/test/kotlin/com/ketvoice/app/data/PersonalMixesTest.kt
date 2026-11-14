package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class PersonalMixesTest {
    private fun track(id: String, artist: String, title: String = "Title $id", catalogue: String? = null) =
        Track(id, title, artist, "Album", coverUrl = "https://img/$id",
            addon = if (catalogue != null) "um" else null, artistId = catalogue)

    // Alpha is only known from another source; Beta and Gamma are catalogue artists.
    private val known = (1..6).map { track("a$it", "Alpha") } + (1..5).map { track("b$it", "Beta", catalogue = "11") } +
        (1..4).map { track("c$it", "Gamma", catalogue = "22") } + (1..12).map { track("x$it", "Solo $it") }
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

    @Test fun proposalsRotateBetweenLaunchesButNeverWithinOne() {
        val styles = (1L..40L).map { seed ->
            PersonalMixes.propose(known, known.takeLast(5), counts, emptySet(), seed).first { it.kind == MixKind.Style }.artist
        }.toSet()
        assertEquals("The style mix takes turns among the catalogue artists", setOf("Beta", "Gamma"), styles)
        // The history list is reordered at every play: the same launch must keep the same proposals.
        val first = PersonalMixes.propose(known, known.takeLast(5), counts, emptySet(), seed = 7)
        repeat(10) { round ->
            val reordered = known.shuffled(Random(round))
            assertEquals(first, PersonalMixes.propose(reordered, known.takeLast(5), counts, emptySet(), seed = 7))
            assertEquals(PersonalMixes.rediscover(known, known.take(8), counts, seed = 7),
                PersonalMixes.rediscover(reordered, known.take(8), counts, seed = 7))
        }
    }

    @Test fun aStyleMixNeedsAnArtistTheCatalogueIsKnownToHave() {
        val offCatalogue = known.map { it.copy(addon = null, artistId = null) }
        val cards = PersonalMixes.propose(offCatalogue, offCatalogue.takeLast(5), counts, emptySet(), seed = 3)
        assertTrue(cards.none { it.kind == MixKind.Style })
        assertTrue(cards.any { it.kind == MixKind.Artist })
    }

    @Test fun untaggedLocalFilesNeverBecomeAnArtist() {
        val untagged = (1..8).map { track("local:$it", "Artiste inconnu") }
        val library = known + untagged
        val plays = counts + untagged.associate { it.id to 9 }
        assertTrue(PersonalMixes.topArtists(library, plays, emptySet()).none { it.name.equals("Artiste inconnu", true) })
        assertTrue(PersonalMixes.propose(library, emptyList(), plays, emptySet(), seed = 1).none { it.artist == "Artiste inconnu" })
        // They still count for what was simply played a lot.
        assertTrue(PersonalMixes.onRepeat(library, plays).any { it.id.startsWith("local:") })
    }

    @Test fun artistsAreCountedByTheirCreditAndFavouritesWeighMore() {
        val artists = PersonalMixes.topArtists(known, counts, favorites = setOf("c1", "c2", "c3", "c4"))
        assertEquals(listOf("Alpha", "Beta", "Gamma"), artists.take(3).map { it.name })
        assertEquals(listOf(30, 15, 12), artists.take(3).map { it.plays })
        assertEquals(listOf(null, "11", "22"), artists.take(3).map { it.catalogId })
        // Collaborators joined by another source are reduced to the first credit…
        assertEquals("Beta", PersonalMixes.primaryArtist(track("y", "Beta, Guest")))
        assertEquals("Gamma", PersonalMixes.primaryArtist(track("y", "Gamma feat. Someone")))
        // …but a catalogue artist keeps its own commas.
        assertEquals("Tyler, The Creator", PersonalMixes.primaryArtist(track("y", "Tyler, The Creator", catalogue = "4")))
    }

    @Test fun onRepeatAndRediscoverUseOnlyTheListenersOwnHistory() {
        val repeat = PersonalMixes.onRepeat(known, counts)
        assertTrue(repeat.all { (counts[it.id] ?: 0) >= 3 })
        assertEquals("a1", repeat.first().id)
        val recent = known.take(8)
        val again = PersonalMixes.rediscover(known, recent, counts, seed = 3)
        assertTrue(again.isNotEmpty())
        assertTrue(again.none { it.id in recent.map(Track::id) })
        assertNotEquals("Another launch picks another order", again, PersonalMixes.rediscover(known, recent, counts, seed = 4))
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

    @Test fun localFilesGetTheirContentUriBackFromTheLibrary() {
        val remembered = listOf(track("local:1", "Alpha"), track("um:9", "Beta", catalogue = "11"), track("local:gone", "Alpha"))
        val library = listOf(track("local:1", "Alpha").copy(streamUrl = "content://media/1"))
        val mix = PersonalMixes.withLocalFiles(remembered, library)
        assertEquals("content://media/1", mix[0].streamUrl)
        assertSame(remembered[1], mix[1])
        assertSame("A file the library no longer lists is kept as it was", remembered[2], mix[2])
        val streamed = remembered.subList(1, 2)
        assertSame("Nothing is copied when there is no local file", streamed, PersonalMixes.withLocalFiles(streamed, library))
    }

    @Test fun aCatalogueHitMustBearTheRequestedName() {
        assertTrue(CatalogRepo.sameArtistName("Beyoncé", "beyonce"))
        assertTrue(CatalogRepo.sameArtistName("Tyler, The Creator", "tyler the creator"))
        assertFalse(CatalogRepo.sameArtistName("Earth", "Earth, Wind & Fire"))
        assertFalse(CatalogRepo.sameArtistName("", ""))
    }
}
