package com.ketvoice.app.stems

import kotlin.math.sqrt

/**
 * The separation network: Deezer's Spleeter (2 stems), as two models that each estimate the
 * magnitude of one stem from the magnitude of the mix.
 */
internal interface StemNet : AutoCloseable {
    /**
     * [input] holds `2 × [StemDsp.NET_FRAMES] × [StemDsp.NET_BINS]` magnitudes (channel, frame,
     * bin). Writes the estimated vocal and accompaniment magnitudes, in the same layout.
     */
    fun run(input: FloatArray, vocals: FloatArray, accompaniment: FloatArray)
    override fun close() {}
}

/**
 * Which part of each time–frequency cell of a title belongs to its instrumental: one value per
 * frame and bin below [StemDsp.NET_BINS], shared by both channels, stored on 4 bits with a square-root
 * law (fine steps near 0, where a voice is taken out). Above the network's bins everything is
 * instrumental. Its first frame starts at [startUs] in the title, on the 44.1 kHz grid.
 */
internal class StemMask(val startUs: Long, val frames: Int, val packed: ByteArray,
                        /** Per frame, how much of the voice band is voice (0..255), and how loud that voice is (0..255 for -96..0 dB). Null in separations of 5.10.0. */
                        val voiceShare: ByteArray? = null, val voiceLevel: ByteArray? = null) {
    init {
        require(packed.size == frames * StemDsp.NET_BINS / 2)
        require(voiceShare == null || (voiceShare.size == frames && voiceLevel?.size == frames))
    }

    fun value(frame: Int, bin: Int): Float {
        val b = packed[(frame * StemDsp.NET_BINS + bin) shr 1].toInt()
        val q = if (bin and 1 == 0) b and 15 else (b shr 4) and 15
        return LEVELS[q]
    }

    /** Position in the title of the start of [frame], in milliseconds. */
    fun timeMs(frame: Int): Long = (startUs + frame.toLong() * StemDsp.HOP * 1_000_000L / StemDsp.RATE) / 1000L

    val endMs: Long get() = timeMs(frames) + StemDsp.N_FFT * 1000L / StemDsp.RATE

    companion object {
        private val LEVELS = FloatArray(16) { val r = it / 15f; r * r }

        fun quantize(value: Float): Int = (sqrt(value.coerceIn(0f, 1f)) * 15f + .5f).toInt().coerceIn(0, 15)
    }
}

/**
 * Where somebody sings in a separated part of a title (5.11.0), read from what the network found:
 * a title needs no lyrics for Ketmix to know where its voice starts and stops.
 */
internal object VoiceActivity {
    /** A voice is there when it makes this share of the voice band... */
    private const val MIN_SHARE = .30f
    /** ...and is no more than this far under the loud moments of the part. */
    private const val BELOW_LOUD_DB = 26f
    /** Shorter bursts are not singing (a leak of a snare, a shout). */
    private const val MIN_RUN_MS = 450L
    /** Breaths and short rests between words do not end a sung passage. */
    private const val BRIDGE_MS = 900L

    private fun framesOf(ms: Long) = (ms * StemDsp.RATE / 1000L / StemDsp.HOP).toInt().coerceAtLeast(1)

    /** For each frame of [mask], whether a voice is heard; null for a separation that did not record it. */
    fun active(mask: StemMask): BooleanArray? {
        val share = mask.voiceShare ?: return null
        val level = mask.voiceLevel ?: return null
        val n = mask.frames
        // The loud moments of the voice band, voice or not: the 90th percentile of what was recorded with the voice.
        val sorted = IntArray(n) { level[it].toInt() and 255 }.also { it.sort() }
        val loud = sorted[(n * 9 / 10).coerceAtMost(n - 1)]
        val floor = loud - (BELOW_LOUD_DB * 255f / 96f).toInt()
        val raw = BooleanArray(n) { (share[it].toInt() and 255) >= MIN_SHARE * 255f && (level[it].toInt() and 255) >= floor && (level[it].toInt() and 255) > 40 }
        // Bridge the short rests, then drop what is too short to be sung.
        val bridge = framesOf(BRIDGE_MS); val minRun = framesOf(MIN_RUN_MS)
        val out = raw.copyOf()
        var last = -1
        for (i in 0 until n) if (raw[i]) { if (last >= 0 && i - last - 1 in 1..bridge) for (j in last + 1 until i) out[j] = true; last = i }
        var i = 0
        while (i < n) {
            if (!out[i]) { i++; continue }
            var j = i
            while (j < n && out[j]) j++
            if (j - i < minRun) for (k in i until j) out[k] = false
            i = j
        }
        return out
    }

