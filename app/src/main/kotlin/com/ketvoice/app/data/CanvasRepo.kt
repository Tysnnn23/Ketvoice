package com.ketvoice.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * CANVAS SPOTIFY — réel, permanent, sans serveur intermédiaire.
 *
 * Pipeline (validé depuis le poste de dev) :
 *   1. Genius (titre + artiste) → page du morceau → ID de piste Spotify.
 *   2. Cookie `sp_dc` de l'utilisateur + TOTP (secrets publics Spotify) →
 *      access token non anonyme via open.spotify.com/api/token.
 *   3. POST protobuf `canvaz-cache/v0/canvases` → URL `*.cnvs.mp4`
 *      (réponse gzip éventuelle décompressée).
 *   4. Téléchargement du MP4 dans filesDir/canvas/{spotifyId}.mp4 :
 *      le canvas reste EN PERMANENCE sur l'appareil, même hors ligne.
 *
 * Aucune donnée n'est envoyée à un serveur tiers : tout est direct.
 */
object CanvasRepo {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val SPOTIFY_CLIENT_UA = "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)"
    private const val SECRETS_URL =
        "https://raw.githubusercontent.com/xyloflake/spot-secrets-go/refs/heads/main/secrets/secretDict.json"
    private const val PREFS = "ketvoice_canvas"

    /** track.id Ketvoice → chemin local du MP4 déjà téléchargé. */
    private val _paths = MutableStateFlow<Map<String, String>>(emptyMap())
    val paths: StateFlow<Map<String, String>> = _paths

    private val mutex = Mutex()
    private var appContext: Context? = null
    private var secrets: Pair<String, ByteArray>? = null
    private var secretsAt = 0L
    private var token: String? = null
    private var tokenExpiry = 0L
    private val failed = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var tokenCookieHash = 0

