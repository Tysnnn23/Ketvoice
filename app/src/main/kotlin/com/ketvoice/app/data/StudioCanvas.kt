package com.ketvoice.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where a Canvas comes from in the Studio: a video file, a direct link to a video, or a
 * link to an API (a JSON answer that names the video, or a Spotify track link). The
 * device downloads the video itself and sends the file; the server never fetches an
 * address it was given.
 */
object StudioCanvas {
    /** The server's limit for one Canvas. */
    const val MAX_BYTES = 20L * 1024 * 1024
    private const val MAX_JSON = 256 * 1024

    sealed interface Source {
        data class Spotify(val trackId: String) : Source
        data class Link(val url: String) : Source
    }

    private val spotifyTrack = Regex("""^(?:https://open\.spotify\.com/(?:intl-[a-z]{2}/)?track/|spotify:track:)([A-Za-z0-9]{22})""")

    /** What a pasted text points to, or null when it cannot be a Canvas source. */
    fun classify(input: String): Source? {
        val text = input.trim()
        spotifyTrack.find(text)?.let { return Source.Spotify(it.groupValues[1]) }
        val url = runCatching { URL(text) }.getOrNull() ?: return null
        if (url.protocol != "https" || url.host.isNullOrBlank() || url.userInfo != null) return null
        return Source.Link(text)
    }

    /** True for the first bytes of an MP4/MOV file (an "ftyp" box), the only format the server stores. */
    fun isMp4(header: ByteArray): Boolean =
        header.size >= 8 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp"

    /**
     * The video an API answer names: the first HTTPS address under a key that means a
     * video or a Canvas, else the first HTTPS address that looks like an MP4.
     */
    fun videoUrlIn(json: Any?): String? {
        val preferred = listOf("canvasurl", "canvas_url", "canvas", "videourl", "video_url", "video", "mp4", "url", "src", "href")
        fun https(value: Any?) = (value as? String)?.trim()?.takeIf { it.startsWith("https://") && it.length <= 2048 }
        fun search(node: Any?, depth: Int): String? {
            if (depth > 6) return null
            when (node) {
                is JSONObject -> {
                    val keys = node.keys().asSequence().toList()
                    for (want in preferred) keys.firstOrNull { it.lowercase() == want }?.let { key ->
                        https(node.opt(key))?.let { return it }
                        search(node.opt(key), depth + 1)?.let { return it }
                    }
                    keys.forEach { key -> search(node.opt(key), depth + 1)?.let { return it } }
                }
                is JSONArray -> for (i in 0 until node.length()) search(node.opt(i), depth + 1)?.let { return it }
                is String -> https(node)?.takeIf { it.substringBefore('?').lowercase().endsWith(".mp4") }?.let { return it }
            }
            return null
        }
        return search(json, 0)
    }

    /** What the Studio tells the publisher about a chosen video before it is sent. */
    enum class Level { Ok, Warning, Blocking }
    data class Check(val label: String, val level: Level)

    fun checks(bytes: Long, durationMs: Long, width: Int, height: Int, mp4: Boolean): List<Check> = buildList {
        add(if (mp4) Check("Format MP4", Level.Ok) else Check("Format non pris en charge : MP4 requis", Level.Blocking))
        val mb = bytes / (1024.0 * 1024.0)
        add(when {
            bytes <= 0 -> Check("Fichier vide", Level.Blocking)
            bytes > MAX_BYTES -> Check("Trop lourd : %.1f Mo (20 Mo maximum)".format(java.util.Locale.ROOT, mb), Level.Blocking)
            else -> Check("Poids : %.1f Mo".format(java.util.Locale.ROOT, mb), Level.Ok)
        })
        val seconds = durationMs / 1000.0
        add(when {
            durationMs <= 0 -> Check("Durée inconnue", Level.Warning)
            durationMs < 2_000 -> Check("Trop court : %.1f s (3 à 8 s conseillées)".format(java.util.Locale.ROOT, seconds), Level.Warning)
            durationMs > 30_000 -> Check("Long : %.0f s (3 à 8 s conseillées)".format(java.util.Locale.ROOT, seconds), Level.Warning)
            else -> Check("Durée : %.1f s".format(java.util.Locale.ROOT, seconds), Level.Ok)
        })
        if (width > 0 && height > 0) add(
            if (height >= width) Check("Vertical", Level.Ok)
            else Check("Horizontal : il sera recadré (vertical conseillé)", Level.Warning))
    }

    fun canSend(checks: List<Check>) = checks.none { it.level == Level.Blocking }

    /** Downloads the Canvas a source points to into [dest]. */
    suspend fun download(source: Source, dest: File): File = withContext(Dispatchers.IO) {
        val url = when (source) {
            is Source.Spotify -> KetvoiceApi.canvas(Track("studio:canvas", "", "", "", spotifyId = source.trackId))?.url
                ?: error("Aucun Canvas pour ce titre Spotify")
            is Source.Link -> source.url
        }
        fetch(url, dest, allowJson = source is Source.Link)
    }

    private fun fetch(address: String, dest: File, allowJson: Boolean): File {
        val url = URL(address)
        require(url.protocol == "https") { "Lien HTTPS requis" }
        val c = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000; readTimeout = 30_000; instanceFollowRedirects = true
            setRequestProperty("Accept", "video/mp4,application/json;q=0.9,*/*;q=0.5")
            setRequestProperty("User-Agent", "Ketvoice-Studio")
        }
        try {
            require(c.responseCode in 200..299) { "Lien inaccessible (HTTP ${c.responseCode})" }
            require(c.url.protocol == "https") { "Lien HTTPS requis" }
            if (c.contentLengthLong > MAX_BYTES) error("Vidéo trop lourde (20 Mo maximum)")
            val type = c.contentType.orEmpty().lowercase()
            val input = java.io.BufferedInputStream(c.inputStream)
            input.mark(16)
            val head = ByteArray(16).also { val read = input.read(it); require(read > 0) { "Réponse vide" } }
            input.reset()
            // An API answers with JSON that names the video: follow it once.
            if (allowJson && ("json" in type || head.first().toInt().toChar() in "{[")) {
                // readNBytes needs API 33: read at most MAX_JSON + 1 bytes by hand.
                val text = java.io.ByteArrayOutputStream().also { out ->
                    val buffer = ByteArray(8 * 1024)
                    while (out.size() <= MAX_JSON) {
                        val read = input.read(buffer, 0, minOf(buffer.size, MAX_JSON + 1 - out.size()))
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                }.toByteArray()
                require(text.size <= MAX_JSON) { "Réponse de l'API trop volumineuse" }
                val video = videoUrlIn(JSONTokener(String(text, Charsets.UTF_8)).nextValue())
                    ?: error("L'API ne donne pas de lien vers une vidéo")
                return fetch(video, dest, allowJson = false)
            }
            require(isMp4(head)) { "Ce lien n'est pas une vidéo MP4" }
            dest.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024); var size = 0L
                while (true) {
                    val read = input.read(buffer); if (read < 0) break
                    size += read; require(size <= MAX_BYTES) { "Vidéo trop lourde (20 Mo maximum)" }
                    out.write(buffer, 0, read)
                }
            }
            return dest
        } catch (failure: Exception) {
            dest.delete(); throw failure
        } finally { c.disconnect() }
    }
}
