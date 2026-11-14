package com.ketvoice.app.automix

import com.ketvoice.app.data.MusicApi
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

internal enum class MixKind {
    /** The titles follow each other as they are (albums in order, repeat, timer). */
    None,
    /** Only the silence between the two is removed; a last note is never covered. */
    Trim,
    /** One fades out while the other fades in. */
    Crossfade,
    /** Both play in time with each other and the bass changes hands on a beat. */
    Beatmatch
}

/** What is known about one of the two titles of a transition. */
internal data class MixFacts(
    val durationMs: Long,
    /** The last seconds of the outgoing title, or the first seconds of the incoming one. */
    val analysis: SegmentAnalysis? = null,
    /** Incoming title: where the first sung line starts. */
    val vocalStartMs: Long? = null,
    /** Outgoing title: where the last sung line ends. */
    val vocalEndMs: Long? = null
)

internal data class MixContext(
    val sameAlbumInOrder: Boolean = false,
    val repeatOne: Boolean = false,
    val stopAfterCurrent: Boolean = false,
    val exactAudio: Boolean = false,
    val skipSilence: Boolean = false,
    /** The listener wants the titles of an album mixed too. */
    val albumsMixed: Boolean = true
)

/** 0 = discreet, 1 = balanced, 2 = DJ. */
internal data class MixSettings(val intensity: Int = 1) {
    /** How far the incoming title may be sped up or slowed down to follow the outgoing one. */
    val tempoRange: Float get() = when (intensity) { 0 -> .04f; 2 -> .12f; else -> .08f }
    val beatCounts: IntArray get() = when (intensity) { 0 -> intArrayOf(16, 8); 2 -> intArrayOf(64, 32, 16, 8); else -> intArrayOf(32, 16, 8) }
    val crossfadeMs: Long get() = when (intensity) { 0 -> 4_000L; 2 -> 12_000L; else -> 8_000L }
    /** How much of an instrumental ending may be skipped to mix earlier. */
    val skippableOutroMs: Long get() = when (intensity) { 0 -> 0L; 2 -> 90_000L; else -> 25_000L }
}

internal data class MixPlan(
    val kind: MixKind,
    /** Position in the outgoing title where the incoming one becomes audible. */
    val outMs: Long = 0,
    /** Position in the incoming title at that moment. */
    val inMs: Long = 0,
    /** How long both are heard, on the outgoing title's clock. */
    val overlapMs: Long = 0,
    /** Speed of the incoming title while both play (1 = its own tempo). */
    val rate: Float = 1f,
    /** Outgoing position where the bass passes to the incoming title; -1 = no bass swap. */
    val swapMs: Long = -1,
    /** Level of the incoming title during the overlap, so that it does not jump out. */
    val gain: Float = 1f,
    /** Length of a beat of the outgoing title, when both are mixed in time. */
    val beatMs: Float = 0f,
    val reason: String = ""
) {
    val mixes: Boolean get() = kind != MixKind.None
    /** Position in the incoming title once the outgoing one is gone. */
    val inEndMs: Long get() = inMs + (overlapMs * rate).roundToLong()
}

internal object TransitionPlanner {
    private const val TRIM_OVERLAP_MS = 240L
    private const val MIN_TITLE_MS = 30_000L
    private const val MIN_CROSSFADE_MS = 2_500L

