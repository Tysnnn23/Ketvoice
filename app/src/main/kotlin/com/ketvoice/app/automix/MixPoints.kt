package com.ketvoice.app.automix

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Where the listener chose to leave and to enter a title in the Ketmix DJ editor (6.1, owner: « tu
 * choisis toi-même le point d'entrée et de sortie, mémorisés pour la prochaine fois »). Positions are
 * in the title, in ms, for the rendition of [durationMs]; -1 = left to Ketmix.
 */
internal data class TitlePoints(val exitMs: Long = -1, val entryMs: Long = -1, val durationMs: Long = 0) {
    val empty: Boolean get() = exitMs < 0 && entryMs < 0
    /** The points only apply to a rendition of about the same length (another source may be a second longer). */
    fun fits(durationMs: Long): Boolean = durationMs <= 0 || this.durationMs <= 0 || abs(durationMs - this.durationMs) <= MAX_DRIFT_MS
    companion object { const val MAX_DRIFT_MS = 3_000L }
}

/** What the listener chose for one pair of titles: the style of its transition and how long both are heard (-1 = Ketmix's). */
internal data class PairChoice(val style: MixStyle? = null, val overlapMs: Long = -1, val shape: MixShape = MixShape.DEFAULT) {
    val empty: Boolean get() = style == null && overlapMs <= 0 && shape.isDefault
}

/**
 * The beat grid of a title as the DJ editor and the planner snap to it. Both use this one function, so a
 * point the editor shows on a beat is the point Ketmix plays.
 */
internal object GridSnap {
    enum class Kind { None, Beat, Bar, Phrase }
    data class Result(val ms: Long, val kind: Kind)

    /**
     * How far outside its measured stretch a grid is still followed. A produced title keeps its tempo, and
     * the editor shows more of a title than Ketmix measures (90 s of an ending, 45 s are read by default).
     */
    const val REACH_MS = 50_000L

    /** True when [analysis] has a grid that may be followed at [positionMs]. */
    fun covers(analysis: SegmentAnalysis?, positionMs: Long): Boolean {
        if (analysis == null || !analysis.hasBeat) return false
        return positionMs >= analysis.startMs - REACH_MS && positionMs <= analysis.startMs + analysis.lengthMs + REACH_MS
    }

    /** The beat nearest to [positionMs]. */
    fun nearestBeat(analysis: SegmentAnalysis, positionMs: Double): Double {
        val period = analysis.beatMs.toDouble()
        return analysis.beatAtMs + Math.round((positionMs - analysis.beatAtMs) / period) * period
    }

    /**
     * Where a point dropped at [positionMs] lands: on the start of a phrase within twice [toleranceMs], else on
     * the first beat of a bar within [toleranceMs], else on the nearest beat; where it is when there is no grid.
     */
    fun snap(analysis: SegmentAnalysis?, positionMs: Long, toleranceMs: Long): Result {
        if (analysis == null || !covers(analysis, positionMs)) return Result(positionMs, Kind.None)
        val at = positionMs.toDouble()
        if (analysis.hasPhrases) {
            val period = analysis.phraseMs
            val phrase = analysis.phraseAtMs + Math.round((at - analysis.phraseAtMs) / period) * period
            if (abs(phrase - at) <= 2.0 * toleranceMs) return Result(phrase.roundToLong(), Kind.Phrase)
        }
        if (analysis.barOffset >= 0) {
            val bar = analysis.beatMs * 4.0
            val first = analysis.beatAtMs + analysis.barOffset * analysis.beatMs.toDouble()
            val nearest = first + Math.round((at - first) / bar) * bar
            if (abs(nearest - at) <= toleranceMs) return Result(nearest.roundToLong(), Kind.Bar)
        }
        return Result(nearestBeat(analysis, at).roundToLong(), Kind.Beat)
    }

    /** What kind of grid line [positionMs] is on, for the editor's haptics and labels. */
    fun kindAt(analysis: SegmentAnalysis?, positionMs: Long): Kind {
        if (analysis == null || !covers(analysis, positionMs)) return Kind.None
        val beat = analysis.beatMs.toDouble()
        val at = positionMs.toDouble()
        fun on(first: Double, period: Double): Boolean { val k = (at - first) / period; return abs(k - Math.round(k)) * period < 2.0 }
        if (analysis.hasPhrases && on(analysis.phraseAtMs, analysis.phraseMs)) return Kind.Phrase
        if (analysis.barOffset >= 0 && on(analysis.beatAtMs + analysis.barOffset * beat, beat * 4)) return Kind.Bar
        return if (on(analysis.beatAtMs, beat)) Kind.Beat else Kind.None
    }

    /** The beats between [fromMs] and [untilMs] for drawing, at most [limit]; empty without a grid. */
    fun beats(analysis: SegmentAnalysis?, fromMs: Long, untilMs: Long, limit: Int = 600): List<Pair<Long, Kind>> {
        if (analysis == null || !analysis.hasBeat || untilMs <= fromMs) return emptyList()
        val period = analysis.beatMs.toDouble()
        val low = maxOf(fromMs, analysis.startMs - REACH_MS).toDouble()
        val high = minOf(untilMs, analysis.startMs + analysis.lengthMs + REACH_MS).toDouble()
        if (high <= low) return emptyList()
        var at = analysis.beatAtMs + kotlin.math.ceil((low - analysis.beatAtMs) / period) * period
        val out = ArrayList<Pair<Long, Kind>>()
        while (at <= high && out.size < limit) {
            val ms = at.roundToLong()
            out += ms to kindAt(analysis, ms).let { if (it == Kind.None) Kind.Beat else it }
            at += period
        }
        return out
    }

    /** Whole bars of [analysis] closest to [lengthMs], at least one: an overlap played in time. */
    fun bars(analysis: SegmentAnalysis, lengthMs: Long): Long {
        val bar = analysis.beatMs * 4.0
        return (maxOf(1.0, Math.round(lengthMs / bar).toDouble()) * bar).roundToLong()
    }

    /** Whole bars of [analysis] no longer than [lengthMs], at least one. */
    fun barsWithin(analysis: SegmentAnalysis, lengthMs: Long): Long {
        val bar = analysis.beatMs * 4.0
        return (maxOf(1.0, floor(lengthMs / bar)) * bar).roundToLong()
    }
}

/** Where [MixPointStore] keeps its values: the app's preferences, or a map in tests. */
internal interface PointsBackend {
    fun all(): Map<String, String>
    fun put(key: String, value: String?)
}

internal class MapPointsBackend(private val map: MutableMap<String, String> = LinkedHashMap()) : PointsBackend {
    override fun all(): Map<String, String> = map.toMap()
    override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

/**
 * The listener's mix points, remembered for the next time (6.1). A title's exit applies whenever it is the
 * outgoing title, its entry whenever it is the incoming one; the style and the overlap belong to a pair.
 * Keys are digests of the titles' ids (as [com.ketvoice.app.data.LyricsOffsets] does), so the personal
 * backup carries them without carrying ids in clear.
 */
internal object MixPointStore {
    const val PREFS = "ketmix-points-v1"
    /** At most this many titles and pairs are kept; the oldest go first. */
    const val LIMIT = 2_000
    private const val MAX_POSITION_MS = 3_600_000L
    private val keyPattern = Regex("[tp]:[0-9a-f]{64}")
    private val titlePattern = Regex("-?\\d{1,7},-?\\d{1,7},\\d{1,7}")
    private val pairPattern = Regex("-?\\d,-?\\d{1,5}(,\\d{1,3})?")

    @Volatile private var backend: PointsBackend? = null
    private val _revision = MutableStateFlow(0)
    /** Changes whenever a point is set or removed, for the screens that draw them. */
    val revision: StateFlow<Int> = _revision

    fun init(context: Context) {
        if (backend != null) return
        synchronized(this) {
            if (backend != null) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            backend = object : PointsBackend {
                override fun all(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
                override fun put(key: String, value: String?) { prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
            }
        }
    }

    /** For tests on the host. */
    internal fun use(target: PointsBackend?) { backend = target; _revision.value++ }

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    internal fun titleKey(trackId: String): String = "t:" + digest(trackId)
    internal fun pairKey(fromId: String, toId: String): String = "p:" + digest("$fromId>$toId")

    internal fun encodeTitle(p: TitlePoints): String = "${p.exitMs.coerceIn(-1, MAX_POSITION_MS)},${p.entryMs.coerceIn(-1, MAX_POSITION_MS)},${p.durationMs.coerceIn(0, MAX_POSITION_MS)}"
    internal fun decodeTitle(text: String?): TitlePoints? {
        if (text == null || !titlePattern.matches(text)) return null
        val (exit, entry, duration) = text.split(',').map { it.toLong() }
        if (exit < -1 || entry < -1 || exit > MAX_POSITION_MS || entry > MAX_POSITION_MS || duration > MAX_POSITION_MS) return null
        return TitlePoints(exit, entry, duration).takeIf { !it.empty }
    }
    internal fun encodePair(c: PairChoice): String = "${c.style?.ordinal ?: -1},${c.overlapMs.coerceIn(-1, 60_000)}" +
        if (c.shape.isDefault) "" else ",${c.shape.code()}"
    internal fun decodePair(text: String?): PairChoice? {
        if (text == null || !pairPattern.matches(text)) return null
        val parts = text.split(',').map { it.toLong() }
        val (style, overlap) = parts
        if (overlap < -1 || overlap > 60_000) return null
        // Choices saved before 6.9 have no shape: Ketmix's own.
        val shape = parts.getOrNull(2)?.let { MixShape.of(it.toInt()) ?: return null } ?: MixShape.DEFAULT
        return PairChoice(if (style < 0) null else MixStyle.values().getOrNull(style.toInt()) ?: return null, overlap, shape).takeIf { !it.empty }
    }

    /** True for a key and value a backup may bring back. */
    fun validEntry(key: String, value: String): Boolean = keyPattern.matches(key) &&
        (if (key.startsWith("t:")) decodeTitle(value) != null else decodePair(value) != null)

    fun title(trackId: String): TitlePoints? = backend?.all()?.get(titleKey(trackId))?.let(::decodeTitle)
    fun pair(fromId: String, toId: String): PairChoice? = backend?.all()?.get(pairKey(fromId, toId))?.let(::decodePair)

    /** The exit the listener chose for [trackId] as an outgoing title of [durationMs], or -1. */
    fun exitOf(trackId: String, durationMs: Long): Long = title(trackId)?.takeIf { it.fits(durationMs) }?.exitMs ?: -1
    /** The entry the listener chose for [trackId] as an incoming title of [durationMs], or -1. */
    fun entryOf(trackId: String, durationMs: Long): Long = title(trackId)?.takeIf { it.fits(durationMs) }?.entryMs ?: -1

    @Synchronized fun setExit(trackId: String, durationMs: Long, exitMs: Long) = updateTitle(trackId) { it.copy(exitMs = exitMs, durationMs = durationMs) }
    @Synchronized fun setEntry(trackId: String, durationMs: Long, entryMs: Long) = updateTitle(trackId) {
        // The entry is kept for the rendition it was chosen on; an exit kept for another length stays with its own.
        it.copy(entryMs = entryMs, durationMs = if (it.exitMs >= 0 && it.durationMs > 0) it.durationMs else durationMs)
    }

    private fun updateTitle(trackId: String, change: (TitlePoints) -> TitlePoints) {
        val target = backend ?: return
        if (trackId.isBlank()) return
        val next = change(title(trackId) ?: TitlePoints())
        target.put(titleKey(trackId), if (next.empty) null else encodeTitle(next))
        trim(target)
        _revision.value++
    }

    @Synchronized fun setPair(fromId: String, toId: String, choice: PairChoice) {
        val target = backend ?: return
        target.put(pairKey(fromId, toId), if (choice.empty) null else encodePair(choice))
        trim(target)
        _revision.value++
    }

    /** « Réinitialiser »: the transition from [fromId] to [toId] goes back to Ketmix's own choice. */
    @Synchronized fun reset(fromId: String, toId: String?) {
        updateTitle(fromId) { it.copy(exitMs = -1) }
        if (toId != null) {
            updateTitle(toId) { it.copy(entryMs = -1) }
            setPair(fromId, toId, PairChoice())
        }
    }

    /** Everything kept, for the personal backup. */
    fun export(): Map<String, String> = backend?.all()?.filter { (k, v) -> validEntry(k, v) }.orEmpty()

    /** Brings back a backup's points; points chosen on this device win. */
    @Synchronized fun mergeBackup(entries: Map<String, String>) {
        val target = backend ?: error("Mix points unavailable")
        val present = target.all()
        entries.forEach { (key, value) -> if (validEntry(key, value) && key !in present) target.put(key, value) }
        trim(target)
        _revision.value++
    }

    private fun trim(target: PointsBackend) {
        val all = target.all()
        if (all.size <= LIMIT) return
        // Preferences keep no order: the surplus goes, titles before pairs are spared.
        all.keys.sortedBy { if (it.startsWith("p:")) 0 else 1 }.take(all.size - LIMIT).forEach { target.put(it, null) }
    }
}
