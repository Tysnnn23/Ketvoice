package com.ketvoice.app.automix

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

/** Feeds the sound of a deck played silently and fast into an [OnsetTracker], between two positions of a title. */
internal class AnalysisTap(private val fromUs: Long, private val untilUs: Long) : PcmTap {
    private var tracker: OnsetTracker? = null
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
            current = OnsetTracker(sampleRate, channels)
            tracker = current; rate = sampleRate; firstUs = positionUs
        }
        current.accept16(buffer)
        expectedUs = positionUs + (buffer.remaining() / (2 * channels)) * 1_000_000L / sampleRate
        reachedUs = expectedUs
        if (expectedUs >= untilUs) done = true
    }

    /** What was read, or null when too little of it is continuous. */
    fun result(): SegmentAnalysis? {
        val read = tracker ?: return null
        // A reading that does not start where it was asked to is not trusted with positions.
        if (read.frames / read.fps < 6f || kotlin.math.abs(firstUs - fromUs) > 6_000_000L) return null
        return BeatAnalysis.analyze(read.snapshot(), firstUs / 1000L)
    }
}

/**
 * Remembers what was measured of each title's start and end, so that a title is only
 * read once. Kept on the device, a few hundred titles at most.
 */
internal object AnalysisStore {
    private const val LIMIT = 600
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

    @Synchronized fun intro(trackId: String, durationMs: Long): SegmentAnalysis? = entries[key(trackId, durationMs, true)]
    @Synchronized fun outro(trackId: String, durationMs: Long): SegmentAnalysis? = entries[key(trackId, durationMs, false)]

    @Synchronized fun put(trackId: String, durationMs: Long, intro: Boolean, analysis: SegmentAnalysis) {
        entries[key(trackId, durationMs, intro)] = analysis
        dirty = true
    }

    /** Written at most once per transition, off the main thread. */
    fun save() {
        val (target, text) = synchronized(this) {
            if (!dirty) return
            dirty = false
            val root = JSONObject()
            for ((key, value) in entries) root.put(key, encode(value))
            (file ?: return) to root.toString()
        }
        runCatching {
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.writeText(text)
            if (!temporary.renameTo(target)) { target.writeText(text); temporary.delete() }
        }
    }

    internal fun encode(a: SegmentAnalysis): JSONObject = JSONObject()
        .put("s", a.startMs).put("l", a.lengthMs).put("b", a.bpm.toDouble()).put("p", a.beatAtMs).put("c", a.confidence.toDouble())
        .put("r", a.barOffset).put("d", a.loudnessDb.toDouble()).put("ss", a.soundStartMs).put("se", a.soundEndMs)
        .put("ri", a.riseMs).put("fa", a.fallMs).put("fo", a.fadeOut).put("he", a.hardEnding)

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
            o.optBoolean("fo"), o.optBoolean("he"))
    }
}
