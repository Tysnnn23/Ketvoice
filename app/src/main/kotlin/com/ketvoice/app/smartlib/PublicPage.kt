package com.ketvoice.app.smartlib

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * What the Apple Music, Tidal and SoundCloud imports share: reading a public web page as anyone
 * would, without an account, a key or a token, and the small text helpers their parsers need.
 */
internal object PublicPage {
    const val NOT_FOUND = "Playlist introuvable ou privée. Vérifiez qu'elle est publique."
    const val CHANGED = "Cette page a changé de format : l'import n'est plus possible pour le moment."
    const val NOT_A_PLAYLIST = "Ce lien ne mène pas à une playlist"

    fun uri(link: String): URI? = runCatching { URI(link.trim()) }.getOrNull()
        ?.takeIf { it.scheme == "https" && it.userInfo == null && it.port in listOf(-1, 443) && it.host != null }

    fun host(link: String): String? = uri(link)?.host?.lowercase()

    /**
     * The page at [address], following redirections only to [hosts]; null when the service answers that
     * it does not exist (404, 410) or refuses it (401, 403). Throws when the service cannot be reached.
     */
    suspend fun fetch(address: String, hosts: Set<String>): Pair<String, String>? = withContext(Dispatchers.IO) {
        var current = address
        repeat(5) {
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000; connection.readTimeout = 15_000; connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Ketvoice")
                connection.setRequestProperty("Accept", "text/html")
                when (val code = connection.responseCode) {
                    200 -> return@withContext current to connection.inputStream.use { String(readCapped(it), Charsets.UTF_8) }
                    301, 302, 303, 307, 308 -> {
                        val next = URI(current).resolve(connection.getHeaderField("Location") ?: throw IllegalStateException(CHANGED)).toString()
                        if (host(next) !in hosts) throw IllegalArgumentException(NOT_A_PLAYLIST)
                        current = next
                    }
                    401, 403, 404, 410 -> return@withContext null
                    else -> throw java.io.IOException("HTTP $code")
                }
            } finally { connection.disconnect() }
        }
        throw IllegalStateException(CHANGED)
    }

    private fun readCapped(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val block = ByteArray(8192)
        while (out.size() <= 6 * 1024 * 1024) { val count = input.read(block); if (count < 0) break; out.write(block, 0, count) }
        require(out.size() <= 6 * 1024 * 1024) { "Playlist trop volumineuse" }
        return out.toByteArray()
    }

    /** The text of the first `<script>` whose opening tag contains [marker]. */
    fun script(html: String, marker: String): String? {
        var from = 0
        while (true) {
            val open = html.indexOf("<script", from).takeIf { it >= 0 } ?: return null
            val end = html.indexOf('>', open).takeIf { it >= 0 } ?: return null
            val close = html.indexOf("</script>", end).takeIf { it >= 0 } ?: return null
            if (html.substring(open, end).contains(marker)) return html.substring(end + 1, close)
            from = close
        }
    }

    private val entity = Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos|#39|nbsp);")

    /** Plain text from a piece of HTML: tags removed, entities read. */
    fun text(html: String): String = entity.replace(html.replace(Regex("<[^>]*>"), "")) { match ->
        when (val name = match.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos", "#39" -> "'"; "nbsp" -> " "
            else -> runCatching {
                val code = if (name.startsWith("#x")) name.drop(2).toInt(16) else name.drop(1).toInt()
                String(Character.toChars(code))
            }.getOrDefault(match.value)
        }
    }.trim()

    /** "3:44" or "1:02:03" in milliseconds; 0 when it cannot be read. */
    fun clock(value: String): Long {
        val parts = value.trim().split(':').map { it.toLongOrNull() ?: return 0 }
        if (parts.size !in 2..3) return 0
        return parts.fold(0L) { total, part -> total * 60 + part } * 1000
    }

    /** A cover address kept only when it is served over HTTPS by one of the service's own image hosts. */
    fun cover(url: String?, hostSuffix: String): String? =
        url?.takeIf { it.startsWith("https://") && host(it)?.endsWith(hostSuffix) == true }

    fun clean(value: String?, max: Int = 200): String = value.orEmpty().trim().take(max)
}
