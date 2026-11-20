package com.ketvoice.app.automix

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A value that changes over the positions of a title: straight lines between its points, flat outside them. */
internal class Curve(private val timesUs: LongArray, private val values: FloatArray) {
    init { require(timesUs.size == values.size && timesUs.isNotEmpty()) }
    private var hint = 0

    fun at(timeUs: Long): Float {
        val last = timesUs.size - 1
        if (timeUs <= timesUs[0]) return values[0]
        if (timeUs >= timesUs[last]) return values[last]
        // Positions are asked in order; the last segment is almost always still the right one.
        var i = hint
        if (i >= last || timesUs[i] > timeUs) i = 0
        while (i < last - 1 && timesUs[i + 1] <= timeUs) i++
        hint = i
        val span = timesUs[i + 1] - timesUs[i]
        if (span <= 0L) return values[i + 1]
        val t = (timeUs - timesUs[i]).toFloat() / span
        return values[i] + (values[i + 1] - values[i]) * t
    }

    val endUs: Long get() = timesUs.last()

    companion object {
        fun flat(value: Float) = Curve(longArrayOf(0L), floatArrayOf(value))

        /** [shape] maps progress 0..1 to a value; it is sampled finely enough to sound continuous. */
        fun shaped(fromUs: Long, toUs: Long, steps: Int = 32, shape: (Float) -> Float): Pair<LongArray, FloatArray> {
            val times = LongArray(steps + 1) { fromUs + (toUs - fromUs) * it / steps }
            val values = FloatArray(steps + 1) { shape(it.toFloat() / steps) }
            return times to values
        }

        fun join(vararg parts: Pair<LongArray, FloatArray>): Curve {
            val times = ArrayList<Long>(); val values = ArrayList<Float>()
            for ((t, v) in parts) for (i in t.indices) {
                if (times.isNotEmpty() && t[i] <= times.last()) continue
                times += t[i]; values += v[i]
            }
            return Curve(times.toLongArray(), values.toFloatArray())
        }
    }
}

/**
 * What one deck does to its sound during a transition, by position in its own title:
 * its level, a high-pass and a low-pass filter (0 = open), how much of the filtered sound
 * is heard directly ([dry]) and how much is sent into an echo and into a reverb.
 * Being tied to positions rather than to the clock, it stays on the music whatever the
 * audio buffering is, and simply waits while playback is paused.
 */
