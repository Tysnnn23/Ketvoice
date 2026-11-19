package com.ketvoice.app.smartlib

import com.ketvoice.app.data.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Deezer and Spotify were not reachable from the machine these tests were written on: the fixtures
 * under `test/resources/smartlib` are written by hand in the public formats of Deezer's API and of
 * Spotify's embed page, not recorded from the services.
 */
class PlaylistImportTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("smartlib/$name")!!.readText()

    @Test fun linksAreRecognised() {
        assertEquals(ImportService.Spotify, PlaylistImport.serviceOf("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=x"))
        assertEquals(ImportService.Deezer, PlaylistImport.serviceOf("https://www.deezer.com/fr/playlist/11223344556"))
        assertEquals(ImportService.Deezer, PlaylistImport.serviceOf("https://link.deezer.com/s/30abcdEF"))
        assertEquals("11223344556", PlaylistImport.deezerId("https://www.deezer.com/fr/playlist/11223344556?utm_source=x"))
        assertEquals("123", PlaylistImport.deezerId("https://deezer.com/playlist/123"))
        assertNull("A short link is expanded online", PlaylistImport.deezerId("https://link.deezer.com/s/30abcdEF"))
        for (bad in listOf("https://www.deezer.com/fr/album/1", "http://www.deezer.com/playlist/1", "https://evil.com/playlist/1", "nothing")) {
            assertTrue(bad, runCatching { if (PlaylistImport.serviceOf(bad) == ImportService.Deezer) PlaylistImport.deezerId(bad) }.isFailure)
        }
    }

    @Test fun aDeezerPlaylistIsReadPageByPage() = runBlocking {
        val asked = mutableListOf<String>()
        val preview = PlaylistImport.preview("https://www.deezer.com/fr/playlist/11223344556") { url ->
            asked += url
            when {
                url.endsWith("/playlist/11223344556") -> fixture("deezer-playlist.json")
                url.contains("tracks?index=3") -> fixture("deezer-playlist-page2.json")
                else -> null
            }
        }
        assertEquals(2, asked.size)
        assertEquals("Rap FR chill 2010s", preview.name)
        assertTrue(preview.complete)
        assertEquals(listOf("Bené", "Chemin d'or", "Titre retiré", "Nuit blanche (Live)"), preview.songs.map { it.title })
        assertEquals("um:136332242", preview.songs[0].catalogue?.id)
        assertNull("Deezer cannot play it: it is looked for like any other title", preview.songs[2].catalogue)
        assertTrue(preview.cover!!.startsWith("https://e-cdns-images.dzcdn.net/"))
    }

    @Test fun aMissingDeezerPlaylistSaysSo() {
        val failure = runCatching { PlaylistImport.parseDeezer(fixture("deezer-error.json"), "1") }.exceptionOrNull()
        assertEquals("Playlist Deezer introuvable ou privée", failure?.message)
    }

    @Test fun aSpotifyEmbedPageIsReadAsAPartialImport() = runBlocking {
        val preview = PlaylistImport.preview("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M") { url ->
            assertEquals("https://open.spotify.com/embed/playlist/37i9dQZF1DXcBWIGoYBM5M", url); fixture("spotify-embed.html")
        }
        assertEquals("Mes sons calmes", preview.name)
        assertFalse(preview.complete)
        assertEquals(4, preview.songs.size)
        assertEquals(188_500L, preview.songs[1].durationMs)
    }

    @Test fun titlesAreMatchedOnTitleArtistAndLength() {
        val song = ImportSong("0AAAAAAAAAAAAAAAAAAAA1", "Bené", "Werenoi", 201_000)
        val good = Track("um:1", "Bené", "Werenoi", "Pétunias", durationMs = 201_000, addon = "um")
        val video = Track("yt:2", "Bené (Clip officiel)", "Werenoi", "", durationMs = 236_000)
        val otherArtist = Track("um:3", "Bené", "Quelqu'un", "", durationMs = 201_000, addon = "um")
        val live = Track("um:4", "Bené (Live)", "Werenoi", "", durationMs = 203_000, addon = "um")
        assertTrue(PlaylistImport.score(song, good) > .95f)
        assertTrue(PlaylistImport.score(song, video) < PlaylistImport.score(song, good))
        assertEquals(0f, PlaylistImport.score(song, otherArtist))
        assertTrue(PlaylistImport.score(song, live) < PlaylistImport.MATCH_FROM)
        val match = PlaylistImport.best(song, listOf(otherArtist, live, video, good))
        assertEquals("um:1", match.track?.id)
        assertEquals("0AAAAAAAAAAAAAAAAAAAA1", match.track?.spotifyId)
        assertNull(PlaylistImport.best(song, listOf(otherArtist, live)).track)
        val isrc = ImportSong("x", "Different words", "Nobody", 0, isrc = "FRX1")
        assertEquals(1f, PlaylistImport.score(isrc, good.copy(isrc = "frx1")))
    }

    @Test fun resolvingReportsProgressAndKeepsUnmatchedTitles() = runBlocking {
        val preview = PlaylistImport.fromSpotify(fixture("spotify-embed.html"), "37i9dQZF1DXcBWIGoYBM5M")
        val catalogue = listOf(
            Track("um:1", "Bené", "Werenoi", "", durationMs = 201_000, addon = "um"),
            Track("um:2", "Chemin d'or", "Werenoi", "", durationMs = 188_000, addon = "um"),
            Track("um:5", "Nuit blanche (Live)", "Lunis", "", durationMs = 215_000, addon = "um"))
        val steps = mutableListOf<Int>()
        val matches = PlaylistImport.resolve(preview, { query -> catalogue.filter { query.contains(it.artist.lowercase()) || query.contains(it.artist) } }) { done, total ->
            assertEquals(4, total); steps += done
        }
        assertEquals(listOf(1, 2, 3, 4), steps.sorted())
        assertEquals(listOf("um:1", "um:2", null, "um:5"), matches.map { it.track?.id })
        val entries = PlaylistImport.entries(matches)
        assertEquals(4, entries.size)
        assertNull(entries[2].track)
        assertEquals("Introuvable", entries[2].title)
    }

    @Test fun anIsrcFindsTheCatalogueTitleFirstAndALengthGapRulesOut() = runBlocking {
        val withCode = ImportSong("sc:1", "BbY WOW (John Summit Remix)", "KAROL G, John Summit", 218_826, isrc = "USA2P2684089")
        val plain = ImportSong("sc:2", "Eternity", "Levity", 205_241)
        val byCode = Track("um:9", "BbY WOW - John Summit Remix", "KAROL G", "", durationMs = 219_000, addon = "um", isrc = "USA2P2684089")
        val extended = Track("um:10", "Eternity", "Levity", "", durationMs = 305_000, addon = "um")
        val asked = mutableListOf<String>()
        val matches = PlaylistImport.resolve(ImportPreview(ImportService.SoundCloud, "Set", "https://soundcloud.com/a/sets/b", null, listOf(withCode, plain), false),
            search = { asked += it; listOf(extended) }, byIsrc = { if (it == "USA2P2684089") byCode else null }) { _, _ -> }
        assertEquals("um:9", matches[0].track?.id)
        assertEquals(1f, matches[0].score)
        assertEquals("The ISRC answer spares a search", 1, asked.size)
        assertNull("A title 100 s longer is another recording", matches[1].track)
        assertTrue(PlaylistImport.score(plain, extended.copy(durationMs = 206_000)) > PlaylistImport.MATCH_FROM)
    }

    @Test fun catalogueAlbumsGiveStylesAndYear() {
        val facts = SmartFacts.parseAlbum(fixture("deezer-album.json"))!!
        assertEquals(setOf(Genre.Rap), facts.genres)
        assertEquals(2016, facts.year)
        assertEquals(facts, SmartFacts.decode(SmartFacts.encode(mapOf("2001" to facts)))["2001"])
        assertNull(SmartFacts.parseAlbum(fixture("deezer-error.json")))
    }
}
