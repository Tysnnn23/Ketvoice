package com.ketvoice.app.smartlib

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * `soundcloud-set.html` is cut from SoundCloud's public mobile page of the editorial set « New EDM Hits:
 * On The Up » (soundcloud-the-peak), read on 2026-11-19: the set, two of the 20 titles the page describes
 * in full, one of the 80 it names by id only, and the uploaders' public names.
 */
class SoundCloudImportTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("smartlib/$name")!!.readText()
    private val path = "soundcloud-the-peak/sets/on-the-up-new-edm-hits"

    @Test fun linksAreRecognised() {
        assertEquals(ImportService.SoundCloud, PlaylistImport.serviceOf("https://soundcloud.com/$path"))
        assertEquals(ImportService.SoundCloud, PlaylistImport.serviceOf("https://on.soundcloud.com/AbC123xyz"))
        assertTrue(SoundCloudImport.isShort("https://on.soundcloud.com/AbC123xyz"))
        assertEquals(path, SoundCloudImport.setPath("https://soundcloud.com/$path?si=abc&utm_source=clipboard"))
        assertEquals(path, SoundCloudImport.setPath("https://m.soundcloud.com/$path"))
        assertEquals("$path/s-AbCd1234", SoundCloudImport.setPath("https://soundcloud.com/$path/s-AbCd1234"))
        for (bad in listOf("https://soundcloud.com/soundcloud-the-peak", "https://soundcloud.com/soundcloud-the-peak/a-track",
                "https://soundcloud.com/a/sets/b/c", "http://soundcloud.com/$path")) {
            assertTrue(bad, runCatching { SoundCloudImport.setPath(bad) }.isFailure)
        }
    }

    @Test fun thePublicPageGivesTheFirstTitlesWithTheirIsrc() = runBlocking {
        val asked = mutableListOf<String>()
        val preview = PlaylistImport.preview("https://soundcloud.com/$path") { asked += it; fixture("soundcloud-set.html") }
        assertEquals(listOf("https://m.soundcloud.com/$path"), asked)
        assertEquals("New EDM Hits: On The Up", preview.name)
        assertEquals("https://i1.sndcdn.com/artworks-5w55JYi7qyV7b4tP-nQDwRw-t500x500.jpg", preview.cover)
        assertEquals(2, preview.songs.size)
        assertEquals("BbY WOW (John Summit Remix)", preview.songs[0].title)
        assertEquals("KAROL G, John Summit, Judeline, rusowsky", preview.songs[0].artist)
        assertEquals(218_826L, preview.songs[0].durationMs)
        assertEquals("USA2P2684089", preview.songs[0].isrc)
        assertEquals(100, preview.publishedCount)
        assertFalse(preview.complete)
    }

    @Test fun aMissingOrChangedSetSaysSo() = runBlocking {
        val link = "https://soundcloud.com/$path"
        assertEquals(PublicPage.NOT_FOUND, runCatching { PlaylistImport.preview(link) { null } }.exceptionOrNull()?.message)
        assertEquals(PublicPage.CHANGED, runCatching { PlaylistImport.preview(link) { "<html></html>" } }.exceptionOrNull()?.message)
        val privateSet = fixture("soundcloud-set.html").replace("\"sharing\": \"public\"", "\"sharing\": \"private\"")
        assertEquals(PublicPage.NOT_FOUND, runCatching { PlaylistImport.preview(link) { privateSet } }.exceptionOrNull()?.message)
    }
}
