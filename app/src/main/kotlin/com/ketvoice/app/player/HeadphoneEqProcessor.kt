@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * « Égaliseur par casque » (9.0) in the audio chain, between [com.ketvoice.app.automix.DeckMixProcessor] and
 * [LevelingAudioProcessor]: each deck runs its own, so a Ketmix blend and the voice level are corrected alike, and the
 * leveler's soft limit and the system equalizer's limiter come after it. A change of correction (another output, the
 * switch, « Écouter sans correction ») crossfades over [FADE_MS] instead of stepping.
 */
internal class HeadphoneEqProcessor(private val source: () -> HeadphoneProfile? = { HeadphoneEq.playing }) : BaseAudioProcessor() {
    private val eq = EqCrossfade()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..8 ||
            inputAudioFormat.sampleRate <= 0) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    // Media3 flushes on every change of speed too: the filters keep their memory unless the format changed.
    override fun onFlush() = eq.flush(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        // Media3's shared empty buffer is also what an empty output is: nothing to copy (see ReverbAudioProcessor).
        if (size == 0) return
        val rate = inputAudioFormat.sampleRate
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        eq.follow(source(), rate, channels)
        val output = replaceOutputBuffer(size)
        if (eq.idle) { output.put(inputBuffer); output.flip(); return }
        var channel = 0
        while (inputBuffer.remaining() >= 2) {
            val x = inputBuffer.short / 32768.0
            val y = eq.sample(x, channel)
            output.putShort((y * 32768.0).toInt().coerceIn(-32768, 32767).toShort())
            channel++
            if (channel == channels) { channel = 0; eq.frameDone() }
        }
        eq.settle()
        output.flip()
    }

    override fun onReset() = eq.reset()

    companion object { const val FADE_MS = EqCrossfade.FADE_MS }
}
