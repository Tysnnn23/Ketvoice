package com.ketvoice.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The extended manifest (docs/manifest.md): lyrics, Canvas and cover endpoints, read without changing version 1 manifests. */
class CustomSourceExtendedTest {

    @Test
    fun versionOneManifestReadsAsBefore() {
        val m = parseManifest("https://example.com/source.json",
            """{"name":"Old","baseUrl":"https://api.example.com","search":"/search?q={query}","stream":"/stream/{id}","cover":"https://example.com/logo.png","canvas":"https://example.com/c.mp4"}""")!!
        assertEquals(1, m.version)
        // A top-level « cover » or « canvas » string of an older manifest is the source's own picture, not an endpoint.
        assertNull(m.resolvedCover)
        assertNull(m.resolvedCanvas)
        assertNull(m.resolvedLyrics)
        assertEquals("https://api.example.com/search?q={query}", m.resolvedSearch)
        assertEquals("https://api.example.com/stream/{id}", m.resolvedStream)
    }

    @Test
    fun extendedEndpointsAreReadFromTheEndpointsObject() {
        val m = parseManifest("https://example.com/source.json", """{"name":"New","manifestVersion":2,"baseUrl":"https://api.example.com",
            "endpoints":{"search":"/search?q={query}","stream":{"url":"/stream/{id}"},"lyrics":"/lyrics?t={title}&a={artist}&d={duration}",
            "canvas":{"path":"/canvas/{isrc}"},"cover":"https://img.example.com/cover?t={title}"}}""")!!
        assertEquals(2, m.version)
        assertEquals("https://api.example.com/lyrics?t={title}&a={artist}&d={duration}", m.resolvedLyrics)
        assertEquals("https://api.example.com/canvas/{isrc}", m.resolvedCanvas)
        assertEquals("https://img.example.com/cover?t={title}", m.resolvedCover)
        assertEquals("https://api.example.com/search?q={query}", m.resolvedSearch)
    }

    @Test
    fun resourcesWithoutEndpointsUseTheDefaultPaths() {
        val m = parseManifest("https://example.com/m.json",
            """{"name":"Res","baseUrl":"https://api.example.com","resources":["search","stream","Lyrics",{"name":"cover"},"unknown"]}""")!!
        assertEquals(setOf("search", "stream", "lyrics", "cover"), m.resources)
        assertEquals(2, m.version)
        assertEquals("https://api.example.com" + SourceExtras.LYRICS_PATH, m.resolvedLyrics)
        assertEquals("https://api.example.com" + SourceExtras.COVER_PATH, m.resolvedCover)
        assertNull(m.resolvedCanvas)
    }

    @Test
    fun flatUrlFieldsAndUnsafeAddressesAreHandled() {
        val m = parseManifest("https://example.com/m.json",
            """{"baseUrl":"https://api.example.com","lyricsUrl":"/l?q={title}","coverUrl":"http://insecure.example.com/c","canvasUrl":"javascript:alert(1)"}""")!!
        assertEquals("https://api.example.com/l?q={title}", m.resolvedLyrics)
        assertNull("only https or a path on the source's base", m.resolvedCover)
        assertNull(m.resolvedCanvas)
    }

    @Test
    fun placeholdersAreFilledAndEncoded() {
        val url = CustomSources.fill("https://api.example.com/l?t={title}&a={artist}&al={album}&d={duration}&i={isrc}", query = "", id = "",
            quality = "", title = "Été 85", artist = "A & B", album = "X/Y", durationSec = 214, isrc = "FR1234567890")
        assertEquals("https://api.example.com/l?t=%C3%89t%C3%A9+85&a=A+%26+B&al=X%2FY&d=214&i=FR1234567890", url)
    }

    @Test
    fun lyricsAnswersOfEveryShapeAreRead() {
        val lrc = SourceExtras.parseLyrics("""{"syncedLyrics":"[00:01.00]Hello\n[00:02.50]World","plainLyrics":"Hello\nWorld"}""")
        assertEquals(listOf(1000L, 2500L), lrc.map { it.timeMs })
        assertEquals("World", lrc[1].text)
        val nested = SourceExtras.parseLyrics("""{"data":{"lrc":"[00:03.00]Nested"}}""")
        assertEquals(3000L, nested.single().timeMs)
        val lines = SourceExtras.parseLyrics("""{"lines":[{"time":500,"text":"One"},{"startTimeMs":"1500","text":"Two"}]}""")
        assertEquals(listOf(500L, 1500L), lines.map { it.timeMs })
        val plain = SourceExtras.parseLyrics("""{"plain":"\nFirst\nSecond\n"}""")
        assertEquals(listOf("First", "Second"), plain.map { it.text })
        assertTrue(plain.all { it.timeMs < 0 })
        assertEquals(2000L, SourceExtras.parseLyrics("[00:02.00]Raw LRC").single().timeMs)
        assertEquals("Just text", SourceExtras.parseLyrics("Just text").single().text)
        assertTrue(SourceExtras.parseLyrics("""{"error":"not found"}""").isEmpty())
    }

    @Test
    fun linksAreTakenOnlyWhenSafe() {
        assertEquals("https://cdn.example.com/a.mp4", SourceExtras.parseLink("https://cdn.example.com/a.mp4\n", SourceExtras.CANVAS_KEYS))
        assertEquals("https://cdn.example.com/b.mp4", SourceExtras.parseLink("""{"data":{"canvasUrl":"https://cdn.example.com/b.mp4"}}""", SourceExtras.CANVAS_KEYS))
        assertEquals("https://img.example.com/c.jpg", SourceExtras.parseLink("""{"artwork":"https://img.example.com/c.jpg"}""", SourceExtras.COVER_KEYS))
        assertNull(SourceExtras.parseLink("""{"cover":"http://img.example.com/c.jpg"}""", SourceExtras.COVER_KEYS))
        assertNull(SourceExtras.parseLink("not a link", SourceExtras.COVER_KEYS))
    }
}
