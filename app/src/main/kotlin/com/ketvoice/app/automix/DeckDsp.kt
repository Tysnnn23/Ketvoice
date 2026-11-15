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
    val vocalCut: Curve = Curve.flat(0f)
) {
    val endUs: Long get() = maxOf(maxOf(gain.endUs, highPassHz.endUs, lowPassHz.endUs), maxOf(dry.endUs, echo.endUs, reverb.endUs), vocalCut.endUs)
    val usesVocalCut: Boolean get() = vocalCut.endUs > 0L
    val usesEcho: Boolean get() = echoDelayUs > 0L

    fun copyWith(vocalCut: Curve) = DeckAutomation(gain, highPassHz, lowPassHz, dry, echo, reverb, echoDelayUs, echoFeedback, resonance, vocalCut)
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

        private fun fadeOut(x: Float) = cos(PI / 2 * x).toFloat().coerceAtLeast(0f).pow(POWER)
        private fun fadeIn(x: Float) = sin(PI / 2 * x).toFloat().coerceAtLeast(0f).pow(POWER)
        private fun ease(x: Float) = (.5 - .5 * cos(PI * x)).toFloat()
        private fun sweep(fromHz: Float, toHz: Float, x: Float) = exp(ln(fromHz) + (ln(toHz) - ln(fromHz)) * ease(x))
        private fun point(us: Long, value: Float) = longArrayOf(us) to floatArrayOf(value)

        /** The title that leaves. */
        fun outgoing(plan: MixPlan): DeckAutomation {
            val start = plan.outMs * 1000L
            val end = (plan.outMs + plan.overlapMs) * 1000L
            return when (plan.kind) {
                MixKind.Beatmatch -> if (plan.swapMs < 0) plainOut(start, end) else withVoiceDown(blendOut(plan, start, end), plan)
                MixKind.Echo -> echoOut(plan, start, end)
                MixKind.Filter -> withVoiceDown(filterOut(plan, start, end), plan)
                MixKind.Wash -> withVoiceDown(washOut(start, end), plan)
                else -> plainOut(start, end)
            }
        }

        /** The title that arrives. Its positions run [MixPlan.rate] times faster than the outgoing clock. */
        fun incoming(plan: MixPlan): DeckAutomation {
            fun position(outMs: Long) = ((plan.inMs + (outMs - plan.outMs) * plan.rate.toDouble()) * 1000.0).toLong()
            val start = plan.inMs * 1000L
            val end = position(plan.outMs + plan.overlapMs)
            // After the overlap the title comes back to its own level, slowly enough not to be noticed.
            val settle = Curve.shaped(end, end + SETTLE_US, 8) { plan.gain + (1f - plan.gain) * ease(it) }
            return when (plan.kind) {
                MixKind.Beatmatch -> if (plan.swapMs < 0) plainIn(plan, start, end, settle)
                    else blendIn(plan, start, end, position(plan.swapMs), settle)
                MixKind.Echo -> {
                    // It comes in on the beat where the outgoing title is cut: at once, not faded.
                    val attack = start + 30_000L
                    DeckAutomation(Curve.join(point(start, 0f), point(attack, plan.gain), point(end, plan.gain), settle), Curve.flat(0f))
                }
                MixKind.Filter -> filterIn(plan, start, end, settle)
                MixKind.Wash -> {
                    // Under the wash the title rises without its bass at first, so that the two do not blur.
                    val open = start + (end - start) * 55 / 100
                    DeckAutomation(Curve.join(Curve.shaped(start, end) { plan.gain * fadeIn(it) }, settle),
                        Curve.join(point(0L, 220f), Curve.shaped(start, open, 16) { sweep(220f, OPEN_HZ, it) }, point(open + 1, 0f)))
                }
                else -> plainIn(plan, start, end, settle)
            }
        }

        /** When the blend starts under the last sung bars, the outgoing voice steps back over them. */
        private fun withVoiceDown(automation: DeckAutomation, plan: MixPlan): DeckAutomation {
            if (plan.sungUntilMs <= plan.outMs) return automation
            val start = plan.outMs * 1000L
            // Over eight beats, or three seconds when the two titles are not in time.
            val over = if (plan.beatMs > 0f) (plan.beatMs * 8000f).toLong() else VOICE_DOWN_US
            val down = (start + over).coerceAtMost(maxOf(start + 1, plan.sungUntilMs * 1000L))
            return automation.copyWith(vocalCut = Curve.join(point(start, 0f), Curve.shaped(start + 1, down, 12) { VOICE_DOWN * ease(it) }))
        }
        /** The voice is taken down by this much: well under the incoming title, never cut off. */
        const val VOICE_DOWN = .8f
        private const val VOICE_DOWN_US = 3_000_000L

        private fun plainOut(start: Long, end: Long) =
            DeckAutomation(Curve.join(Curve.shaped(start, end, shape = ::fadeOut)), Curve.flat(0f))

        private fun plainIn(plan: MixPlan, start: Long, end: Long, settle: Pair<LongArray, FloatArray>) =
            DeckAutomation(Curve.join(Curve.shaped(start, end) { plan.gain * fadeIn(it) }, settle), Curve.flat(0f))

        /**
         * Both titles in time: the outgoing one keeps its bass until the swap, then thins out.
         * Variant 0 lets it rise into the air (high-pass), variant 1 lets it sink (low-pass).
         */
        private fun blendOut(plan: MixPlan, start: Long, end: Long): DeckAutomation {
            val swap = (plan.swapMs * 1000L).coerceIn(start + 1, end - 1)
            val half = (plan.beatMs * 500f).toLong().coerceIn(120_000L, 400_000L)
            val gain = Curve.join(
                Curve.shaped(start, swap, 16) { 1f - (1f - MEETING) * ease(it) },
                Curve.shaped(swap, end, 24) { MEETING * fadeOut(it) })
            val bassAway = Curve.shaped(swap - half, swap + half, 16) { sweep(OPEN_HZ, BASS_HZ, it) }
            if (plan.variant == 1) {
                val sink = Curve.join(point(swap + half, 0f), point(swap + half + 1, TOP_HZ),
                    Curve.shaped(swap + half + 2, end, 24) { sweep(TOP_HZ, 1_400f, it) })
                return DeckAutomation(gain, Curve.join(point(swap - half - 1, 0f), bassAway), sink, resonance = SWEEP_Q)
            }
            val air = Curve.shaped(swap + half + 1, end, 24) { sweep(BASS_HZ, if (plan.clash) 2_400f else 1_100f, it) }
            return DeckAutomation(gain, Curve.join(point(swap - half - 1, 0f), bassAway, air), resonance = SWEEP_Q)
        }

        private fun blendIn(plan: MixPlan, start: Long, end: Long, swapAt: Long, settle: Pair<LongArray, FloatArray>): DeckAutomation {
            val swap = swapAt.coerceIn(start + 1, end - 1)
            val half = (plan.beatMs * 500f * plan.rate).toLong().coerceIn(120_000L, 400_000L)
            val gain = Curve.join(
                Curve.shaped(start, swap, 24) { plan.gain * MEETING * fadeIn(it) },
                Curve.shaped(swap, end, 16) { plan.gain * (MEETING + (1f - MEETING) * ease(it)) },
                settle)
            val bass = Curve.join(
                point(0L, BASS_HZ),
                Curve.shaped(swap - half, swap + half, 16) { sweep(BASS_HZ, OPEN_HZ, it) },
                point(swap + half + 1, 0f))
            if (plan.variant != 0) return DeckAutomation(gain, bass)
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
         * A crossing of spectra rather than of levels. Variant 0: the outgoing title rises into
         * the air while the next one grows from its low end. Variant 1: it sinks while the next
         * one comes down from the top. [MixPlan.hold] keeps its level while it fades by itself.
         */
        private fun filterOut(plan: MixPlan, start: Long, end: Long): DeckAutomation {
            val held = start + ((end - start) * plan.hold.coerceIn(0f, .85f)).toLong()
            val gain = Curve.join(point(held, 1f), Curve.shaped(held + 1, end, 24, ::fadeOut))
            return if (plan.variant == 1)
                DeckAutomation(gain, Curve.flat(0f), Curve.join(point(start, 0f), point(start + 1, TOP_HZ),
                    Curve.shaped(start + 2, end, 32) { sweep(TOP_HZ, 260f, it) }), resonance = SWEEP_Q)
            else DeckAutomation(gain, Curve.join(point(start, 0f), Curve.shaped(start + 1, end, 32) { sweep(OPEN_HZ, 1_500f, it) }),
                resonance = SWEEP_Q)
        }

        private fun filterIn(plan: MixPlan, start: Long, end: Long, settle: Pair<LongArray, FloatArray>): DeckAutomation {
            val gain = Curve.join(Curve.shaped(start, end) { plan.gain * fadeIn(it) }, settle)
            val opened = start + (end - start) * 4 / 5
            return if (plan.variant == 1)
                DeckAutomation(gain, Curve.join(point(0L, 1_200f), Curve.shaped(start, opened, 32) { sweep(1_200f, OPEN_HZ, it) }, point(opened + 1, 0f)),
                    resonance = SWEEP_Q)
            else DeckAutomation(gain, Curve.flat(0f),
                Curve.join(point(0L, 380f), Curve.shaped(start, opened, 32) { sweep(380f, TOP_HZ, it) }, point(opened + 1, 0f)),
                resonance = SWEEP_Q)
        }

        /** The title dissolves into its own reverberation, which then fades. */
        private fun washOut(start: Long, end: Long): DeckAutomation {
            val length = end - start
            val send = Curve.join(point(start, 0f), Curve.shaped(start + 1, start + length * 35 / 100, 12, ::ease))
            val dry = Curve.join(point(start + length * 15 / 100, 1f),
                Curve.shaped(start + length * 15 / 100 + 1, start + length * 60 / 100, 16) { 1f - ease(it) })
            val gain = Curve.join(point(start + length / 2, 1f), Curve.shaped(start + length / 2 + 1, end, 24, ::fadeOut))
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
internal class DeckDsp(private val sampleRate: Int, private val channels: Int) {
    private val high = Biquad(channels)
    private val low = Biquad(channels)
    /** The band of the voice, on the centre of the image: high-passed then low-passed. */
    private val voiceHigh = Biquad(1)
    private val voiceLow = Biquad(1)
    private var gain = Float.NaN
    private var dry = 1f
    private var echo: Echo? = null
    private var reverb: Reverb? = null
    private val frame = FloatArray(channels)

    fun reset() {
        high.clear(); low.clear(); voiceHigh.clear(); voiceLow.clear()
        gain = Float.NaN; dry = 1f
        echo = null; reverb = null
    }

    /**
     * Reads all of [input] and writes the result to [output]. [startUs] is the position in
     * the title of the first frame. Without automation the sound passes through untouched.
     */
    fun process(input: ByteBuffer, output: ByteBuffer, startUs: Long, automation: DeckAutomation?) {
        if (automation == null) {
            if (!gain.isNaN() || high.cutoff > 0f || low.cutoff > 0f || echo != null || reverb != null) reset()
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
            if (gain.isNaN()) { gain = automation.gain.at(blockUs); dry = automation.dry.at(blockUs) }
            val step = (target - gain) / block
            val dryTarget = automation.dry.at(endUs)
            val dryStep = (dryTarget - dry) / block
            high.follow(automation.highPassHz.at(middleUs), automation.resonance, sampleRate, highPass = true)
            low.follow(automation.lowPassHz.at(middleUs), automation.resonance, sampleRate, highPass = false)
            val voice = if (channels >= 2 && automation.usesVocalCut) automation.vocalCut.at(middleUs).coerceIn(0f, 1f) else 0f
            if (voice > 0f) { voiceHigh.follow(220f, .7071f, sampleRate, highPass = true); voiceLow.follow(4_500f, .7071f, sampleRate, highPass = false) }
            val send = if (repeats != null) automation.echo.at(middleUs) else 0f
            val wash = if (room != null) automation.reverb.at(middleUs) else 0f
            for (i in 0 until block) {
                gain += step; dry += dryStep
                for (c in 0 until channels) {
                    var x = input.short.toFloat()
                    if (high.cutoff > 0f) x = high.run(x, c)
                    if (low.cutoff > 0f) x = low.run(x, c)
                    frame[c] = x
                }
                if (voice > 0f) {
                    // Mid and side: the voice lives in the middle; only its band is taken down there.
                    val mid = (frame[0] + frame[1]) * .5f
                    val side = (frame[0] - frame[1]) * .5f
                    val band = voiceLow.run(voiceHigh.run(mid, 0), 0)
                    val kept = mid - voice * band
                    frame[0] = kept + side; frame[1] = kept - side
                }
                for (c in 0 until channels) {
                    val x = frame[c]
                    var y = x * dry
                    if (repeats != null) y += repeats.run(x * send, c)
                    if (room != null && c < 2) y += room.run(x * wash, c)
                    output.putShort((y * gain).toInt().coerceIn(-32768, 32767).toShort())
                }
                repeats?.advance()
            }
            gain = target; dry = dryTarget
            done += block
        }
        // A trailing partial frame is passed on as it came.
        while (input.hasRemaining()) output.put(input.get())
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

        fun clear() { x1.fill(0f); x2.fill(0f); y1.fill(0f); y2.fill(0f); fresh.fill(true); cutoff = 0f }

        fun follow(hz: Float, q: Float, sampleRate: Int, highPass: Boolean) {
            if (hz <= 0f) { if (cutoff > 0f) clear(); return }
            if (abs(hz - cutoff) <= cutoff * .01f) return
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
            return v
        }
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
     * Speed of the incoming title [through] the overlap (0..1) when the two tempos meet in
     * time: [rate] at first, when it must follow the outgoing title, its own tempo at the
     * end. The outgoing title plays at this speed divided by [rate], so the two stay locked.
     */
    fun ride(rate: Float, through: Float): Float {
        val t = ((through - RIDE_FROM) / (RIDE_TO - RIDE_FROM)).coerceIn(0f, 1f)
        val eased = t * t * (3f - 2f * t)
        return rate.pow(1f - eased)
    }
    private const val RIDE_FROM = .12f
    private const val RIDE_TO = .88f

    /** A speed on the grid of steps too small to hear. */
    fun stepped(speed: Float): Float = (Math.round(speed / STEP) * STEP).toFloat()

    /** Speeds from the mixing tempo back to the title's own, one per half second. */
    fun glide(rate: Float, steps: Int = 16): List<Float> =
        if (abs(rate - 1f) < .003f) listOf(1f)
        else (1..steps).map { step -> if (step == steps) 1f else (Math.round((rate + (1f - rate) * step / steps) / STEP) * STEP).toFloat() }

    private const val STEP = .002
}
