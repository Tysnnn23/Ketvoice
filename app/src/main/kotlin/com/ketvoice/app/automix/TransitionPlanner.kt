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
    Wash,
    /**
     * The tension of a DJ's build: over the last bars of a phrase the outgoing title thins out through a
     * rising filter while a breath of noise swells, then the next title drops in on the first beat of the
     * next phrase. Needs no common tempo and no change of speed.
     */
    Rise
}

/** What is known about one of the two titles of a transition. */
internal data class MixFacts(
    val durationMs: Long,
    /** The last seconds of the outgoing title, or the first seconds of the incoming one. */
    val analysis: SegmentAnalysis? = null,
    /** Incoming title: where the first sung line starts. */
    val vocalStartMs: Long? = null,
    /** Outgoing title: where the last sung line ends. */
    val vocalEndMs: Long? = null,
    /** Outgoing title: the exit the listener chose in the DJ editor (6.1), -1 = Ketmix's own. */
    val userExitMs: Long = -1,
    /** Incoming title: the entry the listener chose in the DJ editor (6.1), -1 = Ketmix's own. */
    val userEntryMs: Long = -1,
    /**
     * Where each line is sung in the part of the title that is mixed (6.9, « fondu sur les paroles »), from its
     * synchronised lyrics or else its separated voice; empty when unknown. See [LyricTimes].
     */
    val sungLines: List<LongRange> = emptyList(),
    /**
     * 8.4: where its separated voice sings in that part ([com.ketvoice.app.stems.VoiceActivity.passages]) beside its
     * lyrics; null when it was not separated. A « pont en boucle » ([LoopBridge]) reads both.
     */
    val voiceLines: List<LongRange>? = null
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
    val beats: Boolean = true,
    /** Speed the outgoing title plays at (a previous blend may have left it a little off its own). */
    val speed: Float = 1f,
    /** The last exit on the beat that needs no common tempo: 0 none yet, 1 an echo, 2 a build. They take turns. */
    val lastExit: Int = 0,
    /** How long both titles are heard, as the listener chose it for this pair in the DJ editor (6.1); -1 = Ketmix's. */
    val userOverlapMs: Long = -1,
    /** The kinds of the last transitions of this listening, oldest first (6.9, see [MixVariety]). */
    val recentKinds: List<MixKind> = emptyList(),
    /** The last transition was a « boucle » blend: two in a row would be heard as a trick (see [LoopBlend]). */
    val loopedLast: Boolean = false,
    /**
     * Windows 2.0 energy matching: the energy of the incoming title's start minus that of the outgoing title's end
     * (desktop `EnergyCurve`, 0..1 scale); NaN when unknown, as always on the phone.
     */
    val energyStep: Float = Float.NaN,
    /** Windows 2.0 « Ketmix apprend »: kinds of transition the listener keeps turning down; empty on the phone. */
    val avoid: Set<MixKind> = emptySet()
)

/** The transition the listener prefers; [Auto] lets Ketmix choose for each pair of titles. */
/**
 * Stored by ordinal (settings, pair choices): new styles are appended, never inserted. Cut, Drop and Voices: 7.6, see
 * [StyleCuts]; Bridge (« Pont en boucle »): 8.4, see [LoopBridge].
 */
internal enum class MixStyle { Auto, Tempo, Echo, Filters, Fade, Rise, Cut, Drop, Voices, Bridge;
    companion object { fun of(setting: Int): MixStyle = values().getOrElse(setting) { Auto } }
}

/** 0 = discreet, 1 = balanced, 2 = DJ. */
internal data class MixSettings(val intensity: Int = 1, val style: MixStyle = MixStyle.Auto,
                                  /** « Calage du tempo » (DJ editor, 6.9): false = no title is sped up or slowed down for this transition. */
                                  val tempoMatch: Boolean = true) {
    /**
     * How far from its own tempo a title may be played so that two titles meet in time (6.0.0 Beta 4, the
     * owner chose Apple Music's way: « Calage des temps »). The incoming title is given that speed while it
     * is still silent, plays the whole blend in time, then glides back to its own tempo in steps too small
     * to be heard, its pitch kept (see [TempoReturn]). Before, it kept that speed to its end, so only 2–4 %
     * were allowed.
     */
    val tempoRange: Float get() = if (!tempoMatch) 0f else when (intensity) { 0 -> .04f; 2 -> .08f; else -> .06f }
    /**
     * Blends run up to 64 beats (16 bars, about 30 s at 128 BPM), the length DJs and Apple Music favour.
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
    /**
     * Level the outgoing title is led down to where the two meet in a blend in time, when the incoming
     * title is the quieter of the two (see [BlendFit.outGain]); 1 = left as it is.
     */
    val outGain: Float = 1f,
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
    val tail: Boolean = false,
    /** How long the build of a [MixKind.Rise] lasts before [outMs], on the outgoing title's clock. */
    val buildMs: Long = 0,
    /**
     * "Séparer les voix" (5.9.1, experimental): the incoming title enters as its instrumental and its
     * voice joins later; the outgoing voice is taken down before the incoming one sings.
     */
    val stems: Boolean = false,
    /** Incoming position where its voice begins (-1 unknown), from its lyrics or from its separated voice. */
    val voiceFromMs: Long = -1,
    /**
     * The incoming voice sings alone over the outgoing title's instrumental until the bass changes
     * hands (5.11.0). Only set once both separations are on the decks, see [Mashup].
     */
    val mashup: Boolean = false,
    /**
     * Outgoing position (ms) from which its voice steps back when the blend starts under its last sung words,
     * when not at [outMs]: the end of the line being sung there (6.9), so that no line is faded halfway; -1 = [outMs].
     */
    val voiceDownMs: Long = -1,
    /** « Boucle » (6.9): the outgoing bar looped under the blend, from this position (µs) for [loopUs]; see [LoopBlend]. */
    val loopFromUs: Long = -1,
    val loopUs: Long = 0,
    /** The details the listener set in the DJ editor (6.9), see [MixShaping]; the default plays Ketmix's own. */
    val shape: MixShape = MixShape.DEFAULT,
    /**
     * 7.1: a sung line of each title is still heard over the other in this crossing (known from their lyrics or
     * separated voices): the outgoing title's mids, where its voice lives, step back (see [DeckAutomation.withMids]).
     */
    val voicesMeet: Boolean = false,
    /** 7.6: the listener's volume keyframes over the blend (voice, instruments, whole title of each), see [MixKeys]. */
    val keys: List<LevelKey>? = null,
    /** 7.6: a cut on the beat (« Coupe », « Sur le drop »): a [MixKind.Rise] without its build, see [StyleCuts]. */
    val cut: Boolean = false,
    /** 8.4 « Pont en boucle »: both decks' speed glides over the blend, see [LoopBridge]; null = speeds held. */
    val glide: TempoGlide? = null
) {
    val mixes: Boolean get() = kind != MixKind.None
    /** Position in the incoming title once the outgoing one is gone. */
    val inEndMs: Long get() = inMs + (overlapMs * rate).roundToLong()
    /** The transition follows the beats of the outgoing title. */
    val beatTied: Boolean get() = kind == MixKind.Beatmatch || kind == MixKind.Echo || kind == MixKind.Rise
}

