package com.ketvoice.app.data

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 7.5 speed: a big file over four connections from a source that holds each one to a given speed, and the
 * quality check that stops waiting once a lossless offer came. Fakes in memory, timed for real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadSpeedHostTest {
    private val original = Downloader.openAudioConnection
    private val id = "qa:speed"
    private val payload = ByteArray(8 * 1024 * 1024) { (it * 31 % 253).toByte() }.apply { "fLaC".toByteArray().copyInto(this) }
    /** Each connection gets this many bytes per millisecond (4 MB/s), as a throttling source does. */
    private val perConnection = 4_000
    private lateinit var directory: File

    @Before fun setup() {
        directory = File(System.getProperty("java.io.tmpdir"), "kv-speed-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        AppSettings.downloadsWifiOnly.value = false
        (Downloader.busy as MutableStateFlow<Set<String>>).value = setOf(id)
    }
    @After fun reset() {
        Downloader.openAudioConnection = original
        (Downloader.busy as MutableStateFlow<Set<String>>).value = emptySet()
        directory.deleteRecursively()
    }

    private inner class Source(val ranges: Boolean, val honour: Boolean = ranges, val opened: AtomicInteger) : HttpURLConnection(URL("https://fixture.invalid/audio.flac")) {
        init { opened.incrementAndGet() }
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        private fun asked(): LongRange? = getRequestProperty("Range")?.takeIf { honour }?.removePrefix("bytes=")?.split('-')?.let { (a, b) ->
            a.toLong()..(b.toLongOrNull() ?: payload.lastIndex.toLong()) }
        override fun getResponseCode() = if (asked() != null) 206 else 200
        override fun getContentLengthLong() = asked()?.let { it.last - it.first + 1 } ?: payload.size.toLong()
        override fun getHeaderField(key: String): String? = when (key) {
            "ETag" -> "\"v1\""
            "Accept-Ranges" -> if (ranges) "bytes" else null
            "Content-Range" -> asked()?.let { "bytes ${it.first}-${it.last}/${payload.size}" }
            else -> null
        }
        override fun getInputStream(): InputStream = object : InputStream() {
            var at = asked()?.first?.toInt() ?: 0
            val end = (asked()?.last?.toInt() ?: payload.lastIndex) + 1
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (at >= end) return -1
                val n = minOf(len, 64 * 1024, end - at)
                Thread.sleep((n / perConnection).toLong().coerceAtLeast(1))
                payload.copyInto(b, off, at, at + n); at += n; return n
            }
        }
    }

    private fun timed(ranges: Boolean, honour: Boolean = ranges): Pair<Long, Int> = runBlocking {
        val opened = AtomicInteger()
        Downloader.openAudioConnection = { Source(ranges, honour, opened) }
        val dest = File(directory, "audio-$ranges-$honour.flac")
        val start = System.nanoTime()
        assertTrue(Downloader.fetch("https://fixture.invalid/audio.flac", dest, id))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertArrayEquals("the file is whole and in order", payload, dest.readBytes())
        ms to opened.get()
    }

    @Test fun aBigFileComesOverFourConnections() {
        val (one, oneOpened) = timed(ranges = false)
        val (four, fourOpened) = timed(ranges = true)
        val (refused, refusedOpened) = timed(ranges = true, honour = false)
        println("download-speed: 8 MB at 4 MB/s per connection: one connection $one ms ($oneOpened opened), " +
            "in parts $four ms ($fourOpened opened), source refusing ranges $refused ms ($refusedOpened opened, back to one)")
        assertEquals(1, oneOpened)
        assertEquals(4, fourOpened)
        assertTrue("at least twice as fast in parts", four * 2 < one)
    }

    @Test fun theQualityCheckStopsWaitingOnceALosslessOfferCame() = runBlocking {
        val lossy = StreamResolver.Candidate("a", 0, MusicApi.StreamInfo("https://a.invalid/1.mp3", "MP3", "128 kbps"))
        val flac = StreamResolver.Candidate("b", 1, MusicApi.StreamInfo("https://b.invalid/1.flac", "FLAC", "FLAC 16-bit 44.1 kHz"))
        fun sources() = flow {
            emit(lossy); delay(100); emit(flac)
            delay(10_000) // a source that never answers
        }
        val enough: (List<StreamResolver.Candidate>) -> Boolean = { found -> found.any { DownloadUpgrade.ofStream(it.stream).lossless } }
        var start = System.nanoTime()
        val before = StreamResolver.collectWithin(sources(), 3_000L)
        val waitedBefore = (System.nanoTime() - start) / 1_000_000
        start = System.nanoTime()
        val after = StreamResolver.collectWithin(sources(), 3_000L, graceMs = 300L, enough = enough)
        val waitedAfter = (System.nanoTime() - start) / 1_000_000
        println("upgrade-check: window 3000 ms, lossless offer at 100 ms: waited $waitedBefore ms before, $waitedAfter ms with the early stop (grace 300 ms)")
        assertEquals(before.map { it.sourceId }.toSet(), after.map { it.sourceId }.toSet())
        assertTrue(waitedBefore >= 2_900)
        assertTrue(waitedAfter < 1_000)
    }
}
