package com.ketvoice.app.player

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

object AutoMixDsp {

    data class Tempo(val bpm: Float, val confidence: Float, val beatOffsetFrames: Int)

    fun downmix(interleaved: FloatArray, frames: Int, channels: Int, mono: FloatArray, offset: Int = 0): Int {
        val count = minOf(frames, mono.size - offset)
        if (channels <= 1) {
            for (i in 0 until count) mono[offset + i] = interleaved[i]
            return count
        }
        val scale = 1f / channels
        for (i in 0 until count) {
            var sum = 0f
            val base = i * channels
            for (c in 0 until channels) sum += interleaved[base + c]
            mono[offset + i] = sum * scale
        }
        return count
    }

    fun decimate(input: FloatArray, factor: Int): FloatArray {
        if (factor <= 1) return input
        val out = FloatArray(input.size / factor)
        for (i in out.indices) {
            var sum = 0f
            val base = i * factor
            for (k in 0 until factor) sum += input[base + k]
            out[i] = sum / factor
        }
        return out
    }

    fun rms(values: FloatArray, from: Int = 0, to: Int = values.size): Float {
        val start = from.coerceIn(0, values.size)
        val end = to.coerceIn(start, values.size)
        if (end <= start) return 0f
        var sum = 0.0
        for (i in start until end) {
            val v = values[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / (end - start)).toFloat()
    }

    fun peak(values: FloatArray): Float {
        var value = 0f
        for (sample in values) {
            val magnitude = abs(sample)
            if (magnitude > value) value = magnitude
        }
        return value
    }

    fun onsetEnvelope(mono: FloatArray, hop: Int): FloatArray {
        if (mono.isEmpty() || hop <= 0) return FloatArray(0)
        val frames = mono.size / hop
        if (frames == 0) return FloatArray(0)
        val energy = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0.0
            val base = frame * hop
            for (i in base until base + hop) {
                val v = mono[i].toDouble()
                sum += v * v
            }
            energy[frame] = ln(1e-7 + sum / hop).toFloat()
        }
        val onset = FloatArray(frames)
        for (frame in 1 until frames) {
            onset[frame] = (energy[frame] - energy[frame - 1]).coerceAtLeast(0f)
        }
        return onset
    }

    fun tempo(onsets: FloatArray, frameRate: Float, minBpm: Float = 62f, maxBpm: Float = 185f): Tempo {
        if (onsets.size < frameRate * 4f || frameRate <= 0f) return Tempo(0f, 0f, 0)
        val mean = onsets.sum().toDouble() / onsets.size
        val centered = DoubleArray(onsets.size) { onsets[it] - mean }
        val minLag = maxOf(2, (frameRate * 60 / maxBpm).toInt())
        val maxLag = minOf(centered.size / 2, (frameRate * 60 / minBpm).toInt())
        if (maxLag <= minLag + 2) return Tempo(0f, 0f, 0)
        val scores = DoubleArray(maxLag + 2)
        var best = 0.0
        var bestLag = 0
        for (lag in minLag..maxLag) {
            var product = 0.0
            var energy = 0.0
            for (i in lag until centered.size) {
                product += centered[i] * centered[i - lag]
                energy += centered[i] * centered[i]
            }
            if (energy <= 0.0) continue
            val score = product / energy
            val bpm = 60.0 * frameRate / lag
            val weight = exp(-0.5 * (ln(bpm / 118.0) / 0.34) * (ln(bpm / 118.0) / 0.34))
            val weighted = score * weight
            scores[lag] = weighted
            if (weighted > best) {
                best = weighted
                bestLag = lag
            }
        }
        if (bestLag == 0) return Tempo(0f, 0f, 0)
        var refined = bestLag.toDouble()
        if (bestLag in minLag + 1..maxLag - 1) {
            val left = scores[bestLag - 1]
            val right = scores[bestLag + 1]
            val denominator = left - 2 * scores[bestLag] + right
            if (abs(denominator) > 1e-9) refined += 0.5 * (left - right) / denominator
        }
        var baseline = 0.0
        for (lag in minLag..maxLag) baseline += scores[lag]
        baseline /= (maxLag - minLag + 1).toDouble()
        val confidence = if (baseline <= 1e-9) 0f else (best / baseline / 6.0).coerceIn(0.0, 1.0).toFloat()
        val bpm = (60.0 * frameRate / refined).toFloat().coerceIn(minBpm, maxBpm)
        val period = refined
        val offset = beatOffset(centered, period)
        return Tempo(bpm, confidence, offset)
    }

    private fun beatOffset(centered: DoubleArray, period: Double): Int {
        if (period < 2.0) return 0
        val span = ceil(period).toInt().coerceAtLeast(1)
        var bestPhase = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (phase in 0 until span) {
            var score = 0.0
            var index = phase.toDouble()
            while (index < centered.size) {
                val base = index.toInt()
                if (base >= centered.size) break
                score += centered[base]
                index += period
            }
            if (score > bestScore) {
                bestScore = score
                bestPhase = phase
            }
        }
        return bestPhase
    }

    fun vocalRisk(mono: FloatArray, sampleRate: Int): Float {
        if (mono.isEmpty() || sampleRate <= 0) return 0f
        val highPass = 1.0 - exp(-2 * Math.PI * 240.0 / sampleRate)
        val lowPass = 1.0 - exp(-2 * Math.PI * 3_600.0 / sampleRate)
        var low = 0.0
        var upper = 0.0
        var band = 0.0
        var total = 0.0
        for (sample in mono) {
            val value = sample.toDouble()
            low += highPass * (value - low)
            upper += lowPass * (value - upper)
            val mid = upper - low
            band += mid * mid
            total += value * value
        }
        if (total <= 1e-9) return 0f
        val ratio = band / total
        return ((ratio - 0.35) / 0.55).coerceIn(0.0, 1.0).toFloat()
    }

    fun quietTailMs(mono: FloatArray, sampleRate: Int, blockMs: Int = 250): Long {
        if (mono.isEmpty() || sampleRate <= 0) return 0L
        val block = (sampleRate.toLong() * blockMs / 1000L).toInt().coerceAtLeast(1)
        var quiet = 0L
        var end = mono.size
        while (end > 0) {
            val start = (end - block).coerceAtLeast(0)
            val level = rms(mono, start, end)
            if (level > 0.045f) break
            quiet += blockMs.toLong()
            end = start
        }
        return quiet
    }

    fun structureStartMs(mono: FloatArray, sampleRate: Int, windowStartMs: Long, durationMs: Long): Long {
        if (mono.isEmpty() || sampleRate <= 0 || durationMs <= 0) return 0L
        val block = sampleRate
        val count = mono.size / block
        if (count < 5) return 0L
        val energy = FloatArray(count) { index -> rms(mono, index * block, (index + 1) * block) }
        var best = 0L
        var strongest = 0f
        for (index in 1 until count - 1) {
            val position = windowStartMs + index * 1000L
            if (durationMs - position !in 1_000L..15_000L) continue
            val before = energy[index - 1]
            val after = (energy[index] + energy[index + 1]) / 2f
            val drop = (before - after).coerceAtLeast(0f)
            if (before > .045f && after < before * .58f && drop > strongest) {
                strongest = drop
                best = position
            }
        }
        return best
    }
}