internal object TransitionPlanner {
    private const val TRIM_OVERLAP_MS = 240L
    internal const val MIN_TITLE_MS = 30_000L
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
    /** Windows 2.0: an energy change between the two titles this large (on the 0..1 scale) shapes the way across. */
    internal const val ENERGY_STEP = .15f

    /** True when the listener chose a point or an overlap for this pair in the DJ editor. */
    fun listener(out: MixFacts, incoming: MixFacts, context: MixContext): Boolean =
        out.userExitMs >= 0 || incoming.userEntryMs >= 0 || context.userOverlapMs > 0

    fun plan(out: MixFacts, incoming: MixFacts, context: MixContext, settings: MixSettings = MixSettings()): MixPlan {
        // The rules that say these two titles are not to be mixed at all stand whatever the listener chose.
        if (context.exactAudio) return MixPlan(MixKind.None, reason = "exact audio")
        if (context.repeatOne && !context.loop) return MixPlan(MixKind.None, reason = "repeat one")
        if (context.stopAfterCurrent) return MixPlan(MixKind.None, reason = "sleep timer")
        if (context.sameAlbumInOrder && !context.albumsMixed) return MixPlan(MixKind.None, reason = "album in order")
        if (out.durationMs < MIN_TITLE_MS || incoming.durationMs < MIN_TITLE_MS) return MixPlan(MixKind.None, reason = "short title")
        val listener = listener(out, incoming, context)
        // 7.1: after a false ending or before a hidden title, Ketmix's own plans only look at what follows the silence.
        val restart = Endings.restarts(out.analysis)
        val auto = automatic(if (restart) out.copy(analysis = Endings.afterGap(out.analysis!!)) else out, incoming, context, settings, listener)
            .let { if (restart && it.mixes) it.copy(reason = it.reason + " · after a silence") else it }
        return if (listener) honour(auto, out, incoming, context, settings) else auto
    }

