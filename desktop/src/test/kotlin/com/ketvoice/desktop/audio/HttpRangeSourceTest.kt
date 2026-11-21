package com.ketvoice.desktop.audio

import com.ketvoice.desktop.audio.AudioFixtures.Recorder
import com.ketvoice.desktop.audio.AudioFixtures.firstLoud
import com.ketvoice.desktop.audio.AudioFixtures.frequency
import com.ketvoice.desktop.audio.AudioFixtures.lastLoud
import com.ketvoice.desktop.audio.AudioFixtures.left
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The range reader against a tiny local server that honours `Range` and can drop a connection mid-body. */
class HttpRangeSourceTest {
    private class Server(val body: ByteArray) {
        val ranges: MutableList<String> = Collections.synchronizedList(ArrayList())
        val agents: MutableList<String> = Collections.synchronizedList(ArrayList())
        /** The next responses stop after this many bytes and close the connection. */
        @Volatile var dropAfter = -1
        @Volatile var drops = 0
        val http: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/track") { ex ->
                val range = ex.requestHeaders.getFirst("Range") ?: ""
                ranges += range; agents += ex.requestHeaders.getFirst("User-Agent") ?: ""
                val from = Regex("bytes=(\\d+)-").find(range)?.groupValues?.get(1)?.toInt() ?: 0
                ex.responseHeaders.add("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                val cut = dropAfter
                if (cut >= 0 && drops > 0) {
                    // Chunked, then ended early: the body stops short of the length Content-Range announced.
                    drops--
                    ex.sendResponseHeaders(206, 0)
                    runCatching { ex.responseBody.write(body, from, minOf(cut, body.size - from)) }
                    runCatching { ex.close() }
                } else {
                    ex.sendResponseHeaders(if (range.isEmpty()) 200 else 206, (body.size - from).toLong())
                    runCatching { ex.responseBody.write(body, from, body.size - from); ex.close() }
                }
            }
            executor = java.util.concurrent.Executors.newCachedThreadPool()
            start()
        }
        val url get() = "http://127.0.0.1:${http.address.port}/track?id=abc&itag=251&expire=${Random.nextInt()}"
        fun stop() = http.stop(0)
    }

    private val servers = ArrayList<Server>()
    private val cache: File = Files.createTempDirectory("kv-audio-cache").toFile()
    @After fun tearDown() { servers.forEach { it.stop() }; cache.deleteRecursively() }

    private fun server(body: ByteArray) = Server(body).also { servers += it }

    @Test fun readsEveryByteThroughADroppedConnection() {
        val body = Random(7).nextBytes(700_000)
        val s = server(body).apply { dropAfter = 150_000; drops = 1 }
        val src = HttpRangeSource(s.url, mapOf("User-Agent" to "KetvoiceTest/1"), cacheDir = cache, retryDelayMs = 10)
        try {
            assertEquals(body.size.toLong(), src.size)
            val got = ByteArray(body.size)
            var p = 0
            while (p < body.size) { val n = src.readAt(p.toLong(), got, p, minOf(9_999, body.size - p)); assertTrue(n > 0); p += n }
            assertContentEquals(body, got)
            // Random reads, including far seeks.
            val r = Random(3)
            repeat(50) {
                val at = r.nextInt(body.size - 5000); val buf = ByteArray(4000)
                val n = src.readAt(at.toLong(), buf, 0, 4000)
                assertContentEquals(body.copyOfRange(at, at + n), buf.copyOf(n))
            }
            assertEquals(-1, src.readAt(body.size.toLong(), ByteArray(10), 0, 10))
            assertTrue(s.ranges.size >= 2, "reconnected: ${s.ranges}")
            val resumed = s.ranges[1].removePrefix("bytes=").removeSuffix("-").toLong()
            assertTrue(resumed in 1..150_000 && resumed % HttpRangeSource.CHUNK == 0L, "resumed at $resumed")
            assertTrue(s.agents.all { it == "KetvoiceTest/1" }, "headers: ${s.agents}")
            Thread.sleep(100)
            assertTrue(src.complete)
        } finally { src.close() }
        // Fully cached: a new reader with another signature needs no server at all.
        s.stop()
        val again = HttpRangeSource(s.url, cacheDir = cache)
        try {
            val buf = ByteArray(1000)
            assertEquals(1000, again.readAt(600_000, buf, 0, 1000))
            assertContentEquals(body.copyOfRange(600_000, 601_000), buf)
        } finally { again.close() }
    }

    @Test fun seekFarAheadStartsARequestThere() {
        val body = Random(9).nextBytes(2_000_000)
        val s = server(body)
        val src = HttpRangeSource(s.url, cacheDir = cache)
        try {
            val buf = ByteArray(100)
            src.readAt(1_900_000, buf, 0, 100)
            assertContentEquals(body.copyOfRange(1_900_000, 1_900_100), buf)
            assertTrue(s.ranges.any { it.startsWith("bytes=18") }, "${s.ranges}")
        } finally { src.close() }
    }

    @Test fun failsAfterTooManyDrops() {
        val s = server(Random(1).nextBytes(300_000)).apply { dropAfter = 0; drops = 100 }
        val src = HttpRangeSource(s.url, cacheDir = cache, retries = 2, retryDelayMs = 5)
        try {
            val e = runCatching { src.readAt(0, ByteArray(10), 0, 10) }.exceptionOrNull()
            assertTrue(e is java.io.IOException, "got $e")
        } finally { src.close() }
    }

    @Test fun enginePlaysAStreamWithAConnectionDrop() {
        val file = AudioFixtures.tone("flac", ms = 10_000, rate = 48_000)
        val s = server(file.readBytes()).apply { dropAfter = (file.length() / 2).toInt(); drops = 1 }
        val sink = CaptureSink()
        val engine = FfmpegAudioEngine(sink) { src ->
            (src as AudioSource.Url).let { HttpRangeSource(it.url, it.headers, cacheDir = cache, retryDelayMs = 10) }
        }
        val rec = Recorder(engine)
        try {
            engine.load(AudioSource.Url(s.url, mapOf("User-Agent" to "KetvoiceTest/2")))
            rec.await { it is AudioEvent.Ended || it is AudioEvent.Error }
            assertTrue(rec.events.none { it is AudioEvent.Error }, "${rec.events}")
            val x = left(sink.samples())
            val ms = (lastLoud(x) - firstLoud(x)) * 1000.0 / 48_000
            assertTrue(abs(ms - 10_000) <= 20, "lasts $ms ms")
            assertTrue(abs(frequency(x, 48_000, 4_800, x.size - 4_800) - 440) < 9)
            assertEquals("flac", rec.events.filterIsInstance<AudioEvent.Format>().single().codec)
            assertTrue(s.ranges.size >= 2 && s.agents.all { it == "KetvoiceTest/2" }, "${s.ranges} ${s.agents}")
        } finally { rec.stop(); engine.release() }
    }

    @Test fun cacheKeyIgnoresTheSignature() {
        val a = HttpRangeSource.cacheKey("https://r1.example/videoplayback?expire=1&id=o-AB&itag=251&sig=x")
        val b = HttpRangeSource.cacheKey("https://r1.example/videoplayback?sig=y&itag=251&expire=2&id=o-AB")
        assertEquals(a, b)
        assertTrue(HttpRangeSource.cacheKey("https://x/a.mp3?t=1") != HttpRangeSource.cacheKey("https://x/a.mp3?t=2"))
    }
}
