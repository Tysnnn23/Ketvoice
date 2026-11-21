package com.ketvoice.desktop.audio

import kotlinx.coroutines.flow.SharedFlow

/**
 * The desktop audio engine (Phase 1 contract, implemented by the FFmpeg engine). [events] reports
 * [AudioEvent.Ended] each time a deck reaches its end, including when the deck given to
 * [preloadNext] takes over without a gap.
 */
interface AudioEngine {
    val events: SharedFlow<AudioEvent>
    /** Replaces the current deck. */
    fun load(source: AudioSource, startMs: Long = 0, play: Boolean = true)
    /** Gapless: decoded ahead, starts exactly when the current one ends; null forgets it. */
    fun preloadNext(source: AudioSource?)
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun positionMs(): Long
    fun durationMs(): Long
    fun isPlaying(): Boolean
    /** 0..1, smoothed. */
    var volume: Float
    /** 10 bands (EqualizerProfile frequencies) or null for flat. */
    fun setEqualizer(gainsDb: FloatArray?)
    fun release()
}

sealed interface AudioEvent {
    data object Ended : AudioEvent
    data class Error(val message: String) : AudioEvent
    data class Buffering(val buffering: Boolean) : AudioEvent
    data class Format(val codec: String, val sampleRate: Int, val bitDepth: Int, val channels: Int, val bitrate: Int) : AudioEvent
}

sealed interface AudioSource {
    data class Url(val url: String, val headers: Map<String, String> = emptyMap()) : AudioSource
    data class LocalFile(val path: String) : AudioSource
}
