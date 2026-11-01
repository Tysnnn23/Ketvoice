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

internal data class ArtistSignature(val url: String, val format: String, val source: String, val license: String)

/** Public-domain assets only; artist names leave the device only after opt-in. */
internal object ArtistSignatures {
    private const val BASE = "https://proud-cascade-1654.de.deplexo.com"
    private data class Entry(val signature: ArtistSignature?, val expires: Long)
    private val cache = linkedMapOf<String, Entry>()
    private val lock = Mutex()
    private fun name(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    suspend fun find(artist: String): ArtistSignature? {
        if (!AppSettings.artistSignatures.value || artist.isBlank()) return null
        return lock.withLock {
            val key = name(artist)
            cache[key]?.takeIf { it.expires > System.currentTimeMillis() }?.let { return@withLock it.signature }
            var lifetime = 10 * 60_000L
            val result = try {
                withContext(Dispatchers.IO) {
                    val connection = URL("$BASE/v1/signatures?artist=${URLEncoder.encode(artist.trim(), "UTF-8")}")
                        .openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 6_000
                        connection.readTimeout = 6_000
                        connection.instanceFollowRedirects = false
                        connection.setRequestProperty("Accept", "application/json")
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

    /** The actual v1 service schema. Do not infer identity or licensing from filenames. */
    internal fun parse(root: JSONObject, artist: String): ArtistSignature? {
        if (name(root.optJSONObject("artist")?.optString("name").orEmpty()) != name(artist)) return null
        val values = root.optJSONArray("signatures") ?: return null
        val allowed = setOf("PD-signature", "CC0", "CC0-1.0", "Public domain")
        return (0 until values.length()).mapNotNull { index ->
            val item = values.optJSONObject(index) ?: return@mapNotNull null
            if (name(item.optJSONObject("artist")?.optString("name").orEmpty()) != name(artist)) return@mapNotNull null
            val license = item.optJSONObject("license") ?: return@mapNotNull null
            val label = license.optString("name")
            if (license.optString("status") != "known" || label !in allowed) return@mapNotNull null
            val asset = item.optJSONObject("asset") ?: return@mapNotNull null
            val format = asset.optString("format").lowercase(Locale.ROOT)
            if (format !in setOf("svg", "png", "webp")) return@mapNotNull null
            val uri = runCatching { URI(asset.optString("url")) }.getOrNull() ?: return@mapNotNull null
            if (uri.scheme != "https" || uri.host != "upload.wikimedia.org" || uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) return@mapNotNull null
            val source = item.optJSONObject("source")?.optString("url").orEmpty()
            val attribution = runCatching { URI(source) }.getOrNull()
            if (attribution?.scheme != "https" || attribution.host != "commons.wikimedia.org" || attribution.rawUserInfo != null ||
                attribution.port !in setOf(-1, 443)) return@mapNotNull null
            ArtistSignature(uri.toASCIIString(), format, source, label)
        }.sortedBy { if (it.format == "svg") 0 else 1 }.firstOrNull()
    }
}
