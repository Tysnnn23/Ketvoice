package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test

class CustomStreamResponseTest {
    private val source = CustomSource("qa", "https://source.example/source.json", "QA", "https://source.example")
    @Test fun downloadEndpointsReturningJsonResolveToAudioRatherThanSavingTheJson() {
        val result = CustomSources.parseStreamResponse("""{"url":"https://cdn.example/live.mp3","downloadUrl":"https://cdn.example/full.flac","format":"flac","quality":"24-bit"}""", source, true)!!
        assertEquals("https://cdn.example/full.flac", result.url)
        assertEquals("https://cdn.example/full.flac", result.downloadUrl)
        assertEquals("flac", result.format)
    }
    @Test fun streamAndDownloadUrlsStayDistinctForStreaming() {
        val result = CustomSources.parseStreamResponse("""{"data":{"url":"https://cdn.example/live.mp3","downloadUrl":"https://cdn.example/full.flac","format":"mp3"}}""", source)!!
        assertEquals("https://cdn.example/live.mp3", result.url)
        assertEquals("https://cdn.example/full.flac", result.downloadUrl)
    }
    @Test fun plainAudioUrlAndRelativeDownloadJsonAreSupported() {
        assertEquals("https://cdn.example/audio.flac", CustomSources.parseStreamResponse("https://cdn.example/audio.flac", source, true)!!.downloadUrl)
        assertEquals("https://source.example/files/audio.flac", CustomSources.parseStreamResponse("""{"downloadUrl":"/files/audio.flac","format":"flac"}""", source, true)!!.url)
        assertNull(CustomSources.parseStreamResponse("<html>Expired</html>", source, true))
        assertNull(CustomSources.parseStreamResponse("http://cdn.example/audio.flac", source))
    }

    @Test fun flacDirectAndExtensionlessDashPreserveFormatInformation() {
        val flac = CustomSources.parseStreamResponse("""{"url":"https://cdn.example/audio","format":"flac","mimeType":"audio/flac","codec":"flac"}""", source)!!
        assertFalse(flac.isDash)
        assertEquals("audio/flac", flac.mimeType)
        assertEquals("flac", flac.codec)
        val dash = CustomSources.parseStreamResponse("""{"url":"https://source.example/tidal-mpd/573293?quality=low","format":"dash","mimeType":"application/dash+xml","manifest":"dash","codec":"flac","quality":"lossless","encrypted":false}""", source)!!
        assertTrue(dash.isDash)
        assertEquals("application/dash+xml", dash.mimeType)
        assertEquals("flac", dash.codec)
        assertTrue(StreamQuality.rank(dash) > StreamQuality.rank(MusicApi.StreamInfo("https://cdn.example/a", "aac", "320 kbps")))
        assertTrue(CustomSources.parseStreamResponse("https://cdn.example/audio.mpd?token=fixture", source)!!.isDash)
        assertNull(CustomSources.parseStreamResponse("""{"url":"https://source.example/dash","encrypted":true}""", source))
    }

    @Test fun declaredManifestIsRecognizedWithoutAnExtensionOrFormat() {
        val result = CustomSources.parseStreamResponse("""{"data":{"url":"https://source.example/manifest","manifest":"dash","codec":"flac"}}""", source)!!
        assertTrue(result.isDash)
        assertEquals("dash", result.format)
        val typed = CustomSources.parseStreamResponse("""{"url":"https://source.example/manifest","mimeType":"application/dash+xml; charset=utf-8"}""", source)!!
        assertTrue(typed.isDash)
        val aac = CustomSources.parseStreamResponse("""{"url":"https://source.example/audio","format":"aac","mimeType":"audio/mp4"}""", source)!!
        assertFalse(aac.isDash)
        assertEquals("audio/mp4", aac.mimeType)
    }
}
