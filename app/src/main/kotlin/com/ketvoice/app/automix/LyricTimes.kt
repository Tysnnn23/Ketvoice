package com.ketvoice.app.automix

import com.ketvoice.app.data.MusicApi
import kotlin.math.roundToLong

/**
 * « Fondu sur les paroles » (6.9): where each line of a title is sung, read from its synchronised lyrics
 * (or, without lyrics, from its separated voice, see [com.ketvoice.app.stems.VoiceActivity.passages]),
 * so that a transition never steps a voice back in the middle of a line and an incoming title never
 * enters a few words into a line.
 */
internal object LyricTimes {
    /** A line is not cut short by less than this after its start: the words have begun. */
    private const val INSIDE_MS = 80L
    /** An entry moved to a line comes in this much before its first word. */
    const val ENTRY_LEAD_MS = 120L
    /** An entry is moved back to the start of its line by at most this. */
    const val MAX_ENTRY_SHIFT_MS = 3_000L
    /** A line followed by an untimed rest (a blank line, « ♪ ») ends there, within these bounds. */
    private const val MIN_LINE_MS = 300L
    private const val MAX_LINE_MS = 15_000L

    private fun sung(line: MusicApi.LyricLine): Boolean = line.timeMs >= 0 && line.text.any { it.isLetterOrDigit() }

    /** Duration guessed from a line's length when nothing else tells where it ends (as [Vocals.endMs] did since 5.0). */
    private fun guessed(line: MusicApi.LyricLine): Long = (1_500L + 320L * line.text.split(' ').count { it.isNotBlank() }).coerceAtMost(7_000L)

    /**
     * The sung lines of [lines] as start..end in the title (ms), in order. A line ends at its last timed word;
     * else where a rest line (no letters: blank, « ♪ », « … ») marks the end of the singing; else at a duration
     * guessed from its words, never past the next line.
     */
    fun lines(lines: List<MusicApi.LyricLine>, durationMs: Long): List<LongRange> {
        val timed = lines.filter { it.timeMs >= 0 }.sortedBy { it.timeMs }
        val out = ArrayList<LongRange>()
        for ((i, line) in timed.withIndex()) {
            if (!sung(line)) continue
            val next = timed.getOrNull(i + 1)
            val words = line.words.maxOfOrNull { if (it.endMs > 0) it.endMs else it.startMs }?.takeIf { it > line.timeMs }
            var end = when {
                words != null -> words
                next != null && !sung(next) -> next.timeMs.coerceIn(line.timeMs + MIN_LINE_MS, line.timeMs + MAX_LINE_MS)
                else -> line.timeMs + guessed(line)
            }
            if (next != null && sung(next) && next.timeMs > line.timeMs) end = end.coerceAtMost(next.timeMs)
            if (durationMs > 0) end = end.coerceAtMost(durationMs)
            if (end > line.timeMs) out += line.timeMs..end
        }
        return out
    }

    /** The lyrics when the title has synchronised ones, else its separated voice's passages. */
    fun merge(lyrics: List<LongRange>, separated: List<LongRange>?): List<LongRange> = lyrics.ifEmpty { separated.orEmpty() }

    /** The line [positionMs] falls in, words already begun and not yet over; null between lines. */
    fun inside(lines: List<LongRange>, positionMs: Long): LongRange? =
        lines.firstOrNull { positionMs > it.first + INSIDE_MS && positionMs < it.last }

    /**
     * Where the outgoing voice may start to step back when a blend starts at [outMs] under its last sung
     * words: at once between two lines, else once the line being sung at [outMs] is over.
     */
    fun voiceDownFrom(lines: List<LongRange>, outMs: Long): Long = inside(lines, outMs)?.last ?: outMs

    /**
     * An incoming title is never entered a few words into a line: an entry that falls inside one (a title that
     * sings from its very first beat) is moved back to just before that line, the outgoing position with it so
     * that both stay in time, when that does not bring the incoming voice over the outgoing one.
     */
    fun entry(plan: MixPlan, incoming: List<LongRange>, sungUntilMs: Long?): MixPlan {
        if (!plan.mixes || incoming.isEmpty()) return plan
        // An exit on the beat drops the next title in on its downbeat: moving it would take the drop off the beat.
        if (plan.kind == MixKind.Echo || plan.kind == MixKind.Rise || plan.kind == MixKind.None) return plan
        val line = inside(incoming, plan.inMs) ?: return plan
        val target = (line.first - ENTRY_LEAD_MS).coerceAtLeast(0L)
        val shift = plan.inMs - target
        if (shift <= 0L || shift > MAX_ENTRY_SHIFT_MS) return plan
        val outShift = (shift / plan.rate.toDouble()).roundToLong()
        val outMs = plan.outMs - outShift
        // The incoming voice is heard from the new start: the outgoing one must be over by then.
        if (sungUntilMs != null && outMs < sungUntilMs) return plan
        return plan.copy(inMs = target, outMs = outMs, overlapMs = plan.overlapMs + outShift)
    }

    /**
     * True when a sung line of the incoming title would be heard over a sung line of the outgoing one, both
     * still at a level that counts (the outgoing title over the first [heardShare] of the blend).
     */
    fun voicesMeet(plan: MixPlan, outgoing: List<LongRange>, incoming: List<LongRange>, heardShare: Float = .6f): Boolean {
        if (!plan.mixes || outgoing.isEmpty() || incoming.isEmpty()) return false
        val heardUntil = plan.outMs + (plan.overlapMs * heardShare).toLong()
        val cut = if (plan.kind == MixKind.Echo || plan.kind == MixKind.Rise || plan.kind == MixKind.Trim) plan.outMs else heardUntil
        for (o in outgoing) {
            val from = maxOf(o.first, plan.outMs); val until = minOf(o.last, cut)
            if (until <= from) continue
            for (i in incoming) {
                val start = plan.outMs + ((i.first - plan.inMs) / plan.rate.toDouble()).toLong()
                val end = plan.outMs + ((i.last - plan.inMs) / plan.rate.toDouble()).toLong()
                if (minOf(end, until) - maxOf(start, from) > 250L) return true
            }
        }
        return false
    }
}
