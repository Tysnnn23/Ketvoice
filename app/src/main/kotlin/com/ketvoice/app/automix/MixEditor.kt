package com.ketvoice.app.automix

import kotlin.math.roundToLong

/**
 * What the Ketmix DJ editor (6.1) works with, apart from the screen: the two ends of titles it shows,
 * the listener's choice, and the plan Ketmix will play from it — worked out by [TransitionPlanner]
 * itself, so that what the editor draws is what the engine plays.
 */
internal object MixEditor {
    /** How much of the outgoing title's ending the editor shows: more than Ketmix reads, so that an earlier exit can be chosen. */
    const val TAIL_MS = 90_000L
    /** How much of the incoming title's start it shows. */
    const val HEAD_MS = 60_000L
    /** Each step of the editor's arrows when a title has no beat grid. */
    const val FREE_STEP_MS = 250L

    /** One end of a title as the editor shows it: the length it is filed under, its wave and its analysis when known. */
    data class Side(val trackId: String, val durationMs: Long, val wave: Wave? = null, val analysis: SegmentAnalysis? = null)

    /** The listener's choice for a transition; -1 / null = left to Ketmix. */
    data class Choice(val exitMs: Long = -1, val entryMs: Long = -1, val overlapMs: Long = -1, val style: MixStyle? = null,
                      /** Volume curve, bass, filter and tempo (6.9); the default is Ketmix's own. */
                      val shape: MixShape = MixShape.DEFAULT,
                      /** Volume keyframes of both titles over the blend (7.6, see [MixKeys]). */
                      val keys: List<LevelKey> = emptyList()) {
        val automatic: Boolean get() = exitMs < 0 && entryMs < 0 && overlapMs <= 0 && style == null && shape.isDefault && keys.isEmpty()
        /** What is remembered for the pair (the points belong to each title). */
        val pairChoice: PairChoice get() = PairChoice(style, overlapMs, shape, keys)
    }

    /** What is remembered for the transition from [fromId] into [toId] (a title alone when both are the same). */
    fun remembered(fromId: String, fromDurationMs: Long, toId: String, toDurationMs: Long, pair: Boolean): Choice {
        val choice = if (pair) MixPointStore.pair(fromId, toId) else null
        return Choice(MixPointStore.exitOf(fromId, fromDurationMs), MixPointStore.entryOf(toId, toDurationMs),
            choice?.overlapMs ?: -1, choice?.style, choice?.shape ?: MixShape.DEFAULT, choice?.keys.orEmpty())
    }

    /**
     * The plan for [out] into [into] with [choice], as [Automix] would plan it (the voice of the outgoing title
     * from [vocalEndMs]). [defaultStyle] is the style of the settings, used when the pair has none.
     */
    fun plan(out: Side, into: Side, choice: Choice, intensity: Int, defaultStyle: MixStyle,
             vocalEndMs: Long? = null, vocalStartMs: Long? = null): MixPlan =
        TransitionPlanner.plan(
            MixFacts(out.durationMs, out.analysis, vocalEndMs = vocalEndMs, userExitMs = choice.exitMs),
            MixFacts(into.durationMs, into.analysis, vocalStartMs = vocalStartMs, userEntryMs = choice.entryMs),
            MixContext(loop = out.trackId == into.trackId, userOverlapMs = choice.overlapMs),
            MixSettings(intensity, choice.style ?: defaultStyle, tempoMatch = choice.shape.tempo))
            .let { MixShaping.apply(it, choice.shape) }
            .let { if (it.mixes && choice.keys.isNotEmpty()) it.copy(keys = choice.keys) else it }

    /** Ketmix's own plan for the same pair: where the editor draws the automatic points. */
    fun automatic(out: Side, into: Side, intensity: Int, defaultStyle: MixStyle, vocalEndMs: Long? = null, vocalStartMs: Long? = null): MixPlan =
        plan(out, into, Choice(), intensity, defaultStyle, vocalEndMs, vocalStartMs)

    /**
     * Where a point dragged to [rawMs] lands: on the grid of [analysis] ([GridSnap.snap]) unless the listener
     * holds it to move it freely, within [range].
     */
    fun place(analysis: SegmentAnalysis?, rawMs: Long, toleranceMs: Long, free: Boolean, range: LongRange): GridSnap.Result {
        val clamped = rawMs.coerceIn(range.first, range.last)
        if (free) return GridSnap.Result(clamped, GridSnap.Kind.None)
        val snapped = GridSnap.snap(analysis, clamped, toleranceMs)
        return if (snapped.ms in range) snapped else GridSnap.Result(clamped, GridSnap.Kind.None)
    }

    /** How far a point is drawn to a bar or a phrase: about [pullPx] of the screen, never less than a quarter beat. */
    fun tolerance(analysis: SegmentAnalysis?, msPerPx: Float, pullPx: Float): Long {
        val screen = (msPerPx * pullPx).roundToLong()
        val beat = analysis?.takeIf { it.hasBeat }?.beatMs ?: 0f
        return maxOf(screen, (beat / 4f).roundToLong(), 40L)
    }

    /** One step of the arrows beside a point: a beat of its grid, or [FREE_STEP_MS] without one. */
    fun step(analysis: SegmentAnalysis?, atMs: Long, forward: Boolean, range: LongRange): Long {
        val grid = analysis?.takeIf { GridSnap.covers(it, atMs) }
        val next = if (grid != null) {
            val beat = grid.beatMs.toDouble()
            val k = (atMs - grid.beatAtMs) / beat
            val onBeat = kotlin.math.abs(k - Math.rint(k)) * beat < 2.0
            // On a beat, to the next one; between two, to the one on that side.
            val target = when { onBeat -> Math.rint(k) + if (forward) 1 else -1; forward -> kotlin.math.ceil(k); else -> kotlin.math.floor(k) }
            (grid.beatAtMs + target * beat).roundToLong()
        } else atMs + if (forward) FREE_STEP_MS else -FREE_STEP_MS
        return next.coerceIn(range.first, range.last)
    }

    /** The stretch around the start of the mix that the detail lanes show: some seconds before, the blend, some after. */
    data class View(val beforeMs: Long, val afterMs: Long) {
        val spanMs: Long get() = beforeMs + afterMs
        /** Where the start of the mix stands across the lanes, 0..1. */
        val anchor: Float get() = beforeMs.toFloat() / spanMs
    }

    fun view(plan: MixPlan, wide: Boolean): View {
        val before = (if (wide) 14_000L else 9_000L) + plan.buildMs
        val after = (maxOf(plan.overlapMs, 2_000L) + if (wide) 12_000L else 7_000L)
        val span = (before + after).coerceIn(16_000L, if (wide) 90_000L else 60_000L)
        val scale = span.toDouble() / (before + after)
        return View((before * scale).roundToLong(), (after * scale).roundToLong())
    }

    /** "3:12.4": a point in a title, to a tenth of a second. */
    fun clock(ms: Long): String {
        val t = ms.coerceAtLeast(0L)
        val minutes = t / 60_000L
        val seconds = (t / 1000L) % 60
        val tenth = (t / 100L) % 10
        return "$minutes:${seconds.toString().padStart(2, '0')}.$tenth"
    }

    /** The range a title's exit may be put in: inside the stretch the editor shows. */
    fun exitRange(durationMs: Long): LongRange = maxOf(5_000L, durationMs - TAIL_MS)..maxOf(5_000L, durationMs - 1_000L)
    /** The range a title's entry may be put in. */
    fun entryRange(durationMs: Long): LongRange = 0L..maxOf(0L, minOf(HEAD_MS, durationMs - 10_000L))
}
