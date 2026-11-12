package com.ketvoice.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

data class CustomSource(
    val id: String,
    val manifestUrl: String,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val quality: String = "high",
    val manifestJson: String = "",
    val checkedAt: Long = 0L,
    val error: String? = null
)

data class SourceManifest(
    val name: String,
    val baseUrl: String,
    val searchUrl: String,
    val streamUrl: String?,
    val downloadUrl: String?,

    val qualities: List<String>,

    val downloadsAllowed: Boolean = true,
    val explicitDownloadPermission: Boolean = false
) {
    val resolvedSearch: String get() = absolute(searchUrl)

    val resolvedStream: String? get() = streamUrl?.let(::absolute)
        ?: baseUrl.takeIf { it.isNotBlank() }?.let { absolute("$it/stream/{id}?quality={quality}") }

    val downloadEndpoint: String? get() = downloadUrl?.let(::absolute)
    val resolvedDownload: String? get() = downloadEndpoint ?: resolvedStream

    private fun absolute(value: String): String {
        if (value.startsWith("https://")) return value
        if (!value.startsWith("/")) return baseUrl.trimEnd('/') + "/" + value
        return baseUrl.trimEnd('/') + value
    }
}

object CustomSources {
    private const val PREFS = "ketvoice_custom_sources"
    private const val UA = "Ketvoice/2.3 (Android)"
    internal const val LOSSLESS_MANIFEST = "https://ketvoice-lossless.onrender.com/manifest.json"
    private const val LOSSLESS_DEFAULT = """{"name":"Ketvoice Lossless","resources":["search","stream"],"settings":[{"key":"quality","options":[{"value":"lossless"},{"value":"high"},{"value":"low"}]}]}"""
    private val reusableManifest = Regex("https://ketvoice-lossless\\.onrender\\.com/tidal-mpd/[0-9]+\\?quality=(low|high|lossless)")
    internal fun reusableManifestUrl(url: String): Boolean = reusableManifest.matches(url)
    internal var openStreamConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
    @Volatile private var fastSearchSupported = true

    private val _sources = MutableStateFlow<List<CustomSource>>(emptyList())
    val sources: StateFlow<List<CustomSource>> = _sources
    private var prefs: android.content.SharedPreferences? = null
    private val losslessMatches = ArrayDeque<Pair<Long, Track>>()