    private fun automatic(out: MixFacts, incoming: MixFacts, context: MixContext, settings: MixSettings, listener: Boolean): MixPlan {
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
        // A pair the listener set by hand in the DJ editor is mixed where they chose, joins or not.
        if (context.sameAlbumInOrder && !listener && gapless(out.durationMs, a, b)) return MixPlan(MixKind.None, reason = "gapless album")
        // An album without a steady beat played in order (a symphony, chamber music, a reading) is left as
        // it was sequenced: Apple Music plays such albums without transitions, and nothing here could be tied
        // to the music anyway.
        if (context.sameAlbumInOrder && !listener && !a.hasBeat && !b.hasBeat) return MixPlan(MixKind.None, reason = "album in order")
        val gain = 10f.pow((a.loudnessDb - b.loudnessDb) / 20f).coerceIn(.5f, 1f)
        // A fade is almost silent in its last quarter: the incoming title must be in before that.
        val end = if (a.fadeOut) min(a.soundEndMs, a.fallMs + (a.soundEndMs - a.fallMs) * 3 / 4) else a.soundEndMs
        val start = b.soundStartMs
        val sungUntil = out.vocalEndMs?.plus(300L)
        val relation = MusicalKey.relation(a.key, b.key)
        val clash = relation == MusicalKey.Relation.Clash
        val variant = if (context.lastVariant == 0) 1 else 0
        val beats = context.effects && context.beats && !context.skipSilence
        fun measured(plan: MixPlan) = checked(lyricAware(plan.copy(fromBpm = if (a.hasBeat) a.bpm else 0f, toBpm = if (b.hasBeat) b.bpm else 0f,
            fromKey = a.key, toKey = b.key, loop = context.loop), out, incoming, sungUntil), out, incoming)
            .let { CutBudget.keep(it, a, b, incoming, context.speed) }
            .let { if (it.mixes && LyricTimes.voicesMeet(it, out.sungLines, incoming.sungLines)) it.copy(voicesMeet = true) else it }

        // A plain fade was asked for: the same lengths and care for the singing, no effect.
        if (settings.style == MixStyle.Fade) return automatic(out, incoming, context.copy(effects = false), settings.copy(style = MixStyle.Auto), listener)
        // 7.6 « Relais des voix »: what « Calé sur le tempo » would play, the voices handed over.
        if (settings.style == MixStyle.Voices) return StyleCuts.handoff(automatic(out, incoming, context, settings.copy(style = MixStyle.Tempo), listener), incoming)
        // 8.4 « Pont en boucle »: played whenever this pair has the bars for it, else what « Calé sur le tempo » would play.
        if (settings.style == MixStyle.Bridge) return bridge(a, b, out, incoming, context, settings, gain, ::measured)
            ?: automatic(out, incoming, context, settings.copy(style = MixStyle.Tempo), listener)
        // 7.6 « Coupe » / « Sur le drop »: a cut on a phrase; a last note at full level is still only cut by the DJ setting.
        fun cutOn(drop: Boolean) = StyleCuts.plan(a, b, incoming, settings, end, start, sungUntil, gain, variant, drop)?.let(::measured)?.takeIf { it.cut }
        if (StyleCuts.cuts(settings.style) && beats && !context.loop && !(a.hardEnding && !a.fadeOut && !settings.cutsFullEndings))
            cutOn(settings.style == MixStyle.Drop)?.let { return it }
        // Left to Ketmix, a drop takes a turn among the ways out on the beat when the next title has a clear one (not twice running).
        fun dropTurn() = if (settings.style == MixStyle.Auto && settings.echo && beats && !context.loop && !a.fadeOut && context.lastExit != 2 &&
            !(context.energyStep <= -ENERGY_STEP) && MixKind.Rise !in context.avoid &&
            !MixVariety.tired(context.recentKinds, MixKind.Rise) && StyleCuts.dropAt(b, incoming) != null) cutOn(drop = true)?.takeIf { it.reason == "drop" } else null
        val echoAllowed = settings.style == MixStyle.Echo || (settings.style == MixStyle.Auto && settings.echo && MixKind.Echo !in context.avoid)
        val riseAllowed = settings.style == MixStyle.Rise || (settings.style == MixStyle.Auto && settings.echo && MixKind.Rise !in context.avoid)
        fun onTheBeat() = beatExit(a, b, context, settings, end, start, sungUntil, gain, variant, beats, echoAllowed, riseAllowed, ::measured)
        // 7.1: two titles too far apart change over as on the radio; a last note at full level still rings out whole.
        if (settings.style == MixStyle.Auto && !context.loop && !(a.hardEnding && !a.fadeOut))
            PairFit.radio(a, b, end, gain)?.let { p -> measured(p).takeIf { it.mixes }?.let { return it } }
        // Windows 2.0 energy matching: a clearly more intense next title drops in on its beat when it has a drop.
        if (settings.style == MixStyle.Auto && context.energyStep >= ENERGY_STEP && !(a.hardEnding && !a.fadeOut)) dropTurn()?.let { return it }
        // 0. Variety (6.9): after two blends in time running, a way out on the beat takes a turn when this pair has one.
        if (settings.style == MixStyle.Auto && MixVariety.tired(context.recentKinds, MixKind.Beatmatch) && !(a.hardEnding && !a.fadeOut))
            (dropTurn() ?: onTheBeat())?.let { return it }
        // 1. Both in time, the bass changing hands: the finest transition, when the tempos allow it.
        if (beats && BlendFit.steady(a, b) && settings.style != MixStyle.Echo && settings.style != MixStyle.Filters && settings.style != MixStyle.Rise &&
            !StyleCuts.cuts(settings.style) && !(settings.style == MixStyle.Auto && MixKind.Beatmatch in context.avoid)) {
            val rate = BeatAnalysis.foldedRatio(a.bpm, b.bpm)
            // Measured from the incoming title's own tempo: what it will sound like for the rest of it.
            if (abs(context.speed * rate - 1f) <= settings.tempoRange) {
                // A fade-out with clean bars before it: those bars are looped under the blend instead of the fade.
                if (!context.loopedLast && !context.loop) LoopBlend.plan(a, b, out, settings, rate, gain, end, sungUntil, clash, variant,
                    BlendFit.counts(settings, abs(context.speed * rate - 1f), clash, context.loop, BlendFit.levelGapDb(a, b)))
                    ?.let { blend -> measured(blend).takeIf { it.mixes }?.let { return it } }
                beatmatch(a, b, out, incoming, settings, rate, gain, end, start, sungUntil, clash, variant,
                    BlendFit.counts(settings, abs(context.speed * rate - 1f), clash, context.loop, BlendFit.levelGapDb(a, b)))
                    ?.let { blend -> measured(blend).takeIf { it.mixes }?.let { return it } }
            } else if (settings.style == MixStyle.Auto && LoopBridge.wanted(context.speed, rate, settings))
                // 8.4: too far apart for a blend in time: a « pont en boucle » when both titles have the bars for it.
                bridge(a, b, out, incoming, context, settings, gain, ::measured)?.let { return it }
        }
        // 2. A last note at full level is never covered: the next title starts right after it, on the beat
        // when there is one, and (unless discreet) the last note rings on in an echo over that start.
        val fullEnding = a.hardEnding && !a.fadeOut
        // Ringing out leaves the whole title heard: only the plain styles do without it.
        val ringAllowed = echoAllowed || (settings.style == MixStyle.Tempo && settings.echo)
        fun ending(): MixPlan {
            val trim = trim(a, b, out, end, start, gain, beats)
            if (!ringAllowed || !context.effects || !context.beats || context.loop) return measured(trim)
            return measured(ringOut(a, trim, end, sungUntil)).takeIf { it.mixes } ?: measured(trim)
        }
        if (fullEnding && !(settings.cutsFullEndings && beats && a.hasBeat)) return ending()
        // 3. A way out on the beat that needs no common tempo (asked for, or left to Ketmix): a build into
        // the next phrase, or an echo. Left to Ketmix, the two take turns from one transition to the next.
        (dropTurn() ?: onTheBeat())?.let { return it }
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
        // Keys that clash are not left to rub for long, even without a beat in common.
        if (clash) fade = fade.coerceAtMost(max(MIN_CROSSFADE_MS, settings.crossfadeMs / 2))
        // On a phrase (6.0.0 Beta 4): the crossing starts where a phrase of the outgoing title starts, when one
        // lies within a quarter of the planned crossing, so that the next title arrives as the music turns a page.
        var onPhrase = false
        if (a.hasBeat) {
            val planned = (end - fade).toDouble()
            val phrase = a.phraseStarts(planned - fade / 4.0, planned + fade / 4.0).minByOrNull { abs(it - planned) }
            if (phrase != null) {
                val longer = (end - phrase).roundToLong()
                // Never earlier for the sake of a phrase when that would put the incoming voice under the outgoing one.
                val voices = sungUntil != null && sungFrom != null && phrase < planned && phrase + (sungFrom - start) < sungUntil
                if (longer >= MIN_CROSSFADE_MS && !voices && (sungUntil == null || phrase >= sungUntil - settings.vocalLeadMs)) { fade = longer; onPhrase = true }
            }
        }
        val reason = if (a.hasBeat && b.hasBeat) "tempos too far apart" else "no steady beat"
        val kind = if (!a.hasBeat && !a.fadeOut && settings.style != MixStyle.Filters) MixKind.Wash else MixKind.Filter
        // Windows 2.0 « Ketmix apprend »: a kind the listener keeps turning down becomes a plain fade (only when left to Ketmix).
        val avoided = settings.style == MixStyle.Auto && kind in context.avoid
        if (!context.effects || avoided) return measured(MixPlan(MixKind.Crossfade, outMs = end - fade, inMs = start, overlapMs = fade, gain = gain, reason = reason, onPhrase = onPhrase))
        // While the title dies away by itself its level is left alone; ours only closes the end.
        val hold = if (end - fade >= a.fallMs - 400L) .55f else .2f
        return measured(MixPlan(kind, outMs = end - fade, inMs = start, overlapMs = fade, gain = gain, reason = reason,
            variant = variant, clash = clash, hold = hold, onPhrase = onPhrase, sungUntilMs = sungUntil?.takeIf { it > end - fade } ?: -1))
    }

