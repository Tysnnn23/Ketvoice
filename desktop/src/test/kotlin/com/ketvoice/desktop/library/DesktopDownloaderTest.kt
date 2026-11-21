package com.ketvoice.desktop.library

import com.ketvoice.app.data.DownloadPhase
import com.ketvoice.app.data.DownloadQueue
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.QueueState
import com.ketvoice.app.data.Track
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.random.Random

/** The downloader against a local server that serves ranges (`If-Range` on an ETag), slowly on demand. */
class DesktopDownloaderTest {
    private lateinit var server: HttpServer
    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private val files = HashMap<String, ByteArray>()
    private val ranges = CopyOnWriteArrayList<String>()
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val track = Track("um:42", "Chanson", "Artiste", "Album", durationMs = 200_000, coverUrl = "https://cover.test/x.jpg")

    private fun mp3(size: Int) = ByteArray(size).also { Random(size).nextBytes(it); "ID3".toByteArray().copyInto(it) }
    private fun flac(size: Int) = ByteArray(size).also {
        Random(size).nextBytes(it)
        byteArrayOf(0x66, 0x4C, 0x61, 0x43, 0, 0, 0, 34).copyInto(it)
        // STREAMINFO sample rate 96000 (20 bits from byte 18).
        it[18] = (96000 shr 12).toByte(); it[19] = ((96000 shr 4) and 0xFF).toByte(); it[20] = ((96000 and 0xF) shl 4).toByte()
    }

    @Before fun setUp() {
        dir = Files.createTempDirectory("kv-downloads").toFile()
        files["/song.mp3"] = mp3(300_000)
        files["/slow.mp3"] = mp3(1_500_000)
        files["/big.mp3"] = mp3(7_000_000)
        files["/page.html"] = ByteArray(20_000) { '<'.code.toByte() }
        files["/fake.flac"] = mp3(400_000)
        files["/real.flac"] = flac(400_000)
        files["/cover.jpg"] = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/") { serve(it) }
            start()
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        DesktopDownloader.resetForTest()
        DesktopDownloader.allowPlainHttp = true
        DesktopDownloader.lyrics = { Triple("LRCLIB", listOf(MusicApi.LyricLine(1_000, "Bonjour"), MusicApi.LyricLine(4_500, "Salut")), "lrclib:1") }
        DesktopDownloader.coverOf = { "$base/cover.jpg" }
        DesktopDownloader.init(dir, scope)
    }

    @After fun tearDown() {
        DesktopDownloader.resetForTest()
        scope.cancel()
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun serve(x: HttpExchange) {
        val body = files[x.requestURI.path] ?: run { x.sendResponseHeaders(404, -1); x.close(); return }
        val etag = "\"v1\""
        val range = x.requestHeaders.getFirst("Range")
        range?.let { ranges += it }
        val ifRange = x.requestHeaders.getFirst("If-Range")
        val m = range?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }?.takeIf { ifRange == null || ifRange == etag }
        val start = m?.groupValues?.get(1)?.toInt() ?: 0
        val end = m?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toInt() ?: (body.size - 1)
        x.responseHeaders.add("ETag", etag)
        x.responseHeaders.add("Accept-Ranges", "bytes")
        if (m != null) x.responseHeaders.add("Content-Range", "bytes $start-$end/${body.size}")
        x.sendResponseHeaders(if (m != null) 206 else 200, (end - start + 1).toLong())
        runCatching {
            x.responseBody.use { out ->
                var at = start
                while (at <= end) {
                    val n = minOf(16_384, end - at + 1)
                    out.write(body, at, n); out.flush(); at += n
                    if (x.requestURI.path == "/slow.mp3") Thread.sleep(15)
                }
            }
        }
        x.close()
    }