    fun plan(out: MixFacts, incoming: MixFacts, context: MixContext, settings: MixSettings = MixSettings()): MixPlan {
        if (context.exactAudio) return MixPlan(MixKind.None, reason = "exact audio")
        if (context.repeatOne) return MixPlan(MixKind.None, reason = "repeat one")
        if (context.stopAfterCurrent) return MixPlan(MixKind.None, reason = "sleep timer")
        if (context.sameAlbumInOrder && !context.albumsMixed) return MixPlan(MixKind.None, reason = "album in order")
        if (out.durationMs < MIN_TITLE_MS || incoming.durationMs < MIN_TITLE_MS) return MixPlan(MixKind.None, reason = "short title")

        val a = out.analysis
        val b = incoming.analysis
        // Without analysis only the duration is known: a plain, short crossfade.
        if (a == null || b == null) {
            val fade = (settings.crossfadeMs * 2 / 3).coerceAtLeast(MIN_CROSSFADE_MS)
            val end = (a?.soundEndMs ?: (out.durationMs - 250L)).coerceAtMost(out.durationMs)
            return checked(MixPlan(MixKind.Crossfade, outMs = end - fade, inMs = b?.soundStartMs ?: 0L, overlapMs = fade,
                gain = 1f, reason = "no analysis"), out, incoming)
        }

        // An album whose titles run into each other (no silence after one, a full-level start for the
        // next) is one piece of music: mixing over its joins would only cover them.
        if (context.sameAlbumInOrder && gapless(out.durationMs, a, b)) return MixPlan(MixKind.None, reason = "gapless album")
        val gain = 10f.pow((a.loudnessDb - b.loudnessDb) / 20f).coerceIn(.5f, 1f)
        // A fade is almost silent in its last quarter: the incoming title must be in before that.
        val end = if (a.fadeOut) min(a.soundEndMs, a.fallMs + (a.soundEndMs - a.fallMs) * 3 / 4) else a.soundEndMs
        val start = b.soundStartMs
        val sungUntil = out.vocalEndMs?.plus(300L)

        if (a.hasBeat && b.hasBeat && !context.skipSilence) {
            val rate = BeatAnalysis.foldedRatio(a.bpm, b.bpm)
            if (abs(rate - 1f) <= settings.tempoRange) {
                beatmatch(a, b, out, incoming, settings, rate, gain, end, start, sungUntil)?.let { return checked(it, out, incoming) }
            }
        }
        // A last note at full level is left alone: the next title starts right after it.
        if (a.hardEnding && !a.fadeOut) {
            return checked(MixPlan(MixKind.Trim, outMs = end - TRIM_OVERLAP_MS / 2, inMs = max(0L, start - 40L),
                overlapMs = TRIM_OVERLAP_MS, gain = gain, reason = "hard ending"), out, incoming)
        }

        var fade = settings.crossfadeMs
        if (a.fadeOut) fade = fade.coerceAtMost(max(MIN_CROSSFADE_MS, end - a.fallMs))
        // The fade stays in the part of the ending where nobody sings, when there is one.
        if (sungUntil != null) fade = fade.coerceAtMost(max(MIN_CROSSFADE_MS, end - sungUntil))
        // An incoming title that sings at once gets a shorter fade, so that two voices do not overlap for long.
        val sungFrom = incoming.vocalStartMs
        if (sungFrom != null && (sungUntil == null || sungUntil > end - fade) && sungFrom - start < fade / 2)
            fade = fade.coerceAtMost(max(MIN_CROSSFADE_MS, (sungFrom - start) * 2))
        return checked(MixPlan(MixKind.Crossfade, outMs = end - fade, inMs = start, overlapMs = fade, gain = gain,
            reason = if (a.hasBeat && b.hasBeat) "tempos too far apart" else "no steady beat"), out, incoming)
    }

