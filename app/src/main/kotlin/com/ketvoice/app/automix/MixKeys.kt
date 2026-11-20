package com.ketvoice.app.automix

import kotlin.math.PI
import kotlin.math.cos

/** The three levels the listener sets for one title at a keyframe (7.6), in percent: its voice, its instruments, the whole title. */
internal data class Levels(val voice: Int = 100, val instruments: Int = 100, val music: Int = 100) {
    val isDefault: Boolean get() = voice == 100 && instruments == 100 && music == 100
    fun clamped() = Levels(voice.coerceIn(0, 100), instruments.coerceIn(0, 100), music.coerceIn(0, 100))
}

/**
 * A volume keyframe of the Ketmix editor (7.6, owner: « des keyframes de volume pour régler le niveau de voix,
 * instrument ou simplement la musique, sur les deux musiques »): [atMs] from the start of the blend on the
 * outgoing title's clock, and the levels of both titles there.
 */
internal data class LevelKey(val atMs: Long, val out: Levels = Levels(), val into: Levels = Levels())

/**
 * The levels of one deck by position in its own title (µs), as gains (1 = as recorded): [voice] and [instruments]
 * act through the title's whole separation in the voice stage, [music] on the whole sound in the deck's DSP.
 */
internal class KeyLevels(val voice: Curve, val instruments: Curve, val music: Curve) {
    val endUs: Long get() = maxOf(voice.endUs, instruments.endUs, music.endUs)
}

/**
 * How keyframes are played. Inside the blend each level eases from one keyframe to the next (a raised cosine,
 * sampled finely; the deck ramps it sample by sample, the voice stage hop by hop, so nothing clicks). The outgoing
 * title starts the blend as recorded and keeps its last keyframe to its end; the incoming one holds its first
 * keyframe from its start and glides back to itself over [RETURN_MS] after the blend, then plays on unchanged.
 */
internal object MixKeys {
    const val MAX = 12
    /** The outgoing title leaves its own level no sooner than this after the blend starts. */
    const val LEAD_MS = 40L
    const val RETURN_MS = 2_000L
    /** Two keyframes closer than this are one. */
    const val MIN_GAP_MS = 120L
    private const val STEPS = 16

    /** A percent as a gain: squared, so that the middle of a control sounds like half as loud. */
    fun gain(percent: Float): Float { val x = (percent / 100f).coerceIn(0f, 1f); return x * x }

    private fun ease(x: Float) = (.5 - .5 * cos(PI * x)).toFloat()

    /** The points one level of one title goes through, (ms from the blend start, percent), anchors included. */
    internal fun points(keys: List<LevelKey>, overlapMs: Long, outgoing: Boolean, pick: (Levels) -> Int): List<Pair<Long, Float>> {
        val end = overlapMs.coerceAtLeast(MIN_GAP_MS)
        val sorted = keys.sortedBy { it.atMs }
        val out = ArrayList<Pair<Long, Float>>()
        if (outgoing) out += 0L to 100f
        for (k in sorted) {
            val at = k.atMs.coerceIn(if (outgoing) LEAD_MS else 0L, end)
            val v = pick(if (outgoing) k.out else k.into).coerceIn(0, 100).toFloat()
            if (out.isNotEmpty() && at <= out.last().first) out[out.size - 1] = out.last().first to v else out += at to v
        }
        if (!outgoing && out.isNotEmpty()) out += (maxOf(out.last().first, end) + RETURN_MS) to 100f
        return out
    }

    /** The level (percent) of one title at [atMs] from the blend start, as heard. */
    fun valueAt(keys: List<LevelKey>, overlapMs: Long, outgoing: Boolean, atMs: Long, pick: (Levels) -> Int): Float {
        val p = points(keys, overlapMs, outgoing, pick)
        if (p.isEmpty()) return 100f
        if (atMs <= p.first().first) return p.first().second
        if (atMs >= p.last().first) return p.last().second
        val i = p.indexOfLast { it.first <= atMs }
        val (t0, v0) = p[i]; val (t1, v1) = p[i + 1]
        return v0 + (v1 - v0) * ease((atMs - t0).toFloat() / (t1 - t0))
    }

    /** All three levels of one title at [atMs]: what a keyframe placed there starts from. */
    fun levelsAt(keys: List<LevelKey>, overlapMs: Long, outgoing: Boolean, atMs: Long): Levels = Levels(
        Math.round(valueAt(keys, overlapMs, outgoing, atMs) { it.voice }),
        Math.round(valueAt(keys, overlapMs, outgoing, atMs) { it.instruments }),
        Math.round(valueAt(keys, overlapMs, outgoing, atMs) { it.music }))

