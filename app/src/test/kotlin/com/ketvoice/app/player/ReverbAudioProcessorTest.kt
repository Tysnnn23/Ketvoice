@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ReverbAudioProcessorTest {
    @Test fun diagnosticIsOnlyRaisedAfterActualPcmInput() {
        var processed = 0
        val processor = ReverbAudioProcessor { processed++ }
        processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        assertEquals(0, processed)
        processor.queueInput(ByteBuffer.allocateDirect(1920).order(ByteOrder.nativeOrder()))
        assertEquals(1, processed)
    }
    @After fun reset() { ReverbControl.amount = 0f }
    @Test fun pcmPipelinePreservesFramesAndDisabledSamplesExactly() {
        val processor = ReverbAudioProcessor()
        val format = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        assertEquals(format, processor.configure(format))
        processor.flush()
        val samples = shortArrayOf(-32768, 32767, 0, 0, 128, -128)
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { input.putShort(it) }; input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        assertEquals(samples.size * 2, output.remaining())
        samples.forEach { assertEquals(it, output.short) }
        assertFalse(input.hasRemaining())
    }
    @Test fun pcmPipelineActuallyProducesReverbAndFlushDiscardsHistory() {
        ReverbControl.amount = 1f
        val processor = ReverbAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        val input = ByteBuffer.allocateDirect(44100 * 2).order(ByteOrder.nativeOrder())
        repeat(44100) { input.putShort(if (it == 4410) 16000 else 0) }; input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        var tail = 0
        repeat(44100) { if (output.short.toInt() != 0 && it > 5000) tail++ }
        assertTrue(tail > 100)
        processor.flush()
        val silence = ByteBuffer.allocateDirect(10000).order(ByteOrder.nativeOrder())
        processor.queueInput(silence)
        val cleared = processor.output
        while (cleared.hasRemaining()) assertEquals(0, cleared.get().toInt())
    }
}
