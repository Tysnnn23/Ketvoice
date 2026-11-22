package com.ketvoice.app.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The filters a headphone correction is made of (9.0), as parametric equalizers name them. */
enum class EqFilterType { Peak, LowShelf, HighShelf }

/** One filter of a correction: its kind, centre (or corner) frequency, gain and Q. */
data class EqFilter(val type: EqFilterType, val hz: Float, val gainDb: Float, val q: Float)

/**
 * Second-order filter coefficients, normalised so that a0 = 1, from Robert Bristow-Johnson's « Audio EQ Cookbook »
 * (the formulas every parametric equalizer uses; shelves take their Q directly, as Equalizer APO's LSC/HSC do).
 */
data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    /** The gain in dB of this filter at [hz] for [sampleRate]. */
    fun magnitudeDb(hz: Double, sampleRate: Int): Double {
        val w = 2 * PI * hz / sampleRate
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
        val dr = 1 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
        return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    companion object {
        val IDENTITY = Biquad(1.0, 0.0, 0.0, 0.0, 0.0)

        /** The highest frequency a filter may sit at: a little below half the sample rate, where the formulas still hold. */
        fun maxHz(sampleRate: Int): Double = sampleRate * .45

        fun of(filter: EqFilter, sampleRate: Int): Biquad {
            if (sampleRate <= 0 || filter.gainDb == 0f || !filter.hz.isFinite() || !filter.q.isFinite()) return IDENTITY
            val f0 = filter.hz.toDouble().coerceIn(1.0, maxHz(sampleRate))
            val q = filter.q.toDouble().coerceIn(.05, 30.0)
            val a = 10.0.pow(filter.gainDb / 40.0)
            val w0 = 2 * PI * f0 / sampleRate
            val cw = cos(w0)
            val alpha = sin(w0) / (2 * q)
            return when (filter.type) {
                EqFilterType.Peak -> normalise(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
                EqFilterType.LowShelf -> lowShelf(a, cw, 2 * sqrt(a) * alpha)
                EqFilterType.HighShelf -> highShelf(a, cw, 2 * sqrt(a) * alpha)
            }
        }

        private fun lowShelf(a: Double, cw: Double, k: Double) = normalise(
            a * ((a + 1) - (a - 1) * cw + k), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - k),
            (a + 1) + (a - 1) * cw + k, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - k)

        private fun highShelf(a: Double, cw: Double, k: Double) = normalise(
            a * ((a + 1) + (a - 1) * cw + k), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - k),
            (a + 1) - (a - 1) * cw + k, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - k)

        private fun normalise(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }
}
