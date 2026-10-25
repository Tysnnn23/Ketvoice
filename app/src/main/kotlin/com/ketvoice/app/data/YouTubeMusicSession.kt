package com.ketvoice.app.data

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

data class YouTubeProfile(val connected: Boolean = false, val name: String = "Mode local", val email: String = "",
                          val photo: String? = null, val message: String = "Aucune session YouTube Music connectée",
                          val syncedListens: Int = 0)

object YouTubeMusicSession {
    private val _profile = MutableStateFlow(YouTubeProfile())
    val profile: StateFlow<YouTubeProfile> = _profile
    private var store: SecureSessionStore? = null
    @Volatile private var cookie: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun init(context: Context) {
        if (store != null) return
        store = SecureSessionStore(context.applicationContext)
        cookie = store?.get("youtube-cookie")
        if (!cookie.isNullOrBlank()) scope.launch { validate(cookie.orEmpty(), false) }
    }
    fun headers(override: String? = null): Map<String, String> {
        val value = override ?: cookie?.takeIf { _profile.value.connected } ?: return emptyMap()
        return sessionHeaders(value, System.currentTimeMillis() / 1000)
    }
    internal fun sessionHeaders(value: String, seconds: Long): Map<String, String> {
        val items = value.split(';').mapNotNull { part ->
            val at = part.indexOf('='); if (at <= 0) null else part.substring(0, at).trim() to part.substring(at + 1).trim()
        }.toMap()
        val sid = items["SAPISID"] ?: items["__Secure-3PAPISID"] ?: return emptyMap()
        val input = "$seconds $sid https://music.youtube.com"
        val hash = MessageDigest.getInstance("SHA-1").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
        return mapOf("Cookie" to value, "Authorization" to "SAPISIDHASH ${seconds}_$hash", "X-Goog-AuthUser" to "0",
            "X-Origin" to "https://music.youtube.com", "X-YouTube-Bootstrap-Logged-In" to "true")
    }
    suspend fun validate(value: String, persist: Boolean = true): Boolean {
        if (headers(value).isEmpty()) { _profile.value = YouTubeProfile(message = "Connectez-vous d'abord sur la page YouTube Music."); return false }
        val response = YouTubeApi.request("account/account_menu", JSONObject(), value)
        val header = response?.let { YouTubeJson.objects(it, "activeAccountHeaderRenderer").firstOrNull() }
        val name = header?.let { YouTubeJson.text(it.optJSONObject("accountName")) }.orEmpty()
        if (name.isBlank()) {
            _profile.value = YouTubeProfile(message = "Google n'a pas confirmé de session authentifiée. Aucun compte n'est déclaré connecté.")
            return false
        }
        cookie = value
        if (persist) store?.put("youtube-cookie", value)
        _profile.value = YouTubeProfile(true, name, YouTubeJson.text(header?.optJSONObject("email")),
            YouTubeJson.image(header?.optJSONObject("accountPhoto")), "Session web personnelle confirmée par YouTube Music")
        HomeFeed.refreshYouTube()
        return true
    }

    suspend fun connectCookie(raw: String): Boolean {
        val value = normalizeCookie(raw)
        if (value.isBlank()) {
            _profile.value = _profile.value.copy(connected = false, message = "Cookie vide")
            return false
        }
        return validate(value, persist = true)
    }

    internal fun normalizeCookie(raw: String): String {
        val text = raw.trim()
        if (text.isBlank()) return ""
        if (text.contains("SAPISID=") || text.contains("__Secure-3PAPISID=")) return text
        if (text.matches(Regex("[A-Za-z0-9_\\-]{20,}"))) {
            return "SAPISID=$text; __Secure-3PAPISID=$text"
        }
        return text
    }

    fun signOut() {
        cookie = null
        store?.remove("youtube-cookie")
        _profile.value = YouTubeProfile()
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        android.webkit.CookieManager.getInstance().flush()
        HomeFeed.clearPersonalized()
    }

    fun submitSearch(query: String) {
        if (!_profile.value.connected || query.isBlank()) return
        scope.launch { YouTubeApi.request("search", JSONObject().put("query", query).put("params", OfficialMusicParser.SONGS_FILTER)) }
    }

