package com.ketvoice.app.account

import android.content.Context
import com.ketvoice.app.data.SecureSessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Who is signed in to Ketvoice on this computer. [message] tells why a session ended by itself. */
data class AccountState(val userId: String? = null, val email: String = "", val message: String? = null) {
    val signedIn: Boolean get() = userId != null
}

/**
 * Desktop counterpart of the Android `AccountSession`: the same Ketvoice account (Supabase Auth over
 * the shared [AuthApi], [AccountTokens] and [SupabaseHttp]), e-mail and password or Discord. Discord's
 * answer comes back to a one-shot server on 127.0.0.1 ([LoopbackRedirect]) instead of an Android
 * intent; its addresses must be allowed in the Supabase project's redirect URLs. The session is sealed
 * with Windows DPAPI ([SecureSessionStore]); nothing is sent anywhere until the listener signs in, and
 * only to the app's own Supabase. A listener account never gains a publishing right: the verified
 * publishing session keeps its own store, checks and server rules.
 */
object AccountSession {
    /** Discord's sign-in comes back to the first free one: http://127.0.0.1:<port>/callback. */
    val LOOPBACK_PORTS = listOf(47821, 47822, 47823)
    private const val STORE_KEY = "account-session"

    private val _state = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = _state
    private val _discordWaiting = MutableStateFlow(false)
    /** True while the browser shows Discord's sign-in and Ketvoice waits for its answer. */
    val discordWaiting: StateFlow<Boolean> = _discordWaiting
    private var store: SecureSessionStore? = null
    private var appContext: Context? = null
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val tokens = AccountTokens(
        refresher = { refresh -> withContext(Dispatchers.IO) { AuthApi.refresh(refresh, System::currentTimeMillis) } },
        persist = ::save,
        onRejected = { message -> signedOutLocally(message) })

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context
        runCatching { store = SecureSessionStore(context) }
        Profiles.init(context)
        val saved = runCatching { store?.get(STORE_KEY)?.let(::decode) }.getOrNull() ?: return
        tokens.set(saved)
        _state.value = AccountState(saved.userId, saved.email)
        afterSignIn(fresh = false)
    }

    private fun save(session: AuthSession?) {
        val s = store ?: return
        if (session == null) s.remove(STORE_KEY) else s.put(STORE_KEY, encode(session))
    }

    internal fun encode(session: AuthSession): String = JSONObject().put("refresh", session.refresh).put("access", session.access)
        .put("expiresAt", session.expiresAt).put("user", session.userId).put("email", session.email).toString()

    internal fun decode(text: String): AuthSession? = runCatching {
        val json = JSONObject(text)
        AuthSession(json.optString("access"), json.getString("refresh"), json.optLong("expiresAt"),
            json.getString("user"), json.optString("email")).takeIf { it.refresh.isNotBlank() && it.userId.isNotBlank() }
    }.getOrNull()

    suspend fun signUp(displayName: String, email: String, password: String) = withContext(Dispatchers.IO) {
        val name = AccountRules.displayName(displayName)
        AccountRules.checkCredentials(email, password)
        accept(AuthApi.signUp(email.trim(), password, name, System::currentTimeMillis), name)
    }

    suspend fun signIn(email: String, password: String) = withContext(Dispatchers.IO) {
        AccountRules.checkCredentials(email, password, signUp = false)
        accept(AuthApi.signIn(email.trim(), password, System::currentTimeMillis), null)
    }

    /**
     * Discord through the browser (PKCE): [open] shows Supabase's authorize page, whose answer comes back
     * to 127.0.0.1. Cancelling the calling coroutine stops the wait and closes the server.
     */
    suspend fun signInWithDiscord(open: (String) -> Unit = ::browse, waitMs: Long = 10 * 60_000L) = withContext(Dispatchers.IO) {
        val verifier = Pkce.verifier()
        val redirect = LoopbackRedirect.start(LOOPBACK_PORTS)
        _discordWaiting.value = true
        try {
            open(AuthApi.authorizeUrl("discord", redirect.url, Pkce.challenge(verifier)))
            val answer = redirect.await(waitMs) ?: error("Connexion expirée. Réessayez.")
            if (answer.error != null) error("Connexion Discord annulée.")
            val code = answer.code?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,200}")) } ?: error("Lien invalide")
            accept(AuthApi.exchangeCode(code, verifier, System::currentTimeMillis), null)
        } finally {
            redirect.close()
            _discordWaiting.value = false
        }
    }

    /**
     * Sign-in by QR code (Windows 2.0): the website approved this PC and the `device-link` function gave a one-time
     * token hash, exchanged here for this PC's own session (`/auth/v1/verify`).
     */
    suspend fun signInWithLink(tokenHash: String) = withContext(Dispatchers.IO) {
        require(tokenHash.matches(Regex("[A-Za-z0-9_-]{16,200}"))) { "Lien invalide" }
        val body = JSONObject().put("type", "magiclink").put("token_hash", tokenHash).toString().toByteArray()
        val response = SupabaseHttp.request("POST", "/auth/v1/verify", body)
        val json = runCatching { JSONObject(response.text) }.getOrNull()
        if (!response.ok || json == null) throw SupabaseHttp.HttpError(response.status, json?.optString("error_code").orEmpty(),
            AuthApi.message(response.status, json))
        accept(AuthApi.parseSession(json, System.currentTimeMillis()), null)
    }

    /**
     * The session already ended on the server (another device signed this one out, or the account was deleted): this
     * PC forgets it at once, with [message] (French) shown in the account card.
     */
    fun endedOnServer(message: String) = signedOutLocally(message)

    private fun browse(url: String) {
        val uri = java.net.URI(url)
        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
            java.awt.Desktop.getDesktop().browse(uri)
        } else {
            ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri.toString()).start()
        }
    }

    private fun accept(session: AuthSession, displayName: String?) {
        tokens.set(session)
        _state.value = AccountState(session.userId, session.email)
        Profiles.signedIn(session.userId, displayName)
        afterSignIn(fresh = true)
    }

    private fun afterSignIn(fresh: Boolean) {
        val context = appContext ?: return
        scope.launch { runCatching { Profiles.refresh() } }
        com.ketvoice.app.sync.LibrarySync.signedIn(context, fresh)
    }

    /** Sends [call] with a valid token; on 401 the token is renewed once and the call sent again. */
    internal suspend fun authorized(call: (String) -> SupabaseHttp.Response): SupabaseHttp.Response {
        val first = call(tokens.access()).also(ServerClock::observe)
        if (first.status != 401) return first
        tokens.expire()
        return call(tokens.access()).also(ServerClock::observe)
    }

    fun userId(): String? = tokens.session?.userId

    suspend fun signOut() {
        com.ketvoice.app.sync.LibrarySync.flush()
        val access = tokens.session?.access.orEmpty()
        if (access.isNotBlank()) withContext(Dispatchers.IO) { AuthApi.signOut(access) }
        signedOutLocally(null)
    }

    private fun signedOutLocally(message: String?) {
        tokens.set(null)
        appContext?.let { context ->
            com.ketvoice.app.sync.LibrarySync.signedOut(context)
            Profiles.signedOut()
        }
        _state.value = AccountState(message = message)
    }

    /** For renders and tests: an account shown without any network. */
    internal fun showForTest(state: AccountState) { _state.value = state }
}

