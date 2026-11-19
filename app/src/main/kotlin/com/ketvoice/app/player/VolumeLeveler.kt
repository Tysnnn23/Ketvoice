@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.ketvoice.app.data.AppSettings
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tanh

/**
 * "Normaliser le volume" (5.9.0): every title at about the same loudness. A slow leveler, as in
 * broadcast loudness control: it measures the sound over several seconds (silences ignored) and
 * moves the gain towards a target very slowly, so that it never pumps within a song; a soft clip
 * keeps the peaks of a raised title under full scale. Its memory is kept across titles, so a new
 * title starts at the level of the last and only drifts if it is really louder or quieter.
 */
internal class VolumeLeveler(sampleRate: Int) {
    companion object {
        const val TARGET_DB = -18f
        const val MAX_BOOST_DB = 6f
        const val MAX_CUT_DB = -9f
        /** Below this level a buffer counts as silence and changes nothing. */
        const val GATE_DB = -50f
        const val MEASURE_SECONDS = 6f
        const val GAIN_SECONDS = 4f

        fun db(power: Double): Float = if (power <= 1e-12) -120f else (10.0 * log10(power)).toFloat()
        fun linear(db: Float): Float = 10f.pow(db / 20f)
        /** The gain that brings a measured loudness to the target, within the limits. */
        fun wanted(loudnessDb: Float): Float = (TARGET_DB - loudnessDb).coerceIn(MAX_CUT_DB, MAX_BOOST_DB)
        /** Above 0.9 a raised sample bends softly towards 1 instead of clipping. */
        fun soft(x: Float): Float = if (x > .9f) .9f + .1f * tanh((x - .9f) / .1f) else if (x < -.9f) -.9f - .1f * tanh((-x - .9f) / .1f) else x
    }

    val rate = sampleRate.coerceAtLeast(1)
    private var power = linear(TARGET_DB).let { (it * it).toDouble() }
    var gainDb = 0f
        private set

    /**
     * Measures one buffer of [frames] frames with mean square [meanSquare], then moves the gain. While [hold]
     * (7.1: a Ketmix transition is heard) neither moves: two titles crossing, or a fade dying away, are not
     * levelled back up, so the crossing has no bump and an ending keeps the level it was mixed at.
     */
    fun observe(meanSquare: Double, frames: Int, hold: Boolean = false) {
        if (frames <= 0 || hold) return
        val seconds = frames.toFloat() / rate
        if (db(meanSquare) > GATE_DB) {
            val a = 1.0 - exp(-seconds / MEASURE_SECONDS.toDouble())
            power += (meanSquare - power) * a
        }
        val b = 1f - exp(-seconds / GAIN_SECONDS)
        gainDb += (wanted(db(power)) - gainDb) * b
    }
}

/** 7.1: tells whether a Ketmix transition is heard; null until Ketmix is first used. */
internal object LevelHold {
    @Volatile var mixing: (() -> Boolean)? = null
}

internal class LevelingAudioProcessor : BaseAudioProcessor() {
    private var leveler: VolumeLeveler? = null
    private var appliedGain = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..8)
            return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush() {
        // The measure is kept from one title to the next; only a change of rate starts it again.
        if (leveler == null || leveler?.rate != inputAudioFormat.sampleRate) leveler = VolumeLeveler(inputAudioFormat.sampleRate)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        // Media3's shared empty buffer is also what an empty output is: nothing to copy (see ReverbAudioProcessor).
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        val level = leveler
        if (!AppSettings.volumeLeveling.value || level == null) {
            appliedGain = 1f
            output.put(inputBuffer)
            output.flip()
            return
        }
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val start = inputBuffer.position()
        var sum = 0.0
        var count = 0
        while (inputBuffer.remaining() >= 2) {
            val v = inputBuffer.short / 32768f
            sum += v * v; count++
        }
        inputBuffer.position(start)
        level.observe(if (count > 0) sum / count else 0.0, count / channels,
            hold = LevelHold.mixing?.invoke() == true)
        val target = VolumeLeveler.linear(level.gainDb)
        // The gain glides across the buffer from its last value: no step is ever heard.
        val from = appliedGain
        val samples = count.coerceAtLeast(1)
        var i = 0
        while (inputBuffer.remaining() >= 2) {
            val g = from + (target - from) * (i.toFloat() / samples)
            val v = VolumeLeveler.soft(inputBuffer.short / 32768f * g)
            output.putShort((v * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            i++
        }
        appliedGain = target
        output.flip()
    }

    override fun onReset() { leveler = null; appliedGain = 1f }
}
