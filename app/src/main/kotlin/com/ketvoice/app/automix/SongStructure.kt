package com.ketvoice.app.automix

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Song structure (Windows 2.0, « Maximum »): where the intro, verses, choruses, a bridge and the outro start, read
 * from the whole title's log-mel frames (the beat model's own spectrogram, 50 a second) on its bar grid.
 *
 * No permissively licensed neural structure model could be converted (see docs/licenses/ketmix-pc-models.md:
 * All-In-One is MIT but needs NATTEN's custom attention and a 4-stem front end), so this is the classic recipe:
 * bar-synchronous timbre vectors, their self-similarity, a checkerboard novelty curve whose peaks are the
 * boundaries (snapped to bars, sections of at least [MIN_BARS] bars), then sections grouped by what they sound like:
 * the loudest group that comes back is the chorus, a quieter first section the intro, a quieter last one the outro,
 * a section heard once after the middle the bridge, the rest verses. Pure: no audio, no model.
 *
 * Shared with the phone (9.0): there it reads the stretches Ketmix already decodes (a title's start and end) from
 * cheap band energies instead of log-mel frames, see [SongSections].
 */
object SongStructure {
    enum class Label { Intro, Verse, Chorus, Bridge, Outro }
    data class Section(val startMs: Long, val endMs: Long, val label: Label)

    const val MIN_BARS = 4
    private const val KERNEL_BARS = 8
    private const val BANDS = 32
    private const val SAME = .92f

    /**
     * [mels]: frames × [melCount] log-mel values at [fps]; [barsMs]: the downbeats (ms). Empty when too short to tell.
     */
    fun sections(mels: FloatArray, melCount: Int, fps: Float, barsMs: List<Double>, durationMs: Long): List<Section> {
        if (barsMs.size < 3 * MIN_BARS) return emptyList()
        val vectors = barVectors(mels, melCount, fps, barsMs)
        val bands = vectors.first().size - 1
        val energy = vectors.map { it.last() }
        val bounds = boundaries(vectors.map { it.copyOf(bands) })
        val edges = listOf(0) + bounds + listOf(vectors.size)
        val spans = edges.zipWithNext().filter { (a, b) -> b > a }
        val starts = spans.map { (a, _) -> if (a == 0) 0L else barsMs[a].toLong() }
        val ends = spans.map { (_, b) -> if (b >= barsMs.size) durationMs else barsMs[b].toLong() }
        val labels = label(spans.map { (a, b) -> mean(vectors.subList(a, b).map { it.copyOf(bands) }) },
            spans.map { (a, b) -> energy.subList(a, b).average().toFloat() })
        return spans.indices.map { Section(starts[it], ends[it].coerceAtMost(durationMs), labels[it]) }
    }

    /**
     * Per bar: the mean of [BANDS] grouped mel bands (unit length), then the bar's loudness as the last value. Fewer
     * input bands than [BANDS] (the phone's cheap band energies, see [SongSections]) are kept one by one.
     */
    internal fun barVectors(mels: FloatArray, melCount: Int, fps: Float, barsMs: List<Double>): List<FloatArray> {
        val frames = mels.size / melCount
        val bands = min(BANDS, melCount)
        val group = max(1, melCount / bands)
        return barsMs.indices.map { i ->
            val from = (barsMs[i] / 1000.0 * fps).toInt().coerceIn(0, frames)
            val until = (if (i + 1 < barsMs.size) barsMs[i + 1] / 1000.0 * fps else from + 2.0 * fps).toInt().coerceIn(from, frames)
            val v = FloatArray(bands + 1)
            for (f in from until until) for (m in 0 until min(melCount, bands * group)) v[m / group] += mels[f * melCount + m]
            val n = max(1, until - from) * group
            for (b in 0 until bands) v[b] /= n
            v[bands] = v.take(bands).average().toFloat()
            normalize(v, bands)
            v
        }
    }

    /** Bar indices where a new section starts: peaks of the checkerboard novelty, at least [MIN_BARS] apart. */
    internal fun boundaries(vectors: List<FloatArray>): List<Int> {
        val n = vectors.size
        val novelty = FloatArray(n) { i -> checkerboard(vectors, i) }
        val mean = novelty.average().toFloat()
        val spread = sqrt(novelty.map { (it - mean) * (it - mean) }.average()).toFloat()
        val found = ArrayList<Int>()
        for (i in MIN_BARS until n - MIN_BARS + 1) {
            val v = novelty[i]
            if (v < mean + .5f * spread) continue
            if ((max(0, i - 2)..min(n - 1, i + 2)).any { novelty[it] > v }) continue
            if (found.isNotEmpty() && i - found.last() < MIN_BARS) { if (v > novelty[found.last()]) found[found.size - 1] = i; continue }
            found += i
        }
        // Sections are mostly 4, 8 or 16 bars: a boundary one bar off a multiple of four from the previous one moves onto it.
        return found.map { b -> snapToPhrase(b, found.lastOrNull { it < b } ?: 0, n) }.distinct().sorted()
    }

    private fun snapToPhrase(b: Int, previous: Int, n: Int): Int {
        val off = (b - previous) % 4
        val snapped = when (off) { 1 -> b - 1; 3 -> b + 1; else -> b }
        return if (snapped - previous >= MIN_BARS && snapped <= n - MIN_BARS) snapped else b
    }

    private fun checkerboard(v: List<FloatArray>, at: Int): Float {
        val half = KERNEL_BARS / 2
        var sum = 0f
        var count = 0
        for (i in at - half until at + half) for (j in at - half until at + half) {
            if (i !in v.indices || j !in v.indices) continue
            val sign = if ((i < at) == (j < at)) 1f else -1f
            val taper = 1f - (abs(i - at + .5f) + abs(j - at + .5f)) / (2f * half + 1f)
            sum += sign * taper * cosine(v[i], v[j]); count++
        }
        return if (count == 0) 0f else sum / count
    }

    /** Chorus, intro, outro, bridge, verse (see the class). */
    internal fun label(means: List<FloatArray>, loudness: List<Float>): List<Label> {
        val n = means.size
        if (n == 0) return emptyList()
        val group = IntArray(n) { -1 }
        var groups = 0
        for (i in 0 until n) if (group[i] < 0) {
            group[i] = groups
            for (j in i + 1 until n) if (group[j] < 0 && cosine(means[i], means[j]) >= SAME) group[j] = groups
            groups++
        }
        val loud = loudness.maxOrNull() ?: 0f
        val quiet = loudness.minOrNull() ?: 0f
        val gap = max(1e-6f, loud - quiet)
        val repeated = (0 until groups).filter { g -> group.count { it == g } >= 2 }
        val chorus = repeated.maxByOrNull { g -> (0 until n).filter { group[it] == g }.map { loudness[it] }.average() } ?: -1
        return (0 until n).map { i ->
            val soft = (loudness[i] - quiet) / gap < .4f
            when {
                i == 0 && n > 2 && soft && group[i] != chorus -> Label.Intro
                i == n - 1 && n > 2 && soft && group[i] != chorus -> Label.Outro
                group[i] == chorus -> Label.Chorus
                group.count { it == group[i] } == 1 && i > n / 2 && i < n - 1 -> Label.Bridge
                else -> Label.Verse
            }
        }
    }

    /** The section starts, in ms, that a mix may land on (all of them but the title's own start). */
    fun starts(sections: List<Section>): List<Double> = sections.drop(1).map { it.startMs.toDouble() }

    private fun mean(v: List<FloatArray>): FloatArray {
        val out = FloatArray(v.firstOrNull()?.size ?: 0)
        for (x in v) for (k in out.indices) out[k] += x[k] / v.size
        return out
    }

    private fun normalize(v: FloatArray, count: Int) {
        val m = (0 until count).map { v[it] }.average().toFloat()
        var norm = 0f
        for (k in 0 until count) { v[k] -= m; norm += v[k] * v[k] }
        val s = sqrt(norm)
        if (s > 1e-9f) for (k in 0 until count) v[k] /= s
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (k in 0 until min(a.size, b.size)) { dot += a[k] * b[k]; na += a[k] * a[k]; nb += b[k] * b[k] }
        val s = sqrt(na * nb)
        return if (s > 1e-12f) dot / s else 0f
    }
}
