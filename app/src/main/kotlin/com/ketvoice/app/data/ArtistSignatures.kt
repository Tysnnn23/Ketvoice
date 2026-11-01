package com.ketvoice.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

internal data class ArtistSignature(val url: String, val format: String, val source: String,
                                    val attribution: String, val monochrome: Boolean = true)

/** Opt-in artist identity artwork. Provider credentials never leave the broker. */
internal object ArtistSignatures {
    private val base = KetvoiceApi.BASE.substringBeforeLast('/') + "/ketvoice-artists"
    private data class Entry(val signature: ArtistSignature?, val expires: Long)
    private val cache = linkedMapOf<String, Entry>()
    private val lock = Mutex()
    private fun name(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    suspend fun find(artist: String): ArtistSignature? {
        if (!AppSettings.artistSignatures.value || artist.isBlank() || artist.length > 160) return null
        return lock.withLock {
            val key = name(artist)
            cache[key]?.takeIf { it.expires > System.currentTimeMillis() }?.let { return@withLock it.signature }
            var lifetime = 10 * 60_000L
            val result = try {
                withContext(Dispatchers.IO) {
                    val connection = URL("$base/logo?artist=${URLEncoder.encode(artist.trim(), "UTF-8")}")
                        .openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 10_000
                        connection.readTimeout = 18_000
                        connection.instanceFollowRedirects = false
                        connection.setRequestProperty("Accept", "application/json")
                        connection.setRequestProperty("apikey", KetvoiceApi.ANON_KEY)
                        connection.setRequestProperty("Authorization", "Bearer ${KetvoiceApi.ANON_KEY}")
                        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
                        when (connection.responseCode) {
                            404 -> null
                            200 -> {
                                val bytes = connection.inputStream.use { input ->
                                    val output = java.io.ByteArrayOutputStream()
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        require(output.size() + count <= 256 * 1024) { "Oversized signature response" }
                                        output.write(buffer, 0, count)
                                    }
                                    output.toByteArray()
                                }
                                parse(JSONObject(bytes.toString(Charsets.UTF_8)), artist)
                            }
                            else -> throw java.io.IOException("Signature service unavailable")
                        }
                    } finally { connection.disconnect() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { lifetime = 60_000L; null }
            if (result != null) lifetime = 12 * 60 * 60_000L
            if (cache.size >= 128) cache.remove(cache.keys.first())
            cache[key] = Entry(result, System.currentTimeMillis() + lifetime)
            result.takeIf { AppSettings.artistSignatures.value }
        }
    }

    /** Strict broker schema: branded logos are not labelled as public-domain handwriting. */
    internal fun parse(root: JSONObject, artist: String): ArtistSignature? {
        if (name(root.optString("artist")) != name(artist)) return null
        val mbid = root.optString("mbid").lowercase(Locale.ROOT)
        if (!Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(mbid)) return null
        val logo = root.optJSONObject("logo") ?: return null
        if (logo.optString("kind") != "logo" || logo.optString("provider") != "fanart.tv" || logo.optString("format") != "png") return null
        val uri = runCatching { URI(logo.optString("url")) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != "assets.fanart.tv" || uri.rawUserInfo != null ||
            uri.port !in setOf(-1, 443) || uri.rawQuery != null || uri.rawFragment != null ||
            !uri.path.startsWith("/fanart/") || !uri.path.endsWith(".png")) return null
        val source = "https://fanart.tv/artist/$mbid/"
        if (logo.optString("source") != source) return null
        return ArtistSignature(uri.toASCIIString(), "png", source, "fanart.tv", monochrome = false)
    }
}
