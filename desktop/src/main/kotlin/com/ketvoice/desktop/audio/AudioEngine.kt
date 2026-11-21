package com.ketvoice.desktop.audio

import kotlinx.coroutines.flow.SharedFlow

/** What the desktop player plays. Contract shared by the Phase 1 agents; keep the names. */
interface AudioEngine {
    /** Ended, Error, Buffering, Format, Advanced. */
    val events: SharedFlow<AudioEvent>
    /** Replaces the current deck. */
    fun load(source: AudioSource, startMs: Long = 0, play: Boolean = true)
    /** Gapless: decoded ahead, starts exactly when the current one ends. Null clears it. */
    fun preloadNext(source: AudioSource?)
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun positionMs(): Long
    fun durationMs(): Long
    fun isPlaying(): Boolean
    /** 0..1, smoothed. */
    var volume: Float
    /** 10 bands at the EqualizerProfile frequencies, in dB, or null for flat. */
    fun setEqualizer(gainsDb: FloatArray?)
    fun release()
}

sealed interface AudioSource {
    data class Url(val url: String, val headers: Map<String, String> = emptyMap()) : AudioSource
    data class LocalFile(val path: String) : AudioSource
}

sealed interface AudioEvent {
    /** The current source played to its end and nothing preloaded followed it. */
    data object Ended : AudioEvent
    data class Error(val message: String) : AudioEvent
    data class Buffering(val buffering: Boolean) : AudioEvent
    /**
     * The source's real format (before resampling), for the Lossless badge. [bitDepth] is 0 for
     * lossy codecs; [bitrate] is in bits per second, 0 when unknown.
     */
    data class Format(val codec: String, val sampleRate: Int, val bitDepth: Int, val channels: Int, val bitrate: Long) : AudioEvent {
        val lossless: Boolean get() = codec in LOSSLESS
    }
    /**
     * Addition to the brief's list: the preloaded source took over gaplessly, so the Player
     * advances its queue (and the source's own [Format] follows).
     */
    data class Advanced(val source: AudioSource) : AudioEvent

    companion object {
        val LOSSLESS = setOf("flac", "alac", "wav", "pcm", "ape", "wavpack", "tta", "truehd", "mlp")
    }
}