    private fun offer(path: String, format: String = "mp3", quality: String = "128 kbps") {
        DesktopDownloader.candidates = { _, _ -> listOf(MusicApi.StreamInfo("$base$path", format, quality)) }
    }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, check: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!check()) { if (System.currentTimeMillis() > until) error("timed out: $what"); Thread.sleep(10) }
    }

    private fun phase() = DesktopDownloader.progress(track.id).value?.phase
    private fun stem() = DesktopDownloader.key(track.id)

    @Test fun downloadSavesTheFileWithLyricsAndCover() {
        offer("/song.mp3")
        DesktopDownloader.download(track)
        waitFor("complete") { phase() == DownloadPhase.Complete }
        val item = DesktopDownloader.items.value.single()
        assertArrayEquals(files["/song.mp3"], item.file.readBytes())
        assertEquals("${stem()}.mp3", item.file.name)
        assertEquals("MP3 · 128 kbps", item.format)
        assertTrue(File(dir, "${stem()}.lrc").readText().startsWith("[00:01.00]Bonjour"))
        assertArrayEquals(files["/cover.jpg"], File(dir, "${stem()}.cover.jpg").readBytes())
        assertEquals(listOf("Bonjour", "Salut"), DesktopDownloader.localLyrics(track.id)?.second?.map { it.text })
        assertEquals(item.file, DesktopDownloader.fileOf(track.id))
        assertTrue(item.track.streamUrl!!.startsWith("file:"))
        // The listing survives a restart, and the queue holds nothing left to do.
        DesktopDownloader.resetForTest(); DesktopDownloader.allowPlainHttp = true
        DesktopDownloader.init(dir, scope)
        assertEquals(track.id, DesktopDownloader.items.value.single().track.id)
        assertEquals(emptyList<Any>(), DownloadQueue.decode(File(dir, "download-queue.json").readText()))
    }

    @Test fun pauseKeepsThePartAndResumeGoesOnFromIt() {
        offer("/slow.mp3")
        DesktopDownloader.download(track)
        waitFor("bytes") { (DesktopDownloader.progress(track.id).value?.bytes ?: 0) > 200_000 }
        DesktopDownloader.pause(track.id)
        waitFor("paused") { phase() == DownloadPhase.Paused }
        val part = File(dir, "${stem()}.mp3.part")
        val kept = part.length()
        assertTrue("kept $kept", kept in 1 until 1_500_000)
        assertEquals(QueueState.Paused, DownloadQueue.decode(File(dir, "download-queue.json").readText()).single().state)
        ranges.clear()
        DesktopDownloader.resume(track.id)
        waitFor("complete") { phase() == DownloadPhase.Complete }
        val from = ranges.first().removePrefix("bytes=").removeSuffix("-").toLong()
        assertTrue("resumed from $from", from >= kept && from > 0)
        assertArrayEquals(files["/slow.mp3"], DesktopDownloader.fileOf(track.id)!!.readBytes())
        assertFalse(part.exists())
    }

    @Test fun cancelDropsEverything() {
        offer("/slow.mp3")
        DesktopDownloader.download(track)
        waitFor("bytes") { (DesktopDownloader.progress(track.id).value?.bytes ?: 0) > 100_000 }
        DesktopDownloader.cancel(track.id)
        waitFor("cancelled") { phase() == DownloadPhase.Cancelled }
        Thread.sleep(100)
        assertEquals(emptyList<String>(), dir.list()!!.filter { it.startsWith(stem()) })
        assertTrue(DesktopDownloader.items.value.isEmpty())
        assertEquals(emptyList<Any>(), DownloadQueue.decode(File(dir, "download-queue.json").readText()))
    }

    @Test fun somethingThatIsNotAudioFailsAndLeavesNothing() {
        offer("/page.html")
        DesktopDownloader.download(track)
        waitFor("failed") { phase() == DownloadPhase.Failed }
        assertEquals(emptyList<String>(), dir.list()!!.filter { it.startsWith(stem()) })
        assertEquals(QueueState.Failed, DownloadQueue.decode(File(dir, "download-queue.json").readText()).single().state)
    }

    @Test fun aBigFileComesInParts() {
        offer("/big.mp3")
        DesktopDownloader.download(track)
        waitFor("complete", 30_000) { phase() == DownloadPhase.Complete }
        assertArrayEquals(files["/big.mp3"], DesktopDownloader.fileOf(track.id)!!.readBytes())
        assertTrue("ranges $ranges", ranges.size >= 3)
    }

    @Test fun upgradeSwapsOnlyAVerifiedBetterFile() = runBlocking {
        offer("/song.mp3")
        DesktopDownloader.download(track)
        waitFor("complete") { phase() == DownloadPhase.Complete }
        val old = DesktopDownloader.fileOf(track.id)!!
        // Same quality: not worth it.
        assertFalse(DesktopDownloader.upgrade(track.id))
        // A « FLAC » that is not one: refused, the saved file stays as it was.
        offer("/fake.flac", "flac", "Lossless")
        assertFalse(DesktopDownloader.upgrade(track.id))
        assertArrayEquals(files["/song.mp3"], old.readBytes())
        assertEquals("MP3 · 128 kbps", DesktopDownloader.items.value.single().format)
        assertEquals(emptyList<String>(), dir.list()!!.filter { it.contains(".upgrade.") || it.endsWith(".bak") })
        // A real FLAC: swapped in, the old file goes last.
        offer("/real.flac", "flac", "Lossless")
        assertTrue(DesktopDownloader.upgrade(track.id))
        val item = DesktopDownloader.items.value.single()
        assertEquals("${stem()}.flac", item.file.name)
        assertEquals("FLAC · Sans perte", item.format)
        assertArrayEquals(files["/real.flac"], item.file.readBytes())
        assertFalse(old.exists())
        assertNotNull(DesktopDownloader.localLyrics(track.id))
    }
}
