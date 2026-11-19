package com.ketvoice.app.player

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

internal object QualityHandoff {
    // ---- 7.2: the two renditions are brought together in time, not only measured.
    // The sound measure (offset) is made in each title's own time: it tells where the better stream must be
    // (the old position plus the lag), and it does not change when one of them moves. Before 7.2 the better
    // stream was moved by the lag and the sound measured again, which gave the same lag, so it was moved again,
    // and after three tries the handoff was given up: renditions of two different encoders never switched.
    // Now the lag is measured once and the gap between the two decks is read from their positions.

    /** A gap above this is closed by a seek; below, by playing the muted better stream a little faster or slower. */
    const val SEEK_ABOVE_MS = 400.0
    /** After the last step the two may still be this far apart: the crossfade is then longer, so no echo is heard. */
    const val ACCEPT_MS = 20.0
    /** Steps tried before the handoff is given up. */
    const val MAX_STEPS = 6
    /** A changed speed reaches the ear after what the audio track holds (up to a second): positions are read after. */
    const val SETTLE_MS = 1_200L
    /** Equal-power crossfade, ms: short when the two are heard as one, longer when they are only close. */
    fun crossfadeMs(errorMs: Double): Long = if (abs(errorMs) <= HEARD_ALIGNED_MS) 96L else 240L
    fun acceptable(errorMs: Double): Boolean = abs(errorMs) <= ACCEPT_MS

    /** What to do next, given [errorMs] = where the better stream must be minus where it is, in ms of the title. */
    sealed interface Step {
        data object Done : Step
        data class Seek(val byMs: Long) : Step
        /** Play at [factor] times the listener's speed for [forMs] ms (of the title) while muted. */
        data class Nudge(val factor: Float, val forMs: Long) : Step
    }

    fun step(errorMs: Double): Step {
        val gap = abs(errorMs)
        if (gap <= HEARD_ALIGNED_MS) return Step.Done
        if (gap > SEEK_ABOVE_MS) return Step.Seek(Math.round(errorMs))
        // About two seconds per step, gentler for small gaps so that the last one lands precisely.
        val rate = (gap / 2_000.0).coerceIn(.02, .15)
        val factor = (if (errorMs > 0) 1.0 + rate else 1.0 - rate).toFloat()
        return Step.Nudge(factor, kotlin.math.ceil(gap / rate).toLong())
    }

    /** Where the better stream is against the heard one, ms: the median of paired readings (old, next), which are noisy. */
    fun skew(pairs: List<Pair<Long, Long>>): Double {
        val d = pairs.map { (old, next) -> (next - old).toDouble() }.sorted()
        if (d.isEmpty()) return 0.0
        return if (d.size % 2 == 1) d[d.size / 2] else (d[d.size / 2 - 1] + d[d.size / 2]) / 2
    }

    /** The rank of the stream heard and the best one found since: one handoff at a time, the best first. */
    class BetterStream<T : Any>(private val rank: (T) -> Int, heard: T? = null) {
        private var playing = heard?.let(rank) ?: 0
        private var waiting: T? = null
        private var failures = 0
        /** Whether [candidate] becomes the one to try: better than what plays and than what already waits. */
        @Synchronized fun offer(candidate: T): Boolean {
            val r = rank(candidate)
            if (r <= playing || r <= (waiting?.let(rank) ?: 0)) return false
            waiting = candidate; failures = 0
            return true
        }
        @Synchronized fun next(): T? = waiting
        @Synchronized fun heard(stream: T) {
            playing = rank(stream)
            if (waiting?.let(rank)?.let { it <= playing } != false) { waiting = null; failures = 0 }
        }
        /** A handoff that did not happen; false once [candidate] is given up (after [MAX_TRIES]). */
        @Synchronized fun failed(candidate: T): Boolean {
            if (waiting !== candidate) return true
            if (++failures < MAX_TRIES) return true
            waiting = null; failures = 0
            return false
        }
        companion object { const val MAX_TRIES = 3; const val RETRY_MS = 3_000L }
    }

