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

/**
 * Sources personnalisées façon BitChord.
 *
 * L'utilisateur ajoute une URL HTTPS terminée par `.json`. Le manifeste peut
 * exposer `baseUrl` + endpoints, ou directement `search` / `stream`.
 * Rien n'est simulé : une source n'apparaît comme active qu'après un
 * téléchargement et un parsing réels de son manifeste.
 */
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
    /** Options déclarées par le manifeste ou ses `settings` BitChord. */
    val qualities: List<String>,
    /** `allowDownloads` du protocole d'addon ; absent = autorisé. */
    val downloadsAllowed: Boolean = true
) {
    val resolvedSearch: String get() = absolute(searchUrl)
    // Le protocole d'addon BitChord expose /stream/{id}?quality=… ; la qualité
    // choisie dans Ketvoice doit réellement partir avec la requête.
    val resolvedStream: String? get() = streamUrl?.let(::absolute)
        ?: baseUrl.takeIf { it.isNotBlank() }?.let { absolute("$it/stream/{id}?quality={quality}") }
    /** Endpoint de téléchargement explicitement déclaré, jamais inventé. */
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

    private val _sources = MutableStateFlow<List<CustomSource>>(emptyList())
    val sources: StateFlow<List<CustomSource>> = _sources
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val arr = runCatching { JSONArray(p.getString("items", "[]")) }.getOrDefault(JSONArray())
        _sources.value = (0 until arr.length()).mapNotNull { i ->
            runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
        }
    }

    fun enabledSources(): List<CustomSource> = _sources.value.filter { it.enabled }

    /**
     * Valide puis enregistre une source. Une URL qui ne répond pas, ne se
     * parse pas, ou qui n'expose aucun endpoint de recherche est refusée.
     */
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
        // Test réel : une source qui ne répond pas à une recherche ne doit pas
        // être enregistrée comme active.
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

    suspend fun search(source: CustomSource, query: String): List<Track> = withContext(Dispatchers.IO) {
        val manifest = parseManifest(source.manifestUrl, source.manifestJson) ?: return@withContext emptyList()
        val quality = negotiateQuality(source.quality, manifest.qualities)
        val url = fill(manifest.resolvedSearch, query = query, id = "", quality = quality)
        val raw = fetchText(url) ?: return@withContext emptyList()
        parseTracks(source, raw)
    }

    /**
     * Résout un flux réel. Si la piste vient d'une source, son identifiant est
     * utilisé directement ; sinon la source est interrogée avec titre/artiste.
     */
    suspend fun streamForTrack(track: Track, forDownload: Boolean = false): MusicApi.StreamInfo? =
        withContext(Dispatchers.IO) {
            val tagged = track.addon?.takeIf { it.startsWith("cs:") }
            val source = if (tagged != null) _sources.value.firstOrNull { it.enabled && "cs:${it.id}" == tagged } else null
            if (source != null) {
                // Un addon peut interdire le téléchargement de ses flux (allowDownloads: 0).
                val manifest = parseManifest(source.manifestUrl, source.manifestJson)
                if (forDownload && manifest?.downloadsAllowed == false) return@withContext null
                stream(source, track, forDownload)?.let { return@withContext it }
            }
            if (track.streamUrl?.startsWith("https://") == true) {
                return@withContext streamInfo(track.streamUrl, track.downloadUrl, source?.quality ?: "high")
            }
            // Une piste du catalogue peut aussi être servie par une source ajoutée.
            for (candidate in enabledSources()) {
                val found = search(candidate, "${track.title} ${track.artist.substringBefore(',')}")
                    .bestCatalogMatch(track) ?: continue
                stream(candidate, found, forDownload)?.let { return@withContext it }
            }
            null
        }

    private suspend fun stream(source: CustomSource, track: Track, forDownload: Boolean): MusicApi.StreamInfo? =
        withContext(Dispatchers.IO) {
            val manifest = parseManifest(source.manifestUrl, source.manifestJson) ?: return@withContext null
            if (forDownload && !manifest.downloadsAllowed) return@withContext null
            val id = track.addonId.orEmpty()
            val qualityParam = negotiateQuality(source.quality, manifest.qualities)
            // Un endpoint `download` explicite est prioritaire sur le flux de
            // lecture, mais seulement pour un vrai téléchargement.
            if (forDownload) {
                val downloadTemplate = manifest.downloadEndpoint
                if (downloadTemplate != null && (id.isNotBlank() || !downloadTemplate.contains("{id}"))) {
                    val target = fill(downloadTemplate, query = "", id = id, quality = qualityParam,
                        title = track.title, artist = track.artist)
                    if (target.startsWith("https://")) {
                        return@withContext streamInfo(target, target, source.quality)
                    }
                }
            }
            val direct = track.streamUrl?.takeIf { it.startsWith("https://") }
            if (direct != null) return@withContext streamInfo(direct, track.downloadUrl, source.quality)
            val template = manifest.resolvedStream ?: return@withContext null
            if (id.isBlank()) return@withContext null
            val url = fill(template, query = "", id = id, quality = qualityParam,
                title = track.title, artist = track.artist)
            val raw = fetchText(url, timeout = 12_000) ?: return@withContext null
            val trimmed = raw.trim()
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                return@withContext streamInfo(trimmed, null, source.quality)
            }
            val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return@withContext null
            val nested = obj.optJSONObject("data")
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
                obj.optString("url"), obj.optString("stream"), obj.optString("link"),
                obj.optString("audio"), obj.optString("download"),
                nested?.optString("url").orEmpty(), nested?.optString("stream").orEmpty(),
                nested?.optString("link").orEmpty(), fromArray.orEmpty()
            ).firstOrNull { it.startsWith("https://") || it.startsWith("/") }
                ?: return@withContext null
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
            val info = streamInfo(stream, download, source.quality)
            val finalFormat = format.ifBlank { info.format }
            val finalQuality = quality.ifBlank {
                if (finalFormat.lowercase().contains("flac") || finalFormat.lowercase().contains("wav")) {
                    "Sans perte · ${if (source.quality == "high") "haute" else "économique"}"
                } else if (source.quality == "high") "Haute qualité · source personnalisée"
                else "Qualité économique · source personnalisée"
            }
            info.copy(format = finalFormat, quality = finalQuality)
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

    /** Rend absolu un lien relatif renvoyé par une source, sans accepter HTTP. */
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

    private fun List<Track>.bestCatalogMatch(want: Track): Track? {
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
            // Un titre identique par un autre artiste n'est pas le bon morceau.
            if (wa.isNotBlank() && !(ca.contains(wa) || wa.contains(ca))) return@mapNotNull null
            var score = if (ct == wt) 5 else 2
            if (wa.isNotBlank() && (ca.contains(wa) || wa.contains(ca))) score += 3
            candidate to score
        }.maxByOrNull { it.second }?.takeIf { it.second >= 5 }?.first
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

    private fun fetchText(url: String, timeout: Int = 10_000): String? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeout
                readTimeout = timeout + 4_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json, text/plain, */*")
            }
            if (c.responseCode !in 200..299) null
            else c.inputStream.bufferedReader().use { it.readText() }.takeIf { it.length < 2_000_000 }
        } catch (_: Exception) {
            null
        } finally {
            c?.disconnect()
        }
    }

    fun isValidManifestUrl(url: String): Boolean =
        url.startsWith("https://") && url.substringBefore('?').endsWith(".json", true)
}

/**
 * Parsing séparé du manifeste pour rester testable sans réseau.
 * Formats acceptés : endpoints plats, objet `endpoints`, ou base + chemins.
 */
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
    return SourceManifest(
        name = name,
        baseUrl = base,
        searchUrl = search,
        streamUrl = endpoint("stream", "streamUrl", "stream_url", "play", "audio", "song"),
        downloadUrl = endpoint("download", "downloadUrl", "download_url", "file"),
        qualities = qualities,
        downloadsAllowed = downloadsAllowed
    )
}

/**
 * Le protocole BitChord déclare parfois `LOSSLESS|HIGH|LOW` et parfois
 * `high|normal|low`. On envoie une valeur réellement proposée par l'addon.
 */
internal fun negotiateQuality(tier: String, options: List<String>): String {
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
