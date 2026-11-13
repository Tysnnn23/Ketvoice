package com.ketvoice.app.discord

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Standard Discord OAuth2 authorization-code flow with PKCE. Ketvoice drives the
 * browser itself so the redirect always comes back to our own exported activity;
 * the SDK only performs the token exchange and the presence connection.
 */
object DiscordOAuth {
    const val APPLICATION_ID = 1566454532890820668L
    const val REDIRECT_URI = "https://ketvoice.netlify.app/discord.html"
    const val SCOPES = "openid sdk.social_layer_presence"
    private const val TOKEN_URL = "https://discord.com/api/oauth2/token"

    data class Tokens(val access: String, val refresh: String, val expiresIn: Long)
    data class Redirect(val code: String, val state: String)

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun newVerifier(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return base64Url(bytes)
    }

    /** RFC 7636 S256: BASE64URL(SHA-256(ASCII(verifier))). */
    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64Url(digest)
    }

    fun newState(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return base64Url(bytes)
    }

    fun authorizeUrl(state: String, challenge: String): String = buildString {
        append("https://discord.com/oauth2/authorize")
        append("?client_id=").append(APPLICATION_ID)
        append("&response_type=code")
        append("&redirect_uri=").append(encode(REDIRECT_URI))
        append("&scope=").append(encode(SCOPES))
        append("&state=").append(encode(state))
        append("&code_challenge=").append(encode(challenge))
        append("&code_challenge_method=S256")
    }

    /** Public client: no client secret. Exactly the four fields Discord expects. */
    fun exchangeForm(code: String, verifier: String): String = listOf(
        "grant_type" to "authorization_code",
        "client_id" to APPLICATION_ID.toString(),
        "code" to code,
        "redirect_uri" to REDIRECT_URI,
        "code_verifier" to verifier
    ).joinToString("&") { (key, value) -> key + "=" + encode(value) }

    internal fun parseTokens(raw: String): Tokens? = runCatching {
        val root = org.json.JSONObject(raw)
        val access = root.optString("access_token")
        val refresh = root.optString("refresh_token")
        if (access.isBlank() || refresh.isBlank()) return null
        Tokens(access, refresh, root.optLong("expires_in", 0L))
    }.getOrNull()

    internal fun errorMessage(raw: String): String {
        val root = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return ""
        return root.optString("error_description").ifBlank { root.optString("error") }.take(160)
    }

    /**
     * Performs the authorization-code exchange ourselves (PKCE, public client).
     * This is the path unit-tested without a device; the SDK exchange is kept as a
     * fallback for builds whose portal configuration requires it.
     */
    fun exchange(code: String, verifier: String,
                 post: (String) -> Pair<Int, String> = ::httpsPost): Tokens? {
        if (code.isBlank() || verifier.isBlank()) return null
        val (status, body) = post(exchangeForm(code, verifier))
        return if (status in 200..299) parseTokens(body) else null
    }

    private fun httpsPost(body: String): Pair<Int, String> {
        var connection: java.net.HttpURLConnection? = null
        return try {
            connection = (java.net.URL(TOKEN_URL).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; instanceFollowRedirects = false
                connectTimeout = 12_000; readTimeout = 15_000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Ketvoice/${com.ketvoice.app.BuildConfig.VERSION_NAME} (Android)")
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.US_ASCII)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } catch (_: Exception) {
            0 to ""
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Parses any accepted redirect form. The state must match the one we sent, so a
     * forged callback cannot link an account.
     */
    internal fun redirect(raw: String?, expectedState: String): Redirect? {
        if (raw.isNullOrBlank() || expectedState.isBlank()) return null
        val hashless = raw.substringBefore('#')
        val queryIndex = hashless.indexOf('?')
        val base = if (queryIndex >= 0) hashless.substring(0, queryIndex) else hashless
        val query = if (queryIndex >= 0) hashless.substring(queryIndex + 1) else ""
        val scheme = base.substringBefore(':').lowercase()
        val afterScheme = base.substringAfter(':', "").trimStart('/')
        val authority = afterScheme.substringBefore('/').lowercase()
        val path = "/" + afterScheme.substringAfter('/', "")
        val accepted = when {
            scheme == "discord-$APPLICATION_ID" -> true
            scheme == "ketvoice" && authority == "discord" -> true
            scheme == "https" && authority == "ketvoice.netlify.app" &&
                path.trimEnd('/') == "/discord.html" -> true
            else -> false
        }
        if (!accepted) return null
        val params = query.split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            decode(key) to decode(value)
        }.toMap()
        val state = params["state"].orEmpty()
        val code = params["code"].orEmpty()
        if (state != expectedState || code.isBlank()) return null
        return Redirect(code, state)
    }

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
