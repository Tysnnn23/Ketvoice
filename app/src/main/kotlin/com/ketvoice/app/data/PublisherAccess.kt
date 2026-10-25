package com.ketvoice.app.data

import java.net.URI
import java.net.URLDecoder

object PublisherAccess {
    const val EMAIL = "konnektsupport@gmail.com"
    const val HOST = "xodklrunqlzyeiqtovgx.supabase.co"
    const val REDIRECT_URL = "ketvoice://auth"
    fun matches(email: String): Boolean = email.trim().equals(EMAIL, ignoreCase = true)

    data class Verification(val key: String, val value: String, val type: String)

    fun verification(input: String): Verification {
        val text = input.trim()
        require(text.length <= 16_384) { "Lien invalide" }
        if (text.matches(Regex("[0-9]{6,10}"))) return Verification("token", text, "email")
        val url = runCatching { URI(text) }.getOrElse { error("Session invalide") }
        require(url.scheme == "https" && url.host == HOST && url.path == "/auth/v1/verify" &&
            url.userInfo == null && url.port == -1 && url.rawFragment == null) { "Lien invalide" }
        val values = parameters(url.rawQuery.orEmpty())

        val type = values["type"]?.takeIf { it in setOf("email", "magiclink", "signup") } ?: error("Lien invalide")
        val hash = values["token_hash"] ?: values["token"] ?: error("Lien invalide")
        require(hash.matches(Regex("[A-Za-z0-9_-]{20,200}")))
        return Verification("token_hash", hash, type)
    }

    fun redirect(input: String): Map<String, String> {
        require(input.length <= 16_384) { "Lien invalide" }
        val url = runCatching { URI(input.trim()) }.getOrElse { error("Lien invalide") }
        require(url.scheme == "ketvoice" && url.host == "auth" && url.userInfo == null &&
            url.port == -1 && url.path.orEmpty() in setOf("", "/") &&
            (url.rawFragment == null || url.rawQuery == null)) { "Lien invalide" }
        return parameters(url.rawFragment ?: url.rawQuery ?: error("Lien invalide"))
    }

    private fun parameters(raw: String): Map<String, String> {
        val pairs = raw.split('&').mapNotNull { part ->
            val pair = part.split('=', limit = 2)
            if (pair.size == 2) URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8") else null
        }
        require(pairs.map { it.first }.distinct().size == pairs.size) { "Lien invalide" }
        return pairs.toMap()
    }
}
