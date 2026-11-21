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
 * decks → mix → equalizer → gain. One deck plays at a time and moves to the preloaded [next] at the
 * exact sample where [current] ends (gapless). A Ketmix transition ([Mix]) runs the preloaded deck beside
 * the current one, sample-locked to it: each deck goes through its [Lane] (the shared `DeckDsp` with the
 * plan's `DeckAutomation`, and a [TimeStretch] when it plays off its own speed), and the two are summed.
 */
class DesktopMixer(val sampleRate: Int = FfmpegDecoder.OUT_RATE) {
    @Volatile var current: Deck? = null
    @Volatile var next: Deck? = null
    val equalizer = Equalizer(sampleRate)
    val gain = Gain(sampleRate)

    /** The transition being played (writer thread only). */
    @Volatile var mix: Mix? = null
        private set
    /** After a mix in time: the deck gliding back to its own speed, and frames until its next step. */
    private var gliding: Deck? = null
    private var glideIn = 0L
    /** After a mix: the deck whose automation is dropped once its title passes [settleFrame] (it ends at the title's own level). */
    private var settling: Deck? = null
    private var settleFrame = 0L
    private var other = FloatArray(0)

    /**
     * A transition: the incoming deck starts when the outgoing title reaches [startFrame], at its own
     * position and at [speed]; both play their automation. At [promoteFrame] (outgoing title) the incoming
     * deck becomes [current]; past [endFrame] the outgoing one is closed.
     */
    class Mix internal constructor(
        val incoming: Deck,
        val startFrame: Long,
        val speed: Float,
        internal val outAutomation: com.ketvoice.app.automix.DeckAutomation,
        internal val inAutomation: com.ketvoice.app.automix.DeckAutomation,
        val promoteFrame: Long,
        val endFrame: Long,
    ) {
        @Volatile var outgoing: Deck? = null; internal set
        @Volatile var started = false; internal set
        @Volatile var promoted = false; internal set
        @Volatile var finished = false; internal set
    }

    /** What happened inside one rendered block, at which frame. */
    class Block {
        var switchedAt = -1; var switchedTo: Deck? = null
        /** Title frame of [switchedTo] at [switchedAt], and its speed (a Ketmix takeover lands mid-title). */
        var switchedFrame = 0L; var switchedRate = 1.0
        /** The current deck changed speed at the block's end (a step of the glide back). */
        var retimed = false
        var endedAt = -1; var failed = false
        /** Frames of silence at the block's end because the deck had nothing yet. */
        var starved = 0
        fun reset() { switchedAt = -1; switchedTo = null; switchedFrame = 0L; switchedRate = 1.0; retimed = false; endedAt = -1; failed = false; starved = 0 }
    }

    /** Plays [m] from its start frame; false when it cannot (no current deck, or already past it). */
    internal fun startMix(m: Mix): Boolean {
        val cur = current ?: return false
        if (cur.lane.titleFrame > m.startFrame) return false
        cancelMix()
        cur.lane.automation = m.outAutomation
        m.outgoing = cur
        mix = m
        return true
    }

    /**
     * Drops the transition: before it started the current deck plays on untouched; once started, the deck
     * that is not the title shown is closed, and the one left plays on without levels or tempo change.
     */
    fun cancelMix() {
        val m = mix ?: return
        mix = null
        m.finished = true
        if (m.started) { if (m.promoted) m.outgoing?.close() else m.incoming.close() }
        // Not started: the next deck goes back to its start, for an ordinary gapless end.
        else if (next === m.incoming) { m.incoming.lane.reset(); m.incoming.seek(0) }
        current?.lane?.let { it.automation = null; if (m.started) it.speed = 1f }
        gliding = null; settling = null
    }

    /**
     * Renders [frames] stereo frames into [out]. [wait] is asked what to do when the deck has no
     * data yet: true when it waited (try again), false to fill silence.
     */
    fun render(out: FloatArray, frames: Int, block: Block, wait: (Deck, Int) -> Boolean) {
        block.reset()
        val m = mix
        if (m == null) mixDecks(out, 0, frames, block, wait) else mixTwo(m, out, frames, block, wait)
        glide(frames, block)
        equalizer.process(out, frames)
        gain.process(out, frames)
    }

    private fun mixDecks(out: FloatArray, from: Int, frames: Int, block: Block, wait: (Deck, Int) -> Boolean): Int {
        var i = from
        while (i < frames) {
            val d = current ?: break
            i += d.lane.read(out, i, frames - i)
            if (i >= frames) break
            if (d.lane.ended) {
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
        return i
    }

    private fun mixTwo(m: Mix, out: FloatArray, frames: Int, block: Block, wait: (Deck, Int) -> Boolean) {
        var i = 0
        if (!m.started) {
            val cur = current
            if (cur == null || cur !== m.outgoing || cur.lane.ended) { cancelMix(); mixDecks(out, 0, frames, block, wait); return }
            val until = ((m.startFrame - cur.lane.titleFrame) / cur.lane.speed.toDouble()).toLong().coerceIn(0L, frames.toLong()).toInt()
            if (until > 0) i = mixDecks(out, 0, until, block, wait)
            if (current !== cur || block.endedAt >= 0) { mix = null; m.finished = true; return }
            if (i < until) return // starved before the start: the transition waits with the title
            if (i == frames) return
            // From here the incoming deck is no longer the gapless one: it plays beside the current one.
            if (next === m.incoming) next = null
            m.incoming.lane.automation = m.inAutomation
            m.incoming.lane.speed = m.speed
            m.started = true
        }
        val outgoing = m.outgoing!!
        val incoming = m.incoming
        val n = frames - i
        // Outgoing: its sound, then silence through its effects (an echo rings out) once its title is over.
        val a = if (outgoing.lane.ended) 0 else outgoing.lane.read(out, i, n)
        if (a < n) outgoing.lane.silence(out, i + a, n - a)
        // Incoming: kept in step; a moment without data waits a little, then plays silence for it.
        if (other.size < n * 2) other = FloatArray(n * 2)
        if (incoming.availableFrames < n && !incoming.lane.ended) wait(incoming, n)
        val b = incoming.lane.read(other, 0, n)
        if (b < n) java.util.Arrays.fill(other, b * 2, n * 2, 0f)
        for (k in 0 until n * 2) out[i * 2 + k] += other[k]
        if (incoming.error != null && !m.promoted) {
            // The next title failed: the current one plays on as if nothing had started.
            mix = null; m.finished = true; incoming.close(); outgoing.lane.automation = null
            return
        }
        val outAt = outgoing.lane.titleFrame
        if (!m.promoted && (outAt >= m.promoteFrame || outgoing.lane.ended)) {
            m.promoted = true
            current = incoming
            block.switchedAt = frames; block.switchedTo = incoming
            block.switchedFrame = incoming.lane.titleFrame; block.switchedRate = incoming.lane.speed.toDouble()
        }
        if (m.promoted && (outAt >= m.endFrame || outgoing.lane.silentFrames > sampleRate * 6L)) {
            outgoing.close()
            mix = null; m.finished = true
            if (incoming.lane.speed != 1f) { gliding = incoming; glideIn = com.ketvoice.app.automix.TempoReturn.WAIT_MS * sampleRate / 1000 }
            settling = incoming; settleFrame = (m.inAutomation.endUs / 1000L + 600L) * sampleRate / 1000
        }
    }

    /** The glide back to the title's own speed after a mix in time, in `TempoReturn`'s steps. */
    private fun glide(frames: Int, block: Block) {
        settling?.let { if (it !== current || mix != null) settling = null else if (it.lane.titleFrame >= settleFrame) { it.lane.automation = null; settling = null } }
        val d = gliding ?: return
        if (d !== current || mix != null) { gliding = null; return }
        glideIn -= frames
        if (glideIn > 0) return
        d.lane.speed = com.ketvoice.app.automix.TempoReturn.next(d.lane.speed)
        block.retimed = true
        if (d.lane.speed == 1f) gliding = null else glideIn = com.ketvoice.app.automix.TempoReturn.EVERY_MS * sampleRate / 1000
    }
}

/**
 * One deck's way into the mix: its frames (through a [TimeStretch] once it has played off its own
 * speed), then the shared Ketmix `DeckDsp` while it carries an automation. Writer thread only.
 */
class Lane internal constructor(private val deck: Deck) {
    /** The transition's levels and filters for this deck, keyed on its title position; null = untouched. */
    internal var automation: com.ketvoice.app.automix.DeckAutomation? = null
    private var dsp: com.ketvoice.app.automix.DeckDsp? = null
    private var stretch: TimeStretch? = null
    private var inBytes = java.nio.ByteBuffer.allocate(0)
    private var outBytes = java.nio.ByteBuffer.allocate(0)
    /** Frames of silence played through the effects after the title's end. */
    var silentFrames = 0L; private set

    /** Input frames per output frame. Off 1 the deck gets a stretcher, which it keeps (at 1 it is exact). */
    var speed: Float
        get() = stretch?.speed ?: 1f
        set(value) {
            if (stretch == null && value == 1f) return
            val s = stretch ?: TimeStretch(deck::read, { deck.ended }, deck.positionFrames).also { stretch = it }
            s.speed = value
        }

    /** Title frame of the next frame [read] gives. */
    val titleFrame: Long get() = (stretch?.titleFrame ?: deck.positionFrames) + silentFrames

    val ended: Boolean get() = stretch?.done ?: deck.ended

    /** Frames into [out] at frame [offset], with the automation applied; fewer when the deck has none yet. */
    fun read(out: FloatArray, offset: Int, frames: Int): Int {
        val from = titleFrame
        val n = stretch?.process(out, offset, frames) ?: deck.read(out, offset, frames)
        if (n > 0) effects(out, offset, n, from)
        return n
    }

    /** [frames] of silence through the deck's effects, the title being over (its echo and reverb ring out). */
    fun silence(out: FloatArray, offset: Int, frames: Int) {
        java.util.Arrays.fill(out, offset * 2, (offset + frames) * 2, 0f)
        if (automation == null) return
        effects(out, offset, frames, titleFrame)
        silentFrames += frames
    }

    /** A seek or a new start: whatever a transition left on this deck is gone. */
    fun reset() { automation = null; dsp = null; stretch = null; silentFrames = 0 }

    private fun effects(buf: FloatArray, offset: Int, frames: Int, titleFrame: Long) {
        val a = automation
        if (a == null && dsp == null) return
        val proc = dsp ?: com.ketvoice.app.automix.DeckDsp(FfmpegDecoder.OUT_RATE, 2).also { dsp = it }
        val bytes = frames * 4
        if (inBytes.capacity() < bytes) {
            inBytes = java.nio.ByteBuffer.allocate(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            outBytes = java.nio.ByteBuffer.allocate(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        }
        val input = inBytes; val output = outBytes
        input.clear(); output.clear()
        for (k in 0 until frames * 2) input.putShort((buf[offset * 2 + k] * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        input.flip()
        proc.process(input, output, titleFrame * 1_000_000L / FfmpegDecoder.OUT_RATE, a)
        output.flip()
        for (k in 0 until frames * 2) buf[offset * 2 + k] = output.short / 32768f
        // Without an automation the processor has reset itself and let the sound through: it is dropped.
        if (a == null) dsp = null
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