internal class DeckAutomation(
    val gain: Curve,
    val highPassHz: Curve,
    val lowPassHz: Curve = Curve.flat(0f),
    val dry: Curve = Curve.flat(1f),
    val echo: Curve = Curve.flat(0f),
    val reverb: Curve = Curve.flat(0f),
    /** Time between two repeats of the echo; 0 = no echo. */
    val echoDelayUs: Long = 0L,
    val echoFeedback: Float = 0f,
    /** Q of the two filters: .707 is neutral, more gives a sweep its edge. */
    val resonance: Float = .7071f,
    /**
     * How much of the voice is taken down (0..1): the centre of the stereo image, between 220 Hz
     * and 4.5 kHz, where a lead vocal sits. The instruments on the sides and the bass stay.
     */
    val vocalCut: Curve = Curve.flat(0f),
    /** A breath of white noise added to the deck (0..1 of full scale), and the high-pass it goes through. */
    val noise: Curve = Curve.flat(0f),
    val noiseHz: Curve = Curve.flat(0f),
    /**
     * How much the deck plays its voice alone (0..1), from its separated voice (5.11.0): a voice over
     * the other title's instrumental. Without that separation the title plays whole.
     */
    val vocalSolo: Curve = Curve.flat(0f),
    /**
     * Level of the mids, in dB, around [MID_HZ] (6.0.0 Beta 4): the third band of a DJ's equaliser, between
     * the bass the high-pass hands over and the highs the sweeps open. Taken down where two titles would
     * rub (their chords and voices live there), more when their keys clash.
     */
    val midDb: Curve = Curve.flat(0f),
    /** 7.6: the listener's keyframes for this deck (see [MixKeys]); the music level is applied here, voice and instruments in the voice stage. */
    val levels: KeyLevels? = null,
    /** 7.6: the transition this deck plays its part of; the two decks of one share it and their sum is guarded ([MixBus]). */
    val pair: Any? = null
) {
    val endUs: Long get() = maxOf(maxOf(gain.endUs, highPassHz.endUs, lowPassHz.endUs), maxOf(dry.endUs, echo.endUs, reverb.endUs),
        maxOf(vocalCut.endUs, vocalSolo.endUs, midDb.endUs), levels?.endUs ?: 0L)
    val usesMids: Boolean get() = midDb.endUs > 0L || midDb.at(0L) != 0f
    val usesVocalCut: Boolean get() = vocalCut.endUs > 0L
    val usesVocalSolo: Boolean get() = vocalSolo.endUs > 0L
    val usesEcho: Boolean get() = echoDelayUs > 0L

    fun copyWith(vocalCut: Curve = this.vocalCut, vocalSolo: Curve = this.vocalSolo, midDb: Curve = this.midDb, pair: Any? = this.pair) =
        DeckAutomation(gain, highPassHz, lowPassHz, dry, echo, reverb, echoDelayUs, echoFeedback, resonance, vocalCut, noise, noiseHz, vocalSolo, midDb, levels, pair)
    val usesNoise: Boolean get() = noise.endUs > 0L
    val usesReverb: Boolean get() = reverb.endUs > 0L

    companion object {
        /** Bass below this goes to one deck at a time. */
        const val BASS_HZ = 300f
        private const val OPEN_HZ = 28f
        private const val TOP_HZ = 18_000f
        /** Both decks at this level where the bass changes hands: full enough, without summing past full scale. */
        private const val MEETING = .72f
        /** Between a straight and an equal-power crossfade: no hole in the middle, no clipping either. */
        private const val POWER = 1.47f
        private const val SETTLE_US = 8_000_000L
        private const val SWEEP_Q = 1.05f
        /** Where the highs of an incoming title start: above it, only cymbals and air. */
        private const val HIGHS_HZ = 1_400f

        val MUTE = DeckAutomation(Curve.flat(0f), Curve.flat(0f))

        /** Ketmix's own crossing, or the one the listener chose in the DJ editor (see [MixShaping]). */
        private fun fadeOut(x: Float, curve: VolumeCurve = VolumeCurve.Auto) =
            if (curve != VolumeCurve.Auto) MixShaping.fall(x, curve) else cos(PI / 2 * x).toFloat().coerceAtLeast(0f).pow(POWER)
        private fun fadeIn(x: Float, curve: VolumeCurve = VolumeCurve.Auto) =
            if (curve != VolumeCurve.Auto) MixShaping.rise(x, curve) else sin(PI / 2 * x).toFloat().coerceAtLeast(0f).pow(POWER)
        private fun ease(x: Float) = (.5 - .5 * cos(PI * x)).toFloat()
        private fun ease(x: Float, curve: VolumeCurve) = if (curve != VolumeCurve.Auto) MixShaping.rise(x, curve) else ease(x)
        private fun sweep(fromHz: Float, toHz: Float, x: Float) = exp(ln(fromHz) + (ln(toHz) - ln(fromHz)) * ease(x))
        private fun point(us: Long, value: Float) = longArrayOf(us) to floatArrayOf(value)

        /** Centre of the middle band of the equaliser. */
        const val MID_HZ = 1_000f
        /** How far the mids step back during a blend in time; further when the keys clash. */
        const val MID_DOWN_DB = -4f
        const val MID_CLASH_DB = -8f

        /** The title that leaves. */
        fun outgoing(plan: MixPlan): DeckAutomation = withKeys(withMids(Stems.outgoing(outgoingMix(plan), plan), plan, outgoing = true), plan, true).copyWith(pair = plan)

        /** 7.6 (voice76): the listener's volume keyframes join the deck's automation. */
        private fun withKeys(automation: DeckAutomation, plan: MixPlan, outgoing: Boolean): DeckAutomation {
            val levels = MixKeys.curves(plan, outgoing) ?: return automation
            return DeckAutomation(automation.gain, automation.highPassHz, automation.lowPassHz, automation.dry, automation.echo, automation.reverb,
                automation.echoDelayUs, automation.echoFeedback, automation.resonance, automation.vocalCut, automation.noise, automation.noiseHz,
                automation.vocalSolo, automation.midDb, levels, automation.pair)
        }

        /**
         * The three-band equaliser of a blend in time (6.0.0 Beta 4): before the bass changes hands the
         * incoming title rises with its mids held back, after it the outgoing one steps back the same way,
         * so that the two sets of chords and voices are never both at full. Other transitions are left as they are.
         */
        internal fun withMids(automation: DeckAutomation, plan: MixPlan, outgoing: Boolean): DeckAutomation {
            if (outgoing && plan.voicesMeet && (plan.kind == MixKind.Filter || plan.kind == MixKind.Wash)) return voiceMids(automation, plan)
            if (plan.kind != MixKind.Beatmatch || plan.swapMs < 0 || plan.overlapMs <= 0) return automation
            val depth = if (plan.clash) MID_CLASH_DB else MID_DOWN_DB
            fun at(outMs: Long): Long = if (outgoing) outMs * 1000L
                else ((plan.inMs + (outMs - plan.outMs) * plan.rate.toDouble()) * 1000.0).toLong()
            val start = at(plan.outMs); val swap = at(plan.swapMs); val end = at(plan.outMs + plan.overlapMs)
            if (swap <= start || end <= swap) return automation
            val curve = if (outgoing) Curve.join(point(start, 0f), point(swap, 0f), Curve.shaped(swap + 1, end, 16) { depth * ease(it) })
                else Curve.join(point(start, depth), point(swap, depth), Curve.shaped(swap + 1, end, 16) { depth * (1f - ease(it)) }, point(end + 1, 0f))
            return automation.copyWith(midDb = curve)
        }

        /** How far the outgoing mids step back when two voices meet in a crossing that is not in time (7.1). */
        const val MID_VOICES_DB = -7.5f

        /**
         * 7.1: two sung lines still meet in a crossing that is not in time: rather than two voices stacked, the
         * outgoing title's mids (where its voice and chords live) step back over the first quarter of the crossing.
         */
        private fun voiceMids(automation: DeckAutomation, plan: MixPlan): DeckAutomation {
            val start = plan.outMs * 1000L
            val down = start + plan.overlapMs * 250L
            if (down <= start) return automation
            return automation.copyWith(midDb = Curve.join(point(start, 0f), Curve.shaped(start + 1, down, 12) { MID_VOICES_DB * ease(it) }))
        }

        private fun outgoingMix(plan: MixPlan): DeckAutomation {
            val start = plan.outMs * 1000L
            val end = (plan.outMs + plan.overlapMs) * 1000L
            return when (plan.kind) {
                MixKind.Beatmatch -> if (plan.swapMs < 0) plainOut(plan, start, end) else withVoiceDown(blendOut(plan, start, end), plan)
                MixKind.Echo -> echoOut(plan, start, end)
                MixKind.Rise -> riseOut(plan, start, end)
                MixKind.Filter -> withVoiceDown(filterOut(plan, start, end), plan)
                MixKind.Wash -> withVoiceDown(washOut(start, end, plan.shape.volume), plan)
                else -> plainOut(plan, start, end)
            }
        }

        /** The title that arrives. Its positions run [MixPlan.rate] times faster than the outgoing clock. */
        fun incoming(plan: MixPlan): DeckAutomation = withKeys(withMids(Stems.incoming(incomingMix(plan), plan), plan, outgoing = false), plan, false).copyWith(pair = plan)

        private fun incomingMix(plan: MixPlan): DeckAutomation {
            fun position(outMs: Long) = ((plan.inMs + (outMs - plan.outMs) * plan.rate.toDouble()) * 1000.0).toLong()
            val start = plan.inMs * 1000L
            val end = position(plan.outMs + plan.overlapMs)
            // After the overlap the title comes back to its own level, slowly enough not to be noticed.
            val settle = Curve.shaped(end, end + SETTLE_US, 8) { plan.gain + (1f - plan.gain) * ease(it) }
            return when (plan.kind) {
                MixKind.Beatmatch -> if (plan.swapMs < 0) plainIn(plan, start, end, settle)
                    else blendIn(plan, start, end, position(plan.swapMs), settle)
                MixKind.Echo, MixKind.Rise -> {
                    // It comes in on the beat where the outgoing title is cut: at once, not faded (7.6: a cut keeps its kick's attack).
                    val attack = start + if (plan.cut) CUT_ATTACK_US else 30_000L
                    DeckAutomation(Curve.join(point(start, 0f), point(attack, plan.gain), point(end, plan.gain), settle), Curve.flat(0f))
                }
                MixKind.Filter -> filterIn(plan, start, end, settle)
                MixKind.Wash -> {
                    // Under the wash the title rises without its bass at first, so that the two do not blur.
                    val open = start + (end - start) * 55 / 100
                    DeckAutomation(Curve.join(Curve.shaped(start, end) { plan.gain * fadeIn(it, plan.shape.volume) }, settle),
                        Curve.join(point(0L, 220f), Curve.shaped(start, open, 16) { sweep(220f, OPEN_HZ, it) }, point(open + 1, 0f)))
                }
                else -> plainIn(plan, start, end, settle)
            }
        }

        /** When the blend starts under the last sung bars, the outgoing voice steps back over them. */
        private fun withVoiceDown(automation: DeckAutomation, plan: MixPlan): DeckAutomation {
            // 6.9: from the end of the line being sung where the blend starts (MixPlan.voiceDownMs), never halfway into it.
            val from = maxOf(plan.outMs, plan.voiceDownMs)
            if (plan.sungUntilMs <= from) return automation
            val start = from * 1000L
            // Over eight beats, or three seconds when the two titles are not in time.
            val over = if (plan.beatMs > 0f) (plan.beatMs * 8000f).toLong() else VOICE_DOWN_US
            val down = (start + over).coerceAtMost(maxOf(start + 1, plan.sungUntilMs * 1000L))
            return automation.copyWith(vocalCut = Curve.join(point(start, 0f), Curve.shaped(start + 1, down, 12) { VOICE_DOWN * ease(it) }))
        }
        /** The voice is taken down by this much: well under the incoming title, never cut off. */
        const val VOICE_DOWN = .8f
        private const val VOICE_DOWN_US = 3_000_000L

        private fun plainOut(plan: MixPlan, start: Long, end: Long) =
            DeckAutomation(Curve.join(Curve.shaped(start, end) { fadeOut(it, plan.shape.volume) }), crossBass(plan, start, end, start, end, true))

        private fun plainIn(plan: MixPlan, start: Long, end: Long, settle: Pair<LongArray, FloatArray>): DeckAutomation {
            val swap = ((plan.inMs + (plan.swapMs - plan.outMs) * plan.rate.toDouble()) * 1000.0).toLong()
            return DeckAutomation(Curve.join(Curve.shaped(start, end) { plan.gain * fadeIn(it, plan.shape.volume) }, settle),
                crossBass(plan, start, end, swap, end, false))
        }

        /**
         * A plain crossing the listener gave a bass swap (DJ editor, 6.9): the outgoing bass goes and the incoming
         * one comes at [swapUs] (a position of this deck); without one the high-pass stays open, as always.
         */
        private fun crossBass(plan: MixPlan, start: Long, end: Long, swapUs: Long, until: Long, outgoing: Boolean): Curve {
            if (plan.kind != MixKind.Crossfade || plan.swapMs < 0 || end - start < 4) return Curve.flat(0f)
            val swap = (if (outgoing) plan.swapMs * 1000L else swapUs).coerceIn(start + 2, until - 2)
            val half = MixShaping.bassHalfUs(plan, 150_000L, minOf(swap - start, until - swap) - 1)
            return if (outgoing) Curve.join(point(swap - half - 1, 0f), Curve.shaped(swap - half, swap + half, 16) { sweep(OPEN_HZ, BASS_HZ, it) })
            else Curve.join(point(0L, BASS_HZ), Curve.shaped(swap - half, swap + half, 16) { sweep(BASS_HZ, OPEN_HZ, it) }, point(swap + half + 1, 0f))
        }

        /**
         * Both titles in time: the outgoing one keeps its bass until the swap, then thins out.
         * Variant 0 lets it rise into the air (high-pass), variant 1 lets it sink (low-pass).
         */
        private fun blendOut(plan: MixPlan, start: Long, end: Long): DeckAutomation {
            val swap = (plan.swapMs * 1000L).coerceIn(start + 1, end - 1)
            val half = MixShaping.bassHalfUs(plan, (plan.beatMs * 500f).toLong().coerceIn(120_000L, 400_000L), minOf(swap - start, end - swap) / 2)
            val curve = plan.shape.volume
            // Led down a little further when the incoming title is the quieter one, so that the two meet level.
            val meeting = MEETING * plan.outGain
            val gain = Curve.join(
                Curve.shaped(start, swap, 24) { 1f - (1f - meeting) * beforeSwap(it, curve) },
                Curve.shaped(swap, end, 24) { meeting * fadeOut(it, curve) })
            val bassAway = Curve.shaped(swap - half, swap + half, 16) { sweep(OPEN_HZ, BASS_HZ, it) }
            // No sweep asked for: the bass goes and the rest simply fades.
            if (plan.shape.filter == FilterSweep.Off) return DeckAutomation(gain, Curve.join(point(swap - half - 1, 0f), bassAway))
            if (plan.variant == 1) {
                val sink = Curve.join(point(swap + half, 0f), point(swap + half + 1, TOP_HZ),
                    Curve.shaped(swap + half + 2, end, 24) { sweep(TOP_HZ, 1_400f, it) })
                return DeckAutomation(gain, Curve.join(point(swap - half - 1, 0f), bassAway), sink, resonance = SWEEP_Q)
            }
            val air = Curve.shaped(swap + half + 1, end, 24) { sweep(BASS_HZ, if (plan.clash) 2_400f else 1_100f, it) }
            return DeckAutomation(gain, Curve.join(point(swap - half - 1, 0f), bassAway, air), resonance = SWEEP_Q)
        }

        /**
         * 7.6: once it carries the bass the incoming title is the music: it reaches its full level within the first
         * [TAKE_OVER] of the rest of the blend (it rose over the whole of it before, a 2–3 LU hole while the outgoing
         * title, thinned, was already going). A curve the listener chose keeps its own course.
         */
        private fun afterSwap(x: Float, curve: VolumeCurve) = if (curve != VolumeCurve.Auto) ease(x, curve) else ease((x / TAKE_OVER).coerceAtMost(1f))
        private const val TAKE_OVER = .4f
        /** 7.6: the outgoing title, the only one with its bass until the swap, keeps its level longer and meets the other late. */
        private fun beforeSwap(x: Float, curve: VolumeCurve) = if (curve != VolumeCurve.Auto) ease(x, curve) else ease(x * x * x)

        private fun blendIn(plan: MixPlan, start: Long, end: Long, swapAt: Long, settle: Pair<LongArray, FloatArray>): DeckAutomation {
            val swap = swapAt.coerceIn(start + 1, end - 1)
            val half = MixShaping.bassHalfUs(plan, (plan.beatMs * 500f * plan.rate).toLong().coerceIn(120_000L, 400_000L), minOf(swap - start, end - swap) / 2)
            val curve = plan.shape.volume
            val gain = Curve.join(
                Curve.shaped(start, swap, 24) { plan.gain * MEETING * fadeIn(it, curve) },
                Curve.shaped(swap, end, 16) { plan.gain * (MEETING + (1f - MEETING) * afterSwap(it, curve)) },
                settle)
            val bass = Curve.join(
                point(0L, BASS_HZ),
                Curve.shaped(swap - half, swap + half, 16) { sweep(BASS_HZ, OPEN_HZ, it) },
                point(swap + half + 1, 0f))
            if (plan.variant != 0 || plan.shape.filter == FilterSweep.Off) return DeckAutomation(gain, bass)
            // Variant 0, the way DJs bring a title in: its highs first, then its mids, and its bass
            // last, on the swap. The high-pass comes down from the top of the mids to the bass line.
            val mids = (start + (swap - start) * 3 / 5).coerceAtMost(swap - half - 1)
            val layered = Curve.join(
                point(0L, HIGHS_HZ),
                Curve.shaped(start, mids, 16) { sweep(HIGHS_HZ, BASS_HZ, it) },
                point(swap - half, BASS_HZ),
                Curve.shaped(swap - half, swap + half, 16) { sweep(BASS_HZ, OPEN_HZ, it) },
                point(swap + half + 1, 0f))
            return DeckAutomation(gain, layered, resonance = SWEEP_Q)
        }

        /**
         * The last beat is caught in an echo, the title is cut on the bar and its repeats ring
         * out over the start of the next one: a way out that needs no common tempo.
         */
        private fun echoOut(plan: MixPlan, start: Long, end: Long): DeckAutomation {
            val beat = (plan.beatMs * 1000f).toLong().coerceIn(250_000L, 1_200_000L)
            // The title's own sound is cut here: where the next one starts, or after its last note.
            val cut = if (plan.cutMs >= 0) (plan.cutMs * 1000L).coerceIn(start, end - 1) else start
            val send = Curve.join(point(cut - beat, 0f), point(cut - beat * 3 / 4, 1f), point(cut, 1f), point(cut + 8_000L, 0f))
            val dry = Curve.join(point(cut, 1f), point(cut + 28_000L, 0f))
            val fadeFrom = cut + (end - cut) * 2 / 5
            val gain = Curve.join(point(fadeFrom, 1f), Curve.shaped(fadeFrom + 1, end, 16) { fadeOut(it) })
            return DeckAutomation(gain, Curve.flat(0f), dry = dry, echo = send, echoDelayUs = beat / 2, echoFeedback = .6f)
        }

        /**
         * The build: from [MixPlan.buildMs] before the cut a high-pass climbs from the bass to the top of
         * the mids, faster and faster, with an edge to it, while white noise swells through a filter that
         * rises with it. On the cut the title stops at once, leaving two beats of its reverberation.
         */
        private fun riseOut(plan: MixPlan, start: Long, end: Long): DeckAutomation {
            if (plan.cut) return cutOut(plan, start, end)
            val from = start - plan.buildMs * 1000L
            val climb = Curve.join(point(from, 0f), Curve.shaped(from + 1, start, 32) { sweep(OPEN_HZ, RISE_TOP_HZ, it * it) })
            val noise = Curve.join(point(from, 0f), Curve.shaped(from + 1, start, 24) { RISE_NOISE * it * it * it }, point(start + 15_000L, 0f))
            val noiseHz = Curve.join(point(from, RISE_NOISE_FROM_HZ), Curve.shaped(from + 1, start, 24) { sweep(RISE_NOISE_FROM_HZ, RISE_NOISE_TO_HZ, it) })
            val beat = (plan.beatMs * 1000f).toLong().coerceIn(250_000L, 1_200_000L)
            val send = Curve.join(point(start - beat, 0f), point(start, 1f), point(start + 10_000L, 0f))
            val dry = Curve.join(point(start, 1f), point(start + 20_000L, 0f))
            // The resonant filter lifts its cut-off by a few dB and the noise adds to the music: the title gives way a little meanwhile.
            val gain = Curve.join(point(from, 1f), Curve.shaped(from + 1, start, 16) { 1f - (1f - RISE_FLOOR) * it }, point(start, RISE_FLOOR),
                Curve.shaped(start + 1, end, 16) { RISE_FLOOR * fadeOut(it) })
            return DeckAutomation(gain, climb, dry = dry, reverb = send, resonance = RISE_Q, noise = noise, noiseHz = noiseHz)
        }
        /**
         * 7.6 « Coupe » / « Sur le drop »: the title stops on the beat over [CUT_US] (equal power against the incoming
         * attack, no click), and only its reverberation of the last beat rings on, fading over the overlap.
         */
        private fun cutOut(plan: MixPlan, start: Long, end: Long): DeckAutomation {
            val beat = (plan.beatMs * 1000f).toLong().coerceIn(250_000L, 1_200_000L)
            val send = Curve.join(point(start - beat, 0f), point(start - beat / 4, .8f), point(start, .8f), point(start + CUT_US, 0f))
            val dry = Curve.join(point(start - CUT_US / 3, 1f), Curve.shaped(start - CUT_US / 3 + 1, start + CUT_US * 2 / 3, 8) { fadeOut(it) })
            val gain = Curve.join(point(start, 1f), Curve.shaped(start + 1, maxOf(start + 2, end), 16) { fadeOut(it) })
            return DeckAutomation(gain, Curve.flat(0f), dry = dry, reverb = send)
        }
        private const val CUT_US = 12_000L
        private const val CUT_ATTACK_US = 6_000L
        private const val RISE_TOP_HZ = 2_200f
        private const val RISE_Q = 1.6f
        /** The noise at its loudest, a share of full scale: present under the music, never over it. */
        const val RISE_NOISE = .05f
        private const val RISE_FLOOR = .78f
        private const val RISE_NOISE_FROM_HZ = 900f
        private const val RISE_NOISE_TO_HZ = 7_500f

        /**
         * 7.6: the outgoing sweep keeps most of its body for the first half and goes late, the incoming one opens early,
         * so that the two are never thin at the same time (a 2–3 LU hole in the middle before).
         *
         * A crossing of spectra rather than of levels. Variant 0: the outgoing title rises into
         * the air while the next one grows from its low end. Variant 1: it sinks while the next
         * one comes down from the top. [MixPlan.hold] keeps its level while it fades by itself.
         */
        private fun filterOut(plan: MixPlan, start: Long, end: Long): DeckAutomation {
            val held = start + ((end - start) * plan.hold.coerceIn(0f, .85f)).toLong()
            val gain = Curve.join(point(held, 1f), Curve.shaped(held + 1, end, 24) { fadeOut(it, plan.shape.volume) })
            return if (plan.variant == 1)
                DeckAutomation(gain, Curve.flat(0f), Curve.join(point(start, 0f), point(start + 1, TOP_HZ),
                    Curve.shaped(start + 2, end, 32) { sweep(TOP_HZ, 260f, it * it) }), resonance = SWEEP_Q)
            else DeckAutomation(gain, Curve.join(point(start, 0f), Curve.shaped(start + 1, end, 32) { sweep(OPEN_HZ, 1_500f, it * it) }),
                resonance = SWEEP_Q)
        }

        private fun filterIn(plan: MixPlan, start: Long, end: Long, settle: Pair<LongArray, FloatArray>): DeckAutomation {
            val gain = Curve.join(Curve.shaped(start, end) { plan.gain * fadeIn(it, plan.shape.volume) }, settle)
            val opened = start + (end - start) * 4 / 5
            return if (plan.variant == 1)
                DeckAutomation(gain, Curve.join(point(0L, 1_200f), Curve.shaped(start, opened, 32) { sweep(1_200f, OPEN_HZ, sqrt(it)) }, point(opened + 1, 0f)),
                    resonance = SWEEP_Q)
            else DeckAutomation(gain, Curve.flat(0f),
                Curve.join(point(0L, 380f), Curve.shaped(start, opened, 32) { sweep(380f, TOP_HZ, sqrt(it)) }, point(opened + 1, 0f)),
                resonance = SWEEP_Q)
        }

        /** The title dissolves into its own reverberation, which then fades. */
        private fun washOut(start: Long, end: Long, curve: VolumeCurve): DeckAutomation {
            val length = end - start
            val send = Curve.join(point(start, 0f), Curve.shaped(start + 1, start + length * 35 / 100, 12, ::ease))
            val dry = Curve.join(point(start + length * 15 / 100, 1f),
                Curve.shaped(start + length * 15 / 100 + 1, start + length * 60 / 100, 16) { 1f - ease(it) })
            val gain = Curve.join(point(start + length / 2, 1f), Curve.shaped(start + length / 2 + 1, end, 24) { fadeOut(it, curve) })
            val thin = Curve.join(point(start, 0f), Curve.shaped(start + 1, start + length * 60 / 100, 16) { sweep(OPEN_HZ, 320f, it) })
            return DeckAutomation(gain, thin, dry = dry, reverb = send)
        }
    }
}

