package com.ketvoice.app.data

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * A big file in a few parts at once (7.5): sources that hold each connection to a given speed send a 40 MB FLAC
 * several times faster over four. Only when the source says it serves ranges and the file is big enough; the
 * first response already open carries the first part, so nothing is asked twice. A part that fails makes the
 * whole try fail, and the caller goes back to one connection.
 */
internal object RangedDownload {
    const val MIN_BYTES = 6L * 1024 * 1024
    const val CONNECTIONS = 4
    private const val BUFFER = 256 * 1024

    /** [total] bytes cut into [connections] parts of nearly the same size, as inclusive ranges. */
    fun parts(total: Long, connections: Int = CONNECTIONS): List<LongRange> {
        if (total <= 0) return emptyList()
        val n = connections.coerceIn(1, maxOf(1L, total / (256L * 1024)).toInt().coerceAtLeast(1))
        val size = total / n
        return (0 until n).map { i -> (i * size) until if (i == n - 1) total else (i + 1) * size }
    }

    /** Whether a response lets the file come in parts: ranges served and a known size worth it. */
    fun worth(total: Long, acceptRanges: String?, partial: Boolean): Boolean =
        total >= MIN_BYTES && (partial || acceptRanges?.contains("bytes", ignoreCase = true) == true)

    /**
     * Writes [total] bytes into [dest]: the first part from [first] (a response from byte 0, read only that far),
     * each other part from [open] (the bytes of its range, null when refused). [onBytes] is told what arrives.
     */
    suspend fun fetch(first: InputStream, total: Long, dest: File, open: (LongRange) -> InputStream?,
                      onBytes: (Long) -> Unit, cancelled: () -> Boolean, connections: Int = CONNECTIONS): Boolean = coroutineScope {
        RandomAccessFile(dest, "rw").use { it.setLength(total) }
        val ranges = parts(total, connections)
        ranges.mapIndexed { i, range ->
            async(Dispatchers.IO) {
                val input = (if (i == 0) first else open(range)) ?: return@async false
                input.use { copy(it, dest, range, onBytes, cancelled) }
            }
        }.awaitAll().all { it }
    }

    private fun copy(input: InputStream, dest: File, range: LongRange, onBytes: (Long) -> Unit, cancelled: () -> Boolean): Boolean {
        RandomAccessFile(dest, "rw").use { out ->
            out.seek(range.first)
            val buf = ByteArray(BUFFER)
            var left = range.last - range.first + 1
            while (left > 0) {
                if (cancelled()) throw CancellationException()
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) return false
                out.write(buf, 0, n)
                left -= n
                onBytes(n.toLong())
            }
        }
        return true
    }
}