    /** 8.4 « Pont en boucle » ([LoopBridge]) for this pair, as Ketmix may play it; null when it cannot. */
    private fun bridge(a: SegmentAnalysis, b: SegmentAnalysis, out: MixFacts, incoming: MixFacts, context: MixContext, settings: MixSettings,
                       gain: Float, measured: (MixPlan) -> MixPlan): MixPlan? {
        if (!context.effects || !context.beats || context.skipSilence || context.loop || context.loopedLast) return null
        return LoopBridge.plan(a, b, out, incoming, context.speed, settings, gain)?.let(measured)?.takeIf { it.mixes }
    }

    /** 3. of [automatic]: a build into the next phrase or an echo, taking turns; null when neither fits this pair. */
    private fun beatExit(a: SegmentAnalysis, b: SegmentAnalysis, context: MixContext, settings: MixSettings, end: Long, start: Long,
                         sungUntil: Long?, gain: Float, variant: Int, beats: Boolean, echoAllowed: Boolean, riseAllowed: Boolean,
                         measured: (MixPlan) -> MixPlan): MixPlan? {
        if (!beats || !(echoAllowed || riseAllowed) || !a.hasBeat || a.fadeOut) return null
        fun tryRise() = if (riseAllowed) rise(a, b, settings, end, start, sungUntil, gain, variant)?.let(measured)?.takeIf { it.mixes } else null
        fun tryEcho() = if (echoAllowed || settings.style == MixStyle.Rise) echo(a, b, settings, end, start, sungUntil, gain)?.let(measured)?.takeIf { it.mixes } else null
        val first = settings.style == MixStyle.Rise || (settings.style == MixStyle.Auto && context.lastExit == 1)
        return (if (first) tryRise() ?: tryEcho() else tryEcho() ?: tryRise())?.copy(variant = variant)
    }

