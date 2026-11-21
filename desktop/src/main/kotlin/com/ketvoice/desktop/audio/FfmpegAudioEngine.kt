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
    private class Segment(val start: Long, val deck: Deck?, val frame: Long, val kind: Kind) { var announced = false }
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
            mixer.current?.close(); mixer.next?.close(); mixer.next = null
            val d = Deck(source, start, open)
            mixer.current = d; heard = d; frozen = false
            mark(written, d, d.positionFrames, Kind.Jump)
            pendingMs = -1
            wantPlay = play
            mixer.gain.set(0f); if (play) mixer.gain.fadeTo(1f, Gain.DECLICK_MS)
            if (!buffering) { buffering = true; emit(AudioEvent.Buffering(true)) }
        }
    }

    override fun preloadNext(source: AudioSource?) = post {
        mixer.next?.close()
        mixer.next = source?.let { Deck(it, 0, open) }
    }

    override fun play() {
        playing = true
        post {
            if (mixer.current == null) { playing = false; return@post }
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
        val played = written - sink.queuedFrames()
        val frames = synchronized(timeline) {
            val last = timeline.lastOrNull() ?: return 0
            if (last.kind == Kind.Jump && last.start > played) last.frame
            else {
                val seg = timeline.lastOrNull { it.start <= played } ?: timeline.first()
                when (seg.kind) {
                    Kind.Frozen, Kind.Ended -> seg.frame
                    else -> seg.frame + (played - seg.start).coerceAtLeast(0)
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

    private fun active() = mixer.current != null && (wantPlay || mixer.gain.fading)

    private fun loop() {
        try {
            sink.open(RATE, 2)
        } catch (t: Throwable) {
            emit(AudioEvent.Error("audio output: ${t.message ?: t}"))
        }
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
            mark(written, before, before.positionFrames, Kind.Run); frozen = false
        }
        mixer.render(buf, frames, block, ::waitFor)
        if (block.switchedAt >= 0) mark(written + block.switchedAt, block.switchedTo, 0, Kind.Advanced)
        if (block.endedAt >= 0) {
            val last = block.switchedTo ?: before
            mark(written + block.endedAt, last, last?.positionFrames ?: 0, Kind.Ended)
            wantPlay = false; frozen = false
            if (block.failed) playing = false
        } else if (block.starved > 0) {
            val d = mixer.current
            if (!frozen && d != null) { mark(written + frames - block.starved, d, d.positionFrames, Kind.Frozen); frozen = true }
            if (!buffering) { buffering = true; emit(AudioEvent.Buffering(true)) }
        } else if (buffering) { buffering = false; emit(AudioEvent.Buffering(false)) }
        if (block.endedAt == 0 && block.switchedAt < 0) { announce(); return } // nothing left to hear in this block
        for (i in 0 until frames * 2) {
            val v = (buf[i] * 32767f).roundToInt().coerceIn(-32768, 32767)
            pcm[i * 2] = v.toByte(); pcm[i * 2 + 1] = (v shr 8).toByte()
        }
        sink.write(pcm, frames * 4)
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

    private fun mark(start: Long, deck: Deck?, frame: Long, kind: Kind) = synchronized(timeline) {
        timeline.addLast(Segment(start, deck, frame, kind))
    }

    /** Events for what has just become audible; the format and errors of the heard deck. */
    private fun announce() {
        val played = written - sink.queuedFrames()
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