    fun init(context: Context) {
        SourcePriority.init(context)
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val arr = runCatching { JSONArray(p.getString("items", "[]")) }.getOrNull() ?: return
        _sources.value = (0 until arr.length()).mapNotNull { i ->
            runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
        }
        if (!p.getBoolean("lossless_added_v1", false)) {
            val existing = _sources.value.firstOrNull { it.manifestUrl == LOSSLESS_MANIFEST }
            val id = existing?.id ?: MessageDigest.getInstance("SHA-1").digest(LOSSLESS_MANIFEST.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(12)
            if (existing == null) {
                _sources.value = _sources.value + CustomSource(id, LOSSLESS_MANIFEST, "Ketvoice Lossless",
                    "https://ketvoice-lossless.onrender.com", quality = "high", manifestJson = LOSSLESS_DEFAULT)
                persist()
            }
            SourcePriority.promotePlaybackOnce("cs:$id")
            p.edit().putBoolean("lossless_added_v1", true).apply()
        }
    }

    fun enabledSources(): List<CustomSource> = _sources.value.filter { it.enabled }

    private fun requestedQuality(source: CustomSource): String =
        if (source.manifestUrl == LOSSLESS_MANIFEST && source.quality == "high") "lossless" else source.quality

    suspend fun add(manifestUrl: String): Result<CustomSource> = withContext(Dispatchers.IO) {
        val url = manifestUrl.trim()
        if (!isValidManifestUrl(url)) return@withContext Result.failure(
            IllegalArgumentException("L'adresse doit commencer par https:// et finir par .json")
        )
        val fetched = fetchText(url) ?: return@withContext Result.failure(
            IllegalStateException("Manifeste inaccessible (réseau, 404 ou blocage du serveur)")
        )
        val manifest = parseManifest(url, fetched) ?: return@withContext Result.failure(
            IllegalArgumentException("Manifeste JSON invalide : aucun endpoint de recherche reconnu")
        )
        val id = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(12)
        val existing = _sources.value.firstOrNull { it.id == id }

        val probe = fill(manifest.resolvedSearch, query = "test", id = "",
            quality = negotiateQuality(existing?.quality ?: "high", manifest.qualities))
        val probeRaw = fetchText(probe, timeout = 12_000)
        if (probeRaw == null || runCatching { org.json.JSONTokener(probeRaw).nextValue() }.isFailure) {
            return@withContext Result.failure(
                IllegalStateException("La recherche de test n'a pas renvoyé un JSON valide")
            )
        }
        val source = CustomSource(
            id = id, manifestUrl = url, name = manifest.name, baseUrl = manifest.baseUrl,
            enabled = existing?.enabled ?: true, quality = existing?.quality ?: "high",
            manifestJson = fetched, checkedAt = System.currentTimeMillis()
        )
        _sources.value = (_sources.value.filterNot { it.id == id } + source)
        persist()
        Result.success(source)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        _sources.value = _sources.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        persist()
    }

    fun setQuality(id: String, quality: String) {
        _sources.value = _sources.value.map {
            if (it.id == id) it.copy(quality = if (quality == "low") "low" else "high") else it
        }
        persist()
    }

    fun remove(id: String) {
        _sources.value = _sources.value.filterNot { it.id == id }
        persist()
    }

    /** Moves a source up (-1) or down (+1); the list order is the priority order. */
    fun move(id: String, delta: Int) {
        if (delta == 0) return
        val list = _sources.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (to == from) return
        val item = list.removeAt(from)
        list.add(to, item)
        _sources.value = list
        persist()
    }

    suspend fun refresh(id: String): Result<CustomSource> = withContext(Dispatchers.IO) {
        val current = _sources.value.firstOrNull { it.id == id }
            ?: return@withContext Result.failure(IllegalStateException("Source absente"))
        val fetched = fetchText(current.manifestUrl)
        if (fetched == null) {
            _sources.value = _sources.value.map {
                if (it.id == id) it.copy(error = "Manifeste inaccessible", checkedAt = System.currentTimeMillis()) else it
            }
            persist()
            return@withContext Result.failure(IllegalStateException("Manifeste inaccessible"))
        }
        val manifest = parseManifest(current.manifestUrl, fetched)
        if (manifest == null) {
            _sources.value = _sources.value.map {
                if (it.id == id) it.copy(error = "JSON non reconnu", checkedAt = System.currentTimeMillis()) else it
            }
            persist()
            return@withContext Result.failure(IllegalArgumentException("JSON non reconnu"))
        }
        val updated = current.copy(name = manifest.name, baseUrl = manifest.baseUrl, manifestJson = fetched,
            checkedAt = System.currentTimeMillis(), error = null)
        _sources.value = _sources.value.map { if (it.id == id) updated else it }
        persist()
        Result.success(updated)
    }

    suspend fun searchAll(query: String): List<Track> = coroutineScope {
        enabledSources().map { source -> async(Dispatchers.IO) { search(source, query) } }.flatMap { it.await() }
    }

    suspend fun search(source: CustomSource, query: String, fast: Boolean = false): List<Track> = withContext(Dispatchers.IO) {
        val manifest = parseManifest(source.manifestUrl, source.manifestJson) ?: return@withContext emptyList()
        val quality = negotiateQuality(requestedQuality(source), manifest.qualities)
        val url = fill(manifest.resolvedSearch, query = query, id = "", quality = quality)
        val fastUrl = if (fast && fastSearchSupported && source.manifestUrl == LOSSLESS_MANIFEST)
            fill("${source.baseUrl}/search/fast?q={query}&quality={quality}", query, "", quality) else null
        // The deployed aggregate search has been observed taking 24.5 s; it runs
        // concurrently with other providers and the optional fast endpoint avoids it.
        val timeout = if (source.manifestUrl == LOSSLESS_MANIFEST && fastUrl == null) 25_000 else 10_000
        val raw = fetchText(fastUrl ?: url, timeout) ?: run {
            // A server without /search/fast must not cost an extra request on every track.
            if (fastUrl != null) fastSearchSupported = false
            return@withContext emptyList()
        }
        val tracks = parseTracks(source, raw)
        if (source.manifestUrl == LOSSLESS_MANIFEST) synchronized(losslessMatches) {
            val now = System.currentTimeMillis()
            tracks.filter { !it.addonId.isNullOrBlank() && it.streamUrl == null }.forEach {
                losslessMatches.addLast(now to it.copy(downloadUrl = null))
            }
            while (losslessMatches.size > 200) losslessMatches.removeFirst()
        }
        tracks
    }

    suspend fun streamForTrack(track: Track, forDownload: Boolean = false): MusicApi.StreamInfo? =
        withContext(Dispatchers.IO) {
            val tagged = track.addon?.takeIf { it.startsWith("cs:") }
            val source = if (tagged != null) _sources.value.firstOrNull { it.enabled && "cs:${it.id}" == tagged } else null
            if (source != null) {

                val manifest = parseManifest(source.manifestUrl, source.manifestJson)
                if (forDownload && manifest?.downloadsAllowed == false) return@withContext null
                stream(source, track, forDownload)?.let { return@withContext it }
            }
            if (track.streamUrl?.startsWith("https://") == true) {
                return@withContext streamInfo(track.streamUrl, track.downloadUrl, source?.quality ?: "high")
            }

            for (candidate in enabledSources()) {
                val found = search(candidate, "${track.title} ${track.artist.substringBefore(',')}")
                    .bestCatalogMatch(track) ?: continue
                stream(candidate, found, forDownload)?.let { return@withContext it }
            }
            null
        }

    internal suspend fun streamFrom(source: CustomSource, wanted: Track, forDownload: Boolean): MusicApi.StreamInfo? {
        if (!source.enabled) return null
        val manifest = parseManifest(source.manifestUrl, source.manifestJson) ?: return null
        if (forDownload && !manifest.downloadsAllowed) return null
        val tried = mutableSetOf<String>()
        if (wanted.addon == "cs:${source.id}") {
            tried += wanted.addonId ?: wanted.id
            stream(source, wanted, forDownload)?.let { return it }
        }
        val cached = if (source.manifestUrl == LOSSLESS_MANIFEST) synchronized(losslessMatches) {
            val cutoff = System.currentTimeMillis() - 30 * 60_000L
            losslessMatches.filter { it.first >= cutoff && it.second.addon == "cs:${source.id}" }.map { it.second }
        } else emptyList()
        suspend fun attempt(tracks: List<Track>) = resolveCatalogMatches(wanted, tracks, tried) { stream(source, it, forDownload) }
        attempt(cached)?.let { return it }
        val query = "${wanted.title} ${wanted.artist.substringBefore(',')}"
        val fast = source.manifestUrl == LOSSLESS_MANIFEST
        attempt(search(source, query, fast = fast))?.let { return it }
        if (fast && tried.size < 4) attempt(search(source, query))?.let { return it }
        return null
    }

    private suspend fun stream(source: CustomSource, track: Track, forDownload: Boolean): MusicApi.StreamInfo? =
        withContext(Dispatchers.IO) {
            val manifest = parseManifest(source.manifestUrl, source.manifestJson) ?: return@withContext null
            if (forDownload && !manifest.downloadsAllowed) return@withContext null
            val id = track.addonId.orEmpty()
            val qualityParam = negotiateQuality(requestedQuality(source), manifest.qualities)

            val downloadTemplate = manifest.downloadEndpoint?.takeIf {
                forDownload && (id.isNotBlank() || !it.contains("{id}"))
            }
            val direct = track.streamUrl?.takeIf { it.startsWith("https://") }
            val provider = when (track.addonId?.substringBefore(':')?.lowercase()) {
                "tidal" -> "TIDAL"
                "qobuz" -> "Qobuz"
                "amazon" -> "Amazon"
                else -> null
            }
            if (direct != null && downloadTemplate == null) return@withContext streamInfo(direct, track.downloadUrl, source.quality)
                .copy(provider = provider, dashDownloadAllowed = forDownload && manifest.explicitDownloadPermission)
            val template = downloadTemplate ?: manifest.resolvedStream ?: return@withContext null
            if (id.isBlank() && template.contains("{id}")) return@withContext null
            val url = fill(template, query = "", id = id, quality = qualityParam,
                title = track.title, artist = track.artist)
            val raw = fetchStreamEndpoint(url) ?: return@withContext null
            parseStreamResponse(raw, source, forDownload)?.copy(provider = provider,
                dashDownloadAllowed = forDownload && manifest.explicitDownloadPermission)
        }

    /** Both stream and download endpoints may answer with JSON, a URL or audio. */
    internal fun parseStreamResponse(raw: String, source: CustomSource, forDownload: Boolean = false): MusicApi.StreamInfo? {
            val trimmed = raw.trim()
            if (trimmed.startsWith("https://")) {
                return streamInfo(trimmed, if (forDownload) trimmed else null, source.quality)
            }
            val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
            val nested = obj.optJSONObject("data")
            if (obj.optBoolean("encrypted", false) || nested?.optBoolean("encrypted", false) == true) return null
            val sources = obj.optJSONArray("sources") ?: nested?.optJSONArray("sources")
                ?: obj.optJSONArray("streams") ?: nested?.optJSONArray("streams")
            val fromArray = sources?.let { arr ->
                (0 until arr.length()).firstNotNullOfOrNull { i ->
                    when (val item = arr.opt(i)) {
                        is String -> item.takeIf { it.isNotBlank() }
                        is JSONObject -> firstString(item, "url", "stream", "link", "audio", "download")
                        else -> null
                    }
                }
            }
            val rawStream = listOf(
                if (forDownload) obj.optString("downloadUrl") else "",
                if (forDownload) nested?.optString("downloadUrl").orEmpty() else "",
                obj.optString("url"), obj.optString("stream"), obj.optString("link"),
                obj.optString("audio"), obj.optString("download"), obj.optString("downloadUrl"),
                nested?.optString("url").orEmpty(), nested?.optString("stream").orEmpty(),
                nested?.optString("link").orEmpty(), fromArray.orEmpty()
            ).firstOrNull { it.startsWith("https://") || it.startsWith("/") }
                ?: return null
            val stream = if (rawStream.startsWith("https://")) rawStream
                else source.baseUrl.trimEnd('/') + rawStream
            val rawDownload = listOf(
                obj.optString("downloadUrl"), obj.optString("download"), obj.optString("file"),
                nested?.optString("downloadUrl").orEmpty(), nested?.optString("download").orEmpty(),
                nested?.optString("file").orEmpty()
            ).firstOrNull { it.startsWith("https://") || it.startsWith("/") }
            val download = rawDownload?.let {
                if (it.startsWith("https://")) it else source.baseUrl.trimEnd('/') + it
            }
            val format = firstString(obj, "format", "codec", "container", "fileCodec")
                ?: firstString(nested, "format", "codec", "container", "fileCodec")
                ?: ""
            val quality = firstString(obj, "quality", "streamQuality", "audioQuality")
                ?: firstString(nested, "quality", "streamQuality", "audioQuality")
                ?: ""
            val mimeType = firstString(obj, "mimeType", "mime_type", "contentType")
                ?: firstString(nested, "mimeType", "mime_type", "contentType")
            val codec = firstString(obj, "codec", "fileCodec") ?: firstString(nested, "codec", "fileCodec")
            val manifest = firstString(obj, "manifest") ?: firstString(nested, "manifest")
            val info = streamInfo(stream, download, source.quality)
            val finalFormat = if (manifest.equals("dash", true) || mimeType?.substringBefore(';')?.trim().equals("application/dash+xml", true)) "dash"
                else format.ifBlank { info.format }
            val finalQuality = quality.ifBlank {
                if (finalFormat.lowercase().contains("flac") || finalFormat.lowercase().contains("wav")) {
                    "Sans perte · ${if (source.quality == "high") "haute" else "économique"}"
                } else if (source.quality == "high") "Haute qualité · source personnalisée"
                else "Qualité économique · source personnalisée"
            }
            return info.copy(format = finalFormat, quality = finalQuality, mimeType = mimeType, codec = codec)
    }

    internal suspend fun fetchStreamEndpoint(url: String): String? {
        return try {
            if (!url.startsWith("https://")) return null
            CancellableConnection.use({ openStreamConnection(URL(url)) }) { connection ->
            connection.apply {
                connectTimeout = 12_000; readTimeout = 16_000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Range", "bytes=0-1048575")
                setRequestProperty("Accept-Encoding", "identity")
            }
            if (connection.responseCode !in 200..299 || connection.url.protocol != "https") return@use null
            connection.inputStream.use { input ->
                val head = ByteArray(16)
                var size = 0
                while (size < head.size) {
                    val count = input.read(head, size, head.size - size)
                    if (count < 0) break
                    size += count
                }
                if (DownloadValidation.audio(head.copyOf(size))) return@use connection.url.toString()
                val output = java.io.ByteArrayOutputStream()
                output.write(head, 0, size)
                val chunk = ByteArray(8192)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (output.size() + count > 1_048_576) return@use null
                    output.write(chunk, 0, count)
                }
                output.toString("UTF-8")
            }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null }
    }

    internal fun parseTracks(source: CustomSource, raw: String): List<Track> {
        val root = runCatching { org.json.JSONTokener(raw).nextValue() }.getOrNull() ?: return emptyList()
        val array = when (root) {
            is JSONArray -> root
            is JSONObject -> firstArray(root,
                "tracks", "results", "data", "songs", "items", "streams") ?: return emptyList()
            else -> return emptyList()
        }
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val nested = o.optJSONObject("track") ?: o.optJSONObject("song") ?: o
            val remoteId = firstString(nested, "id", "_id", "videoId", "trackId", "deezerId", "isrc")
                ?: return@mapNotNull null
            val title = firstString(nested, "title", "name", "track", "song") ?: return@mapNotNull null
            val artist = firstString(nested, "artist", "artist_name", "artistName", "artists", "author")
                ?: firstString(nested.optJSONObject("artist"), "name").orEmpty()
            val albumObj = nested.optJSONObject("album")
            val album = firstString(nested, "album", "album_name", "albumName")
                ?: firstString(albumObj, "title", "name").orEmpty()
            val cover = firstString(nested, "cover", "coverUrl", "artwork", "artworkURL", "image", "thumbnail", "picture")
                ?: firstString(nested.optJSONObject("album"), "cover", "cover_xl", "cover_big", "image")
            val isrc = firstString(nested, "isrc")
            val duration = nested.optDouble("durationMs", 0.0).takeIf { it > 0 }
                ?: (nested.optDouble("duration", 0.0) * 1000.0).takeIf { it > 0 }
                ?: (nested.optDouble("duration_ms", 0.0))
                ?: 0.0
            val direct = firstString(
                nested, "streamURL", "streamUrl", "stream_url", "stream", "url", "audio", "download"
            )?.let { absoluteLink(it, source.baseUrl) }
            val directDownload = firstString(nested, "downloadUrl", "download_url", "download", "file")
                ?.let { absoluteLink(it, source.baseUrl) }
            Track(
                id = "cs:${source.id}:$remoteId",
                title = title,
                artist = artist,
                album = album,
                durationMs = duration.toLong(),
                coverUrl = cover?.let { absoluteLink(it, source.baseUrl) },
                streamUrl = direct,
                downloadUrl = directDownload,
                addon = "cs:${source.id}",
                addonId = remoteId,
                isrc = isrc,
                lyricsProvider = "LRCLIB"
            )
        }.distinctBy { it.id }
    }

