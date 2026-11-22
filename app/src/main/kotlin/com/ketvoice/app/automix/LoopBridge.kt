package com.ketvoice.app.automix

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * The speed of the outgoing deck over a « pont en boucle » ([LoopBridge]), in positions of the outgoing title (ms):
 * [fromSpeed] at [fromMs], [toSpeed] at [untilMs], a straight line between. The incoming deck always plays
 * [MixPlan.rate] times as fast, so both stay on one beat grid whatever the master tempo is, and every position
 * of the plan (levels, filters, the loop, the bass swap) keeps its place.
 */
internal data class TempoGlide(val fromMs: Long, val untilMs: Long, val fromSpeed: Float, val toSpeed: Float) {
    /** The outgoing deck's speed at [outMs], the glide starting from [from] (the deck's speed when the mix starts). */
    fun speedAt(outMs: Long, from: Float = fromSpeed): Float {
        if (outMs <= fromMs || untilMs <= fromMs) return if (outMs <= fromMs) from else toSpeed
        if (outMs >= untilMs) return toSpeed
        val x = (outMs - fromMs).toFloat() / (untilMs - fromMs)
        return from + (toSpeed - from) * x
    }

    /** How far the master tempo moves per bar of [barMs] (outgoing positions), in BPM of a pulse of [pulseBpm] at speed 1. */
    fun bpmPerBar(pulseBpm: Float, barMs: Double, from: Float = fromSpeed): Float =
        if (untilMs <= fromMs) 0f else (abs(toSpeed - from) * pulseBpm * (barMs / (untilMs - fromMs))).toFloat()
}

/**
 * « Pont en boucle » (8.4, [MixStyle.Bridge]): two titles of any tempo, mixed in time. The outgoing title hands over
 * to four bars of its own that nobody sings in (known from its separated voice and its synchronised lyrics, never
 * guessed), looped on its beat grid; the incoming title enters on its earliest four bars without singing, its bass
 * cut; then one master tempo glides from the outgoing title's to the incoming one's, at most [MAX_BPM_PER_BAR] per
 * bar, both decks locked on the same beats (half and double tempos folded, see [BeatAnalysis.foldedRatio]). The bass
 * changes hands half-way through the glide and the loop leaves through a rising high-pass; what is left of the way to
 * the incoming title's own tempo (when the glide could not be long enough) is done afterwards by [TempoReturn].
 *
 * The only Ketmix transition that may play a title further than [MixSettings.tempoRange] off its tempo, always as a
 * glide, never a jump. The automatic mode takes it when the tempos are too far apart for a blend in time and both
 * titles have the bars it needs; otherwise nothing changes.
 */
internal object LoopBridge {
    const val LOOP_BARS = 4
    /** A loop or an entry may hold this share of singing at most (the tail of a word, a breath the separation hears). */
    const val MAX_SUNG = .03f
    /** The master tempo never moves faster than this, in BPM per bar. */
    const val MAX_BPM_PER_BAR = 2.5f
    /** The shortest bridge: two turns of the loop. */
    const val MIN_BARS = 8
    /** Two titles further apart than this (the incoming one played 25 % off its tempo at first) are not bridged. */
    const val MAX_GAP = .25f
    private const val MAX_OVERLAP_MS = 44_000L
    private const val MAX_BARS = 24
    private const val END_MARGIN_MS = 600L
    /** The automatic mode needs at least this gap: below it a blend in time already plays the pair. */
    private const val MIN_GAP = .005f

    /** Share of [fromMs]..[untilMs] covered by [lines]. */
    fun sungShare(fromMs: Double, untilMs: Double, lines: List<LongRange>): Float {
        if (untilMs <= fromMs) return 1f
        var sung = 0.0
        for (l in lines) sung += max(0.0, min(untilMs, l.last.toDouble()) - max(fromMs, l.first.toDouble()))
        return (sung / (untilMs - fromMs)).toFloat()
    }

    /** Where a title sings, from its lyrics and its separated voice together; null when neither is known. */
    fun voices(facts: MixFacts, outgoing: Boolean): List<LongRange>? {
        val lines = (facts.sungLines + facts.voiceLines.orEmpty()).distinct()
        val edge = if (outgoing) facts.vocalEndMs else facts.vocalStartMs
        if (lines.isEmpty() && edge == null && facts.voiceLines == null) return null
        return lines
    }