    /** A title started from the audio cache still looks for better on a free network (7.2: before, it never did). */
    fun keepLookingFromCache(highQuality: Boolean, online: Boolean, metered: Boolean, lossless: Boolean, cachedRank: Int): Boolean =
        highQuality && online && !metered && cachedRank < (if (lossless) 10_000 else 256)

    /** How far apart two renditions' durations may be once their sound is aligned by ear (6.0.0 Beta 4). */
    const val ALIGNED_DURATION_MS = 4_000L
    /** Without a measure of the sound, the durations must agree this closely. */
    const val POSITION_DURATION_MS = 1_500L

    /** Do not silently switch to another edit, a live rendition or an ended track. */
    fun compatible(oldDuration: Long, newDuration: Long, position: Long, toleranceMs: Long = POSITION_DURATION_MS): Boolean =
        oldDuration > 0 && newDuration > 0 && abs(oldDuration - newDuration) <= toleranceMs &&
            position >= 0 && position < minOf(oldDuration, newDuration) - 2000
    fun aligned(oldPosition: Long, newPosition: Long): Boolean = abs(oldPosition - newPosition) <= 60

    /** Two renditions are heard as one once their sound is this close (a crossfade of a few ms is inaudible). */
    const val HEARD_ALIGNED_MS = 6.0
    /** Below this likeness the two are not the same recording (another edit, a live version): no switch. */
    const val SAME_RECORDING = .55f

    /** Loudness envelope: the level of the mono sound every [hopMs] milliseconds, in dB, of interleaved [pcm]. */
    fun envelope(pcm: ShortArray, rate: Int, channels: Int, hopMs: Double = 2.0): FloatArray {
        if (rate <= 0 || channels <= 0) return FloatArray(0)
        val hop = (rate * hopMs / 1000.0).toInt().coerceAtLeast(1)
        val frames = pcm.size / channels
        val out = FloatArray(frames / hop)
        for (f in out.indices) {
            var sum = 0.0
            for (i in f * hop until (f + 1) * hop) {
                var m = 0.0
                for (c in 0 until channels) m += pcm[i * channels + c]
                m /= channels
                sum += m * m
            }
            out[f] = (10.0 * ln(sum / hop + 1.0) / ln(10.0)).toFloat()
        }
        return out
    }

    /**
     * How much later (+) or earlier (−), in ms, the second rendition holds the same sound than the first,
     * measured on their envelopes [a] (first frame at [aStartUs] in the title) and [b] (at [bStartUs]),
     * with how alike they are there (normalised correlation, −1..1). Null without enough common sound.
     * Positions reported by two decoders agree only roughly (encoders add priming, seeks in MP3 or AAC are
     * estimated): only the sound itself tells where they really are.
     */
    fun offset(a: FloatArray, aStartUs: Long, b: FloatArray, bStartUs: Long, hopMs: Double = 2.0, maxMs: Double = 450.0): Pair<Double, Float>? {
        val hopUs = hopMs * 1000.0
        val shift = Math.round((bStartUs - aStartUs) / hopUs).toInt()
        val reach = (maxMs / hopMs).toInt()
        var bestLag = 0; var best = -2f
        for (lag in -reach..reach) {
            // a[i] is compared with b at the same title position plus lag: b index i - shift + lag.
            var n = 0; var sa = 0.0; var sb = 0.0
            for (i in a.indices) { val j = i - shift + lag; if (j in b.indices) { sa += a[i]; sb += b[j]; n++ } }
            if (n < 250) continue
            val ma = sa / n; val mb = sb / n
            var num = 0.0; var da = 0.0; var db = 0.0
            for (i in a.indices) {
                val j = i - shift + lag
                if (j !in b.indices) continue
                val x = a[i] - ma; val y = b[j] - mb
                num += x * y; da += x * x; db += y * y
            }
            if (da <= 1e-9 || db <= 1e-9) continue
            val r = (num / sqrt(da * db)).toFloat()
            if (r > best) { best = r; bestLag = lag }
        }
        return if (best < -1f) null else bestLag * hopMs to best
    }
}
