package com.ketvoice.desktop.library

import android.content.Context
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.DownloadPhase
import com.ketvoice.app.data.DownloadQueue
import com.ketvoice.app.data.DownloadQueueStore
import com.ketvoice.app.data.DownloadTask
import com.ketvoice.app.data.DownloadUpgrade
import com.ketvoice.app.data.DownloadValidation
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.QueueState
import com.ketvoice.app.data.RangedDownload
import com.ketvoice.app.data.StreamQuality
import com.ketvoice.app.data.StreamResolver
import com.ketvoice.app.data.Track
import com.ketvoice.desktop.player.DashAudio
import com.ketvoice.desktop.player.DesktopStreams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads for Ketvoice for Windows, in `%LOCALAPPDATA%\Ketvoice\Data\downloads`, in the Android
 * app's layout: `<key>.<ext>` with its `<key>.json` metadata, `<key>.lyrics.json` + `.lrc` and
 * `<key>.cover.jpg` next to it. The shared rules decide (queue file, range checks, audio headers,
 * parts at once, upgrades); a saved file is only ever replaced by one already verified.
 */
object DesktopDownloader {
    data class Item(val track: Track, val file: File, val format: String, val bytes: Long,
                    val lyricsFile: File? = null, val coverFile: File? = null, val downloadedAt: Long = 0L)

    private const val CONCURRENT = 2
    private const val MAX_COVER = 2L * 1024 * 1024
    private val EXTENSIONS = listOf("flac", "mp3", "m4a", "opus", "wav", "webm")

    @Volatile private var root: File? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var slots = Semaphore(CONCURRENT)
    private var queue: DownloadQueueStore? = null

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    /** What is saved, newest first. */
    val items: StateFlow<List<Item>> = _items
    private val _tasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    /** Every title being downloaded, paused, failed or just finished, by id. */
    val tasks: StateFlow<Map<String, DownloadTask>> = _tasks
    private val perTitle = ConcurrentHashMap<String, MutableStateFlow<DownloadTask?>>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val tracks = ConcurrentHashMap<String, Track>()
    private val paused = ConcurrentHashMap.newKeySet<String>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val connections = ConcurrentHashMap<String, MutableSet<HttpURLConnection>>()

    /** The offers for a download, best first (the app's sources, collected for a few seconds). */
    internal var candidates: suspend (Track, Boolean) -> List<MusicApi.StreamInfo> = { track, lossless ->
        val flow = StreamResolver.candidates(track, download = true, lossless = lossless, race = true)
        StreamResolver.ranked(withTimeoutOrNull(20_000) { StreamResolver.collectWithin(flow, 8_000L) }
            ?: flow.toList()).map { it.stream }
    }
    internal var lyrics: suspend (Track) -> Triple<String, List<MusicApi.LyricLine>, String>? = { MusicApi.bestLyrics(it) }
    internal var coverOf: (Track) -> String? = { ArtworkRepo.coverForTrack(it) ?: it.coverUrl }
    /** Tests serve files from a local plain-HTTP server; the app only fetches over HTTPS. */
    internal var allowPlainHttp = false

    private class Paused : CancellationException("paused")
    private class Stopped : CancellationException("cancelled")

    /** Opens the folder, puts back what a crash left half-swapped, lists the files and restarts the queue. */
    @Synchronized
    fun init(directory: File = File(Context.app.dataDir, "downloads"),
             scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
        val dir = directory.apply { mkdirs() }
        root = dir
        this.scope = scope
        DownloadUpgrade.recover(dir)
        refresh()
        val store = DownloadQueueStore(File(dir, "download-queue.json"))
        queue = store
        for (entry in store.load(_items.value.mapTo(HashSet()) { it.track.id })) {
            tracks[entry.track.id] = entry.track
            if (DownloadQueue.startsImmediately(entry.state)) start(entry.track)
            else setTask(entry.track.id) { DownloadTask(DownloadQueue.phaseFor(entry.state)) }
        }
    }

    private fun dir(): File = root ?: File(Context.app.dataDir, "downloads").also { root = it; it.mkdirs() }

    /** The saved audio of a title, or null. */
    fun fileOf(id: String): File? = _items.value.firstOrNull { it.track.id == id }?.file?.takeIf { it.isFile }

    /** One title's download as it goes: null when none is running or waiting. */
    fun progress(id: String): StateFlow<DownloadTask?> = perTitle.getOrPut(id) { MutableStateFlow(_tasks.value[id]) }

