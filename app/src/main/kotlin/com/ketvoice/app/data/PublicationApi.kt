package com.ketvoice.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

object PublicationApi {
    private const val ROOT = "https://xodklrunqlzyeiqtovgx.supabase.co"
    private const val BASE = "$ROOT/functions/v1/ketvoice-content"
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun connection(url: String, method: String, admin: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 12_000; readTimeout = 90_000
            instanceFollowRedirects = false
            setRequestProperty("apikey", KetvoiceApi.ANON_KEY)
            setRequestProperty("Authorization", "Bearer ${KetvoiceApi.ANON_KEY}")
            setRequestProperty("Accept", "application/json")
            admin?.let { setRequestProperty("x-ketvoice-admin", it) }
        }

    /** The server did not recognise the owner's session (expired token): renewed and sent once more. */
    internal class Unauthorized(message: String) : IllegalStateException(message)

    private fun response(c: HttpURLConnection): JSONObject {
        val status = c.responseCode
        val stream = if (status in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        val message = json.optString("error_description").ifBlank {
            json.optString("msg").ifBlank { json.optString("error").ifBlank { "Service de publication indisponible" } }
        }.take(250)
        if (status == 401) throw Unauthorized(message)
        if (status !in 200..299) error(message)
        return json
    }

    internal fun auth(path: String, body: JSONObject, token: String? = null): JSONObject {
        val c = connection("$ROOT/auth/v1$path", "POST")
        return try {
            token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            response(c)
        } finally { c.disconnect() }
    }

    internal fun adminSession(token: String): Boolean {
        val c = connection("$BASE/admin/session", "GET", token)
        return try { response(c).optBoolean("allowed") } finally { c.disconnect() }
    }

    private fun get(path: String): JSONObject? {
        val c = connection(BASE + path, "GET")
        c.readTimeout = 12_000
        return try { response(c) } catch (_: Exception) { null } finally { c.disconnect() }
    }

    suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        val rows = get("/search?q=${enc(query)}")?.optJSONArray("tracks") ?: JSONArray()
        (0 until rows.length()).mapNotNull { i -> rows.optJSONObject(i)?.let(::track) }
    }

    private fun track(item: JSONObject): Track? {
        val id = item.optString("id")
        val stream = item.optString("streamURL")
        if (!id.startsWith("kv:upload:") || !stream.startsWith("$ROOT/storage/v1/object/public/ketvoice-public-media/")) return null
        return Track(id, item.optString("title"), item.optString("artist"), item.optString("album"),
            durationMs = item.optLong("durationMs"), streamUrl = stream, downloadUrl = stream,
            coverUrl = item.optString("artworkURL").takeIf { it.startsWith("https://") },
            canvasUrl = item.optString("canvasURL").takeIf { it.startsWith("https://") },
            addon = "upload", addonId = item.optString("addonId"), lyricsProvider = "Ketvoice API").also(ArtworkRepo::acceptPublication)
    }

    suspend fun stream(wanted: Track, cachedFallback: MusicApi.StreamInfo? = null): MusicApi.StreamInfo? = withContext(Dispatchers.IO) {
        val fresh = get("/track?id=${enc(wanted.addonId ?: wanted.id.removePrefix("kv:upload:"))}")?.optJSONObject("track")?.let(::track)
        if (fresh == null && cachedFallback != null) return@withContext cachedFallback
        val song = fresh ?: wanted.takeIf { it.streamUrl?.startsWith("$ROOT/storage/v1/object/public/ketvoice-public-media/") == true }
        val url = song?.streamUrl ?: return@withContext null
        if (fresh != null) {
            ListeningHistory.replacePublication(fresh)
            com.ketvoice.app.player.Player.refreshPublication(fresh)
        }
        MusicApi.StreamInfo(url, url.substringBefore('?').substringAfterLast('.'), "Ketvoice API", ImportedSong.cacheTag(url), url)
    }