    /** The curves of one deck for [plan], by position in its own title; null without keyframes. */
    fun curves(plan: MixPlan, outgoing: Boolean): KeyLevels? {
        val keys = plan.keys
        if (keys.isNullOrEmpty() || !plan.mixes) return null
        fun us(rel: Long): Long = if (outgoing) (plan.outMs + rel) * 1000L
            else ((plan.inMs + rel * plan.rate.toDouble()) * 1000.0).toLong()
        fun curve(pick: (Levels) -> Int): Curve {
            val p = points(keys, plan.overlapMs, outgoing, pick)
            val parts = ArrayList<Pair<LongArray, FloatArray>>()
            parts += longArrayOf(us(p.first().first)) to floatArrayOf(gain(p.first().second))
            for (i in 0 until p.size - 1) {
                val (t0, v0) = p[i]; val (t1, v1) = p[i + 1]
                if (v0 == v1) { parts += longArrayOf(us(t1)) to floatArrayOf(gain(v1)); continue }
                parts += Curve.shaped(us(t0) + 1, us(t1), STEPS) { x -> gain(v0 + (v1 - v0) * ease(x)) }
            }
            return Curve.join(*parts.toTypedArray())
        }
        return KeyLevels(curve { it.voice }, curve { it.instruments }, curve { it.music })
    }

    /** A keyframe placed at [atMs], with the levels heard there; the same list when the place is taken or the list full. */
    fun place(keys: List<LevelKey>, atMs: Long, overlapMs: Long): List<LevelKey> {
        val at = atMs.coerceIn(0L, overlapMs.coerceAtLeast(0L))
        if (keys.size >= MAX || keys.any { kotlin.math.abs(it.atMs - at) < MIN_GAP_MS }) return keys
        return (keys + LevelKey(at, levelsAt(keys, overlapMs, true, at), levelsAt(keys, overlapMs, false, at))).sortedBy { it.atMs }
    }

    /** The keyframe at [index] moved to [atMs], kept apart from its neighbours. */
    fun move(keys: List<LevelKey>, index: Int, atMs: Long, overlapMs: Long): List<LevelKey> {
        val key = keys.getOrNull(index) ?: return keys
        val others = keys.filterIndexed { i, _ -> i != index }
        val low = others.filter { it.atMs < key.atMs }.maxOfOrNull { it.atMs + MIN_GAP_MS } ?: 0L
        val high = others.filter { it.atMs > key.atMs }.minOfOrNull { it.atMs - MIN_GAP_MS } ?: overlapMs.coerceAtLeast(0L)
        if (high < low) return keys
        return keys.toMutableList().also { it[index] = key.copy(atMs = atMs.coerceIn(low, high)) }
    }

    /** The keyframe nearest [atMs] within [toleranceMs], or -1. */
    fun near(keys: List<LevelKey>, atMs: Long, toleranceMs: Long): Int =
        keys.indices.filter { kotlin.math.abs(keys[it].atMs - atMs) <= toleranceMs }.minByOrNull { kotlin.math.abs(keys[it].atMs - atMs) } ?: -1

    // ---- Storage (inside a pair's choice, see [MixPointStore]) ----

    private val keyPattern = Regex("\\d{1,5}(,\\d{1,3}){6}")

    fun encode(keys: List<LevelKey>): String = keys.sortedBy { it.atMs }.take(MAX).joinToString("/") { k ->
        val o = k.out.clamped(); val i = k.into.clamped()
        "${k.atMs.coerceIn(0L, 60_000L)},${o.voice},${o.instruments},${o.music},${i.voice},${i.instruments},${i.music}"
    }

    /** Null when [text] is not a list of keyframes. */
    fun decode(text: String): List<LevelKey>? {
        if (text.isEmpty()) return emptyList()
        val items = text.split('/')
        if (items.size > MAX) return null
        return items.map { item ->
            if (!keyPattern.matches(item)) return null
            val n = item.split(',').map { it.toInt() }
            if (n[0] > 60_000 || n.drop(1).any { it > 100 }) return null
            LevelKey(n[0].toLong(), Levels(n[1], n[2], n[3]), Levels(n[4], n[5], n[6]))
        }.sortedBy { it.atMs }
    }
}
