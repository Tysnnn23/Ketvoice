package com.ketvoice.app.data

import java.net.URI
import java.net.URLDecoder

/** A UI hint is never authorization; the broker verifies every write again. */
object PublisherAccess {
    const val EMAIL = "konnektsupport@gmail.com"
    const val HOST = "xodklrunqlzyeiqtovgx.supabase.co"
    fun matches(email: String): Boolean = email.trim().equals(EMAIL, ignoreCase = true)

    data class Verification(val key: String, val value: String, val type: String)

    fun verification(input: String): Verification {
        val text = input.trim()
        if (text.matches(Regex("[0-9]{6,10}"))) return Verification("token", text, "email")
        val url = runCatching { URI(text) }.getOrElse { error("Session invalide") }
        require(url.scheme == "https" && url.host == HOST && url.path == "/auth/v1/verify" && url.userInfo == null)
        val values = url.rawQuery.orEmpty().split('&').mapNotNull { part ->
            val pair = part.split('=', limit = 2)
            if (pair.size == 2) URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8") else null
        }.toMap()
        // Supabase verifies magic links / signup confirmations with their email
        // template type. Sending "email" for a magiclink token is rejected.
        val type = values["type"]?.takeIf { it in setOf("email", "magiclink", "signup") } ?: error("Lien invalide")
        val hash = values["token_hash"] ?: values["token"] ?: error("Lien invalide")
        require(hash.matches(Regex("[A-Za-z0-9_-]{20,200}")))
        return Verification(if (values["token_hash"] != null) "token_hash" else "token", hash, type)
    }
}
