package com.ketvoice.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomSourcesTest {

    @Test
    fun flatManifestResolvesRealEndpoints() {
        val manifest = parseManifest(
            "https://example.com/source.json",
            """{"name":"Ma source","baseUrl":"https://api.example.com","search":"/search?q={query}","stream":"/stream/{id}?quality={quality}","qualities":["high","low"]}"""
        )
        assertNotNull(manifest)
        assertEquals("Ma source", manifest!!.name)
        assertEquals("https://api.example.com/search?q={query}", manifest.resolvedSearch)
        assertEquals("https://api.example.com/stream/{id}?quality={quality}", manifest.resolvedStream)
        assertEquals(listOf("high", "low"), manifest.qualities)
    }

    @Test
    fun nestedEndpointsAreAccepted() {
        val manifest = parseManifest(
            "https://cdn.example.com/source.json",
            """{"title":"Nested","api":{"baseUrl":"https://music.example.com/api"},"endpoints":{"search":{"path":"/find"},"stream":{"url":"/play/{id}"}}}"""
        )
        assertNotNull(manifest)
        assertEquals("Nested", manifest!!.name)
        assertEquals("https://music.example.com/api/find", manifest.resolvedSearch)
        assertEquals("https://music.example.com/api/play/{id}", manifest.resolvedStream)
    }

    @Test
    fun missingStreamFallsBackToBaseStreamPath() {
        val manifest = parseManifest(
            "https://example.com/s.json",
            """{"baseUrl":"https://api.example.com","search":"https://api.example.com/search?q={query}"}"""
        )
        assertNotNull(manifest)
        assertEquals("https://api.example.com/stream/{id}?quality={quality}", manifest!!.resolvedStream)
    }

    @Test
    fun absoluteSearchAndRelativeBaseFromManifestDirectory() {
        val manifest = parseManifest(
            "https://example.com/addons/source.json",
            """{"search":"https://other.example.com/find?q={query}"}"""
        )
        assertNotNull(manifest)
        assertEquals("https://other.example.com/find?q={query}", manifest!!.resolvedSearch)
        assertEquals("https://example.com/addons", manifest.baseUrl)
    }

    @Test
    fun onlyHttpsJsonManifestUrlsAreAccepted() {
        assertTrue(CustomSources.isValidManifestUrl("https://example.com/source.json"))
        assertTrue(CustomSources.isValidManifestUrl("https://example.com/source.json?token=1"))
        assertFalse(CustomSources.isValidManifestUrl("http://example.com/source.json"))
        assertFalse(CustomSources.isValidManifestUrl("https://example.com/source.txt"))
        assertFalse(CustomSources.isValidManifestUrl("https://example.com/source"))
    }

    @Test
    fun explicitDownloadEndpointIsKeptSeparateFromStreaming() {
        val manifest = parseManifest(
            "https://example.com/source.json",
            """{"baseUrl":"https://api.example.com","search":"/search?q={query}","stream":"/stream/{id}","download":"/download/{id}?quality={quality}"}"""
        )
        assertNotNull(manifest)
        assertEquals("https://api.example.com/download/{id}?quality={quality}", manifest!!.downloadEndpoint)
        assertEquals("https://api.example.com/download/{id}?quality={quality}", manifest.resolvedDownload)
    }

    @Test
    fun bitChordAddonManifestUsesStandardEndpoints() {
        val manifest = parseManifest(
            "https://addon.example.com/manifest.json",
            """{"id":"unified","name":"Unified","version":"1.2","resources":["search","stream"],"settings":[{"key":"quality","default":"normal","options":[{"value":"lossless"},{"value":"high"},{"value":"normal"}]}]}"""
        )
        assertNotNull(manifest)
        assertEquals("Unified", manifest!!.name)
        assertEquals("https://addon.example.com", manifest.baseUrl)
        assertEquals("https://addon.example.com/search?q={query}&quality={quality}", manifest.resolvedSearch)
        assertEquals("https://addon.example.com/stream/{id}?quality={quality}", manifest.resolvedStream)
        assertEquals(listOf("lossless", "high", "normal"), manifest.qualities)
        assertEquals("high", negotiateQuality("high", manifest.qualities))
        assertEquals("normal", negotiateQuality("low", manifest.qualities))
        assertTrue(manifest.downloadsAllowed)
    }

    @Test
    fun qualityNegotiationFallsBackToDeclaredOption() {
        assertEquals("high", negotiateQuality("high", emptyList()))
        assertEquals("low", negotiateQuality("low", emptyList()))
        assertEquals("320 kbps", negotiateQuality("high", listOf("128 kbps", "320 kbps")))
        assertEquals("128 kbps", negotiateQuality("low", listOf("128 kbps", "320 kbps")))
    }

    @Test
    fun addonCanForbidDownloads() {
        val manifest = parseManifest(
            "https://addon.example.com/manifest.json",
            """{"id":"nods","name":"No downloads","resources":["search","stream"],"allowDownloads":0}"""
        )
        assertNotNull(manifest)
        assertFalse(manifest!!.downloadsAllowed)
    }

    @Test
    fun bitChordSearchRowsExposeDirectStreamAndArtwork() {
        val source = CustomSource(
            id = "addon",
            manifestUrl = "https://addon.example.com/manifest.json",
            name = "Unified",
            baseUrl = "https://addon.example.com",
            manifestJson = """{"id":"unified","name":"Unified","resources":["search","stream"]}"""
        )
        val tracks = CustomSources.parseTracks(source, """
            {"tracks":[{"id":"track-1","title":"Ciao","artist":"Werenoi, Ninho","album":"Ciao",
            "duration":245.0,"artworkURL":"https://cdn.example.com/ciao.jpg",
            "streamURL":"https://cdn.example.com/ciao.flac","format":"flac"}]}
        """.trimIndent())
        assertEquals(1, tracks.size)
        val track = tracks.single()
        assertEquals("Ciao", track.title)
        assertEquals("Werenoi, Ninho", track.artist)
        assertEquals(245_000L, track.durationMs)
        assertEquals("https://cdn.example.com/ciao.jpg", track.coverUrl)
        assertEquals("https://cdn.example.com/ciao.flac", track.streamUrl)
        assertEquals("track-1", track.addonId)
        assertEquals("cs:addon", track.addon)
    }
}
