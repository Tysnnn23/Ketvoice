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

    /**
     * Whether a new stream should run through the voice stage from its start (6.0.0 Beta 4): with a
     * separation model installed, any title may be separated while it plays, and its separation is then
     * taken up live, without the jump that starting the stage later would make.
     */
    fun stageWanted(hasMask: Boolean): Boolean = hasMask || StemModel.anyReady || testStage

    /** Set by tests: the stage runs as if a model were installed. */
    @Volatile internal var testStage = false

    private val _delayMs = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** How late the main deck's sound is heard because of the voice stage; the lyrics follow the sound heard. */
    val delayMs: kotlinx.coroutines.flow.StateFlow<Int> = _delayMs

    /** Set by the main deck's processor when a stream starts. */
    internal fun stageDelay(rate: Int, on: Boolean) { _delayMs.value = if (on && rate > 0) StemDsp.N_FFT * 1000 / rate else 0 }
}

/**
 * Turns the voice of a deck's sound down in real time, from the stored separation of its whole title:
 * a short-time Fourier transform at the deck's own rate (4096 points, a hop of 1024), each cell kept as
 * `instrumental + level × voice`, and the sound rebuilt by overlap-add. It delays the sound by 4096
 * frames (under 0.1 s) for as long as the stream plays, the same at every level and with or without a
 * separation, so that the slider moving or a separation arriving never makes the sound jump (6.0.0
 * Beta 4: the stage runs from the start of the stream whenever a separation model is installed, and the
 * separation is taken up live). The separation is read between its frames (linear in time), and above
 * the 11 kHz it covers, the share of voice of its highest band is carried on, as Spleeter's own mask
 * extension does, so that sibilants go with the voice.
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
    /** For each bin of this rate, the bin of the separation's 44.1 kHz grid. */
    private val bin44 = IntArray(n / 2 + 1) { k -> Math.round(k.toDouble() * rate / StemDsp.RATE).toInt() }
    private var filled = 0
    /** Position in the title of the newest sample taken in. */
    private var newestUs = 0L

    fun reset() {
        inL.fill(0f); inR.fill(0f); accL.fill(0f); accR.fill(0f); outL.fill(0f); outR.fill(0f)
        inAt = 0; accAt = 0; filled = 0; outRead = 0; outWrite = hop
    }

    /**
     * Processes [frames] interleaved 16-bit stereo frames of [buffer] in place, from byte [offset]; the
     * first is at [startUs]. Without a [mask] the sound only goes through (delayed, unchanged).
     */
    fun process(buffer: ByteBuffer, offset: Int, frames: Int, startUs: Long, mask: StemMask?, level: Float,
                keys: com.ketvoice.app.automix.KeyLevels? = null) {
        if (channels != 2) return
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val size = outL.size
        for (i in 0 until frames) {
            val at = offset + i * 4
            inL[inAt] = b.getShort(at) / 32768f; inR[inAt] = b.getShort(at + 2) / 32768f
            inAt = (inAt + 1) % n
            newestUs = startUs + i * 1_000_000L / rate
            if (++filled == hop) { filled = 0; frame(mask, level, keys) }
            b.putShort(at, (outL[outRead] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
            b.putShort(at + 2, (outR[outRead] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
            outL[outRead] = 0f; outR[outRead] = 0f
            outRead = (outRead + 1) % size
        }
    }

    /** The gains of one frame centred at [centreUs] in the title; false when every gain is 1. */
    internal fun gainsAt(centreUs: Long, mask: StemMask?, level: Float, into: FloatArray = gains, instruments: Float = 1f): Boolean {
        if (mask == null || (level >= .999f && instruments >= .999f)) return false
        // The separation's frames, 1024 samples apart at 44.1 kHz; this frame falls between two of them.
        val position = ((centreUs - mask.startUs) * StemDsp.RATE / 1_000_000.0 - n / 2) / hop
        if (position < -1.0 || position > mask.frames.toDouble()) return false
        val f0 = Math.floor(position).toInt().coerceIn(0, mask.frames - 1)
        val f1 = (f0 + 1).coerceAtMost(mask.frames - 1)
        val t = (position - Math.floor(position)).toFloat().coerceIn(0f, 1f)
        // Above the separation's band: the voice share of its top eighth carries on.
        var top0 = 0f; var top1 = 0f
        val from = StemDsp.NET_BINS - StemDsp.NET_BINS / 8
        for (k in from until StemDsp.NET_BINS) { top0 += mask.value(f0, k); top1 += mask.value(f1, k) }
        val count = (StemDsp.NET_BINS - from).toFloat()
        val upper = (top0 / count) * (1f - t) + (top1 / count) * t
        val keep = level.coerceIn(0f, 1f)
        val band = instruments.coerceIn(0f, 1f)
        for (k in into.indices) {
            val k44 = bin44[k]
            val kept = if (k44 >= StemDsp.NET_BINS) upper else mask.value(f0, k44) * (1f - t) + mask.value(f1, k44) * t
            into[k] = band * kept + keep * (1f - kept)
        }
        return true
    }

    private fun frame(mask: StemMask?, level: Float, keys: com.ketvoice.app.automix.KeyLevels?) {
        // Centre of the window in the title.
        val centreUs = newestUs - (n / 2) * 1_000_000L / rate
        // 7.6: a mix's voice and instrument keyframes, hop by hop; the overlap-add crossfades each change over the window.
        val voice = level * (keys?.voice?.at(centreUs) ?: 1f)
        val shaped = gainsAt(centreUs, mask, voice, instruments = keys?.instruments?.at(centreUs) ?: 1f)
        // Oldest sample first: the ring starts at the next write position.
        for (j in 0 until n) { val s = (inAt + j) % n; re[j] = inL[s] * window[j]; im[j] = inR[s] * window[j] }
        if (shaped) {
            fft.transform(re, im)
            // Both channels share one transform (left real, right imaginary); a real gain per bin keeps them apart.
            re[0] *= gains[0]; im[0] *= gains[0]
            re[n / 2] *= gains[n / 2]; im[n / 2] *= gains[n / 2]
            for (k in 1 until n / 2) {
                val g = gains[k]
                re[k] *= g; im[k] *= g; re[n - k] *= g; im[n - k] *= g
            }
            fft.transform(re, im, inverse = true)
        } else {
            // Unchanged: the windowed frame itself, scaled as the inverse transform would leave it.
            for (j in 0 until n) { re[j] *= n.toFloat(); im[j] *= n.toFloat() }
        }
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
