package com.ketvoice.desktop.canvas

import com.ketvoice.desktop.platform.AppLog
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A Canvas playing in a loop on its own thread: frames are decoded at their time and handed over one at a time
 * ([take] the newest, [give] it back once shown). Three buffers go round, so decoding never waits for drawing and no
 * frame is allocated while it plays. While paused it keeps the frame it showed; [close] ends the thread.
 */
class CanvasPlayback(private val file: File, private val maxWidth: Int, private val maxHeight: Int) : AutoCloseable {
    class Buffer(val width: Int, val height: Int, val pixels: ByteArray)

    @Volatile var playing = false
        set(value) { field = value; synchronized(lock) { (lock as Object).notifyAll() } }
    @Volatile private var closed = false
    @Volatile var aspect = 0f; private set
    /** Frames decoded so far (for the tests). */
    @Volatile var decoded = 0L; private set
    private val lock = Any()
    private val latest = AtomicReference<Buffer?>(null)
    private val free = ArrayBlockingQueue<Buffer>(3)
    private val thread = Thread(::run, "canvas-${file.nameWithoutExtension.take(16)}").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }

    fun start(): CanvasPlayback { thread.start(); return this }

    /** The newest decoded frame not shown yet, or null. */
    fun take(): Buffer? = latest.getAndSet(null)

    /** A frame [take] returned, once drawn. */
    fun give(buffer: Buffer) { free.offer(buffer) }

    private fun run() {
        try {
            VideoFrames(file, maxWidth, maxHeight).use { video ->
                aspect = video.aspect
                repeat(3) { free.offer(Buffer(video.width, video.height, ByteArray(video.width * video.height * 4))) }
                var clockStart = System.nanoTime()
                var clockBaseMs = 0L
                var wasPlaying = false
                var first = true
                while (!closed) {
                    if (!first && !playing) {
                        wasPlaying = false
                        synchronized(lock) { if (!playing && !closed) (lock as Object).wait(250) }
                        continue
                    }
                    val buffer = free.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    val at = video.next(buffer.pixels) ?: run { free.offer(buffer); return }
                    if (first || !wasPlaying) { clockStart = System.nanoTime(); clockBaseMs = at; wasPlaying = playing }
                    first = false
                    // Shown at its time: a late frame goes at once, an early one waits.
                    val wait = (at - clockBaseMs) - (System.nanoTime() - clockStart) / 1_000_000
                    if (wait > 0) Thread.sleep(wait.coerceAtMost(500))
                    if (closed) break
                    latest.getAndSet(buffer)?.let { free.offer(it) }
                    decoded++
                }
            }
        } catch (e: InterruptedException) {
            // closed
        } catch (e: Throwable) {
            AppLog.w("canvas", "cannot play ${file.name}: $e")
            failed = true
        }
    }

    @Volatile var failed = false; private set

    override fun close() {
        closed = true
        playing = false
        thread.interrupt()
    }
}
