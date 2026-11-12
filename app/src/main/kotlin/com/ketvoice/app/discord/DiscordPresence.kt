package com.ketvoice.app.discord

import android.app.Activity

/** One presence button, matching Discord's two-button limit. */
data class DiscordButton(val label: String, val url: String)

/**
 * Renderable rich presence. Built in the main source set so the payload rules are
 * unit-tested even when the SDK binary is not present.
 */
data class DiscordPresencePayload(
    val details: String,
    val state: String,
    val largeImage: String?,
    val largeText: String,
    val startEpochMs: Long,
    val buttons: List<DiscordButton>
)

object DiscordPresence {
    const val DOWNLOAD_URL = "https://ketvoice.netlify.app/"
    const val DOWNLOAD_LABEL = "Download ketvoice"
    const val LISTEN_LABEL = "Listen now"

    private const val MAX_TEXT = 128
    private const val MAX_ASSET = 300
    private const val MAX_URL = 512
    private const val MAX_LABEL = 32

    private fun clamp(value: String, limit: Int, fallback: String): String {
        val cleaned = value.replace(Regex("\\s+"), " ").trim()
        val safe = cleaned.ifBlank { fallback }
        return if (safe.length <= limit) safe else safe.take(limit - 1).trimEnd() + "…"
    }

    /**
     * Discord only renders an activity while something is actually playing.
     * Returns null when paused/stopped so the caller clears the presence instead
     * of showing a frozen track.
     */
    fun fromTrack(
        title: String,
        artist: String,
        coverUrl: String?,
        playing: Boolean,
        positionMs: Long,
        nowMs: Long,
        listenUrl: String?
    ): DiscordPresencePayload? {
        if (!playing) return null
        val details = clamp(title, MAX_TEXT, "Ketvoice")
        val state = clamp(artist, MAX_TEXT, "Ketvoice")
        val cover = coverUrl?.takeIf { it.startsWith("https://") && it.length <= MAX_ASSET }
        val buttons = buildList {
            add(DiscordButton(DOWNLOAD_LABEL, DOWNLOAD_URL))
            listenUrl?.takeIf { it.startsWith("https://") && it.length <= MAX_URL }
                ?.let { add(DiscordButton(LISTEN_LABEL, it)) }
        }
        return DiscordPresencePayload(
            details = details,
            state = state,
            largeImage = cover,
            largeText = clamp("$title — $artist", MAX_TEXT, details),
            startEpochMs = (nowMs - positionMs.coerceAtLeast(0)).coerceAtLeast(0),
            buttons = buttons.take(2).map { it.copy(label = clamp(it.label, MAX_LABEL, "Ketvoice")) }
        )
    }
}

data class DiscordState(
    val available: Boolean = false,
    val linked: Boolean = false,
    val busy: Boolean = false,
    val ready: Boolean = false,
    val userName: String? = null,
    val error: String? = null,
    val log: String? = null,
    /** Presence through the installed Discord app over RPC, no account linking. */
    val presenceOnly: Boolean = false,
    val presenceActive: Boolean = false,
    val presenceError: String? = null
)

/**
 * Implemented by the optional Discord source set, which is compiled only when the
 * owner-supplied Social SDK AAR is present.
 */
interface DiscordController {
    fun attach(activity: Activity)
    fun start()
    fun connect()
    fun connectWithToken(token: String)
    fun link()
    fun completeAuthorization(uri: android.net.Uri?)
    fun onForeground()
    fun unlink()
    fun authenticated(): Boolean
    fun enablePresenceOnly()
    fun disablePresenceOnly()
    fun setPresence(payload: DiscordPresencePayload)
    fun clearPresence()
}

object Discord {
    private val controller: DiscordController? = runCatching {
        Class.forName("com.ketvoice.app.discord.DiscordControllerImpl")
            .getDeclaredConstructor().newInstance() as DiscordController
    }.getOrNull()

    val available: Boolean get() = controller != null

    private val holder = kotlinx.coroutines.flow.MutableStateFlow(
        DiscordState(available = available)
    )
    val state: kotlinx.coroutines.flow.StateFlow<DiscordState> = holder

    internal fun publish(next: DiscordState) {
        holder.value = next
    }

    internal fun update(block: (DiscordState) -> DiscordState) {
        holder.value = block(holder.value)
    }

    fun attach(activity: Activity) = controller?.attach(activity)
    fun start() = controller?.start()
    fun connect() = controller?.connect()
    fun connectWithToken(token: String) = controller?.connectWithToken(token)
    fun link() = controller?.link()
    fun completeAuthorization(uri: android.net.Uri?) = controller?.completeAuthorization(uri)
    fun onForeground() = controller?.onForeground()
    fun unlink() = controller?.unlink()
    fun authenticated(): Boolean = controller?.authenticated() == true
    fun enablePresenceOnly() = controller?.enablePresenceOnly()
    fun disablePresenceOnly() = controller?.disablePresenceOnly()
    fun setPresence(payload: DiscordPresencePayload) = controller?.setPresence(payload)
    fun clearPresence() = controller?.clearPresence()
}
