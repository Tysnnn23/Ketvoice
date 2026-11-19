package com.ketvoice.app.smartlib

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * `tidal-page.html` and `tidal-embed.html` are cut from Tidal's public page and public embed player of
 * the editorial playlist « New Arrivals » (1b418bb8-90a7-4f87-901d-707993838346), read on 2026-11-19:
 * the page's schema.org data, and 3 of the 50 rows the player lists for its 100 titles.
 */
class TidalImportTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("smartlib/$name")!!.readText()
    private val id = "1b418bb8-90a7-4f87-901d-707993838346"

    @Test fun linksAreRecognised() {
        for (link in listOf("https://tidal.com/browse/playlist/$id", "https://listen.tidal.com/playlist/$id?u",
                "https://tidal.com/playlist/${id.uppercase()}", "https://embed.tidal.com/playlists/$id")) {
            assertEquals(link, ImportService.Tidal, PlaylistImport.serviceOf(link))
            assertEquals(link, id, TidalImport.playlistId(link))
        }
        for (bad in listOf("https://tidal.com/browse/album/123", "https://tidal.com/browse/playlist/123", "https://eviltidal.com/playlist/$id")) {
            assertTrue(bad, runCatching { TidalImport.playlistId(bad) }.isFailure)
        }
    }

    @Test fun thePageAndThePlayerGiveNameCoverAndTitles() = runBlocking {
        val asked = mutableListOf<String>()
        val preview = PlaylistImport.preview("https://listen.tidal.com/playlist/$id") { url ->
            asked += url
            when (url) { TidalImport.pageUrl(id) -> fixture("tidal-page.html"); TidalImport.embedUrl(id) -> fixture("tidal-embed.html"); else -> null }
        }
        assertEquals(2, asked.size)
        assertEquals("New Arrivals", preview.name)
        assertEquals("https://resources.tidal.com/images/a17fb679/10e1/4109/ade9/df504394f1ab/1080x1080.jpg", preview.cover)
        assertEquals(3, preview.songs.size)
        assertEquals("Just A Woman", preview.songs[0].title)
        assertEquals("Lainey Wilson", preview.songs[0].artist)
        assertEquals(224_000L, preview.songs[0].durationMs)
        assertEquals(100, preview.publishedCount)
        assertFalse("The player shows only part of the playlist", preview.complete)
    }

    @Test fun severalArtistsAndEntitiesAreRead() {
        val row = """<list-item role="listitem" product-id="42" product-type="track"><span slot="title">Rock &amp; Roll</span>
            <span slot="artist"><a href="https://tidal.com/artist/1">Hozier</a><a href="https://tidal.com/artist/2">Jessie Buckley</a></span>
            <time slot="duration">1:04:05</time></list-item><list-item product-id="43" product-type="video"><span slot="title">Clip</span></list-item>"""
        val preview = TidalImport.parse(null, "<media-item-list>$row</media-item-list>", id)
        assertEquals(1, preview.songs.size)
        assertEquals("Rock & Roll", preview.songs[0].title)
        assertEquals("Hozier, Jessie Buckley", preview.songs[0].artist)
        assertEquals(3_845_000L, preview.songs[0].durationMs)
        assertTrue("Fewer than 50 rows and no announced size: nothing is hidden", preview.complete)
    }

    @Test fun aMissingOrChangedPlaylistSaysSo() = runBlocking {
        val link = "https://tidal.com/browse/playlist/$id"
        assertEquals(PublicPage.NOT_FOUND, runCatching { PlaylistImport.preview(link) { null } }.exceptionOrNull()?.message)
        val changed = runCatching { PlaylistImport.preview(link) { url -> if (url.contains("embed")) "<html></html>" else fixture("tidal-page.html") } }
        assertEquals(PublicPage.CHANGED, changed.exceptionOrNull()?.message)
    }
}
