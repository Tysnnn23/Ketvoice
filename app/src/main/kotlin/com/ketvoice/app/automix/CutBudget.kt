package com.ketvoice.app.automix

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * 7.1 « budget de coupe »: what Ketmix may leave out of two titles to mix them, whatever the kind of transition.
 * The first complaint about other apps' automatic mixes is a verse or a real ending gone (an incoming title
 * started at 49 s, 30 s cut off an ending). Ketmix's own plans are held to this; points the listener set in
 * the DJ editor are not (see [TransitionPlanner.honour]).
 *
 *  - The outgoing title loses at most [OUT_BARS] bars of music after the blend (counted to its last note, or
 *    to the point of a fade where it is nearly silent).
 *  - The incoming title never starts after its first sung words, and skips at most [IN_BARS] bars of intro.
 *  - No mix starts in the silence of a false ending, or before a hidden title (see [Endings]).
 *  - A title played off its own tempo by more than [VOICE_STRETCH] is not heard so while a voice sings:
 *    a voice gives a stretch away long before drums do.
 *
 * A plan that breaks one of these is not played: the planner then falls back to a shorter way out.
 */
internal object CutBudget {
    const val OUT_BARS = 8
    const val IN_BARS = 16
    /** A bar when no tempo could be read: one of 120 BPM. */
    private const val PLAIN_BAR_MS = 2_000.0
    /** An entry this close after the first sung words is the beat that carries them, not a skipped line. */
    private const val VOICE_TOLERANCE_MS = 150L
    /** Beyond this the incoming title is not played off its tempo under a voice (3.5 %). */
    const val VOICE_STRETCH = .035f

    /** Length of a bar, on a pulse between 80 and 160 BPM. */
    fun barMs(a: SegmentAnalysis): Double {
        if (a.bpm <= 0f) return PLAIN_BAR_MS
        var beat = a.beatMs.toDouble()
        while (60_000.0 / beat >= 160.0) beat *= 2.0
        while (60_000.0 / beat < 80.0) beat /= 2.0
        return beat * 4.0
    }

    /** Where the music of the outgoing title really ends: its last note, or three quarters into its fade. */
    fun musicEnd(a: SegmentAnalysis): Long =
        if (a.fadeOut) minOf(a.soundEndMs, a.fallMs + (a.soundEndMs - a.fallMs) * 3 / 4) else a.soundEndMs

    /** How much of the outgoing ending a blend may leave out: what the setting allows, within the budget. */
    fun skippableMs(a: SegmentAnalysis, settings: MixSettings): Long =
        minOf(settings.skippableOutroMs, (OUT_BARS * barMs(a)).toLong())

    /** Music of the outgoing title that is never heard: after the blend, before its end. */
    fun lostMs(plan: MixPlan, a: SegmentAnalysis): Long = max(0L, musicEnd(a) - (plan.outMs + plan.overlapMs))

    /** Intro of the incoming title that is never heard. */
    fun skippedMs(plan: MixPlan, b: SegmentAnalysis): Long = max(0L, plan.inMs - b.soundStartMs)

    /** True when a voice is heard while the incoming title plays off its tempo by more than [VOICE_STRETCH]. */
    fun stretchedUnderVoice(plan: MixPlan, incoming: MixFacts, speed: Float): Boolean {
        if (plan.kind != MixKind.Beatmatch || abs(speed * plan.rate - 1f) <= VOICE_STRETCH + 1e-4f) return false
        val outSings = plan.sungUntilMs > plan.outMs
        val inSings = incoming.vocalStartMs?.let { it < plan.inEndMs } == true
        return outSings || inSings
    }

    /** Why [plan] breaks the budget, or null when it keeps to it. */
    fun broken(plan: MixPlan, a: SegmentAnalysis, b: SegmentAnalysis, incoming: MixFacts, speed: Float): String? = when {
        !plan.mixes -> null
        lostMs(plan, a) > OUT_BARS * barMs(a) + 1.0 -> "ending cut"
        incoming.vocalStartMs?.let { plan.inMs > it + VOICE_TOLERANCE_MS } == true -> "first words skipped"
        skippedMs(plan, b) > IN_BARS * barMs(b) + 1.0 -> "intro skipped"
        a.gapUntilMs >= 0 && plan.outMs < a.gapUntilMs -> "in the silence"
        stretchedUnderVoice(plan, incoming, speed) -> "stretch under a voice"
        else -> null
    }

