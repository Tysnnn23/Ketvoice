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
    @Volatile private var presenceOnly = false
    @Volatile private var connectFailed = false
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
    @Volatile private var retries = 0

    /**
     * The Social SDK only delivers status, auth and presence results from
     * `discordpp::RunCallbacks()`. Without this pump on the main thread no callback
     * ever fired, so presence results and the Ready state were never observed.
     */
    private val pump = object : Runnable {
        override fun run() {
            if (!created) return
            runCatching { NativeDiscord.nativeRunCallbacks() }
                .onFailure { Log.w(TAG, "Discord callbacks failed", it) }
            main.postDelayed(this, PUMP_INTERVAL_MS)
        }
    }

    private fun startPump() {
        if (!created) return
        main.removeCallbacks(pump)
        main.post(pump)
    }

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

    /**
     * A linked account whose SDK connection never reaches Ready used to keep
     * `busy` true with no deadline, which is the endless "waiting for the Discord
     * app" state. This deadline turns it into an explicit, retryable state.
     */
    private val presenceTimeout = Runnable {
        if (!linked || ready) return@Runnable
        Discord.update {
            it.copy(
                busy = false,
                error = "Compte lié, mais la présence Discord n'est pas active. " +
                    "Ouvre l'app Discord, vérifie que tu y es connecté, puis réessaie."
            )
        }
        scheduleReconnect()
    }

    private val reconnect = Runnable {
        if (!linked || ready || activity == null) return@Runnable
        NativeDiscord.nativeConnect()
        armPresenceTimeout()
    }

    private fun armPresenceTimeout() {
        main.removeCallbacks(presenceTimeout)
        main.postDelayed(presenceTimeout, READY_TIMEOUT_MS)
    }

    /** Backs off quickly, then stops instead of draining the battery. */
    private fun scheduleReconnect() {
        main.removeCallbacks(reconnect)
        if (!linked || ready) return
        val delay = when (retries) {
            0 -> 15_000L
            1 -> 45_000L
            2 -> 90_000L
            else -> {
                connectFailed = true
                Discord.update {
                    it.copy(
                        busy = false,
                        error = "La connexion au compte Discord échoue. Utilise « Afficher via l'app Discord » : cette présence ne dépend pas du compte."
                    )
                }
                return
            }
        }
        retries++
        main.postDelayed(reconnect, delay)
    }

    private val tokens: DiscordTokenStore? by lazy {
        runCatching { KetvoiceApp.instance?.let(::DiscordTokenStore) }.getOrNull()
    }
    private val pendingOAuth: DiscordPendingStore? by lazy {
        runCatching { KetvoiceApp.instance?.let(::DiscordPendingStore) }.getOrNull()
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
        if (linked || presenceOnly) return
        ensureCreated()
        if (com.ketvoice.app.data.AppSettings.discordPresence.value) {
            // Presence does not need an account: publish through the installed
            // Discord app over RPC instead of restoring a gateway token. The
            // gateway path used to retry forever when it never became Ready.
            enablePresenceOnly()
        }
    }

    /**
     * Rich Presence over RPC: a bare client that only knows the application ID.
     * The SDK talks to the installed, signed-in Discord app, so no OAuth token,
     * no `Connect()` and no `Ready` state are involved.
     */
    override fun enablePresenceOnly() {
        ensureCreated()
        presenceOnly = true
        linked = false
        ready = false
        busyClear()
        retries = 0
        connectFailed = false
        clearPending()
        main.removeCallbacks(presenceTimeout)
        main.removeCallbacks(reconnect)
        val reset = created && runCatching { NativeDiscord.nativeReset() }.getOrDefault(false)
        created = reset
        if (!created) ensureCreated() else startPump()
        com.ketvoice.app.data.AppSettings.discordPresence.value = true
        com.ketvoice.app.data.SettingsStore.save()
        Discord.update {
            it.copy(linked = false, busy = false, ready = false, presenceOnly = true,
                presenceActive = false, presenceError = null, error = null)
        }
        pending?.let { pending = null; send(it) }
    }

    override fun disablePresenceOnly() {
        presenceOnly = false
        busyClear()
        pending = null
        runCatching { NativeDiscord.nativeClearPresence() }
        com.ketvoice.app.data.AppSettings.discordPresence.value = false
        com.ketvoice.app.data.SettingsStore.save()
        Discord.update {
            it.copy(presenceOnly = false, presenceActive = false, presenceError = null)
        }
    }

    private fun busyClear() {
        Discord.update { it.copy(busy = false) }
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
        Discord.update {
            it.copy(busy = true, error = null, presenceOnly = false,
                presenceActive = false, presenceError = null)
        }
        retries = 0
        connectFailed = false
        presenceOnly = false
        main.removeCallbacks(timeout)
        armPresenceTimeout()
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
        retries = 0
        connectFailed = false
        presenceOnly = false
        Discord.update {
            it.copy(busy = true, error = null, presenceOnly = false,
                presenceActive = false, presenceError = null)
        }
        main.removeCallbacks(timeout)
        armPresenceTimeout()
        if (!NativeDiscord.nativeSetToken(value)) {
            pendingAccess = null
            main.removeCallbacks(presenceTimeout)
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
        retries = 0
        connectFailed = false
        presenceOnly = false
        // Survives a process restart while the browser/WebView is in front.
        pendingOAuth?.save(expectedState, verifier)
        Discord.update {
            it.copy(busy = true, error = null, presenceOnly = false,
                presenceActive = false, presenceError = null)
        }
        main.removeCallbacks(presenceTimeout)
        main.removeCallbacks(reconnect)
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, TIMEOUT_MS)
        openAuthorization(host, DiscordOAuth.authorizeUrl(expectedState, DiscordOAuth.challenge(verifier)))
    }

    override fun completeAuthorization(uri: Uri?) {
        // Recover an authorization that was in flight when Android recreated us.
        if (!awaitingCode) {
            val store = pendingOAuth
            if (store != null && store.fresh()) {
                expectedState = store.state().orEmpty()
                verifier = store.verifier().orEmpty()
                awaitingCode = expectedState.isNotBlank() && verifier.isNotBlank()
            }
        }
        if (uri == null || !awaitingCode) return
        val redirect = DiscordOAuth.redirect(uri.toString(), expectedState)
        val code = redirect?.code
        val pendingVerifier = verifier
        if (code.isNullOrBlank()) {
            clearPending()
            pendingOAuth?.clear()
            main.removeCallbacks(presenceTimeout)
            Discord.update { it.copy(busy = false, error = "Réponse Discord invalide. Réessaie.") }
            return
        }
        awaitingCode = false
        Discord.update { it.copy(busy = true, error = null) }
        // Exchange on a worker: the Activity must not block on the network.
        Thread({
            val tokens = DiscordOAuth.exchange(code, pendingVerifier)
            main.post {
                verifier = ""
                expectedState = ""
                pendingOAuth?.clear()
                if (tokens != null) {
                    linked = true
                    restoring = false
                    retries = 0
                    pendingAccess = tokens.access
                    runCatching { this.tokens?.save(tokens.refresh) }
                    runCatching { this.tokens?.saveAccess(tokens.access) }
                    com.ketvoice.app.data.AppSettings.discordPresence.value = true
                    com.ketvoice.app.data.SettingsStore.save()
                    main.removeCallbacks(timeout)
                    Discord.update { it.copy(linked = true, busy = true, ready = false, error = null) }
                    // The token exchange is ours, so the SDK can still refuse the
                    // connection silently. Never leave `busy` without a deadline.
                    armPresenceTimeout()
                    if (!runCatching { NativeDiscord.nativeSetToken(tokens.access) }.getOrDefault(false)) {
                        // The account is linked even when the SDK cannot start here.
                        main.removeCallbacks(presenceTimeout)
                        Discord.update { it.copy(linked = true, busy = false, ready = false,
                            error = "Compte lié. Ouvre l'app Discord pour publier l'écoute.") }
                    }
                } else if (!runCatching {
                        NativeDiscord.nativeExchange(code, pendingVerifier, DiscordOAuth.REDIRECT_URI)
                    }.getOrDefault(false)) {
                    main.removeCallbacks(timeout)
                    main.removeCallbacks(presenceTimeout)
                    Discord.update { it.copy(busy = false,
                        error = "Échec de l'échange du jeton Discord. Vérifie le redirect et le mode client public.") }
                } else {
                    // The SDK owns the exchange now; keep a deadline too.
                    armPresenceTimeout()
                }
            }
        }, "discord-token-exchange").start()
    }

    override fun onForeground() {
        // Returning from Discord without a code must not spin forever.
        if (awaitingCode) {
            main.removeCallbacks(timeout)
            main.postDelayed(timeout, RETURN_CANCEL_MS)
        }
        // No automatic Connect() retry: a linked gateway that never becomes
        // Ready used to restart on every foreground. Presence now uses RPC.
    }

    override fun unlink() {
        clearPending()
        linked = false
        presenceOnly = false
        connectFailed = false
        ready = false
        retries = 0
        main.removeCallbacks(presenceTimeout)
        main.removeCallbacks(reconnect)
        tokens?.clear()
        com.ketvoice.app.data.AppSettings.discordPresence.value = false
        com.ketvoice.app.data.SettingsStore.save()
        runCatching {
            NativeDiscord.nativeClearPresence()
            NativeDiscord.nativeDisconnect()
        }
        Discord.update { DiscordState(available = true) }
    }

    override fun authenticated(): Boolean =
        runCatching { NativeDiscord.nativeIsAuthenticated() }.getOrDefault(false)

    override fun setPresence(payload: DiscordPresencePayload) {
        // RPC presence goes straight to the installed Discord app: there is no
        // account, no gateway status and therefore no Ready state to wait for.
        if (presenceOnly) {
            pending = null
            send(payload)
            return
        }
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
        startPump()
    }

    private fun handleStatus(status: Int) {
        // In RPC mode there is deliberately no gateway connection or Ready state.
        if (presenceOnly) return
        val wasReady = ready
        ready = status == STATUS_READY
        if (ready) {
            // Unauthenticated presence through the local Discord app still reaches
            // the user's profile; there is no token to store in that mode.
            linked = true
            retries = 0
            main.removeCallbacks(timeout)
            main.removeCallbacks(presenceTimeout)
            main.removeCallbacks(reconnect)
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
            main.removeCallbacks(presenceTimeout)
            Discord.update {
                it.copy(
                    busy = false,
                    linked = false,
                    error = "Ouvre l'app Discord et connecte-toi, puis réessaie."
                )
            }
            return
        }
        if (wasReady && linked) {
            // A ready presence that drops must retry instead of freezing the UI.
            Discord.update { it.copy(ready = false, busy = true) }
            armPresenceTimeout()
            scheduleReconnect()
        } else {
            Discord.update { it.copy(ready = false) }
        }
    }

    private fun handleAuth(ok: Boolean, message: String, refreshToken: String) {
        if (presenceOnly) return
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
            main.removeCallbacks(presenceTimeout)
            Discord.update { it.copy(linked = linked, busy = false, error = friendly(message)) }
            if (droppedStored) connect()
            return
        }
        restoring = false
        restoringAccess = false
        linked = true
        retries = 0
        pendingAccess?.let { tokens?.saveAccess(it) }
        pendingAccess = null
        if (refreshToken.isNotBlank()) tokens?.save(refreshToken)
        // Linking is an explicit opt-in for presence: the user just granted it.
        com.ketvoice.app.data.AppSettings.discordPresence.value = true
        com.ketvoice.app.data.SettingsStore.save()
        // Authentication succeeded, but presence only works once status is Ready.
        armPresenceTimeout()
        NativeDiscord.nativeConnect()
        Discord.update { it.copy(linked = true, busy = true, ready = false, error = null) }
    }

    private fun handleLog(message: String) {
        val line = message.trim().take(220)
        if (line.isEmpty()) return
        discordLog = line
        Discord.update { it.copy(log = line) }
    }

    private fun handlePresence(ok: Boolean, message: String) {
        if (!ok) Log.w(TAG, "Presence update rejected: $message")
        val detail = message.trim().take(180).ifBlank { null }
        Discord.update {
            it.copy(
                presenceActive = ok,
                presenceError = if (ok) null
                else (detail ?: "Discord n'a pas accepté la présence")
            )
        }
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
        private const val READY_TIMEOUT_MS = 25_000L
        private const val PUMP_INTERVAL_MS = 50L

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
