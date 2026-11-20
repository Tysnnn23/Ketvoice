package com.ketvoice.app.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.URL

/** What goes to the Cast device, and the rules of the phone's little file server. */
class CastPlanAndServerTest {
    @Test fun streamsGoAsTheyAreAndLocalFilesAreServed() {
        assertEquals(CastSource.Remote("https://rr1.googlevideo.com/videoplayback?id=1", "audio/webm"),
            CastPlan.decide("https://rr1.googlevideo.com/videoplayback?id=1", "audio/webm; codecs=\"opus\"", false))
        assertEquals(CastSource.Remote("https://cdn.example.com/a/song.flac", "audio/flac"),
            CastPlan.decide("https://cdn.example.com/a/song.flac", null, false))
        assertEquals(CastSource.Local("file:///data/user/0/app/files/downloads/x.m4a", "audio/mp4"),
            CastPlan.decide("file:///data/user/0/app/files/downloads/x.m4a", null, false))
        assertEquals(CastSource.Local("content://media/external/audio/media/42", "audio/mpeg"),
            CastPlan.decide("content://media/external/audio/media/42", "audio/mpeg", false))
        assertEquals(CastSource.Remote("https://x.example/m.mpd", CastPlan.DASH), CastPlan.decide("https://x.example/m.mpd", null, false))
    }

    @Test fun whatTheReceiverCannotReachIsNotSent() {
        assertTrue(CastPlan.decide("https://x.example/m.mpd", null, offlineDash = true) is CastSource.Unsupported)
        assertTrue(CastPlan.decide("http://127.0.0.1:8080/stream", null, false) is CastSource.Unsupported)
        assertTrue(CastPlan.decide("http://localhost/stream", null, false) is CastSource.Unsupported)
        assertTrue(CastPlan.decide("file:///sdcard/a.mpd", null, false) is CastSource.Unsupported)
        assertTrue(CastPlan.decide("asset:///intro.mp3", null, false) is CastSource.Unsupported)
        assertTrue(CastPlan.decide(null, null, false) is CastSource.Unsupported)
    }

    @Test fun onlyTheTokenAndAHandedOutIdOpenAFile() {
        val token = "0123456789abcdef0123456789abcdef"
        assertEquals("00112233aabbccdd", LocalCastServer.idFor("/$token/00112233aabbccdd", token))
        assertEquals("00112233aabbccdd", LocalCastServer.idFor("/$token/00112233aabbccdd?x=1", token))
        assertNull(LocalCastServer.idFor("/ffff456789abcdef0123456789abcdef/00112233aabbccdd", token))
        assertNull(LocalCastServer.idFor("/$token/../00112233aabbccdd", token))
        assertNull(LocalCastServer.idFor("/$token/00112233aabbccdd/x", token))
        assertNull(LocalCastServer.idFor("/$token/%2e%2e", token))
        assertNull(LocalCastServer.idFor("/00112233aabbccdd", token))
        assertNull(LocalCastServer.idFor("", token))
    }

    @Test fun rangesAreReadAsTheReceiverSendsThem() {
        assertEquals(0L..99L, LocalCastServer.parseRange("bytes=0-", 100))
        assertEquals(10L..19L, LocalCastServer.parseRange("bytes=10-19", 100))
        assertEquals(90L..99L, LocalCastServer.parseRange("bytes=-10", 100))
        assertEquals(50L..99L, LocalCastServer.parseRange("bytes=50-500", 100))
        assertNull(LocalCastServer.parseRange("bytes=200-", 100))
        assertNull(LocalCastServer.parseRange("bytes=0-1,5-6", 100))
        assertNull(LocalCastServer.parseRange(null, 100))
    }

    @Test fun theWifiAddressIsChosenNeverMobileDataOrLoopback() {
        val wifi = InetAddress.getByName("192.168.1.23")
        val data = InetAddress.getByName("10.80.1.2")
        val list = listOf("lo" to InetAddress.getByName("127.0.0.1"), "rmnet_data0" to data, "wlan0" to wifi)
        assertEquals(wifi, LocalCastServer.pickAddress(list))
        assertNull(LocalCastServer.pickAddress(listOf("rmnet_data0" to data, "wlan0" to InetAddress.getByName("8.8.8.8"))))
    }

    @Test fun theServerAnswersWithTheFileAndARangeAndRefusesTheRest() {
        val bytes = ByteArray(1000) { (it % 251).toByte() }
        val server = LocalCastServer(InetAddress.getByName("127.0.0.1")) { uri ->
            if (uri == "file:///song.mp3") LocalCastServer.Opened(bytes.size.toLong(), ByteArrayInputStream(bytes)) else null
        }.start()
        try {
            val url = server.publish("file:///song.mp3", "audio/mpeg")
            assertTrue(url.startsWith("http://127.0.0.1:${server.port}/${server.token}/"))
            assertEquals(url, server.publish("file:///song.mp3", "audio/mpeg"))
            val whole = open(url)
            assertEquals(200, whole.responseCode)
            assertEquals("audio/mpeg", whole.contentType)
            assertEquals(bytes.toList(), whole.inputStream.readBytes().toList())
            val part = open(url) { setRequestProperty("Range", "bytes=100-199") }
            assertEquals(206, part.responseCode)
            assertEquals("bytes 100-199/1000", part.getHeaderField("Content-Range"))
            assertEquals(bytes.copyOfRange(100, 200).toList(), part.inputStream.readBytes().toList())
            assertEquals(404, open(url.replace(server.token, "f".repeat(32))).responseCode)
            assertEquals(405, open(url) { requestMethod = "DELETE" }.responseCode)
        } finally { server.stop() }
    }

    private fun open(url: String, setup: HttpURLConnection.() -> Unit = {}): HttpURLConnection =
        (URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 5000; setup() }
}
