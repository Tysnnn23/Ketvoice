package com.ketvoice.app.data

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Téléchargements réels : enregistre le flux choisi (FLAC Last Babe ou
 * MP3 Ultra MAX selon le réglage) dans filesDir/downloads, rejouable HORS LIGNE.
 * Progression exposée (0-100), toast début/fin, repli sur l'autre qualité
 * si le téléchargement échoue, métadonnées JSON à côté du fichier.
 *
 * Chaque téléchargement emporte aussi ses meilleures paroles (JSON + LRC) et
 * le Canvas Spotify lorsqu'il existe. Le Canvas reste dans le cache partagé
 * `filesDir/canvas` afin d'être réutilisé par les autres titres du même album.
 */
object Downloader {

    data class Item(
        val track: Track,
        val file: File,
        val format: String,
        val bytes: Long,
        val lyricsFile: File? = null,
        val canvasFile: File? = null,
        val hasLyrics: Boolean = false,
        val hasCanvas: Boolean = false
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy
    private val _activeTracks = MutableStateFlow<Map<String, Track>>(emptyMap())
    val activeTracks: StateFlow<Map<String, Track>> = _activeTracks

    /** Progression par id de piste, en pourcentage (0-100). */
    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val progress: StateFlow<Map<String, Int>> = _progress

    private var ctx: Context? = null

    fun init(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        refresh()
    }

    fun itemFor(trackId: String): Item? = _items.value.firstOrNull { it.track.id == trackId }

    fun download(track: Track) {
        val c = ctx ?: return
        if (itemFor(track.id) != null) {
            toast("Déjà téléchargé")
            return
        }
        if (track.id in _busy.value) {
            toast("Téléchargement déjà en cours…")
            return
        }
        _busy.update { it + track.id }
        _activeTracks.update { it + (track.id to track) }
        _progress.update { it + (track.id to 0) }
        toast("Téléchargement de « ${track.title} »…")
        scope.launch {
            try {
                val preferFlac = AppSettings.downloadQualityMode.value == 0
                val publication = track.addon == "upload" || track.id.startsWith("kv:upload:")
                val streams = withTimeoutOrNull(75_000) { MusicApi.resolve(track) }
                val candidates = linkedSetOf<MusicApi.StreamInfo>()
                if (preferFlac) {
                    streams?.best?.let { candidates += it }
                    streams?.fast?.let { candidates += it }
                } else {
                    streams?.fast?.let { candidates += it }
                    streams?.best?.let { candidates += it }
                }
                // Repli direct : la résolution parallèle peut avoir été coupée
                // par un addon lent, mais l'autre qualité reste accessible.
                if (candidates.isEmpty() && !publication) {
                    runCatching { MusicApi.addonStream(track, "um") }.getOrNull()?.let { candidates += it }
                    runCatching { MusicApi.flacStream(track) }.getOrNull()?.let { candidates += it }
                }

                var gotFile: File? = null
                var gotStream: MusicApi.StreamInfo? = null
                for (candidate in candidates) {
                    val dest = fileFor(c, track.id, extOf(candidate))
                    val urls = listOfNotNull(candidate.downloadUrl, candidate.url)
                        .distinct().filter { it.startsWith("https://") }
                    if (urls.any { fetch(it, dest, track.id) }) {
                        gotFile = dest
                        gotStream = candidate
                        break
                    }
                    dest.delete()
                }

                // Dernier recours : le flux YouTube Music, sans secret côté APK.
                if (gotFile == null && !publication) {
                    val yt = withTimeoutOrNull(35_000) { runCatching { MusicApi.youtubeStream(track) }.getOrNull() }
                    if (yt != null) {
                        val dest = fileFor(c, track.id, extOf(yt))
                        if (fetch(yt.url, dest, track.id)) {
                            gotFile = dest
                            gotStream = yt
                        } else {
                            dest.delete()
                        }
                    }
                }

                val file = gotFile
                if (file == null || gotStream == null) {
                    toast("Échec du téléchargement (aucun flux lisible)")
                    return@launch
                }

                _progress.value = _progress.value + (track.id to 99)
                val meta = JSONObject().apply {
                    put("id", track.id)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("album", track.album)
                    put("durationMs", track.durationMs)
                    put("albumId", track.albumId ?: "")
                    put("artistId", track.artistId ?: "")
                    put("spotifyId", track.spotifyId ?: "")
                    put("coverUrl", ArtworkRepo.coverForTrack(track) ?: track.coverUrl ?: "")
                    put("format", displayFormat(file, gotStream))
                    put("quality", gotStream.quality)
                    put("source", gotStream.quality)
                    put("file", file.name)
                    put("downloadedAt", System.currentTimeMillis())
                }

                // Commit audio first. An optional lyrics/Canvas provider must
                // never turn a successful audio download into a failed item.
                val metaFile = File(dir(c), file.nameWithoutExtension + ".json")
                metaFile.writeText(meta.toString(), Charsets.UTF_8)
                refresh()

                // Les paroles sont téléchargées AVANT l'écriture finale : le
                // fichier et ses sidecars apparaissent ensemble.
                val best = withTimeoutOrNull(30_000) { runCatching { MusicApi.bestLyrics(track) }.getOrNull() }
                if (best != null && best.second.isNotEmpty()) {
                    val lyricsFile = File(dir(c), file.nameWithoutExtension + ".lyrics.json")
                    runCatching { saveLyrics(lyricsFile, best.first, best.second, best.third) }
                    meta.put("lyricsFile", lyricsFile.name)
                    meta.put("lyricsSource", best.first)
                    meta.put("lyricsSourceKey", best.third)
                    meta.put("lyricsLines", best.second.size)
                    meta.put("lyricsTimed", best.second.any { it.timeMs >= 0 })
                    // Sidecar LRC lisible par les autres lecteurs.
                    runCatching {
                        File(dir(c), file.nameWithoutExtension + ".lrc").writeText(toLrc(best.second), Charsets.UTF_8)
                    }
                }

                // Le Canvas est indépendant de la qualité audio et reste dans
                // son cache permanent, réutilisable par les autres titres.
                withTimeoutOrNull(50_000) { runCatching { CanvasRepo.resolve(track, force = true) } }
                val canvas = CanvasRepo.localPath(track.id)
                if (canvas != null) {
                    meta.put("canvasFile", canvas.absolutePath)
                    meta.put("canvasSpotifyId", canvas.nameWithoutExtension)
                }

                metaFile.writeText(meta.toString(), Charsets.UTF_8)
                _progress.value = _progress.value + (track.id to 100)
                refresh()
                toast("« ${track.title} » téléchargé")
            } catch (_: Exception) {
                toast("Échec du téléchargement")
            } finally {
                _progress.update { it - track.id }
                _busy.update { it - track.id }
                _activeTracks.update { it - track.id }
            }
        }
    }

    fun delete(item: Item) {
        runCatching {
            item.file.delete()
            val stem = item.file.nameWithoutExtension
            File(item.file.parentFile, "$stem.json").delete()
            File(item.file.parentFile, "$stem.lyrics.json").delete()
            File(item.file.parentFile, "$stem.lrc").delete()
        }
        refresh()
    }

    fun totalBytes(): Long = _items.value.sumOf { it.bytes }

    /** Paroles hors ligne déjà téléchargées avec ce titre. */
    fun localLyrics(trackId: String): Pair<String, List<MusicApi.LyricLine>>? {
        val item = itemFor(trackId) ?: return null
        val sidecar = item.lyricsFile?.takeIf { it.exists() } ?: return null
        return runCatching {
            val root = JSONObject(sidecar.readText(Charsets.UTF_8))
            val source = root.optString("source", "Paroles")
            val arr = root.optJSONArray("lines") ?: JSONArray()
            val lines = (0 until arr.length()).mapNotNull { index ->
                val o = arr.optJSONObject(index) ?: return@mapNotNull null
                val wordsArr = o.optJSONArray("words") ?: JSONArray()
                val words = (0 until wordsArr.length()).mapNotNull { wi ->
                    val w = wordsArr.optJSONObject(wi) ?: return@mapNotNull null
                    MusicApi.LyricWord(
                        startMs = w.optLong("startMs"),
                        text = w.optString("text"),
                        endMs = w.optLong("endMs", -1L)
                    )
                }
                MusicApi.LyricLine(
                    timeMs = o.optLong("timeMs", -1L),
                    text = o.optString("text"),
                    words = words,
                    syllableTimed = o.optBoolean("syllableTimed", false),
                    translations = o.optJSONObject("translations")?.let { translations ->
                        translations.keys().asSequence().associateWith { translations.optString(it) }
                    }.orEmpty()
                )
            }
            if (lines.isEmpty()) null else source to lines
        }.getOrNull()
    }

    private fun saveLyrics(
        file: File,
        source: String,
        lines: List<MusicApi.LyricLine>,
        sourceKey: String
    ) {
        val root = JSONObject().apply {
            put("source", source)
            put("sourceKey", sourceKey)
            put("savedAt", System.currentTimeMillis())
            put("lines", JSONArray().apply {
                lines.forEach { line ->
                    put(JSONObject().apply {
                        put("timeMs", line.timeMs)
                        put("text", line.text)
                        put("syllableTimed", line.syllableTimed)
                        if (line.translations.isNotEmpty()) put("translations", JSONObject(line.translations))
                        if (line.words.isNotEmpty()) put("words", JSONArray().apply {
                            line.words.forEach { word ->
                                put(JSONObject().apply {
                                    put("startMs", word.startMs)
                                    put("text", word.text)
                                    put("endMs", word.endMs)
                                })
                            }
                        })
                    })
                }
            })
        }
        file.writeText(root.toString(), Charsets.UTF_8)
    }

    private fun toLrc(lines: List<MusicApi.LyricLine>): String = buildString {
        lines.forEach { line ->
            if (line.timeMs < 0) {
                appendLine(line.text)
                return@forEach
            }
            val minutes = line.timeMs / 60_000
            val seconds = (line.timeMs % 60_000) / 1000.0
            append(String.format(Locale.ROOT, "[%02d:%05.2f]", minutes, seconds))
            if (line.words.isNotEmpty()) {
                line.words.forEach { word ->
                    if (word.startMs >= 0) {
                        val wm = word.startMs / 60_000
                        val ws = (word.startMs % 60_000) / 1000.0
                        append(String.format(Locale.ROOT, "<%02d:%05.2f>", wm, ws))
                    }
                    append(word.text)
                }
            } else {
                append(line.text)
            }
            appendLine()
        }
    }

    private fun extOf(s: MusicApi.StreamInfo): String {
        val format = s.format.lowercase()
        val url = s.url.lowercase()
        return when {
            format.contains("flac") || url.contains(".flac") -> "flac"
            format.contains("m4a") || format.contains("mp4") || format.contains("aac") ||
                url.contains(".m4a") || url.contains(".mp4") -> "m4a"
            format.contains("ogg") || format.contains("opus") || url.contains(".opus") -> "opus"
            format.contains("wav") -> "wav"
            else -> "mp3"
        }
    }

    private fun displayFormat(file: File, stream: MusicApi.StreamInfo): String = when (file.extension) {
        "flac" -> "FLAC · Sans perte"
        "m4a" -> "AAC · ${stream.quality}"
        "opus" -> "Opus · ${stream.quality}"
        "wav" -> "WAV · Sans perte"
        else -> "MP3 · ${stream.quality}"
    }

    private fun fileFor(c: Context, id: String, extension: String): File =
        File(dir(c), key(id) + "." + extension)

    private fun refresh() {
        val c = ctx ?: return
        val dir = dir(c)
        val list = mutableListOf<Item>()
        dir.listFiles { f -> f.extension == "json" && !f.name.endsWith(".lyrics.json") }?.forEach { metaFile ->
            runCatching {
                val o = JSONObject(metaFile.readText(Charsets.UTF_8))
                val file = File(dir, o.optString("file"))
                if (!file.exists() || file.length() < 10_000) return@runCatching
                val cover = o.optString("coverUrl").takeIf { it.isNotEmpty() }
                val lyricsFile = o.optString("lyricsFile").takeIf { it.isNotEmpty() }?.let { File(dir, it) }
                val canvasName = o.optString("canvasFile").takeIf { it.isNotEmpty() }
                val canvasFile = canvasName?.let { File(it) }
                    ?: o.optString("canvasSpotifyId").takeIf { it.isNotEmpty() }
                        ?.let { File(c.filesDir, "canvas/$it.mp4") }
                val track = Track(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    artist = o.getString("artist"),
                    album = o.optString("album"),
                    durationMs = o.optLong("durationMs"),
                    albumId = o.optString("albumId").takeIf { it.isNotBlank() },
                    artistId = o.optString("artistId").takeIf { it.isNotBlank() },
                    spotifyId = o.optString("canvasSpotifyId", o.optString("spotifyId")).takeIf { it.isNotBlank() },
                    streamUrl = Uri.fromFile(file).toString(),
                    lyricsProvider = o.optString("lyricsSource", "LRCLIB"),
                    coverUrl = cover
                )
                list += Item(
                    track = track,
                    file = file,
                    format = o.optString("format", "MP3"),
                    bytes = file.length(),
                    lyricsFile = lyricsFile?.takeIf { it.exists() && it.length() > 0 },
                    canvasFile = canvasFile?.takeIf { it.exists() && it.length() > 10_000 },
                    hasLyrics = lyricsFile?.let { it.exists() && it.length() > 0 } == true,
                    hasCanvas = canvasFile?.let { it.exists() && it.length() > 10_000 } == true
                )
            }
        }
        _items.value = list.sortedByDescending { it.file.lastModified() }
    }

    private fun fetch(url: String, dest: File, id: String): Boolean {
        val tmp = File(dest.parentFile, dest.name + ".part")
        tmp.delete()
        var validator: String? = null
        repeat(3) {
          var connection: HttpURLConnection? = null
          try {
            var current = URL(url)
            val offset = tmp.takeIf { it.exists() }?.length() ?: 0L
            var responseCode = 0
            for (hop in 0..5) {
                if (current.protocol != "https") return false
                connection = (current.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 90_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
                    setRequestProperty("Accept", "audio/*,*/*;q=0.8")
                    setRequestProperty("Accept-Encoding", "identity")
                    if (offset > 0L) {
                        setRequestProperty("Range", "bytes=$offset-")
                        validator?.let { setRequestProperty("If-Range", it) }
                    }
                }
                responseCode = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (responseCode in listOf(301, 302, 303, 307, 308) && !location.isNullOrBlank()) {
                    connection.disconnect()
                    current = URL(current, location)
                    continue
                }
                break
            }
            val c = connection ?: return@repeat
            if (responseCode !in 200..299) {
                return@repeat
            }
            val range = c.getHeaderField("Content-Range")
                ?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it) }
            val resumed = responseCode == 206 && offset > 0L &&
                range?.groupValues?.get(1)?.toLongOrNull() == offset
            if (responseCode == 206 && !resumed) {
                tmp.delete()
                return@repeat
            }
            validator = c.getHeaderField("ETag") ?: c.getHeaderField("Last-Modified")
            val total = if (resumed) range!!.groupValues[3].toLong() else c.contentLengthLong
            val buf = ByteArray(128 * 1024)
            var written = if (resumed) offset else 0L
            var lastTick = 0L
            c.inputStream.use { input ->
                FileOutputStream(tmp, resumed).use { out ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        if (total > 0 && now - lastTick > 200) {
                            lastTick = now
                            val percent = (written * 98 / total).toInt().coerceIn(0, 98)
                            _progress.update { it + (id to percent) }
                        }
                    }
                }
            }
            if (!DownloadValidation.complete(tmp.length(), total)) return@repeat
            if (!looksLikeAudio(tmp)) {
                tmp.delete()
                return false
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            return dest.exists() && dest.length() > 10_000
          } catch (_: Exception) {
              // Keep a partial response only for the next Range attempt.
          } finally {
              connection?.disconnect()
          }
        }
        tmp.delete()
        return false
    }

    /** Évite d'enregistrer une page HTML/JSON d'erreur comme fichier audio. */
    private fun looksLikeAudio(file: File): Boolean {
        val head = ByteArray(16)
        val read = file.inputStream().use { it.read(head) }
        return read > 0 && DownloadValidation.audio(head.copyOf(read))
    }

    private fun dir(context: Context): File =
        File(context.filesDir, "downloads").apply { if (!exists()) mkdirs() }

    private fun key(id: String): String = MessageDigest.getInstance("SHA-1")
        .digest(id.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(24)

    private fun toast(message: String) {
        Handler(Looper.getMainLooper()).post {
            ctx?.let { Toast.makeText(it, AppText.t(message), Toast.LENGTH_SHORT).show() }
        }
    }
}
