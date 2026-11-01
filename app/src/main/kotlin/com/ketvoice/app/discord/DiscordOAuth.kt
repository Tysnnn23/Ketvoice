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
    const val REDIRECT_URI = "discord-1566454532890820668:/authorize/callback"
    const val SCOPES = "openid sdk.social_layer_presence"

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

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
