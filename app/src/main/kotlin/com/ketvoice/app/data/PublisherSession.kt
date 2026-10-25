package com.ketvoice.app.data

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

object PublisherSession {
    private val _allowed = MutableStateFlow(false)
    val allowed: StateFlow<Boolean> = _allowed
    private var store: SecureSessionStore? = null
    private var access = ""
    private var refresh = ""
    private var expiresAt = 0L
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (store != null) return
        store = SecureSessionStore(context.applicationContext)
        refresh = store?.get("publisher-refresh").orEmpty()
        if (refresh.isNotBlank()) scope.launch { runCatching { token() } }
    }

    suspend fun sendLink() = withContext(Dispatchers.IO) {
        PublicationApi.auth("/otp?redirect_to=${java.net.URLEncoder.encode(PublisherAccess.REDIRECT_URL, "UTF-8")}",
            JSONObject().put("email", PublisherAccess.EMAIL).put("create_user", false))
        Unit
    }

    suspend fun verify(input: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val text = input.trim()
            if (text.startsWith("ketvoice://", ignoreCase = true)) {
                acceptRedirect(text)
                return@withLock
            }
            val link = PublisherAccess.verification(text)
            val body = JSONObject().put(link.key, link.value).put("type", link.type)
            if (link.key == "token") body.put("email", PublisherAccess.EMAIL)
            accept(PublicationApi.auth("/verify", body))
        }
    }

    private suspend fun acceptRedirect(url: String) {
        val values = PublisherAccess.redirect(url)
        require(values["error"].isNullOrBlank() && values["error_description"].isNullOrBlank()) {
            "Lien expiré ou déjà utilisé. Demandez un nouveau lien."
        }
        require(!values["access_token"].isNullOrBlank() && values["token_type"] == "bearer") { "Lien invalide" }
        val nextRefresh = values["refresh_token"]?.takeIf { it.isNotBlank() } ?: error("Lien invalide")

        accept(PublicationApi.auth("/token?grant_type=refresh_token", JSONObject().put("refresh_token", nextRefresh)))
    }

    private suspend fun accept(result: JSONObject) {
        _allowed.value = false
        val user = result.optJSONObject("user") ?: error("Session invalide")
        require(PublisherAccess.matches(user.optString("email")) && !user.isNull("email_confirmed_at")) { "Ce compte ne peut pas publier" }
        val candidate = result.getString("access_token")
        require(PublicationApi.adminSession(candidate)) { "Ce compte ne peut pas publier" }
        access = candidate
        refresh = result.optString("refresh_token", refresh)
        expiresAt = System.currentTimeMillis() + result.optLong("expires_in", 3600) * 1000
        store?.put("publisher-refresh", refresh)
        _allowed.value = true
    }

    internal suspend fun token(): String = mutex.withLock {
        if (access.isBlank() || System.currentTimeMillis() >= expiresAt - 60_000) {
            require(refresh.isNotBlank()) { "Connexion administrateur requise" }
            try {
                accept(PublicationApi.auth("/token?grant_type=refresh_token", JSONObject().put("refresh_token", refresh)))
            } catch (error: Exception) {
                _allowed.value = false
                throw error
            }
        }
        access
    }

    fun signOut() {
        val old = access
        access = ""; refresh = ""; expiresAt = 0L; _allowed.value = false
        store?.remove("publisher-refresh")
        if (old.isNotBlank()) scope.launch { runCatching { PublicationApi.auth("/logout", JSONObject(), old) } }
    }
}
