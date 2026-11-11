package com.ketvoice.app.ui.library

import com.ketvoice.app.data.Track
import org.junit.Assert.*
import org.junit.Test

class LocalAlbumsTest {
    @Test fun unknownCatalogueIdsGroupLocallyWithoutMixingDifferentArtists() {
        val tracks = listOf(Track("a", "A", "Artist", "Album"), Track("b", "B", "artist", " album "),
            Track("c", "C", "Other artist", "Album"), Track("d", "D", "Artist", ""))
        val groups = localAlbums(tracks + tracks.first())
        assertEquals(2, groups.size)
        assertEquals(listOf("a", "b"), groups.first().tracks.map { it.id })
        assertEquals(listOf("c"), groups.last().tracks.map { it.id })
    }

    @Test fun realAlbumIdsKeepCompilationArtistsTogetherAndDistinctEditionsApart() {
        val tracks = listOf(Track("a", "A", "One", "Compilation", albumId = "123"),
            Track("b", "B", "Two", "Compilation", albumId = "123"), Track("c", "C", "One", "Compilation", albumId = "456"))
        assertEquals(listOf(2, 1), localAlbums(tracks).map { it.tracks.size })
    }
}
