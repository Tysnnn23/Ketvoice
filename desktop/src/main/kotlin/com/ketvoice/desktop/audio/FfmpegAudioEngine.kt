package com.ketvoice.desktop.audio

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * The desktop [AudioEngine]: FFmpeg decks → [DesktopMixer] → [PcmSink] (the sound card by default).
 * One high-priority writer thread owns the mixer and the sink; the public calls post commands to it,
 * so a call never waits for audio. The position is what has been heard: frames written minus what
 * the device still holds, mapped through a small timeline of jumps (seek, load), gapless switches,
 * underruns and the end.
 */
class FfmpegAudioEngine(
    private val sink: PcmSink = LineSink(),
    private val open: (AudioSource) -> ByteSource = Deck::openSource,
) : AudioEngine {
    private val _events = MutableSharedFlow<AudioEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<AudioEvent> = _events.asSharedFlow()

    /** The seam Phase 2 builds Ketmix on. Touch it only from the writer thread (through [post]). */
    val mixer = DesktopMixer(RATE)

    private val commands = LinkedBlockingQueue<() -> Unit>()
    @Volatile private var playing = false
    @Volatile private var released = false
    @Volatile private var written = 0L
    @Volatile private var pendingMs = -1L
    /** The deck being heard (the one [positionMs] and [durationMs] describe). */
    @Volatile private var heard: Deck? = null

    private enum class Kind { Jump, Run, Frozen, Advanced, Ended }
    /** From written frame [start], [deck] is heard from title frame [frame], [rate] title frames per frame. */
    private class Segment(val start: Long, val deck: Deck?, val frame: Long, val kind: Kind, val rate: Double = 1.0) { var announced = false }
    private val timeline = ArrayDeque<Segment>()

    // Writer thread only.
    private var wantPlay = false
    private var frozen = false
    private var buffering = false
    private var formatSent: Deck? = null
    private var errorSent: Deck? = null
    private val buf = FloatArray(BLOCK * 2)
    private val pcm = ByteArray(BLOCK * 4)
    private val block = DesktopMixer.Block()

    private val thread = Thread(::loop, "ketvoice-audio-out").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }

    override var volume: Float
        get() = mixer.gain.volume
        set(v) { mixer.gain.volume = v.coerceIn(0f, 1f) }

    override fun load(source: AudioSource, startMs: Long, play: Boolean) {
        val start = startMs.coerceAtLeast(0)
        pendingMs = start; playing = play
        post {
            declickOut()
            mixer.cancelMix()
            mixer.resetVoice()
            mixer.current?.close(); mixer.next?.close(); mixer.next = null
            val d = Deck(source, start, open)
            mixer.current = d; heard = d; frozen = false
            mark(written, d, d.positionFrames, Kind.Jump)
            pendingMs = -1
            wantPlay = play && outputReady()
            if (!wantPlay) playing = false
            mixer.gain.set(0f); if (play) mixer.gain.fadeTo(1f, Gain.DECLICK_MS)
            if (!buffering) { buffering = true; emit(AudioEvent.Buffering(true)) }
        }
    }

    override fun preloadNext(source: AudioSource?) = post {
        if (mixer.mix?.let { !it.started && it.incoming === mixer.next } == true) mixer.cancelMix()
        mixer.next?.close()
        mixer.next = source?.let { Deck(it, 0, open) }
    }

    override fun play() {
        playing = true
        post {
            if (mixer.current == null || !outputReady()) { playing = false; return@post }
            wantPlay = true
            mixer.gain.fadeTo(1f, Gain.PAUSE_FADE_MS)
        }
    }

    override fun pause() {
        playing = false
        post {
            wantPlay = false
            if (mixer.gain.fade > 0f || mixer.gain.fading) mixer.gain.fadeTo(0f, Gain.PAUSE_FADE_MS)
        }
    }

    override fun seekTo(ms: Long) {
        val to = ms.coerceAtLeast(0)
        pendingMs = to
        post {
            declickOut()
            mixer.cancelMix()
            mixer.resetVoice()
            // A pending transition seeked the next deck to its entry: back to its start for the gapless end.
            mixer.next?.let { if (it.positionFrames != 0L) it.seek(0) }
            mixer.current?.lane?.reset()
            var d = mixer.current
            if (d == null) {
                // Played to the end: the same source again, from there.
                val last = heard ?: run { pendingMs = -1; return@post }
                d = Deck(last.source, to, open); mixer.current = d
                wantPlay = false; playing = false
            } else d.seek(to)
            heard = d; frozen = false
            mark(written, d, to * RATE / 1000, Kind.Jump)
            pendingMs = -1
            mixer.gain.set(0f); if (wantPlay) mixer.gain.fadeTo(1f, Gain.DECLICK_MS)
        }
    }

    override fun positionMs(): Long {
        pendingMs.let { if (it >= 0) return it }
        val played = written - sink.queuedFrames() - mixer.voiceDelayFrames
        val frames = synchronized(timeline) {
            val last = timeline.lastOrNull() ?: return 0
            if (last.kind == Kind.Jump && last.start > played) last.frame
            else {
                val seg = timeline.lastOrNull { it.start <= played } ?: timeline.first()
                when (seg.kind) {
                    Kind.Frozen, Kind.Ended -> seg.frame
                    else -> seg.frame + ((played - seg.start).coerceAtLeast(0) * seg.rate).toLong()
                }
            }
        }
        val ms = frames * 1000 / RATE
        val duration = durationMs()
        return if (duration > 0) ms.coerceAtMost(duration) else ms
    }

    override fun durationMs(): Long = heard?.durationMs ?: 0
    override fun isPlaying(): Boolean = playing
    override fun setEqualizer(gainsDb: FloatArray?) = mixer.equalizer.set(gainsDb)

    override fun release() {
        if (released) return
        post { released = true }
        if (Thread.currentThread() !== thread) thread.join(2_000)
    }

    private fun post(command: () -> Unit) { if (!released) commands.offer(command) }

    private fun emit(event: AudioEvent) { _events.tryEmit(event) }

    private var sinkOpen = false

    /** Opens the output when it is not (no sound card, or it went away); false with an Error event when it cannot. */
    private fun outputReady(): Boolean {
        if (sinkOpen) return true
        return try {
            sink.open(RATE, 2); sinkOpen = true; true
        } catch (t: Throwable) {
            emit(AudioEvent.Error("audio output: ${t.message ?: t}")); false
        }
    }

    private fun active() = mixer.current != null && (wantPlay || mixer.gain.fading)

    private fun loop() {
        outputReady()
        try {
            while (!released) {
                val command = if (active()) commands.poll() else commands.poll(20, TimeUnit.MILLISECONDS)
                if (command != null) { command(); continue }
                announce()
                if (active()) render(BLOCK)
            }
        } catch (t: Throwable) {
            emit(AudioEvent.Error("audio engine: ${t.message ?: t}"))
        } finally {
            playing = false
            mixer.current?.close(); mixer.next?.close()
            runCatching { sink.close() }
        }
    }

    /** A few ms fade before a cut (seek, new track), so the cut does not click. */
    private fun declickOut() {
        if (mixer.current == null || !active() || mixer.gain.fade <= 0f) return
        mixer.gain.fadeTo(0f, Gain.DECLICK_MS)
        render(RATE * Gain.DECLICK_MS / 1000)
    }

    private fun render(frames: Int) {
        val before = mixer.current
        if (frozen && before != null && (before.availableFrames > 0 || before.ended)) {
            mark(written, before, before.lane.titleFrame, Kind.Run, before.lane.speed.toDouble()); frozen = false
        }
        val mixBefore = mixer.mix
        mixer.render(buf, frames, block, ::waitFor)
        if (block.switchedAt >= 0) mark(written + block.switchedAt, block.switchedTo, block.switchedFrame, Kind.Advanced, block.switchedRate)
        if (block.retimed) mixer.current?.let { mark(written + frames, it, it.lane.titleFrame, Kind.Run, it.lane.speed.toDouble()) }
        if (mixBefore != null || mixer.mix != null) publishMix()
        if (block.endedAt >= 0) {
            val last = block.switchedTo ?: before
            mark(written + block.endedAt, last, last?.lane?.titleFrame ?: 0, Kind.Ended)
            wantPlay = false; frozen = false
            if (block.failed) playing = false
        } else if (block.starved > 0) {
            val d = mixer.current
            if (!frozen && d != null) { mark(written + frames - block.starved, d, d.lane.titleFrame, Kind.Frozen); frozen = true }
            if (!buffering) { buffering = true; emit(AudioEvent.Buffering(true)) }
        } else if (buffering) { buffering = false; emit(AudioEvent.Buffering(false)) }
        if (block.endedAt == 0 && block.switchedAt < 0) { announce(); return } // nothing left to hear in this block
        for (i in 0 until frames * 2) {
            val v = (buf[i] * 32767f).roundToInt().coerceIn(-32768, 32767)
            pcm[i * 2] = v.toByte(); pcm[i * 2 + 1] = (v shr 8).toByte()
        }
        try {
            sink.write(pcm, frames * 4)
        } catch (t: Throwable) {
            // The device went away: stop here (the next play opens it again) rather than race through the queue.
            runCatching { sink.close() }; sinkOpen = false
            wantPlay = false; playing = false; mixer.gain.set(0f)
            emit(AudioEvent.Error("audio output: ${t.message ?: t}"))
            return
        }
        written += frames
        announce()
    }

    /** No data yet: wait while the device still has sound to play, else fill silence (buffering). */
    private fun waitFor(deck: Deck, frames: Int): Boolean {
        if (!commands.isEmpty()) return false
        if (!sink.realtime) { deck.awaitData(frames, 1_000); return true }
        val queuedMs = sink.queuedFrames() * 1000L / RATE
        if (queuedMs <= 40) return false
        deck.awaitData(frames, minOf(10L, queuedMs - 40))
        return true
    }

    private fun mark(start: Long, deck: Deck?, frame: Long, kind: Kind, rate: Double = 1.0) = synchronized(timeline) {
        timeline.addLast(Segment(start, deck, frame, kind, rate))
    }

    // ------------------------------------------------------------ Ketmix

    /** Where a Ketmix transition stands, for the controller and the badge. */
    internal data class MixStatus(val phase: MixPhase = MixPhase.Idle, val plan: com.ketvoice.app.automix.MixPlan? = null, val promoted: Boolean = false)
    enum class MixPhase { Idle, Scheduled, Mixing }

    private val _mixStatus: kotlinx.coroutines.flow.MutableStateFlow<MixStatus> = kotlinx.coroutines.flow.MutableStateFlow(MixStatus())
    internal val mixStatus: kotlinx.coroutines.flow.StateFlow<MixStatus> = _mixStatus
    @Volatile private var mixPlan: com.ketvoice.app.automix.MixPlan? = null

    /** Position the writer has reached in the current title (ahead of what is heard by the device's buffer). */
    fun renderedMs(): Long = mixer.current?.lane?.titleFrame?.let { it * 1000 / RATE } ?: positionMs()

    /** Speed the current title plays at (off 1 after a mix in time, until its glide back ends). */
    fun speed(): Float = mixer.current?.lane?.speed ?: 1f

    /**
     * Plays [plan] from the current title into the preloaded one (the Player's next title): the incoming
     * deck starts [preRollMs] before [MixPlan.outMs], silent until its automation lets it in, sample-locked
     * to the outgoing one and at the plan's speed. [onResult] says (on the writer thread) whether it is set.
     */
    internal fun mixIntoNext(plan: com.ketvoice.app.automix.MixPlan, preRollMs: Long,
                             stems: Pair<com.ketvoice.app.stems.StemAudio?, com.ketvoice.app.stems.StemAudio?>? = null,
                             onResult: (Boolean) -> Unit = {}) = post {
        val cur = mixer.current
        val incoming = mixer.next
        if (cur == null || incoming == null || incoming.error != null) { onResult(false); return@post }
        val speed = com.ketvoice.app.automix.DeckSync.speedOf(plan, cur.lane.speed)
        val startMs = plan.outMs - preRollMs
        incoming.lane.reset()
        // The separated ending and start (Ketmix « Séparer les voix »): the voice moves really take the voice out.
        cur.lane.stem = stems?.first
        incoming.lane.stem = stems?.second
        incoming.seek((plan.inMs - preRollMs * plan.rate.toDouble()).toLong().coerceAtLeast(0L))
        val outAutomation = com.ketvoice.app.automix.DeckAutomation.outgoing(plan)
        val inAutomation = com.ketvoice.app.automix.DeckAutomation.incoming(plan)
        val promoteMs = when {
            plan.kind == com.ketvoice.app.automix.MixKind.Beatmatch && plan.swapMs >= 0 -> plan.swapMs + (plan.beatMs / 2f).toLong()
            plan.kind == com.ketvoice.app.automix.MixKind.Trim || plan.kind == com.ketvoice.app.automix.MixKind.Echo ||
                plan.kind == com.ketvoice.app.automix.MixKind.Rise -> plan.outMs
            else -> plan.outMs + plan.overlapMs * 55 / 100
        }
        val endMs = maxOf(outAutomation.endUs / 1000L, plan.outMs + plan.overlapMs) + 300L
        val m = DesktopMixer.Mix(incoming, startMs * RATE / 1000, speed, outAutomation, inAutomation, promoteMs * RATE / 1000, endMs * RATE / 1000)
        val ok = mixer.startMix(m)
        if (!ok) incoming.seek(0)
        mixPlan = if (ok) plan else null
        publishMix()
        onResult(ok)
    }

    /** Drops a transition that is set or playing (the title heard plays on alone). */
    fun cancelMix() = post { mixer.cancelMix(); publishMix() }

    private fun publishMix() {
        val m = mixer.mix
        val status = when {
            m == null || m.finished -> MixStatus()
            !m.started -> MixStatus(MixPhase.Scheduled, mixPlan)
            else -> MixStatus(MixPhase.Mixing, mixPlan, m.promoted)
        }
        if (status.phase == MixPhase.Idle) mixPlan = null
        if (_mixStatus.value != status) _mixStatus.value = status
    }

    /** Events for what has just become audible; the format and errors of the heard deck. */
    private fun announce() {
        val played = written - sink.queuedFrames() - mixer.voiceDelayFrames
        synchronized(timeline) {
            for (s in timeline) {
                if (s.start > played) break
                if (s.announced) continue
                s.announced = true
                when (s.kind) {
                    Kind.Advanced -> { heard = s.deck; s.deck?.let { emit(AudioEvent.Advanced(it.source)) } }
                    Kind.Ended -> if (s.deck?.error == null) { playing = false; emit(AudioEvent.Ended) }
                    else -> {}
                }
            }
            while (timeline.size > 1 && timeline[1].start <= played && timeline[1].announced) timeline.removeFirst()
        }
        val h = heard ?: return
        if (h.error != null && errorSent !== h) {
            errorSent = h; playing = false; wantPlay = false
            emit(AudioEvent.Error(h.error!!))
        }
        val f = h.format
        if (f != null && formatSent !== h) { formatSent = h; emit(f) }
    }

    companion object {
        const val RATE = FfmpegDecoder.OUT_RATE
        /** 10 ms per block. */
        const val BLOCK = RATE / 100
    }
}