    /**
     * « Fondu sur les paroles » (6.9): an entry is moved back to the start of the line it would cut into, and the
     * outgoing voice steps back from the end of the line being sung where the blend starts, never halfway into it.
     */
    private fun lyricAware(plan: MixPlan, out: MixFacts, incoming: MixFacts, sungUntil: Long?): MixPlan {
        if (!plan.mixes || plan.kind == MixKind.Trim) return plan
        val entered = LyricTimes.entry(plan, incoming.sungLines, sungUntil)
        if (entered.sungUntilMs <= entered.outMs || out.sungLines.isEmpty()) return entered
        return entered.copy(voiceDownMs = LyricTimes.voiceDownFrom(out.sungLines, entered.outMs))
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

    /**
     * A build into the next phrase: the cut falls on the first beat of a phrase (else of a bar) once the
     * singing is over, the build takes the 8 beats before it (16 with the DJ setting), and the next title
     * starts on its first downbeat. The outgoing title only rings on for two beats of reverberation.
     */
    private fun rise(a: SegmentAnalysis, b: SegmentAnalysis, settings: MixSettings, end: Long, start: Long, sungUntil: Long?,
                     gain: Float, variant: Int): MixPlan? {
        val beat = pulse(a)
        val build = ((if (settings.intensity >= 2) 16 else 8) * beat).roundToLong()
        // A drop needs something to drop into: the next title must start on a beat, and not well under the
        // level of the one that built up to it.
        if (!b.hasBeat || !BlendFit.carriesADrop(a, b)) return null
        val bodyEnd = if (a.hardEnding) end else a.fallMs
        // At most half of the build over the last sung words.
        val earliest = max(a.startMs + build, sungUntil?.plus(build / 2) ?: a.startMs).toDouble()
        val latest = min(bodyEnd + (beat / 2).toLong(), end - beat.toLong()).toDouble()
        if (latest < earliest) return null
        fun lastAtOrBefore(first: Double, period: Double) = first + floor((latest - first) / period + 1e-6) * period
        val phrase = a.phraseStarts(earliest, latest).lastOrNull()
        val cut = phrase ?: (if (a.barOffset >= 0) lastAtOrBefore(a.beatAtMs + a.barOffset * a.beatMs.toDouble(), a.beatMs * 4.0)
            else lastAtOrBefore(a.beatAtMs, a.beatMs.toDouble())).takeIf { it >= earliest } ?: return null
        // What is left out after the cut is counted against what this setting may skip.
        if (end - cut.roundToLong() > max(settings.skippableOutroMs, 4_500L)) return null
        // On its first downbeat, unless that is more than a beat away: the first beat will do.
        val firstBeat = b.beatAtOrAfter(start - 20.0)
        val firstBar = if (b.barOffset >= 0) b.barAtOrAfter(start - 20.0) else firstBeat
        val inMs = if (!b.hasBeat) start else (if (firstBar - firstBeat <= b.beatMs * 1.05) firstBar else firstBeat).roundToLong().coerceAtLeast(0L)
        val ring = (2 * beat).roundToLong()
        return MixPlan(MixKind.Rise, outMs = cut.roundToLong(), inMs = inMs, overlapMs = min(ring, max(1L, end - cut.roundToLong() + ring / 2)),
            gain = gain, beatMs = beat, reason = "rise", onPhrase = phrase != null, buildMs = build, variant = variant)
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
                          counts: IntArray): MixPlan? {
        val beat = pulse(a)
        // The incoming title enters on its first beat, or on its first downbeat when that is the next beat.
        val firstBeat = b.beatAtOrAfter(start - 20.0)
        val firstBar = if (b.barOffset >= 0) b.barAtOrAfter(start - 20.0) else firstBeat
        val inMs = (if (firstBar - firstBeat <= b.beatMs * 1.05) firstBar else firstBeat).roundToLong().coerceAtLeast(0L)
        val sungFrom = incoming.vocalStartMs
        // How long the two may be heard together is told by how well they fit: see BlendFit.counts.
        // Two voices never sing over each other: the blend starts under the last sung words only as far
        // as the incoming title stays without its voice.
        val lead = BlendFit.voiceLeadMs(a, settings, beat, sungFrom?.let { (it - inMs) / rate }, rate)
        // 7.1: played off its tempo by more than that, the incoming title meets no voice in the blend (see CutBudget).
        val stretched = abs(rate - 1f) > CutBudget.VOICE_STRETCH + 1e-4f
        for ((index, count) in counts.withIndex()) {
            val overlap = (count * beat).roundToLong()
            val latest = end - overlap
            // The mix starts once the singing is over, or under its last bars with the voice softened;
            // without lyrics, where the level drops.
            val earliest = max(a.startMs + a.beatMs.toLong(), sungUntil?.let { it - lead } ?: a.fallMs)
            if (earliest > latest) continue
            // The incoming voice should arrive once the outgoing title is mostly gone.
            if (sungFrom != null && (sungUntil == null || stretched)) {
                val voiceAfter = (sungFrom - inMs) / rate
                val share = if (stretched) 1f else if (index == counts.lastIndex) .4f else .6f
                if (voiceAfter < overlap * share) continue
            }
            val target = max(earliest, latest - CutBudget.skippableMs(a, settings)).toDouble()
            var outMs = a.beatAtOrAfter(target)
            if (outMs > latest) outMs = a.beatAtOrAfter(latest.toDouble()) - a.beatMs
            if (outMs < earliest - a.beatMs / 2 || outMs > latest + 1) continue
            // The start of a phrase of the outgoing title, when one is within reach, is where a DJ begins:
            // the incoming title then arrives as the music turns a page, not in the middle of a sentence.
            val phrase = a.phraseStarts(earliest - a.beatMs / 2.0, latest + 1.0).minByOrNull { abs(it - target) }
            // The incoming title's first bar opens that phrase, so that the bars of both meet there too.
            if (phrase != null) outMs = BlendFit.onPhrase(a, b, inMs, rate, phrase, earliest.toDouble(), latest.toDouble())
            // First beats of bars meet when both titles let their bars be told apart.
            else if (a.barOffset >= 0 && b.barOffset >= 0) {
                val inBar = BlendFit.barIndex(b, inMs.toDouble())
                var candidate = outMs
                for (shift in 0 until 4) {
                    if (candidate > latest + 1) break
                    if (BlendFit.barIndex(a, candidate) == inBar) { outMs = candidate; break }
                    candidate += a.beatMs
                }
            }
            // The bass changes hands in the middle, on the first beat of a bar.
            val swap = BlendFit.swapBeat(a, outMs + overlap / 2.0)
            return MixPlan(MixKind.Beatmatch, outMs = outMs.roundToLong(), inMs = inMs, overlapMs = overlap, rate = rate,
                swapMs = swap.roundToLong(), gain = gain, outGain = BlendFit.outGain(a, b), beatMs = beat, reason = "$count beats", variant = variant, clash = clash,
                onPhrase = phrase != null, sungUntilMs = sungUntil?.takeIf { it > outMs.roundToLong() } ?: -1)
        }
        return null
    }

    /** The shortest overlap a listener may choose. */
    internal const val MIN_USER_OVERLAP_MS = 1_000L
    /** The longest one (the planner's own limit). */
    internal const val MAX_USER_OVERLAP_MS = 45_000L

    /**
     * The points the listener chose in the DJ editor (6.1): [auto] is what Ketmix would have played; its exit,
     * its entry and its overlap are replaced by the listener's where they chose them, and its kind is kept when
     * it can be played from there.
     *
     * Ketmix's own exits never leave a title before its last sung part (5.11.0, see [automatic]). A point the
     * listener set is their explicit choice: it may lie before the last sung words, and it is honoured there —
     * that rule is lifted for listener-set points only, never for Ketmix's own. The outgoing voice is still
     * stepped back under the blend ([MixPlan.sungUntilMs]) so that two voices do not run over each other.
     *
     * What still holds: no exit before 5 s or past the end of the title, no entry that leaves less than 5 s of
     * the incoming title after the blend, an overlap within 1–45 s that fits in what is left of the outgoing
     * title, and, for a transition tied to the beat (in time, echo, build), both points on a beat of their
     * title's grid; a point outside any grid gets a transition that needs none.
     */
    private fun honour(auto: MixPlan, out: MixFacts, incoming: MixFacts, context: MixContext, settings: MixSettings): MixPlan {
        val a = out.analysis
        val b = incoming.analysis
        val userExit = out.userExitMs.takeIf { it >= 0 }
        val userEntry = incoming.userEntryMs.takeIf { it >= 0 }
        val userOverlap = context.userOverlapMs.takeIf { it > 0 }?.coerceIn(MIN_USER_OVERLAP_MS, MAX_USER_OVERLAP_MS)
        val beats = context.effects && context.beats && !context.skipSilence
        val tempoRate = if (a != null && b != null && a.hasBeat && b.hasBeat) BeatAnalysis.foldedRatio(a.bpm, b.bpm) else 0f
        val inTime = beats && tempoRate > 0f && abs(context.speed * tempoRate - 1f) <= settings.tempoRange
        val end = a?.let { if (it.fadeOut) min(it.soundEndMs, it.fallMs + (it.soundEndMs - it.fallMs) * 3 / 4) else it.soundEndMs }
            ?: (out.durationMs - 250L)

        // The kind: the listener's style for this pair when it can be played, else what Ketmix chose.
        val blend = if (context.effects) (if (a != null && !a.hasBeat && !a.fadeOut) MixKind.Wash else MixKind.Filter) else MixKind.Crossfade
        var kind = when {
            !context.effects -> if (auto.kind == MixKind.Trim && userOverlap == null) MixKind.Trim else MixKind.Crossfade
            settings.style == MixStyle.Fade -> MixKind.Crossfade
            settings.style == MixStyle.Filters -> MixKind.Filter
            // A bridge is tied to its own loop and entry: once the listener moves a point it is played as a blend in time.
            settings.style == MixStyle.Tempo || settings.style == MixStyle.Bridge -> if (inTime) MixKind.Beatmatch else blend
            settings.style == MixStyle.Echo -> if (beats && a?.hasBeat == true) MixKind.Echo else blend
            settings.style == MixStyle.Rise -> if (beats && a?.hasBeat == true && b?.hasBeat == true) MixKind.Rise else blend
            settings.style == MixStyle.Voices -> if (inTime) MixKind.Beatmatch else blend
            StyleCuts.cuts(settings.style) -> if (beats && a?.hasBeat == true && b?.hasBeat == true) MixKind.Rise else blend
            auto.mixes -> auto.kind
            inTime -> MixKind.Beatmatch
            else -> blend
        }
        if (kind == MixKind.Beatmatch && !inTime) kind = blend
        // An overlap asked for is a blend, not a cut.
        if (kind == MixKind.Trim && userOverlap != null) kind = blend
        val sameKind = auto.mixes && auto.kind == kind

        // The two points, before they are made to fit.
        var exit = userExit ?: if (auto.mixes) auto.outMs else end - (userOverlap ?: settings.crossfadeMs)
        var entry = userEntry ?: if (auto.mixes) auto.inMs else (b?.soundStartMs ?: 0L)
        // A transition tied to the beat needs both points on their title's grid: they are put on the nearest beat.
        if (kind == MixKind.Beatmatch || kind == MixKind.Echo || kind == MixKind.Rise) {
            if (a == null || !GridSnap.covers(a, exit)) kind = blend
            else exit = GridSnap.nearestBeat(a, exit.toDouble()).roundToLong()
        }
        if ((kind == MixKind.Beatmatch || kind == MixKind.Rise) && b != null && b.hasBeat) {
            if (GridSnap.covers(b, entry)) entry = GridSnap.nearestBeat(b, entry.toDouble()).roundToLong().coerceAtLeast(0L)
            else if (kind == MixKind.Beatmatch) kind = blend
        }
        val beat = if (a != null && a.bpm > 0f) pulse(a) else RING_BEAT_MS
        val rate = if (kind == MixKind.Beatmatch) (if (auto.kind == MixKind.Beatmatch) auto.rate else tempoRate) else 1f

        // How long both are heard.
        var build = 0L
        var overlap = when (kind) {
            MixKind.Trim -> TRIM_OVERLAP_MS
            MixKind.Echo -> (ECHO_BEATS * beat).roundToLong()
            MixKind.Rise -> {
                build = if (StyleCuts.cuts(settings.style)) 0L else if (auto.kind == MixKind.Rise) auto.buildMs else ((if (settings.intensity >= 2) 16 else 8) * beat).roundToLong()
                (if (build == 0L) beat else 2 * beat).roundToLong()
            }
            MixKind.Beatmatch -> GridSnap.bars(a!!, userOverlap ?: if (sameKind) auto.overlapMs else (32 * beat).roundToLong())
            else -> userOverlap ?: if (sameKind || (auto.mixes && auto.kind != MixKind.Trim && auto.kind != MixKind.Echo && auto.kind != MixKind.Rise))
                auto.overlapMs else settings.crossfadeMs
        }
        overlap = overlap.coerceIn(if (kind == MixKind.Trim) TRIM_OVERLAP_MS else MIN_USER_OVERLAP_MS / 4, MAX_USER_OVERLAP_MS)

        // The exit stays inside the title: after its first 5 s (and the build of a rise), before its very end.
        val earliest = 5_000L + build
        val room = out.durationMs + 400L
        exit = exit.coerceIn(earliest, maxOf(earliest, out.durationMs - 1_000L))
        when (kind) {
            // An echo or a build rings on for its own length; a shorter one is played when the title ends first.
            MixKind.Echo, MixKind.Rise -> overlap = min(overlap, maxOf(1L, out.durationMs - exit))
            MixKind.Beatmatch -> if (exit + overlap > room) overlap = GridSnap.barsWithin(a!!, room - exit)
            else -> if (exit + overlap > room) overlap = maxOf(MIN_USER_OVERLAP_MS / 4, room - exit)
        }
        if (exit + overlap > room) exit = maxOf(earliest, room - overlap)
        // The entry leaves the incoming title at least 5 s once the blend is over.
        val inLatest = incoming.durationMs - 5_000L - (overlap * rate).roundToLong() - 1L
        entry = entry.coerceIn(0L, maxOf(0L, inLatest))

        val sungUntil = out.vocalEndMs?.plus(300L)
        val swap = if (kind == MixKind.Beatmatch) a!!.beatAtOrAfter(exit + overlap / 2.0 - a.beatMs / 2.0).roundToLong() else -1L
        val hold = if (kind == MixKind.Filter || kind == MixKind.Wash) (if (a != null && exit >= a.fallMs - 400L) .55f else .2f) else 0f
        val gain = if (auto.mixes) auto.gain else if (a != null && b != null) 10f.pow((a.loudnessDb - b.loudnessDb) / 20f).coerceIn(.5f, 1f) else 1f
        val onPhrase = a?.phraseStarts(exit - 60.0, exit + 60.0)?.isNotEmpty() == true
        val plan = MixPlan(kind, outMs = exit, inMs = entry, overlapMs = overlap, rate = rate, swapMs = swap, gain = gain,
            outGain = if (kind == MixKind.Beatmatch && a != null && b != null) BlendFit.outGain(a, b) else 1f,
            beatMs = if (kind == MixKind.Beatmatch || kind == MixKind.Echo || kind == MixKind.Rise || kind == MixKind.Trim) beat else 0f,
            reason = "listener", variant = if (auto.mixes) auto.variant else if (context.lastVariant == 0) 1 else 0,
            clash = auto.clash || (a != null && b != null && MusicalKey.relation(a.key, b.key) == MusicalKey.Relation.Clash),
            hold = hold, onPhrase = onPhrase, buildMs = build, cut = kind == MixKind.Rise && build == 0L,
            sungUntilMs = if (kind == MixKind.Echo || kind == MixKind.Rise || kind == MixKind.Trim) -1L else sungUntil?.takeIf { it > exit } ?: -1L,
            fromBpm = if (a?.hasBeat == true) a.bpm else 0f, toBpm = if (b?.hasBeat == true) b.bpm else 0f,
            fromKey = a?.key ?: MusicalKey.UNREAD, toKey = b?.key ?: MusicalKey.UNREAD, loop = context.loop)
        return checked(if (settings.style == MixStyle.Voices) StyleCuts.handoff(plan, incoming) else plan, out, incoming)
    }

    /**
     * True when the end of one title and the start of the next leave no gap at all: the two were cut
     * from one piece of music.
     *
     * Either a last note at full level followed at once by a start at full level, or a sound that runs
     * on across the join under the level of the music (the applause of a concert, a held chord, rain):
     * the outgoing title is still heard at its very last sample, without having faded, and the next one
     * starts without any silence.
     */
    internal fun gapless(outDurationMs: Long, a: SegmentAnalysis, b: SegmentAnalysis): Boolean {
        val trailingSilence = outDurationMs - a.soundEndMs
        if (trailingSilence >= 200L || a.fadeOut) return false
        val immediateStart = b.soundStartMs - b.startMs < 120L
        return if (a.hardEnding) immediateStart && b.riseMs - b.soundStartMs < 500L else immediateStart
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

    fun label(plan: MixPlan): String = when {
        plan.stems && plan.reason.startsWith("voices") -> "relais des voix"
        plan.cut -> if (plan.reason == "drop") "coupe sur le drop" else "coupe sur le temps"
        else -> kindLabel(plan)
    }

    private fun kindLabel(plan: MixPlan): String = when (plan.kind) {
        MixKind.Beatmatch -> if (plan.glide != null) "pont en boucle" else if (plan.loopUs > 0L) "calé sur une boucle" else "calé sur le tempo"
        MixKind.Echo -> "sortie en écho"
        MixKind.Filter -> "croisement de filtres"
        MixKind.Wash -> "dissolution"
        MixKind.Rise -> "montée"
        MixKind.Crossfade -> "fondu enchaîné"
        MixKind.Trim -> if (plan.onBeat) "enchaîné sur le temps" else "enchaînement sans silence"
        MixKind.None -> "sans mix"
    }

    /** Share of the overlap at which the bass changes hands, or the cut falls; -1 when nothing of the kind happens. */
    fun swapShare(plan: MixPlan): Float = when {
        plan.overlapMs <= 0L -> -1f
        plan.kind == MixKind.Beatmatch && plan.swapMs >= 0 -> ((plan.swapMs - plan.outMs).toFloat() / plan.overlapMs).coerceIn(0f, 1f)
        plan.kind == MixKind.Echo || plan.kind == MixKind.Trim || plan.kind == MixKind.Rise -> 0f
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

    /**
     * End of the last sung line: its last word when words are timed, else where a rest line after it marks the
     * end of the singing (6.9), else a duration guessed from its length. See [LyricTimes.lines].
     */
    fun endMs(lines: List<MusicApi.LyricLine>, durationMs: Long): Long? = LyricTimes.lines(lines, durationMs).lastOrNull()?.last
}

/** When a voice may sing over the other title's instrumental (5.11.0). */
internal object Mashup {
    /** The incoming voice must be singing within this share of the blend, or the listener only hears an instrumental thinning out. */
    const val VOICE_WITHIN = .4f

    /**
     * Both titles in time and in keys that go together, the bass changing hands, and an incoming
     * voice that sings early in the blend. The separations themselves are checked by the engine.
     */
    fun fits(plan: MixPlan): Boolean {
        if (plan.kind != MixKind.Beatmatch || plan.swapMs < 0 || plan.clash || plan.loop) return false
        if (plan.fromKey == MusicalKey.UNREAD || plan.toKey == MusicalKey.UNREAD) return false
        if (kotlin.math.abs(plan.rate - 1f) > .06f || plan.overlapMs < 8_000L) return false
        if (plan.voiceFromMs < 0) return false
        val voiceAfterMs = (plan.voiceFromMs - plan.inMs) / plan.rate
        return voiceAfterMs <= plan.overlapMs * VOICE_WITHIN
    }
}

/** Where a title sings, from its lyrics and from its separated voice: the cautious one of the two. */
internal object VoiceFacts {
    /** The outgoing voice is over once both say so. */
    fun end(lyrics: Long?, separated: Long?): Long? = listOfNotNull(lyrics, separated).maxOrNull()
    /** The incoming voice starts as soon as either says so. */
    fun start(lyrics: Long?, separated: Long?): Long? = listOfNotNull(lyrics, separated).minOrNull()
}

/**
 * "File intelligente" (5.11.0, on unless switched off): in shuffle, the title that plays next is
 * the one, among the next few, that goes best with the end of the current one (tempo, then key).
 * Only titles Ketmix has already measured can be compared; a queue the listener ordered (shuffle
 * off) and a title they asked to hear next are never moved.
 */
internal object SmartQueue {
    /** How many of the coming titles are looked at. */
    const val WINDOW = 6

    /** 0 = a perfect match; larger = further apart. Null when one of the two was not measured. */
    fun distance(out: SegmentAnalysis?, into: SegmentAnalysis?): Float? {
        if (out == null || into == null || !out.hasBeat || !into.hasBeat) return null
        val tempo = kotlin.math.abs(BeatAnalysis.foldedRatio(out.bpm, into.bpm) - 1f)
        val key = when (MusicalKey.relation(out.key, into.key)) {
            MusicalKey.Relation.Same -> 0f
            MusicalKey.Relation.Close -> .01f
            MusicalKey.Relation.Unknown -> .03f
            MusicalKey.Relation.Clash -> .06f
        }
        return tempo + key
    }

    /**
     * Which of [candidates] (the coming titles, in order) should play next: the best match when it is
     * clearly better than the one already next, else 0 (nothing moves).
     */
    fun pick(out: SegmentAnalysis?, candidates: List<SegmentAnalysis?>): Int {
        val scores = candidates.map { distance(out, it) }
        val best = scores.withIndex().filter { it.value != null }.minByOrNull { it.value!! } ?: return 0
        if (scores.isEmpty()) return 0
        val first = scores[0]
        // An unmeasured next title keeps its place unless a measured one is a real match.
        val margin = if (first == null) best.value!! <= .04f else first - best.value!! >= .03f
        return if (best.index != 0 && margin) best.index else 0
    }

    /** Moves the best match right after the current title. Returns true when the queue changed. */
    fun reorder(current: com.ketvoice.app.data.Track, durationMs: Long): Boolean {
        val state = com.ketvoice.app.player.Player.state.value
        if (!com.ketvoice.app.data.AppSettings.ketmixSmartQueue.value || !state.shuffle || state.repeatOne) return false
        val coming = com.ketvoice.app.player.Player.automixUpcoming(WINDOW)
        if (coming.size < 2 || coming.first().index != state.index + 1) return false
        if (com.ketvoice.app.player.Player.pinnedNext(coming.first().entryId)) return false
        val out = AnalysisStore.outro(current.id, durationMs) ?: return false
        val choice = pick(out, coming.map { e -> AnalysisStore.intro(e.track.id, e.track.durationMs.takeIf { it > 0 } ?: 600_000L) })
        if (choice == 0) return false
        val from = coming[choice].index
        // Only a plain run of the queue is reordered (not across a repeat-all wrap).
        if (from <= state.index + 1) return false
        com.ketvoice.app.player.Player.moveQueue(from, state.index + 1)
        return true
    }
}
