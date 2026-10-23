package com.ketvoice.app.player

import kotlin.math.ln

object EqualizerProfile {
    val frequencies = listOf(60, 230, 910, 3600, 14000)
    val presets = linkedMapOf(
        "Plat" to listOf(0, 0, 0, 0, 0),
        "Graves" to listOf(5, 3, 0, -1, 0),
        "Voix" to listOf(-2, 0, 3, 4, 1),
        "Brillance" to listOf(-1, 0, 1, 3, 4),
        "Rock" to listOf(4, 2, -1, 2, 4))
    fun normalize(gains: List<Int>): List<Int> = List(5) { gains.getOrElse(it) { 0 }.coerceIn(-12, 12) }
    /** Map five UI anchors to the real vendor's band centers, not a fake DSP. */
    fun millibels(frequencyHz: Float, gains: List<Int>, minimum: Int, maximum: Int): Int {
        val safe = normalize(gains)
        val x = frequencyHz.coerceAtLeast(1f)
        val i = frequencies.indexOfLast { x >= it }.coerceAtLeast(0)
        val db = if (x <= frequencies.first()) safe.first().toFloat()
        else if (i >= 4) safe.last().toFloat()
        else {
            val fraction = ((ln(x.toDouble()) - ln(frequencies[i].toDouble())) /
                (ln(frequencies[i + 1].toDouble()) - ln(frequencies[i].toDouble()))).toFloat().coerceIn(0f, 1f)
            safe[i] + (safe[i + 1] - safe[i]) * fraction
        }
        return (db * 100).toInt().coerceIn(minimum, maximum)
    }
}
