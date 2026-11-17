package com.ketvoice.app.automix

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * A compact picture of a stretch of a title's sound for the Ketmix DJ editor (6.1): one column every
 * [COLUMN_MS], each with its peak, its level (RMS) and how much of that level is bass and treble, so
 * that the editor can colour it by band. Values are kept as the square root of the linear level in a
 * byte (quiet parts keep some resolution); [level] and friends give them back between 0 and 1.
 */
internal class Wave(val startMs: Long, val peak: ByteArray, val rms: ByteArray, val low: ByteArray, val high: ByteArray) {
    init { require(rms.size == peak.size && low.size == peak.size && high.size == peak.size) }
    val columns: Int get() = peak.size
    val endMs: Long get() = startMs + columns * COLUMN_MS
    val lengthMs: Long get() = columns * COLUMN_MS

    private fun decode(b: Byte): Float { val v = (b.toInt() and 0xFF) / 255f; return v * v }
    /** Peak of column [i], linear 0..1. */
    fun peakAt(i: Int): Float = decode(peak[i])
    /** RMS of column [i], linear 0..1. */
    fun level(i: Int): Float = decode(rms[i])
    /** RMS of the bass (under about 200 Hz) of column [i]. */
    fun lowAt(i: Int): Float = decode(low[i])
    /** RMS of the treble (over about 3 kHz) of column [i]. */
    fun highAt(i: Int): Float = decode(high[i])

    /** The column that holds [positionMs], or -1 outside the stretch. */
    fun columnAt(positionMs: Long): Int {
        if (positionMs < startMs) return -1
        val i = ((positionMs - startMs) / COLUMN_MS).toInt()
        return if (i < columns) i else -1
    }

    /** True when this stretch covers [fromMs, untilMs) give or take [slackMs]. */
    fun covers(fromMs: Long, untilMs: Long, slackMs: Long = 1_500L): Boolean = startMs <= fromMs + slackMs && endMs >= untilMs - slackMs

    companion object {
        const val COLUMN_MS = 50L
        /** At most 10 minutes per stretch: what a file may hold, so that a damaged one cannot ask for much memory. */
        const val MAX_COLUMNS = 12_000

        fun encodeLevel(linear: Float): Byte = (sqrt(linear.coerceIn(0f, 1f)) * 255f + .5f).toInt().coerceIn(0, 255).toByte()
    }
}

/**
 * Builds a [Wave] from 16-bit interleaved PCM as it is decoded. The bands are split by two one-pole
 * filters, cheap enough to run on every sample while a title is read eight times faster than real time.
 */
internal class WaveBuilder(private val sampleRate: Int, private val channels: Int, private val startMs: Long) {
    private val framesPerColumn = (sampleRate * Wave.COLUMN_MS / 1000L).toInt().coerceAtLeast(1)
    private val lowCoefficient = 1f - exp(-2.0 * Math.PI * LOW_HZ / sampleRate).toFloat()
    private val highCoefficient = 1f - exp(-2.0 * Math.PI * HIGH_HZ / sampleRate).toFloat()
    private var lowState = 0f
    private var midState = 0f
    private var count = 0
    private var peak = 0f
    private var sum = 0.0
    private var lowSum = 0.0
    private var highSum = 0.0
    private val peaks = ByteArrayOutputStream()
    private val levels = ByteArrayOutputStream()
    private val lows = ByteArrayOutputStream()
    private val highs = ByteArrayOutputStream()
    var columns = 0
        private set
    /** Frames taken so far. */
    var frames = 0L
        private set

    /** Takes the frames of [buffer] (little-endian 16-bit, interleaved) without consuming it. */
    fun accept16(buffer: ByteBuffer) {
        val copy = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frameCount = copy.remaining() / (2 * channels)
        for (f in 0 until frameCount) {
            var sumChannels = 0
            for (c in 0 until channels) sumChannels += copy.short.toInt()
            frame(sumChannels / (channels * 32768f))
        }
    }