/**
 * Applies a [DeckAutomation] to interleaved 16-bit PCM: a level that moves sample by
 * sample (no zipper noise), two second-order filters whose cut-offs follow their curves,
 * an echo and a reverb fed after the filters.
 */
/**
 * The voices of a transition when "Séparer les voix" is on. Since 5.10.0 the voice is really
 * separated when the separation model was downloaded: each deck carries the instrumental of the
 * part it mixes ([DeckControl.stem], computed once per title by [com.ketvoice.app.stems.StemEngine]) and
 * [DeckAutomation.vocalCut] fades from the title to it. Without the model, Ketvoice cuts in real time the
 * centre of the stereo image between 220 Hz and 4.5 kHz, where a lead vocal sits (5.9.1):
 * - the incoming title enters as its instrumental, and its voice joins over two bars just before
 *   it sings (or at the bass swap, or two thirds into the blend, when that moment is unknown);
 * - the outgoing voice steps down before the incoming one sings, so two voices never overlap.
 * The bass already changes hands on one beat (the swap of the blends).
 */
internal object Stems {
    /** How far a band cut takes the voice down, without a separation: well under the music, never a hole. */
    const val DOWN = .85f
    /** The curves ask for the whole voice; with a separated instrumental it really leaves. */
    private const val ALL = 1f
    private const val MOVE_US = 2_000_000L

