package com.ketvoice.app.stems

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos

/**
 * How loud the voice is in the title being heard (6.0.0, like Apple Music Sing): 1 the record as it is,
 * 0 its instrumental. It works on titles separated whole (downloaded titles, see [StemLibrary]).
 */
object VoiceLevel {
    /** Set by the player's slider; read on the audio thread. */
    @Volatile var level: Float = 1f
        set(value) { field = value.coerceIn(0f, 1f) }

    /** Whether the title [trackId] can have its voice turned down. */
    fun available(trackId: String?): Boolean = trackId != null && StemStore.hasWhole(trackId)
}

/**
 * Turns the voice of a deck's sound down in real time, from the stored separation of its whole title:
 * a short-time Fourier transform at the deck's own rate (4096 points, a hop of 1024), each cell kept as
 * `instrumental + level × voice`, and the sound rebuilt by overlap-add. It delays the sound by 4096
 * frames (under 0.1 s) for as long as the title plays, the same at every level, so that moving the
 * slider never makes the sound jump.
 */
internal class VoiceStage(val rate: Int, private val channels: Int) {
    private val n = StemDsp.N_FFT
    private val hop = StemDsp.HOP
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / n)).toFloat() }
    private val fft = Fft(n)
    /** The last n samples taken in, in a ring; [inAt] is where the next one goes. */
    private val inL = FloatArray(n); private val inR = FloatArray(n)
    private var inAt = 0
    /** Overlap-add of the frames, in a ring of n: [accAt] is the start of the oldest, incomplete hop. */
    private val accL = FloatArray(n); private val accR = FloatArray(n)
    private var accAt = 0
    /** Finished sound waiting to be played, in a ring; it starts one hop ahead (the stage's delay is n). */
    private val outL = FloatArray(2 * n); private val outR = FloatArray(2 * n)
    private var outRead = 0
    private var outWrite = hop
    private val re = FloatArray(n); private val im = FloatArray(n)
    private val gains = FloatArray(n / 2 + 1)
    private var filled = 0
    /** Position in the title of the newest sample taken in. */
    private var newestUs = 0L

    fun reset() {
        inL.fill(0f); inR.fill(0f); accL.fill(0f); accR.fill(0f); outL.fill(0f); outR.fill(0f)
        inAt = 0; accAt = 0; filled = 0; outRead = 0; outWrite = hop
    }

    /** Processes [frames] interleaved 16-bit stereo frames of [buffer] in place, from byte [offset]; the first is at [startUs]. */
    fun process(buffer: ByteBuffer, offset: Int, frames: Int, startUs: Long, mask: StemMask, level: Float) {
        if (channels != 2) return
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val size = outL.size
        for (i in 0 until frames) {
            val at = offset + i * 4
            inL[inAt] = b.getShort(at) / 32768f; inR[inAt] = b.getShort(at + 2) / 32768f
            inAt = (inAt + 1) % n
            newestUs = startUs + i * 1_000_000L / rate
            if (++filled == hop) { filled = 0; frame(mask, level) }
            b.putShort(at, (outL[outRead] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
            b.putShort(at + 2, (outR[outRead] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
            outL[outRead] = 0f; outR[outRead] = 0f
            outRead = (outRead + 1) % size
        }
    }

    private fun frame(mask: StemMask, level: Float) {
        // Centre of the window in the title, and the separation's frame there (its grid is 44.1 kHz, hop 1024).
        val centreUs = newestUs - (n / 2) * 1_000_000L / rate
        val f = Math.round(((centreUs - mask.startUs) * StemDsp.RATE / 1_000_000.0 - n / 2) / hop).toInt()
        val inside = f in 0 until mask.frames
        for (k in gains.indices) {
            val k44 = Math.round(k.toDouble() * rate / StemDsp.RATE).toInt()
            gains[k] = if (!inside || k44 >= StemDsp.NET_BINS) 1f else {
                val kept = mask.value(f, k44)
                kept + level * (1f - kept)
            }
        }
        // Oldest sample first: the ring starts at the next write position.
        for (j in 0 until n) { val s = (inAt + j) % n; re[j] = inL[s] * window[j]; im[j] = inR[s] * window[j] }
        fft.transform(re, im)
        // Both channels share one transform (left real, right imaginary); a real gain per bin keeps them apart.
        re[0] *= gains[0]; im[0] *= gains[0]
        re[n / 2] *= gains[n / 2]; im[n / 2] *= gains[n / 2]
        for (k in 1 until n / 2) {
            val g = gains[k]
            re[k] *= g; im[k] *= g; re[n - k] *= g; im[n - k] *= g
        }
        fft.transform(re, im, inverse = true)
        val scale = 1f / (n * OverlapAdd.FULL_OVERLAP)
        for (j in 0 until n) {
            val s = (accAt + j) % n
            accL[s] += re[j] * window[j] * scale; accR[s] += im[j] * window[j] * scale
        }
        // The oldest hop of the accumulator is complete: it is the next sound to play.
        val size = outL.size
        for (j in 0 until hop) {
            val s = (accAt + j) % n; val o = (outWrite + j) % size
            outL[o] = accL[s]; outR[o] = accR[s]; accL[s] = 0f; accR[s] = 0f
        }
        accAt = (accAt + hop) % n
        outWrite = (outWrite + hop) % size
    }
}
