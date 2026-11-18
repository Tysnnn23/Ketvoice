package com.ketvoice.app.automix

import kotlin.math.PI
import kotlin.math.cos

/** How the two levels cross in a blend (« Volume » in the DJ editor); [Auto] is Ketmix's own curve. */
internal enum class VolumeCurve { Auto, Smooth, Linear, Fast }

/** How long the bass takes to change hands (« Basses »); [Off] = no swap, both fade whole. */
internal enum class BassSwap { Auto, Short, Medium, Long, Off }

/** The sweep the outgoing title leaves through (« Filtre »); [Off] = a blend of levels only. */
internal enum class FilterSweep { Auto, Off, HighPass, LowPass }

/**
 * The details of one transition the listener set in the DJ editor (6.9), remembered with the pair. Every
 * field at its default leaves Ketmix's plan exactly as it was: [MixShaping.apply] returns it unchanged.
 */
internal data class MixShape(
    val volume: VolumeCurve = VolumeCurve.Auto,
    val bass: BassSwap = BassSwap.Auto,
    val filter: FilterSweep = FilterSweep.Auto,
    /** « Calage du tempo »: false keeps both titles on their own speed (no blend in time unless the tempos already agree). */
    val tempo: Boolean = true
) {
    val isDefault: Boolean get() = this == DEFAULT

    /** One small number for the store: 0 is the default. */
    fun code(): Int = volume.ordinal + VOLUMES * (bass.ordinal + BASSES * (filter.ordinal + FILTERS * (if (tempo) 0 else 1)))

    companion object {
        val DEFAULT = MixShape()
        private val VOLUMES = VolumeCurve.values().size
        private val BASSES = BassSwap.values().size
        private val FILTERS = FilterSweep.values().size
        val MAX_CODE: Int get() = VOLUMES * BASSES * FILTERS * 2 - 1

        fun of(code: Int): MixShape? {
            if (code !in 0..MAX_CODE) return null
            var rest = code
            val volume = VolumeCurve.values()[rest % VOLUMES]; rest /= VOLUMES
            val bass = BassSwap.values()[rest % BASSES]; rest /= BASSES
            val filter = FilterSweep.values()[rest % FILTERS]; rest /= FILTERS
            return MixShape(volume, bass, filter, tempo = rest == 0)
        }
    }
}

/**
 * Applies a [MixShape] to a plan from [TransitionPlanner]: the kind and colour it plays (a filter asked
 * for, or left out), where the bass changes hands, and the shape itself for [DeckAutomation] to draw its
 * levels with. The default shape returns the plan untouched, so what Ketmix plays by itself never changes.
 */
internal object MixShaping {
    fun apply(plan: MixPlan, shape: MixShape, effects: Boolean = true): MixPlan {
        if (shape.isDefault || !plan.mixes) return plan
        var next = plan.copy(shape = shape)
        next = filtered(next, shape.filter, effects)
        next = bass(next, shape.bass)
        return next
    }

    private fun filtered(plan: MixPlan, filter: FilterSweep, effects: Boolean): MixPlan {
        val variant = when (filter) { FilterSweep.HighPass -> 0; FilterSweep.LowPass -> 1; else -> plan.variant }
        return when (plan.kind) {
            MixKind.Filter, MixKind.Wash -> when (filter) {
                FilterSweep.Auto -> plan
                FilterSweep.Off -> plan.copy(kind = MixKind.Crossfade, hold = 0f)
                else -> plan.copy(kind = MixKind.Filter, variant = variant, hold = if (plan.kind == MixKind.Wash) .2f else plan.hold)
            }
            MixKind.Crossfade -> if (effects && (filter == FilterSweep.HighPass || filter == FilterSweep.LowPass))
                plan.copy(kind = MixKind.Filter, variant = variant, hold = .2f) else plan
            // A blend in time: high-pass rises into the air, low-pass sinks (its two colours); Off keeps the bass swap alone.
            MixKind.Beatmatch -> plan.copy(variant = variant)
            else -> plan
        }
    }

    private fun bass(plan: MixPlan, bass: BassSwap): MixPlan = when {
        bass == BassSwap.Auto -> plan
        plan.kind == MixKind.Beatmatch && bass == BassSwap.Off -> plan.copy(swapMs = -1)
        // A plain crossing gets a bass swap of its own, half-way.
        plan.kind == MixKind.Crossfade && bass != BassSwap.Off && plan.overlapMs >= 1_000L -> plan.copy(swapMs = plan.outMs + plan.overlapMs / 2)
        else -> plan
    }

    /** True when [plan] plays a bass swap that [BassSwap] can change. */
    fun hasBass(plan: MixPlan): Boolean = plan.kind == MixKind.Beatmatch || plan.kind == MixKind.Crossfade
    /** True when the listener's filter is heard in [plan]'s kind. */
    fun hasFilter(plan: MixPlan): Boolean = plan.kind in setOf(MixKind.Beatmatch, MixKind.Filter, MixKind.Wash, MixKind.Crossfade)
    /** True when the volume curve is heard: a crossing of levels, not a cut on the beat. */
    fun hasVolume(plan: MixPlan): Boolean = plan.kind in setOf(MixKind.Beatmatch, MixKind.Filter, MixKind.Wash, MixKind.Crossfade)

    /**
     * Half the time the bass takes to change hands, in µs: Ketmix's own ([todayUs], half a beat) for [BassSwap.Auto]
     * and [BassSwap.Short], two beats for medium, four for long, always within [roomUs].
     */
    fun bassHalfUs(plan: MixPlan, todayUs: Long, roomUs: Long): Long {
        val beatUs = if (plan.beatMs > 0f) (plan.beatMs * 1000f).toLong() else 500_000L
        val half = when (plan.shape.bass) {
            BassSwap.Medium -> beatUs * 2
            BassSwap.Long -> beatUs * 4
            else -> return todayUs
        }
        return half.coerceAtMost(roomUs).coerceAtLeast(minOf(todayUs, roomUs)).coerceAtLeast(1L)
    }

    private fun ease(x: Float) = (.5 - .5 * cos(PI * x)).toFloat()

    /** The rise of a level 0..1 over a crossing for a curve the listener chose (not [VolumeCurve.Auto]). */
    fun rise(x: Float, curve: VolumeCurve): Float {
        val t = x.coerceIn(0f, 1f)
        return when (curve) {
            VolumeCurve.Linear -> t
            // A franker change, gathered around the middle.
            VolumeCurve.Fast -> ease(ease(t))
            else -> ease(t)
        }
    }

    fun fall(x: Float, curve: VolumeCurve): Float = 1f - rise(x, curve)
}