/** RFC 7636 S256, as the Android app's Discord sign-in makes it. */
internal object Pkce {
    private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun verifier(random: SecureRandom = SecureRandom()): String = base64Url(ByteArray(32).also(random::nextBytes))
    fun challenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

/**
 * The one-shot page OAuth comes back to: a server on 127.0.0.1 only (never the network), on the first
 * free port of a fixed list, answering a single `GET /callback?code=…`. A forged call cannot sign anyone
 * in: the code is only worth something with the PKCE verifier, which never leaves this process.
 */
internal class LoopbackRedirect private constructor(private val server: ServerSocket) : AutoCloseable {
    data class Answer(val code: String?, val error: String?)

    val url: String = "http://127.0.0.1:${server.localPort}/callback"
    private val answer = CompletableDeferred<Answer>()

    init { Thread(::serve, "oauth-loopback").apply { isDaemon = true }.start() }

    suspend fun await(timeoutMs: Long): Answer? = withTimeoutOrNull(timeoutMs) { answer.await() }

    private fun serve() {
        while (!server.isClosed && !answer.isCompleted) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return
            runCatching { socket.use(::handle) }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 5_000
        val line = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine().orEmpty()
        val target = line.split(' ').getOrNull(1).orEmpty()
        val result = if (line.startsWith("GET ") && target.substringBefore('?') == "/callback") {
            val query = query(target.substringAfter('?', ""))
            Answer(query["code"], query["error"] ?: if (query["code"] == null) "missing" else null)
        } else null
        val text = com.ketvoice.app.data.AppText.t(when {
            result == null -> "Page introuvable."
            result.error != null -> "Connexion Discord annulée."
            else -> "Connexion réussie. Vous pouvez fermer cet onglet et revenir à Ketvoice."
        })
        val body = ("<!doctype html><meta charset=utf-8><title>Ketvoice</title>" +
            "<body style=\"font:16px system-ui,sans-serif;margin:3em;color:#1c1c1e\"><p>${escape(text)}</p>").toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 ${if (result == null) "404 Not Found" else "200 OK"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); write(body); flush() }
        if (result != null) answer.complete(result)
    }