    fun keep(plan: MixPlan, a: SegmentAnalysis, b: SegmentAnalysis, incoming: MixFacts, speed: Float): MixPlan =
        broken(plan, a, b, incoming, speed)?.let { MixPlan(MixKind.None, reason = "cut budget: $it") } ?: plan
}

/**
 * 7.1: endings that are not what they seem. A silence of 2 s or more followed by music again ([SegmentAnalysis.gapFromMs])
 * is a false ending (the band comes back) or a hidden title after a long silence. Ketmix never mixes inside that
 * silence: the ending is planned on what comes after it, as if the title started there, and a short piece after
 * the silence is let end whole, the next title starting right after its last note.
 */
internal object Endings {
    /** Under this, what follows the silence is let end, never blended. */
    const val SHORT_RESTART_MS = 8_000L

    fun restarts(a: SegmentAnalysis?): Boolean = a != null && a.gapUntilMs >= 0 && a.gapUntilMs < a.soundEndMs

    /** The outgoing stretch seen from the end of its last silence; [a] itself when it has none. */
    fun afterGap(a: SegmentAnalysis): SegmentAnalysis {
        if (!restarts(a)) return a
        val from = a.gapUntilMs
        val inside = a.fallMs >= from
        val short = a.soundEndMs - from < SHORT_RESTART_MS
        return a.copy(startMs = from, lengthMs = a.startMs + a.lengthMs - from, soundStartMs = max(a.soundStartMs, from),
            riseMs = max(a.riseMs, from), fallMs = if (short) a.soundEndMs else max(a.fallMs, from),
            fadeOut = a.fadeOut && inside && !short, hardEnding = short || (a.hardEnding && inside))
    }
}

/**
 * 7.1: two titles too far apart to be blended like a DJ would (a ballad without drums into a loud, busy title;
 * the Kate Bush into Limp Bizkit of other apps): they change over as on the radio, a short fade at the end of
 * the first. Read from what Ketmix measures anyway: tempo, a steady beat or none, level, how busy the sound is,
 * and keys that clash (only keys read with confidence are ever given, see [MusicalKey.MIN_STRENGTH]).
 */
internal object PairFit {
    /** From this distance on, the change is a radio one. */
    const val RADIO = 2f
    const val RADIO_FADE_MS = 3_000L

    /** 0 for two titles alike; each term counts up to 1 (a clash 0.5). */
    fun distance(a: SegmentAnalysis, b: SegmentAnalysis): Float {
        val tempo = tempo(a, b)
        val level = ((BlendFit.levelGapDb(a, b) - 3f) / 6f).coerceIn(0f, 1f)
        val busy = if (a.activity > 0f && b.activity > 0f)
            ((abs(ln(a.activity / b.activity)) - ln(1.5f)) / ln(2f)).coerceIn(0f, 1f) else 0f
        val clash = if (MusicalKey.relation(a.key, b.key) == MusicalKey.Relation.Clash) .5f else 0f
        return tempo + level + busy + clash
    }

    /** How far apart the pulses are: 0 the same, 1 at 12 % or more, or one with a steady beat and one without. */
    fun tempo(a: SegmentAnalysis, b: SegmentAnalysis): Float = when {
        a.hasBeat && b.hasBeat -> (abs(BeatAnalysis.foldedRatio(a.bpm, b.bpm) - 1f) / .12f).coerceIn(0f, 1f)
        a.hasBeat != b.hasBeat -> 1f
        else -> 0f
    }

    /**
     * The radio change for a pair this far apart, or null when the two may be blended. Two titles on nearly the
     * same pulse are always left to a DJ blend: a quiet, sparse intro of a dance title is what such blends are for.
     */
    fun radio(a: SegmentAnalysis, b: SegmentAnalysis, end: Long, gain: Float): MixPlan? {
        if (tempo(a, b) < .5f || distance(a, b) < RADIO) return null
        val fade = RADIO_FADE_MS.coerceAtMost(max(1_000L, end - a.startMs))
        return MixPlan(MixKind.Crossfade, outMs = end - fade, inMs = b.soundStartMs, overlapMs = fade, gain = gain, reason = "far apart")
    }
}
