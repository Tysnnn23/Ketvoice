package com.ketvoice.desktop.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/** Where the mixed sound goes: 16-bit little-endian interleaved PCM. */
interface PcmSink : Closeable {
    /** A real device that plays in real time (a slow decoder means silence), or a capture that waits. */
    val realtime: Boolean
    fun open(sampleRate: Int, channels: Int)
    /** Blocks until the device takes all of it. */
    fun write(pcm: ByteArray, length: Int)
    /** Frames written but not heard yet. */
    fun queuedFrames(): Int
    /** Drops what was written and not heard yet. */
    fun flush() {}
    /** Blocks until what was written has been heard. */
    fun drain() {}
    /** The output device this sink plays to now, by name (null: the system's default). */
    val device: String? get() = null
    /** Plays to the device called [name] from the next [open] (null: the system's default); false when this sink has no devices to choose. */
    fun select(name: String?): Boolean = false
}

/**
 * The sound card through Java Sound: 48 kHz 16-bit stereo, about 170 ms of buffer, on the device [select] named
 * (« Sortie audio », see [OutputDevices]); a device that is gone or refuses to open gives way to the system's default.
 */
class LineSink(private val bufferMs: Int = 170) : PcmSink {
    private var line: SourceDataLine? = null
    private var frameBytes = 4
    @Volatile private var wanted: String? = null
    @Volatile override var device: String? = null; private set
    override val realtime = true

    override fun select(name: String?): Boolean { wanted = name; return true }

    override fun open(sampleRate: Int, channels: Int) {
        val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
        frameBytes = 2 * channels
        val name = wanted
        val named = name?.let { runCatching { start(OutputDevices.line(it, format), format, sampleRate) }.getOrNull() }
        line = named ?: start(AudioSystem.getSourceDataLine(format), format, sampleRate)
        device = if (named != null) name else null
    }

    private fun start(l: SourceDataLine?, format: AudioFormat, sampleRate: Int): SourceDataLine {
        checkNotNull(l) { "no such output" }
        l.open(format, sampleRate * bufferMs / 1000 * frameBytes)
        l.start()
        return l
    }

    override fun drain() { line?.drain() }

    override fun write(pcm: ByteArray, length: Int) { line?.write(pcm, 0, length) }

    override fun queuedFrames(): Int = line?.let { (it.bufferSize - it.available()).coerceAtLeast(0) / frameBytes } ?: 0

    override fun flush() { line?.flush() }

    override fun close() { line?.let { runCatching { it.stop(); it.close() } }; line = null; device = null }
}

/** Swallows the sound (smoke runs, headless hosts); counts the frames. */
class NullSink : PcmSink {
    @Volatile var frames = 0L; private set
    private var frameBytes = 4
    override val realtime = false
    override fun open(sampleRate: Int, channels: Int) { frameBytes = 2 * channels }
    override fun write(pcm: ByteArray, length: Int) { frames += length / frameBytes }
    override fun queuedFrames() = 0
    override fun close() {}
}

/**
 * Keeps everything written, for tests: [samples] as 16-bit values, [writeWav] to look at or listen
 * to later. [onWrite] runs after each block with the frame count so far (tests act mid-stream there).
 */
class CaptureSink(var onWrite: ((Long) -> Unit)? = null) : PcmSink {
    private val bytes = java.io.ByteArrayOutputStream()
    var sampleRate = 0; private set
    var channels = 0; private set
    override val realtime = false

    override fun open(sampleRate: Int, channels: Int) { this.sampleRate = sampleRate; this.channels = channels }

    override fun write(pcm: ByteArray, length: Int) {
        val n = synchronized(bytes) { bytes.write(pcm, 0, length); bytes.size() / (2 * channels).toLong() }
        onWrite?.invoke(n)
    }

    override fun queuedFrames() = 0
    override fun close() {}

    val frames: Long get() = synchronized(bytes) { bytes.size() / (2L * channels.coerceAtLeast(1)) }

    /** Interleaved samples. */
    fun samples(): ShortArray {
        val b = synchronized(bytes) { bytes.toByteArray() }
        return ShortArray(b.size / 2) { ((b[it * 2].toInt() and 0xFF) or (b[it * 2 + 1].toInt() shl 8)).toShort() }
    }

    fun writeWav(file: File) {
        val data = synchronized(bytes) { bytes.toByteArray() }
        writeWav(file, data, sampleRate, channels)
    }

    companion object {
        fun writeWav(file: File, pcm16: ByteArray, sampleRate: Int, channels: Int) {
            RandomAccessFile(file, "rw").use { f ->
                f.setLength(0)
                fun i32(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
                fun i16(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
                f.write("RIFF".toByteArray()); i32(36 + pcm16.size); f.write("WAVEfmt ".toByteArray())
                i32(16); i16(1); i16(channels); i32(sampleRate); i32(sampleRate * channels * 2); i16(channels * 2); i16(16)
                f.write("data".toByteArray()); i32(pcm16.size); f.write(pcm16)
            }
        }
    }
}
