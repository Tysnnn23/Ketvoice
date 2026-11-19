package com.ketvoice.app.automix

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * « Mode DJ set » (7.0.0, owner's choice): Ketmix plays a whole playlist, or the queue, like a set. The titles are put
 * in an order a DJ would play them: a smooth tempo path, harmonic moves (same key or a Camelot neighbour, see
 * [MusicalKey]) and an energy that rises, peaks and comes down ([target]). The title playing and the titles the listener
 * asked for with « Lire ensuite » keep their places. Every transition is then Ketmix's own (the same planner, lyric
 * rules, loop blend and variety as always); in a set an album's titles are mixed too. [SetExport] renders it to a file.
 */
internal object DjSet {
    /** What the ordering knows of one title. [energy] is NaN when the title was never measured. */
    data class Title(val bpm: Float = 0f, val key: Int = MusicalKey.UNREAD, val energy: Float = Float.NaN, val pinned: Boolean = false)

    /** Where the set peaks, as a share of its length, and its level at the start and at the end (in energy ranks). */
    const val PEAK_AT = .7f
    const val START = .3f
    const val END = .55f
    /** A jump of this much tempo (after folding double and half time) costs as much as the worst key clash. */
    private const val TEMPO_SPAN = .08f
    private const val KEY_WEIGHT = .6f
    private const val ENERGY_WEIGHT = 1.2f
    /** What an unmeasured tempo, key or energy costs: neither a good nor a bad neighbour. */
    private const val UNKNOWN_TEMPO = .35f
    private const val UNKNOWN_ENERGY = .25f
    private const val PASSES = 4

    private fun ease(x: Float) = (.5 - .5 * cos(Math.PI * x.coerceIn(0f, 1f))).toFloat()

    /** The energy the set aims for at [t] (0 = its first title, 1 = its last), in ranks 0..1. */
    fun target(t: Float): Float =
        if (t <= PEAK_AT) START + (1f - START) * ease(t / PEAK_AT)
        else 1f - (1f - END) * ease((t - PEAK_AT) / (1f - PEAK_AT))

    /** Each energy as its rank among the measured ones, 0 (calmest) .. 1 (most energetic); NaN stays NaN. */
    fun ranks(energies: List<Float>): FloatArray {
        val known = energies.withIndex().filter { !it.value.isNaN() }.sortedBy { it.value }
        val out = FloatArray(energies.size) { Float.NaN }
        known.forEachIndexed { rank, item -> out[item.index] = if (known.size == 1) .5f else rank.toFloat() / (known.size - 1) }
        return out
    }

    fun tempoCost(a: Title, b: Title): Float =
        if (a.bpm <= 0f || b.bpm <= 0f) UNKNOWN_TEMPO else min(1f, abs(BeatAnalysis.foldedRatio(a.bpm, b.bpm) - 1f) / TEMPO_SPAN)

    fun keyCost(a: Title, b: Title): Float = when (MusicalKey.relation(a.key, b.key)) {
        MusicalKey.Relation.Same -> 0f
        MusicalKey.Relation.Close -> .3f
        MusicalKey.Relation.Unknown -> .4f
        MusicalKey.Relation.Clash -> 1f
    }

    fun pairCost(a: Title, b: Title): Float = tempoCost(a, b) + KEY_WEIGHT * keyCost(a, b)

    private fun energyCost(rank: Float, slot: Int, n: Int): Float =
        if (rank.isNaN()) UNKNOWN_ENERGY else abs(rank - target(if (n <= 1) 0f else slot.toFloat() / (n - 1)))

    /** What the title in [slot] costs there: its energy against the curve, and its join with the title before it. */
    private fun slotCost(order: IntArray, titles: List<Title>, ranks: FloatArray, slot: Int): Float {
        if (slot !in order.indices) return 0f
        val here = order[slot]
        val join = if (slot > 0) pairCost(titles[order[slot - 1]], titles[here]) else 0f
        return ENERGY_WEIGHT * energyCost(ranks[here], slot, order.size) + join
    }

    fun cost(order: IntArray, titles: List<Title>): Float {
        val ranks = ranks(titles.map { it.energy })
        return order.indices.sumOf { slotCost(order, titles, ranks, it).toDouble() }.toFloat()
    }

    /**
     * The set's order of [titles], as indices into it. A pinned title keeps its own place; the others are placed slot
     * after slot (the best next title for the curve and the join), then pairs are swapped while that lowers the cost.
     */
    fun order(titles: List<Title>): IntArray {
        val n = titles.size
        if (n < 3) return IntArray(n) { it }
        val ranks = ranks(titles.map { it.energy })
        val order = IntArray(n) { -1 }
        titles.forEachIndexed { i, t -> if (t.pinned) order[i] = i }
        val left = titles.indices.filterNot { titles[it].pinned }.toMutableList()
        for (slot in 0 until n) {
            if (order[slot] >= 0) continue
            val best = left.minBy { candidate -> placeCost(order, titles, ranks, slot, candidate) }
            order[slot] = best
            left.remove(best)
        }
        improve(order, titles, ranks)
        return order
    }

