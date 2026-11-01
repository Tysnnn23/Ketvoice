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
    private var verifier = ""
    private var expectedState = ""

    private val main = Handler(Looper.getMainLooper())
    private val timeout = Runnable {
        if (awaitingCode) {
            clearPending()
            Discord.update {
                it.copy(busy = false, error = "Délai dépassé. Réessaie la liaison Discord.")
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
        if (!custom && !bridge) return
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
            if (linked) NativeDiscord.nativeFetchUser()
            pending?.let { pending = null; send(it) }
        }
        Discord.update { it.copy(ready = ready) }
    }

    private fun handleAuth(ok: Boolean, message: String, refreshToken: String) {
        main.removeCallbacks(timeout)
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
        message.contains("invalid_client", ignoreCase = true) ||
            message.contains("Invalid client", ignoreCase = true) ->
            "Échec de l'échange du jeton. Vérifie que « Client public » est activé dans le portail Discord."
        else -> message
    }

    companion object {
        private const val TAG = "KetvoiceDiscord"
        private const val STATUS_READY = 3
        private const val TIMEOUT_MS = 180_000L
        private const val RETURN_CANCEL_MS = 8_000L

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
