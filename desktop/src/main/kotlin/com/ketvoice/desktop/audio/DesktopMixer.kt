package com.ketvoice.desktop.audio

import com.ketvoice.app.player.EqualizerProfile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * decks → mix → equalizer → gain. Phase 1 plays one deck at a time and moves to the preloaded
 * [next] at the exact sample where [current] ends (gapless). Phase 2 plugs the Ketmix two-deck DSP
 * (DeckDsp per deck, MixBus, VoiceStage, a Sonic port) in [mixDecks], keeping this seam.
 */
class DesktopMixer(val sampleRate: Int = FfmpegDecoder.OUT_RATE) {
    @Volatile var current: Deck? = null
    @Volatile var next: Deck? = null
    val equalizer = Equalizer(sampleRate)
    val gain = Gain(sampleRate)

    /** What happened inside one rendered block, at which frame. */
    class Block {
        var switchedAt = -1; var switchedTo: Deck? = null
        var endedAt = -1; var failed = false
        /** Frames of silence at the block's end because the deck had nothing yet. */
        var starved = 0
        fun reset() { switchedAt = -1; switchedTo = null; endedAt = -1; failed = false; starved = 0 }
    }

    /**
     * Renders [frames] stereo frames into [out]. [wait] is asked what to do when the deck has no
     * data yet: true when it waited (try again), false to fill silence.
     */
    fun render(out: FloatArray, frames: Int, block: Block, wait: (Deck, Int) -> Boolean) {
        block.reset()
        mixDecks(out, frames, block, wait)
        equalizer.process(out, frames)
        gain.process(out, frames)
    }

    private fun mixDecks(out: FloatArray, frames: Int, block: Block, wait: (Deck, Int) -> Boolean) {
        var i = 0
        while (i < frames) {
            val d = current ?: break
            i += d.read(out, i, frames - i)
            if (i >= frames) break
            if (d.ended) {
                if (d.error != null) { block.failed = true; block.endedAt = i; current = null; break }
                val n = next
                next = null
                if (n != null && n.error == null) {
                    current = n; block.switchedAt = i; block.switchedTo = n
                    d.close()
                    continue
                }
                n?.close()
                block.endedAt = i; current = null; d.close()
                break
            }
            if (!wait(d, frames - i)) { block.starved = frames - i; break }
        }
        if (i < frames) java.util.Arrays.fill(out, i * 2, frames * 2, 0f)
    }
}

/**
 * The volume, smoothed per sample (no zipper noise, no clicks), times a fader for pause and resume
 * (equal-power curve over [PAUSE_FADE_MS], the 7.5 « fondu à la pause » idea; `player/PauseFade.kt`
 * itself leans on the Android Player's SkipFade, so its curve is restated here).
 */
class Gain(private val sampleRate: Int) {
    @Volatile var volume = 1f
    private var smoothed = 1f
    private val k = (1.0 - exp(-1.0 / (SMOOTH_MS / 1000.0 * sampleRate))).toFloat()

    private var level = 1f
    private var from = 1f
    private var to = 1f
    private var length = 0
    private var at = 0

    /** Where the fader is (0 = silent). */
    val fade: Float get() = level
    val fading: Boolean get() = at < length

    fun fadeTo(target: Float, ms: Int) {
        from = level; to = target
        length = (ms.toLong() * sampleRate / 1000).toInt().coerceAtLeast(1); at = 0
        if (ms <= 0) { level = target; length = 0 }
    }

    /** Jumps the fader (no audio is flowing). */
    fun set(target: Float) { level = target; from = target; to = target; length = 0; at = 0 }

    fun process(buf: FloatArray, frames: Int) {
        val target = volume.coerceIn(0f, 1f)
        for (f in 0 until frames) {
            smoothed += (target - smoothed) * k
            if (at < length) { at++; level = curve(from, to, at.toFloat() / length) }
            val g = smoothed * level
            buf[f * 2] *= g; buf[f * 2 + 1] *= g
        }
    }

