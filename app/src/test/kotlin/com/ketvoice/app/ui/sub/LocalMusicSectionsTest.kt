package com.ketvoice.app.ui.sub

import com.ketvoice.app.data.Track
import org.junit.Assert.*
import org.junit.Test

class LocalMusicSectionsTest {
    private fun track(id: String, title: String, artist: String, album: String, durationMs: Long = 0) =
        Track("local:$id", title, artist, album, durationMs = durationMs)
    private val tracks = listOf(
        track("1", "Zeta", "Beta, Guest", "Second", 200_000),
        track("2", "alpha", "Alpha", "First", 100_000),
        track("3", "Mid", "beta", "Second", 300_000),
        track("4", "Loose", "<unknown>", "", 50_000))

    @Test fun searchMatchesTitleArtistOrAlbumWithoutCase() {
        assertEquals(tracks, filterLocal(tracks, "  "))
        assertEquals(listOf("local:1", "local:3"), filterLocal(tracks, "BETA").map { it.id })
        assertEquals(listOf("local:2"), filterLocal(tracks, "first").map { it.id })
        assertTrue(filterLocal(tracks, "nothing").isEmpty())
    }

    @Test fun sortsByTitleArtistAlbumAndDuration() {
        assertEquals(listOf("alpha", "Loose", "Mid", "Zeta"), sortLocal(tracks, LocalSort.Title).map { it.title })
        assertEquals("local:4", sortLocal(tracks, LocalSort.Artist).first().id)
        assertEquals(listOf("local:4", "local:2"), sortLocal(tracks, LocalSort.Album).take(2).map { it.id })
        assertEquals(listOf(300_000L, 200_000L, 100_000L, 50_000L), sortLocal(tracks, LocalSort.Duration).map { it.durationMs })
    }

    @Test fun albumsLeaveUntaggedFilesOutAndAddUpTheirDuration() {
        val albums = localAlbums(tracks)
        assertEquals(listOf("First", "Second"), albums.map { it.title })
        assertEquals(500_000L, albums[1].durationMs)
        assertEquals(2, albums[1].tracks.size)
        assertTrue(albums.none { group -> group.tracks.any { it.id == "local:4" } })
    }

    @Test fun artistsGroupByFirstCreditLargestFirst() {
        val artists = localArtists(tracks)
        assertEquals(listOf("Beta", "Alpha"), artists.map { it.title })
        assertEquals(2, artists.first().tracks.size)
        assertEquals(artists.map { it.key }.distinct(), artists.map { it.key })
    }

    @Test fun twoAlbumsWithTheSameNameStayApartByArtist() {
        val same = listOf(track("a", "One", "First", "Greatest Hits"), track("b", "Two", "Second", "Greatest Hits"))
        assertEquals(2, localAlbums(same).size)
    }
}
