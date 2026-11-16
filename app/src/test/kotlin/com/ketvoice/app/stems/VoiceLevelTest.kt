package com.ketvoice.app.stems

import com.ketvoice.app.automix.DeckControl
import com.ketvoice.app.automix.DeckMixProcessor
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class VoiceLevelTest {
    private fun pcm(frames: Int, rate: Int, f: (Int) -> Pair<Float, Float>): ByteBuffer {
        val b = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) { val (l, r) = f(i); b.putShort((l * 32767).toInt().toShort()); b.putShort((r * 32767).toInt().toShort()) }
        b.flip(); return b
    }
    private fun shorts(b: ByteBuffer): ShortArray { val d = b.duplicate().order(ByteOrder.LITTLE_ENDIAN); return ShortArray(d.remaining() / 2) { d.short } }

    /** A separation that holds [kept] in every cell, for [seconds] of a title. */
    private fun mask(kept: Float, seconds: Int): StemMask {
        val frames = StemDsp.frames(StemDsp.RATE * seconds)
        val q = StemMask.quantize(kept)
        return StemMask(0L, frames, ByteArray(frames * StemDsp.NET_BINS / 2) { (q or (q shl 4)).toByte() })
    }

    private fun run(rate: Int, kept: Float, level: Float): Pair<ShortArray, ShortArray> {
        val frames = rate * 4
        val input = pcm(frames, rate) { i -> (.5f * sin(2 * PI * 440 * i / rate).toFloat()) to (.3f * sin(2 * PI * 660 * i / rate).toFloat()) }
        val original = shorts(input)
        val stage = VoiceStage(rate, 2)
        val buffer = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN).put(input.duplicate()).also { it.flip() }
        // In buffers of 480 frames, as a deck would hand them over.
        var at = 0
        while (at < frames) {
            val count = minOf(480, frames - at)
            stage.process(buffer, at * 4, count, at * 1_000_000L / rate, mask(kept, 6), level)
            at += count
        }
        return original to shorts(buffer)
    }

    @Test fun allMusicGivesTheSoundBackDelayed() {
        for (rate in intArrayOf(44_100, 48_000)) {
            val (input, output) = run(rate, kept = 1f, level = 0f)
            val delay = StemDsp.N_FFT
            var worst = 0
            for (i in rate until rate * 3) for (c in 0..1) worst = maxOf(worst, abs(output[2 * (i + delay) + c] - input[2 * i + c]))
            assertTrue("rate $rate worst $worst", worst <= 3)
        }
    }

    @Test fun theLevelScalesWhatIsVoice() {
        // Every cell is voice: level 0 leaves nothing, level 1 everything, a half leaves half.
        val (input, silent) = run(48_000, kept = 0f, level = 0f)
        val (_, whole) = run(48_000, kept = 0f, level = 1f)
        val (_, half) = run(48_000, kept = 0f, level = .5f)
        val delay = StemDsp.N_FFT
        var peakSilent = 0; var worstWhole = 0; var worstHalf = 0f
        for (i in 48_000 until 48_000 * 3) {
            peakSilent = maxOf(peakSilent, abs(silent[2 * (i + delay)].toInt()))
            worstWhole = maxOf(worstWhole, abs(whole[2 * (i + delay)] - input[2 * i]))
            worstHalf = maxOf(worstHalf, abs(half[2 * (i + delay)] - input[2 * i] * .5f))
        }
        assertTrue("left over $peakSilent", peakSilent < 40)
        assertTrue(worstWhole <= 3)
        assertTrue("half $worstHalf", worstHalf < 40)
    }

    /** A deck takes the separation up only where its sound starts: never in the middle, which would make it jump. */
    @Test fun deckTakesTheSeparationAtTheStartOfItsSound() {
        val control = DeckControl()
        val processor = DeckMixProcessor(control)
        val rate = 48_000
        processor.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        var at = 0L
        fun feed(frames: Int, announce: Boolean = false): ShortArray {
            if (announce) { control.pendingTimeUs = control.offsetUs; at = 0L }
            at += frames
            processor.queueInput(pcm(frames, rate) { i -> (.4f * sin(2 * PI * 300 * i / rate).toFloat()).let { it to it } })
            return shorts(processor.output)
        }
        VoiceLevel.level = 0f
        try {
            // No separation when the sound starts: it plays untouched, even once one arrives.
            val first = feed(4_800, announce = true)
            assertTrue(first.any { it.toInt() != 0 })
            control.voiceMask = mask(0f, 6)
            control.pendingTimeUs = C.TIME_UNSET
            processor.queueInput(pcm(4_800, rate) { i -> (.4f * sin(2 * PI * 300 * (i + 4_800) / rate).toFloat()).let { it to it } })
            assertTrue(shorts(processor.output).any { abs(it.toInt()) > 1_000 })
            // From the next start (a seek, a new title), the voice is turned down.
            processor.flush()
            feed(48_000, announce = true)
            val after = feed(48_000)
            assertTrue("voice still there: ${after.maxOf { abs(it.toInt()) }}", after.maxOf { abs(it.toInt()) } < 60)
        } finally { VoiceLevel.level = 1f }
    }

    /** On a real song (local only), the live stage at level 0 gives what the offline separation gives, 4096 frames later. */
    @Test fun liveMatchesOfflineOnASong() {
        val dir = System.getenv("KETVOICE_STEM_REF")?.let(::File)
        assumeTrue(dir != null && File(dir, "mix.f32").isFile)
        fun floats(name: String): FloatArray {
            val b = ByteBuffer.wrap(File(dir, name).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(b.remaining()).also { b.get(it) }
        }
        val mix = floats("mix.f32"); val n = mix.size / 2
        val left = FloatArray(n) { mix[2 * it] }; val right = FloatArray(n) { mix[2 * it + 1] }
        val inputs = floats("input.f32"); val vocals = floats("vocals.f32"); val accompaniment = floats("accompaniment.f32")
        val block = 2 * StemDsp.NET_FRAMES * StemDsp.NET_BINS
        var split = 0
        val replay = object : StemNet {
            override fun run(input: FloatArray, vocals_: FloatArray, accompaniment_: FloatArray) {
                vocals.copyInto(vocals_, 0, split * block, (split + 1) * block); accompaniment.copyInto(accompaniment_, 0, split * block, (split + 1) * block); split++
            }
        }
        assertTrue(inputs.isNotEmpty())
        val mask = Separator.mask(left, right, 0L, replay)!!
        val offline = Separator.instrumental(left, right, mask, 0)
        val buffer = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) { buffer.putShort((left[i] * 32767).toInt().coerceIn(-32768, 32767).toShort()); buffer.putShort((right[i] * 32767).toInt().coerceIn(-32768, 32767).toShort()) }
        buffer.flip()
        val stage = VoiceStage(44_100, 2)
        var at = 0
        val started = System.nanoTime()
        while (at < n) { val c = minOf(1_024, n - at); stage.process(buffer, at * 4, c, at * 1_000_000L / 44_100, mask, 0f); at += c }
        val ms = (System.nanoTime() - started) / 1_000_000
        val live = shorts(buffer)
        var err = 0.0; var ref = 0.0
        for (i in maxOf(offline.from, 44_100) until minOf(offline.until, n - 8_192)) {
            val expected = offline.left[i] * 32767.0; val got = live[2 * (i + StemDsp.N_FFT)].toDouble()
            err += (got - expected) * (got - expected); ref += expected * expected
        }
        val snr = 10 * kotlin.math.log10(ref / err)
        println("live voice level 0 vs offline instrumental: %.1f dB; %d ms for %d s of sound".format(snr, ms, n / 44_100))
        assertTrue(snr > 30)
    }
}
