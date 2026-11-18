package com.ketvoice.app.automix

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

/** Feeds the sound of a deck played silently and fast into an [OnsetTracker], between two positions of a title. */
internal class AnalysisTap(private val fromUs: Long, private val untilUs: Long, private val withKey: Boolean = false,
                           private val thorough: Boolean = false, private val hint: BeatAnalysis.Hint? = null,
                           /** Set when the beat model reads this stretch too: true for an ending, false for an intro. */
                           private val beatTail: Boolean? = null) : PcmTap {
    private var tracker: OnsetTracker? = null
    private var beatPcm: BeatPcm? = null
    private var firstUs = 0L
    private var expectedUs = 0L
    private var rate = 0
    @Volatile var done = false
        private set
    /** Position reached so far, for the engine to see progress. */
    @Volatile var reachedUs = Long.MIN_VALUE
        private set

    override fun pcm(buffer: ByteBuffer, positionUs: Long, sampleRate: Int, channels: Int) {
        if (done) return
        var current = tracker
        // A jump (the seek that starts the reading, or a rebuffer) starts the reading again.
        if (current == null || sampleRate != rate || kotlin.math.abs(positionUs - expectedUs) > 40_000L) {
            current = OnsetTracker(sampleRate, channels, withKey)
            tracker = current; rate = sampleRate; firstUs = positionUs
            beatPcm = if (beatTail != null) BeatPcm(sampleRate, channels, positionUs) else null
        }
        current.accept16(buffer)
        beatPcm?.accept16(buffer)
        expectedUs = positionUs + (buffer.remaining() / (2 * channels)) * 1_000_000L / sampleRate
        reachedUs = expectedUs
        if (expectedUs >= untilUs) done = true
    }

    /** What was read, or null when too little of it is continuous. */
    fun result(): SegmentAnalysis? {
        val read = tracker ?: return null
        // A reading that does not start where it was asked to is not trusted with positions.
        if (read.frames / read.fps < 6f || kotlin.math.abs(firstUs - fromUs) > 6_000_000L) return null
        val signal = BeatAnalysis.analyze(read.snapshot(), firstUs / 1000L, thorough, hint)
        return beatPcm?.let { BeatModel.refine(signal, it, beatTail == true) } ?: signal
    }
}

/**
 * How much of the phone's processor the analysis of a title may take. The three settings
 * read more or less of each title and look for more or less in it.
 */
internal enum class AnalysisDepth(val outroMs: Long, val introMs: Long, val key: Boolean, val thorough: Boolean) {
    /** Tempo, beats and levels on short stretches: the least work, the least battery. */
    Light(30_000L, 24_000L, key = false, thorough = false),
    /** Longer stretches and the key of each title, so that keys that clash are not left to overlap. */
    Balanced(45_000L, 40_000L, key = true, thorough = false),
    /** The longest stretches, the key, and a check that the tempo holds: long mixes, on titles that allow them. */
    Maximum(75_000L, 60_000L, key = true, thorough = true);

    fun satisfiedBy(analysis: SegmentAnalysis, windowMs: Long, durationMs: Long): Boolean {
        val wanted = if (durationMs > 0) minOf(windowMs, durationMs) else windowMs
        // A reading made before the beat model was installed is made again, once, with it.
        val beat = analysis.analyser != 0 || !BeatModel.usable()
        return analysis.lengthMs >= wanted - 6_000L && (!key || analysis.key != MusicalKey.UNREAD) && beat
    }

    companion object {
        fun of(setting: Int): AnalysisDepth = when (setting) { 0 -> Light; 2 -> Maximum; else -> Balanced }
    }
}

/**
 * Remembers what was measured of each title's start and end, so that a title is only
 * read once. Kept on the device, a few hundred titles at most.
 */