    /** The sung passages of the part, as start and end in the title (ms). */
    fun passages(mask: StemMask): List<LongRange>? {
        val on = active(mask) ?: return null
        val out = ArrayList<LongRange>()
        var i = 0
        while (i < on.size) {
            if (!on[i]) { i++; continue }
            var j = i
            while (j < on.size && on[j]) j++
            out += mask.timeMs(i)..mask.timeMs(j)
            i = j
        }
        return out
    }

    /** Where the voice first sings in an intro; the end of the part when nobody sings in it. */
    fun startMs(mask: StemMask): Long? = passages(mask)?.let { it.firstOrNull()?.first ?: mask.endMs }

    /** Where the voice last stops in an ending; the start of the part when nobody sings in it. */
    fun endMs(mask: StemMask): Long? = passages(mask)?.let { it.lastOrNull()?.last ?: mask.timeMs(0) }
}

internal object Separator {
    private const val EPS = 1e-10f
    /** The bins of the voice band (about 200 Hz to 4 kHz), where singing is looked for. */
    private const val VOICE_FROM = 19
    private const val VOICE_UNTIL = 372

    /**
     * Runs the network over a stereo signal at 44.1 kHz and returns where its instrumental is.
     * [startUs] is the position in the title of the first sample. [cancelled] is checked between
     * blocks of the network.
     */
    fun mask(left: FloatArray, right: FloatArray, startUs: Long, net: StemNet, cancelled: () -> Boolean = { false }): StemMask? {
        val frames = StemDsp.frames(left.size)
        if (frames <= 0) return null
        val packed = ByteArray(frames * StemDsp.NET_BINS / 2)
        val voiceShare = ByteArray(frames); val voiceLevel = ByteArray(frames)
        val stft = StereoStft()
        val lr = FloatArray(StemDsp.BINS); val li = FloatArray(StemDsp.BINS)
        val rr = FloatArray(StemDsp.BINS); val ri = FloatArray(StemDsp.BINS)
        val block = StemDsp.NET_FRAMES * StemDsp.NET_BINS
        val input = FloatArray(2 * block)
        val vocals = FloatArray(2 * block)
        val accompaniment = FloatArray(2 * block)
        var first = 0
        while (first < frames) {
            if (cancelled()) return null
            input.fill(0f)
            val count = minOf(StemDsp.NET_FRAMES, frames - first)
            for (f in 0 until count) {
                stft.forward(left, right, (first + f) * StemDsp.HOP, lr, li, rr, ri)
                val row = f * StemDsp.NET_BINS
                for (k in 0 until StemDsp.NET_BINS) {
                    input[row + k] = sqrt(lr[k] * lr[k] + li[k] * li[k])
                    input[block + row + k] = sqrt(rr[k] * rr[k] + ri[k] * ri[k])
                }
            }
            net.run(input, vocals, accompaniment)
            for (f in 0 until count) {
                val row = f * StemDsp.NET_BINS
                val frame = first + f
                var voiceEnergy = 0.0; var bandEnergy = 0.0
                for (k in 0 until StemDsp.NET_BINS) {
                    var share = 0f
                    for (c in 0..1) {
                        val v = vocals[c * block + row + k]; val a = accompaniment[c * block + row + k]
                        val kept = (a * a + EPS / 2) / (v * v + a * a + EPS)
                        share += kept
                        if (k in VOICE_FROM..VOICE_UNTIL) {
                            val x = input[c * block + row + k]
                            bandEnergy += x * x; voiceEnergy += (1f - kept) * (1f - kept) * x * x
                        }
                    }
                    val q = StemMask.quantize(share * .5f)
                    val at = (frame * StemDsp.NET_BINS + k) shr 1
                    packed[at] = (if (k and 1 == 0) (packed[at].toInt() and 0xF0) or q else (packed[at].toInt() and 0x0F) or (q shl 4)).toByte()
                }
                voiceShare[frame] = (sqrt(voiceEnergy / (bandEnergy + 1e-9)) * 255.0).toInt().coerceIn(0, 255).toByte()
                // Energy of a full-scale tone in the band is about (N/4)^2 per bin; -96..0 dB on 0..255.
                val db = 10.0 * kotlin.math.log10(voiceEnergy / (StemDsp.N_FFT.toDouble() * StemDsp.N_FFT) + 1e-12)
                voiceLevel[frame] = ((db + 96.0) * 255.0 / 96.0).toInt().coerceIn(0, 255).toByte()
            }
            first += count
        }
        return StemMask(startUs, frames, packed, voiceShare, voiceLevel)
    }

