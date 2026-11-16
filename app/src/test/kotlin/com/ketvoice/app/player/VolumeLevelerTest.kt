@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.ketvoice.app.data.AppSettings
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

class VolumeLevelerTest {
    @After fun reset() { AppSettings.volumeLeveling.value = false }

    private fun tone(amplitude: Float, frames: Int, rate: Int = 48_000): ByteBuffer =
        ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder()).apply {
            repeat(frames) { i -> val v = (sin(i * 2 * Math.PI * 440 / rate) * amplitude * 32767).toInt().toShort(); putShort(v); putShort(v) }
            flip()
        }

    @Test fun aQuietTitleIsRaisedAndALoudOneLoweredWithinTheLimits() {
        val quiet = VolumeLeveler(48_000); val loud = VolumeLeveler(48_000)
        repeat(600) { quiet.observe(.0005, 4_800); loud.observe(.25, 4_800) }   // a minute of each
        assertTrue("quiet raised: ${quiet.gainDb}", quiet.gainDb > 4f && quiet.gainDb <= VolumeLeveler.MAX_BOOST_DB + .01f)
        assertTrue("loud lowered: ${loud.gainDb}", loud.gainDb < -8f && loud.gainDb >= VolumeLeveler.MAX_CUT_DB - .01f)
    }

    @Test fun theGainMovesSlowlyAndSilenceChangesNothing() {
        val l = VolumeLeveler(48_000)
        l.observe(.25, 4_800)                                  // a tenth of a second of a loud title
        assertTrue("no jump: ${l.gainDb}", l.gainDb > -0.5f)
        val before = l.gainDb
        repeat(100) { l.observe(0.0, 4_800) }                   // ten seconds of silence
        assertTrue(kotlin.math.abs(l.gainDb - before) < 2f)
    }

    @Test fun softClipKeepsRaisedPeaksUnderFullScale() {
        assertEquals(.5f, VolumeLeveler.soft(.5f), 0f)
        assertTrue(VolumeLeveler.soft(1.6f) < 1f); assertTrue(VolumeLeveler.soft(-1.6f) > -1f)
    }

    @Test fun offPassesTheSoundUntouchedAndEmptyInputIsSafe() {
        val processor = LevelingAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)); processor.flush()
        processor.queueInput(AudioProcessor.EMPTY_BUFFER)
        assertFalse(processor.output.hasRemaining())
        val input = tone(.3f, 480)
        val copy = ByteArray(input.remaining()).also { input.duplicate().get(it) }
        processor.queueInput(input)
        val out = processor.output
        val got = ByteArray(out.remaining()).also { out.get(it) }
        assertArrayEquals(copy, got)
    }

    @Test fun onTheProcessorKeepsEveryFrame() {
        AppSettings.volumeLeveling.value = true
        val processor = LevelingAudioProcessor()
        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)); processor.flush()
        val input = tone(.05f, 4_800)
        val bytes = input.remaining()
        processor.queueInput(input)
        assertEquals(bytes, processor.output.remaining())
        assertFalse(input.hasRemaining())
    }
}