    private fun kinds(plan: MixPlan) = plan.stems && plan.kind in setOf(MixKind.Beatmatch, MixKind.Filter, MixKind.Wash, MixKind.Crossfade)

    private fun ease(x: Float) = (.5 - .5 * kotlin.math.cos(Math.PI * x)).toFloat()

    fun incoming(automation: DeckAutomation, plan: MixPlan): DeckAutomation {
        if (!kinds(plan)) return automation
        val start = plan.inMs * 1000L
        val end = plan.inEndMs * 1000L
        if (end <= start + MOVE_US) return automation
        val swap = if (plan.swapMs >= 0) ((plan.inMs + (plan.swapMs - plan.outMs) * plan.rate) * 1000L).toLong() else -1L
        if (plan.mashup && swap > start + MOVE_US) {
            // Its voice alone over the other title's instrumental, then all of it as the bass changes hands.
            val whole = (swap + MOVE_US / 2).coerceAtMost(end)
            val solo = Curve.join(Curve.shaped(start, whole - MOVE_US, 2) { 1f }, Curve.shaped(whole - MOVE_US + 1, whole, 12) { 1f - ease(it) })
            return automation.copyWith(vocalSolo = solo)
        }
        val natural = plan.voiceFromMs.takeIf { it > plan.inMs }?.let { it * 1000L - 300_000L }
        val releaseEnd = (natural ?: swap.takeIf { it > start } ?: (start + (end - start) * 2 / 3)).coerceIn(start + MOVE_US, end)
        val releaseStart = (releaseEnd - MOVE_US).coerceAtLeast(start + 1)
        val cut = Curve.join(Curve.shaped(start, releaseStart, 2) { ALL }, Curve.shaped(releaseStart + 1, releaseEnd, 12) { ALL * (1f - ease(it)) })
        return automation.copyWith(vocalCut = cut)
    }