    /**
     * The instrumental of [left]/[right] (44.1 kHz) by [mask]. [offset] is where the mask's first
     * frame starts in these samples (negative when the signal starts after it). Samples the mask
     * does not cover are silent, outside [Result.from]..[Result.until].
     */
    fun instrumental(left: FloatArray, right: FloatArray, mask: StemMask, offset: Int): Result = stem(left, right, mask, offset, vocals = false)

    /** As [instrumental]; with [vocals] the voice alone instead (nothing above the network's bins). */
    fun stem(left: FloatArray, right: FloatArray, mask: StemMask, offset: Int, vocals: Boolean): Result {
        val outL = FloatArray(left.size); val outR = FloatArray(right.size)
        val stft = StereoStft()
        val lr = FloatArray(StemDsp.BINS); val li = FloatArray(StemDsp.BINS)
        val rr = FloatArray(StemDsp.BINS); val ri = FloatArray(StemDsp.BINS)
        var used = 0
        var firstFrame = -1
        for (t in 0 until mask.frames) {
            val at = offset + t * StemDsp.HOP
            if (at + StemDsp.N_FFT <= 0 || at >= left.size) continue
            if (firstFrame < 0) firstFrame = t
            stft.forward(left, right, at, lr, li, rr, ri)
            for (k in 0 until StemDsp.NET_BINS) {
                val kept = mask.value(t, k)
                val m = if (vocals) 1f - kept else kept
                lr[k] *= m; li[k] *= m; rr[k] *= m; ri[k] *= m
            }
            if (vocals) for (k in StemDsp.NET_BINS until StemDsp.BINS) { lr[k] = 0f; li[k] = 0f; rr[k] = 0f; ri[k] = 0f }
            stft.inverseAdd(lr, li, rr, ri, outL, outR, at)
            used = t - firstFrame + 1
        }
        if (firstFrame < 0) return Result(outL, outR, 0, 0)
        val firstAt = offset + firstFrame * StemDsp.HOP
        // Near the first and last frames the windows only partly overlap: the sound there is not trusted
        // and left silent. In between, the squared windows always add up to the same value.
        val from = (firstAt + StemDsp.N_FFT).coerceIn(0, left.size)
        val until = (firstAt + (used - 1) * StemDsp.HOP).coerceIn(from, left.size)
        val scale = 1f / OverlapAdd.FULL_OVERLAP
        for (i in left.indices) {
            if (i in from until until) { outL[i] *= scale; outR[i] *= scale } else { outL[i] = 0f; outR[i] = 0f }
        }
        return Result(outL, outR, from, until)
    }

    /** An instrumental, trustworthy from sample [from] (inclusive) to [until] (exclusive). */
    class Result(val left: FloatArray, val right: FloatArray, val from: Int, val until: Int)
}
