package com.ketvoice.app.cast

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Google Cast (7.5, owner pick « Google Cast »). The Cast SDK exists only in the phone flavours (mobile, Lite);
 * Quest has no Play services and its CastFlavor does nothing. What the screens read lives here, in every flavour.
 *
 * While a Cast session runs, [remote] is the player the media session shows (a CastPlayer behind a bridge): the
 * app, the notification, the lock screen and headsets then drive the Cast device. The queue stays in the app as
 * always (the engine holds one title): the device receives the title that plays, the app sends the next one when
 * it ends. Ketmix, the voice level and the equalizer work on the phone's own sound, so they do not apply then.
 */
object CastState {
    data class Device(val id: String, val name: String, val description: String?)

    /** Play services and the Cast framework answered (false on Quest and on phones without Play services). */
    val supported = MutableStateFlow(false)
    val devices = MutableStateFlow<List<Device>>(emptyList())
    /** Route id being connected to. */
    val connecting = MutableStateFlow<String?>(null)
    /** Name of the device that plays, while a session runs. */
    val connected = MutableStateFlow<String?>(null)
    /** A short French message for the snackbar (AppText localizes it); cleared once shown. */
    val message = MutableStateFlow<String?>(null)

    /** The session's player while casting; null when the phone plays. */
    @Volatile var remote: androidx.media3.common.Player? = null

    val active: Boolean get() = remote != null

    fun say(text: String) { message.value = text }
}

/** What PlaybackService lends the Cast code: its engine, and a way to show the right player to the session. */
interface CastHost {
    val local: androidx.media3.exoplayer.ExoPlayer
    /** Puts [CastState.remote] (or the local engine when null) behind the media session. */
    fun refreshSession()
}

/** What to hand the receiver for a media item: the stream itself, a file served from the phone, or nothing. */
sealed interface CastSource {
    data class Remote(val url: String, val contentType: String) : CastSource
    data class Local(val uri: String, val contentType: String) : CastSource
    data class Unsupported(val reason: String) : CastSource
}

object CastPlan {
    const val DASH = "application/dash+xml"

    /**
     * Streams go as they are: the receiver is on the same network, so the same public address, and Ketvoice's
     * streams need no header the receiver cannot send (only a browser User-Agent, which it has). Downloads and
     * local music are served from the phone. A download kept as offline DASH segments cannot be handed over, and
     * an address on the phone itself (127.x) means nothing to the receiver.
     */
    fun decide(uri: String?, mimeType: String?, offlineDash: Boolean): CastSource {
        if (uri.isNullOrBlank()) return CastSource.Unsupported("no-uri")
        if (offlineDash) return CastSource.Unsupported("offline-dash")
        val type = contentType(uri, mimeType)
        return when (uri.substringBefore(':', "").lowercase()) {
            "http", "https" -> if (onThisPhone(uri)) CastSource.Unsupported("loopback") else CastSource.Remote(uri, type)
            "file", "content" -> if (type == DASH) CastSource.Unsupported("local-dash") else CastSource.Local(uri, type)
            else -> CastSource.Unsupported("scheme")
        }
    }

    fun contentType(uri: String, mimeType: String?): String {
        mimeType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { return it }
        val ext = uri.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return when (ext) {
            "m4a", "mp4", "aac", "alac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "ogg", "opus", "oga" -> "audio/ogg"
            "webm", "weba" -> "audio/webm"
            "wav" -> "audio/wav"
            "mpd" -> DASH
            "m3u8" -> "application/x-mpegURL"
            else -> "audio/mpeg"
        }
    }

    private fun onThisPhone(uri: String): Boolean {
        val host = uri.substringAfter("://").substringBefore('/').substringBefore('?').substringAfterLast('@')
            .removePrefix("[").substringBefore("]").substringBefore(':').lowercase()
        return host == "localhost" || host.startsWith("127.") || host == "" || host == "0.0.0.0"
    }
}
