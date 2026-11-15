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
    /** One fades out while the other fades in: what is left when nothing finer can be played. */
    Crossfade,
    /** Both play in time with each other and the bass changes hands on a beat. */
    Beatmatch,
    /** The outgoing title is cut on a bar and rings out in an echo while the next one starts on that beat. */
    Echo,
    /** The two cross by their spectra: one thins out while the other opens up. */
    Filter,
    /** A title without a beat dissolves into a reverberation under which the next one rises. */
    Wash
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
    /** The title on repeat is mixed into its own start (see [AppSettings.ketmixLoops]). */
    val loop: Boolean = false,
    val stopAfterCurrent: Boolean = false,
    val exactAudio: Boolean = false,
    val skipSilence: Boolean = false,
    /** The listener wants the titles of an album mixed too. */
    val albumsMixed: Boolean = true,
    /** Levels, filters and effects tied to the music can be played. Without them only a trim or a plain fade is left. */
    val effects: Boolean = true,
    /** Colour of the previous transition (see [MixPlan.variant]), so that two in a row do not sound alike; -1 = none yet. */
    val lastVariant: Int = -1,
    /** The beats of the outgoing title can still be followed. False when it is too late to start in time with them. */
    val beats: Boolean = true
)

/** The transition the listener prefers; [Auto] lets Ketmix choose for each pair of titles. */
internal enum class MixStyle { Auto, Tempo, Echo, Filters, Fade;
    companion object { fun of(setting: Int): MixStyle = values().getOrElse(setting) { Auto } }
}

/** 0 = discreet, 1 = balanced, 2 = DJ. */
internal data class MixSettings(val intensity: Int = 1, val style: MixStyle = MixStyle.Auto) {
    /**
     * How far apart two tempos may be for the titles to be played in time. Listeners hear a
     * change of speed from about 5 %; each title is only stretched while it is the quiet one.
     */
    val tempoRange: Float get() = when (intensity) { 0 -> .05f; 2 -> .12f; else -> .08f }
    /**
     * The tempos meet half-way during a blend (see [DeckSync.ride]): each title is stretched by
     * about half of [tempoRange], under the 5 % listeners notice. Blends run up to 64 beats
     * (16 bars, about 30 s at 128 BPM), the length DJs and Apple Music favour.
     */
    val beatCounts: IntArray get() = when (intensity) { 0 -> intArrayOf(32, 16, 8); 2 -> intArrayOf(64, 32, 16, 8); else -> intArrayOf(64, 32, 16, 8) }
    /** Beats of singing the incoming title may start under, the outgoing voice softened meanwhile. */
    val vocalLeadBeats: Int get() = when (intensity) { 0 -> 0; 2 -> 16; else -> 8 }
    /**
     * The same, for titles that are not played in time: how long an incoming title that does not
     * sing yet may rise under the last sung words, the outgoing voice stepping back.
     */
    val vocalLeadMs: Long get() = when (intensity) { 0 -> 0L; 2 -> 10_000L; else -> 6_000L }
    /** Longest crossing of two titles that are not played in time. */
    val crossfadeMs: Long get() = when (intensity) { 0 -> 5_000L; 2 -> 11_000L; else -> 8_000L }
    /** How much of an instrumental ending may be left out to mix earlier. Discreet never leaves anything out. */
    val skippableOutroMs: Long get() = when (intensity) { 0 -> 0L; 2 -> 45_000L; else -> 16_000L }
    /** An echo is a statement: the discreet setting does without. */
    val echo: Boolean get() = intensity >= 1
    /** Cutting a title that ends at full level is left to the DJ setting. */
    val cutsFullEndings: Boolean get() = intensity >= 2
}