    fun recordListen(track: Track) {
        if (!_profile.value.connected || !AppSettings.syncYouTubeHistory.value) return
        scope.launch {
            val canonical = if (track.youtubeCatalogVerified && track.videoId != null) track else
                MusicApi.searchYtMusic("${track.title} ${track.artist}").firstOrNull {
                    it.title.equals(track.title, true) && it.artist.contains(track.artist.substringBefore(','), true) &&
                        (track.durationMs <= 0 || kotlin.math.abs(track.durationMs - it.durationMs) < 8000)
                } ?: return@launch
            val song = YouTubeApi.request("player", JSONObject().put("videoId", canonical.videoId)) ?: return@launch
            val endpoint = song.optJSONObject("playbackTracking")?.optJSONObject("videostatsPlaybackUrl")?.optString("baseUrl") ?: return@launch
            val url = runCatching { URL(endpoint) }.getOrNull() ?: return@launch
            if (url.protocol != "https" || url.host !in setOf("music.youtube.com", "www.youtube.com", "s.youtube.com") || !url.path.startsWith("/api/stats/")) return@launch
            var c: HttpURLConnection? = null
            try {
                c = URL(endpoint + (if ('?' in endpoint) "&" else "?") + "ver=2&c=WEB_REMIX&cpn=${UUID.randomUUID().toString().replace("-", "").take(16)}").openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false; c.connectTimeout = 6000; c.readTimeout = 8000
                headers().forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (c.responseCode in 200..299) {
                    _profile.value = _profile.value.copy(syncedListens = _profile.value.syncedListens + 1,
                        message = "Dernière écoute acceptée par YouTube Music")

                    HomeFeed.refreshYouTube()
                }
                else _profile.value = _profile.value.copy(message = "Écoute non synchronisée : YouTube a refusé la requête")
            } catch (_: Exception) { _profile.value = _profile.value.copy(message = "Synchronisation temporairement indisponible") }
            finally { c?.disconnect() }
        }
    }
}

object YouTubeApi {
    suspend fun request(endpoint: String, fields: JSONObject, cookieOverride: String? = null, authenticated: Boolean = true): JSONObject? = withContext(Dispatchers.IO) {
        require(endpoint.matches(Regex("[a-z_]+(?:/[a-z_]+)?")))
        val body = JSONObject(fields.toString()).put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "WEB_REMIX").put("clientVersion", "1.20241014.01.00").put("hl", "fr").put("gl", "FR")))
        var c: HttpURLConnection? = null
        try {
            c = URL("https://music.youtube.com/youtubei/v1/$endpoint").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.instanceFollowRedirects = false
            c.connectTimeout = 7000; c.readTimeout = 12000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Origin", "https://music.youtube.com")
            if (authenticated) YouTubeMusicSession.headers(cookieOverride).forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode in 200..299) JSONObject(c.inputStream.bufferedReader().use { it.readText() }) else null
        } catch (_: Exception) { null } finally { c?.disconnect() }
    }
}

object YouTubeJson {
    fun objects(node: Any?, key: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { k -> val v = value.opt(k); if (k == key && v is JSONObject) out += v; visit(v) }
                is JSONArray -> (0 until value.length()).forEach { visit(value.opt(it)) }
            }
        }
        visit(node)
        return out
    }
    fun text(o: JSONObject?): String {
        if (o == null) return ""
        if (o.has("simpleText")) return o.optString("simpleText")
        val runs = o.optJSONArray("runs") ?: return ""
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
    }
    fun image(o: JSONObject?): String? {
        if (o == null) return null
        var best: String? = null; var width = -1
        fun visit(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val url = node.optString("url")
                    if (url.startsWith("https://") && node.optInt("width", -1) > 0 && node.optInt("width", -1) >= width) { best = url; width = node.optInt("width", -1) }
                    node.keys().forEach { visit(node.opt(it)) }
                }
                is JSONArray -> (0 until node.length()).forEach { visit(node.opt(it)) }
            }
        }
        visit(o)
        return best
    }
}