    override fun close() { runCatching { server.close() } }

    companion object {
        fun start(ports: List<Int>): LoopbackRedirect {
            val host = InetAddress.getByName("127.0.0.1")
            for (port in ports) runCatching { return LoopbackRedirect(ServerSocket(port, 4, host)) }
            error("Connexion impossible pour le moment.")
        }

        internal fun query(text: String): Map<String, String> = text.split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

        private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}

/** The listener's profile as this computer shows it: the name only (the photo stays on the phone and the web). */
data class AccountProfile(val displayName: String = "", val handle: String = "")

/**
 * Desktop counterpart of `Profiles`: reads the signed-in listener's name from `ketvoice_profiles` with
 * their own session (owner-only RLS). Creating, editing and the photo stay with the phone and the web.
 */
object Profiles {
    private const val PREFS = "ketvoice-account"
    private val _profile = MutableStateFlow(AccountProfile())
    val profile: StateFlow<AccountProfile> = _profile
    private var context: Context? = null

    fun init(context: Context) {
        if (this.context != null) return
        this.context = context
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _profile.value = AccountProfile(p.getString("name", "").orEmpty(), p.getString("handle", "").orEmpty())
    }

    private fun keep(profile: AccountProfile) {
        _profile.value = profile
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString("name", profile.displayName)?.putString("handle", profile.handle)?.apply()
    }

    internal fun signedIn(userId: String, displayName: String?) {
        if (!displayName.isNullOrBlank()) keep(AccountProfile(displayName))
    }

    internal fun signedOut() {
        _profile.value = AccountProfile()
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val uid = AccountSession.userId() ?: return@withContext
        val response = AccountSession.authorized { token ->
            SupabaseHttp.request("GET", "/rest/v1/ketvoice_profiles?select=display_name,handle&id=eq.$uid", token = token)
        }
        if (!response.ok) return@withContext
        val row = JSONArray(response.text).optJSONObject(0) ?: return@withContext
        keep(AccountProfile(if (row.isNull("display_name")) "" else row.optString("display_name"),
            if (row.isNull("handle")) "" else row.optString("handle")))
    }
}

/** What the forms accept before anything is sent (the Android rules). */
internal object AccountRules {
    private val email = Regex("[^@\\s]{1,64}@[^@\\s]{1,190}\\.[^@\\s.]{2,24}")

    fun checkCredentials(address: String, password: String, signUp: Boolean = true) {
        require(address.trim().matches(email)) { "Adresse e-mail invalide." }
        require(!signUp || password.length >= 8) { "Mot de passe trop faible (8 caractères minimum)." }
        require(password.isNotEmpty() && password.length <= 72) { "Mot de passe invalide." }
    }

    fun displayName(value: String): String = value.trim().replace(Regex("\\s+"), " ").also {
        require(it.length in 1..40 && it.none(Char::isISOControl)) { "Nom invalide (1 à 40 caractères)." }
    }
}

/** The server's clock, learnt from its answers: sync times stay comparable between devices whose clocks differ. */
internal object ServerClock {
    @Volatile private var offset = 0L

    fun observe(response: SupabaseHttp.Response) {
        if (response.serverTimeMs > 0) offset = response.serverTimeMs - System.currentTimeMillis()
    }

    fun now(): Long = System.currentTimeMillis() + offset
}
