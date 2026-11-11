package com.ketvoice.app.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class StreamEndpointTest {
    private val original = CustomSources.openStreamConnection
    private val source = CustomSource("test", "https://source.example/manifest.json", "Test", "https://source.example")

    @After fun reset() { CustomSources.openStreamConnection = original }

    @Test fun httpFailuresAndTimeoutFallBackWithoutRetryingTheFailedCandidate() = runBlocking {
        for (status in listOf(401, 403, 404, 502, -1)) {
            var requests = 0
            CustomSources.openStreamConnection = { url ->
                requests++
                if (status < 0) throw SocketTimeoutException()
                FakeConnection(url, status, "")
            }
            val failed = StreamResolver.Source("failed") {
                CustomSources.fetchStreamEndpoint("https://source.example/stream")?.let {
                    CustomSources.parseStreamResponse(it, source)
                }
            }
            val fallback = StreamResolver.Source("fallback") { MusicApi.StreamInfo("https://fallback.example/audio", "aac", "192 kbps") }
            assertEquals("fallback", StreamResolver.resolve(listOf(failed, fallback), race = false).first().sourceId)
            assertEquals(1, requests)
        }
    }

    @Test fun successfulDashResponseRemainsAvailableForMedia3() = runBlocking {
        CustomSources.openStreamConnection = { url -> FakeConnection(url, 200,
            """{"url":"https://source.example/tidal-mpd/573293","format":"dash","mimeType":"application/dash+xml","codec":"flac"}""") }
        val raw = CustomSources.fetchStreamEndpoint("https://source.example/stream")!!
        assertTrue(CustomSources.parseStreamResponse(raw, source)!!.isDash)
    }

    private class FakeConnection(url: URL, private val status: Int, private val body: String) : HttpURLConnection(url) {
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun connect() = Unit
    }
}
