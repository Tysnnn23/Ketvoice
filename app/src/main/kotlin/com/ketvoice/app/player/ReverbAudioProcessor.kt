package com.ketvoice.app.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/** Runs in the actual Media3 PCM path, independent of vendor audiofx support. */
@UnstableApi
internal class ReverbAudioProcessor(private val onProcessed: () -> Unit = {}) : BaseAudioProcessor() {
    private var engine: ReverbEngine? = null
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..2)
            return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }
    override fun onFlush() {
        engine = if (inputAudioFormat.channelCount in 1..2 && inputAudioFormat.sampleRate > 0)
            ReverbEngine(inputAudioFormat.sampleRate, inputAudioFormat.channelCount) else null
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        val output = replaceOutputBuffer(inputBuffer.remaining())
        val room = engine
        val amount = ReverbControl.amount
        val size = ReverbControl.size
        val damping = ReverbControl.damping
        if (room == null) output.put(inputBuffer)
        else {
            if (inputBuffer.hasRemaining()) onProcessed()
            var channel = 0
            while (inputBuffer.remaining() >= 2) {
                val value = inputBuffer.short
                val result = room.sample(value / 32768f, channel, amount, size, damping)
                output.putShort((result * 32768f).toInt().coerceIn(-32768, 32767).toShort())
                channel = (channel + 1) % inputAudioFormat.channelCount
            }
        }
        output.flip()
    }
    override fun onReset() { engine = null }
}