    private fun placeCost(order: IntArray, titles: List<Title>, ranks: FloatArray, slot: Int, candidate: Int): Float {
        val before = if (slot > 0 && order[slot - 1] >= 0) pairCost(titles[order[slot - 1]], titles[candidate]) else 0f
        val after = if (slot + 1 < order.size && order[slot + 1] >= 0) pairCost(titles[candidate], titles[order[slot + 1]]) else 0f
        return ENERGY_WEIGHT * energyCost(ranks[candidate], slot, order.size) + before + after
    }

    private fun improve(order: IntArray, titles: List<Title>, ranks: FloatArray) {
        val free = order.indices.filterNot { titles[order[it]].pinned }
        repeat(PASSES) {
            var better = false
            for (x in free.indices) for (y in x + 1 until free.size) {
                val i = free[x]; val j = free[y]
                val touched = sortedSetOf(i, i + 1, j, j + 1)
                val was = touched.sumOf { slotCost(order, titles, ranks, it).toDouble() }
                swap(order, i, j)
                val now = touched.sumOf { slotCost(order, titles, ranks, it).toDouble() }
                if (now < was - 1e-6) better = true else swap(order, i, j)
            }
            if (!better) return
        }
    }

    private fun swap(order: IntArray, i: Int, j: Int) { val t = order[i]; order[i] = order[j]; order[j] = t }

    /** A title's energy, 0..1, from what Ketmix measured of it: its level, its tempo and how clear its beat is. */
    fun energyOf(vararg parts: SegmentAnalysis?): Float {
        val known = parts.filterNotNull()
        if (known.isEmpty()) return Float.NaN
        val loud = ((known.maxOf { it.loudnessDb } + 24f) / 18f).coerceIn(0f, 1f)
        val beat = known.firstOrNull { it.hasBeat }
        var bpm = beat?.bpm ?: 0f
        while (bpm in 1f..79.9f) bpm *= 2f
        while (bpm > 160f) bpm /= 2f
        val tempo = if (bpm <= 0f) .4f else ((bpm - 80f) / 80f).coerceIn(0f, 1f)
        return .55f * loud + .3f * tempo + .15f * (known.maxOf { it.confidence }).coerceIn(0f, 1f)
    }

    private fun lengthOf(track: Track) = track.durationMs.takeIf { it > 0 } ?: 600_000L

    /** What Ketmix knows of [track] for the ordering. */
    fun titleOf(track: Track, pinned: Boolean = false): Title {
        val length = lengthOf(track)
        val intro = AnalysisStore.intro(track.id, length)
        val outro = AnalysisStore.outro(track.id, length)
        val body = AnalysisStore.body(track.id, length)
        val tempo = listOfNotNull(body, intro, outro).firstOrNull { it.hasBeat }
        val key = listOfNotNull(body, intro, outro).map { it.key }.firstOrNull { it in 0..23 } ?: MusicalKey.UNREAD
        return Title(tempo?.bpm ?: 0f, key, energyOf(body, intro, outro), pinned)
    }

    // ------------------------------------------------------------------ the set being played

    /** The set playing: its titles (ids) and what was measured of them, and how far the measuring of its downloads is. */
    data class Live(val ids: Set<String>, val energy: Map<String, Float>, val measured: Int = 0, val measuring: Int = 0)

    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> = _live
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var measureJob: Job? = null
    @Volatile private var appContext: Context? = null

    fun init(context: Context) { appContext = context.applicationContext }

    /** True while the queue plays a set: the title heard belongs to it, and so do most of the queue's titles. */
    fun on(state: PlayerUiState, set: Live? = _live.value): Boolean {
        val ids = set?.ids ?: return false
        val current = state.queue.getOrNull(state.index) ?: return false
        return current.id in ids && state.queue.count { it.id in ids } * 2 >= ids.size
    }

    /** « Lancer en DJ set » from a collection: its titles in the set's order, Ketmix on, from the first title. */
    fun play(tracks: List<Track>) {
        val titles = tracks.distinctBy { it.id }
        if (titles.isEmpty()) return
        val order = order(titles.map { titleOf(it) })
        start(titles)
        Player.setShuffle(false)
        Player.playQueue(order.map { titles[it] })
    }

    /** « Lancer en DJ set » from the queue, and « Réordonner » later: the coming titles in the set's order. */
    fun fromQueue() {
        val s = Player.state.value
        if (s.queue.getOrNull(s.index) == null) return
        start(s.queue.distinctBy { it.id })
        Player.setShuffle(false)
        arrange()
    }