    /** Takes [frameCount] interleaved frames of [samples] from [offset]. */
    fun accept(samples: ShortArray, offset: Int = 0, frameCount: Int = (samples.size - offset) / channels) {
        var i = offset
        repeat(frameCount) {
            var sumChannels = 0
            for (c in 0 until channels) sumChannels += samples[i++].toInt()
            frame(sumChannels / (channels * 32768f))
        }
    }

    private fun frame(x: Float) {
        if (columns >= Wave.MAX_COLUMNS) return
        lowState += lowCoefficient * (x - lowState)
        midState += highCoefficient * (x - midState)
        val treble = x - midState
        val a = abs(x)
        if (a > peak) peak = a
        sum += x * x
        lowSum += lowState * lowState
        highSum += treble * treble
        frames++
        if (++count >= framesPerColumn) close()
    }

    private fun close() {
        if (count == 0) return
        peaks.write(Wave.encodeLevel(peak).toInt())
        levels.write(Wave.encodeLevel(sqrt(sum / count).toFloat()).toInt())
        lows.write(Wave.encodeLevel(sqrt(lowSum / count).toFloat()).toInt())
        highs.write(Wave.encodeLevel(sqrt(highSum / count).toFloat()).toInt())
        columns++
        count = 0; peak = 0f; sum = 0.0; lowSum = 0.0; highSum = 0.0
    }

    /** The stretch read so far; a last column needs at least half its frames. */
    fun build(): Wave {
        if (count >= framesPerColumn / 2) close()
        return Wave(startMs, peaks.toByteArray(), levels.toByteArray(), lows.toByteArray(), highs.toByteArray())
    }

    companion object {
        const val LOW_HZ = 200.0
        const val HIGH_HZ = 3_000.0
    }
}

/** Feeds the sound of a deck read silently into a [WaveBuilder], between two positions of a title. */
internal class WaveTap(private val fromUs: Long, private val untilUs: Long) : PcmTap {
    private var builder: WaveBuilder? = null
    private var expectedUs = Long.MIN_VALUE
    private var rate = 0
    @Volatile var done = false
        private set
    @Volatile var reachedUs = Long.MIN_VALUE
        private set

    override fun pcm(buffer: ByteBuffer, positionUs: Long, sampleRate: Int, channels: Int) {
        if (done || channels !in 1..8 || sampleRate <= 0) return
        val frames = buffer.remaining() / (2 * channels)
        val endUs = positionUs + frames * 1_000_000L / sampleRate
        if (endUs <= fromUs) return
        // A jump (the seek that starts the reading, or a rebuffer) starts the picture again.
        if (builder == null || sampleRate != rate || abs(positionUs - expectedUs) > 40_000L) {
            builder = WaveBuilder(sampleRate, channels, positionUs / 1000L); rate = sampleRate
        }
        builder?.accept16(buffer)
        expectedUs = endUs
        reachedUs = endUs
        if (endUs >= untilUs) done = true
    }

    /** What was read, or null when nothing continuous was. */
    fun result(): Wave? = builder?.build()?.takeIf { it.columns >= 20 }
}

/** Passes a reading on to [inner] only from [fromUs]: an analysis that must start where it always starts. */
internal class FromTap(private val fromUs: Long, private val inner: PcmTap) : PcmTap {
    override fun pcm(buffer: ByteBuffer, positionUs: Long, sampleRate: Int, channels: Int) {
        if (sampleRate <= 0 || channels <= 0) return
        val endUs = positionUs + (buffer.remaining() / (2 * channels)) * 1_000_000L / sampleRate
        if (endUs > fromUs) inner.pcm(buffer, positionUs, sampleRate, channels)
    }
}

/**
 * The waves of the titles, kept beside the analysis but apart from it (the analysis store holds 900
 * small entries; waves are a few kilobytes each): one small file per title in [dir], the start
 * ("head") and the end ("tail") of the title, the least recently used files removed past [limit].
 */
