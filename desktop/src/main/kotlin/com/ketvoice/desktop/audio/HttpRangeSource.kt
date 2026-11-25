package com.ketvoice.desktop.audio

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.BitSet
import java.util.concurrent.TimeUnit

/**
 * An HTTP stream read by ranges, for FFmpeg's seeks. A fetcher thread downloads ahead of the reader
 * in [CHUNK]-sized pieces into a sparse file under `Data/cache/audio` (the OS keeps the recent pieces
 * in memory), so the whole track ends up on disk and a later play or seek needs no network. A dropped
 * connection is reopened at the first missing byte, up to [retries] times in a row; a seek far from
 * the fetch point restarts the request there. The cache survives restarts (`.idx` beside the `.bin`).
 */
class HttpRangeSource(
    url: String,
    private val headers: Map<String, String> = emptyMap(),
    cacheDir: File? = StreamCache.folder(),
    private val client: OkHttpClient = defaultClient,
    private val retries: Int = 8,
    private val retryDelayMs: Long = 250,
    /** The smart cache's name for these bytes ([StreamCache.key]); null names them by address ([cacheKey]). */
    key: String? = null,
    /** A fresh address when [url] is refused or answers other bytes (an address kept from an earlier play). */
    private val refresh: (() -> String?)? = null,
) : ByteSource {
    @Volatile private var url: String = url
    /** Whether [url] may be stale: true until a request with it has answered with the expected bytes. */
    @Volatile private var mayRefresh = refresh != null
    private val lock = Object()
    private val have = BitSet()
    private var total = -1L
    private var known = false
    private var error: IOException? = null
    private var closed = false
    /** Where the reader last waited: the fetcher goes there. */
    private var want = 0L
    private val bin: File
    private val idx: File
    private val file: RandomAccessFile
    private val fetcher: Thread

    init {
        val dir = cacheDir ?: File(System.getProperty("java.io.tmpdir"), "ketvoice-audio")
        dir.mkdirs()
        val name = if (key != null) StreamCache.name(key) else sha1(cacheKey(url))
        bin = File(dir, "$name.bin"); idx = File(dir, "$name.idx")
        StreamCache.trim(dir, keep = setOf(name))
        if (bin.exists()) StreamCache.readIndex(idx)?.let { index ->
            total = index.total; known = total >= 0; have.or(index.have)
        }
        StreamCache.touch(bin)
        file = RandomAccessFile(bin, "rw")
        fetcher = Thread(::fetchLoop, "ketvoice-http-range").apply { isDaemon = true; start() }
    }

    override val size: Long
        get() = synchronized(lock) {
            while (!known && error == null && !closed) lock.wait(100)
            total
        }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, len: Int): Int = synchronized(lock) {
        while (true) {
            if (closed) throw IOException("closed")
            if (known && total >= 0 && position >= total) return -1
            val c = (position / CHUNK).toInt()
            if (have[c]) {
                val end = if (known && total >= 0) minOf(total, (c + 1L) * CHUNK) else (c + 1L) * CHUNK
                val n = minOf(len.toLong(), end - position).toInt()
                if (n <= 0) return -1
                file.seek(position)
                return file.read(buffer, offset, n)
            }
            error?.let { throw IOException("stream failed: ${it.message}", it) }
            if (want != position) { want = position; lock.notifyAll() }
            lock.wait(200)
        }
        @Suppress("UNREACHABLE_CODE")
        -1
    }

    /** Whole file on disk. */
    val complete: Boolean get() = synchronized(lock) { known && total >= 0 && have.nextClearBit(0).toLong() * CHUNK >= total }

    override fun close() {
        synchronized(lock) { if (closed) return; closed = true; lock.notifyAll() }
        fetcher.interrupt()
        synchronized(lock) { saveIndex(); runCatching { file.close() } }
    }

    private fun chunks(): Int = if (total < 0) Int.MAX_VALUE else ((total + CHUNK - 1) / CHUNK).toInt()

    /** The next piece to fetch: the first missing one from the reader's position, then the holes before it. */
    private fun nextMissing(): Int? {
        val from = (want / CHUNK).toInt()
        val a = have.nextClearBit(from)
        if (a < chunks()) return a
        val b = have.nextClearBit(0)
        return if (b < chunks() && b < from) b else null
    }

    private fun fetchLoop() {
        var failures = 0
        val piece = ByteArray(CHUNK)
        while (true) {
            val start = synchronized(lock) {
                while (!closed && known && nextMissing() == null) { saveIndex(); lock.wait() }
                if (closed) return
                nextMissing() ?: return
            }
            try {
                fetchFrom(start, piece) { failures = 0 }
            } catch (e: IOException) {
                if (synchronized(lock) { closed }) return
                // An address kept from an earlier play that no longer answers: a fresh one, once.
                if (e is Fatal && mayRefresh) {
                    mayRefresh = false
                    val fresh = runCatching { refresh?.invoke() }.getOrNull()
                    if (fresh != null) { url = fresh; continue }
                }
                if (e is Fatal || ++failures > retries) {
                    synchronized(lock) { error = e; known = true; lock.notifyAll() }
                    return
                }
                try { Thread.sleep(retryDelayMs * failures) } catch (_: InterruptedException) { return }
            } catch (_: InterruptedException) { return }
        }
    }

    /** One request from piece [first]; returns when it ends, when the reader jumps elsewhere or on a cached piece. */
    private fun fetchFrom(first: Int, piece: ByteArray, progress: () -> Unit) {
        val from = first.toLong() * CHUNK
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            header("Range", "bytes=$from-")
            header("Accept-Encoding", "identity")
        }.build()
        client.newCall(request).execute().use { response ->
            val code = response.code
            if (code == 416) { synchronized(lock) { if (!known) { total = from; known = true }; lock.notifyAll() }; return }
            if (code !in 200..299) {
                val e = IOException("HTTP $code")
                throw if (code in 400..499 && code != 408 && code != 429) Fatal(e) else e
            }
            val body = response.body ?: throw IOException("empty body")
            var length = -1L
            var skip = 0L
            if (code == 206) {
                length = response.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1
            } else {
                // The server ignored the range: the body starts at 0.
                length = body.contentLength(); skip = from
            }
            synchronized(lock) {
                if (known && total >= 0 && length >= 0 && length != total) {
                    // Other bytes than those on disk: from a kept address, ask for a fresh one; else the file changed.
                    if (mayRefresh) throw Fatal(IOException("other bytes ($length, not $total)"))
                    have.clear()
                }
                if (length >= 0 || !known) { total = length; known = true; if (length >= 0) runCatching { file.setLength(length) } }
                mayRefresh = false
                lock.notifyAll()
            }
            val input = body.byteStream()
            while (skip > 0) { val s = input.skip(skip); if (s <= 0) throw IOException("short body"); skip -= s }
            var c = first
            while (true) {
                var filled = 0
                while (filled < CHUNK) {
                    val n = input.read(piece, filled, CHUNK - filled)
                    if (n < 0) break
                    filled += n
                }
                val last = filled < CHUNK
                synchronized(lock) {
                    if (closed) return
                    if (last) {
                        val end = c.toLong() * CHUNK + filled
                        // A body shorter than announced is a dropped connection: the partial piece is fetched again.
                        if (total >= 0 && end < total) throw IOException("connection dropped at $end of $total")
                        if (total < 0) total = end
                    }
                    if (filled > 0) { file.seek(c.toLong() * CHUNK); file.write(piece, 0, filled); have.set(c) }
                    lock.notifyAll()
                    progress()
                    if (last) return
                    c++
                    // Go where the reader is when it jumped away, and never fetch twice.
                    val w = (want / CHUNK).toInt()
                    if (!have[w] && (w < c || w > c + 2)) return
                    if (c >= chunks() || have[c]) return
                }
            }
        }
    }

    private fun saveIndex() {
        runCatching { StreamCache.writeIndex(idx, StreamCache.Index(total, have.clone() as BitSet, url)) }
    }

    private class Fatal(cause: IOException) : IOException(cause.message, cause)

    companion object {
        const val CHUNK = 64 * 1024

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true).build()
        }

        fun defaultCacheDir(): File = File(android.content.Context.app.cacheDir, "audio")

        /** Streaming URLs carry expiring signatures; the track itself is named by `id` and `itag` when present. */
        fun cacheKey(url: String): String {
            val q = url.substringAfter('?', "")
            if (q.isEmpty()) return url
            val params = q.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            val id = params["id"]; val itag = params["itag"]
            return if (id != null && itag != null) "${url.substringBefore('?').substringBeforeLast('/')}|$id|$itag" else url
        }

        private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
