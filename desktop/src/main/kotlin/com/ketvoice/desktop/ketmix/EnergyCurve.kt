package com.ketvoice.desktop.ketmix

import kotlin.math.max

/**
 * The energy of a title over time (Windows 2.0): one value a second on a scale shared by every title (0 silence,
 * about 1 a dense, loud master), from the loudness of its log-mel frames and how busy they are (spectral flux), so
 * that the end of one title and the start of the next can be compared. No neural energy model with a
 * permissive licence exists that adds to this; it feeds energy matching beside S-KEY's key.
 */
object EnergyCurve {
    /** BeatSpectrogram's log(1 + 1000 · mel) of a loud modern master averages about this. */
    private const val SCALE = 4.5f

    fun of(mels: FloatArray, melCount: Int, fps: Float): FloatArray {
        val frames = mels.size / melCount
        val perSecond = max(1, fps.toInt())
        val seconds = frames / perSecond
        if (seconds == 0) return FloatArray(0)
        val raw = FloatArray(seconds) { s ->
            var level = 0f; var flux = 0f
            for (f in s * perSecond until (s + 1) * perSecond) {
                for (m in 0 until melCount) {
                    val v = mels[f * melCount + m]
                    level += v
                    if (f > 0) flux += max(0f, v - mels[(f - 1) * melCount + m])
                }
            }
            level / (perSecond * melCount) + .5f * flux / (perSecond * melCount)
        }
        return FloatArray(seconds) { (raw[it] / SCALE).coerceIn(0f, 1.5f) }
    }

    /** The mean energy between two times (ms), NaN when the curve does not cover them. */
    fun around(curve: FloatArray?, fromMs: Long, untilMs: Long): Float {
        if (curve == null || curve.isEmpty()) return Float.NaN
        val a = (fromMs / 1000).toInt().coerceIn(0, curve.size - 1)
        val b = (untilMs / 1000).toInt().coerceIn(a, curve.size - 1)
        return (a..b).map { curve[it] }.average().toFloat()
    }
}
