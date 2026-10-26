package com.ketvoice.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaylistExchangeTest {

    @Test
    fun `json round trip keeps entries and name`() {
        val playlist = LocalPlaylist(
            id = "p1", name = "Soirée",
            entries = listOf(
                PlaylistEntry(title = "Titre A", artist = "Artiste A"),
                PlaylistEntry(title = "Titre B", artist = "Artiste B", spotifyId = "abc123DEF456")
            ),
            spotifyUrl = "https://open.spotify.com/playlist/x"
        )
        val parsed = PlaylistExchange.parseJson(PlaylistExchange.toJson(playlist))
        assertEquals("Soirée", parsed.name)
        assertEquals("https://open.spotify.com/playlist/x", parsed.spotifyUrl)
        assertEquals(2, parsed.entries.size)
        assertEquals("Titre A" to "Artiste A", parsed.entries[0].title to parsed.entries[0].artist)
        assertEquals("abc123DEF456", parsed.entries[1].spotifyId)
    }

    @Test
    fun `m3u parses artist and title lines`() {
        val m3u = """
            #EXTM3U
            #PLAYLIST:Ma liste
            #EXTINF:-1,Artiste A - Titre A
            Titre A - Artiste A
            Titre B - Artiste B
        """.trimIndent()
        val parsed = PlaylistExchange.parseM3u(m3u)
        assertEquals("Ma liste", parsed.name)
        assertEquals(2, parsed.entries.size)
        assertEquals("Titre A", parsed.entries[0].title)
        assertEquals("Artiste A", parsed.entries[0].artist)
    }

    @Test
    fun `foreign json is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaylistExchange.parseJson("""{"kind":"other","name":"x","entries":[]}""")
        }
    }

    @Test
    fun `empty m3u is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaylistExchange.parseM3u("#EXTM3U\n")
        }
    }

    @Test
    fun `blank name is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaylistExchange.parseJson("""{"kind":"ketvoice-playlist","name":"   ","entries":[{"title":"a","artist":"b"}]}""")
        }
    }
}
