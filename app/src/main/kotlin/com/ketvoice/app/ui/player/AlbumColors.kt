package com.ketvoice.app.ui.player

import kotlin.math.pow

object AlbumColors {
    /** The colour of a cover (see [com.ketvoice.app.theme.CoverColors]): its main hue, never an average of two. */
    fun dominant(pixels: IntArray): Int? = com.ketvoice.app.theme.CoverColors.analyze(pixels)?.base

    fun luminance(color: Int): Double {
        fun linear(shift: Int): Double {
            val v = (color shr shift and 255) / 255.0
            return if (v <= .04045) v / 12.92 else ((v + .055) / 1.055).pow(2.4)
        }
        return .2126 * linear(16) + .7152 * linear(8) + .0722 * linear(0)
    }
    fun contrast(a: Int, b: Int): Double {
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
    }
    fun readableAccent(color: Int, surface: Int = 0xFF303038.toInt()): Int {
        var result = color or (255 shl 24)
        while (contrast(result, surface) < 4.5 && result != -1) {
            fun lift(shift: Int) = ((result shr shift and 255) + 8).coerceAtMost(255)
            result = (255 shl 24) or (lift(16) shl 16) or (lift(8) shl 8) or lift(0)
        }
        return result
    }
}