    /**
     * The start (ms) of the outgoing title's loop of [lengthMs]: the latest first beat of a bar (a phrase start
     * when one fits) after its last sung part, whose bars are clean (before its level falls) and leave [roomMs] of
     * the title after it, and in which at most [MAX_SUNG] is sung. Null when there is none, or when nobody knows
     * where the title sings.
     */
    fun loopStart(a: SegmentAnalysis, out: MixFacts, lengthMs: Double, roomMs: Long): Double? {
        val lines = voices(out, outgoing = true) ?: return null
        if (a.barOffset < 0 || a.bpm <= 0f) return null
        val lastSung = max(out.vocalEndMs?.toDouble() ?: Double.NEGATIVE_INFINITY, lines.maxOfOrNull { it.last.toDouble() } ?: Double.NEGATIVE_INFINITY)
        val earliest = max(a.startMs.toDouble(), lastSung - MAX_SUNG * lengthMs)
        val latest = min(a.fallMs - lengthMs, (out.durationMs - END_MARGIN_MS - roomMs).toDouble())
        if (latest < earliest) return null
        a.phraseStarts(earliest, latest).lastOrNull()?.takeIf { sungShare(it, it + lengthMs, lines) <= MAX_SUNG }?.let { return it }
        val bar = 4.0 * a.beatMs
        val first = a.beatAtMs + a.barOffset * a.beatMs.toDouble()
        var at = first + floor((latest - first) / bar + 1e-9) * bar
        while (at >= earliest - 1e-6) {
            if (sungShare(at, at + lengthMs, lines) <= MAX_SUNG) return at
            at -= bar
        }
        return null
    }

    /**
     * The incoming title's entry: the first beat of its earliest bar from which [lengthMs] (incoming positions) hold at
     * most [MAX_SUNG] of singing, within the intro [CutBudget] lets a mix skip and never after its first words.
     */
    fun entry(b: SegmentAnalysis, incoming: MixFacts, lengthMs: Double): Long? {
        val lines = voices(incoming, outgoing = false) ?: return null
        if (b.barOffset < 0 || b.bpm <= 0f) return null
        val bar = 4.0 * b.beatMs
        val first = b.barAtOrAfter(b.soundStartMs - 20.0)
        val skippable = CutBudget.IN_BARS * CutBudget.barMs(b)
        var at = first
        while (at - b.soundStartMs <= skippable + 1.0) {
            if (incoming.vocalStartMs?.let { at > it + 150L } == true) return null
            if (sungShare(at, at + lengthMs, lines) <= MAX_SUNG) return at.roundToLong().coerceAtLeast(0L)
            at += bar
        }
        return null
    }

    /** Bars the master tempo needs to go from [fromBpm] to [toBpm] at [MAX_BPM_PER_BAR]. */
    fun glideBars(fromBpm: Float, toBpm: Float): Int = ceil(abs(toBpm - fromBpm) / MAX_BPM_PER_BAR - 1e-4f).toInt().coerceAtLeast(0)

    /**
     * The bridge for this pair, or null when it cannot be played: no steady grids with bars, keys that clash, tempos
     * further apart than [MAX_GAP], no clean bars to loop or to enter on, or a glide that cannot bring the incoming title
     * within [MixSettings.tempoRange] of its own tempo before the outgoing one is gone. [speed] is the outgoing deck's.
     */
    fun plan(a: SegmentAnalysis, b: SegmentAnalysis, out: MixFacts, incoming: MixFacts, speed: Float, settings: MixSettings, gain: Float): MixPlan? {
        if (!settings.tempoMatch || settings.skippableOutroMs <= 0L || !BlendFit.steady(a, b) || a.confidence < LoopBlend.CONFIDENCE) return null
        if (a.barOffset < 0 || b.barOffset < 0 || MusicalKey.relation(a.key, b.key) == MusicalKey.Relation.Clash) return null
        val rate = BeatAnalysis.foldedRatio(a.bpm, b.bpm)
        if (abs(speed * rate - 1f) > MAX_GAP) return null
        val bar = CutBudget.barMs(a)
        val pulseBpm = (240_000.0 / bar).toFloat()
        val target = 1f / rate
        val needed = glideBars(pulseBpm * speed, pulseBpm * target)
        val loopMs = LOOP_BARS * bar
        val inMs = entry(b, incoming, loopMs * rate) ?: return null
        val longest = min(floor(MAX_OVERLAP_MS / bar).toInt(), MAX_BARS)
        // An even number of bars, so that half-way (the bass swap) falls on a bar; shorter ones when the title has no room.
        val first = min((max(MIN_BARS, needed) + 1) / 2 * 2, longest / 2 * 2)
        for (tried in first downTo MIN_BARS step 2) {
            val overlap = (tried * bar).roundToLong()
            val start = loopStart(a, out, loopMs, overlap) ?: continue
            // What the loop stands in for is a part of the ending left out: counted against what this setting may skip.
            if (CutBudget.musicEnd(a) - (start + loopMs) > settings.skippableOutroMs) continue
            val reach = min(abs(target - speed), MAX_BPM_PER_BAR * tried / pulseBpm)
            val end = speed + if (target >= speed) reach else -reach
            if (abs(end * rate - 1f) > settings.tempoRange + 1e-4f) continue
            return bridge(a, b, start, inMs, overlap, rate, gain, loopMs, TempoGlide(start.roundToLong(), start.roundToLong() + overlap, speed, end))
        }
        return null
    }

