package com.ketvoice.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * An image given by its address (a cover, an artist's photo or portrait): checked once, then shown
 * from where it is. Nothing is downloaded into Ketvoice's storage, so no size limit applies and the
 * server's space is not used.
 */
object StudioImageLink {
    /** The first bytes of the formats the app shows: JPEG, PNG, GIF, WebP and AVIF/HEIF. */
    internal fun isImage(head: ByteArray): Boolean {
        fun at(i: Int) = head.getOrNull(i)?.toInt()?.and(0xFF) ?: -1
        fun text(from: Int, value: String) = value.indices.all { at(from + it) == value[it].code }
        return (at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) ||
            (at(0) == 0x89 && text(1, "PNG")) ||
            text(0, "GIF8") ||
            (text(0, "RIFF") && text(8, "WEBP")) ||
            (text(4, "ftyp") && (text(8, "avif") || text(8, "avis") || text(8, "heic") || text(8, "mif1")))
    }

    internal fun publicHost(host: String?): Boolean {
        val h = host?.lowercase()?.trim('.') ?: return false
        return '.' in h && h != "localhost" && !h.endsWith(".local") && !h.endsWith(".internal") &&
            !Regex("^(\\d+\\.){3}\\d+$").matches(h) && !h.startsWith("[") && ':' !in h
    }

    /** Checks that [address] serves an image over HTTPS and returns the address that serves it (after redirects). */
    suspend fun check(address: String): String = withContext(Dispatchers.IO) {
        val url = runCatching { URL(address.trim()) }.getOrNull() ?: error("Lien HTTPS requis")
        require(url.protocol == "https" && url.userInfo == null) { "Lien HTTPS requis" }
        // As the server does: a public host only, never the phone's own network.
        require(publicHost(url.host)) { "Lien HTTPS public requis" }
        val c = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000; readTimeout = 20_000; instanceFollowRedirects = true
            setRequestProperty("Accept", "image/*")
            setRequestProperty("User-Agent", "Ketvoice-Studio")
            // Only the start of the file: enough to know what it is.
            setRequestProperty("Range", "bytes=0-63")
        }
        try {
            require(c.responseCode in 200..299) { "Lien inaccessible (HTTP ${c.responseCode})" }
            require(c.url.protocol == "https") { "Lien HTTPS requis" }
            val head = ByteArray(16)
            var read = 0
            c.inputStream.use { input -> while (read < head.size) { val n = input.read(head, read, head.size - read); if (n <= 0) break; read += n } }
            require(read >= 4 && isImage(head)) { "Ce lien n'est pas une image (JPEG, PNG, WebP, GIF ou AVIF)" }
            c.url.toString()
        } finally { c.disconnect() }
    }
}
