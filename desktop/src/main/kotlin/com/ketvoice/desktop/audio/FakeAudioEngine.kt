package com.ketvoice.desktop.audio

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * An [AudioEngine] without sound, for tests of the Player, the UI and the platform glue. Time is
 * driven by hand with [advance]; every call is recorded in [calls]. A loaded source lasts
 * [defaultDurationMs] unless [durations] names it.
 */
class FakeAudioEngine(var defaultDurationMs: Long = 180_000) : AudioEngine {
    private val _events = MutableSharedFlow<AudioEvent>(replay = 0, extraBufferCapacity = 64)
    override val events: SharedFlow<AudioEvent> = _events

    val calls = ArrayList<String>()
    val durations = HashMap<AudioSource, Long>()
    var current: AudioSource? = null; private set
    var next: AudioSource? = null; private set
    var equalizer: FloatArray? = null; private set
    var released = false; private set
    private var position = 0L
    private var playing = false

    override var volume: Float = 1f
        set(v) { field = v.coerceIn(0f, 1f); calls += "volume $field" }

    override fun load(source: AudioSource, startMs: Long, play: Boolean) {
        calls += "load $source $startMs $play"
        current = source; position = startMs.coerceAtLeast(0); playing = play
    }
    override fun preloadNext(source: AudioSource?) { calls += "preloadNext $source"; next = source }
    override fun play() { calls += "play"; if (current != null) playing = true }
    override fun pause() { calls += "pause"; playing = false }
    override fun seekTo(ms: Long) { calls += "seekTo $ms"; position = ms.coerceIn(0, durationMs()) }
    override fun positionMs() = position
    override fun durationMs(): Long = current?.let { durations[it] ?: defaultDurationMs } ?: 0
    override fun isPlaying() = playing
    override fun setEqualizer(gainsDb: FloatArray?) { calls += "eq ${gainsDb?.joinToString()}"; equalizer = gainsDb?.copyOf() }
    override fun release() { calls += "release"; released = true; playing = false; current = null }

    /** Moves time on while playing; at the end, switches to the preloaded source or emits Ended. */
    fun advance(ms: Long) {
        if (!playing || current == null) return
        var left = ms
        while (left > 0 && playing) {
            val room = durationMs() - position
            if (left < room) { position += left; return }
            left -= room
            val n = next
            if (n != null) { current = n; next = null; position = 0; emit(AudioEvent.Advanced(n)) }
            else { position = durationMs(); playing = false; emit(AudioEvent.Ended) }
        }
    }

    /** Sends an event as the engine would (Error, Buffering, Format…). */
    fun emit(event: AudioEvent) { _events.tryEmit(event) }
}
