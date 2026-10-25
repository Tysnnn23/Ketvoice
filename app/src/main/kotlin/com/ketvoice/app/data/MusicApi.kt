package com.ketvoice.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

object MusicApi {

    private const val YT_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private var matchPrefs: SharedPreferences? = null

    fun init(context: Context) {
        SearchHistory.init(context)
        LyricsDiskCache.init(context)
        if (matchPrefs == null) {
            matchPrefs = context.applicationContext
                .getSharedPreferences("ketvoice_matches", Context.MODE_PRIVATE)
        }
    }

    data class StreamInfo(val url: String, val format: String, val quality: String,
                          val cacheTag: String = "", val downloadUrl: String? = null)
    data class Streams(
        val fast: StreamInfo? = null,
        val best: StreamInfo? = null,
        val artwork: String? = null,
        val album: String? = null,
        val durationMs: Long = 0L
    )
    data class LyricWord(val startMs: Long, val text: String, val endMs: Long = -1L)

    data class LyricLine(
        val timeMs: Long,
        val text: String,

        val words: List<LyricWord> = emptyList(),
        val syllableTimed: Boolean = false,
        val translations: Map<String, String> = emptyMap()
    )

    data class ProviderStatus(
        val available: Boolean,
        val timed: Boolean,
        val lines: Int,

        val wordTimed: Boolean = false,
        val syllableTimed: Boolean = false,
        val detail: String? = null
    )

    const val SRC_AUTO = "auto"
    const val SRC_LRCLIB = "lrclib"
    const val SRC_NETEASE = "netease"
    const val SRC_OVH = "lyricsovh"
    const val SRC_OFF = "off"
    const val SRC_BETTER = "betterlyrics"
    const val SRC_LIRIQO = "liriqo"
    const val SRC_LYRICA = "lyrica"
    const val SRC_LRCRED = "lrcred"
    const val SRC_KETVOICE = "ketvoice"
    val lyricSources = listOf(SRC_KETVOICE, SRC_LIRIQO, SRC_BETTER, SRC_LRCRED, SRC_LYRICA, SRC_NETEASE, SRC_LRCLIB, SRC_OVH)

    fun sourceName(source: String): String = when (source) {
        SRC_AUTO -> "Automatique"
        SRC_KETVOICE -> "Ketvoice API"
        SRC_BETTER -> "BetterLyrics"
        SRC_LIRIQO -> "LiriQo"
        SRC_LYRICA -> "Lyrica"
        SRC_LRCRED -> "lrc.red"
        SRC_NETEASE -> "NetEase Cloud Music"
        SRC_LRCLIB -> "LRCLIB"
        SRC_OVH -> "Lyrics.ovh"
        SRC_OFF -> "Désactivées"
        else -> source
    }

    private class CacheEntry(val at: Long, val json: String)
    private const val CACHE_TTL = 6 * 60_000L
    private val httpCache = HashMap<String, CacheEntry>()

    private fun cachedHttp(url: String, headers: Map<String, String> = emptyMap()): String? {
        val key = if (headers.isEmpty()) url else url + "#" + headers.hashCode()
        val now = System.currentTimeMillis()
        synchronized(httpCache) {
            httpCache[key]?.let { e -> if (now - e.at < CACHE_TTL) return e.json }
        }
        val text = http(url, "GET", null, headers) ?: return null
        synchronized(httpCache) {
            if (httpCache.size > 100) {
                val it = httpCache.entries.iterator()
                while (it.hasNext()) {
                    if (now - it.next().value.at > CACHE_TTL) it.remove()
                }
            }
            httpCache[key] = CacheEntry(now, text)
        }
        return text
    }