    fun download(track: Track) {
        if (fileOf(track.id) != null || jobs[track.id]?.isActive == true) return
        tracks[track.id] = track
        queue?.mutate { DownloadQueue.withEntry(it, track) }
        start(track)
    }

    /** A whole album (or any list): each title once, in order, two at a time. */
    fun downloadAll(tracks: List<Track>) = tracks.distinctBy { it.id }.forEach(::download)

    /** Stops the transfer and keeps what came, to go on from there with [resume]. */
    fun pause(id: String) {
        val job = jobs[id]?.takeIf { it.isActive } ?: return
        paused.add(id)
        queue?.mutate { DownloadQueue.withState(it, id, QueueState.Paused) }
        disconnect(id)
        job.cancel(Paused())
    }

    fun resume(id: String) {
        if (jobs[id]?.isActive == true) return
        val track = tracks[id] ?: queue?.entries?.let { DownloadQueue.find(it, id) }?.track ?: return
        queue?.mutate { DownloadQueue.withEntry(it, track) }
        start(track)
    }

    /** Stops and forgets the download, with what had come. A saved file stays. */
    fun cancel(id: String) {
        cancelled.add(id)
        queue?.mutate { DownloadQueue.remove(it, id) }
        disconnect(id)
        val job = jobs[id]
        if (job?.isActive == true) job.cancel(Stopped()) else {
            dropParts(id); setTask(id) { DownloadTask(DownloadPhase.Cancelled) }; cancelled.remove(id)
        }
    }

    /** Removes a saved title and everything kept next to it. */
    fun delete(id: String) {
        val item = _items.value.firstOrNull { it.track.id == id } ?: return
        val stem = item.file.nameWithoutExtension
        item.file.delete()
        listOf(".json", ".lyrics.json", ".lrc", ".cover.jpg").forEach { File(item.file.parentFile, stem + it).delete() }
        refresh()
    }

    private fun start(track: Track) {
        val id = track.id
        paused.remove(id); cancelled.remove(id)
        setTask(id) { DownloadTask(DownloadPhase.Resolving, bytes = it?.bytes ?: 0, total = it?.total) }
        val job = scope.launch(Dispatchers.IO) {
            try {
                slots.withPermit { transfer(track) }
            } catch (stop: CancellationException) {
                if (id in paused) setTask(id) { (it ?: DownloadTask()).copy(phase = DownloadPhase.Paused) }
                else { dropParts(id); setTask(id) { (it ?: DownloadTask()).copy(phase = DownloadPhase.Cancelled) } }
                throw stop
            } catch (_: Exception) {
                fail(id)
            } finally {
                disconnect(id)
                cancelled.remove(id)
            }
        }
        jobs[id] = job
        job.invokeOnCompletion { jobs.remove(id, job) }
    }

    private fun fail(id: String) {
        setTask(id) { (it ?: DownloadTask()).copy(phase = DownloadPhase.Failed) }
        queue?.mutate { DownloadQueue.withState(it, id, QueueState.Failed) }
    }

    private suspend fun transfer(track: Track) {
        val id = track.id
        val stem = key(id)
        // A damaged or unreadable earlier download is never overwritten by a retry.
        if (File(dir(), "$stem.json").exists() || EXTENSIONS.any { File(dir(), "$stem.$it").exists() }) { fail(id); return }
        val offers = candidates(track, AppSettings.downloadQualityMode.value == 0)
        for (offer in offers) {
            for (url in urlsOf(offer)) {
                val dest = File(dir(), "$stem.${extOf(offer, url)}")
                val part = fetch(url, dest, id) ?: continue
                if (!part.renameTo(dest)) { part.copyTo(dest, overwrite = true); part.delete() }
                File(part.path + ".json").delete()
                finish(track, dest, offer)
                return
            }
        }
        fail(id)
    }