    companion object {
        const val PAUSE_FADE_MS = 200
        /** Short fades that only take the click out of a cut (seek, track change). */
        const val DECLICK_MS = 5
        private const val SMOOTH_MS = 12.0

        /** The equal-power curve of the app's skip and pause fades. */
        fun curve(from: Float, to: Float, t: Float): Float {
            val p = t.coerceIn(0f, 1f)
            return if (to < from) to + (from - to) * cos(p * PI / 2).toFloat()
            else from + (to - from) * sin(p * PI / 2).toFloat()
        }
    }
}

/**
 * Ten peaking filters (RBJ biquads) at the app's [EqualizerProfile] frequencies, on the summed
 * output. A new setting glides there in small steps (no clicks); flat bands step out of the path.
 */
class Equalizer(private val sampleRate: Int) {
    private val bands = EqualizerProfile.frequencies.size
    @Volatile private var target = FloatArray(bands)
    private val db = FloatArray(bands)
    private val b0 = FloatArray(bands) { 1f }; private val b1 = FloatArray(bands); private val b2 = FloatArray(bands)
    private val a1 = FloatArray(bands); private val a2 = FloatArray(bands)
    private val state = Array(bands) { FloatArray(8) } // x1 x2 y1 y2, per channel

    fun set(gainsDb: FloatArray?) {
        target = FloatArray(bands) { (gainsDb?.getOrNull(it) ?: 0f).coerceIn(-12f, 12f) }
    }

    val flat: Boolean get() = db.all { it == 0f } && target.all { it == 0f }

    fun process(buf: FloatArray, frames: Int) {
        val t = target
        var boosted = false
        for (b in 0 until bands) {
            if (db[b] != t[b]) {
                val step = STEP_DB * frames / (sampleRate / 100f)
                db[b] = if (abs(t[b] - db[b]) <= step) t[b] else db[b] + step * Math.signum(t[b] - db[b])
                if (db[b] == 0f) state[b].fill(0f) else tune(b)
            }
            if (db[b] > 0f) boosted = true
        }
        for (b in 0 until bands) {
            if (db[b] == 0f) continue
            val s = state[b]
            val c0 = b0[b]; val c1 = b1[b]; val c2 = b2[b]; val d1 = a1[b]; val d2 = a2[b]
            for (f in 0 until frames) {
                for (c in 0..1) {
                    val x = buf[f * 2 + c]; val o = c * 4
                    val y = c0 * x + c1 * s[o] + c2 * s[o + 1] - d1 * s[o + 2] - d2 * s[o + 3]
                    s[o + 1] = s[o]; s[o] = x; s[o + 3] = s[o + 2]; s[o + 2] = y
                    buf[f * 2 + c] = y
                }
            }
        }
        // Boosted bands may push peaks past full scale: a soft knee keeps them from clipping hard.
        if (boosted) for (i in 0 until frames * 2) buf[i] = softClip(buf[i])
    }

    private fun tune(b: Int) {
        val a = 10.0.pow(db[b] / 40.0)
        val w = 2.0 * PI * EqualizerProfile.frequencies[b].coerceAtMost((sampleRate * .45).toInt()) / sampleRate
        val alpha = sin(w) / (2.0 * Q)
        val c = cos(w)
        val a0 = 1.0 + alpha / a
        b0[b] = ((1.0 + alpha * a) / a0).toFloat(); b1[b] = (-2.0 * c / a0).toFloat(); b2[b] = ((1.0 - alpha * a) / a0).toFloat()
        a1[b] = (-2.0 * c / a0).toFloat(); a2[b] = ((1.0 - alpha / a) / a0).toFloat()
    }

    companion object {
        /** One octave per band. */
        private const val Q = 1.41
        /** dB per 10 ms while a setting glides. */
        private const val STEP_DB = .5f

        fun softClip(x: Float): Float {
            val m = abs(x)
            if (m <= .9f) return x
            return Math.signum(x) * (.9f + .1f * tanh((m - .9f) / .1f))
        }
    }
}