internal class WaveFiles(private val dir: File, private val limit: Int = LIMIT) {
    class Entry(val head: Wave?, val tail: Wave?)

    private fun file(trackId: String, durationMs: Long): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(trackId.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$digest-${durationMs / 1000}.kwv")
    }

    /** What is kept of a title, looking a second either side of [durationMs] as [AnalysisStore] does. */
    @Synchronized fun read(trackId: String, durationMs: Long): Entry? {
        for (length in longArrayOf(durationMs, durationMs - 1000, durationMs + 1000)) {
            val target = file(trackId, length)
            if (!target.isFile) continue
            val entry = runCatching { decode(target.readBytes()) }.getOrNull() ?: continue
            target.setLastModified(System.currentTimeMillis())
            return entry
        }
        return null
    }

    /** Keeps [wave] as the head of the title when it starts within its first seconds, as its tail otherwise. */
    @Synchronized fun put(trackId: String, durationMs: Long, wave: Wave) {
        if (wave.columns == 0) return
        val old = read(trackId, durationMs)
        val isHead = wave.startMs < HEAD_FROM_MS
        fun better(kept: Wave?) = kept == null || wave.columns >= kept.columns
        val entry = if (isHead) Entry(if (better(old?.head)) wave else old?.head, old?.tail)
            else Entry(old?.head, if (better(old?.tail)) wave else old?.tail)
        dir.mkdirs()
        val target = file(trackId, durationMs)
        runCatching {
            val temporary = File(dir, target.name + ".tmp")
            temporary.writeBytes(encode(entry))
            if (!temporary.renameTo(target)) { target.writeBytes(encode(entry)); temporary.delete() }
        }
        trim()
    }

    @Synchronized fun count(): Int = dir.listFiles { f -> f.name.endsWith(".kwv") }?.size ?: 0

    private fun trim() {
        val files = dir.listFiles { f -> f.name.endsWith(".kwv") } ?: return
        if (files.size <= limit) return
        files.sortedBy { it.lastModified() }.take(files.size - limit).forEach { it.delete() }
    }

    companion object {
        const val LIMIT = 400
        /** A stretch that starts this early is the start of its title. */
        const val HEAD_FROM_MS = 3_000L
        private const val MAGIC = 0x4B575631 // "KWV1"

        fun encode(entry: Entry): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { data ->
                data.writeInt(MAGIC)
                fun write(w: Wave?) {
                    if (w == null) { data.writeInt(0); return }
                    data.writeInt(w.columns); data.writeLong(w.startMs)
                    data.write(w.peak); data.write(w.rms); data.write(w.low); data.write(w.high)
                }
                write(entry.head); write(entry.tail)
            }
            return out.toByteArray()
        }

        fun decode(bytes: ByteArray): Entry? {
            val data = DataInputStream(bytes.inputStream())
            if (bytes.size < 12 || data.readInt() != MAGIC) return null
            fun read(): Wave? {
                val n = data.readInt()
                if (n == 0) return null
                require(n in 1..Wave.MAX_COLUMNS)
                val start = data.readLong()
                require(start in 0..36_000_000L)
                fun bytes() = ByteArray(n).also { data.readFully(it) }
                return Wave(start, bytes(), bytes(), bytes(), bytes())
            }
            return Entry(read(), read())
        }
    }
}

/** The app's [WaveFiles], in `filesDir/ketmix-waves`. */
internal object WaveStore {
    @Volatile private var files: WaveFiles? = null

    fun init(context: Context) {
        if (files != null) return
        synchronized(this) { if (files == null) files = WaveFiles(File(context.applicationContext.filesDir, "ketmix-waves")) }
    }

    /** For tests on the host. */
    internal fun use(target: WaveFiles?) { files = target }

    fun read(trackId: String, durationMs: Long): WaveFiles.Entry? = files?.read(trackId, durationMs)
    fun put(trackId: String, durationMs: Long, wave: Wave) { files?.put(trackId, durationMs, wave) }
}
