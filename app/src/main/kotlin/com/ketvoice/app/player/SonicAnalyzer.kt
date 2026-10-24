package com.ketvoice.app.player

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

data class SonicLevels(val low: Float = 0f, val mid: Float = 0f, val high: Float = 0f)

/** Rough spectral energy, not beat/voice identification. Never modifies PCM data. */
class SonicAnalyzer {
    private var rate = 48000
    private var channels = 2
    private var floating = false
    private val low = DoubleArray(32)
    private val upper = DoubleArray(32)
    private var a = 0.0
    private var b = 0.0
    private var frames = 0
    private var lowEnergy = 0.0
    private var midEnergy = 0.0
    private var highEnergy = 0.0
    fun configure(sampleRate: Int, channelCount: Int, floatPcm: Boolean) {
        rate = sampleRate.coerceIn(8000, 384000); channels = channelCount.coerceIn(1, 32); floating = floatPcm
        a = 1.0 - exp(-2 * PI * 180 / rate); b = 1.0 - exp(-2 * PI * 2400 / rate)
        reset()
    }
    fun reset() { low.fill(0.0); upper.fill(0.0); frames = 0; lowEnergy = 0.0; midEnergy = 0.0; highEnergy = 0.0 }
    fun accept(input: ByteBuffer): SonicLevels? {
        val data = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val width = if (floating) 4 else 2
        var result: SonicLevels? = null
        while (data.remaining() >= width * channels) {
            repeat(channels) { channel ->
                val sample = if (floating) data.float else data.short / 32768f
                val value = if (sample.isFinite()) sample.coerceIn(-1f, 1f).toDouble() else 0.0
                low[channel] += a * (value - low[channel]); upper[channel] += b * (value - upper[channel])
                lowEnergy += low[channel] * low[channel]
                midEnergy += (upper[channel] - low[channel]) * (upper[channel] - low[channel])
                highEnergy += (value - upper[channel]) * (value - upper[channel])
            }
            frames++
            if (frames >= rate / 30) {
                fun energy(value: Double) = (sqrt(value / frames / channels) * 4).toFloat().coerceIn(0f, 1f)
                result = SonicLevels(energy(lowEnergy), energy(midEnergy), energy(highEnergy))
                frames = 0; lowEnergy = 0.0; midEnergy = 0.0; highEnergy = 0.0
            }
        }
        return result
    }
}
