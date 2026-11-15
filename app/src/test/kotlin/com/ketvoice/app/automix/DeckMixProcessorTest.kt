@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.automix

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The processor as Media3 drives it: configure, flush, then buffers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeckMixProcessorTest {
    private val offset = DeckControl.PLAYER_CLOCK_OFFSET_US
    private fun pcm(frames: Int, channels: Int = 2, value: Short = 10_000): ByteBuffer =
        ByteBuffer.allocateDirect(frames * 2 * channels).order(ByteOrder.nativeOrder()).apply {
            repeat(frames * channels) { putShort(value) }; flip()
        }
    private fun started(control: DeckControl, rate: Int = 48_000, channels: Int = 2): DeckMixProcessor =
        DeckMixProcessor(control).apply {
            configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT)); flush()
        }
    private fun out(processor: DeckMixProcessor, input: ByteBuffer): ShortArray {
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        return ShortArray(output.remaining() / 2) { output.short }
    }

    @Test fun withoutATransitionTheSoundIsPassedAsItCame() {
        val control = DeckControl()
        val processor = started(control)
        assertTrue(processor.isActive); assertTrue(control.active)
        control.pendingTimeUs = offset + 5_000_000
        val input = pcm(480)
        val samples = out(processor, input)
        assertEquals(960, samples.size); assertTrue(samples.all { it.toInt() == 10_000 })
        assertFalse("All of the input is consumed", input.hasRemaining())
    }

    @Test fun anEchoRingsOnPastTheLastSampleOfTheTitle() {
        val control = DeckControl()
        val processor = started(control, 48_000, 2)
        // A last note at full level, cut at 10 s, its echo ringing for two seconds.
        val plan = MixPlan(MixKind.Echo, outMs = 9_900, inMs = 0, overlapMs = 2_100, beatMs = 500f, cutMs = 10_000, tail = true)
        control.automation = DeckAutomation.outgoing(plan)
        control.pendingTimeUs = offset + 9_500_000
        val last = out(processor, pcm(24_000))                 // 9.5–10.0 s: the note, heard whole
        assertTrue("the note itself is untouched", last.take(9_600).all { it.toInt() == 10_000 })
        processor.queueEndOfStream()
        val tail = processor.output.order(ByteOrder.nativeOrder())
        val samples = ShortArray(tail.remaining() / 2) { tail.short }
        assertEquals("played out to the end of the echo", 192_000.0, samples.size.toDouble(), 1_920.0)
        assertTrue("its repeats are heard", samples.take(48_000).any { abs(it.toInt()) > 1_000 })
        assertTrue("and fade away", samples.takeLast(4_800).all { abs(it.toInt()) < 200 })
        assertTrue(processor.isEnded || processor.output.remaining() == 0)
        // Without an echo, an end is an end.
        val plain = started(DeckControl(), 48_000, 2)
        out(plain, pcm(480))
        plain.queueEndOfStream()
        assertEquals(0, plain.output.remaining())
    }

    @Test fun aChangeOfSpeedDoesNotStartTheFiltersAfresh() {
        // Media3 flushes the processors at each change of speed. A deck under a high-pass must sound the same with or without it.
        fun run(flushed: Boolean): ShortArray {
            val control = DeckControl()
            val processor = started(control, 48_000, 1)
            control.automation = DeckAutomation(Curve.flat(1f), Curve.flat(900f))
            control.pendingTimeUs = offset + 5_000_000
            val first = out(processor, pcm(4_800, 1))
            if (flushed) { processor.flush(); control.pendingTimeUs = offset + 5_100_000 }
            return first + out(processor, pcm(4_800, 1))
        }
        val plain = run(false); val flushed = run(true)
        assertEquals(plain.size, flushed.size)
        // A constant level through a high-pass dies away; started afresh it would stay silent too, so the check is on the echo below.
        assertTrue(plain.indices.all { abs(plain[it] - flushed[it]) <= 1 })
        // An echo keeps ringing through the flush; a jump elsewhere in the title silences it.
        fun tail(jumpUs: Long): Int {
            val control = DeckControl()
            val processor = started(control, 48_000, 2)
            control.automation = DeckAutomation(Curve.flat(1f), Curve.flat(0f), dry = Curve.flat(0f), echo = Curve(longArrayOf(5_090_000, 5_100_000), floatArrayOf(1f, 0f)),
                echoDelayUs = 250_000, echoFeedback = .6f)
            control.pendingTimeUs = offset + 5_000_000
            out(processor, pcm(4_800))                         // 5.0–5.1 s feeds the echo
            processor.flush(); control.pendingTimeUs = offset + 5_100_000 + jumpUs
            return out(processor, pcm(24_000)).maxOf { abs(it.toInt()) }
        }
        assertTrue("the repeats are still heard after a change of speed", tail(0) > 3_000)
        assertEquals("but not after a seek", 0, tail(30_000_000))
    }

    @Test fun aFormatItCannotProcessLeavesItOutOfThePath() {
        val control = DeckControl()
        val processor = DeckMixProcessor(control)
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT)))
        // Media3 still flushes it: that must be harmless.
        processor.flush()
        assertFalse(processor.isActive); assertFalse(control.active)
    }

    @Test fun levelsFollowPositionsInTheTitle() {
        val control = DeckControl()
        val processor = started(control, 48_000, 1)
        // Silent before 10 s of the title, full level after 10.1 s.
        control.automation = DeckAutomation(Curve(longArrayOf(10_000_000, 10_100_000), floatArrayOf(0f, 1f)), Curve.flat(0f))
        control.pendingTimeUs = offset + 9_990_000
        val first = out(processor, pcm(480, 1))            // 9.99–10.00 s
        assertTrue(first.all { it.toInt() == 0 })
        // The next announcement is "one buffer ahead" of the sound, as under back-pressure: frames are trusted instead.
        control.pendingTimeUs = offset + 10_010_000
        val second = out(processor, pcm(4_800, 1))          // really 10.00–10.10 s
        assertTrue(second.first() < 60); assertEquals(10_000.0, second.last().toDouble(), 60.0)
        assertEquals(5_000.0, second[2_400].toDouble(), 120.0)
        assertTrue(out(processor, pcm(480, 1)).all { it.toInt() == 10_000 })
        // A real jump (a seek the processor was not flushed for) is followed.
        control.pendingTimeUs = offset + 2_000_000
        // (the level glides there over a millisecond rather than jumping)
        assertTrue(out(processor, pcm(480, 1)).drop(64).all { it.toInt() == 0 })
        // After a flush the first announcement is exact again.
        processor.flush()
        control.pendingTimeUs = offset + 20_000_000
        assertTrue(out(processor, pcm(480, 1)).all { it.toInt() == 10_000 })
    }

    @Test fun theTapHearsTheSoundBeforeAnyLevelAndWithItsPosition() {
        val control = DeckControl()
        val processor = started(control)
        val positions = ArrayList<Long>(); var heard = 0; var loud = true
        control.tap = object : PcmTap {
            override fun pcm(buffer: ByteBuffer, positionUs: Long, sampleRate: Int, channels: Int) {
                positions += positionUs; heard += buffer.remaining()
                assertEquals(48_000, sampleRate); assertEquals(2, channels)
                val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                while (view.remaining() >= 2) if (view.short.toInt() != 10_000) loud = false
            }
        }
        control.automation = DeckAutomation.MUTE
        control.pendingTimeUs = offset + 1_000_000
        val a = pcm(480); val b = pcm(960)
        assertTrue(out(processor, a).all { it.toInt() == 0 })
        assertTrue(out(processor, b).all { it.toInt() == 0 })
        assertEquals(listOf(1_000_000L, 1_010_000L), positions)
        assertEquals((480 + 960) * 4, heard)
        assertTrue("The tap gets the sound itself, not the muted output", loud)
        // A tap that fails never breaks playback.
        control.tap = object : PcmTap { override fun pcm(buffer: ByteBuffer, positionUs: Long, sampleRate: Int, channels: Int) = error("boom") }
        assertEquals(960, out(processor, pcm(480)).size)
    }

    @Test fun anAnalysisReadThroughTheProcessorFindsTheTempo() {
        val control = DeckControl()
        val processor = started(control, 44_100, 2)
        val tap = AnalysisTap(155_000_000, 185_000_000)
        control.tap = tap; control.automation = DeckAutomation.MUTE
        val pcm = Synth.pcm16(Synth.groove(44_100, 31f, 122f, .2f), 2)
        control.pendingTimeUs = offset + 155_000_000
        var position = 0
        while (position < pcm.limit() && !tap.done) {
            val size = minOf(4_096 * 4, pcm.limit() - position)
            val piece = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            val source = pcm.duplicate(); source.position(position); source.limit(position + size)
            piece.put(source); piece.flip()
            processor.queueInput(piece); processor.output
            position += size
        }
        assertTrue(tap.done)
        val analysis = tap.result()!!
        assertEquals(155_000L, analysis.startMs)
        assertEquals(1f, BeatAnalysis.foldedRatio(analysis.bpm, 122f), .004f)
        assertTrue("Beats are placed in the title (${BeatAnalysis.nearestBeatError(analysis, 155_200.0)} ms)",
            BeatAnalysis.nearestBeatError(analysis, 155_200.0) < 14.0)
        assertNull("Too little continuous sound is not an analysis", AnalysisTap(0, 60_000_000).apply {
            pcm(Synth.pcm16(Synth.groove(44_100, 2f, 120f), 2), 0, 44_100, 2) }.result())
    }

    @Test fun analysesSurviveStorage() {
        val analysis = SegmentAnalysis(155_000, 45_000, 123.4f, 155_210.5, .82f, 2, -13.5f, 155_000, 199_100, 155_000, 187_000, true, false)
        assertEquals(analysis, AnalysisStore.decode(AnalysisStore.encode(analysis)))
        assertNull(AnalysisStore.decode(org.json.JSONObject("{}")))
        assertNull(AnalysisStore.decode(org.json.JSONObject("""{"s":0,"l":40000,"b":9000}""")))
        val clamped = AnalysisStore.decode(org.json.JSONObject("""{"s":1000,"l":40000,"b":120,"c":7,"r":9,"ss":0,"se":99999999}"""))!!
        assertEquals(1f, clamped.confidence, 0f); assertEquals(3, clamped.barOffset)
        assertEquals(1_000L, clamped.soundStartMs); assertEquals(41_000L, clamped.soundEndMs)
    }
}
