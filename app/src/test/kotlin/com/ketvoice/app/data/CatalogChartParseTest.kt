package com.ketvoice.app.data

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class CatalogChartParseTest {
    @Test fun albumsNeedANumericIdAndATitle() {
        val albums = CatalogRepo.parseAlbums(JSONArray("""[
            {"id": 12, "title": "First", "cover_xl": "https://img.example/a.jpg", "artist": {"name": "Nova"}},
            {"id": 12, "title": "Duplicate"},
            {"id": "x9", "title": "Bad id"},
            {"id": 13, "title": ""},
            {"id": 14, "title": "No art", "cover_xl": "", "cover_big": "http://insecure.example/a.jpg"}
        ]"""))
        assertEquals(listOf("12", "14"), albums.map { it.id })
        assertEquals("Nova", albums[0].artist)
        assertEquals("https://img.example/a.jpg", albums[0].cover)
        // Only HTTPS artwork is kept.
        assertNull(albums[1].cover)
        assertEquals("", albums[1].artist)
    }

    @Test fun artistsFallBackToASmallerPicture() {
        val artists = CatalogRepo.parseArtists(JSONArray("""[
            {"id": 7, "name": "Nova", "picture_xl": "", "picture_big": "https://img.example/n.jpg"},
            {"id": 8, "name": ""},
            {"name": "No id"}
        ]"""))
        assertEquals(1, artists.size)
        assertEquals("7", artists[0].id)
        assertEquals("https://img.example/n.jpg", artists[0].picture)
    }

    @Test fun missingListsAreEmpty() {
        assertTrue(CatalogRepo.parseAlbums(null).isEmpty())
        assertTrue(CatalogRepo.parseArtists(null).isEmpty())
        assertTrue(CatalogChart(emptyList(), emptyList(), emptyList()).isEmpty)
    }
}
