package com.ketvoice.app.player

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

internal object QualityHandoff {
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