    private fun streamInfo(url: String, download: String?, qualitySetting: String): MusicApi.StreamInfo {
        val lower = (download ?: url).lowercase()
        val format = when {
            url.substringBefore('?').endsWith(".mpd", true) -> "dash"
            lower.contains(".flac") || lower.contains("flac") -> "flac"
            lower.contains(".wav") -> "wav"
            lower.contains(".m4a") || lower.contains("aac") -> "aac"
            lower.contains(".opus") -> "opus"
            else -> "mp3"
        }
        val quality = when (format) {
            "flac", "wav" -> "Sans perte · ${if (qualitySetting == "high") "haute" else "économique"}"
            else -> if (qualitySetting == "high") "Haute qualité · source personnalisée" else "Qualité économique · source personnalisée"
        }
        return MusicApi.StreamInfo(url = url, format = format, quality = quality,
            cacheTag = "cs:$format:${url.hashCode()}", downloadUrl = download)
    }

    private fun fill(template: String, query: String, id: String, quality: String,
                     title: String = "", artist: String = ""): String {
        var out = template
            .replace("{query}", enc(query))
            .replace("{q}", enc(query))
            .replace(":query", enc(query))
            .replace("{id}", enc(id))
            .replace(":id", enc(id))
            .replace("{quality}", enc(quality.ifBlank { "high" }))
            .replace("{title}", enc(title))
            .replace("{artist}", enc(artist))
        if (query.isNotBlank() && !template.contains("{query}") && !template.contains("{q}") && !template.contains(":query")) {
            out += (if ('?' in out) "&" else "?") + "q=" + enc(query)
        }
        if (id.isNotBlank() && !template.contains("{id}") && !template.contains(":id")) {
            out += (if (out.endsWith("/")) "" else "/") + enc(id)
        }
        return out
    }

