package com.ketvoice.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Téléchargements réels : enregistre le flux choisi (FLAC Lossless Bridge ou
 * MP3 Ultra MAX selon le réglage) dans filesDir/downloads, rejouable HORS LIGNE.
 * Progression exposée (0-100), toast début/fin, repli sur l'autre qualité
 * si le téléchargement échoue, métadonnées JSON à côté du fichier.
 */
object Downloader {

    data class Item(
        val track: Track,
        val file: File,
        val format: String,
        val bytes: Long
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy

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
        _busy.value = _busy.value + track.id
        toast("Téléchargement de « ${track.title} »…")
        scope.launch {
            try {
                // 0 = FLAC sans perte (best), 1 = MP3 rapide (fast)
                val streams = MusicApi.resolve(track)
                val preferFlac = AppSettings.downloadQualityMode.value == 0
                val primary = if (preferFlac) streams.best ?: streams.fast else streams.fast ?: streams.best
                val fallback = if (primary === streams.best) streams.fast else streams.best
                if (primary == null && fallback == null) {
                    toast("Aucun flux à télécharger pour ce titre")
                    return@launch
                }

                var ok = false
                var gotStream: com.ketvoice.app.data.MusicApi.StreamInfo? = null
                var gotFile: File? = null
                if (primary != null) {
                    _progress.value = _progress.value + (track.id to 0)
                    val f1 = fileFor(c, track.id, extOf(primary))
                    if (fetch(primary.downloadUrl ?: primary.url, f1, track.id)) {
                        ok = true
                        gotStream = primary
                        gotFile = f1
                    } else {
                        f1.delete()
                    }
                }
                if (!ok && fallback != null && (fallback.downloadUrl ?: fallback.url) != (primary?.downloadUrl ?: primary?.url)) {
                    // 2e essai sur l'autre qualité (FLAC <-> MP3)
                    val f2 = fileFor(c, track.id, extOf(fallback))
                    if (fetch(fallback.downloadUrl ?: fallback.url, f2, track.id)) {
                        ok = true
                        gotStream = fallback
                        gotFile = f2
                    } else {
                        f2.delete()
                    }
                }
                if (!ok && preferFlac) {
                    val lastBabe = MusicApi.addonStream(track, "last")
                    if (lastBabe != null && lastBabe.url != primary?.url && lastBabe.url != fallback?.url) {
                        val dest = fileFor(c, track.id, extOf(lastBabe))
                        if (fetch(lastBabe.url, dest, track.id)) {
                            ok = true
                            gotStream = lastBabe
                            gotFile = dest
                        }
                    }
                }
                val file = gotFile ?: File(dir(c), key(track.id) + ".mp3")
                val chosen = gotStream ?: fallback ?: primary

                if (!ok) {
                    toast("Échec du téléchargement (réseau)")
                    return@launch
                }
                val meta = JSONObject().apply {
                    put("id", track.id)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("album", track.album)
                    put("durationMs", track.durationMs)
                    put("coverUrl", ArtworkRepo.coverForTrack(track) ?: track.coverUrl ?: "")
                    put("format", if (file.extension == "flac") "FLAC · Sans perte" else "MP3")
                    put("file", file.name)
                }
                File(dir(c), file.nameWithoutExtension + ".json").writeText(meta.toString())
                refresh()
                toast("« ${track.title} » téléchargé")
                // Offline companions use their own permanent stores, never the audio LRU.
                scope.launch { runCatching { CanvasRepo.resolve(track) } }
                MusicApi.lyricSources.forEach { source ->
                    scope.launch { runCatching { MusicApi.lyrics(track, source = source) } }
                }
            } catch (_: Exception) {
                toast("Échec du téléchargement")
            } finally {
                _progress.value = _progress.value - track.id
                _busy.value = _busy.value - track.id
            }
        }
    }

    fun delete(item: Item) {
        runCatching {
            item.file.delete()
            File(item.file.parentFile, item.file.nameWithoutExtension + ".json").delete()
        }
        refresh()
    }

    fun totalBytes(): Long = _items.value.sumOf { it.bytes }

    private fun extOf(s: com.ketvoice.app.data.MusicApi.StreamInfo?): String =
        if (s?.format?.contains("flac", true) == true || s?.url?.contains(".flac", true) == true)
            "flac" else "mp3"

    private fun fileFor(c: Context, id: String, extension: String): File =
        File(dir(c), key(id) + "." + extension)

    private fun refresh() {
        val c = ctx ?: return
        val dir = dir(c)
        val list = mutableListOf<Item>()
        dir.listFiles { f -> f.extension == "json" }?.forEach { metaFile ->
            runCatching {
                val o = JSONObject(metaFile.readText())
                val file = File(dir, o.optString("file"))
                if (!file.exists() || file.length() < 100_000) return@runCatching
                val cover = o.optString("coverUrl").takeIf { it.isNotEmpty() }
                val track = Track(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    artist = o.getString("artist"),
                    album = o.optString("album"),
                    durationMs = o.optLong("durationMs"),
                    streamUrl = file.toURI().toString(),
                    lyricsProvider = "LRCLIB",
                    coverUrl = cover
                )
                list += Item(
                    track = track,
                    file = file,
                    format = o.optString("format", "MP3"),
                    bytes = file.length()
                )
            }
        }
        _items.value = list.sortedByDescending { it.file.lastModified() }
    }

    private fun fetch(url: String, dest: File, id: String): Boolean {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 90_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Ketvoice/2.0")
            }
            if (c.responseCode !in 200..299) {
                c.disconnect()
                return false
            }
            val total = c.contentLengthLong
            val tmp = File(dest.parentFile, dest.name + ".part")
            val buf = ByteArray(96 * 1024)
            var written = 0L
            var lastTick = 0L
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        if (total > 0 && now - lastTick > 250) {
                            lastTick = now
                            _progress.value = _progress.value +
                                (id to ((written * 100 / total).toInt().coerceIn(0, 99)))
                        }
                    }
                }
            }
            c.disconnect()
            if (tmp.length() < 100_000) {
                tmp.delete()
                return false
            }
            if (dest.extension == "flac") {
                val signature = ByteArray(4)
                val valid = tmp.inputStream().use { it.read(signature) == 4 } &&
                    signature.contentEquals(byteArrayOf(0x66, 0x4c, 0x61, 0x43))
                if (!valid) { tmp.delete(); return false }
            }
            if (dest.exists()) dest.delete()
            tmp.renameTo(dest)
        } catch (_: Exception) {
            false
        }
    }

    private fun dir(context: Context): File =
        File(context.filesDir, "downloads").apply { if (!exists()) mkdirs() }

    private fun key(id: String): String = MessageDigest.getInstance("SHA-1")
        .digest(id.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(24)

    private fun toast(message: String) {
        Handler(Looper.getMainLooper()).post {
            ctx?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
        }
    }
}
