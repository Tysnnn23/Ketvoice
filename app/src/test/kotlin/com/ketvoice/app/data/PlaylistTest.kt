package com.ketvoice.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaylistTest {
    private val playlistId = "37i9dQZF1DXcBWIGoYBM5M"

    private fun page(vararg songs: Pair<String, String>, cover: String? = "https://i.scdn.co/image/abc"): String {
        val list = songs.mapIndexed { index, (title, artist) ->
            JSONObject()
                .put("uri", "spotify:track:${"a".repeat(22 - index.toString().length)}${index}")
                .put("title", title).put("subtitle", artist).put("duration", 180000 + index)
        }
        val entity = JSONObject().put("name", "Ma sélection")
            .put("trackList", org.json.JSONArray(list.drop(0)))
            .put("coverArt", JSONObject().put("sources", org.json.JSONArray().put(JSONObject().put("url", cover ?: ""))))
        val state = JSONObject().put("data", JSONObject().put("entity", entity))
        return "<html><script id=\"__NEXT_DATA__\" type=\"application/json\">" +
            JSONObject().put("props", JSONObject().put("pageProps", JSONObject().put("state", state))) +
            "</script></html>"
    }

    @Test fun publicLinksAreAcceptedAndPrivateSurfacesRejected() {
        assertEquals(playlistId, SpotifyPlaylistImport.playlistId("https://open.spotify.com/playlist/$playlistId?si=abc"))
        assertEquals(playlistId, SpotifyPlaylistImport.playlistId("https://open.spotify.com/intl-fr/playlist/$playlistId"))
        assertEquals(playlistId, SpotifyPlaylistImport.playlistId("spotify:playlist:$playlistId"))
        listOf(
            "http://open.spotify.com/playlist/$playlistId",
            "https://open.spotify.com/track/$playlistId",
            "https://open.spotify.com.evil.test/playlist/$playlistId",
            "https://user@open.spotify.com/playlist/$playlistId",
            "https://open.spotify.com/playlist/short",
            ""
        ).forEach { link ->
            assertTrue(link, runCatching { SpotifyPlaylistImport.playlistId(link) }.isFailure)
        }
    }

    @Test fun previewKeepsOrderAndDropsIncompleteRows() {
        val html = page("Titre A" to "Artiste A", "" to "Artiste B", "Titre C" to "Artiste C")
        val preview = SpotifyPlaylistImport.parse(html, playlistId)
        assertEquals("Ma sélection", preview.name)
        assertEquals(listOf("Titre A", "Titre C"), preview.entries.map { it.title })
        assertEquals("https://open.spotify.com/playlist/$playlistId", preview.url)
        assertEquals("https://i.scdn.co/image/abc", preview.cover)
    }

    @Test fun previewRefusesForeignCoverHostsAndEmptyTrackLists() {
        val html = page("Titre A" to "Artiste A", cover = "https://tracker.example/pixel.png")
        assertNull(SpotifyPlaylistImport.parse(html, playlistId).cover)
        val empty = "<html><script id=\"__NEXT_DATA__\">" + JSONObject().put("props", JSONObject().put("pageProps",
            JSONObject().put("state", JSONObject().put("data", JSONObject().put("entity",
                JSONObject().put("name", "Vide").put("trackList", org.json.JSONArray())))))) + "</script></html>"
        assertTrue(runCatching { SpotifyPlaylistImport.parse(empty, playlistId) }.isFailure)
        assertTrue(runCatching { SpotifyPlaylistImport.parse("<html></html>", playlistId) }.isFailure)
    }

    @Test fun matchingNeedsExactTitleArtistAndVerifiedCatalogEntry() {
        val song = SpotifySong("b".repeat(22), "Titre A", "Artiste A, Autre", 200000)
        val good = Track("1", "Titre A", "Artiste A", "Album", durationMs = 201000, youtubeCatalogVerified = true)
        val wrongArtist = good.copy(id = "2", artist = "Artiste B")
        val unverified = good.copy(id = "3", youtubeCatalogVerified = false)
        val wrongDuration = good.copy(id = "4", durationMs = 400000)
        assertEquals("1", SpotifyPlaylistImport.match(song, listOf(wrongArtist, unverified, wrongDuration, good))?.id)
        assertNull(SpotifyPlaylistImport.match(song, listOf(wrongArtist, unverified, wrongDuration)))
        assertNull(SpotifyPlaylistImport.match(song.copy(artist = ""), listOf(good)))
    }

    @Test fun storedTracksNeverKeepPlayableUrls() {
        val track = Track("1", "Titre", "Artiste", "Album", streamUrl = "https://x/stream", downloadUrl = "https://x/file")
        val entry = PlaylistRules.entry(track)
        assertNull(entry.track?.streamUrl)
        assertNull(entry.track?.downloadUrl)
        assertEquals("Titre", entry.title)
    }

    @Test fun namesAreTrimmedAndBounded() {
        assertEquals("Live", PlaylistRules.name("  Live  "))
        listOf("", "   ", "a".repeat(81), "bad\u0000name").forEach { value ->
            assertTrue(value, runCatching { PlaylistRules.name(value) }.isFailure)
        }
    }

    @Test fun reorderingIsBoundedAndKeepsEveryEntry() {
        val entries = listOf("a", "b", "c").map { PlaylistEntry(id = it, title = it, artist = "x") }
        assertEquals(listOf("b", "a", "c"), PlaylistRules.move(entries, 0, 1).map { it.id })
        assertEquals(listOf("a", "b", "c"), PlaylistRules.move(entries, 3, 0).map { it.id })
        assertEquals(listOf("a", "b", "c"), PlaylistRules.move(entries, 1, 1).map { it.id })
        assertEquals(3, entries.size)
    }
}