    fun outgoing(automation: DeckAutomation, plan: MixPlan): DeckAutomation {
        // A looped bar has no voice, and its separation would follow the title's own positions rather than the loop (6.9).
        if (!kinds(plan) || plan.loopUs > 0L) return automation
        val start = plan.outMs * 1000L
        val end = (plan.outMs + plan.overlapMs) * 1000L
        if (plan.mashup && end > start + MOVE_US) {
            // Its voice leaves at once: the incoming voice sings over its instrumental.
            val from = (start - MOVE_US).coerceAtLeast(0L)
            return automation.copyWith(vocalCut = Curve.join(Curve.shaped(from, start, 12) { ALL * ease(it) }, Curve.shaped(start + 1, end, 2) { ALL }))
        }
        // Already stepping down under its last sung bars: that earlier move is kept.
        if (automation.usesVocalCut) return automation
        // When the incoming voice starts, on the outgoing clock; else halfway into the blend.
        val sings = plan.voiceFromMs.takeIf { it > plan.inMs }?.let { (plan.outMs + (it - plan.inMs) / plan.rate.toDouble()).toLong() * 1000L }
        val downEnd = (sings ?: (start + (end - start) / 2)).coerceIn(start + 1, end)
        val downStart = (downEnd - MOVE_US).coerceAtLeast(start)
        val cut = Curve.join(Curve.shaped(start, maxOf(start + 1, downStart), 2) { 0f },
            Curve.shaped(maxOf(start + 2, downStart + 1), maxOf(start + 3, downEnd), 12) { ALL * ease(it) })
        return automation.copyWith(vocalCut = cut)
    }
}

