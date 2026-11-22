package com.ketvoice.app.player

import kotlin.math.abs
import kotlin.math.pow

/**
 * A headphone correction (9.0) as a cascade of biquads, for one sample rate and channel count: the safe preamp first
 * (so no frequency is lifted above full scale), then each filter in transposed direct form II, in double precision.
 * Pure Kotlin, shared with the Windows build (its PCM path runs it on the mixed decks).
 */
internal class EqCascade(val profile: HeadphoneProfile, val sampleRate: Int, val channels: Int) {
    private val sections = profile.filters.map { Biquad.of(it, sampleRate) }.filter { it != Biquad.IDENTITY }.toTypedArray()
    val gain = 10f.pow(profile.safePreampDb(sampleRate) / 20f).toDouble()
    private val z1 = Array(sections.size) { DoubleArray(channels) }
    private val z2 = Array(sections.size) { DoubleArray(channels) }

    fun sample(input: Double, channel: Int): Double {
        var v = input * gain
        for (i in sections.indices) {
            val c = sections[i]
            val s1 = z1[i]; val s2 = z2[i]
            val y = c.b0 * v + s1[channel]
            s1[channel] = c.b1 * v - c.a1 * y + s2[channel]
            s2[channel] = c.b2 * v - c.a2 * y
            v = y
        }
        return v
    }

    /** Clears the filters' memory of vanishing values, which would otherwise slow the processor down in silence. */
    fun settle() {
        for (i in sections.indices) for (ch in 0 until channels) {
            if (abs(z1[i][ch]) < 1e-20) z1[i][ch] = 0.0
            if (abs(z2[i][ch]) < 1e-20) z2[i][ch] = 0.0
        }
    }

    fun reset() { z1.forEach { it.fill(0.0) }; z2.forEach { it.fill(0.0) } }
}

/**
 * The correction a stream plays and the change from one to another: at once while nothing was heard yet, else a
 * crossfade over [FADE_MS] between the old and the new cascade (another output, the switch, « Écouter sans
 * correction »). Shared by Android's [HeadphoneEqProcessor] and the Windows mixer.
 */
internal class EqCrossfade {
    private var cascade: EqCascade? = null
    private var leaving: EqCascade? = null
    private var fadeFrames = 0
    private var fadeDone = 0
    /** False until the stream's first sound: the correction then starts at once, with nothing to fade from. */
    private var started = false

    /** True when the sound passes through untouched (no correction and no fade). */
    val idle: Boolean get() = cascade == null && fadeFrames == 0

    /** Moves to [wanted] for a stream of [rate] and [channels], then marks the stream as heard. */
    fun follow(wanted: HeadphoneProfile?, rate: Int, channels: Int) {
        move(wanted, rate, channels)
        started = true
    }

    private fun move(wanted: HeadphoneProfile?, rate: Int, channels: Int) {
        val now = cascade
        if (wanted === now?.profile) return
        if (wanted != null && now != null && wanted == now.profile) return
        cascade = wanted?.let { EqCascade(it, rate, channels) }
        if (!started) return
        leaving = now
        fadeFrames = (rate * FADE_MS / 1000).coerceAtLeast(1); fadeDone = 0
    }

    /** One sample of [channel], through the correction (and the one it fades from). */
    fun sample(x: Double, channel: Int): Double {
        val now = cascade?.sample(x, channel) ?: x
        if (fadeFrames == 0) return now
        val before = leaving?.sample(x, channel) ?: x
        val t = fadeDone.toDouble() / fadeFrames
        return before + (now - before) * t
    }

    /** A whole frame (every channel) was played: the fade moves on. */
    fun frameDone() {
        if (fadeFrames > 0 && ++fadeDone >= fadeFrames) { fadeFrames = 0; leaving = null }
    }

    /** After a block: see [EqCascade.settle]. */
    fun settle() { cascade?.settle(); leaving?.settle() }

    /** A flush: the filters keep their memory unless the format changed; a fade under way is dropped. */
    fun flush(rate: Int, channels: Int) {
        if (cascade?.let { it.sampleRate != rate || it.channels != channels } == true) { cascade = null; started = false }
        leaving = null; fadeFrames = 0
    }

    fun reset() { cascade = null; leaving = null; fadeFrames = 0; started = false }

    companion object { const val FADE_MS = 60 }
}
