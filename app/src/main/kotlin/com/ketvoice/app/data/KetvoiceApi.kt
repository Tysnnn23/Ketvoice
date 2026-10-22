package com.ketvoice.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client de la source intégrée **Ketvoice API**.
 *
 * Toutes les opérations sensibles (recherche addon, flux signés, Spotify
 * Canvas) passent par une Supabase Edge Function. L'APK ne contient ni cookie
 * Spotify, ni jeton d'addon : ces valeurs restent côté Supabase.
 *
 * La clé ci-dessous est la clé publique `anon` du projet Supabase. Elle est
 * destinée au client et ne donne accès qu'aux fonctions autorisées.
 */
object KetvoiceApi {

    const val DISPLAY_NAME = "Ketvoice API"

    internal const val BASE =
        "https://xodklrunqlzyeiqtovgx.supabase.co/functions/v1/ketvoice-api"
    internal const val ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InhvZGtscnVucWx6eWVpcXRvdmd4Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTI1ODgzNjgsImV4cCI6MjEwODE2NDM2OH0.V-F8ZhM21Ht1yRV5FXOuZcddufvY8aIW5kclb-F2zao"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    data class CanvasResult(val url: String, val spotifyId: String)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun get(path: String, readTimeoutMs: Int = 30_000): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = readTimeoutMs
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("apikey", ANON_KEY)
                setRequestProperty("Authorization", "Bearer $ANON_KEY")
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText)
            if (code in 200..299) text else null
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun getJson(path: String, readTimeoutMs: Int = 30_000): JSONObject? =
        get(path, readTimeoutMs)?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }

    /** Vérifie que la fonction Supabase et les secrets serveur sont disponibles. */
    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        getJson("/health")?.optBoolean("ok") == true
    }

    /**
     * Recherche agrégée côté Supabase. Les identifiants addon restent sur le
     * serveur ; le client ne reçoit que les métadonnées utiles.
     */
    suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        if (query.trim().length < 2) return@withContext emptyList()
        val publicTracks = async { PublicationApi.search(query) }
        val root = getJson("/search?q=${encode(query.trim())}")
        val tracks = root?.optJSONArray("tracks") ?: JSONArray()
        publicTracks.await() + (0 until tracks.length()).mapNotNull { index ->
            val item = tracks.optJSONObject(index) ?: return@mapNotNull null
            val addon = item.optString("source")
            val addonId = item.optString("addonId")
            if (addon.isBlank() || addonId.isBlank()) return@mapNotNull null
            Track(
                id = item.optString("id").ifBlank { "kv:$addon:$addonId" },
                title = item.optString("title"),
                artist = item.optString("artist"),
                album = item.optString("album"),
                durationMs = item.optLong("durationMs", 0L),
                coverUrl = item.optString("artworkURL").takeIf { it.startsWith("https://") },
                addon = addon,
                addonId = addonId,
                isrc = item.optString("isrc").takeIf { it.isNotBlank() },
                lyricsProvider = "LRCLIB"
            )
        }
    }

    /**
     * Résout un flux via Supabase. Le serveur choisit l'addon, garde son jeton
     * et renvoie uniquement l'URL signée temporaire.
     */
    suspend fun stream(track: Track, tag: String): MusicApi.StreamInfo? =
        withContext(Dispatchers.IO) {
            // Un identifiant n'est réutilisable que pour l'addon qui l'a émis.
            // Sinon le serveur refait une correspondance titre/artiste.
            val addonId = if (track.addon == tag) track.addonId.orEmpty() else ""
            val path = buildString {
                append("/stream?source=")
                append(encode(tag))
                append("&id=")
                append(encode(addonId))
                append("&title=")
                append(encode(track.title))
                append("&artist=")
                append(encode(track.artist))
            }
            val root = getJson(path, readTimeoutMs = 45_000) ?: return@withContext null
            val url = root.optString("url")
            if (!url.startsWith("https://")) return@withContext null
            MusicApi.StreamInfo(
                url = url,
                format = root.optString("format").ifBlank { "mp3" },
                quality = root.optString("quality").ifBlank { "Haute qualité" },
                cacheTag = "kv:$tag:$addonId",
                downloadUrl = url
            )
        }

    /**
     * Résout et renvoie l'URL du canvas Spotify. Le cookie `sp_dc` et le TOTP
     * sont exécutés côté Supabase, jamais dans l'application.
     */
    suspend fun canvas(track: Track): CanvasResult? = withContext(Dispatchers.IO) {
        val path = buildString {
            append("/canvas?title=")
            append(encode(track.title))
            append("&artist=")
            append(encode(track.artist))
            if (!track.spotifyId.isNullOrBlank()) {
                append("&spotifyId=")
                append(encode(track.spotifyId))
            }
        }
        val root = getJson(path, readTimeoutMs = 60_000) ?: return@withContext null
        val url = root.optString("url")
        val spotifyId = root.optString("spotifyId")
        if (!url.startsWith("https://") || spotifyId.isBlank()) return@withContext null
        CanvasResult(url, spotifyId)
    }
}
