package com.ketvoice.app.automix

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * 7.6 (ketmix76): the styles DJs use that Ketmix lacked.
 *
 *  - « Coupe » ([MixStyle.Cut]): the outgoing title stops dead on the first beat of a phrase (else of a bar) once its
 *    singing is over, only one beat of its reverberation left; the next title starts on that very beat, on its first
 *    downbeat. No tempo in common is needed, no build, no echo: the cleanest way out between two energetic titles.
 *  - « Sur le drop » ([MixStyle.Drop]): the same cut, but the next title starts where its intro ends and its whole
 *    groove comes in (its drop), when that lies within what [CutBudget] lets an intro skip and before its first words.
 *  - « Relais des voix » ([MixStyle.Voices]): a blend (in time when the tempos allow it) where the outgoing voice steps
 *    back exactly as the incoming one starts: with the separated voices when they are on the decks, else the
 *    real-time band cut of 5.9.1 ([Stems]).
 *
 * Both cuts are played as a [MixKind.Rise] without a build ([MixPlan.buildMs] = 0, [MixPlan.cut]); the automatic
 * mode only reaches for « Sur le drop », when the next title has a clear drop and the last exit was not a cut already.
 */
internal object StyleCuts {
    /** An intro shorter than this is no intro to skip: the next title has no drop. */
    private const val MIN_INTRO_BARS = 2
    /** How long the outgoing reverberation rings after a cut, in beats. */
    private const val RING_BEATS = 1

    fun cuts(style: MixStyle) = style == MixStyle.Cut || style == MixStyle.Drop

    /** Where the next title's groove comes in on a downbeat, or null when it has no drop worth jumping to. */
    fun dropAt(b: SegmentAnalysis, incoming: MixFacts): Long? {
        if (!b.hasBeat || b.barOffset < 0) return null
        val bar = CutBudget.barMs(b)
        if (b.riseMs - b.soundStartMs < MIN_INTRO_BARS * bar) return null
        val drop = b.barAtOrAfter(b.riseMs - b.beatMs / 2.0)
        if (drop - b.soundStartMs > CutBudget.IN_BARS * bar || drop > b.startMs + b.lengthMs - 1_000L) return null
        if (incoming.vocalStartMs?.let { drop > it + 150L } == true) return null
        return drop.roundToLong()
    }

    /** The cut of [cut]/[drop]; null when the outgoing title has no beat to cut on before its end. */
    fun plan(a: SegmentAnalysis, b: SegmentAnalysis, incoming: MixFacts, settings: MixSettings, end: Long, start: Long,
             sungUntil: Long?, gain: Float, variant: Int, drop: Boolean): MixPlan? {
        if (!a.hasBeat || !b.hasBeat) return null
        val cut = cutAt(a, settings, end, sungUntil) ?: return null
        val inMs = (if (drop) dropAt(b, incoming) else null) ?: firstDownbeat(b, start)
        val beat = a.beatMs.toDouble().let { var p = it; while (60_000.0 / p >= 160.0) p *= 2.0; while (60_000.0 / p < 80.0) p /= 2.0; p }
        val ring = (RING_BEATS * beat).roundToLong()
        val phrase = a.phraseStarts(cut - 60.0, cut + 60.0).isNotEmpty()
        return MixPlan(MixKind.Rise, outMs = cut, inMs = inMs, overlapMs = min(ring, max(1L, end - cut + ring / 2)), gain = gain,
            beatMs = beat.toFloat(), reason = if (drop && inMs != firstDownbeat(b, start)) "drop" else "cut", onPhrase = phrase, buildMs = 0L, variant = variant, cut = true)
    }

    /** The last first beat of a phrase (else of a bar) after the singing and before the body of the title ends. */
    private fun cutAt(a: SegmentAnalysis, settings: MixSettings, end: Long, sungUntil: Long?): Long? {
        val beat = a.beatMs.toDouble()
        val bodyEnd = if (a.hardEnding) end else a.fallMs
        val earliest = max(a.startMs + beat, sungUntil?.toDouble() ?: a.startMs.toDouble())
        val latest = min(bodyEnd + beat / 2, end - beat)
        if (latest < earliest) return null
        fun lastAtOrBefore(first: Double, period: Double) = first + floor((latest - first) / period + 1e-6) * period
        // What is left out after the cut is counted against what this setting may skip: a phrase too far back gives way to a bar.
        val skippable = max(settings.skippableOutroMs, 4_500L)
        val cut = a.phraseStarts(earliest, latest).lastOrNull()?.takeIf { end - it <= skippable }
            ?: (if (a.barOffset >= 0) lastAtOrBefore(a.beatAtMs + a.barOffset * beat, beat * 4.0) else lastAtOrBefore(a.beatAtMs, beat))
                .takeIf { it >= earliest } ?: return null
        if (end - cut.roundToLong() > skippable) return null
        return cut.roundToLong()
    }

    private fun firstDownbeat(b: SegmentAnalysis, start: Long): Long {
        val firstBeat = b.beatAtOrAfter(start - 20.0)
        val firstBar = if (b.barOffset >= 0) b.barAtOrAfter(start - 20.0) else firstBeat
        return (if (firstBar - firstBeat <= b.beatMs * 1.05) firstBar else firstBeat).roundToLong().coerceAtLeast(0L)
    }

    /** « Relais des voix »: [plan] with the voices handed over (see [Stems]); kinds without a blend are left as they are. */
    fun handoff(plan: MixPlan, incoming: MixFacts): MixPlan =
        if (!plan.mixes || plan.kind !in setOf(MixKind.Beatmatch, MixKind.Filter, MixKind.Wash, MixKind.Crossfade)) plan
        else plan.copy(stems = true, voiceFromMs = incoming.vocalStartMs ?: plan.voiceFromMs, reason = "voices · " + plan.reason)
}