internal object AnalysisStore {
    private const val LIMIT = 900
    private val entries = object : LinkedHashMap<String, SegmentAnalysis>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SegmentAnalysis>?) = size > LIMIT
    }
    private var file: File? = null
    private var dirty = false

    @Synchronized fun init(context: Context) {
        if (file != null) return
        val target = File(context.filesDir, "automix-analysis.json")
        file = target
        runCatching {
            if (target.isFile && target.length() < 4_000_000L) {
                val root = JSONObject(target.readText())
                for (key in root.keys()) decode(root.optJSONObject(key))?.let { entries[key] = it }
            }
        }
    }

    private fun key(trackId: String, durationMs: Long, intro: Boolean) = "${if (intro) "i" else "o"}|${durationMs / 1000}|$trackId"

    /**
     * What was kept of a title's start or end, when it is as much as [depth] asks for: a
     * stretch read by a lighter setting is read again rather than trusted for a finer mix.
     */
    @Synchronized fun intro(trackId: String, durationMs: Long, depth: AnalysisDepth = AnalysisDepth.Light): SegmentAnalysis? =
        near(trackId, durationMs, true)?.takeIf { depth.satisfiedBy(it, depth.introMs, durationMs) }
    @Synchronized fun outro(trackId: String, durationMs: Long, depth: AnalysisDepth = AnalysisDepth.Light): SegmentAnalysis? =
        near(trackId, durationMs, false)?.takeIf { depth.satisfiedBy(it, depth.outroMs, durationMs) }

    /**
     * A length read from a file's header and the one the player reports may fall on either side of a
     * second (5.11.0: titles are also measured from their downloaded file): the neighbours are looked at too.
     */
    private fun near(trackId: String, durationMs: Long, intro: Boolean): SegmentAnalysis? =
        entries[key(trackId, durationMs, intro)] ?: entries[key(trackId, durationMs - 1000, intro)] ?: entries[key(trackId, durationMs + 1000, intro)]

    /** What was measured of the body of a title (see [BeatAnalysis.Hint]). */
    @Synchronized fun body(trackId: String, durationMs: Long): SegmentAnalysis? = entries["b|${durationMs / 1000}|$trackId"]
    @Synchronized fun putBody(trackId: String, durationMs: Long, analysis: SegmentAnalysis) {
        entries["b|${durationMs / 1000}|$trackId"] = analysis
        dirty = true
    }

    /** The stretch of the body that is read: 30 s from 45 % of the title, when the title is long enough to have one. */
    fun bodyWindow(durationMs: Long): LongRange? {
        if (durationMs < 150_000L) return null
        val from = (durationMs * 45 / 100).coerceIn(60_000L, durationMs - 90_000L)
        return from until from + BODY_MS
    }
    const val BODY_MS = 30_000L

    @Synchronized fun put(trackId: String, durationMs: Long, intro: Boolean, analysis: SegmentAnalysis) {
        entries[key(trackId, durationMs, intro)] = analysis
        dirty = true
    }

    /**
     * Written at most once per transition, off the main thread. Since 6.1 two processes keep measurements (the
     * playing one, and the work process that prepares downloads): what the other wrote since is read and kept
     * first, under a file lock, so that neither erases the other's work.
     */
    fun save() {
        val target = synchronized(this) { if (!dirty) return; file } ?: return
        runCatching {
            locked(target) {
                val text = synchronized(this) {
                    merge(readDisk(target))
                    dirty = false
                    val root = JSONObject()
                    for ((key, value) in entries) root.put(key, encode(value))
                    root.toString()
                }
                val temporary = File(target.parentFile, target.name + ".tmp")
                temporary.writeText(text)
                if (!temporary.renameTo(target)) { target.writeText(text); temporary.delete() }
            }
        }
    }

    /** Takes up what the other process measured and wrote since (the playing process, after the library's work). */
    fun refresh() {
        val target = synchronized(this) { file } ?: return
        runCatching { val disk = locked(target) { readDisk(target) }; synchronized(this) { merge(disk) } }
    }

    private fun readDisk(target: File): Map<String, SegmentAnalysis> {
        if (!target.isFile || target.length() >= 4_000_000L) return emptyMap()
        val root = JSONObject(target.readText())
        val out = LinkedHashMap<String, SegmentAnalysis>()
        for (key in root.keys()) decode(root.optJSONObject(key))?.let { out[key] = it }
        return out
    }

    /** Adds the entries of [disk] this process does not have, as the oldest ones; called under the object's lock. */
    internal fun merge(disk: Map<String, SegmentAnalysis>) {
        val missing = disk.filterKeys { it !in entries }
        if (missing.isEmpty()) return
        val mine = LinkedHashMap(entries)
        entries.clear()
        for ((k, v) in missing) entries[k] = v
        for ((k, v) in mine) entries[k] = v
        while (entries.size > LIMIT) entries.remove(entries.keys.first())
    }

    /** One writer at a time: the threads of this process (a monitor), then the other process (a file lock). */
    private val diskMonitor = Any()
    private fun <T> locked(target: File, block: () -> T): T = synchronized(diskMonitor) {
        java.io.RandomAccessFile(File(target.parentFile, target.name + ".lock"), "rw").use { lock ->
            lock.channel.lock().use { block() }
        }
    }

    internal fun encode(a: SegmentAnalysis): JSONObject = JSONObject()
        .put("s", a.startMs).put("l", a.lengthMs).put("b", a.bpm.toDouble()).put("p", a.beatAtMs).put("c", a.confidence.toDouble())
        .put("r", a.barOffset).put("d", a.loudnessDb.toDouble()).put("ss", a.soundStartMs).put("se", a.soundEndMs)
        .put("ri", a.riseMs).put("fa", a.fallMs).put("fo", a.fadeOut).put("he", a.hardEnding)
        .put("k", a.key).put("ks", a.keyStrength.toDouble()).put("ph", a.phraseAtMs).put("h", a.tempoHint.toDouble()).put("an", a.analyser)

    internal fun decode(o: JSONObject?): SegmentAnalysis? {
        o ?: return null
        val start = o.optLong("s", -1); val length = o.optLong("l", -1)
        if (start < 0 || length <= 0 || length > 600_000) return null
        val bpm = o.optDouble("b", 0.0).toFloat()
        if (bpm.isNaN() || bpm < 0f || bpm > 400f) return null
        val end = start + length
        return SegmentAnalysis(start, length, bpm, o.optDouble("p", 0.0), o.optDouble("c", 0.0).toFloat().coerceIn(0f, 1f),
            o.optInt("r", -1).coerceIn(-1, 3), o.optDouble("d", -90.0).toFloat(),
            o.optLong("ss", start).coerceIn(start, end), o.optLong("se", end).coerceIn(start, end),
            o.optLong("ri", start).coerceIn(start, end), o.optLong("fa", end).coerceIn(start, end),
            o.optBoolean("fo"), o.optBoolean("he"),
            o.optInt("k", MusicalKey.UNREAD).coerceIn(MusicalKey.UNREAD, 23), o.optDouble("ks", 0.0).toFloat().coerceIn(0f, 1f),
            o.optDouble("ph", -1.0).takeIf { it.isFinite() && it >= 0.0 && it <= end } ?: -1.0,
            o.optDouble("h", 0.0).toFloat().takeIf { it.isFinite() && it in 0f..400f } ?: 0f,
            o.optInt("an", 0).coerceIn(0, SegmentAnalysis.BEAT_TRIED))
    }
}

