@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * A headphone correction (9.0) as a cascade of biquads, for one sample rate and channel count: the safe preamp first
 * (so no frequency is lifted above full scale), then each filter in transposed direct form II, in double precision.
 */
internal class EqCascade(val profile: HeadphoneProfile, val sampleRate: Int, val channels: Int) {
    private val sections = profile.filters.map { Biquad.of(it, sampleRate) }.filter { it != Biquad.IDENTITY }.toTypedArray()
    val gain = VolumeLeveler.linear(profile.safePreampDb(sampleRate)).toDouble()
    private val z1 = Array(sections.size) { DoubleArray(channels) }
    private val z2 = Array(sections.size) { DoubleArray(channels) }

    fun sample(input: Double, channel: Int): Double {
        var v = input * gain
        for (i in sections.indices) {
            val c = sections[i]
            val s1 = z1[i]; val s2 = z2[i]
            val y = c.b0 * v + s1[channel]
            s1[channel] = c.b1 * v - c.a1 * y + s2[channel]
            s2[channel] = c.b2 * v - c.a2 * y
            v = y
        }
        return v
    }

    /** Clears the filters' memory of vanishing values, which would otherwise slow the processor down in silence. */
    fun settle() {
        for (i in sections.indices) for (ch in 0 until channels) {
            if (abs(z1[i][ch]) < 1e-20) z1[i][ch] = 0.0
            if (abs(z2[i][ch]) < 1e-20) z2[i][ch] = 0.0
        }
    }

    fun reset() { z1.forEach { it.fill(0.0) }; z2.forEach { it.fill(0.0) } }
}

/**
 * « Égaliseur par casque » (9.0) in the audio chain, between [com.ketvoice.app.automix.DeckMixProcessor] and
 * [LevelingAudioProcessor]: each deck runs its own, so a Ketmix blend and the voice level are corrected alike, and the
 * leveler's soft limit and the system equalizer's limiter come after it. A change of correction (another output, the
 * switch, « Écouter sans correction ») crossfades over [FADE_MS] instead of stepping.
 */
internal class HeadphoneEqProcessor(private val source: () -> HeadphoneProfile? = { HeadphoneEq.playing }) : BaseAudioProcessor() {
    private var cascade: EqCascade? = null
    private var leaving: EqCascade? = null
    private var fadeFrames = 0
    /** False until the stream's first sound: the correction then starts at once, with nothing to fade from. */
    private var started = false
    private var fadeDone = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..8 ||
            inputAudioFormat.sampleRate <= 0) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush() {
        // Media3 flushes on every change of speed too: the filters keep their memory unless the format changed.
        val format = inputAudioFormat
        if (cascade?.let { it.sampleRate != format.sampleRate || it.channels != format.channelCount } == true) { cascade = null; started = false }
        leaving = null; fadeFrames = 0
    }

    /** Moves to what [source] wants: at once when nothing was heard yet, else through a crossfade. */
    private fun follow(rate: Int, channels: Int) {
        val wanted = source()
        val now = cascade
        if (wanted === now?.profile) return
        if (wanted != null && now != null && wanted == now.profile) return
        cascade = wanted?.let { EqCascade(it, rate, channels) }
        if (!started) return
        leaving = now
        fadeFrames = (rate * FADE_MS / 1000).coerceAtLeast(1); fadeDone = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        // Media3's shared empty buffer is also what an empty output is: nothing to copy (see ReverbAudioProcessor).
        if (size == 0) return
        val rate = inputAudioFormat.sampleRate
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        follow(rate, channels)
        started = true
        val output = replaceOutputBuffer(size)
        val eq = cascade
        if (eq == null && fadeFrames == 0) { output.put(inputBuffer); output.flip(); return }
        var channel = 0
        while (inputBuffer.remaining() >= 2) {
            val x = inputBuffer.short / 32768.0
            val y = mix(x, channel)
            output.putShort((y * 32768.0).toInt().coerceIn(-32768, 32767).toShort())
            channel++
            if (channel == channels) { channel = 0; if (fadeFrames > 0 && ++fadeDone >= fadeFrames) { fadeFrames = 0; leaving = null } }
        }
        cascade?.settle(); leaving?.settle()
        output.flip()
    }

    private fun mix(x: Double, channel: Int): Double {
        val now = cascade?.sample(x, channel) ?: x
        if (fadeFrames == 0) return now
        val before = leaving?.sample(x, channel) ?: x
        val t = fadeDone.toDouble() / fadeFrames
        return before + (now - before) * t
    }

    override fun onReset() { cascade = null; leaving = null; fadeFrames = 0; started = false }

    companion object { const val FADE_MS = 60 }
}
