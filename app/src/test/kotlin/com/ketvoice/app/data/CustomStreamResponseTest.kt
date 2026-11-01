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
}