    private fun absoluteLink(value: String, base: String): String? {
        val v = value.trim()
        return when {
            v.startsWith("https://") -> v
            v.startsWith("//") -> "https:$v"
            v.startsWith("/") -> base.trimEnd('/') + v
            else -> null
        }
    }

    private fun firstArray(o: JSONObject, vararg keys: String): JSONArray? {
        keys.forEach { key ->
            o.optJSONArray(key)?.let { return it }
            o.optJSONObject(key)?.let { nested ->
                firstArray(nested, "tracks", "results", "data", "songs", "items")?.let { return it }
            }
        }
        return null
    }

    private fun firstString(o: JSONObject?, vararg keys: String): String? {
        if (o == null) return null
        keys.forEach { key ->
            val value = o.opt(key)
            when (value) {
                is String -> if (value.isNotBlank()) return value
                is Number -> return value.toString()
                is JSONArray -> if (value.length() > 0) {
                    val first = value.opt(0)
                    if (first is String && first.isNotBlank()) return first
                    if (first is JSONObject) firstString(first, "name", "title")?.let { return it }
                }
                is JSONObject -> firstString(value, "name", "title")?.let { return it }
            }
        }
        return null
    }

    private fun List<Track>.bestCatalogMatch(want: Track): Track? = catalogMatches(want).firstOrNull()

