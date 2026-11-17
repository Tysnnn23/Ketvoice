package com.ketvoice.app.account

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ketvoice.app.data.SecureSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Who is signed in to Ketvoice on this device. [message] tells why a session ended by itself. */
data class AccountState(val userId: String? = null, val email: String = "", val message: String? = null) {
    val signedIn: Boolean get() = userId != null
}

/**
 * The Ketvoice account (like Spotify's): e-mail and password, or Discord through Supabase Auth; no
 * two-step verification and no confirmation e-mail. The session is kept encrypted with an Android
 * Keystore key ([SecureSessionStore]); nothing is sent anywhere until the listener signs in.
 * Separate from the verified publishing session, which keeps its own store, checks and server rules.
 */
object AccountSession {
    const val OAUTH_REDIRECT = "ketvoice://auth/account"
    private const val STORE_KEY = "account-session"
    private const val PKCE_KEY = "account-pkce"

    private val _state = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = _state
    private var store: SecureSessionStore? = null
    private var appContext: Context? = null
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val tokens = AccountTokens(
        refresher = { refresh -> withContext(Dispatchers.IO) { AuthApi.refresh(refresh, System::currentTimeMillis) } },
        persist = ::save,
        onRejected = { message -> signedOutLocally(message) })

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        runCatching { store = SecureSessionStore(context.applicationContext) }
        val saved = runCatching { store?.get(STORE_KEY)?.let(::decode) }.getOrNull() ?: return
        tokens.set(saved)
        _state.value = AccountState(saved.userId, saved.email)
        Profiles.init(context)
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

    /** Opens Discord's sign-in in the browser; the answer comes back through [completeOAuth]. */
    fun startDiscord(context: Context) {
        val verifier = com.ketvoice.app.discord.DiscordOAuth.newVerifier()
        store?.put(PKCE_KEY, JSONObject().put("verifier", verifier).put("at", System.currentTimeMillis()).toString())
        val url = AuthApi.authorizeUrl("discord", OAUTH_REDIRECT, com.ketvoice.app.discord.DiscordOAuth.challenge(verifier))
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun isOAuthRedirect(uri: Uri): Boolean = uri.scheme == "ketvoice" && uri.host == "auth" && uri.path == "/account"

    suspend fun completeOAuth(uri: Uri) = withContext(Dispatchers.IO) {
        require(isOAuthRedirect(uri)) { "Lien invalide" }
        val pending = store?.get(PKCE_KEY)?.let { runCatching { JSONObject(it) }.getOrNull() }
        store?.remove(PKCE_KEY)
        uri.getQueryParameter("error")?.let { error("Connexion Discord annulée.") }
        val code = uri.getQueryParameter("code")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,200}")) } ?: error("Lien invalide")
        val verifier = pending?.optString("verifier")?.takeIf { it.isNotBlank() } ?: error("Connexion expirée. Réessayez.")
        require(System.currentTimeMillis() - pending.optLong("at") < 15 * 60_000L) { "Connexion expirée. Réessayez." }
        accept(AuthApi.exchangeCode(code, verifier, System::currentTimeMillis), null)
    }

    private fun accept(session: AuthSession, displayName: String?) {
        tokens.set(session)
        _state.value = AccountState(session.userId, session.email)
        appContext?.let { Profiles.init(it) }
        Profiles.signedIn(session.userId, displayName)
        afterSignIn(fresh = true)
    }

    private fun afterSignIn(fresh: Boolean) {
        val context = appContext ?: return
        scope.launch { runCatching { Profiles.refresh() } }
        com.ketvoice.app.sync.LibrarySync.signedIn(context, fresh)
        com.ketvoice.app.sync.NowPlayingSync.signedIn(context)
        com.ketvoice.app.sync.SharedPlaylists.signedIn(context)
        PublicProfiles.signedIn(context)
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

    /** Removes the profile photo, then the account (and with it the profile and the synced library) on the server. */
    suspend fun deleteAccount() = withContext(Dispatchers.IO) {
        Profiles.deleteAllAvatars()
        val response = authorized { token ->
            SupabaseHttp.request("POST", "/rest/v1/rpc/ketvoice_delete_account", "{}".toByteArray(), token)
        }
        check(response.ok) { "Suppression impossible pour le moment." }
        signedOutLocally(null)
    }

    private fun signedOutLocally(message: String?) {
        tokens.set(null)
        appContext?.let { context ->
            com.ketvoice.app.sync.LibrarySync.signedOut(context)
            com.ketvoice.app.sync.NowPlayingSync.signedOut()
            com.ketvoice.app.sync.SharedPlaylists.signedOut(context)
            PublicProfiles.signedOut()
            Profiles.signedOut(context)
        }
        _state.value = AccountState(message = message)
    }

    /** For host renders and tests: an account shown without any network or Keystore. */
    internal fun showForTest(state: AccountState) { _state.value = state }
}

/** What the forms accept before anything is sent. */
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

    /** A handle is optional: 3 to 24 lowercase letters, digits, dots and underscores. */
    fun handle(value: String): String? = value.trim().removePrefix("@").lowercase().ifEmpty { null }?.also {
        require(it.matches(Regex("[a-z0-9_.]{3,24}"))) { "Nom d'utilisateur : 3 à 24 lettres, chiffres, points ou tirets bas." }
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