    suspend fun lyrics(track: Track): List<MusicApi.LyricLine> = withContext(Dispatchers.IO) {
        val query = "/lyrics?trackId=${enc(track.id)}&title=${enc(track.title)}&artist=${enc(track.artist)}" +
            "&durationMs=${track.durationMs}" + (track.isrc?.let { "&isrc=${enc(it)}" } ?: "")
        val versions = get(query)?.optJSONArray("versions") ?: return@withContext emptyList()
        (0 until versions.length()).mapNotNull { index ->
            val version = versions.optJSONObject(index) ?: return@mapNotNull null
            val content = version.optString("content")
            if (content.length > 1_048_576) return@mapNotNull null
            when (version.optString("format")) {
                "ttml" -> LyricsFormats.ttml(content)
                "lrc" -> MusicApi.parseLrc(content)
                else -> emptyList()
            }.takeIf { it.isNotEmpty() }
        }.maxByOrNull { ExtraLyricsProviders.rank(it) * 10000 + it.size.coerceAtMost(9999) }.orEmpty()
    }

    suspend fun publishLyrics(track: Track, content: ImportedLyrics) = withContext(Dispatchers.IO) {
        val token = PublisherSession.token()
        val body = metadata(track).put("content", content.content).put("format", content.format).put("rightsConfirmed", true)
        val c = connection("$BASE/admin/lyrics", "POST", token)
        try {
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            require(response(c).optBoolean("published")) { "Publication impossible" }
        } finally { c.disconnect() }
        if (com.ketvoice.app.player.Player.state.value.current?.id == track.id) LyricsRepo.load(track, force = true, refresh = true)
    }

    private fun metadata(track: Track) = JSONObject().put("trackId", track.id).put("title", track.title)
        .put("artist", track.artist).put("album", track.album).put("durationMs", track.durationMs).put("isrc", track.isrc ?: "")

    data class EditableSong(val track: Track, val revision: String)

    suspend fun editableSong(wanted: Track): EditableSong = withContext(Dispatchers.IO) {
        val id = ImportedSong.id(wanted) ?: error("Seuls les titres importés peuvent être modifiés")
        val c = connection("$BASE/admin/song?id=${enc(id)}", "GET", PublisherSession.token())
        try {
            val result = response(c)
            EditableSong(track(result.getJSONObject("track")) ?: error("Titre importé invalide"), result.getString("revision"))
        } finally { c.disconnect() }
    }

    /** What can change on a published title; null keeps the current value. */
    data class SongInfo(val title: String? = null, val artist: String? = null, val album: String? = null)

    suspend fun updateSong(wanted: EditableSong, audio: File?, cover: File?, durationMs: Long,
                           onProgress: (Int) -> Unit, canvas: File? = null, info: SongInfo = SongInfo()): Track {
        require(ImportedSong.id(wanted.track) != null)
        require(audio != null || cover != null || canvas != null || info != SongInfo())
        val body = JSONObject().put("trackId", wanted.track.id).put("revision", wanted.revision)
            .put("rightsConfirmed", true).put("durationMs", durationMs)
        info.title?.let { body.put("title", it) }
        info.artist?.let { body.put("artist", it) }
        info.album?.let { body.put("album", it) }
        val updated = upload("/admin/song/update", body, audio, cover, null, onProgress, canvas)
        refreshed(listOf(updated))
        return updated
    }

    suspend fun publishSong(track: Track, audio: File, cover: File?, lyrics: File?, onProgress: (Int) -> Unit,
                            canvas: File? = null): Track =
        upload("/admin/song", metadata(track).put("rightsConfirmed", true), audio, cover, lyrics, onProgress, canvas)

    /** Every title this account published, newest first, each with the revision its edits need. */
    suspend fun songs(): List<EditableSong> = withContext(Dispatchers.IO) {
        val c = connection("$BASE/admin/songs", "GET", PublisherSession.token())
        try {
            val rows = response(c).optJSONArray("songs") ?: JSONArray()
            (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val song = row.optJSONObject("track")?.let(::track) ?: return@mapNotNull null
                EditableSong(song, row.optString("revision"))
            }
        } finally { c.disconnect() }
    }

