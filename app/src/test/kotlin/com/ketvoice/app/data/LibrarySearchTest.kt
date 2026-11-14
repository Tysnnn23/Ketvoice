package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test

class LibrarySearchTest {
    private fun track(id: String, title: String, artist: String = "Artist", album: String = "Album") = Track(id, title, artist, album)

    @Test fun searchesTheWholeLibraryDownloadsFirstWithoutDuplicates() {
        val download = track("d1", "Night Drive")
        val playlist = LocalPlaylist(name = "Road", entries = listOf(
            PlaylistEntry(title = "Night Drive", artist = "Artist", track = download),
            PlaylistEntry(title = "Night Walk", artist = "Artist", track = track("p1", "Night Walk")),
            PlaylistEntry(title = "Unresolved night", artist = "Artist")))
        val local = listOf(track("local:1", "Morning", album = "Night Tales"))
        val known = listOf(track("k1", "Other", artist = "Nightingale"), track("p1", "Night Walk"), track("k2", "Day"))
        val found = LibrarySearch.tracks(" NIGHT ", listOf(download), listOf(playlist), local, known)
        assertEquals(listOf("d1", "p1", "local:1", "k1"), found.map { it.id })
    }

    @Test fun shortQueriesAndLimitsAreRespected() {
        val known = (1..10).map { track("k$it", "Song $it") }
        assertTrue(LibrarySearch.tracks("s", emptyList(), emptyList(), emptyList(), known).isEmpty())
        assertEquals(3, LibrarySearch.tracks("song", emptyList(), emptyList(), emptyList(), known, limit = 3).size)
    }

    @Test fun findsPlaylistsByName() {
        val lists = listOf(LocalPlaylist(name = "Road trip"), LocalPlaylist(name = "Focus"))
        assertEquals(listOf("Road trip"), LibrarySearch.playlists("ROAD", lists).map { it.name })
        assertTrue(LibrarySearch.playlists("r", lists).isEmpty())
    }
}