    /**
     * « Retélécharger en meilleure qualité »: only an offer [DownloadUpgrade.better] than the saved file,
     * only kept once its header is verified, and swapped in with [DownloadUpgrade.replace] (the old file
     * goes last). False when nothing better came or the new file did not hold what it promised.
     */
    suspend fun upgrade(id: String): Boolean = withContext(Dispatchers.IO) {
        val item = _items.value.firstOrNull { it.track.id == id } ?: return@withContext false
        val saved = DownloadUpgrade.ofSaved(item.format, DownloadUpgrade.head(item.file))
        val offer = candidates(item.track, true).firstOrNull { DownloadUpgrade.better(saved, DownloadUpgrade.ofStream(it)) }
            ?: return@withContext false
        val stem = item.file.nameWithoutExtension
        for (url in urlsOf(offer)) {
            val ext = extOf(offer, url)
            val fresh = fetch(url, File(dir(), "$stem.upgrade.$ext"), id) ?: continue
            File(fresh.path + ".json").delete()
            if (!DownloadUpgrade.verified(saved, ext, DownloadUpgrade.ofStream(offer), DownloadUpgrade.head(fresh))) { fresh.delete(); continue }
            val metaFile = File(dir(), "$stem.json")
            val meta = JSONObject(metaFile.readText(Charsets.UTF_8))
                .put("format", displayFormat(ext, offer)).put("quality", offer.quality).put("source", offer.quality)
                .put("file", "$stem.$ext").put("upgradedAt", System.currentTimeMillis())
            val swapped = DownloadUpgrade.replace(item.file, fresh, File(dir(), "$stem.$ext"), metaFile, meta.toString())
            refresh()
            return@withContext swapped
        }
        false
    }

    private suspend fun urlsOf(offer: MusicApi.StreamInfo): List<String> {
        val direct = if (offer.isDash) listOfNotNull(offer.downloadUrl?.takeIf { it != offer.url },
            DesktopStreams.fetchText(offer.url)?.let { DashAudio.directUrl(it, offer.url) })
            else listOfNotNull(offer.downloadUrl, offer.url)
        return direct.distinct().filter { it.startsWith("https://") || (allowPlainHttp && it.startsWith("http://")) }
    }

    // ------------------------------------------------------------ transfer