    internal suspend fun resolveCatalogMatches(want: Track, tracks: List<Track>, tried: MutableSet<String>,
                                               resolve: suspend (Track) -> MusicApi.StreamInfo?): MusicApi.StreamInfo? {
        val ranked = tracks.catalogMatches(want)
        val providersFirst = ranked.distinctBy { (it.addonId ?: it.id).substringBefore(':') }
        for (candidate in providersFirst) {
            if (tried.size >= 4) break
            if (!tried.add(candidate.addonId ?: candidate.id)) continue
            resolve(candidate)?.let { return it }
        }
        return null
    }

    private fun List<Track>.catalogMatches(want: Track): List<Track> {
        fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val wt = norm(want.title)
        val wa = norm(want.artist.substringBefore(','))
        return mapNotNull { candidate ->
            val ct = norm(candidate.title)
            val ca = norm(candidate.artist)
            if (ct.isBlank() || wt.isBlank() || !(ct == wt || ct.contains(wt) || wt.contains(ct))) return@mapNotNull null

            if (wa.isNotBlank() && !(ca.contains(wa) || wa.contains(ca))) return@mapNotNull null
            var score = if (ct == wt) 5 else 2
            if (wa.isNotBlank() && (ca.contains(wa) || wa.contains(ca))) score += 3
            candidate to score
        }.filter { it.second >= 5 }.sortedByDescending { it.second }.map { it.first }
    }