    private fun bridge(a: SegmentAnalysis, b: SegmentAnalysis, start: Double, inMs: Long, overlap: Long, rate: Float, gain: Float,
                       loopMs: Double, glide: TempoGlide): MixPlan {
        val outMs = start.roundToLong()
        // Half-way through the glide, on the first beat of a bar.
        val swap = BlendFit.swapBeat(a, outMs + overlap / 2.0).roundToLong()
        return MixPlan(MixKind.Beatmatch, outMs = outMs, inMs = inMs, overlapMs = overlap, rate = rate, swapMs = swap, gain = gain,
            outGain = BlendFit.outGain(a, b), beatMs = (CutBudget.barMs(a) / 4.0).toFloat(), reason = "loop bridge", variant = 0,
            onPhrase = a.phraseStarts(start - 1.0, start + 1.0).isNotEmpty(),
            loopFromUs = (start * 1000.0).roundToLong(), loopUs = (loopMs * 1000.0).roundToLong(), glide = glide)
    }

    /** True for a plan of the automatic mode's: tempos too far apart for a blend in time ([MixSettings.tempoRange]). */
    fun wanted(speed: Float, rate: Float, settings: MixSettings): Boolean =
        abs(speed * rate - 1f) > max(settings.tempoRange, MIN_GAP)

    /** The incoming deck's speed when [plan]'s incoming title reaches [inMs] (its own positions). */
    fun incomingSpeedAt(plan: MixPlan, inMs: Long, outSpeed: Float): Float {
        val glide = plan.glide ?: return outSpeed * plan.rate
        val outAt = plan.outMs + ((inMs - plan.inMs) / plan.rate.toDouble()).roundToLong()
        return glide.speedAt(outAt, outSpeed) * plan.rate
    }
}

/**
 * The speeds of both decks while a [TempoGlide] plays, from what the mix engine reads every tick: the outgoing deck
 * follows the glide in steps of at least [MIN_STEP], never closer than [EVERY_MS] apart (a change of speed flushes
 * the sound path, see [DeckMixProcessor]); the incoming one plays [MixPlan.rate] times as fast, nudged by
 * [correction] when the two drift apart (a change reaches each deck's sound after its own buffer).
 */
internal class GlideDriver(private val plan: MixPlan, private val glide: TempoGlide, startOutSpeed: Float, startInSpeed: Float) {
    private val from = startOutSpeed
    var outSpeed = startOutSpeed
        private set
    var inSpeed = startInSpeed
        private set
    private var changedAt = Long.MIN_VALUE / 2

    /** Speeds for this tick; true when either changed (the caller then gives both decks theirs). */
    fun step(nowMs: Long, outMs: Long, errorMs: Double, audible: Boolean): Boolean {
        if (nowMs - changedAt < EVERY_MS) return false
        val wanted = glide.speedAt(outMs, from)
        val out = if (abs(wanted - outSpeed) >= MIN_STEP || (wanted == glide.toSpeed && wanted != outSpeed)) wanted else outSpeed
        val corrected = (out * plan.rate * (1.0 - correction(errorMs, audible))).toFloat()
        val into = if (out != outSpeed || abs(corrected - inSpeed) >= MIN_STEP / 2) corrected else inSpeed
        if (out == outSpeed && into == inSpeed) return false
        outSpeed = out; inSpeed = into; changedAt = nowMs
        return true
    }

    companion object {
        const val EVERY_MS = 250L
        const val MIN_STEP = .001f
        /**
         * While both are heard: every step of the glide reaches each deck after its own buffer, so the two drift a
         * little at each step (a few ms per second of glide); a closer, gentler hold than a blend at a fixed speed
         * needs, never more than [MAX_NUDGE] off the glide.
         */
        const val DEAD_BAND_MS = 6.0
        const val STRENGTH = .0005
        const val MAX_NUDGE = .01

        /** The share by which the incoming deck is slowed (+) or sped up (−) to catch up [errorMs] (see [DeckSync.correction] before it is heard). */
        fun correction(errorMs: Double, audible: Boolean): Double = when {
            !audible -> DeckSync.correction(errorMs, audible = false)
            abs(errorMs) < DEAD_BAND_MS -> 0.0
            else -> (errorMs * STRENGTH).coerceIn(-MAX_NUDGE, MAX_NUDGE)
        }
    }
}
