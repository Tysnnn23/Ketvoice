package com.ketvoice.app.discord

import android.app.Activity
import android.util.Log
import com.discord.socialsdk.DiscordSocialSdkInit
import com.ketvoice.app.KetvoiceApp

/**
 * Real account linking and live listening presence through the official Discord
 * Social SDK. The controller is instantiated by reflection from the main source
 * set, so builds without the owner-supplied AAR simply report the feature as
 * unavailable instead of shipping a broken button.
 */
class DiscordControllerImpl : DiscordController {

    private var activity: Activity? = null
    @Volatile private var created = false
    @Volatile private var linked = false
    @Volatile private var ready = false
    @Volatile private var pending: DiscordPresencePayload? = null
    @Volatile private var restoring = false

    private val tokens: DiscordTokenStore? by lazy {
        runCatching { KetvoiceApp.instance?.let(::DiscordTokenStore) }.getOrNull()
    }

    init {
        instance = this
    }

    override fun attach(activity: Activity) {
        this.activity = activity
        val contextReady = runCatching { DiscordSocialSdkInit.setEngineActivity(activity) }
            .onFailure { Log.w(TAG, "Discord engine activity unavailable", it) }
            .isSuccess
        // Never create the native client without the Context it needs.
        if (contextReady) ensureCreated()
    }

    override fun start() {
        if (linked) return
        ensureCreated()
        val stored = tokens?.load()
        if (stored.isNullOrBlank()) return
        linked = true
        restoring = true
        Discord.update { it.copy(linked = true, busy = true, error = null) }
        if (!NativeDiscord.nativeRestore(stored)) {
            restoring = false
            Discord.update { it.copy(busy = false, error = "Discord indisponible") }
        }
    }

    override fun link() {
        val host = activity
        if (host == null) {
            Discord.update { it.copy(error = "Ouvrez à nouveau Ketvoice pour lier Discord") }
            return
        }
        ensureCreated()
        if (!created) {
            Discord.update { it.copy(error = "Discord indisponible") }
            return
        }
        val contextReady = runCatching { DiscordSocialSdkInit.setEngineActivity(host) }.isSuccess
        if (!contextReady) {
            Discord.update { it.copy(busy = false, error = "Discord indisponible") }
            return
        }
        Discord.update { it.copy(busy = true, error = null) }
        if (!NativeDiscord.nativeAuthorize(null)) {
            Discord.update { it.copy(busy = false, error = "Discord indisponible") }
        }
    }

    override fun unlink() {
        pending = null
        linked = false
        ready = false
        tokens?.clear()
        runCatching {
            NativeDiscord.nativeClearPresence()
            NativeDiscord.nativeDisconnect()
        }
        Discord.update { DiscordState(available = true) }
    }

    override fun setPresence(payload: DiscordPresencePayload) {
        if (!linked) return
        if (!ready) {
            pending = payload
            return
        }
        send(payload)
    }

    override fun clearPresence() {
        pending = null
        runCatching { NativeDiscord.nativeClearPresence() }
    }

    private fun send(payload: DiscordPresencePayload) {
        val buttons = payload.buttons
        val fields = arrayOf(
            payload.details,
            payload.state,
            payload.largeImage.orEmpty(),
            payload.largeText,
            "",
            "",
            buttons.getOrNull(0)?.label.orEmpty(),
            buttons.getOrNull(0)?.url.orEmpty(),
            buttons.getOrNull(1)?.label.orEmpty(),
            buttons.getOrNull(1)?.url.orEmpty()
        )
        NativeDiscord.nativeSetPresence(fields, payload.startEpochMs)
    }

    private fun ensureCreated() {
        if (created) return
        created = runCatching {
            NativeDiscord.nativeCreate(APP_ID)
        }.onFailure { Log.w(TAG, "Discord client creation failed", it) }.getOrDefault(false)
    }

    private fun handleStatus(status: Int) {
        ready = status == STATUS_READY
        if (ready) {
            if (linked) NativeDiscord.nativeFetchUser()
            pending?.let { pending = null; send(it) }
        }
        Discord.update { it.copy(ready = ready) }
    }

    private fun handleAuth(ok: Boolean, message: String, refreshToken: String) {
        if (!ok) {
            // A transient refresh failure must not throw away a valid link.
            if (!restoring) linked = false
            restoring = false
            Discord.update { it.copy(linked = linked, busy = false, error = friendly(message)) }
            return
        }
        restoring = false
        linked = true
        if (refreshToken.isNotBlank()) tokens?.save(refreshToken)
        // Linking is an explicit opt-in for presence: the user just granted it.
        com.ketvoice.app.data.AppSettings.discordPresence.value = true
        com.ketvoice.app.data.SettingsStore.save()
        NativeDiscord.nativeConnect()
        Discord.update { it.copy(linked = true, busy = false, error = null) }
    }

    private fun handlePresence(ok: Boolean, message: String) {
        if (!ok) Log.w(TAG, "Presence update rejected: $message")
    }

    private fun handleUser(id: Long, username: String, displayName: String) {
        val name = displayName.ifBlank { username }
        if (name.isBlank()) return
        Discord.update { it.copy(userName = name) }
    }

    private fun friendly(message: String): String = when {
        message.isBlank() -> "La liaison Discord a échoué"
        message.contains("canceled", ignoreCase = true) ||
            message.contains("cancelled", ignoreCase = true) -> "Liaison Discord annulée"
        else -> message
    }

    companion object {
        private const val TAG = "KetvoiceDiscord"
        private const val APP_ID = 1566454532890820668L
        private const val STATUS_READY = 3

        @Volatile
        private var instance: DiscordControllerImpl? = null

        internal fun onStatus(status: Int) {
            instance?.handleStatus(status)
        }

        internal fun onAuth(ok: Boolean, message: String, refreshToken: String) {
            instance?.handleAuth(ok, message, refreshToken)
        }

        internal fun onPresence(ok: Boolean, message: String) {
            instance?.handlePresence(ok, message)
        }

        internal fun onUser(id: Long, username: String, displayName: String) {
            instance?.handleUser(id, username, displayName)
        }
    }
}