internal class DeckDsp(val sampleRate: Int, val channels: Int) {
    private val high = Biquad(channels)
    private val low = Biquad(channels)
    private val mids = Peak(channels)
    /** The band of the voice, on the centre of the image: high-passed then low-passed. */
    private val voiceHigh = Biquad(1)
    private val voiceLow = Biquad(1)
    private var gain = Float.NaN
    private var dry = 1f
    /** The listener's « Musique » keyframe level (7.6), ramped sample by sample like [gain]. */
    private var music = 1f
    private var echo: Echo? = null
    private var reverb: Reverb? = null
    private val frame = FloatArray(channels)
    private val pending = FloatArray(BLOCK * channels)
    /** 7.6: the share of full scale this deck leaves to the other one of the transition (see [MixBus]). */
    private val bus = MixBus.Deck()
    private val hiss = Biquad(1)
    private var seed = 0x2545F491

    fun reset() {
        high.clear(); low.clear(); voiceHigh.clear(); voiceLow.clear(); hiss.clear(); mids.clear()
        gain = Float.NaN; dry = 1f; music = 1f
        echo = null; reverb = null
        bus.leave()
    }

    /**
     * Reads all of [input] and writes the result to [output]. [startUs] is the position in
     * the title of the first frame. Without automation the sound passes through untouched.
     */
    fun process(input: ByteBuffer, output: ByteBuffer, startUs: Long, automation: DeckAutomation?,
                stem: com.ketvoice.app.stems.StemAudio? = null) {
        if (automation == null) {
            if (!gain.isNaN() || high.active || low.active || echo != null || reverb != null || hiss.active) reset()
            output.put(input)
            return
        }
        input.order(ByteOrder.LITTLE_ENDIAN); output.order(ByteOrder.LITTLE_ENDIAN)
        val repeats = if (automation.usesEcho) echo ?: Echo(sampleRate, channels).also { echo = it } else null
        val room = if (automation.usesReverb) reverb ?: Reverb(sampleRate, channels).also { reverb = it } else null
        repeats?.tune((automation.echoDelayUs * sampleRate / 1_000_000L).toInt(), automation.echoFeedback)
        val frames = input.remaining() / (2 * channels)
        var done = 0
        while (done < frames) {
            val block = minOf(BLOCK, frames - done)
            val blockUs = startUs + done * 1_000_000L / sampleRate
            val endUs = startUs + (done + block) * 1_000_000L / sampleRate
            val middleUs = (blockUs + endUs) / 2
            val target = automation.gain.at(endUs)
            if (gain.isNaN()) { gain = automation.gain.at(blockUs); dry = automation.dry.at(blockUs); music = automation.levels?.music?.at(blockUs) ?: 1f }
            val musicTarget = automation.levels?.music?.at(endUs) ?: 1f
            val musicStep = (musicTarget - music) / block
            val step = (target - gain) / block
            val dryTarget = automation.dry.at(endUs)
            val dryStep = (dryTarget - dry) / block
            high.follow(automation.highPassHz.at(middleUs), automation.resonance, sampleRate, highPass = true)
            low.follow(automation.lowPassHz.at(middleUs), automation.resonance, sampleRate, highPass = false)
            if (automation.usesMids) mids.follow(automation.midDb.at(middleUs), sampleRate) else mids.follow(0f, sampleRate)
            val voice = if (channels >= 2 && automation.usesVocalCut) automation.vocalCut.at(middleUs).coerceIn(0f, 1f) else 0f
            // The separated instrumental, where it covers this block: the voice is really taken out.
            val solo = if (channels == 2 && automation.usesVocalSolo && stem?.vocals != null) automation.vocalSolo.at(middleUs).coerceIn(0f, 1f) else 0f
            val stemAt = if ((voice > 0f || solo > 0f) && stem != null && channels == 2 && stem.rate == sampleRate)
                stem.frameAt(blockUs).takeIf { it >= 0 && stem.frameAt(endUs - 1) >= 0 } ?: -1 else -1
            val stemPcm = stem?.pcm
            val soloPcm = if (solo > 0f && stemAt >= 0) stem?.vocals else null
            if (voice > 0f && stemAt < 0) { voiceHigh.follow(220f, .7071f, sampleRate, highPass = true); voiceLow.follow(4_500f, .7071f, sampleRate, highPass = false) }
            // Without the separation, a band cut reaches [Stems.DOWN] at most.
            val bandVoice = if (stemAt >= 0) 0f else minOf(voice, Stems.DOWN)
            val send = if (repeats != null) automation.echo.at(middleUs) else 0f
            val wash = if (room != null) automation.reverb.at(middleUs) else 0f
            val breath = if (automation.usesNoise) automation.noise.at(middleUs) * 32_767f else 0f
            if (breath > 0f) hiss.follow(automation.noiseHz.at(middleUs), .7071f, sampleRate, highPass = true)
            for (i in 0 until block) {
                gain += step; dry += dryStep; music += musicStep
                for (c in 0 until channels) {
                    var x = input.short.toFloat()
                    if (stemAt >= 0 && stemPcm != null && voice > 0f) x += voice * (stemPcm[2 * (stemAt + i) + c] - x)
                    if (soloPcm != null) x += solo * (soloPcm[2 * (stemAt + i) + c] - x)
                    if (high.active) x = high.run(x, c)
                    if (low.active) x = low.run(x, c)
                    if (mids.active) x = mids.run(x, c)
                    frame[c] = x
                }
                if (bandVoice > 0f) {
                    // Mid and side: the voice lives in the middle; only its band is taken down there.
                    val mid = (frame[0] + frame[1]) * .5f
                    val side = (frame[0] - frame[1]) * .5f
                    val band = voiceLow.run(voiceHigh.run(mid, 0), 0)
                    val kept = mid - bandVoice * band
                    frame[0] = kept + side; frame[1] = kept - side
                }
                val n = if (breath > 0f) hiss.run(nextNoise() * breath, 0) else 0f
                for (c in 0 until channels) {
                    val x = frame[c]
                    var y = x * dry
                    if (repeats != null) y += repeats.run(x * send, c)
                    if (room != null && c < 2) y += room.run(x * wash, c)
                    val v = y * gain * music + n
                    pending[i * channels + c] = if (breath > 0f) softClip(v) else v
                }
                repeats?.advance()
            }
            bus.write(pending, block * channels, output, automation.pair)
            gain = target; dry = dryTarget; music = musicTarget
            done += block
        }
        // A trailing partial frame is passed on as it came.
        while (input.hasRemaining()) output.put(input.get())
    }

