package com.ketvoice.app.account

import org.json.JSONObject
import java.io.IOException

/** A signed-in listener's session, as Supabase Auth gives it. */
internal data class AuthSession(val access: String, val refresh: String, val expiresAt: Long,
                                val userId: String, val email: String) {
    /** Renewed a minute before it expires, so a request never leaves with a token about to die. */
    fun needsRefresh(now: Long): Boolean = access.isBlank() || now >= expiresAt - REFRESH_MARGIN_MS

    companion object {
        const val REFRESH_MARGIN_MS = 60_000L
    }
}

/** The server refused the remembered session (expired, revoked, account deleted): the listener signs in again. */
internal class SessionRejected(message: String) : Exception(message)

/** Supabase Auth over REST: e-mail and password, refresh, PKCE for an OAuth provider, sign-out. */
internal object AuthApi {
    private val uuid = Regex("[0-9a-fA-F-]{36}")

    fun parseSession(json: JSONObject, now: Long, previousRefresh: String = ""): AuthSession {
        val access = json.optString("access_token")
        val user = json.optJSONObject("user") ?: error("Session invalide")
        val id = user.optString("id")
        require(access.isNotBlank() && id.matches(uuid)) { "Session invalide" }
        val refresh = json.optString("refresh_token").ifBlank { previousRefresh }
        require(refresh.isNotBlank()) { "Session invalide" }
        val expires = json.optLong("expires_in", 3600).coerceIn(60, 7 * 24 * 3600)
        return AuthSession(access, refresh, now + expires * 1000, id, user.optString("email"))
    }

    /** A message for the listener, in French (translated at display), from Supabase's error. */
    fun message(status: Int, json: JSONObject?): String {
        val code = json?.optString("error_code").orEmpty().ifBlank { json?.optString("error").orEmpty() }
        val text = (json?.optString("msg").orEmpty() + " " + json?.optString("error_description").orEmpty() +
            " " + json?.optString("message").orEmpty()).lowercase()
        return when {
            code == "invalid_credentials" || "invalid login credentials" in text -> "E-mail ou mot de passe incorrect."
            code == "user_already_exists" || code == "email_exists" || "already registered" in text -> "Un compte existe déjà avec cet e-mail."
            code == "weak_password" || "password should be" in text -> "Mot de passe trop faible (8 caractères minimum)."
            code == "email_address_invalid" || code == "validation_failed" && "email" in text -> "Adresse e-mail invalide."
            code == "signup_disabled" || "signups not allowed" in text -> "Les inscriptions sont fermées pour le moment."
            code == "provider_disabled" || "provider is not enabled" in text -> "Cette méthode de connexion n'est pas encore disponible."
            code == "over_request_rate_limit" || code == "over_email_send_rate_limit" || status == 429 ->
                "Trop de tentatives. Réessayez dans quelques minutes."
            else -> "Connexion impossible pour le moment."
        }
    }

    /** True when a refresh failed because the server no longer knows the session, not because of the network. */
    fun rejected(status: Int, json: JSONObject?): Boolean {
        val code = json?.optString("error_code").orEmpty().ifBlank { json?.optString("error").orEmpty() }
        return status in 400..403 && (code in setOf("refresh_token_not_found", "refresh_token_already_used", "invalid_grant",
            "session_not_found", "session_expired", "user_not_found", "bad_jwt") || "refresh token" in json.toString().lowercase())
    }

    private fun post(path: String, body: JSONObject, token: String? = null): JSONObject {
        val response = SupabaseHttp.request("POST", "/auth/v1$path", body.toString().toByteArray(), token)
        val json = runCatching { JSONObject(response.text) }.getOrNull()
        if (!response.ok) throw SupabaseHttp.HttpError(response.status, json?.optString("error_code").orEmpty(), message(response.status, json))
        return json ?: JSONObject()
    }

    fun signUp(email: String, password: String, displayName: String, now: () -> Long): AuthSession {
        val json = post("/signup", JSONObject().put("email", email).put("password", password)
            .put("data", JSONObject().put("display_name", displayName)))
        // With « Confirm email » on, Supabase answers with the user but no session.
        if (json.optString("access_token").isBlank()) error("Le compte attend une confirmation par e-mail que Ketvoice ne demande pas. Réessayez plus tard.")
        return parseSession(json, now())
    }

    fun signIn(email: String, password: String, now: () -> Long): AuthSession =
        parseSession(post("/token?grant_type=password", JSONObject().put("email", email).put("password", password)), now())

    fun refresh(refresh: String, now: () -> Long): AuthSession {
        // A network failure (IOException) goes up as it is: the remembered session is kept for later.
        val response = SupabaseHttp.request("POST", "/auth/v1/token?grant_type=refresh_token",
            JSONObject().put("refresh_token", refresh).toString().toByteArray())
        val json = runCatching { JSONObject(response.text) }.getOrNull()
        if (response.ok && json != null) return parseSession(json, now(), refresh)
        if (rejected(response.status, json)) throw SessionRejected("Votre session a expiré. Reconnectez-vous.")
        throw IOException("Session refresh failed: ${response.status}")
    }

    /** PKCE: the code the provider's redirect carried, exchanged for a session. */
    fun exchangeCode(code: String, verifier: String, now: () -> Long): AuthSession =
        parseSession(post("/token?grant_type=pkce", JSONObject().put("auth_code", code).put("code_verifier", verifier)), now())

    fun authorizeUrl(provider: String, redirect: String, challenge: String): String =
        "${SupabaseHttp.ROOT}/auth/v1/authorize?provider=${SupabaseHttp.encode(provider)}" +
            "&redirect_to=${SupabaseHttp.encode(redirect)}&code_challenge=${SupabaseHttp.encode(challenge)}&code_challenge_method=s256"

    fun signOut(access: String) {
        runCatching { SupabaseHttp.request("POST", "/auth/v1/logout?scope=local", "{}".toByteArray(), access) }
    }
}