internal data class MixPlan(
    val kind: MixKind,
    /** Position in the outgoing title where the incoming one becomes audible. */
    val outMs: Long = 0,
    /** Position in the incoming title at that moment. */
    val inMs: Long = 0,
    /** How long both are heard, on the outgoing title's clock. */
    val overlapMs: Long = 0,
    /** Positions of the incoming title per position of the outgoing one while both play (1 = same tempo). */
    val rate: Float = 1f,
    /** Outgoing position where the bass passes to the incoming title; -1 = no bass swap. */
    val swapMs: Long = -1,
    /** Level of the incoming title during the overlap, so that it does not jump out. */
    val gain: Float = 1f,
    /** Length of a beat of the outgoing title, when the transition is tied to its beats. */
    val beatMs: Float = 0f,
    val reason: String = "",
    /** Which of the two colours of its kind the transition takes; they alternate. */
    val variant: Int = 0,
    /** The two keys do not go together: the overlap is kept short and thin. */
    val clash: Boolean = false,
    /** Share of the overlap during which the outgoing title keeps its own level (it is already dying away). */
    val hold: Float = 0f,
    /** The next title starts on the beat that follows the last note. */
    val onBeat: Boolean = false,
    /** What was measured, for the listener to read: 0 or [MusicalKey.UNREAD] when unknown. */
    val fromBpm: Float = 0f,
    val toBpm: Float = 0f,
    val fromKey: Int = MusicalKey.UNREAD,
    val toKey: Int = MusicalKey.UNREAD,
    /** The title mixes into its own start: it is on repeat. */
    val loop: Boolean = false,
    /** The mix starts where a phrase of the outgoing title starts. */
    val onPhrase: Boolean = false,
    /** Outgoing position where its last sung words end, when the blend starts under them; -1 otherwise. */
    val sungUntilMs: Long = -1,
    /**
     * Outgoing position where its own sound is cut, when not at [outMs] (-1): an echo after a last
     * note at full level lets the note end, then rings on past the end of the title.
     */
    val cutMs: Long = -1,
    /** The transition rings on past the end of the outgoing title (its echo is played out after its last sample). */
    val tail: Boolean = false
) {
    val mixes: Boolean get() = kind != MixKind.None
    /** Position in the incoming title once the outgoing one is gone. */
    val inEndMs: Long get() = inMs + (overlapMs * rate).roundToLong()
    /** The transition follows the beats of the outgoing title. */
    val beatTied: Boolean get() = kind == MixKind.Beatmatch || kind == MixKind.Echo
}

internal object TransitionPlanner {
    private const val TRIM_OVERLAP_MS = 240L
    private const val MIN_TITLE_MS = 30_000L
    private const val MIN_CROSSFADE_MS = 2_500L
    /** An echo rings for four beats after the cut. */
    private const val ECHO_BEATS = 4
    /** The incoming voice still arrives this long after a crossing that started under the outgoing one. */
    private const val VOICE_GAP_MS = 1_500L
    /** Below the confidence needed to mix in time, a tempo still gives an echo its length. */
    private const val RING_CONFIDENCE = .25f
    /** The length of beat of an echo when no tempo could be read (120 BPM). */
    private const val RING_BEAT_MS = 500f
    /** How long an echo may ring past the end of the outgoing title. */
    internal const val MAX_TAIL_MS = 6_000L

