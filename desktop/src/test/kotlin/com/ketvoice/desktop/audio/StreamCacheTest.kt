package com.ketvoice.desktop.audio

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.BitSet
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The smart cache: least recently played out first, titles found again under a fresh address, an instant start. */
class StreamCacheTest {
    private val dir: File = Files.createTempDirectory("kv-stream-cache").toFile()
    private val servers = ArrayList<HttpServer>()
    @After fun tearDown() { servers.forEach { it.stop(0) }; dir.deleteRecursively() }

    private fun entry(name: String, bytes: Int, playedAt: Long, complete: Boolean = true) {
        val bin = File(dir, "$name.bin").apply { writeBytes(ByteArray(bytes)) }
        val have = BitSet().apply { set(0, (bytes + HttpRangeSource.CHUNK - 1) / HttpRangeSource.CHUNK) }
        if (!complete) have.clear(1, have.length())
        StreamCache.writeIndex(File(dir, "$name.idx"), StreamCache.Index(bytes.toLong(), have, "https://cdn.example.com/$name"))
        bin.setLastModified(playedAt)
    }

    @Test fun leastRecentlyPlayedGoesFirstUntilUnderTheLimit() {
        val mb = 1 shl 20
        entry("old", 3 * mb, 1_000)
        entry("mid", 3 * mb, 2_000)
        entry("new", 3 * mb, 3_000)
        entry("playing", 3 * mb, 500)
        val freed = StreamCache.trim(dir, keep = setOf("playing"), limit = 7L * mb)
        assertEquals(6L * mb, freed)
        assertEquals(setOf("new.bin", "playing.bin"), dir.listFiles { f -> f.name.endsWith(".bin") }!!.map { it.name }.toSet())
        assertTrue(!File(dir, "old.idx").exists() && !File(dir, "mid.idx").exists(), "indexes go with their files")
        // A title played again moves to the back of the line.
        StreamCache.touch(File(dir, "playing.bin"))
        StreamCache.trim(dir, limit = 3L * mb)
        assertEquals(listOf("playing.bin"), dir.listFiles { f -> f.name.endsWith(".bin") }!!.map { it.name })
    }

    @Test fun sparseFilesCountByWhatIsReallyOnDisk() {
        entry("partial", 4 * HttpRangeSource.CHUNK, 1_000, complete = false)
        val stats = StreamCache.stats(dir)
        assertEquals(1, stats.titles)
        assertEquals(0, stats.complete)
        assertEquals(HttpRangeSource.CHUNK.toLong(), stats.bytes)
        entry("whole", 2 * HttpRangeSource.CHUNK, 2_000)
        assertEquals(1, StreamCache.stats(dir).complete)
        StreamCache.clear(dir)
        assertEquals(0, StreamCache.stats(dir).titles)
    }

    @Test fun aTitleIsFoundAgainByNameAndStartsFromDisk() {
        val body = Random(11).nextBytes(5 * HttpRangeSource.CHUNK + 123)
        var hits = 0
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/a") { ex ->
                hits++
                val from = Regex("bytes=(\\d+)-").find(ex.requestHeaders.getFirst("Range") ?: "")?.groupValues?.get(1)?.toInt() ?: 0
                ex.responseHeaders.add("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
                ex.sendResponseHeaders(206, (body.size - from).toLong())
                runCatching { ex.responseBody.write(body, from, body.size - from); ex.close() }
            }
            createContext("/expired") { ex -> ex.sendResponseHeaders(403, -1); ex.close() }
            start()
        }.also { servers += it }
        val base = "http://127.0.0.1:${http.address.port}"
        StreamCache.dir = dir
        try {
            val key = StreamCache.key("yt:abc", "Sans perte")
            // First play: under the title's name, whatever its (signed, changing) address.
            HttpRangeSource("$base/a?sig=1", cacheDir = dir, key = key).use { src ->
                val buf = ByteArray(body.size)
                var p = 0
                while (p < body.size) { val n = src.readAt(p.toLong(), buf, p, body.size - p); assertTrue(n > 0); p += n }
                assertContentEquals(body, buf)
            }
            val (_, index) = StreamCache.lookup(key)!!
            assertTrue(index.complete)
            assertEquals("$base/a?sig=1", index.url)
            // Whole on disk: it plays as a file, without asking anyone.
            assertIs<AudioSource.LocalFile>(StreamCache.ready("yt:abc", "Sans perte", emptyMap(), refresh = null, minHead = 1))
            assertNull(StreamCache.ready("yt:abc", "Haute", emptyMap(), refresh = null), "another quality is another file")
            // Only its start on disk, with an address that expired: it starts, then a fresh address takes over.
            val partial = File(dir, StreamCache.name(key) + ".idx")
            val have = BitSet().apply { set(0, 2) }
            StreamCache.writeIndex(partial, StreamCache.Index(body.size.toLong(), have, "$base/expired"))
            var refreshed = 0
            val start = StreamCache.ready("yt:abc", "Sans perte", emptyMap(), refresh = { refreshed++; "$base/a?sig=2" }, minHead = 1)
            assertIs<AudioSource.Url>(start)
            HttpRangeSource(start.url, cacheDir = dir, key = start.cacheKey, refresh = start.refresh).use { src ->
                val head = ByteArray(1000)
                assertEquals(1000, src.readAt(0, head, 0, 1000))
                assertContentEquals(body.copyOfRange(0, 1000), head)
                val tail = ByteArray(100)
                assertEquals(100, src.readAt(body.size - 100L, tail, 0, 100))
                assertContentEquals(body.copyOfRange(body.size - 100, body.size), tail)
            }
            assertEquals(1, refreshed)
        } finally { StreamCache.dir = null }
    }
}
