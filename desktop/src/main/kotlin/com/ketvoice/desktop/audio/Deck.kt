package com.ketvoice.desktop.audio

import java.io.Closeable
import java.io.File
import java.net.URI

/**
 * One source decoded ahead on its own thread into a PCM FIFO (48 kHz stereo float). The output
 * thread reads it without blocking. Phase 2's Ketmix runs two of these side by side.
 */
class Deck(
    val source: AudioSource,
    startMs: Long = 0,
    private val open: (AudioSource) -> ByteSource = ::openSource,
    private val listener: Listener? = null,
    bufferMs: Int = 3_000,
    /** « Lecture sans blanc »: the title ends on its exact last sample (see [FfmpegDecoder.exactEnd]). */
    private val exactEnd: Boolean = false,
    /** Output rate: 48 kHz, or 0 for the source's own (« Mode exclusif » bit-perfect), known once [ready]. */
    private val outRate: Int = FfmpegDecoder.OUT_RATE,
) : Closeable {
    interface Listener {
        fun onReady(deck: Deck) {}
        fun onError(deck: Deck, message: String) {}
    }

    private val lock = Object()
    private val capacity = (if (outRate > 0) outRate else MAX_NATIVE_RATE) * bufferMs / 1000
    private val startMs = startMs.coerceAtLeast(0)
    /** Frames per second of what [read] gives (the source's own rate once ready when asked for it). */
    @Volatile var rate: Int = if (outRate > 0) outRate else FfmpegDecoder.OUT_RATE; private set
    private val ring = FloatArray(capacity * 2)
    private var head = 0 // next frame to read
    private var count = 0
    private var eof = false
    private var closed = false
    private var seekMs = startMs.coerceAtLeast(0).takeIf { it > 0 } ?: -1L
    private var generation = 0

    @Volatile var format: AudioEvent.Format? = null; private set
    @Volatile var durationMs: Long = 0; private set
    @Volatile var error: String? = null; private set
    @Volatile var ready = false; private set
    /** Frames (48 kHz) from the track's start of the next frame [read] returns. */
    @Volatile var positionFrames: Long = startMs.coerceAtLeast(0) * rate / 1000; private set

    /** How the mixer reads this deck (Ketmix levels, filters and tempo); writer thread only. */
    val lane = Lane(this)

    private val thread = Thread(::decodeLoop, "ketvoice-deck").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 2; start() }
    private var byteSource: ByteSource? = null

    /** Played to the end, or failed: nothing more will come. */
    val ended: Boolean get() = synchronized(lock) { (eof && count == 0) || error != null }

    val availableFrames: Int get() = synchronized(lock) { count }

    /** Copies up to [frames] frames into [out] at frame [offset]; never blocks. */
    fun read(out: FloatArray, offset: Int, frames: Int): Int = synchronized(lock) {
        val n = minOf(frames, count)
        var done = 0
        while (done < n) {
            val run = minOf(n - done, capacity - head)
            System.arraycopy(ring, head * 2, out, (offset + done) * 2, run * 2)
            head = (head + run) % capacity; done += run
        }
        count -= n
        positionFrames += n
        if (n > 0) lock.notifyAll()
        n
    }

    /** Waits until [frames] frames are buffered or nothing more will come, at most [timeoutMs]. */
    fun awaitData(frames: Int, timeoutMs: Long) {
        val until = System.nanoTime() + timeoutMs * 1_000_000
        synchronized(lock) {
            while (count < minOf(frames, capacity) && !eof && error == null && !closed) {
                val left = (until - System.nanoTime()) / 1_000_000
                if (left <= 0) return
                lock.wait(left)
            }
        }
    }

    /** Drops the buffer and decodes again from [ms]. */
    fun seek(ms: Long) = synchronized(lock) {
        val to = ms.coerceAtLeast(0).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
        head = 0; count = 0; eof = false
        seekMs = to; generation++
        positionFrames = to * rate / 1000
        lock.notifyAll()
    }

    override fun close() {
        synchronized(lock) { if (closed) return; closed = true; lock.notifyAll() }
        // Unblocks a network read in progress; the decoding thread frees FFmpeg's side itself.
        runCatching { byteSource?.close() }
    }

    private fun decodeLoop() {
        var decoder: FfmpegDecoder? = null
        try {
            val bytes = open(source)
            synchronized(lock) { byteSource = bytes; if (closed) { bytes.close(); return } }
            decoder = FfmpegDecoder(bytes, exactEnd, outRate)
            if (decoder.outRate != rate) synchronized(lock) {
                rate = decoder.outRate
                if (count == 0) positionFrames = (if (seekMs >= 0) seekMs else startMs) * rate / 1000
            }
            format = decoder.format
            durationMs = decoder.durationMs
            ready = true
            listener?.onReady(this)
            val chunk = FloatArray(2048 * 2)
            while (true) {
                var gen: Int
                var room: Int
                var seek: Long
                synchronized(lock) {
                    while (!closed && seekMs < 0 && (count == capacity || eof)) lock.wait()
                    if (closed) return
                    gen = generation; seek = seekMs; seekMs = -1; room = capacity - count
                }
                if (seek >= 0) { decoder.seek(seek); continue }
                val n = decoder.read(chunk, 0, minOf(2048, room))
                synchronized(lock) {
                    if (gen != generation || closed) return@synchronized
                    if (n < 0) { eof = true; lock.notifyAll(); return@synchronized }
                    var done = 0
                    var tail = (head + count) % capacity
                    while (done < n) {
                        val run = minOf(n - done, capacity - tail)
                        System.arraycopy(chunk, done * 2, ring, tail * 2, run * 2)
                        tail = (tail + run) % capacity; done += run
                    }
                    count += n
                    lock.notifyAll()
                }
            }
        } catch (t: Throwable) {
            val closedNow = synchronized(lock) { closed }
            if (!closedNow) {
                error = t.message ?: t.toString()
                synchronized(lock) { lock.notifyAll() }
                listener?.onError(this, error!!)
            }
        } finally {
            runCatching { decoder?.close() }
            runCatching { byteSource?.close() }
        }
    }

    companion object {
        /** The ring of a deck at its source's own rate is sized for this rate. */
        const val MAX_NATIVE_RATE = 192_000

        fun openSource(source: AudioSource): ByteSource = when (source) {
            is AudioSource.LocalFile -> FileByteSource(File(source.path))
            is AudioSource.Url ->
                if (source.url.startsWith("file:")) FileByteSource(File(URI(source.url)))
                else HttpRangeSource(source.url, source.headers, key = source.cacheKey, refresh = source.refresh)
        }
    }
}