    /** Above -3 dBFS a sample is bent smoothly towards full scale instead of being cut flat. */
    private fun softClip(v: Float): Float {
        val knee = 23_170f
        val a = kotlin.math.abs(v)
        if (a <= knee) return v
        val room = 32_767f - knee
        val bent = knee + room * kotlin.math.tanh((a - knee) / room)
        return if (v < 0f) -bent else bent
    }

    /** White noise in -1..1 (xorshift): cheap, and the same from one run to the next. */
    private fun nextNoise(): Float {
        var x = seed
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        seed = x
        return (x.toFloat() / Int.MAX_VALUE.toFloat()).coerceIn(-1f, 1f)
    }

    /** A peaking filter at [DeckAutomation.MID_HZ] (RBJ's cookbook), retuned as its level moves; flat at 0 dB. */
    private class Peak(channels: Int) {
        private val x1 = FloatArray(channels); private val x2 = FloatArray(channels)
        private val y1 = FloatArray(channels); private val y2 = FloatArray(channels)
        private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
        private var db = 0f
        /** The filter runs while its level is off 0 dB, and its state is kept from block to block. */
        var active = false
            private set

        fun clear() { x1.fill(0f); x2.fill(0f); y1.fill(0f); y2.fill(0f); db = 0f; active = false }

        fun follow(levelDb: Float, sampleRate: Int) {
            val target = if (abs(levelDb) < .05f) 0f else levelDb
            if (!active && target == 0f) return
            // Back at 0 dB (unity) for a block: the filter steps out of the path.
            if (active && abs(target - db) < .05f) { if (target == 0f) clear(); return }
            if (!active) { x1.fill(0f); x2.fill(0f); y1.fill(0f); y2.fill(0f) }
            db = target; active = true
            val level = target
            val a = 10.0.pow(level / 40.0)
            val w = 2.0 * PI * DeckAutomation.MID_HZ / sampleRate
            val alpha = sin(w) / (2.0 * .8)
            val c = cos(w)
            val a0 = 1.0 + alpha / a
            b0 = ((1.0 + alpha * a) / a0).toFloat(); b1 = (-2.0 * c / a0).toFloat(); b2 = ((1.0 - alpha * a) / a0).toFloat()
            a1 = b1; a2 = ((1.0 - alpha / a) / a0).toFloat()
        }