    private fun persist() {
        val p = prefs ?: return
        val arr = JSONArray()
        _sources.value.forEach { arr.put(toJson(it)) }
        p.edit().putString("items", arr.toString()).apply()
    }

    private fun toJson(s: CustomSource): JSONObject = JSONObject()
        .put("id", s.id).put("manifestUrl", s.manifestUrl).put("name", s.name)
        .put("baseUrl", s.baseUrl).put("enabled", s.enabled).put("quality", s.quality)
        .put("manifestJson", s.manifestJson).put("checkedAt", s.checkedAt)
        .put("error", s.error ?: "")

    private fun fromJson(o: JSONObject): CustomSource = CustomSource(
        id = o.getString("id"), manifestUrl = o.getString("manifestUrl"),
        name = o.optString("name", "Source"), baseUrl = o.optString("baseUrl"),
        enabled = o.optBoolean("enabled", true), quality = o.optString("quality", "high"),
        manifestJson = o.optString("manifestJson"), checkedAt = o.optLong("checkedAt"),
        error = o.optString("error").takeIf { it.isNotBlank() }
    )

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private suspend fun fetchText(url: String, timeout: Int = 10_000): String? {
        return try {
            CancellableConnection.use({ URL(url).openConnection() as HttpURLConnection }) { c ->
            c.apply {
                connectTimeout = timeout
                readTimeout = timeout + 4_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json, text/plain, */*")
            }
            if (c.responseCode !in 200..299) null
            else c.inputStream.bufferedReader().use { it.readText() }.takeIf { it.length < 2_000_000 }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) {
            null
        }
    }

    fun isValidManifestUrl(url: String): Boolean =
        url.startsWith("https://") && url.substringBefore('?').endsWith(".json", true)
}

fun parseManifest(manifestUrl: String, raw: String): SourceManifest? {
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    val endpoints = root.optJSONObject("endpoints") ?: root.optJSONObject("api")?.takeIf {
        it.has("search") || it.has("stream")
    }
    fun endpoint(vararg names: String): String? {
        for (name in names) {
            val direct = root.opt(name)
            when (direct) {
                is String -> if (direct.isNotBlank() && direct != "null") return direct
                is JSONObject -> (direct.optString("url").takeIf { it.isNotBlank() }
                    ?: direct.optString("path").takeIf { it.isNotBlank() }
                    ?: direct.optString("endpoint").takeIf { it.isNotBlank() })?.let { return it }
            }
            val nested = endpoints?.opt(name)
            when (nested) {
                is String -> if (nested.isNotBlank()) return nested
                is JSONObject -> (nested.optString("url").takeIf { it.isNotBlank() }
                    ?: nested.optString("path").takeIf { it.isNotBlank() }
                    ?: nested.optString("endpoint").takeIf { it.isNotBlank() })?.let { return it }
            }
        }
        return null
    }
    fun baseValue(vararg names: String): String? {
        for (name in names) {
            root.opt(name)?.let { value ->
                if (value is String && value.startsWith("https://")) return value.trimEnd('/')
                if (value is JSONObject) {
                    val u = value.optString("url").takeIf { it.startsWith("https://") }
                        ?: value.optString("baseUrl").takeIf { it.startsWith("https://") }
                    if (u != null) return u.trimEnd('/')
                }
            }
        }
        return null
    }
    val base = baseValue("baseUrl", "base_url", "api", "server", "endpoint", "url", "website")
        ?: manifestUrl.substringBeforeLast('/')
    val search = endpoint("search", "searchUrl", "search_url", "find", "query")
        ?: "$base/search?q={query}&quality={quality}"
    if (!search.startsWith("https://") && !search.startsWith("/")) return null
    val name = listOf("name", "title", "sourceName", "source_name")
        .firstNotNullOfOrNull { key -> root.optString(key).takeIf { it.isNotBlank() } }
        ?: runCatching { URL(manifestUrl).host }.getOrDefault("Source personnalisée")
    fun optionText(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.isNotBlank() }
        is JSONObject -> listOf("value", "name", "label", "id")
            .firstNotNullOfOrNull { key -> value.optString(key).takeIf { it.isNotBlank() } }
        else -> null
    }
    fun optionTexts(array: JSONArray?): List<String> = array?.let {
        (0 until it.length()).mapNotNull { index -> optionText(it.opt(index)) }
    }.orEmpty()
    val qualityValues = mutableListOf<String>()
    root.optJSONArray("settings")?.let { settings ->
        for (i in 0 until settings.length()) {
            val setting = settings.optJSONObject(i) ?: continue
            val key = setting.optString("key").ifBlank { setting.optString("id") }
            if (!key.equals("quality", ignoreCase = true)) continue
            qualityValues += optionTexts(setting.optJSONArray("options"))
        }
    }
    qualityValues += optionTexts(root.optJSONArray("qualities"))
    val qualities = qualityValues.distinct()
    val downloadsAllowed = when (val allow = root.opt("allowDownloads")) {
        is Boolean -> allow
        is Number -> allow.toInt() != 0
        is String -> allow.lowercase() !in setOf("0", "false", "no", "off")
        else -> true
    }
    val explicitDownloadPermission = when (val allow = root.opt("allowDownloads")) {
        is Boolean -> allow
        is Number -> allow.toDouble() == 1.0
        is String -> allow.lowercase() in setOf("1", "true", "yes", "on")
        else -> false
    }
    return SourceManifest(
        name = name,
        baseUrl = base,
        searchUrl = search,
        streamUrl = endpoint("stream", "streamUrl", "stream_url", "play", "audio", "song"),
        downloadUrl = endpoint("download", "downloadUrl", "download_url", "file"),
        qualities = qualities,
        downloadsAllowed = downloadsAllowed,
        explicitDownloadPermission = explicitDownloadPermission
    )
}

internal fun negotiateQuality(tier: String, options: List<String>): String {
    if (tier == "lossless") options.firstOrNull { it.equals("lossless", ignoreCase = true) }?.let { return it }
    val requested = if (tier == "low") "low" else "high"
    if (options.isEmpty()) return requested
    options.firstOrNull { it.equals(requested, ignoreCase = true) }?.let { return it }
    val groups = if (requested == "low") {
        listOf(
            listOf("low", "96", "128", "min", "eco"),
            listOf("normal", "standard"),
            listOf("lossless", "flac", "hifi", "high", "max", "best")
        )
    } else {
        listOf(
            listOf("high", "320", "lossless", "flac", "hifi", "max", "best"),
            listOf("normal", "standard")
        )
    }
    groups.forEach { words ->
        options.firstOrNull { option -> words.any { it in option.lowercase() } }?.let { return it }
    }
    return options.first()
}