    /**
     * One cover and/or one Canvas for several of this account's titles at once (one title,
     * a selection or a whole album), or the Canvas removed from them.
     */
    suspend fun applyMedia(trackIds: List<String>, cover: File?, canvas: File?, removeCanvas: Boolean = false,
                           onProgress: (Int) -> Unit = {}, canvasLink: String? = null): List<Track> {
        require(trackIds.isNotEmpty() && trackIds.all { ImportedSong.id(Track(it, "", "", "")) != null })
        require(cover != null || canvas != null || removeCanvas || canvasLink != null)
        val meta = JSONObject().put("trackIds", JSONArray(trackIds.distinct())).put("rightsConfirmed", true)
        if (removeCanvas) meta.put("removeCanvas", true)
        // A link is kept as an address and streamed from where it is.
        canvasLink?.let { meta.put("links", JSONObject().put("canvas", it)) }
        val result = multipart("/admin/media", meta, listOfNotNull(cover?.let { "cover" to it }, canvas?.let { "canvas" to it }), onProgress)
        val rows = result.optJSONArray("tracks") ?: JSONArray()
        val tracks = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(::track) }
        refreshed(tracks)
        return tracks
    }

    /** Every owner-curated catalogue visual, as the raw JSON the app caches. */
    suspend fun overrides(): String? = withContext(Dispatchers.IO) {
        val c = connection("$BASE/overrides", "GET")
        c.readTimeout = 15_000
        c.useCaches = false
        c.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (c.responseCode !in 200..299) null
            else c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.takeIf { it.length < 4_000_000 }
        } catch (_: Exception) { null } finally { c.disconnect() }
    }

    /**
     * A change to what the music player alone shows for a catalogue title or album. A key that
     * is not named is left as it is; [followFrame] and [followCanvas] give one back to the album page.
     */
    class PlayerChange(val frame: Frame? = null, val followFrame: Boolean = false,
                       val canvas: Boolean? = null, val followCanvas: Boolean = false) {
        init { require(frame != null || followFrame || canvas != null || followCanvas) }
        fun json(): JSONObject = JSONObject().apply {
            if (frame != null) put("frame", MediaOverrides.frameJson(frame)) else if (followFrame) put("frame", JSONObject.NULL)
            if (canvas != null) put("canvas", if (canvas) "on" else "off") else if (followCanvas) put("canvas", JSONObject.NULL)
        }
    }

    /**
     * Replaces the cover, Canvas or artist photo of a catalogue item ([MediaOverrides]),
     * or restores the original ones listed in [restore]. Returns what is now applied,
     * null once nothing is left (the catalogue's own visuals are back).
     */
    suspend fun saveOverride(kind: String, key: String, label: String, cover: File? = null, canvas: File? = null,
                             hero: File? = null, restore: List<String> = emptyList(),
                             onProgress: (Int) -> Unit = {}, frame: Frame? = null, canvasOff: Boolean? = null,
                             player: PlayerChange? = null, links: Map<String, String> = emptyMap()): MediaOverride? {
        require(cover != null || canvas != null || hero != null || restore.isNotEmpty() || frame != null || canvasOff != null ||
            player != null || links.isNotEmpty())
        val meta = JSONObject().put("kind", kind).put("key", key).put("label", label.take(300)).put("rightsConfirmed", true)
        frame?.let { meta.put("frame", MediaOverrides.frameJson(it)) }
        canvasOff?.let { meta.put("canvasOff", it) }
        player?.let { meta.put("player", it.json()) }
        if (links.isNotEmpty()) meta.put("links", JSONObject(links))
        if (restore.isNotEmpty()) meta.put("remove", JSONArray(restore.distinct()))
        val result = multipart("/admin/override", meta,
            listOfNotNull(cover?.let { "cover" to it }, canvas?.let { "canvas" to it }, hero?.let { "hero" to it }), onProgress)
        val saved = result.optJSONObject("override")?.let(MediaOverrides::decodeRow)
        MediaOverrides.accept(kind, key, saved)
        return saved
    }

    /**
     * The same change for a selection of catalogue titles or albums at once (up to 80 per request):
     * one Canvas for all of them, their Canvas switched off, or their original restored. Returns the
     * overrides that now exist for them; an item missing from the answer has none left.
     */
    suspend fun saveOverrides(kind: String, items: List<Pair<String, String>>, canvas: File? = null, canvasOff: Boolean? = null,
                              restore: List<String> = emptyList(), onProgress: (Int) -> Unit = {},
                              player: PlayerChange? = null, links: Map<String, String> = emptyMap()): List<MediaOverride> {
        require(items.isNotEmpty() && (canvas != null || canvasOff != null || restore.isNotEmpty() || player != null || links.isNotEmpty()))
        val saved = ArrayList<MediaOverride>()
        val chunks = items.distinctBy { it.first }.chunked(80)
        chunks.forEachIndexed { index, chunk ->
            val meta = JSONObject().put("kind", kind).put("rightsConfirmed", true).put("items", JSONArray().apply {
                chunk.forEach { put(JSONObject().put("key", it.first).put("label", it.second.take(300))) }
            })
            if (restore.isNotEmpty()) meta.put("remove", JSONArray(restore.distinct()))
            canvasOff?.let { meta.put("canvasOff", it) }
            player?.let { meta.put("player", it.json()) }
            if (links.isNotEmpty()) meta.put("links", JSONObject(links))
            val result = multipart("/admin/override", meta, listOfNotNull(canvas?.let { "canvas" to it }),
                { onProgress((index * 100 + it) / chunks.size) })
            val rows = result.optJSONArray("overrides") ?: JSONArray()
            val decoded = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(MediaOverrides::decodeRow) }
            MediaOverrides.acceptMany(kind, chunk.map { it.first }, decoded)
            saved += decoded
        }
        return saved
    }

    /** The rest of the app sees a changed publication at once: history, queue and player. */
    private fun refreshed(tracks: List<Track>) = tracks.forEach { updated ->
        ListeningHistory.replacePublication(updated)
        com.ketvoice.app.player.Player.refreshPublication(updated)
    }

    private suspend fun upload(path: String, meta: JSONObject, audio: File?, cover: File?, lyrics: File?, onProgress: (Int) -> Unit,
                               canvas: File? = null): Track {
        val files = listOfNotNull(audio?.let { "audio" to it }, cover?.let { "cover" to it }, lyrics?.let { "lyrics" to it },
            canvas?.let { "canvas" to it })
        val result = multipart(path, meta, files, onProgress)
        require(result.optBoolean("published")) { "Publication impossible" }
        return track(result.getJSONObject("track")) ?: error("Réponse de publication invalide")
    }

    private suspend fun multipart(path: String, meta: JSONObject, files: List<Pair<String, File>>, onProgress: (Int) -> Unit): JSONObject =
        try { multipartOnce(path, meta, files, onProgress) }
        catch (refused: Unauthorized) {
            // An access token can be refused before its announced expiry: renew it and send once more.
            PublisherSession.expire()
            multipartOnce(path, meta, files, onProgress)
        }

    private suspend fun multipartOnce(path: String, meta: JSONObject, files: List<Pair<String, File>>, onProgress: (Int) -> Unit): JSONObject = withContext(Dispatchers.IO) {
        val token = PublisherSession.token()
        val boundary = "Ketvoice${UUID.randomUUID()}"
        val c = connection("$BASE$path", "POST", token)
        val total = files.sumOf { it.second.length() }.coerceAtLeast(1)
        var sent = 0L
        try {
            c.doOutput = true; c.setChunkedStreamingMode(64 * 1024)
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.outputStream.use { out ->
                fun write(value: String) = out.write(value.toByteArray(Charsets.UTF_8))
                write("--$boundary\r\nContent-Disposition: form-data; name=\"metadata\"\r\n\r\n$meta\r\n")
                files.forEach { (name, file) ->
                    val filename = "$name.${file.extension.lowercase()}"
                    write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer); if (read < 0) break
                            out.write(buffer, 0, read); sent += read
                            onProgress((sent * 98 / total).toInt().coerceIn(0, 98))
                        }
                    }
                    write("\r\n")
                }
                write("--$boundary--\r\n")
            }
            val result = response(c)
            onProgress(100)
            result
        } finally { c.disconnect() }
    }
}