    /** Orders the titles after the one playing; those asked for with « Lire ensuite » stay where they are. */
    fun arrange() {
        val s = Player.state.value
        val from = s.index + 1
        if (s.index < 0 || s.queue.size - from < 2 || s.queueEntryIds.size != s.queue.size) return
        val entries = s.queueEntryIds.drop(from)
        val coming = s.queue.drop(from)
        // The title playing leads the order: the first coming title is chosen to follow it.
        val titles = listOf(titleOf(s.queue[s.index], pinned = true)) + coming.mapIndexed { i, t -> titleOf(t, Player.pinnedNext(entries[i])) }
        val order = order(titles).drop(1).map { it - 1 }
        val wanted = order.map { entries[it] }
        wanted.forEachIndexed { slot, entry ->
            val now = Player.state.value.queueEntryIds.indexOf(entry)
            if (now >= 0 && now != from + slot) Player.moveQueue(now, from + slot)
        }
    }

    fun stop() { measureJob?.cancel(); _live.value = null }

    /** Host renders of the queue's header. */
    internal fun showForTest(set: Live?) { _live.value = set }

    private fun start(tracks: List<Track>) {
        if (!AppSettings.automix.value) { AppSettings.automix.value = true; SettingsStore.save() }
        _live.value = Live(tracks.map { it.id }.toSet(), energies(tracks))
        measure(tracks)
    }

    private fun energies(tracks: List<Track>) = tracks.associate { it.id to titleOf(it).energy }

    /**
     * Measures, one after the other, the titles of the set that are on the phone and that Ketmix never measured
     * (never while a mix is prepared), then orders the coming titles again with what it learnt. Streamed titles
     * are measured as they come up, as always.
     */
    private fun measure(tracks: List<Track>) {
        measureJob?.cancel()
        val context = appContext ?: return
        val todo = tracks.filter { titleOf(it).energy.isNaN() && offline(it) != null }.take(MAX_MEASURED)
        if (todo.isEmpty()) return
        measureJob = scope.launch {
            todo.forEachIndexed { i, track ->
                _live.value = _live.value?.copy(measured = i, measuring = todo.size) ?: return@launch
                while (Automix.busy && isActive) delay(2_000)
                withContext(Dispatchers.IO) { runCatching { measureTitle(context, track) } }
            }
            AnalysisStore.save()
            val set = _live.value ?: return@launch
            _live.value = set.copy(energy = energies(tracks), measured = todo.size, measuring = 0)
            withContext(Dispatchers.Main) { if (on(Player.state.value)) arrange() }
        }
    }

    private const val MAX_MEASURED = 60

    /** Where [track] is on the phone (a download's file, a local title's address), or null when it is only streamed. */
    fun offline(track: Track): String? {
        Downloader.itemFor(track.id)?.takeIf { it.dashManifestUrl == null && it.file.isFile }?.let { return it.file.absolutePath }
        if (!track.id.startsWith("local:")) return null
        return track.streamUrl?.takeIf { it.startsWith("content://") || it.startsWith("file://") || it.startsWith("/") }
    }

    /** An extractor on [source] (see [offline]) with its audio track selected, and that track's format; null when it has none. */
    fun open(context: Context, source: String): Pair<MediaExtractor, MediaFormat>? {
        val extractor = MediaExtractor()
        try {
            if (source.startsWith("/")) extractor.setDataSource(source)
            else extractor.setDataSource(context, android.net.Uri.parse(source), null)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: run { extractor.release(); return null }
            extractor.selectTrack(index)
            return extractor to extractor.getTrackFormat(index)
        } catch (failure: Exception) {
            runCatching { extractor.release() }
            throw failure
        }
    }

    /** Measures the start and the end of a title on the phone, as Ketmix does while it plays (with its key). */
    fun measureTitle(context: Context, track: Track) {
        val source = offline(track) ?: return
        val (extractor, format) = open(context, source) ?: return
        try {
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000L else return
            if (duration < 30_000L) return
            val length = lengthOf(track)
            val depth = AnalysisDepth.of(AppSettings.ketmixAnalysis.value)
            val parts = listOf(true to (0L to min(depth.introMs, duration)), false to (max(0L, duration - depth.outroMs) to duration))
            for ((intro, range) in parts) {
                if ((if (intro) AnalysisStore.intro(track.id, length) else AnalysisStore.outro(track.id, length)) != null) continue
                val tap = AnalysisTap(range.first * 1000L, range.second * 1000L, true, depth.thorough, null)
                com.ketvoice.app.stems.StemLibrary.decode(extractor, format, range.first * 1000L, range.second * 1000L, tap)
                tap.result()?.let { AnalysisStore.put(track.id, length, intro, it) }
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** The energy of each title of [queue] in the set, for the curve the queue shows (NaN: not measured yet). */
    fun curve(queue: List<Track>, set: Live): List<Float> = queue.filter { it.id in set.ids }.map { set.energy[it.id] ?: Float.NaN }
}