    private fun beatmatch(a: SegmentAnalysis, b: SegmentAnalysis, out: MixFacts, incoming: MixFacts, settings: MixSettings,
                          rate: Float, gain: Float, end: Long, start: Long, sungUntil: Long?): MixPlan? {
        // Bars are counted on a pulse between 80 and 160 BPM, whichever way the tempo was read.
        var beat = a.beatMs
        while (60_000f / beat >= 160f) beat *= 2f
        while (60_000f / beat < 80f) beat /= 2f
        // The incoming title enters on its first beat.
        val inMs = b.beatAtOrAfter(start - 20.0).roundToLong().coerceAtLeast(0L)
        val sungFrom = incoming.vocalStartMs
        val counts = settings.beatCounts
        for ((index, count) in counts.withIndex()) {
            val overlap = (count * beat).roundToLong()
            val latest = end - overlap
            // The mix starts once the singing is over; without lyrics, where the level drops.
            val earliest = max(a.startMs + a.beatMs.toLong(), sungUntil ?: a.fallMs)
            if (earliest > latest) continue
            // The incoming voice should arrive once the outgoing title is mostly gone.
            if (sungFrom != null && sungUntil == null) {
                val voiceAfter = (sungFrom - inMs) / rate
                val share = if (index == counts.lastIndex) .4f else .6f
                if (voiceAfter < overlap * share) continue
            }
            val target = max(earliest, latest - settings.skippableOutroMs).toDouble()
            var outMs = a.beatAtOrAfter(target)
            if (outMs > latest) outMs = a.beatAtOrAfter(latest.toDouble()) - a.beatMs
            if (outMs < earliest - a.beatMs / 2 || outMs > latest + 1) continue
            // First beats of bars meet when both titles let their bars be told apart.
            if (a.barOffset >= 0 && b.barOffset >= 0) {
                val inBar = barIndex(b, inMs.toDouble())
                var candidate = outMs
                for (shift in 0 until 4) {
                    if (candidate > latest + 1) break
                    if (barIndex(a, candidate) == inBar) { outMs = candidate; break }
                    candidate += a.beatMs
                }
            }
            val middle = outMs + overlap / 2.0
            val swap = a.beatAtOrAfter(middle - a.beatMs / 2.0)
            return MixPlan(MixKind.Beatmatch, outMs = outMs.roundToLong(), inMs = inMs, overlapMs = overlap, rate = rate,
                swapMs = swap.roundToLong(), gain = gain, beatMs = beat, reason = "$count beats")
        }
        return null
    }

    /** True when the end of one title and the start of the next leave no gap at all. */
    internal fun gapless(outDurationMs: Long, a: SegmentAnalysis, b: SegmentAnalysis): Boolean {
        val trailingSilence = outDurationMs - a.soundEndMs
        val abruptEnd = a.hardEnding && !a.fadeOut && trailingSilence < 200L
        val immediateStart = b.soundStartMs - b.startMs < 120L && b.riseMs - b.soundStartMs < 500L
        return abruptEnd && immediateStart
    }

    /** Which beat of its bar (0..3) [positionMs] is, for a title whose bars are known. */
    private fun barIndex(analysis: SegmentAnalysis, positionMs: Double): Int {
        val beats = floor((positionMs - analysis.beatAtMs) / analysis.beatMs + .5).toLong()
        return (((beats - analysis.barOffset) % 4) + 4).toInt() % 4
    }

    /** A plan that does not fit in the two titles is not played. */
    private fun checked(plan: MixPlan, out: MixFacts, incoming: MixFacts): MixPlan = when {
        plan.outMs < 5_000L -> MixPlan(MixKind.None, reason = "no room before the end")
        plan.outMs + plan.overlapMs > out.durationMs + 400L -> MixPlan(MixKind.None, reason = "past the end")
        plan.inEndMs > incoming.durationMs - 5_000L -> MixPlan(MixKind.None, reason = "incoming too short")
        plan.overlapMs !in 1..45_000L -> MixPlan(MixKind.None, reason = "overlap out of range")
        else -> plan
    }
}

/** When the singing starts and stops, read from synchronised lyrics. */
internal object Vocals {
    private fun sung(line: MusicApi.LyricLine): Boolean =
        line.timeMs >= 0 && line.text.any { it.isLetterOrDigit() }

    fun startMs(lines: List<MusicApi.LyricLine>): Long? = lines.firstOrNull(::sung)?.timeMs

    /** End of the last sung line: its last word when words are timed, else a duration guessed from its length. */
    fun endMs(lines: List<MusicApi.LyricLine>, durationMs: Long): Long? {
        val last = lines.lastOrNull(::sung) ?: return null
        val timedEnd = last.words.maxOfOrNull { if (it.endMs > 0) it.endMs else it.startMs }?.takeIf { it > last.timeMs }
        val guessed = last.timeMs + (1_500L + 320L * last.text.split(' ').count { it.isNotBlank() }).coerceAtMost(7_000L)
        val end = timedEnd ?: guessed
        return if (durationMs > 0) end.coerceAtMost(durationMs) else end
    }
}
