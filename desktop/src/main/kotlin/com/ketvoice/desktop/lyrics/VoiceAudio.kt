package com.ketvoice.desktop.lyrics

import com.ketvoice.app.stems.OverlapAdd
import com.ketvoice.app.stems.Resampler
import com.ketvoice.app.stems.StemDsp
import com.ketvoice.app.stems.StemMask
import com.ketvoice.app.stems.StereoStft
import com.ketvoice.desktop.audio.FfmpegDecoder

/**
 * The voice of a song for the word sync by AI: the title's separation (the voice level's, or the library's studio one)
 * applied to its sound, as 16 kHz mono. Without a separation the whole sound is used (Whisper hears through music,
 * less well).
 */
internal object VoiceAudio {
    /** [pcm] (the decks' 48 kHz interleaved stereo) with only its voice kept when [mask] is given, at 16 kHz mono. */
    fun voice16k(pcm: ShortArray, mask: StemMask?): FloatArray {
        val left = Resampler.resample(pcm, 0, 2, FfmpegDecoder.OUT_RATE, StemDsp.RATE)
        val right = Resampler.resample(pcm, 1, 2, FfmpegDecoder.OUT_RATE, StemDsp.RATE)
        val mono = if (mask == null) FloatArray(left.size) { (left[it] + right[it]) * .5f } else isolate(left, right, mask)
        return Resampler.resample(mono, StemDsp.RATE, AlignMath.RATE)
    }

    /** The voice share of each cell (one minus the separation's instrumental share), rebuilt by overlap-add, mono. */
    internal fun isolate(left: FloatArray, right: FloatArray, mask: StemMask): FloatArray {
        val stft = StereoStft()
        val b = StemDsp.BINS
        val lr = FloatArray(b); val li = FloatArray(b); val rr = FloatArray(b); val ri = FloatArray(b)
        val outL = FloatArray(left.size); val outR = FloatArray(right.size)
        val shift = (mask.startUs * StemDsp.RATE / 1_000_000L).toInt()
        for (f in 0 until mask.frames) {
            val offset = shift + f * StemDsp.HOP
            if (offset >= left.size) break
            stft.forward(left, right, offset, lr, li, rr, ri)
            for (k in 0 until b) {
                // Above the separation's band nothing is voice for Whisper (it hears up to 8 kHz).
                val voice = if (k < StemDsp.NET_BINS) 1f - mask.value(f, k) else 0f
                lr[k] *= voice; li[k] *= voice; rr[k] *= voice; ri[k] *= voice
            }
            stft.inverseAdd(lr, li, rr, ri, outL, outR, offset)
        }
        val scale = .5f / OverlapAdd.FULL_OVERLAP
        return FloatArray(left.size) { (outL[it] + outR[it]) * scale }
    }
}
