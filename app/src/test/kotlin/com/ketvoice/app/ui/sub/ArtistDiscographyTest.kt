package com.ketvoice.app.ui.sub

import com.ketvoice.app.data.Album
import com.ketvoice.app.data.Track
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class ArtistDiscographyTest {
    private fun album(id: String, type: String) = Album(id, "Titre $id", "Artiste", type)

    @Test fun releasesAreSortedIntoShelvesByKindAndEmptyShelvesAreLeftOut() {
        val albums = listOf(album("a", "Album · 2023"), album("s", "Single · 2025"), album("e", "EP"), album("b", "Album"))
        val shelves = ArtistDiscography.shelves(albums)
        assertEquals(listOf("Albums", "Singles et EP"), shelves.map { it.title })
        assertEquals(listOf("a", "b"), shelves[0].albums.map { it.id })
        assertEquals(listOf("s", "e"), shelves[1].albums.map { it.id })
        assertEquals(listOf("Albums"), ArtistDiscography.shelves(listOf(album("a", "Album · 2020"))).map { it.title })
        assertTrue(ArtistDiscography.shelves(emptyList()).isEmpty())
    }

    @Test fun theLatestReleaseIsTheNewestKnownYearAndNoneWithoutAYear() {
        val albums = listOf(album("old", "Album · 2019"), album("new", "Single · 2025"), album("same", "EP · 2025"))
        // Same year: the catalogue's own order (newest first) decides.
        assertEquals("new", ArtistDiscography.latest(albums)?.id)
        assertNull(ArtistDiscography.latest(listOf(album("x", "Album"), album("y", "Single"))))
    }

    @Test fun captionsSayTheYearInAlbumsAndTheKindAmongSingles() {
        assertEquals("2023", ArtistDiscography.caption(album("a", "Album · 2023"), singles = false))
        assertEquals("Album", ArtistDiscography.caption(album("a", "Album"), singles = false))
        assertEquals("Single · 2025", ArtistDiscography.caption(album("s", "Single · 2025"), singles = true))
        assertEquals("EP", ArtistDiscography.caption(album("e", "EP"), singles = true))
    }

    @Test fun fivePopularTitlesThenAll() {
        val tracks = (0 until 12).map { Track("t$it", "T$it", "A", "") }
        assertEquals(5, ArtistDiscography.popular(tracks, expanded = false).size)
        assertEquals(12, ArtistDiscography.popular(tracks, expanded = true).size)
    }

    @Test fun aWideWindowGetsABannerAndAPhoneAPortraitBothOnTheFirstScreen() {
        assertFalse(ArtistLayout.wide(400f, quest = false))
        assertTrue(ArtistLayout.wide(1024f, quest = false))
        assertFalse("Quest keeps its own window", ArtistLayout.wide(1280f, quest = true))
        val phone = ArtistLayout.heroHeight(400f, 880f, wide = false)
        assertTrue(phone > 400f && phone <= 880f * .66f + .5f)
        val banner = ArtistLayout.heroHeight(1194f, 834f, wide = true)
        assertTrue(banner < 1194f / 2f && banner <= 834f * .56f + .5f)
        assertEquals(1, ArtistLayout.popularColumns(700f))
        assertEquals(2, ArtistLayout.popularColumns(900f))
    }

    @Test fun theBarTitleKeepsClearOfTheButtons() {
        // Back on one side; the Play that appears and a capsule of one or three actions on the other.
        assertTrue(barSidePadding(1, play = true).value >= 12f + 44f + 52f)
        assertTrue(barSidePadding(3, play = true).value > barSidePadding(1, play = true).value)
        assertTrue(barSidePadding(0, play = false).value >= 56f)
    }

    @Test fun albumFootWritesTheDateAsTheLanguageDoesAndTheTotalLength() {
        assertEquals("31 octobre 2026", AlbumPresentation.releaseDate("2026-10-31", Locale.FRENCH))
        assertEquals("October 31, 2026", AlbumPresentation.releaseDate("2026-10-31", Locale.US))
        assertEquals("2018", AlbumPresentation.releaseDate("2018", Locale.FRENCH))
        assertNull(AlbumPresentation.releaseDate("bientôt", Locale.FRENCH))
        assertNull(AlbumPresentation.releaseDate(null, Locale.FRENCH))
        val tracks = listOf(Track("a", "A", "X", "", durationMs = 3_000_000), Track("b", "B", "X", "", durationMs = 1_620_000))
        assertEquals("1 h 17 min", AlbumPresentation.totalDuration(tracks))
        assertEquals("1 min", AlbumPresentation.totalDuration(listOf(Track("c", "C", "X", "", durationMs = 20_000))))
        assertNull(AlbumPresentation.totalDuration(listOf(Track("d", "D", "X", ""))))
    }

    @Test fun moreByLeavesThisAlbumOut() {
        val albums = listOf(album("MPRE1", "Album · 2020"), Album("MPRE2", "Horizons", "Artiste", "Album"), album("MPRE3", "Single"))
        assertEquals(listOf("MPRE3"), AlbumPresentation.moreBy(albums, "MPRE1", " horizons ").map { it.id })
    }
}