    fun plan(out: MixFacts, incoming: MixFacts, context: MixContext, settings: MixSettings = MixSettings()): MixPlan {
        if (context.exactAudio) return MixPlan(MixKind.None, reason = "exact audio")
        if (context.repeatOne && !context.loop) return MixPlan(MixKind.None, reason = "repeat one")
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
        val clash = MusicalKey.relation(a.key, b.key) == MusicalKey.Relation.Clash
        val variant = if (context.lastVariant == 0) 1 else 0
        val beats = context.effects && context.beats && !context.skipSilence
        fun measured(plan: MixPlan) = checked(plan.copy(fromBpm = if (a.hasBeat) a.bpm else 0f, toBpm = if (b.hasBeat) b.bpm else 0f,
            fromKey = a.key, toKey = b.key, loop = context.loop), out, incoming)

        // A plain fade was asked for: the same lengths and care for the singing, no effect.
        if (settings.style == MixStyle.Fade) return plan(out, incoming, context.copy(effects = false), settings.copy(style = MixStyle.Auto))
        // 1. Both in time, the bass changing hands: the finest transition, when the tempos allow it.
        if (beats && a.hasBeat && b.hasBeat && settings.style != MixStyle.Echo && settings.style != MixStyle.Filters) {
            val rate = BeatAnalysis.foldedRatio(a.bpm, b.bpm)
            if (abs(rate - 1f) <= settings.tempoRange) {
                beatmatch(a, b, out, incoming, settings, rate, gain, end, start, sungUntil, clash, variant, context.loop)
                    ?.let { blend -> measured(blend).takeIf { it.mixes }?.let { return it } }
            }
        }
        // 2. A last note at full level is never covered: the next title starts right after it, on the beat
        // when there is one, and (unless discreet) the last note rings on in an echo over that start.
        val fullEnding = a.hardEnding && !a.fadeOut
        val echoAllowed = settings.style == MixStyle.Echo || (settings.style == MixStyle.Auto && settings.echo)
        // Ringing out leaves the whole title heard: only the plain styles do without it.
        val ringAllowed = echoAllowed || (settings.style == MixStyle.Tempo && settings.echo)
        fun ending(): MixPlan {
            val trim = trim(a, b, out, end, start, gain, beats)
            if (!ringAllowed || !context.effects || !context.beats || context.loop) return measured(trim)
            return measured(ringOut(a, trim, end, sungUntil)).takeIf { it.mixes } ?: measured(trim)
        }
        if (fullEnding && !(settings.cutsFullEndings && beats && a.hasBeat)) return ending()
        // 3. A way out on the beat that needs no common tempo (asked for, or left to Ketmix).
        if (beats && echoAllowed && a.hasBeat && !a.fadeOut) {
            echo(a, b, settings, end, start, sungUntil, gain)?.let { cut -> measured(cut).takeIf { it.mixes }?.let { return it } }
        }
        if (fullEnding) return ending()

        // 4. The ending fades or rings out: the next title comes up under it, within that ending.
        var fade = settings.crossfadeMs
        val tail = end - a.fallMs
        fade = fade.coerceAtMost(max(MIN_CROSSFADE_MS, tail))
        // The crossing stays in the part of the ending where nobody sings, when there is one.
        if (sungUntil != null) fade = fade.coerceAtMost(max(MIN_CROSSFADE_MS, end - sungUntil))
        // An incoming title that sings at once gets a shorter one, so that two voices do not overlap for long.
        val sungFrom = incoming.vocalStartMs
        if (sungFrom != null && (sungUntil == null || sungUntil > end - fade) && sungFrom - start < fade / 2)
            fade = fade.coerceAtMost(max(MIN_CROSSFADE_MS, (sungFrom - start) * 2))
        // An incoming title that does not sing for a while rises under the last sung words, the outgoing
        // voice stepping back: no two voices meet, and a title sung to its end is not crossed in 2.5 s.
        if (context.effects && sungUntil != null && sungFrom != null && settings.vocalLeadMs > 0) {
            val under = min(settings.crossfadeMs, max(0L, end - sungUntil) + settings.vocalLeadMs)
            val room = min(under, sungFrom - start - VOICE_GAP_MS)
            if (room > fade) fade = room
        }
        val reason = if (a.hasBeat && b.hasBeat) "tempos too far apart" else "no steady beat"
        if (!context.effects) return measured(MixPlan(MixKind.Crossfade, outMs = end - fade, inMs = start, overlapMs = fade, gain = gain, reason = reason))
        // While the title dies away by itself its level is left alone; ours only closes the end.
        val hold = if (end - fade >= a.fallMs - 400L) .55f else .2f
        val kind = if (!a.hasBeat && !a.fadeOut && settings.style != MixStyle.Filters) MixKind.Wash else MixKind.Filter
        return measured(MixPlan(kind, outMs = end - fade, inMs = start, overlapMs = fade, gain = gain, reason = reason,
            variant = variant, clash = clash, hold = hold, sungUntilMs = sungUntil?.takeIf { it > end - fade } ?: -1))
    }

    /**
     * After a last note at full level: the next title starts where a trim would start it (on the
     * beat when there is one), the outgoing title plays to its very end and its last beat rings
     * on in an echo, played out past the end of the title, over the first seconds of the next one.
     */
    private fun ringOut(a: SegmentAnalysis, trim: MixPlan, end: Long, sungUntil: Long?): MixPlan {
        // An echo needs a length of beat; one that was read without full confidence still gives one.
        val beat = if (a.bpm > 0f && a.confidence >= RING_CONFIDENCE) pulse(a) else RING_BEAT_MS
        val ring = (ECHO_BEATS * beat).roundToLong()
        val cut = max(end, trim.outMs)
        val overlap = cut + ring - trim.outMs
        return trim.copy(kind = MixKind.Echo, overlapMs = overlap, beatMs = beat, cutMs = cut, tail = true,
            reason = "hard ending", sungUntilMs = sungUntil?.takeIf { it > trim.outMs } ?: -1)
    }

