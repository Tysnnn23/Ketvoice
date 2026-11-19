package com.ketvoice.app.smartlib

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * `apple-playlist.html` is cut from Apple Music's public page of the editorial playlist « Today’s Hits »
 * (pl.f4d106fed2bd41149aaacabb233eb5eb), read on 2026-11-19: its page data with the header and 3 of its 50 titles.
 */
class AppleMusicImportTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("smartlib/$name")!!.readText()

    @Test fun linksAreRecognised() {
        assertEquals(ImportService.AppleMusic, PlaylistImport.serviceOf("https://music.apple.com/fr/playlist/tubes-du-moment/pl.f4d106fed2bd41149aaacabb233eb5eb"))
        assertEquals("https://music.apple.com/fr/playlist/pl.f4d106fed2bd41149aaacabb233eb5eb",
            AppleMusicImport.pageUrl("https://music.apple.com/fr/playlist/tubes-du-moment/pl.f4d106fed2bd41149aaacabb233eb5eb?l=en"))
        assertEquals("https://music.apple.com/us/playlist/pl.u-AbCdEfGh1234",
            AppleMusicImport.pageUrl("https://music.apple.com/us/playlist/mine/pl.u-AbCdEfGh1234"))
        for (bad in listOf("https://music.apple.com/fr/album/x/123", "http://music.apple.com/us/playlist/x/pl.f4d106fed2bd41149aaacabb233eb5eb",
                "https://music.apple.com.evil.com/us/playlist/x/pl.f4d106fed2bd41149aaacabb233eb5eb")) {
            assertTrue(bad, runCatching { AppleMusicImport.pageUrl(bad) }.isFailure)
        }
    }

    @Test fun thePublicPageGivesNameCoverAndTitles() = runBlocking {
        val asked = mutableListOf<String>()
        val preview = PlaylistImport.preview("https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb") {
            asked += it; fixture("apple-playlist.html")
        }
        assertEquals(listOf("https://music.apple.com/us/playlist/pl.f4d106fed2bd41149aaacabb233eb5eb"), asked)
        assertEquals(ImportService.AppleMusic, preview.service)
        assertEquals("Today’s Hits", preview.name)
        assertTrue(preview.cover!!.startsWith("https://is1-ssl.mzstatic.com/") && preview.cover!!.endsWith("/600x600bb.jpg"))
        assertEquals(listOf("Housewife", "Stop The Wedding!"), preview.songs.take(2).map { it.title })
        assertEquals("Tate McRae", preview.songs[0].artist)
        assertEquals(208_810L, preview.songs[0].durationMs)
        assertEquals("am:6822497774", preview.songs[0].sourceId)
        // The excerpt keeps 3 of the 50 titles the page announces: it reads as a partial list.
        assertEquals(50, preview.publishedCount)
        assertFalse(preview.complete)
    }

    @Test fun aMissingOrChangedPageSaysSo() = runBlocking {
        val link = "https://music.apple.com/us/playlist/x/pl.f4d106fed2bd41149aaacabb233eb5eb"
        assertEquals(PublicPage.NOT_FOUND, runCatching { PlaylistImport.preview(link) { null } }.exceptionOrNull()?.message)
        assertEquals(PublicPage.CHANGED, runCatching { PlaylistImport.preview(link) { "<html><body>Apple Music</body></html>" } }.exceptionOrNull()?.message)
    }
}
