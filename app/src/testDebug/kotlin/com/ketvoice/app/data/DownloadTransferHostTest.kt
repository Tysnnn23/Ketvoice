package com.ketvoice.app.data

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** In-memory HTTPS connection fixtures. No provider/network or device test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadTransferHostTest {
    private val original = Downloader.openAudioConnection
    private val payload = ByteArray(70_000) { 42 }.apply { "ID3".toByteArray().copyInto(this) }
    private val id = "qa:transfer"
    private lateinit var directory: File
    @Before fun setup() {
        directory = File(System.getProperty("java.io.tmpdir"), "kv-transfer-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        AppSettings.downloadsWifiOnly.value = false
        (Downloader.busy as MutableStateFlow<Set<String>>).value = setOf(id)
    }
    @After fun reset() {
        Downloader.openAudioConnection = original
        Downloader.resume(id)
        AppSettings.downloadsWifiOnly.value = false
        (Downloader.busy as MutableStateFlow<Set<String>>).value = emptySet()
        directory.listFiles()?.forEach { it.delete() }; directory.delete()
    }
    private fun destination() = File(directory,"audio.mp3")
    private open inner class Connection : HttpURLConnection(URL("https://fixture.invalid/audio")) {
        var ranged = true
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        private fun offset() = getRequestProperty("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
        override fun getResponseCode() = if (ranged && offset() > 0) 206 else 200
        override fun getContentLengthLong() = (payload.size - if (getResponseCode() == 206) offset() else 0).toLong()
        override fun getHeaderField(key: String): String? = when (key) {
            "ETag" -> "\"fixture-v1\""
            "Content-Range" -> if (getResponseCode() == 206) "bytes ${offset()}-${payload.lastIndex}/${payload.size}" else null
            else -> null
        }
        override fun getInputStream(): InputStream = ByteArrayInputStream(payload, if (getResponseCode() == 206) offset() else 0, getContentLengthLong().toInt())
    }

    @Test fun interruptedTransferResumesWithRangeAndValidator() = runBlocking {
        val requests = AtomicInteger()
        var range: String? = null
        Downloader.openAudioConnection = {
            if (requests.getAndIncrement() == 0) object : Connection() {
                override fun getInputStream() = object : InputStream() {
                    var first = true
                    override fun read(): Int = throw IOException("fixture disconnect")
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (!first) throw IOException("fixture disconnect")
                        first = false; payload.copyInto(b,off,0,10_000); return 10_000
                    }
                }
            } else object : Connection() {
                override fun getResponseCode(): Int {
                    range = getRequestProperty("Range")
                    if (range != null) assertEquals("\"fixture-v1\"", getRequestProperty("If-Range"))
                    return super.getResponseCode()
                }
            }
        }
        val dest = destination()
        assertTrue(Downloader.fetch("https://fixture.invalid/audio", dest, id))
        assertEquals("bytes=10000-",range)
        assertArrayEquals(payload,dest.readBytes())
    }

    @Test fun pauseStopsReadsAndResumeCompletesWithoutDroppingBytes() = runBlocking {
        val reads = AtomicInteger()
        Downloader.openAudioConnection = { object : Connection() {
            override fun getInputStream() = object : ByteArrayInputStream(payload) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (reads.incrementAndGet() == 1) { Downloader.pause(id); return super.read(b,off,4096) }
                    return super.read(b,off,len)
                }
            }
        } }
        val dest = destination()
        val worker = async(Dispatchers.IO) { Downloader.fetch("https://fixture.invalid/audio",dest,id) }
        withTimeout(3000) { Downloader.tasks.first { it[id]?.phase == DownloadPhase.Paused } }
        delay(250)
        assertEquals(1,reads.get())
        Downloader.resume(id)
        assertTrue(worker.await()); assertArrayEquals(payload,dest.readBytes())
    }

    @Test fun wifiOnlyMakesNoRequestUntilTheGateIsReleased() = runBlocking {
        AppSettings.downloadsWifiOnly.value = true
        val requests = AtomicInteger()
        Downloader.openAudioConnection = { requests.incrementAndGet(); Connection() }
        val worker = async(Dispatchers.IO) { Downloader.fetch("https://fixture.invalid/audio", destination(), id) }
        withTimeout(3000) { Downloader.tasks.first { it[id]?.phase == DownloadPhase.WaitingWifi } }
        assertEquals(0,requests.get())
        AppSettings.downloadsWifiOnly.value = false
        assertTrue(worker.await())
    }

    @Test fun remainingBytesAreUnknownRatherThanInvented() {
        assertNull(DownloadTask(bytes=300).remaining)
        assertEquals(200L, DownloadTask(bytes=300,total=500).remaining)
        assertEquals(0L, DownloadTask(bytes=600,total=500).remaining)
    }

    @Test fun sourceIgnoringRangeRestartsWithoutAppendingCorruptBytes() = runBlocking {
        var calls = 0
        Downloader.openAudioConnection = {
            if (calls++ == 0) object : Connection() {
                override fun getInputStream() = object : InputStream() {
                    var first = true
                    override fun read(): Int = throw IOException("fixture disconnect")
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (!first) throw IOException("fixture disconnect")
                        first = false; payload.copyInto(b,off,0,12_000); return 12_000
                    }
                }
            } else Connection().apply { ranged = false }
        }
        val dest = destination()
        assertTrue(Downloader.fetch("https://fixture.invalid/audio", dest,id))
        assertArrayEquals(payload,dest.readBytes())
        assertTrue(Downloader.tasks.value[id]!!.restarted)
    }

    @Test fun cancellationWhilePausedRemovesThePartialFile() = runBlocking {
        Downloader.openAudioConnection = { object : Connection() {
            override fun getInputStream() = object : ByteArrayInputStream(payload) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    Downloader.pause(id); return super.read(b,off,4096)
                }
            }
        } }
        val dest = destination()
        val worker = launch(Dispatchers.IO) { Downloader.fetch("https://fixture.invalid/audio",dest,id) }
        withTimeout(3000) { Downloader.tasks.first { it[id]?.phase == DownloadPhase.Paused } }
        worker.cancelAndJoin()
        assertFalse(dest.exists())
        assertFalse(File(directory,"audio.mp3.part").exists())
    }
}
