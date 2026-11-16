package com.ketvoice.app.theme

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The colour of a cover (5.9.1), shared by album and artist pages, the player and the app-wide tint.
 *
 * Earlier versions averaged colours in coarse RGB cubes or took the most common cube whatever its
 * colour: a colourful cover on a large dark or grey ground came out grey, and two hues averaged
 * together came out as a third one (red and blue → purple, yellow and blue → green). Here pixels
 * are grouped by hue; the hue that holds the most colour wins, and its colour is the average of
 * that hue's pixels only, so it is a colour really on the cover. A cover is grey only when almost
 * none of it carries colour.
 */
internal object CoverColors {
    private const val BINS = 24
    /** Below this chroma (0..1) a pixel is grey for us. */
    const val GREY_CHROMA = .14f
    /** A cover is colourful when at least this share of its pixels carry colour. */
    const val COLOURFUL_SHARE = .05f

    data class Result(
        /** A colour of the cover: the average of the pixels of its main hue (or of its greys). */
        val base: Int,
        /** Another hue of the cover, far enough from the first, or null. */
        val second: Int?,
        val colourful: Boolean,
        val hue: Float,
    )

    fun analyze(pixels: IntArray): Result? {
        val weight = DoubleArray(BINS); val count = IntArray(BINS)
        val sums = Array(BINS) { DoubleArray(3) }
        var opaque = 0; var chromatic = 0
        // Greys by lightness: a grey cover takes its most common grey, not the average of black and white.
        val greys = Array(8) { DoubleArray(4) }
        for (p in pixels) {
            if ((p ushr 24) < 128) continue
            opaque++
            val r = (p shr 16 and 255) / 255f; val g = (p shr 8 and 255) / 255f; val b = (p and 255) / 255f
            val max = maxOf(r, g, b); val min = minOf(r, g, b)
            val chroma = max - min; val light = (max + min) / 2f
            if (chroma < GREY_CHROMA || light < .07f || light > .96f) {
                val g8 = greys[(light * 7.999f).toInt().coerceIn(0, 7)]
                g8[0] += r; g8[1] += g; g8[2] += b; g8[3]++
                continue
            }
            chromatic++
            val bin = ((hue(r, g, b, max, chroma) / 360f * BINS).toInt()).coerceIn(0, BINS - 1)
            // Vivid pixels of a medium lightness say the most about the cover's colour.
            val w = chroma.toDouble().pow(1.3) * (1.0 - abs(light - .5f) * .9)
            weight[bin] += w; count[bin]++
            sums[bin][0] += r * w; sums[bin][1] += g * w; sums[bin][2] += b * w
        }
        if (opaque == 0) return null
        if (chromatic.toFloat() / opaque < COLOURFUL_SHARE || chromatic == 0) {
            val top = greys.maxByOrNull { it[3] }!!
            val n = top[3].coerceAtLeast(1.0)
            return Result(rgb(top[0] / n, top[1] / n, top[2] / n), null, false, 0f)
        }
        // The peak is chosen on bins smoothed with their neighbours, so a hue split across two bins still wins.
        fun smoothed(i: Int) = weight[i] + .5 * (weight[(i + 1) % BINS] + weight[(i + BINS - 1) % BINS])
        val peak = (0 until BINS).maxByOrNull(::smoothed)!!
        val main = cluster(peak, weight, sums)
        val second = (0 until BINS)
            .filter { binDistance(it, peak) >= 3 && smoothed(it) >= smoothed(peak) * .25 }
            .maxByOrNull(::smoothed)?.let { cluster(it, weight, sums) }
        return Result(main.first, second?.first, true, main.second)
    }

    /** Average colour of [center] and its two neighbours, weighted, with its hue. */
    private fun cluster(center: Int, weight: DoubleArray, sums: Array<DoubleArray>): Pair<Int, Float> {
        var w = 0.0; var r = 0.0; var g = 0.0; var b = 0.0; var x = 0.0; var y = 0.0
        for (d in -1..1) {
            val i = (center + d + BINS) % BINS
            w += weight[i]; r += sums[i][0]; g += sums[i][1]; b += sums[i][2]
            val angle = Math.toRadians((i + .5) * 360.0 / BINS)
            x += cos(angle) * weight[i]; y += sin(angle) * weight[i]
        }
        val hue = ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
        return rgb(r / w, g / w, b / w) to hue
    }

    private fun binDistance(a: Int, b: Int): Int { val d = abs(a - b); return minOf(d, BINS - d) }

    fun hue(r: Float, g: Float, b: Float, max: Float, chroma: Float): Float {
        val h = when (max) {
            r -> ((g - b) / chroma) % 6f
            g -> (b - r) / chroma + 2f
            else -> (r - g) / chroma + 4f
        } * 60f
        return if (h < 0) h + 360f else h
    }

    private fun rgb(r: Double, g: Double, b: Double): Int {
        fun c(v: Double) = Math.round(v * 255).toInt().coerceIn(0, 255)
        return (255 shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    /** The saturation (HSL) of an ARGB colour, 0..1. */
    fun saturation(color: Int): Float {
        val r = (color shr 16 and 255) / 255f; val g = (color shr 8 and 255) / 255f; val b = (color and 255) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val l = (max + min) / 2f
        return if (max == min) 0f else (max - min) / (1f - abs(2f * l - 1f))
    }
}