    /**
     * Fetches [url] into `<dest>.part`, going on from a paused part when the server still serves the
     * same file (`If-Range`), and returns the part once complete and audio, or null.
     */
    private suspend fun fetch(url: String, dest: File, id: String): File? {
        val part = File(dest.path + ".part")
        val partMeta = File(part.path + ".json")
        var validator = runCatching { JSONObject(partMeta.readText()).optString("validator").ifBlank { null } }.getOrNull()
        if (validator == null || !part.isFile) { part.delete(); partMeta.delete(); validator = null }
        val offset = if (validator != null) part.length() else 0L
        val c = open(URL(url), id) {
            if (offset > 0) { setRequestProperty("Range", "bytes=$offset-"); setRequestProperty("If-Range", validator) }
        } ?: return null
        try {
            val code = c.responseCode
            if (code !in 200..299) return null
            val range = c.getHeaderField("Content-Range")?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it) }?.groupValues
            val ranged = code == 206 && DownloadValidation.validRange(offset, range?.get(1)?.toLongOrNull(),
                range?.get(2)?.toLongOrNull(), range?.get(3)?.toLongOrNull())
            if (code == 206 && !ranged) { part.delete(); partMeta.delete(); return null }
            val resumed = ranged && offset > 0
            val total = if (ranged) range!![3].toLong() else c.contentLengthLong
            val incoming = c.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") } ?: c.getHeaderField("Last-Modified")
            if (incoming != null) partMeta.writeText(JSONObject().put("validator", incoming).put("total", total).toString())
            else partMeta.delete()
            setTask(id) { (it ?: DownloadTask()).copy(phase = DownloadPhase.Transferring, total = total.takeIf { it > 0 },
                bytes = if (resumed) offset else 0, restarted = (it?.restarted ?: false) || (offset > 0 && !resumed)) }
            var lastTick = 0L
            fun tick(written: Long, force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (!force && now - lastTick < 100) return
                lastTick = now
                setTask(id) { (it ?: DownloadTask()).copy(phase = DownloadPhase.Transferring, bytes = written) }
            }
            if (!resumed && RangedDownload.worth(total, c.getHeaderField("Accept-Ranges"), ranged)) {
                // Parts at once cannot go on from a pause: a paused one starts again.
                partMeta.delete()
                val got = java.util.concurrent.atomic.AtomicLong()
                val whole = RangedDownload.fetch(c.inputStream, total, part, open = { r -> openRange(c.url, r, incoming, total, id) },
                    onBytes = { n -> synchronized(got) { tick(got.addAndGet(n)) } }, cancelled = { stopping(id) })
                if (!whole) { part.delete(); return null }
            } else {
                var written = if (resumed) offset else 0L
                c.inputStream.use { input ->
                    FileOutputStream(part, resumed).use { out ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            if (stopping(id)) throw CancellationException()
                            val n = try { input.read(buf) } catch (e: java.io.IOException) { if (stopping(id)) throw CancellationException() else throw e }
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            tick(written)
                        }
                    }
                }
                tick(written, force = true)
            }
            if (!DownloadValidation.complete(part.length(), total) || !looksLikeAudio(part)) {
                part.delete(); partMeta.delete(); return null
            }
            return part
        } catch (stop: CancellationException) {
            if (id !in paused) { part.delete(); partMeta.delete() }
            throw if (id in paused) Paused() else Stopped()
        } catch (_: java.io.IOException) {
            if (stopping(id)) throw if (id in paused) Paused() else Stopped().also { part.delete(); partMeta.delete() }
            return null
        } finally {
            c.disconnect()
            connections[id]?.remove(c)
        }
    }

    private fun stopping(id: String) = id in paused || id in cancelled

    /** A connection after redirects (HTTPS only), or null. */
    private fun open(start: URL, id: String, extra: HttpURLConnection.() -> Unit): HttpURLConnection? {
        var current = start
        repeat(6) {
            if (current.protocol != "https" && !(allowPlainHttp && current.protocol == "http")) return null
            if (stopping(id)) throw CancellationException()
            val c = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", DesktopStreams.USER_AGENT)
                setRequestProperty("Accept", "audio/*,*/*;q=0.8")
                setRequestProperty("Accept-Encoding", "identity")
                extra()
            }
            connections.getOrPut(id) { ConcurrentHashMap.newKeySet() }.add(c)
            val code = try { c.responseCode } catch (e: java.io.IOException) { c.disconnect(); if (stopping(id)) throw CancellationException(); return null }
            val location = c.getHeaderField("Location")
            if (code in 300..399 && !location.isNullOrBlank()) { c.disconnect(); current = URL(current, location); return@repeat }
            return c
        }
        return null
    }

    private fun openRange(url: URL, range: LongRange, validator: String?, total: Long, id: String): InputStream? {
        val c = open(url, id) {
            setRequestProperty("Range", "bytes=${range.first}-${range.last}")
            validator?.let { setRequestProperty("If-Range", it) }
        } ?: return null
        val match = runCatching { c.responseCode }.getOrDefault(0).takeIf { it == 206 }
            ?.let { c.getHeaderField("Content-Range") }?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it) }?.groupValues
        if (match == null || match[1].toLong() != range.first || match[2].toLong() != range.last || match[3].toLong() != total) {
            c.disconnect(); return null
        }
        return c.inputStream
    }

    private fun disconnect(id: String) { connections.remove(id)?.forEach { runCatching { it.disconnect() } } }

    private fun dropParts(id: String) {
        val stem = key(id)
        dir().listFiles { f -> f.name.startsWith("$stem.") && (f.name.endsWith(".part") || f.name.endsWith(".part.json")) }
            ?.forEach { it.delete() }
    }

    /** The shared audio signatures, and WebM (YouTube's Opus), which the desktop engine reads too. */
    private fun looksLikeAudio(file: File): Boolean {
        val head = ByteArray(16)
        val read = file.inputStream().use { it.read(head) }
        if (read <= 0) return false
        val bytes = head.copyOf(read)
        val webm = read >= 4 && bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() && bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()
        return DownloadValidation.audio(bytes) || webm
    }

    // ------------------------------------------------------------ what is kept next to the file

    private suspend fun finish(track: Track, file: File, offer: MusicApi.StreamInfo) {
        val id = track.id
        setTask(id) { (it ?: DownloadTask()).copy(phase = DownloadPhase.Finalizing) }
        val stem = file.nameWithoutExtension
        val meta = JSONObject().apply {
            put("id", id); put("title", track.title); put("artist", track.artist); put("album", track.album)
            put("durationMs", track.durationMs)
            put("albumId", track.albumId ?: ""); put("artistId", track.artistId ?: ""); put("spotifyId", track.spotifyId ?: "")
            put("videoId", track.videoId ?: ""); put("isrc", track.isrc ?: "")
            put("coverUrl", coverOf(track) ?: "")
            put("format", displayFormat(file.extension, offer)); put("quality", offer.quality); put("source", offer.quality)
            put("file", file.name); put("downloadedAt", System.currentTimeMillis())
        }
        val metaFile = File(dir(), "$stem.json")
        writeAtomic(metaFile, meta.toString().toByteArray(Charsets.UTF_8))
        refresh()
        val best = withTimeoutOrNull(30_000) { runCatching { lyrics(track) }.getOrNull() }
        if (best != null && best.second.isNotEmpty()) runCatching {
            writeAtomic(File(dir(), "$stem.lyrics.json"), lyricsJson(best.first, best.second, best.third).toByteArray(Charsets.UTF_8))
            writeAtomic(File(dir(), "$stem.lrc"), toLrc(best.second).toByteArray(Charsets.UTF_8))
            meta.put("lyricsFile", "$stem.lyrics.json").put("lyricsSource", best.first).put("lyricsSourceKey", best.third)
                .put("lyricsLines", best.second.size).put("lyricsTimed", best.second.any { it.timeMs >= 0 })
        }
        coverOf(track)?.let { address -> runCatching { cover(address, id) } .getOrNull() }?.let { image ->
            writeAtomic(File(dir(), "$stem.cover.jpg"), image)
            meta.put("coverFile", "$stem.cover.jpg")
        }
        writeAtomic(metaFile, meta.toString().toByteArray(Charsets.UTF_8))
        refresh()
        queue?.mutate { DownloadQueue.remove(it, id) }
        setTask(id) { (it ?: DownloadTask()).copy(phase = DownloadPhase.Complete) }
    }

    /** The cover's bytes as served (JPEG or PNG, at most 2 MB); the image viewer reads both under any name. */
    private fun cover(address: String, id: String): ByteArray? {
        val c = open(URL(address), id) {} ?: return null
        try {
            if (c.responseCode !in 200..299 || c.contentLengthLong > MAX_COVER) return null
            val bytes = c.inputStream.use { input -> input.readNBytes((MAX_COVER + 1).toInt()) }
            if (bytes.size > MAX_COVER || bytes.size < 4) return null
            val jpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
            val png = bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte()
            return bytes.takeIf { jpeg || png }
        } finally { c.disconnect(); connections[id]?.remove(c) }
    }

    /** The saved lyrics of a downloaded title, read as the shared lyrics code expects them. */
    fun localLyrics(trackId: String): Pair<String, List<MusicApi.LyricLine>>? {
        val file = _items.value.firstOrNull { it.track.id == trackId }?.lyricsFile ?: return null
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val rows = root.optJSONArray("lines") ?: JSONArray()
            val lines = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.map { o ->
                val words = o.optJSONArray("words") ?: JSONArray()
                MusicApi.LyricLine(
                    timeMs = o.optLong("timeMs", -1L), text = o.optString("text"),
                    words = (0 until words.length()).mapNotNull { words.optJSONObject(it) }
                        .map { w -> MusicApi.LyricWord(w.optLong("startMs"), w.optString("text"), w.optLong("endMs", -1L)) },
                    syllableTimed = o.optBoolean("syllableTimed", false),
                    translations = o.optJSONObject("translations")?.let { t -> t.keys().asSequence().associateWith { t.optString(it) } }.orEmpty(),
                    singer = o.optString("singer").ifBlank { null })
            }
            lines.takeIf { it.isNotEmpty() }?.let { root.optString("source", "Paroles") to it }
        }.getOrNull()
    }

    /** The Android sidecar format (`Downloader.saveLyrics`), so a library can move between devices. */
    internal fun lyricsJson(source: String, lines: List<MusicApi.LyricLine>, sourceKey: String): String = JSONObject()
        .put("source", source).put("sourceKey", sourceKey).put("savedAt", System.currentTimeMillis())
        .put("lines", JSONArray(lines.map { line ->
            JSONObject().put("timeMs", line.timeMs).put("text", line.text).put("syllableTimed", line.syllableTimed).apply {
                line.singer?.let { put("singer", it) }
                if (line.translations.isNotEmpty()) put("translations", JSONObject(line.translations))
                if (line.words.isNotEmpty()) put("words", JSONArray(line.words.map { w ->
                    JSONObject().put("startMs", w.startMs).put("text", w.text).put("endMs", w.endMs) }))
            }
        })).toString()

    private fun toLrc(lines: List<MusicApi.LyricLine>): String = buildString {
        fun stamp(ms: Long) = String.format(Locale.ROOT, "%02d:%05.2f", ms / 60_000, (ms % 60_000) / 1000.0)
        for (line in lines) {
            if (line.timeMs < 0) { appendLine(line.text); continue }
            append('[').append(stamp(line.timeMs)).append(']')
            if (line.words.isEmpty()) append(line.text)
            else line.words.forEach { w -> if (w.startMs >= 0) append('<').append(stamp(w.startMs)).append('>'); append(w.text) }
            appendLine()
        }
    }

    // ------------------------------------------------------------ listing

    @Synchronized
    private fun refresh() {
        val dir = dir()
        val list = dir.listFiles { f -> f.extension == "json" && !f.name.endsWith(".lyrics.json") &&
            !f.name.endsWith(".part.json") && f.name != "download-queue.json" }.orEmpty().mapNotNull { metaFile ->
            runCatching {
                val o = JSONObject(metaFile.readText(Charsets.UTF_8))
                val file = File(dir, o.getString("file"))
                if (!file.isFile || file.length() < 10_000) return@runCatching null
                val coverFile = File(dir, file.nameWithoutExtension + ".cover.jpg").takeIf { it.isFile && it.length() > 0 }
                val lyricsFile = o.optString("lyricsFile").takeIf { it.isNotEmpty() }?.let { File(dir, it) }?.takeIf { it.isFile && it.length() > 0 }
                fun text(key: String) = o.optString(key).takeIf { it.isNotBlank() }
                val track = Track(id = o.getString("id"), title = o.getString("title"), artist = o.getString("artist"),
                    album = o.optString("album"), durationMs = o.optLong("durationMs"),
                    albumId = text("albumId"), artistId = text("artistId"), spotifyId = text("spotifyId"),
                    videoId = text("videoId"), isrc = text("isrc"),
                    streamUrl = file.toURI().toString(), lyricsProvider = o.optString("lyricsSource", "LRCLIB"),
                    coverUrl = coverFile?.toURI()?.toString() ?: text("coverUrl"))
                Item(track, file, o.optString("format", "MP3"), file.length(), lyricsFile, coverFile, o.optLong("downloadedAt"))
            }.getOrNull()
        }
        _items.value = list.sortedByDescending { it.downloadedAt }
    }

    private fun setTask(id: String, change: (DownloadTask?) -> DownloadTask) {
        var next: DownloadTask? = null
        _tasks.update { all -> change(all[id]).let { next = it; all + (id to it) } }
        perTitle.getOrPut(id) { MutableStateFlow(null) }.value = next
    }

    private fun extOf(s: MusicApi.StreamInfo, url: String): String {
        val format = (s.format + " " + (s.mimeType ?: "")).lowercase()
        val path = url.substringBefore('?').lowercase()
        return when {
            format.contains("flac") || path.endsWith(".flac") -> "flac"
            format.contains("webm") || path.endsWith(".webm") -> "webm"
            format.contains("m4a") || format.contains("mp4") || format.contains("aac") || path.endsWith(".m4a") || path.endsWith(".mp4") -> "m4a"
            format.contains("ogg") || format.contains("opus") || path.endsWith(".opus") -> "opus"
            format.contains("wav") -> "wav"
            else -> "mp3"
        }
    }

    /** As the Android downloader shows it (« FLAC · Sans perte », « AAC · 130 kbps »). */
    private fun displayFormat(ext: String, s: MusicApi.StreamInfo): String = when (ext) {
        "flac" -> "FLAC · Sans perte"
        "m4a" -> "AAC · ${s.quality}"
        "opus", "webm" -> "Opus · ${s.quality}"
        "wav" -> "WAV · Sans perte"
        else -> "MP3 · ${s.quality}"
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val atomic = android.util.AtomicFile(file)
        val out = atomic.startWrite()
        try { out.write(bytes); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
    }

    /** The Android file key: the first 24 hex digits of the id's SHA-1. */
    internal fun key(id: String): String = MessageDigest.getInstance("SHA-1").digest(id.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(24)

    /** Tests: forget everything (files stay where they are). */
    internal fun resetForTest() {
        jobs.values.forEach { it.cancel() }
        jobs.clear(); tracks.clear(); paused.clear(); cancelled.clear(); perTitle.clear(); connections.clear()
        _items.value = emptyList(); _tasks.value = emptyMap(); queue = null; root = null
        slots = Semaphore(CONCURRENT)
        allowPlainHttp = false
    }
}