    private fun http(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 16_000
            setRequestProperty("User-Agent", UA)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        if (body != null) c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText)
        c.disconnect()
        if (code in 200..299) text else null
    } catch (_: Exception) {
        null
    }

    suspend fun searchYtMusic(q: String): List<Track> = withContext(Dispatchers.IO) {
        if (q.isBlank()) return@withContext emptyList()

        val fields = JSONObject().put("query", q).put("params", OfficialMusicParser.SONGS_FILTER)
        val raw = YouTubeApi.request("search", fields, authenticated = false)
        val songs = raw?.let { OfficialMusicParser.parse(it.toString(), q) }.orEmpty()
        if (songs.isNotEmpty()) return@withContext songs

        YouTubeApi.request("search", JSONObject().put("query", q), authenticated = false)
            ?.let { OfficialMusicParser.parse(it.toString(), q) }.orEmpty()
    }

    suspend fun searchAddon(base: String, tag: String, q: String): List<Track> =
        withContext(Dispatchers.IO) {
            if (base.isBlank()) return@withContext emptyList()
            val raw = cachedHttp("$base/search?q=${enc(q)}") ?: return@withContext emptyList()
            try {
                val arr = JSONObject(raw).optJSONArray("tracks") ?: return@withContext emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id")
                    if (id.isEmpty()) return@mapNotNull null
                    Track(
                        id = "$tag:$id",
                        title = o.optString("title"),
                        artist = o.optString("artist"),
                        album = o.optString("album"),
                        durationMs = (o.optDouble("duration", 0.0) * 1000).toLong(),
                        coverUrl = o.optString("artworkURL", o.optString("artwork")).takeIf { it.startsWith("https://") },
                        addon = tag,
                        addonId = id,
                        isrc = o.optString("isrc").takeIf { it.isNotBlank() },
                        lyricsProvider = "LRCLIB"
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    suspend fun searchAddons(q: String): List<Track> = KetvoiceApi.search(q)

    suspend fun searchAll(q: String): List<Track> = coroutineScope {
        if (q.isBlank()) return@coroutineScope emptyList()
        val yt = async { searchYtMusic(q) }
        val ad = async { searchAddons(q) }
        yt.await() + ad.await()
    }

    suspend fun youtubeStream(track: Track): StreamInfo? = withContext(Dispatchers.IO) {
        val videoId = track.videoId?.takeIf { track.youtubeCatalogVerified }
            ?: bestMatch(searchYtMusic("${track.title} ${track.artist}"), track)?.videoId
            ?: return@withContext null
        val clients = listOf("IOS" to "20.10.4", "ANDROID_VR" to "1.60.19")
        for ((name, version) in clients) {
            val payload = JSONObject().put("videoId", videoId).put("contentCheckOk", true).put("racyCheckOk", true)
                .put("context", JSONObject().put("client", JSONObject().put("clientName", name)
                    .put("clientVersion", version).put("hl", "fr").put("gl", "FR"))).toString()
            val raw = http("https://www.youtube.com/youtubei/v1/player", "POST", payload) ?: continue
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            if (obj.optJSONObject("playabilityStatus")?.optString("status") != "OK") continue
            val formats = obj.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats") ?: continue
            val audio = (0 until formats.length()).mapNotNull { formats.optJSONObject(it) }
                .filter { it.optString("mimeType").startsWith("audio/") && it.optString("url").startsWith("https://") }
                .maxByOrNull { it.optLong("bitrate") } ?: continue
            val url = audio.getString("url")

            var probe: HttpURLConnection? = null
            val usable = try {
                probe = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000; readTimeout = 4000
                    setRequestProperty("Range", "bytes=0-1023")
                    setRequestProperty("User-Agent", UA)
                }
                probe.responseCode in 200..299 && probe.inputStream.use { it.read() >= 0 }
            } catch (_: Exception) { false } finally { probe?.disconnect() }
            if (usable) return@withContext StreamInfo(url, "aac", "YouTube Music", "yt:$videoId:${audio.optInt("itag")}")
        }
        null
    }

    suspend fun addonStream(track: Track, tag: String): StreamInfo? =
        KetvoiceApi.stream(track, tag)

    suspend fun flacStream(track: Track): StreamInfo? =
        addonStream(track, "last")?.takeIf { it.format.contains("flac", true) }
    suspend fun streamOf(base: String, id: String): StreamInfo? = withContext(Dispatchers.IO) {
        val raw = cachedHttp("$base/stream/${enc(id)}") ?: return@withContext null
        try {
            val o = JSONObject(raw)
            val u = o.optString("url")
            if (u.isEmpty()) null
            else StreamInfo(u, o.optString("format"), o.optString("quality"), "$base|$id|${o.optString("format")}")
        } catch (_: Exception) {
            null
        }
    }

    suspend fun resolve(track: Track): Streams = coroutineScope {
        if (track.addon == "upload" || track.id.startsWith("kv:upload:")) {
            val stream = PublicationApi.stream(track)
            return@coroutineScope Streams(fast = stream, best = stream, artwork = track.coverUrl, album = track.album, durationMs = track.durationMs)
        }
        val fast = async { addonStream(track, "um") }
        val best = async { flacStream(track) }
        val custom = async { runCatching { CustomSources.streamForTrack(track, forDownload = true) }.getOrNull() }
        val customStream = custom.await()

        Streams(
            fast = customStream ?: fast.await(),
            best = customStream ?: best.await(),
            artwork = track.coverUrl,
            album = track.album,
            durationMs = track.durationMs
        )
    }

    private fun bestMatch(list: List<Track>, want: Track): Track? {
        if (list.isEmpty()) return null
        val wantTitle = normTitle(want.title)
        val wantArtist = normTitle(want.artist.substringBefore(','))
        val scored = list.mapNotNull { candidate ->
            val ct = normTitle(candidate.title)
            val ca = normTitle(candidate.artist)
            if (ct.isBlank() || wantTitle.isBlank() || !(ct == wantTitle || ct.contains(wantTitle) || wantTitle.contains(ct))) return@mapNotNull null
            if (wantArtist.isNotEmpty() && !(ca.contains(wantArtist) || wantArtist.contains(ca))) return@mapNotNull null
            var score = 0
            if (ct == wantTitle) score += 4
            else if (ct.contains(wantTitle) || wantTitle.contains(ct)) score += 2
            if (wantArtist.isNotEmpty() && (ca.contains(wantArtist) || wantArtist.contains(ca))) score += 3
            if (want.durationMs > 0 && candidate.durationMs > 0) {
                val delta = abs(want.durationMs - candidate.durationMs)
                if (delta < 3_000) score += 2 else if (delta < 10_000) score += 1
            }
            candidate to score
        }

        return scored.maxByOrNull { it.second }?.takeIf { it.second >= 5 }?.first
    }

    private val matchCache = HashMap<String, Track?>()

    suspend fun bestMatchTrack(base: String, tag: String, want: Track): Track? {
        if (want.addon == tag && !want.addonId.isNullOrBlank()) return want
        val key = "$tag|${normTitle(want.title)}|${normTitle(want.artist.substringBefore(','))}"
        synchronized(matchCache) { if (matchCache.containsKey(key)) return matchCache[key] }

        val storeKey = "m:" + key.hashCode()
        val saved = matchPrefs?.getString(storeKey, null)
        if (!saved.isNullOrBlank()) {
            val cached = runCatching {
                val o = JSONObject(saved)
                if (System.currentTimeMillis() - o.optLong("at") > 30L * 24 * 3_600_000) return@runCatching null
                val id = o.optString("id")
                if (id.isBlank()) null else Track(
                    id = "$tag:$id",
                    title = want.title,
                    artist = want.artist,
                    album = o.optString("album"),
                    durationMs = o.optLong("dur"),
                    coverUrl = o.optString("cover").takeIf { it.isNotBlank() },
                    addon = tag,
                    addonId = id
                )
            }.getOrNull()
            if (cached != null) {
                synchronized(matchCache) { matchCache[key] = cached }
                return cached
            }
        }

        val q = "${want.title} ${want.artist.substringBefore(',').trim()}".trim()
        val found = withContext(Dispatchers.IO) { bestMatch(searchAddon(base, tag, q), want) }
        synchronized(matchCache) {
            if (matchCache.size > 300) matchCache.clear()
            matchCache[key] = found
        }
        if (found?.addonId != null) {
            val json = JSONObject().apply {
                put("at", System.currentTimeMillis())
                put("id", found.addonId)
                put("cover", found.coverUrl.orEmpty())
                put("album", found.album)
                put("dur", found.durationMs)
            }
            matchPrefs?.edit()?.putString(storeKey, json.toString())?.apply()
        }
        return found
    }

    fun addonMatchIn(pool: List<Track>, want: Track): Track? {
        if (pool.isEmpty()) return null
        val withId = pool.filter { !it.addonId.isNullOrBlank() && it.id != want.id }
        if (withId.isEmpty()) return null
        val fast = withId.filter { it.addon == "um" }
        return bestMatch(fast, want) ?: bestMatch(withId, want)
    }

    private fun normTitle(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("(feat\\.|ft\\.|featuring).*"), " ")
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private class LyricCacheEntry(val at: Long, val result: Pair<String, List<LyricLine>>?)
    private const val LYRIC_TTL = 10 * 60_000L
    private val lyricCache = HashMap<String, LyricCacheEntry>()
    fun resetLyricsLookups() {
        synchronized(lyricCache) { lyricCache.clear() }
    }

    private fun clearTitle(s: String): String = s
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("(?i)\\b(official|video|audio|lyrics|remaster(ed)?|version|mix|hd|4k|clip)\\b.*"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    suspend fun lyrics(
        track: Track,
        forcePlain: Boolean = false,
        source: String = SRC_AUTO,
        refresh: Boolean = false
    ): Pair<String, List<LyricLine>> = withContext(Dispatchers.IO) {
        if (source == SRC_OFF) return@withContext "Désactivé" to emptyList()
        val title = clearTitle(track.title)
        val artist = track.artist.substringBefore(',').trim()
        val key = "native-v4|$source|$forcePlain|$title|$artist|${track.durationMs / 1000}".lowercase()
        val now = System.currentTimeMillis()
        synchronized(lyricCache) {
            lyricCache[key]?.let { e ->
                if (!refresh && now - e.at < LYRIC_TTL) return@withContext (e.result ?: (labelFor(source) to emptyList()))
            }
        }

        val saved = LyricsDiskCache.read(key)
        if (!refresh && saved != null) return@withContext saved
        if (refresh) synchronized(httpCache) { httpCache.clear() }
        val fetched = fetchForSource(track, title, artist, forcePlain, source)
        val offlineFallback = saved ?: LyricsDiskCache.read(key.replaceFirst("native-v4|", "native-v3|"))
        val result = if (fetched.second.isEmpty() && offlineFallback != null) offlineFallback else fetched
        LyricsDiskCache.write(key, result)
        synchronized(lyricCache) {
            if (lyricCache.size > 90) {
                val it = lyricCache.entries.iterator()
                while (it.hasNext()) if (now - it.next().value.at > LYRIC_TTL) it.remove()
            }
            lyricCache[key] = LyricCacheEntry(now, result)
        }
        result
    }

    suspend fun bestLyrics(track: Track): Triple<String, List<LyricLine>, String>? = coroutineScope {
        val results = mutableListOf<Triple<String, List<LyricLine>, String>>()
        val lock = Any()
        val jobs = lyricSources.map { src ->
            async(Dispatchers.IO) {
                runCatching { lyrics(track, source = src) }.getOrNull()?.let { (label, lines) ->
                    if (lines.isNotEmpty()) synchronized(lock) { results += Triple(label, lines, src) }
                }
            }
        }
        withTimeoutOrNull(18_000) { jobs.joinAll() }
        jobs.forEach { it.cancel() }
        synchronized(lock) {
            results.maxByOrNull { (_, lines, src) ->
                val timing = when {
                    lines.any { it.syllableTimed } -> 500
                    lines.any { it.words.size >= 2 } -> 400
                    lines.any { it.timeMs >= 0 } -> 300
                    else -> 100
                }
                timing + lines.size.coerceAtMost(80) + if (src == SRC_LRCLIB) 5 else 0
            }
        }
    }

    private fun labelFor(source: String): String = when (source) {
        SRC_KETVOICE -> "Ketvoice API"
        SRC_LRCLIB -> "LRCLIB"
        SRC_NETEASE -> "NetEase Cloud Music"
        SRC_OVH -> "Lyrics.ovh"
        else -> "Paroles"
    }

    private suspend fun fetchForSource(
        track: Track,
        title: String,
        artist: String,
        forcePlain: Boolean,
        source: String
    ): Pair<String, List<LyricLine>> {
        if (source == SRC_KETVOICE) {
            val lines = PublicationApi.lyrics(track)
            return "Ketvoice API" to if (forcePlain) lines.map { LyricLine(-1L, it.text) } else lines
        }
        if (source in setOf(SRC_BETTER, SRC_LIRIQO, SRC_LYRICA, SRC_LRCRED)) {
            return ExtraLyricsProviders.fetch(source, track, forcePlain)
        }
        if (source == SRC_LRCLIB) return fetchLyrics(track, title, artist, forcePlain)
        if (source == SRC_NETEASE) {
            return try {
                neteaseLyrics(track, title, artist, forcePlain)
            } catch (_: Exception) {
                "NetEase Cloud Music" to emptyList()
            }
        }
        if (source == SRC_OVH) {
            return try {
                ovhLyrics(title, artist)
            } catch (_: Exception) {
                "Lyrics.ovh" to emptyList()
            }
        }

        return coroutineScope {
            val kvDef = async(Dispatchers.IO) {
                try {
                    kotlinx.coroutines.withTimeoutOrNull(600) { PublicationApi.lyrics(track) }.orEmpty()
                } catch (_: Exception) {
                    emptyList()
                }
            }
            val lrcDef = async(Dispatchers.IO) {
                try { fetchLyrics(track, title, artist, forcePlain) }
                catch (_: Exception) { "LRCLIB" to emptyList<LyricLine>() }
            }
            val neDef = async(Dispatchers.IO) {
                try { neteaseLyrics(track, title, artist, forcePlain) }
                catch (_: Exception) { "NetEase Cloud Music" to emptyList<LyricLine>() }
            }
            val ovhDef = async(Dispatchers.IO) {
                try { ovhLyrics(title, artist) }
                catch (_: Exception) { "Lyrics.ovh" to emptyList<LyricLine>() }
            }
            val kv = kvDef.await()
            if (kv.isNotEmpty()) {
                lrcDef.cancel()
                neDef.cancel()
                ovhDef.cancel()
                val lines = if (forcePlain) kv.map { LyricLine(-1L, it.text) } else kv
                return@coroutineScope "Ketvoice API" to lines
            }
            val lrc = lrcDef.await()
            if (lrc.second.isNotEmpty()) {
                neDef.cancel()
                ovhDef.cancel()
                return@coroutineScope lrc
            }
            val ne = neDef.await()
            if (ne.second.isNotEmpty()) {
                ovhDef.cancel()
                return@coroutineScope ne
            }
            val ovh = ovhDef.await()
            if (ovh.second.isNotEmpty()) return@coroutineScope ovh
            "Paroles" to emptyList()
        }
    }

    suspend fun probeProviders(track: Track): Map<String, ProviderStatus> = coroutineScope {
        val providers = lyricSources
        val jobs = providers.map { src ->
            async(Dispatchers.IO) {
                val (_, lines) = try {
                    lyrics(track, forcePlain = false, source = src)
                } catch (_: Exception) {
                    "" to emptyList()
                }
                src to ProviderStatus(
                    available = lines.isNotEmpty(),
                    timed = lines.firstOrNull()?.timeMs?.let { it >= 0 } == true,
                    lines = lines.size,
                    wordTimed = lines.any { it.words.size >= 2 },
                    syllableTimed = lines.any { it.syllableTimed },
                    detail = ExtraLyricsProviders.status[src]
                )
            }
        }
        jobs.associate { it.await() }
    }

    private fun fetchLyrics(
        track: Track,
        title: String,
        artist: String,
        forcePlain: Boolean
    ): Pair<String, List<LyricLine>> {
        val queries = buildList {
            if (artist.isNotBlank()) add("$title $artist")
            add(title)
            val shorter = title.replace(Regex("[-–—].*$"), "").trim()
            if (shorter.isNotEmpty() && shorter != title) add("$shorter $artist")
        }.distinct()
        for (q in queries) {
            val found = tryLyricsSearch(track, q, forcePlain)
            if (found.second.isNotEmpty()) return found
        }
        return "LRCLIB" to emptyList()
    }

    private fun tryLyricsSearch(
        track: Track,
        query: String,
        forcePlain: Boolean
    ): Pair<String, List<LyricLine>> {
        val raw = cachedHttp("https://lrclib.net/api/search?q=${enc(query)}")
            ?: return "LRCLIB" to emptyList()
        return try {
            val arr = JSONArray(raw)
            var best: JSONObject? = null
            var bestScore = Double.MAX_VALUE
            val wantTitle = normTitle(track.title)
            val wantArtist = normTitle(track.artist.substringBefore(','))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val dur = o.optDouble("duration", 0.0) * 1000.0
                var score = if (track.durationMs > 0) abs(dur - track.durationMs) else 0.0
                val synced = o.optString("syncedLyrics").takeUnless { it == "null" }.orEmpty()
                val plain = o.optString("plainLyrics").takeUnless { it == "null" }.orEmpty()
                if (!forcePlain && synced.isEmpty() && plain.isEmpty()) continue

                if (synced.isEmpty() && !forcePlain) score += 25_000.0

                if (normTitle(o.optString("trackName")) == wantTitle) score -= 40_000.0
                val artistName = o.optString("artistName")
                val candidateTitle = normTitle(o.optString("trackName"))
                val candidateArtist = normTitle(artistName)
                if (candidateTitle.isBlank() || !(candidateTitle == wantTitle || candidateTitle.contains(wantTitle) || wantTitle.contains(candidateTitle))) continue
                if (wantArtist.isNotBlank() && !(candidateArtist.contains(wantArtist) || wantArtist.contains(candidateArtist))) continue
                if (wantArtist.isNotEmpty() &&
                    (artistName.contains(wantArtist, true) || normTitle(artistName) == wantArtist)
                ) score -= 20_000.0
                if (score < bestScore) {
                    bestScore = score
                    best = o
                }
            }
            val b = best ?: return "LRCLIB" to emptyList()
            val synced = b.optString("syncedLyrics").takeUnless { it == "null" }.orEmpty()
            when {
                forcePlain -> "LRCLIB (texte)" to b.optString("plainLyrics")
                    .lines().filter { it.isNotBlank() && it != "null" }.map { LyricLine(-1L, it) }
                synced.isNotEmpty() -> "LRCLIB" to parseLrc(synced)
                else -> "LRCLIB (texte)" to b.optString("plainLyrics")
                    .lines().filter { it.isNotBlank() && it != "null" }.map { LyricLine(-1L, it) }
            }
        } catch (_: Exception) {
            "LRCLIB" to emptyList()
        }
    }

    private fun neteaseLyrics(
        track: Track,
        title: String,
        artist: String,
        forcePlain: Boolean
    ): Pair<String, List<LyricLine>> {
        val q = "$title $artist".trim()
        val headers = mapOf(
            "Referer" to "https://music.163.com/",
            "Origin" to "https://music.163.com"
        )
        val raw = cachedHttp(
            "https://music.163.com/api/search/get?s=${enc(q)}&type=1&limit=8&offset=0",
            headers
        ) ?: return "NetEase Cloud Music" to emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull()
            ?: return "NetEase Cloud Music" to emptyList()
        val songs = root.optJSONObject("result")?.optJSONArray("songs")
            ?: return "NetEase Cloud Music" to emptyList()
        val wantTitle = normTitle(track.title)
        val wantArtist = normTitle(track.artist.substringBefore(','))
        var bestId = ""
        var bestScore = -1
        for (i in 0 until songs.length()) {
            val o = songs.optJSONObject(i) ?: continue
            val name = normTitle(o.optString("name"))
            if (name.isBlank() || !(name == wantTitle || name.contains(wantTitle) || wantTitle.contains(name))) continue
            var score = 0
            if (name == wantTitle) score += 4
            else if (name.contains(wantTitle) || wantTitle.contains(name)) score += 2
            val artists = o.optJSONArray("artists")
            val arName = buildString {
                if (artists != null) for (j in 0 until artists.length()) {
                    append(artists.optJSONObject(j)?.optString("name").orEmpty()).append(' ')
                }
            }
            val ar = normTitle(arName)
            if (wantArtist.isNotBlank() && !(ar.contains(wantArtist) || wantArtist.contains(ar))) continue
            if (wantArtist.isNotEmpty() && (ar.contains(wantArtist) || wantArtist.contains(ar))) score += 2
            val duration = o.optLong("duration")
            if (track.durationMs > 0 && duration > 0) {
                val delta = abs(duration - track.durationMs)
                if (delta < 3_000) score += 2 else if (delta < 10_000) score += 1
            }
            if (score > bestScore) {
                bestScore = score
                bestId = o.optString("id")
            }
        }
        if (bestId.isBlank() || bestScore < 2) return "NetEase Cloud Music" to emptyList()

        val lyricRaw = cachedHttp(
            "https://music.163.com/api/song/lyric?id=$bestId&lv=1&kv=1&tv=-1&yv=1&ytv=1&yrv=1",
            headers
        ) ?: return "NetEase Cloud Music" to emptyList()
        val obj = runCatching { JSONObject(lyricRaw) }.getOrNull()
            ?: return "NetEase Cloud Music" to emptyList()
        val lrc = obj.optJSONObject("lrc")?.optString("lyric").orEmpty()

        val yrc = obj.optJSONObject("yrc")?.optString("lyric").orEmpty()
        var timed = parseYrc(yrc)
        if (timed.isEmpty()) {
            timed = parseLrc(lrc)
        }
        if (timed.isEmpty()) {
            timed = parseLrc(obj.optJSONObject("klyric")?.optString("lyric").orEmpty())
        }
        if (timed.isEmpty()) {
            timed = parseLrc(obj.optJSONObject("tlyric")?.optString("lyric").orEmpty())
        }
        if (timed.isNotEmpty()) {
            if (forcePlain) {
                val plain = timed.map { it.text }.filter { it.isNotBlank() }
                return "NetEase Cloud Music (texte)" to plain.map { LyricLine(-1L, it) }
            }
            return "NetEase Cloud Music" to timed
        }
        val plainLines = lrc.replace("\r", "").lines()
            .map { Regex("\\[\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?]").replace(it, "").trim() }
            .filter { it.isNotEmpty() }
        return "NetEase Cloud Music (texte)" to plainLines.map { LyricLine(-1L, it) }
    }

    private fun ovhLyrics(title: String, artist: String): Pair<String, List<LyricLine>> {
        if (artist.isBlank() || title.isBlank()) return "Lyrics.ovh" to emptyList()
        val raw = cachedHttp("https://api.lyrics.ovh/v1/${enc(artist)}/${enc(title)}")
            ?: return "Lyrics.ovh" to emptyList()
        val text = runCatching { JSONObject(raw).optString("lyrics") }.getOrDefault("")
        if (text.isBlank()) return "Lyrics.ovh" to emptyList()
        val lines = text.replace("\r", "").lines()
            .map { it.trim() }
            .filter {
                it.isNotEmpty() &&
                    !it.startsWith("Paroles de la chanson", ignoreCase = true) &&
                    !it.contains("paroles de la chanson", ignoreCase = true)
            }
        return "Lyrics.ovh" to lines.map { LyricLine(-1L, it) }
    }

    suspend fun translate(text: String, target: String = "fr"): String? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""
        val cacheKey = "translation|${AppSettings.translationSource.value}|$target|$text"
        LyricsDiskCache.read(cacheKey)?.let { return@withContext it.second.joinToString("\n") { line -> line.text } }
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (line in text.replace("\r", "").split('\n')) {
            if (current.isNotEmpty() && current.length + line.length > 450) {
                chunks += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(line)
        }
        if (current.isNotEmpty()) chunks += current.toString()

        val out = StringBuilder()
        for (chunk in chunks) {
            val translated = translateChunk(chunk, target) ?: return@withContext null
            val expectedLines = chunk.split('\n')
            val receivedLines = translated.replace("\r", "").trimEnd('\n').split('\n')

            val aligned = if (receivedLines.size == expectedLines.size) receivedLines
                else expectedLines.map { line ->
                    if (line.isBlank()) "" else translateChunk(line, target)?.replace("\n", " ") ?: return@withContext null
                }
            out.append(aligned.joinToString("\n")).append('\n')
        }
        val result = out.toString().trimEnd('\n').ifBlank { null }
        if (result != null) LyricsDiskCache.write(cacheKey, "Traduction" to result.split('\n').map { LyricLine(-1, it) })
        result
    }

    @Volatile private var googleTranslationRetryAt = 0L

    private fun translationHttp(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", UA)
            if (connection.responseCode in 200..299) connection.inputStream.bufferedReader().use { it.readText() } else null
        } catch (_: Exception) { null } finally { connection?.disconnect() }
    }

    private fun translateChunk(chunk: String, target: String): String? {

        if (System.currentTimeMillis() >= googleTranslationRetryAt) run {
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx" +
                "&sl=auto&tl=${enc(target)}&dt=t&q=${enc(chunk)}"
            val raw = translationHttp(url)
            if (!raw.isNullOrBlank() && raw.trimStart().startsWith("[")) {
                val arr = runCatching { JSONArray(raw) }.getOrNull()
                val segments = arr?.optJSONArray(0)
                if (segments != null) {
                    val sb = StringBuilder()
                    for (i in 0 until segments.length()) {
                        sb.append(segments.optJSONArray(i)?.optString(0).orEmpty())
                    }
                    if (sb.isNotBlank()) return sb.toString()
                }
            }

            googleTranslationRetryAt = System.currentTimeMillis() + 120_000
        }

        val mmUrl = "https://api.mymemory.translated.net/get?q=${enc(chunk)}" +
            "&langpair=${enc("${AppSettings.translationSource.value}|$target")}"
        val raw = translationHttp(mmUrl) ?: return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (obj.optInt("responseStatus", 0) !in 200..299) return null
        return obj.optJSONObject("responseData")?.optString("translatedText")
            ?.takeIf { it.isNotBlank() }
    }

    private val lrcLineRe = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?](.*)")
    private val lrcWordTagRe = Regex("<(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?>")
    private val yrcLineRe = Regex("^\\[(\\d+),(\\d+)]\\s*(.*)$")
    private val yrcWordRe = Regex("\\((\\d+),(\\d+),(-?\\d+)\\)(.*?)(?=\\(\\d+,\\d+,-?\\d+\\)|$)")
    private val creditRe = Regex(
        "(?i)^\\s*(?:(?:作词|作曲|编曲|制作人?|录音|混音|母带)|" +
            "(?:lyrics?|lyricist|composer|producer|arranger|written by|produced by)\\b).*"
    )

    private fun lrcTime(groups: List<String>): Long {
        val min = groups[1].toLongOrNull() ?: 0L
        val sec = groups[2].toLongOrNull() ?: 0L
        val frac = groups.getOrNull(3).orEmpty().let {
            if (it.isEmpty()) 0L else it.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        }
        return min * 60_000 + sec * 1_000 + frac
    }

    internal fun parseLrc(lrc: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        for (raw in lrc.replace("\r", "").lines()) {
            val line = raw.trim()
            val m = lrcLineRe.find(line) ?: continue
            val time = lrcTime(m.groupValues)
            val body = m.groupValues[4]
            val tags = lrcWordTagRe.findAll(body).toList()
            val words = if (tags.isNotEmpty() &&
                body.substring(0, tags.first().range.first).isBlank()
            ) {
                tags.mapIndexedNotNull { i, tag ->
                    val start = tag.range.last + 1
                    val end = if (i + 1 < tags.size) tags[i + 1].range.first else body.length
                    val word = body.substring(start, end)
                    if (word.isEmpty()) null else LyricWord(lrcTime(tag.groupValues), word)
                }
            } else {
                emptyList()
            }
            val text = if (words.isNotEmpty()) {
                words.joinToString("") { it.text }.trim()
            } else {
                body.replace(lrcWordTagRe, "").trim()
            }
            if (text.isEmpty() || creditRe.containsMatchIn(text)) continue
            out += LyricLine(time, text, words)
        }
        return out.sortedBy { it.timeMs }
    }

    private fun parseYrc(yrc: String): List<LyricLine> {
        if (yrc.isBlank()) return emptyList()
        val out = mutableListOf<LyricLine>()
        for (raw in yrc.replace("\r", "").lines()) {
            val line = raw.trim()
            val m = yrcLineRe.find(line) ?: continue
            val lineStart = m.groupValues[1].toLongOrNull() ?: continue
            val body = m.groupValues[3]
            val words = yrcWordRe.findAll(body).mapNotNull { wm ->
                val start = wm.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val text = wm.groupValues[4]
                if (text.isEmpty()) null else LyricWord(start, text, start + wm.groupValues[2].toLong())
            }.toList()
            if (words.isEmpty()) continue
            val text = words.joinToString("") { it.text }.trim()
            if (text.isEmpty() || creditRe.containsMatchIn(text)) continue
            out += LyricLine(lineStart, text, words)
        }
        return out.sortedBy { it.timeMs }
    }

    private fun parseDuration(s: String): Long {
        val p = s.split(":").mapNotNull { it.toLongOrNull() }
        return when (p.size) {
            3 -> p[0] * 3_600_000 + p[1] * 60_000 + p[2] * 1_000
            2 -> p[0] * 60_000 + p[1] * 1_000
            else -> 0L
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun collect(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                val nested = node.optJSONObject(key)
                if (nested != null) out.add(nested)
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k != key) collect(node.opt(k), key, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), key, out)
        }
    }

    private fun runsText(col: JSONObject?): String? {
        if (col == null) return null
        val runs = col.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")?.optJSONArray("runs") ?: return null
        val sb = StringBuilder()
        for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        return sb.toString().ifBlank { null }
    }
}