    private fun trim(a: SegmentAnalysis, b: SegmentAnalysis, out: MixFacts, end: Long, start: Long, gain: Float, beats: Boolean): MixPlan {
        val lead = min(40L, start)
        if (beats && a.hasBeat && b.hasBeat) {
            // The first beat of the next title lands where the outgoing one would have had its next beat.
            val one = a.beatAtOrAfter(end - a.beatMs * .35)
            val firstBeat = b.beatAtOrAfter(start - 20.0)
            val outMs = (one - (firstBeat - start) - lead).roundToLong()
            if (outMs >= end - a.beatMs.toLong() && outMs <= end + 300L && outMs + TRIM_OVERLAP_MS <= out.durationMs - 150L)
                return MixPlan(MixKind.Trim, outMs = outMs, inMs = start - lead, overlapMs = TRIM_OVERLAP_MS, gain = gain,
                    beatMs = a.beatMs, reason = "hard ending", onBeat = true)
        }
        return MixPlan(MixKind.Trim, outMs = end - TRIM_OVERLAP_MS / 2, inMs = start - lead, overlapMs = TRIM_OVERLAP_MS,
            gain = gain, reason = "hard ending")
    }

    /** A pulse between 80 and 160 BPM, whichever way the tempo was read: what bars are counted on. */
    private fun pulse(a: SegmentAnalysis): Float {
        var beat = a.beatMs
        while (60_000f / beat >= 160f) beat *= 2f
        while (60_000f / beat < 80f) beat /= 2f
        return beat
    }

    private fun echo(a: SegmentAnalysis, b: SegmentAnalysis, settings: MixSettings, end: Long, start: Long, sungUntil: Long?, gain: Float): MixPlan? {
        val beat = pulse(a)
        val ring = (ECHO_BEATS * beat).roundToLong()
        // The cut comes where the body of the title ends, so that its last beat is the one that rings.
        val bodyEnd = if (a.hardEnding) end else a.fallMs
        val earliest = max(a.startMs + beat.toLong(), sungUntil ?: a.startMs)
        val latest = min(bodyEnd + (beat / 2).toLong(), end - ring)
        if (latest < earliest) return null
        // On the first beat of a bar when bars are known, on a beat otherwise: the last one that leaves room for the echo.
        fun lastAtOrBefore(first: Double, period: Double) = first + floor((latest - first) / period + 1e-6) * period
        var outMs = if (a.barOffset >= 0) lastAtOrBefore(a.beatAtMs + a.barOffset * a.beatMs.toDouble(), a.beatMs * 4.0) else -1.0
        if (outMs < earliest) outMs = lastAtOrBefore(a.beatAtMs, a.beatMs.toDouble())
        if (outMs < earliest) return null
        // A phrase boundary up to four bars before is a better place to leave than any bar.
        val phrase = a.phraseStarts(maxOf(earliest.toDouble(), latest - beat * 16.0), latest.toDouble()).lastOrNull()
        if (phrase != null) outMs = phrase
        // What is left out after the cut is counted against what this setting may skip.
        if (end - outMs.roundToLong() > ring + max(settings.skippableOutroMs, 4_500L)) return null
        val inMs = (if (b.hasBeat) b.beatAtOrAfter(start - 20.0).roundToLong() else start).coerceAtLeast(0L)
        return MixPlan(MixKind.Echo, outMs = outMs.roundToLong(), inMs = inMs, overlapMs = min(ring, end - outMs.roundToLong()),
            gain = gain, beatMs = beat, reason = "echo", onPhrase = phrase != null)
    }

