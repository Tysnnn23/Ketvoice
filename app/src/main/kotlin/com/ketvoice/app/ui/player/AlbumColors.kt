package com.ketvoice.app.ui.player

import kotlin.math.pow

object AlbumColors {
    fun dominant(pixels: IntArray): Int? {
        val buckets = mutableMapOf<Int, MutableList<Int>>()
        pixels.filter { (it ushr 24) >= 128 }.forEach { pixel ->
            val r = pixel shr 16 and 255; val g = pixel shr 8 and 255; val b = pixel and 255
            val key = (r / 32 shl 6) or (g / 32 shl 3) or (b / 32)
            buckets.getOrPut(key) { mutableListOf() }.add(pixel)
        }
        val chosen = buckets.values.maxByOrNull { group ->
            val color = group.first()
            val channels = listOf(color shr 16 and 255, color shr 8 and 255, color and 255)
            val chroma = (channels.max() - channels.min()) / 255.0
            group.size * (.25 + chroma)
        } ?: return null
        fun channel(shift: Int) = chosen.sumOf { (it shr shift and 255).toLong() }.div(chosen.size).toInt()
        return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

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
