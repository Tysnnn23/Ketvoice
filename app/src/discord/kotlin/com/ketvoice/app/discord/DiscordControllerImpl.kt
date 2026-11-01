package com.ketvoice.app.discord

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.discord.socialsdk.DiscordSocialSdkInit
import com.ketvoice.app.KetvoiceApp

/**
 * Real account linking and live listening presence through the official Discord
 * Social SDK. Ketvoice runs the OAuth2 + PKCE browser flow itself so the redirect
 * is guaranteed to return to [DiscordCallbackActivity]; the SDK only exchanges the
 * code, refreshes the token and owns the presence connection.
 */
class DiscordControllerImpl : DiscordController {

    private var activity: Activity? = null
    @Volatile private var created = false
    @Volatile private var linked = false
    @Volatile private var ready = false
    @Volatile private var pending: DiscordPresencePayload? = null
    @Volatile private var restoring = false
    @Volatile private var awaitingCode = false
    @Volatile private var restoringAccess = false
    private var pendingAccess: String? = null
    private var verifier = ""
    @Volatile private var discordLog = ""
    private var expectedState = ""

    private val main = Handler(Looper.getMainLooper())
    private val timeout = Runnable {
        if (awaitingCode) {
            clearPending()
            Discord.update {
                it.copy(busy = false, error = "Délai dépassé. Réessaie la liaison Discord.")
            }
            return@Runnable
        }
        // A Connect() attempt that never reaches Ready used to leave the panel
        // spinning forever. Report it instead of pretending something happened.
        if (Discord.state.value.busy && !ready) {
            Discord.update {
                it.copy(
                    busy = false,
                    error = "L'app Discord n'a pas répondu. Vérifie qu'elle est installée, " +
                        "ouverte et connectée, puis réessaie."
                )
            }
        }
    }

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
        val access = tokens?.loadAccess()
        if (!access.isNullOrBlank()) {
            linked = true
            restoring = true
            restoringAccess = true
            pendingAccess = null // already persisted; do not overwrite it
            Discord.update { it.copy(linked = true, busy = true, error = null) }
            main.removeCallbacks(timeout)
            main.postDelayed(timeout, CONNECT_TIMEOUT_MS)
            if (!NativeDiscord.nativeSetToken(access)) {
                restoringAccess = false
                Discord.update { it.copy(busy = false, error = "Discord indisponible") }
            }
            return
        }
        val stored = tokens?.load()
        if (stored.isNullOrBlank()) {
            // Presence-only mode resumes silently when the user enabled it.
            if (com.ketvoice.app.data.AppSettings.discordPresence.value) connect()
            return
        }
        linked = true
        restoring = true
        Discord.update { it.copy(linked = true, busy = true, error = null) }
        if (!NativeDiscord.nativeRestore(stored)) {
            restoring = false
            Discord.update { it.copy(busy = false, error = "Discord indisponible") }
        }
    }

    /**
     * Presence-only mode: the local Discord app (if installed and signed in)
     * publishes the activity without any OAuth redirect. This is the reliable
     * path on Android and needs no account-linking flow.
     */
    override fun connect() {
        val host = activity ?: return
        val contextReady = runCatching { DiscordSocialSdkInit.setEngineActivity(host) }.isSuccess
        if (!contextReady) {
            Discord.update { it.copy(error = "Discord indisponible") }
            return
        }
        ensureCreated()
        if (!created) {
            Discord.update { it.copy(error = "Discord indisponible") }
            return
        }
        Discord.update { it.copy(busy = true, error = null) }
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, CONNECT_TIMEOUT_MS)
        NativeDiscord.nativeConnect()
    }

    override fun connectWithToken(token: String) {
        val value = token.trim()
        if (value.length < 20) {
            Discord.update { it.copy(error = "Token Discord invalide") }
            return
        }
        val host = activity ?: return
        runCatching { DiscordSocialSdkInit.setEngineActivity(host) }
        ensureCreated()
        if (!created) {
            Discord.update { it.copy(error = "Discord indisponible") }
            return
        }
        pendingAccess = value
        restoringAccess = false
        Discord.update { it.copy(busy = true, error = null) }
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, CONNECT_TIMEOUT_MS)
        if (!NativeDiscord.nativeSetToken(value)) {
            pendingAccess = null
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
        verifier = DiscordOAuth.newVerifier()
        expectedState = DiscordOAuth.newState()
        awaitingCode = true
        Discord.update { it.copy(busy = true, error = null) }
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, TIMEOUT_MS)
        openAuthorization(host, DiscordOAuth.authorizeUrl(expectedState, DiscordOAuth.challenge(verifier)))
    }

    override fun completeAuthorization(uri: Uri?) {
        if (uri == null || !awaitingCode) return
        val custom = uri.scheme.equals("discord-${DiscordOAuth.APPLICATION_ID}", ignoreCase = true) &&
            uri.path == "/authorize/callback"
        val bridge = uri.scheme.equals("ketvoice", ignoreCase = true) &&
            uri.host.equals("discord", ignoreCase = true)
        val https = uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("ketvoice.netlify.app", ignoreCase = true) &&
            uri.path == "/discord.html"
        if (!custom && !bridge && !https) return
        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            val failure = if (error == "access_denied") "Liaison Discord annulée" else error
            clearPending()
            Discord.update { it.copy(busy = false, error = failure) }
            return
        }
        val state = uri.getQueryParameter("state")
        val code = uri.getQueryParameter("code")
        if (state != expectedState || code.isNullOrBlank()) {
            clearPending()
            Discord.update { it.copy(busy = false, error = "Réponse Discord invalide. Réessaie.") }
            return
        }
        val pendingVerifier = verifier
        // Keep the timeout running until the token exchange reports back.
        awaitingCode = false
        verifier = ""
        expectedState = ""
        if (!NativeDiscord.nativeExchange(code, pendingVerifier, DiscordOAuth.REDIRECT_URI)) {
            Discord.update { it.copy(busy = false, error = "Discord indisponible") }
        }
    }

    override fun onForeground() {
        // Returning from Discord without a code must not spin forever.
        if (awaitingCode) {
            main.removeCallbacks(timeout)
            main.postDelayed(timeout, RETURN_CANCEL_MS)
        }
    }

    override fun unlink() {
        clearPending()
        linked = false
        ready = false
        tokens?.clear()
        com.ketvoice.app.data.AppSettings.discordPresence.value = false
        com.ketvoice.app.data.SettingsStore.save()
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

    private fun clearPending() {
        awaitingCode = false
        verifier = ""
        expectedState = ""
        main.removeCallbacks(timeout)
    }

    private fun openAuthorization(context: Activity, url: String) {
        // Our own WebView intercepts the custom-scheme redirect before Android has
        // to resolve it. Browsers and the Discord app can silently drop that
        // navigation, which is what left the panel stuck.
        val opened = runCatching {
            context.startActivity(
                Intent(context, DiscordAuthActivity::class.java)
                    .putExtra(DiscordAuthActivity.EXTRA_URL, url)
            )
        }.isSuccess
        if (!opened) {
            runCatching {
                CustomTabsIntent.Builder().setShowTitle(true).build()
                    .launchUrl(context, Uri.parse(url))
            }.onFailure { Log.w(TAG, "No browser available for Discord authorization", it) }
        }
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
            NativeDiscord.nativeCreate(DiscordOAuth.APPLICATION_ID)
        }.onFailure { Log.w(TAG, "Discord client creation failed", it) }.getOrDefault(false)
    }

    private fun handleStatus(status: Int) {
        ready = status == STATUS_READY
        if (ready) {
            // Unauthenticated presence through the local Discord app still reaches
            // the user's profile; there is no token to store in that mode.
            linked = true
            main.removeCallbacks(timeout)
            if (tokens?.load().isNullOrBlank()) {
                // Presence-only mode is an explicit action: remember the opt-in.
                com.ketvoice.app.data.AppSettings.discordPresence.value = true
                com.ketvoice.app.data.SettingsStore.save()
            }
            NativeDiscord.nativeFetchUser()
            pending?.let { pending = null; send(it) }
            Discord.update { it.copy(linked = true, busy = false, ready = true, error = null) }
            return
        }
        if (status == STATUS_DISCONNECTED && Discord.state.value.busy && tokens?.load().isNullOrBlank()) {
            main.removeCallbacks(timeout)
            Discord.update {
                it.copy(
                    busy = false,
                    linked = false,
                    error = "Ouvre l'app Discord et connecte-toi, puis réessaie."
                )
            }
            return
        }
        Discord.update { it.copy(ready = ready) }
    }

    private fun handleAuth(ok: Boolean, message: String, refreshToken: String) {
        main.removeCallbacks(timeout)
        if (!ok) {
            // A stored access token that the SDK rejects must not be restored on
            // every launch: drop it and fall back to the local Discord app.
            val droppedStored = restoringAccess
            if (restoringAccess) {
                runCatching { tokens?.clearAccess() }
                restoringAccess = false
            }
            pendingAccess = null
            if (!restoring) linked = false
            restoring = false
            Discord.update { it.copy(linked = linked, busy = false, error = friendly(message)) }
            if (droppedStored) connect()
            return
        }
        restoring = false
        restoringAccess = false
        linked = true
        pendingAccess?.let { tokens?.saveAccess(it) }
        pendingAccess = null
        if (refreshToken.isNotBlank()) tokens?.save(refreshToken)
        // Linking is an explicit opt-in for presence: the user just granted it.
        com.ketvoice.app.data.AppSettings.discordPresence.value = true
        com.ketvoice.app.data.SettingsStore.save()
        NativeDiscord.nativeConnect()
        Discord.update { it.copy(linked = true, busy = false, error = null) }
    }

    private fun handleLog(message: String) {
        val line = message.trim().take(220)
        if (line.isEmpty()) return
        discordLog = line
        Discord.update { it.copy(log = line) }
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
        message.contains("invalid_client", ignoreCase = true) ||
            message.contains("Invalid client", ignoreCase = true) ->
            "Échec de l'échange du jeton. Vérifie que « Client public » est activé dans le portail Discord."
        else -> message
    }

    companion object {
        private const val TAG = "KetvoiceDiscord"
        private const val STATUS_DISCONNECTED = 0
        private const val STATUS_READY = 3
        private const val TIMEOUT_MS = 180_000L
        private const val RETURN_CANCEL_MS = 8_000L
        private const val CONNECT_TIMEOUT_MS = 12_000L

        @Volatile
        private var instance: DiscordControllerImpl? = null

        internal fun onStatus(status: Int) {
            instance?.handleStatus(status)
        }

        internal fun onAuth(ok: Boolean, message: String, refreshToken: String) {
            instance?.handleAuth(ok, message, refreshToken)
        }

        internal fun onLog(message: String) {
            instance?.handleLog(message)
        }

        internal fun onPresence(ok: Boolean, message: String) {
            instance?.handlePresence(ok, message)
        }

        internal fun onUser(id: Long, username: String, displayName: String) {
            instance?.handleUser(id, username, displayName)
        }
    }
}
