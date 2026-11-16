package com.ketvoice.app.player

import kotlin.math.ln

/**
 * Ten bands since 5.9.0 (the octaves from 31 Hz to 16 kHz, as on a graphic equalizer). Gains saved
 * by earlier versions on their five bands are carried over by interpolation, so nothing changes in
 * what the listener hears after the update.
 */
object EqualizerProfile {
    val frequencies = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    /** The five bands of versions before 5.9.0. */
    val legacyFrequencies = listOf(60, 230, 910, 3600, 14000)
    val presets = linkedMapOf(
        "Plat" to listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        "Graves" to listOf(6, 5, 4, 2, 0, 0, 0, 0, 0, 0),
        "Voix" to listOf(-2, -2, -1, 0, 2, 4, 4, 3, 1, 0),
        "Nuit" to listOf(-4, -3, -1, 0, 1, 2, 2, 1, -1, -3),
        "Brillance" to listOf(-1, -1, 0, 0, 0, 1, 2, 3, 4, 5),
        "Rock" to listOf(5, 4, 2, 0, -1, -1, 1, 3, 4, 4),
        "Électro" to listOf(5, 4, 1, 0, -2, 0, 1, 2, 4, 5))

    fun normalize(gains: List<Int>): List<Int> {
        val bounded = gains.map { it.coerceIn(-12, 12) }
        if (bounded.size == legacyFrequencies.size) return frequencies.map { hz ->
            Math.round(interpolate(hz.toFloat(), legacyFrequencies, bounded))
        }
        return List(frequencies.size) { bounded.getOrElse(it) { 0 } }
    }

    private fun interpolate(x0: Float, anchors: List<Int>, gains: List<Int>): Float {
        val x = x0.coerceAtLeast(1f)
        if (x <= anchors.first()) return gains.first().toFloat()
        if (x >= anchors.last()) return gains.last().toFloat()
        val i = anchors.indexOfLast { x >= it }.coerceIn(0, anchors.size - 2)
        val fraction = ((ln(x.toDouble()) - ln(anchors[i].toDouble())) /
            (ln(anchors[i + 1].toDouble()) - ln(anchors[i].toDouble()))).toFloat().coerceIn(0f, 1f)
        return gains[i] + (gains[i + 1] - gains[i]) * fraction
    }

    /** The gain in dB at [frequencyHz] of a ten-band setting, interpolated between the bands. */
    fun db(frequencyHz: Float, gains: List<Int>): Float = interpolate(frequencyHz, frequencies, normalize(gains))

    fun millibels(frequencyHz: Float, gains: List<Int>, minimum: Int, maximum: Int): Int =
        (db(frequencyHz, gains) * 100).toInt().coerceIn(minimum, maximum)

    /** The upper edge of band [index]: halfway (on a log scale) to the next band; the last one ends at 20 kHz. */
    fun cutoffHz(index: Int): Float =
        if (index >= frequencies.lastIndex) 20_000f else kotlin.math.sqrt(frequencies[index].toFloat() * frequencies[index + 1])
}