        fun run(x: Float, c: Int): Float {
            val v = b0 * x + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c]
            x2[c] = x1[c]; x1[c] = x; y2[c] = y1[c]; y1[c] = v
            return v
        }
    }

    /** A second-order filter that is retuned as its cut-off moves, one state per channel. */
    private class Biquad(channels: Int) {
        private val x1 = FloatArray(channels); private val x2 = FloatArray(channels)
        private val y1 = FloatArray(channels); private val y2 = FloatArray(channels)
        private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
        var cutoff = 0f
            private set

        private val fresh = BooleanArray(channels) { true }
        private var passesLow = false
        /**
         * 7.6: share of the filtered sound in the output. A filter joins and leaves the path over [JOIN] samples:
         * dropped at once, even a 28 Hz high-pass shifts a bass note's phase enough for a click.
         */
        private var wet = 0f
        private var wetTarget = 0f
        /** The filter is in the path, or still leaving it. */
        val active: Boolean get() = cutoff > 0f || wet > 0f

        fun clear() { x1.fill(0f); x2.fill(0f); y1.fill(0f); y2.fill(0f); fresh.fill(true); cutoff = 0f; wet = 0f; wetTarget = 0f }

        fun follow(hz: Float, q: Float, sampleRate: Int, highPass: Boolean) {
            if (hz <= 0f) { if (cutoff > 0f) wetTarget = 0f; return }
            if (wetTarget < 1f) { if (wet <= 0f) fresh.fill(true); wetTarget = 1f }
            if (cutoff > 0f && abs(hz - cutoff) <= cutoff * .01f) return
            cutoff = hz
            passesLow = !highPass
            val w = 2.0 * PI * hz.coerceIn(10f, sampleRate * .45f) / sampleRate
            val alpha = sin(w) / (2.0 * q.coerceIn(.5f, 4f))
            val c = cos(w)
            val a0 = 1.0 + alpha
            if (highPass) { b0 = ((1.0 + c) / 2.0 / a0).toFloat(); b1 = (-(1.0 + c) / a0).toFloat() }
            else { b0 = ((1.0 - c) / 2.0 / a0).toFloat(); b1 = ((1.0 - c) / a0).toFloat() }
            b2 = b0
            a1 = (-2.0 * c / a0).toFloat(); a2 = ((1.0 - alpha) / a0).toFloat()
        }

        fun run(x: Float, c: Int): Float {
            // A filter that joins a sound in motion starts as if it had always been there: no click.
            if (fresh[c]) { fresh[c] = false; x1[c] = x; x2[c] = x; val settled = if (passesLow) x else 0f; y1[c] = settled; y2[c] = settled }
            val v = b0 * x + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c]
            x2[c] = x1[c]; x1[c] = x; y2[c] = y1[c]; y1[c] = v
            val heard = if (wet >= 1f) v else x + wet * (v - x)
            if (c == x1.size - 1 && wet != wetTarget) step()
            return heard
        }

        private fun step() {
            wet = if (wetTarget > wet) minOf(wetTarget, wet + 1f / JOIN) else maxOf(wetTarget, wet - 1f / JOIN)
            if (wet <= 0f && wetTarget <= 0f) clear()
        }

        private companion object { const val JOIN = 512f }
    }

    /**
     * Repeats that get duller and thinner each time round: the highs are damped and the
     * bass is taken out of the loop, so that a tail never turns into a rumble.
     */
    private class Echo(sampleRate: Int, private val channels: Int) {
        private val size = sampleRate * 2
        private val lines = Array(channels) { FloatArray(size) }
        private val dull = FloatArray(channels)
        private val bass = FloatArray(channels)
        private val damp = (1.0 - exp(-2.0 * PI * 3_800.0 / sampleRate)).toFloat()
        private val thin = (1.0 - exp(-2.0 * PI * 240.0 / sampleRate)).toFloat()
        private var write = 0
        private var delay = 1
        private var feedback = 0f

        fun tune(delaySamples: Int, feedbackLevel: Float) {
            delay = delaySamples.coerceIn(1, size - 1)
            feedback = feedbackLevel.coerceIn(0f, .85f)
        }

        fun run(input: Float, c: Int): Float {
            val line = lines[c]
            var read = write - delay
            if (read < 0) read += size
            val delayed = line[read]
            dull[c] += damp * (delayed - dull[c])
            bass[c] += thin * (dull[c] - bass[c])
            line[write] = input + (dull[c] - bass[c]) * feedback
            return delayed
        }

        fun advance() { write++; if (write == size) write = 0 }
    }

    /** A plain room reverb (eight combs and four all-passes a side, after Schroeder and Moorer), for the first two channels. */
    private class Reverb(sampleRate: Int, channels: Int) {
        private val sides = minOf(2, channels)
        private val scale = sampleRate / 44_100f
        private val combs = Array(sides) { side -> Array(COMBS.size) { FloatArray(((COMBS[it] + side * SPREAD) * scale).toInt().coerceAtLeast(8)) } }
        private val combAt = Array(sides) { IntArray(COMBS.size) }
        private val combState = Array(sides) { FloatArray(COMBS.size) }
        private val passes = Array(sides) { side -> Array(PASSES.size) { FloatArray(((PASSES[it] + side * SPREAD) * scale).toInt().coerceAtLeast(4)) } }
        private val passAt = Array(sides) { IntArray(PASSES.size) }

        fun run(input: Float, c: Int): Float {
            val fed = input * .03f
            var out = 0f
            val lines = combs[c]; val at = combAt[c]; val state = combState[c]
            for (i in lines.indices) {
                val line = lines[i]
                val y = line[at[i]]
                state[i] = y * (1f - DAMP) + state[i] * DAMP
                line[at[i]] = fed + state[i] * ROOM
                if (++at[i] == line.size) at[i] = 0
                out += y
            }
            val through = passes[c]; val cursor = passAt[c]
            for (i in through.indices) {
                val line = through[i]
                val buffered = line[cursor[i]]
                val y = buffered - out
                line[cursor[i]] = out + buffered * .5f
                if (++cursor[i] == line.size) cursor[i] = 0
                out = y
            }
            return out * WET
        }

        private companion object {
            val COMBS = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
            val PASSES = intArrayOf(556, 441, 341, 225)
            const val SPREAD = 23
            const val ROOM = .90f
            const val DAMP = .28f
            const val WET = 1.3f
        }
    }

    private companion object { const val BLOCK = 64 }
}

/** Keeps the incoming title in time with the outgoing one, the way a DJ nudges a deck. */
internal object DeckSync {
    /** Milliseconds, on the outgoing clock, by which the incoming title is ahead (+) or behind (−). */
    fun errorMs(plan: MixPlan, outPositionMs: Long, inPositionMs: Long): Double =
        (inPositionMs - plan.inMs) / plan.rate.toDouble() - (outPositionMs - plan.outMs)

    /**
     * Speed to give the incoming title. Small errors are left alone (changing speed is
     * not free); larger ones are caught up at 4 % at most, in steps that cannot be heard.
     * While the title is audible only clear errors are corrected, and more gently.
     */
    fun speed(rate: Float, errorMs: Double, audible: Boolean): Float {
        val deadBand = if (audible) 12.0 else 4.0
        if (abs(errorMs) < deadBand) return rate
        val strength = if (audible) .00035 else .0008
        val correction = (errorMs * strength).coerceIn(-.04, .04)
        val speed = rate * (1.0 - correction)
        return (Math.round(speed / STEP) * STEP).toFloat()
    }

    /**
     * The speed of the incoming deck for [plan] while the outgoing one plays at [outgoing]: in time with it
     * for a blend on the beat, its own otherwise. Set once, before the deck is heard.
     */
    fun speedOf(plan: MixPlan, outgoing: Float): Float =
        if (plan.kind == MixKind.Beatmatch) stepped(outgoing * plan.rate) else 1f

    /**
     * The incoming deck is heard once its level means something: at once for a cut (an echo, a build, a
     * trim), after the first eighth of the overlap for a blend, whose level rises from silence.
     */
    fun heard(plan: MixPlan, inPositionMs: Long): Boolean = inPositionMs >= heardFrom(plan)
    private fun heardFrom(plan: MixPlan): Double =
        if (plan.kind == MixKind.Beatmatch) plan.inMs + plan.overlapMs * plan.rate * QUIET_SHARE else plan.inMs.toDouble()
    /** In a blend the incoming title is still under -20 dB over this share of the overlap. */
    private const val QUIET_SHARE = .12
    /** A change of speed reaches the sound once the audio buffer (up to a second) has played out: corrections stop this long before. */
    const val SETTLE_BEFORE_MS = 1_400L

    /** True once the incoming deck is too close to being heard to have its speed corrected. */
    fun settled(plan: MixPlan, inPositionMs: Long): Boolean = inPositionMs >= heardFrom(plan) - SETTLE_BEFORE_MS

    /** A speed on the grid of steps too small to hear. */
    fun stepped(speed: Float): Float = (Math.round(speed / STEP) * STEP).toFloat()

    private const val STEP = .002
}