    private fun beatmatch(a: SegmentAnalysis, b: SegmentAnalysis, out: MixFacts, incoming: MixFacts, settings: MixSettings,
                          rate: Float, gain: Float, end: Long, start: Long, sungUntil: Long?, clash: Boolean, variant: Int,
                          loop: Boolean = false): MixPlan? {
        val beat = pulse(a)
        // The incoming title enters on its first beat, or on its first downbeat when that is the next beat.
        val firstBeat = b.beatAtOrAfter(start - 20.0)
        val firstBar = if (b.barOffset >= 0) b.barAtOrAfter(start - 20.0) else firstBeat
        val inMs = (if (firstBar - firstBeat <= b.beatMs * 1.05) firstBar else firstBeat).roundToLong().coerceAtLeast(0L)
        val sungFrom = incoming.vocalStartMs
        // Keys that do not go together are not left to rub against each other for long.
        // Keys that clash are not left to rub for long; a title over its own start neither, since the
        // same instruments slightly apart in time colour each other (comb filtering).
        val counts = if (clash || loop) settings.beatCounts.filter { it <= 16 }.toIntArray() else settings.beatCounts
        for ((index, count) in counts.withIndex()) {
            val overlap = (count * beat).roundToLong()
            val latest = end - overlap
            // The mix starts once the singing is over, or under its last bars with the voice softened;
            // without lyrics, where the level drops.
            // A title that ends sung on a last note at full level keeps its ending whole.
            val lead = if (a.hardEnding && !a.fadeOut) 0L else (settings.vocalLeadBeats * beat).roundToLong()
            val earliest = max(a.startMs + a.beatMs.toLong(), sungUntil?.let { it - lead } ?: a.fallMs)
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
            // The start of a phrase of the outgoing title, when one is within reach, is where a DJ begins:
            // the incoming title then arrives as the music turns a page, not in the middle of a sentence.
            val phrase = a.phraseStarts(earliest - a.beatMs / 2.0, latest + 1.0).minByOrNull { abs(it - target) }
            if (phrase != null) outMs = phrase
            // First beats of bars meet when both titles let their bars be told apart.
            else if (a.barOffset >= 0 && b.barOffset >= 0) {
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
                swapMs = swap.roundToLong(), gain = gain, beatMs = beat, reason = "$count beats", variant = variant, clash = clash,
                onPhrase = phrase != null, sungUntilMs = sungUntil?.takeIf { it > outMs.roundToLong() } ?: -1)
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
        !plan.mixes -> plan
        plan.outMs < 5_000L -> MixPlan(MixKind.None, reason = "no room before the end")
        plan.outMs + plan.overlapMs > out.durationMs + (if (plan.tail) MAX_TAIL_MS else 400L) -> MixPlan(MixKind.None, reason = "past the end")
        plan.inEndMs > incoming.durationMs - 5_000L -> MixPlan(MixKind.None, reason = "incoming too short")
        plan.overlapMs !in 1..45_000L -> MixPlan(MixKind.None, reason = "overlap out of range")
        else -> plan
    }
}

/** The words a transition is told in (French literals, translated where they are shown). */
internal object MixWords {
    /** What the status line says of a plan: the kind, and "en boucle" for a title mixed into itself. */
    fun describe(plan: MixPlan): String = if (plan.loop) "en boucle · ${label(plan)}" else label(plan)

    fun label(plan: MixPlan): String = when (plan.kind) {
        MixKind.Beatmatch -> "calé sur le tempo"
        MixKind.Echo -> "sortie en écho"
        MixKind.Filter -> "croisement de filtres"
        MixKind.Wash -> "dissolution"
        MixKind.Crossfade -> "fondu enchaîné"
        MixKind.Trim -> if (plan.onBeat) "enchaîné sur le temps" else "enchaînement sans silence"
        MixKind.None -> "sans mix"
    }

    /** Share of the overlap at which the bass changes hands, or the cut falls; -1 when nothing of the kind happens. */
    fun swapShare(plan: MixPlan): Float = when {
        plan.overlapMs <= 0L -> -1f
        plan.kind == MixKind.Beatmatch && plan.swapMs >= 0 -> ((plan.swapMs - plan.outMs).toFloat() / plan.overlapMs).coerceIn(0f, 1f)
        plan.kind == MixKind.Echo || plan.kind == MixKind.Trim -> 0f
        else -> -1f
    }

    /** What was measured of the two titles, for the listener who wants to know why a mix sounds the way it does. */
    fun measures(plan: MixPlan): String {
        val parts = ArrayList<String>(2)
        if (plan.fromBpm > 0f && plan.toBpm > 0f) parts += "${Math.round(plan.fromBpm)} → ${Math.round(plan.toBpm)} BPM"
        val from = MusicalKey.code(plan.fromKey); val to = MusicalKey.code(plan.toKey)
        if (from.isNotEmpty() && to.isNotEmpty()) parts += "$from → $to"
        return parts.joinToString(" · ")
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
