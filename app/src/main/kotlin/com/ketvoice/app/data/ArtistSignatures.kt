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

/** HD portrait/background from Fanart, plus an authentic signature or logo. */
internal data class ArtistArtwork(
    val photo: String? = null,
    val background: String? = null,
    val signature: ArtistSignature? = null,
    val logo: ArtistSignature? = null
) {
    val hero: String? get() = photo ?: background
}

/** Opt-in artist identity artwork. Provider credentials never leave the broker. */
internal object ArtistSignatures {
    private val base = KetvoiceApi.BASE.substringBeforeLast('/') + "/ketvoice-artists"
    private data class Entry(val signature: ArtistSignature?, val expires: Long)
    private val cache = linkedMapOf<String, Entry>()
    private val lock = Mutex()
    private fun name(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private data class ArtEntry(val artwork: ArtistArtwork, val expires: Long)
    private val artCache = linkedMapOf<String, ArtEntry>()

    suspend fun find(artist: String): ArtistSignature? = artwork(artist).logo

    /**
     * Multi-source artist artwork. Fanart portraits/backgrounds come from the same
     * MusicBrainz identity as the logo; an authentic Wikidata P109 signature wins
     * over the logo. Photos are gated by [AppSettings.artistPhotos], signatures and
     * logos by [AppSettings.artistSignatures] (network consent).
     */
    suspend fun artwork(artist: String, includeSignature: Boolean = AppSettings.artistSignatures.value): ArtistArtwork {
        val photos = AppSettings.artistPhotos.value
        val signatures = includeSignature && AppSettings.artistSignatures.value
        if ((!photos && !signatures) || artist.isBlank() || artist.length > 160) return ArtistArtwork()
        return lock.withLock {
            val key = name(artist) + if (signatures) "|sig" else "|photo"
            artCache[key]?.takeIf { it.expires > System.currentTimeMillis() }?.let { return@withLock it.artwork }
            var lifetime = 10 * 60_000L
            val result = try {
                val fetched = withContext(Dispatchers.IO) { request(artist, signatures) }
                // Respect each consent independently: the broker returns everything
                // it knows, the app only keeps what the user allowed.
                fetched.copy(
                    photo = fetched.photo.takeIf { photos },
                    background = fetched.background.takeIf { photos },
                    signature = fetched.signature.takeIf { signatures },
                    logo = fetched.logo.takeIf { signatures }
                )
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { lifetime = 60_000L; ArtistArtwork() }
            if (result.hero != null || result.logo != null || result.signature != null)
                lifetime = 12 * 60 * 60_000L
            if (artCache.size >= 128) artCache.remove(artCache.keys.first())
            artCache[key] = ArtEntry(result, System.currentTimeMillis() + lifetime)
            result
        }
    }

    /** Kept for the existing settings screen and tests. */
    suspend fun fetchLogo(artist: String): ArtistSignature? = find(artist)

    private fun request(artist: String, signature: Boolean): ArtistArtwork {
        val suffix = if (signature) "&signature=1" else ""
        val connection = URL("$base/logo?artist=${URLEncoder.encode(artist.trim(), "UTF-8")}$suffix")
            .openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 18_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("apikey", KetvoiceApi.ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer ${KetvoiceApi.ANON_KEY}")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
            when (connection.responseCode) {
                404 -> ArtistArtwork()
                200 -> {
                    val bytes = connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 256 * 1024) { "Oversized artwork response" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    parseArtwork(JSONObject(bytes.toString(Charsets.UTF_8)), artist)
                }
                else -> throw java.io.IOException("Artwork service unavailable")
            }
        } finally { connection.disconnect() }
    }

    internal fun parseArtwork(root: JSONObject, artist: String): ArtistArtwork {
        val logo = parse(root, artist)
        val photo = safeArt(root.optJSONObject("photo"), "portrait")
        val background = safeArt(root.optJSONObject("background"), "background")
        val signature = parseSignature(root.optJSONObject("signature"))
        return ArtistArtwork(photo, background, signature, logo)
    }

    private fun safeArt(value: JSONObject?, kind: String): String? {
        if (value == null || value.optString("kind") != kind || value.optString("provider") != "fanart.tv") return null
        val uri = runCatching { URI(value.optString("url")) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != "assets.fanart.tv" || uri.rawUserInfo != null ||
            uri.port !in setOf(-1, 443) || uri.rawQuery != null || uri.rawFragment != null ||
            !uri.path.startsWith("/fanart/") ||
            !Regex("(?i).*\\.(png|jpe?g|webp)$").matches(uri.path)) return null
        return uri.toASCIIString()
    }

    private fun parseSignature(value: JSONObject?): ArtistSignature? {
        if (value == null || value.optString("kind") != "signature" ||
            value.optString("provider") != "wikimedia-commons") return null
        val uri = runCatching { URI(value.optString("url")) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != "commons.wikimedia.org" || uri.rawUserInfo != null ||
            uri.port !in setOf(-1, 443) || uri.rawFragment != null ||
            !uri.path.startsWith("/wiki/Special:FilePath/")) return null
        val width = Regex("(?:^|&)width=(\\d{1,4})(?:&|$)").find(uri.rawQuery.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        if (uri.rawQuery != null && (width == null || width !in 100..2000)) return null
        val source = value.optString("source")
        if (!Regex("https://www\\.wikidata\\.org/wiki/Q\\d+").matches(source)) return null
        return ArtistSignature(uri.toASCIIString(), "image", source, "Wikimedia Commons", monochrome = true)
    }

    suspend fun findLegacy(artist: String): ArtistSignature? {
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