/**
 * Records the sound of a deck played silently between two positions of a title (16-bit
 * interleaved), for the separation of its voice (see [com.ketvoice.app.stems.StemEngine]).
 */
internal class PcmCapture(private val fromUs: Long, private val untilUs: Long) : PcmTap {
    private var data = ShortArray(0)
    private var size = 0
    private var expectedUs = Long.MIN_VALUE
    @Volatile var rate = 0
        private set
    @Volatile var channels = 0
        private set
    /** Position in the title of the first recorded frame. */
    @Volatile var startUs = 0L
        private set
    @Volatile var done = false
        private set

    override fun pcm(buffer: ByteBuffer, positionUs: Long, sampleRate: Int, channels: Int) {
        if (done || channels != 2) return
        val frames = buffer.remaining() / 4
        // A jump (the seek that starts the reading, or a rebuffer) starts the recording again.
        if (size == 0 || sampleRate != rate || kotlin.math.abs(positionUs - expectedUs) > 40_000L) {
            if (positionUs + frames * 1_000_000L / sampleRate < fromUs) return
            rate = sampleRate; this.channels = channels; startUs = positionUs; size = 0
            data = ShortArray(((untilUs - positionUs).coerceAtLeast(1_000_000L) * sampleRate / 1_000_000L).toInt() * 2 + 8192)
        }
        val copy = buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val room = (data.size - size) / 2
        val n = minOf(frames, room)
        for (i in 0 until n * 2) data[size + i] = copy.short
        size += n * 2
        expectedUs = positionUs + frames * 1_000_000L / sampleRate
        if (expectedUs >= untilUs || n < frames) done = true
    }

    /** What was recorded, or null when too little of it is continuous. */
    fun pcm(minSeconds: Double = 5.0): ShortArray? = if (rate > 0 && size >= (rate * 2 * minSeconds).toInt()) data.copyOf(size) else null
}

/** Feeds one reading to two taps. */
internal class BothTaps(private val a: PcmTap, private val b: PcmTap) : PcmTap {
    override fun pcm(buffer: ByteBuffer, positionUs: Long, sampleRate: Int, channels: Int) {
        a.pcm(buffer.duplicate(), positionUs, sampleRate, channels)
        b.pcm(buffer.duplicate(), positionUs, sampleRate, channels)
    }
}