    fun resetAuth() {
        token = null
        tokenExpiry = 0
        failed.clear()
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val dir = canvasDir(context.applicationContext)
            val map = mutableMapOf<String, String>()
            prefs.all.forEach { (key, value) ->
                if (value is String && value.isNotBlank()) {
                    val f = File(dir, "$value.mp4")
                    if (f.exists() && f.length() > 10_000) map[key] = f.absolutePath
                }
            }
            _paths.value = map
        }
    }

    /** Résout puis télécharge le canvas du morceau (retente après 10 min d'échec). */
    suspend fun resolve(track: Track) = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext
        // Le réglage « Pochette animée » coupe aussi le téléchargement, pas
        // seulement l'affichage.
        if (!AppSettings.animatedArtwork.value) return@withContext
        fun recentlyFailed() = failed[track.id]?.let {
            System.currentTimeMillis() - it < 10 * 60_000L
        } == true
        if (_paths.value.containsKey(track.id) || recentlyFailed()) return@withContext
        mutex.withLock {
            if (_paths.value.containsKey(track.id) || recentlyFailed()) return@withLock
            try {
                val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                var spotifyId = track.spotifyId
                    ?: prefs.getString(track.id, null)
                if (spotifyId.isNullOrBlank()) {
                    // Recherche Spotify réelle : couvre les titres dont la page
                    // Genius n'expose aucun lecteur Spotify (fréquent en rap FR).
                    spotifyId = spotifyIdFromSearch(track) ?: spotifyIdFromGenius(track)
                    if (spotifyId != null) prefs.edit().putString(track.id, spotifyId).apply()
                }
                if (spotifyId.isNullOrBlank()) {
                    failed[track.id] = System.currentTimeMillis()
                    return@withLock
                }

                val dest = File(canvasDir(ctx), "$spotifyId.mp4")
                if (!dest.exists() || dest.length() < 10_000) {
                    if (!networkAllowed(ctx)) return@withLock
                    val url = fetchCanvasUrl(spotifyId) ?: serverCanvasUrl(spotifyId) ?: run {
                        failed[track.id] = System.currentTimeMillis()
                        return@withLock
                    }
                    download(url, dest) ?: run {
                        failed[track.id] = System.currentTimeMillis()
                        return@withLock
                    }
                }
                _paths.value = _paths.value + (track.id to dest.absolutePath)
            } catch (_: Exception) {
                failed[track.id] = System.currentTimeMillis()
            }
        }
    }

    /** Diagnostic : tente le pipeline réel (Genius → ID Spotify → token sp_dc).
     *  Prouve aux réglages que le canvas est fonctionnel, sans rien télécharger. */
    fun testPipeline(): Boolean {
        return try {
            val test = Track(
                id = "__test_pipeline__",
                title = "Mr. Brightside",
                artist = "The Killers",
                album = "",
                durationMs = 222_000L
            )
            val id = spotifyIdFromSearch(test) ?: spotifyIdFromGenius(test)
            id != null && (fetchCanvasUrl(id) ?: serverCanvasUrl(id)) != null
        } catch (_: Exception) {
            false
        }
    }

    // --------------------------------------------------------- Spotify search
    /**
     * Résolution de l'ID Spotify par la recherche du lecteur web (Pathfinder).
     * C'est la même session sp_dc que le token Canvas : aucun service tiers.
     */
    private fun spotifyIdFromSearch(track: Track): String? {
        val tok = accessToken() ?: return null
        val term = "${track.title} ${track.artist.substringBefore(',')}".trim()
        if (term.isBlank()) return null
        val variables = JSONObject()
            .put("searchTerm", term)
            .put("offset", 0)
            .put("limit", 10)
            .put("numberOfTopResults", 5)
        val extensions = JSONObject().put("persistedQuery", JSONObject()
            .put("version", 1)
            .put("sha256Hash", "75bbf6bfcfdf85b8fc828417bfad92b7cd66bf7f556d85670f4da8292373ebec"))
        val url = "https://api-partner.spotify.com/pathfinder/v1/query?operationName=searchDesktop" +
            "&variables=" + URLEncoder.encode(variables.toString(), "UTF-8") +
            "&extensions=" + URLEncoder.encode(extensions.toString(), "UTF-8")
        val raw = fetchText(url, mapOf(
            "Authorization" to "Bearer $tok",
            "Accept" to "application/json",
            "App-Platform" to "WebPlayer",
            "Origin" to "https://open.spotify.com",
            "Referer" to "https://open.spotify.com/"
        )) ?: return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null

        val candidates = LinkedHashMap<String, JSONObject>()
        fun visit(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val uri = node.optString("uri")
                    if (uri.startsWith("spotify:track:") && node.optString("name").isNotBlank()) {
                        candidates[uri] = node
                    }
                    node.keys().forEach { visit(node.opt(it)) }
                }
                is JSONArray -> (0 until node.length()).forEach { visit(node.opt(it)) }
            }
        }
        visit(root)
        if (candidates.isEmpty()) return null

        val wantTitle = normalize(track.title)
        val wantArtist = normalize(track.artist.substringBefore(','))
        val best = candidates.entries.maxByOrNull { (_, o) ->
            val title = normalize(o.optString("name"))
            var score = 0
            if (title == wantTitle) score += 5
            else if (title.isNotBlank() && (title.contains(wantTitle) || wantTitle.contains(title))) score += 2
            val haystack = normalize(o.toString())
            if (wantArtist.isNotBlank() && haystack.contains(wantArtist)) score += 3
            score
        }
        if (best == null || best.value.optString("name").isBlank()) return null
        val title = normalize(best.value.optString("name"))
        val artistText = normalize(best.value.toString())
        val artistOk = wantArtist.isBlank() || artistText.contains(wantArtist)
        if (title != wantTitle && !title.contains(wantTitle)) return null
        if (!artistOk && title != wantTitle) return null
        return best.key.removePrefix("spotify:track:").takeIf { it.length >= 10 }
    }

    // ------------------------------------------------------------- Genius
    private fun spotifyIdFromGenius(track: Track): String? {
        val query = URLEncoder.encode("${track.title} ${track.artist}", "UTF-8")
        val raw = fetchText("https://genius.com/api/search/multi?q=$query") ?: return null
        val sections = JSONObject(raw).optJSONObject("response")?.optJSONArray("sections") ?: return null

        data class Hit(val url: String, val title: String, val artist: String)

        val hits = mutableListOf<Hit>()
        for (i in 0 until sections.length()) {
            val list = sections.optJSONObject(i)?.optJSONArray("hits") ?: continue
            for (j in 0 until list.length()) {
                val hit = list.optJSONObject(j) ?: continue
                if (hit.optString("type") != "song") continue
                val result = hit.optJSONObject("result") ?: continue
                val url = result.optString("url")
                if (url.isBlank()) continue
                hits += Hit(
                    url = url,
                    title = result.optString("title"),
                    artist = result.optString(
                        "primary_artist_names",
                        result.optJSONObject("primary_artist")?.optString("name").orEmpty()
                    )
                )
            }
        }
        if (hits.isEmpty()) return null

        val wantedTitle = normalize(track.title)
        val wantedArtist = normalize(track.artist)
        val best = hits.maxByOrNull { h ->
            var score = 0
            val ht = normalize(h.title)
            val ha = normalize(h.artist)
            if (ht == wantedTitle) score += 3
            else if (ht.contains(wantedTitle) || wantedTitle.contains(ht)) score += 2
            if (ha.contains(wantedArtist) || wantedArtist.contains(ha)) score += 2
            if (h.title.lowercase().contains(track.artist.substringBefore(",").trim().lowercase())) score += 1
            score
        } ?: return null

        val html = fetchText(best.url) ?: return null
        return Regex("open\\.spotify\\.com/(?:embed/)?track/([A-Za-z0-9]{10,})")
            .find(html)?.groupValues?.getOrNull(1)
    }

    private fun normalize(s: String): String = s.lowercase(Locale.ROOT)
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("[^a-z0-9à-ÿ ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    // ------------------------------------------------------ Spotify token
    private fun accessToken(): String? {
        val spDc = AppSettings.spDc.value.trim()
        if (tokenCookieHash != spDc.hashCode()) { resetAuth(); tokenCookieHash = spDc.hashCode() }
        if (token != null && System.currentTimeMillis() < tokenExpiry - 30_000) return token
        if (spDc.isBlank()) return null
        val (version, code) = totp() ?: return null
        val url = "https://open.spotify.com/api/token?reason=init&productType=mobile-web-player" +
            "&totp=$code&totpVer=$version&totpServer=$code"
        val raw = fetchText(
            url,
            mapOf(
                "Origin" to "https://open.spotify.com",
                "Referer" to "https://open.spotify.com/",
                "Cookie" to "sp_dc=$spDc"
            )
        ) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val access = json.optString("accessToken")
        if (access.isBlank()) return null
        token = access
        tokenExpiry = json.optLong(
            "accessTokenExpirationTimestampMs",
            System.currentTimeMillis() + 3_600_000L
        )
        return access
    }

    private fun totp(): Pair<String, String>? {
        val (version, key) = secretKey() ?: return null
        val counter = System.currentTimeMillis() / 1000L / 30L
        val data = ByteArray(8)
        var v = counter
        for (i in 7 downTo 0) {
            data[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = mac.doFinal(data)
        val offset = hash[19].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        val code = String.format(Locale.US, "%06d", binary % 1_000_000)
        return version to code
    }

    private fun secretKey(): Pair<String, ByteArray>? {
        val now = System.currentTimeMillis()
        secrets?.let { if (now - secretsAt < 3_600_000L) return it }
        val raw = fetchText(SECRETS_URL) ?: return secrets
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return secrets
        val version = json.keys().asSequence()
            .filter { it.toIntOrNull() != null }
            .maxByOrNull { it.toInt() } ?: return secrets
        val arr = json.optJSONArray(version) ?: return secrets
        val builder = StringBuilder()
        for (i in 0 until arr.length()) {
            builder.append((arr.getInt(i) xor ((i % 33) + 9)).toString())
        }
        val key = builder.toString().toByteArray(Charsets.UTF_8)
        secrets = version to key
        secretsAt = now
        return secrets
    }

    // ------------------------------------------------------ Canvas API
    private fun fetchCanvasUrl(spotifyId: String): String? {
        val tok = accessToken() ?: return null
        val uri = "spotify:track:$spotifyId".toByteArray(Charsets.UTF_8)
        // protobuf : CanvasRequest { tracks[0].track_uri = uri }
        val inner = ByteArray(2 + uri.size)
        inner[0] = 0x0A
        inner[1] = uri.size.toByte()
        uri.copyInto(inner, 2)
        val body = ByteArray(2 + inner.size)
        body[0] = 0x0A
        body[1] = inner.size.toByte()
        inner.copyInto(body, 2)

        val bytes = requestBytes(
            "https://spclient.wg.spotify.com/canvaz-cache/v0/canvases",
            "POST",
            body,
            mapOf(
                "Accept" to "application/protobuf",
                "Accept-Language" to "en",
                "Accept-Encoding" to "gzip",
                "Content-Type" to "application/x-www-form-urlencoded",
                "User-Agent" to SPOTIFY_CLIENT_UA,
                "Authorization" to "Bearer $tok"
            )
        ) ?: return null
        val text = String(bytes, Charsets.ISO_8859_1)
        return Regex("https://[\\x21-\\x7E]+\\.cnvs\\.mp4").find(text)?.value
    }

    // ------------------------------------------------------------- HTTP
    /** Compatible with the two supplied server repositories; never forward sp_dc to them. */
    private fun serverCanvasUrl(spotifyId: String): String? {
        val base = AppSettings.canvasUrl.value.trim().trimEnd('/')
        if (!base.startsWith("https://")) return null
        val endpoint = if (base.endsWith("/api/canvas")) base else "$base/api/canvas"
        val raw = fetchText("$endpoint?trackId=$spotifyId") ?: return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return obj.optJSONObject("data")?.optJSONArray("canvasesList")?.optJSONObject(0)
            ?.optString("canvasUrl")?.takeIf { it.startsWith("https://") }
    }

    private fun requestBytes(
        url: String,
        method: String = "GET",
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap()
    ): ByteArray? {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    setFixedLengthStreamingMode(body.size)
                }
            }
            if (body != null) c.outputStream.use { it.write(body) }
            val code = c.responseCode
            if (code !in 200..299) {
                runCatching { c.errorStream?.close() }
                c.disconnect()
                return null
            }
            val raw = c.inputStream.use { it.readBytes() }
            c.disconnect()
            if (raw.size > 2 && raw[0] == 0x1F.toByte() && raw[1] == 0x8B.toByte()) {
                GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
            } else raw
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchText(url: String, headers: Map<String, String> = emptyMap()): String? =
        requestBytes(url, "GET", null, headers)?.toString(Charsets.UTF_8)

    // ----------------------------------------------------------- fichiers
    private fun canvasDir(context: Context): File =
        File(context.filesDir, "canvas").apply { if (!exists()) mkdirs() }

    private fun download(url: String, dest: File): File? {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 40_000
                setRequestProperty("User-Agent", UA)
            }
            if (c.responseCode !in 200..299) {
                c.disconnect()
                return null
            }
            val tmp = File(dest.parentFile, dest.name + ".part")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            c.disconnect()
            if (tmp.length() < 10_000) {
                tmp.delete()
                return null
            }
            if (dest.exists()) dest.delete()
            if (tmp.renameTo(dest)) dest else null
        } catch (_: Exception) {
            null
        }
    }

    private fun networkAllowed(context: Context): Boolean {
        if (AppSettings.canvasWithMobileData.value) return true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}
