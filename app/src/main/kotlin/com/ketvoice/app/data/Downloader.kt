@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ketvoice.app.data

import android.content.Context
import android.net.Uri
import android.os.StatFs
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import androidx.media3.exoplayer.dash.offline.DashDownloader
import com.ketvoice.app.player.OfflineDashStore

object Downloader {

    data class Item(
        val track: Track,
        val file: File,
        val format: String,
        val bytes: Long,
        val lyricsFile: File? = null,
        val canvasFile: File? = null,
        val hasLyrics: Boolean = false,
        val hasCanvas: Boolean = false,
        val dashManifestUrl: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy
    private val _activeTracks = MutableStateFlow<Map<String, Track>>(emptyMap())
    val activeTracks: StateFlow<Map<String, Track>> = _activeTracks

    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val progress: StateFlow<Map<String, Int>> = _progress
    private val _tasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val tasks: StateFlow<Map<String, DownloadTask>> = _tasks
    private val paused = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Downloads that must survive process death; mirrored to `download-queue.json`. */
    @Volatile private var queueStore: DownloadQueueStore? = null

    fun pause(id: String) {
        if (id !in _busy.value) return
        paused.add(id)
        activeDash[id]?.cancel()
        task(id) { it.copy(phase = DownloadPhase.Paused) }
        ctx?.let { c -> updateQueue(c) { DownloadQueue.withState(it, id, QueueState.Paused) } }
    }

    fun resume(id: String) {
        paused.remove(id)
        val c = ctx ?: return
        updateQueue(c) { DownloadQueue.withState(it, id, QueueState.Pending) }
        // A download restored from disk has no live coroutine: start one.
        if (jobs[id] == null) {
            val track = _activeTracks.value[id] ?: DownloadQueue.find(queued, id)?.track ?: return
            start(track, capsuleLimit = null, announce = false)
        }
    }

    fun cancel(id: String) {
        task(id) { it.copy(phase = DownloadPhase.Cancelled) }
        paused.remove(id)
        val job = jobs.remove(id)
        activeDash[id]?.cancel()
        connections.remove(id)?.disconnect()
        job?.cancel()
        // A restored entry has no live coroutine, so nothing will run the
        // `finally` block that normally releases its busy slot.
        if (job == null) {
            _progress.update { it - id }
            _busy.update { it - id }
            _activeTracks.update { it - id }
            ctx?.let { context ->
                if (itemFor(id) == null && File(context.filesDir, "downloads-dash-v1").exists()) scope.launch {
                    runCatching { OfflineDashStore.get(context).remove(id) }
                }
            }
        }
        ctx?.let { c -> updateQueue(c) { DownloadQueue.remove(it, id) } }
    }

    /** Queued downloads, newest last; exposed for diagnostics and tests. */
    val queued: List<QueuedDownload> get() = queueStore?.entries ?: emptyList()

    private fun storeFor(c: Context): DownloadQueueStore =
        queueStore ?: synchronized(this) {
            queueStore ?: DownloadQueueStore(File(c.filesDir, QUEUE_FILE)) { file, bytes ->
                // The store owns the queue rules; the app keeps AtomicFile's fsync.
                writeAtomic(file, bytes)
            }.also { queueStore = it }
        }

    private fun updateQueue(c: Context, change: (List<QueuedDownload>) -> List<QueuedDownload>) {
        storeFor(c).mutate(change)
    }

    private fun restoreQueue(c: Context) {
        val surviving = storeFor(c).load(_items.value.map { it.track.id }.toSet())
        surviving.forEach { entry ->
            val id = entry.track.id
            if (jobs.containsKey(id)) return@forEach
            _activeTracks.update { it + (id to entry.track) }
            task(id) { DownloadTask(phase = DownloadQueue.phaseFor(entry.state)) }
            if (!DownloadQueue.countsAsBusy(entry.state)) return@forEach
            _busy.update { it + id }
            _progress.update { it + (id to 0) }
            if (DownloadQueue.startsImmediately(entry.state)) start(entry.track, capsuleLimit = null, announce = false)
        }
    }

    private const val QUEUE_FILE = "download-queue.json"
    private fun task(id: String, change: (DownloadTask) -> DownloadTask) {
        _tasks.update { it + (id to change(it[id] ?: DownloadTask())) }
    }
    private fun wifiConnected(): Boolean {
        val cm = ctx?.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    private suspend fun awaitTransfer(id: String, phase: DownloadPhase) {
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val waiting = when {
                id in paused -> DownloadPhase.Paused
                AppSettings.downloadsWifiOnly.value && !wifiConnected() -> DownloadPhase.WaitingWifi
                else -> null
            }
            if (_tasks.value[id]?.phase != (waiting ?: phase)) task(id) { it.copy(phase = waiting ?: phase) }
            if (waiting == null) return
            kotlinx.coroutines.delay(200)
        }
    }

    private var ctx: Context? = null
    private val connections = java.util.concurrent.ConcurrentHashMap<String, HttpURLConnection>()
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val activeDash = java.util.concurrent.ConcurrentHashMap<String, DashDownloader>()
    internal var openAudioConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }

    fun init(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        refresh()
        restoreQueue(context.applicationContext)
    }

    fun itemFor(trackId: String): Item? = _items.value.firstOrNull { it.track.id == trackId }

    @Synchronized fun download(track: Track, capsuleLimit: Long? = null): Job? {
        val c = ctx ?: return null
        if (itemFor(track.id) != null) {
            toast("Déjà téléchargé")
            return null
        }
        jobs[track.id]?.let { return it }
        // Capsule preparation is a transient bundle, not a user download.
        if (capsuleLimit == null) updateQueue(c) { DownloadQueue.withEntry(it, track) }
        return start(track, capsuleLimit, announce = true)
    }

    @Synchronized private fun start(track: Track, capsuleLimit: Long?, announce: Boolean): Job? {
        val c = ctx ?: return null
        // A damaged or unreadable existing entry must never be overwritten by a retry.
        val stem = key(track.id)
        val damagedMetadata = File(dir(c), "$stem.json").exists() && itemFor(track.id) == null
        val orphanAudio = listOf("flac", "mp3", "m4a", "opus", "wav").any { File(dir(c), "$stem.$it").exists() } && itemFor(track.id) == null
        if (damagedMetadata || orphanAudio) {
            task(track.id) { it.copy(phase = DownloadPhase.Failed) }
            _busy.update { it - track.id }
            if (capsuleLimit == null) updateQueue(c) { DownloadQueue.withState(it, track.id, QueueState.Failed) }
            if (announce) toast("Téléchargement existant illisible : aucun fichier remplacé")
            return null
        }
        _busy.update { it + track.id }
        _activeTracks.update { it + (track.id to track) }
        _progress.update { it + (track.id to 0) }
        paused.remove(track.id)
        task(track.id) { DownloadTask() }
        if (announce) toast("Téléchargement de « ${track.title} »…")
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                awaitTransfer(track.id, DownloadPhase.Resolving)
                val budget = DownloadBudget(capsuleLimit ?: Long.MAX_VALUE)
                val audioLimit = if (capsuleLimit == null) Long.MAX_VALUE else (capsuleLimit - minOf(1_048_576L, capsuleLimit / 4)).coerceAtLeast(0)
                val preferFlac = capsuleLimit == null && AppSettings.downloadQualityMode.value == 0
                val publication = track.addon == "upload" || track.id.startsWith("kv:upload:")
                var gotFile: File? = null
                var gotStream: MusicApi.StreamInfo? = null
                var gotDashUrl: String? = null
                var gotDashBytes = 0L
                var dashPermissionMissing = false
                suspend fun tryCandidate(candidate: MusicApi.StreamInfo): Boolean {
                    if (candidate.isDash) {
                        val direct = candidate.downloadUrl?.takeIf { it != candidate.url && it.startsWith("https://") }
                        if (direct != null) {
                            val directStream = candidate.copy(url = direct, format = "", mimeType = null)
                            val dest = fileFor(c, track.id, extOf(directStream))
                            if (fetch(direct, dest, track.id, audioLimit) { !isActive }) {
                                gotFile = dest; gotStream = directStream; return true
                            }
                            dest.delete()
                        }
                        if (!candidate.dashDownloadAllowed) {
                            dashPermissionMissing = true
                            return false
                        }
                        if (!CustomSources.reusableManifestUrl(candidate.url)) return false
                        val bytes = fetchDash(c, track.id, candidate.url, audioLimit) { !isActive } ?: return false
                        val marker = fileFor(c, track.id, "mpd")
                        if (marker.exists()) {
                            if (marker.length() > 64 || marker.readText(Charsets.US_ASCII) != "KETVOICE_DASH_OFFLINE_V1") return false
                        } else writeAtomic(marker, "KETVOICE_DASH_OFFLINE_V1".toByteArray(Charsets.US_ASCII))
                        gotFile = marker; gotStream = candidate; gotDashUrl = candidate.url; gotDashBytes = bytes
                        return true
                    }
                    val dest = fileFor(c, track.id, extOf(candidate))
                    val urls = listOfNotNull(candidate.downloadUrl, candidate.url)
                        .distinct().filter { it.startsWith("https://") }
                    if (urls.any { fetch(it, dest, track.id, audioLimit) { !isActive } }) {
                        gotFile = dest; gotStream = candidate; return true
                    }
                    dest.delete()
                    return false
                }
                val mode = AppSettings.downloadQualityMode.value
                val resolved: List<StreamResolver.Candidate> = if (publication) {
                    PublicationApi.stream(track)
                        ?.let { listOf(StreamResolver.Candidate("publication", 0, it)) }
                        .orEmpty()
                } else {
                    // Quality modes need every candidate in parallel even when
                    // progressive playback is off; otherwise the first source
                    // (usually 128 kb/s MP3) would be the only choice.
                    val window = when (mode) { 0 -> 12_000L; 1 -> 5_000L; else -> 0L }
                    val flow = StreamResolver.candidates(track, download = true, lossless = preferFlac,
                        race = window > 0L || AppSettings.progressiveQuality.value)
                    if (window == 0L) flow.take(1).toList() else StreamResolver.collectWithin(flow, window)
                }
                // Try the best verified candidate first, then the next ones: one
                // failing URL must never end the download.
                for (resolvedCandidate in resolved) {
                    if (tryCandidate(resolvedCandidate.stream)) break
                }
                if (gotFile == null && !publication) {
                    // Last-resort fallback to the previous custom-source path.
                    val legacy = withTimeoutOrNull(20_000) {
                        runCatching { CustomSources.streamForTrack(track, forDownload = true) }.getOrNull()
                    }
                    if (legacy != null) {
                        tryCandidate(legacy)
                    }
                }

                val file = gotFile
                val selected = gotStream
                if (file == null || selected == null) {
                    task(track.id) { it.copy(phase = DownloadPhase.Failed) }
                    toast(if (dashPermissionMissing) "Autorisation de téléchargement non déclarée par la source"
                        else "Échec du téléchargement (aucun flux lisible)")
                    return@launch
                }

                check(budget.spend(if (gotDashUrl != null) gotDashBytes + file.length() else file.length())) { "Budget de la capsule atteint" }
                _progress.value = _progress.value + (track.id to 99)
                task(track.id) { it.copy(phase = DownloadPhase.Finalizing) }
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
                    put("format", if (gotDashUrl != null) "DASH · FLAC" else displayFormat(file, selected))
                    put("quality", selected.quality)
                    put("source", selected.quality)
                    put("file", file.name)
                    if (gotDashUrl != null) put("dashManifestUrl", gotDashUrl)
                    put("downloadedAt", System.currentTimeMillis())
                }

                val metaFile = File(dir(c), file.nameWithoutExtension + ".json")
                var metadataSize = 0L
                fun persistMeta() {
                    val bytes = meta.toString().toByteArray(Charsets.UTF_8)
                    check(budget.replace(metadataSize, bytes.size.toLong())) { "Budget de la capsule atteint" }
                    writeAtomic(metaFile, bytes); metadataSize = bytes.size.toLong()
                }
                persistMeta()
                refresh()

                val best = withTimeoutOrNull(30_000) { runCatching { MusicApi.bestLyrics(track) }.getOrNull() }
                if (best != null && best.second.isNotEmpty()) {
                    val lyricsFile = File(dir(c), file.nameWithoutExtension + ".lyrics.json")
                    val saved = runCatching { saveLyrics(lyricsFile, best.first, best.second, best.third, (budget.remaining - 8192).coerceAtLeast(0)) }.getOrDefault(false)
                    if (saved) {
                    check(budget.spend(lyricsFile.length()))
                    meta.put("lyricsFile", lyricsFile.name)
                    meta.put("lyricsSource", best.first)
                    meta.put("lyricsSourceKey", best.third)
                    meta.put("lyricsLines", best.second.size)
                    meta.put("lyricsTimed", best.second.any { it.timeMs >= 0 })

                    runCatching {
                        val bytes = toLrc(best.second).toByteArray(Charsets.UTF_8)
                        if (bytes.size <= budget.remaining - 8192 && budget.spend(bytes.size.toLong()))
                            writeAtomic(File(dir(c), file.nameWithoutExtension + ".lrc"), bytes)
                    }
                    }
                }

                if (capsuleLimit != null && isActive) {
                    val image = runCatching { offlineCover(track, (budget.remaining - 8192).coerceAtLeast(0)) }.getOrNull()
                    if (image != null && budget.spend(image.size.toLong())) {
                        val path = File(dir(c), file.nameWithoutExtension + ".cover.jpg")
                        writeAtomic(path, image); meta.put("coverFile", path.name)
                        meta.put("coverUrl", Uri.fromFile(path).toString())
                    }
                }

                if (capsuleLimit == null) withTimeoutOrNull(50_000) { runCatching { CanvasRepo.resolve(track, force = true) } }
                val canvas = CanvasRepo.localPath(track.id)
                if (canvas != null) {
                    meta.put("canvasFile", canvas.absolutePath)
                    meta.put("canvasSpotifyId", canvas.nameWithoutExtension)
                }

                persistMeta()
                _progress.value = _progress.value + (track.id to 100)
                refresh()
                toast("« ${track.title} » téléchargé")
                task(track.id) { it.copy(phase = DownloadPhase.Complete) }
            } catch (cancelled: CancellationException) {
                task(track.id) { it.copy(phase = DownloadPhase.Cancelled) }
                throw cancelled
            } catch (_: Exception) {
                task(track.id) { it.copy(phase = DownloadPhase.Failed) }
                toast("Échec du téléchargement")
            } finally {
                _progress.update { it - track.id }
                _busy.update { it - track.id }
                if (_tasks.value[track.id]?.phase in setOf(DownloadPhase.Complete, DownloadPhase.Cancelled))
                    _activeTracks.update { it - track.id }
                paused.remove(track.id)
                jobs.remove(track.id)
                // Keep the on-disk queue in step with what really happened:
                // finished or cancelled downloads leave it, failures stay
                // visible so they can be retried after a restart.
                val context = ctx
                if (context != null) {
                    if (_tasks.value[track.id]?.phase == DownloadPhase.Cancelled && itemFor(track.id) == null &&
                        File(context.filesDir, "downloads-dash-v1").exists()) {
                        runCatching { OfflineDashStore.get(context).remove(track.id) }
                    }
                    when (_tasks.value[track.id]?.phase) {
                        DownloadPhase.Complete -> updateQueue(context) { DownloadQueue.remove(it, track.id) }
                        DownloadPhase.Failed -> updateQueue(context) { DownloadQueue.withState(it, track.id, QueueState.Failed) }
                        else -> updateQueue(context) { DownloadQueue.remove(it, track.id) }
                    }
                }
            }
        }
        jobs[track.id] = job
        job.start()
        return job
    }

    internal suspend fun fetchDash(context: Context, id: String, manifestUrl: String, limit: Long,
                                   cancelled: () -> Boolean): Long? {
        val store = try { OfflineDashStore.get(context) } catch (_: Exception) { return null }
        while (true) {
            awaitTransfer(id, DownloadPhase.Transferring)
            if (cancelled()) throw CancellationException()
            val downloader = try {
                store.downloader(id, manifestUrl)
            } catch (_: Exception) { return null }
            activeDash[id] = downloader
            val exceeded = java.util.concurrent.atomic.AtomicBoolean(false)
            val lastDiskCheck = java.util.concurrent.atomic.AtomicLong(0L)
            val monitor = CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch(Dispatchers.IO) {
                while (isActive) {
                    if (id in paused || (AppSettings.downloadsWifiOnly.value && !wifiConnected())) downloader.cancel()
                    kotlinx.coroutines.delay(250)
                }
            }
            try {
                downloader.download { contentLength, downloaded, percent ->
                    task(id) { it.copy(phase = DownloadPhase.Transferring, bytes = downloaded,
                        total = contentLength.takeIf { length -> length > 0 }) }
                    if (percent.isFinite() && percent >= 0f) _progress.update { it + (id to percent.toInt().coerceIn(0, 98)) }
                    val now = android.os.SystemClock.elapsedRealtime()
                    val checkDisk = now - lastDiskCheck.get() >= 1_000 && lastDiskCheck.getAndSet(now) != now
                    val lowDisk = checkDisk && runCatching { StatFs(context.filesDir.path).availableBytes < 64L * 1024 * 1024 }.getOrDefault(true)
                    if (downloaded > limit || contentLength > limit || lowDisk) exceeded.set(true)
                    if (cancelled() || id in paused || (AppSettings.downloadsWifiOnly.value && !wifiConnected()) || exceeded.get())
                        downloader.cancel()
                }
                if (cancelled()) throw CancellationException()
                if (exceeded.get()) return null
                if (id in paused || (AppSettings.downloadsWifiOnly.value && !wifiConnected())) continue
                store.verify(id, manifestUrl)
                val bytes = store.verifyOnce(id, manifestUrl)
                return bytes.takeIf { it <= limit }
            } catch (cancelledException: CancellationException) {
                if (cancelled() || !kotlinx.coroutines.currentCoroutineContext().isActive) throw cancelledException
                if (exceeded.get()) return null
                if (id in paused || (AppSettings.downloadsWifiOnly.value && !wifiConnected())) continue
                return null
            } catch (_: Exception) {
                if (cancelled()) throw CancellationException()
                if (exceeded.get()) return null
                if (id in paused || (AppSettings.downloadsWifiOnly.value && !wifiConnected())) continue
                return null
            } finally {
                monitor.cancel()
                activeDash.remove(id, downloader)
            }
        }
    }

    suspend fun prepareCapsuleTrack(track: Track, limit: Long): Boolean {
        if (itemFor(track.id) != null) return true
        jobs[track.id]?.let { it.join(); return itemFor(track.id) != null }
        val job = download(track, limit) ?: return itemFor(track.id) != null
        try { job.join() }
        catch (cancelled: CancellationException) { job.cancel(); connections[track.id]?.disconnect(); throw cancelled }
        return itemFor(track.id) != null
    }

    fun storedBytes(item: Item): Long {
        val stem = item.file.nameWithoutExtension
        return (if (item.dashManifestUrl != null) item.bytes else item.file.length()) + listOf(".json", ".lyrics.json", ".lrc", ".cover.jpg")
            .sumOf { File(item.file.parentFile, stem + it).takeIf { it.exists() }?.length() ?: 0L }
    }

    fun delete(item: Item): Boolean {
        val removed = runCatching {
            if (item.dashManifestUrl != null) {
                val context = ctx ?: return@runCatching false
                val cache = OfflineDashStore.get(context)
                cache.remove(item.track.id)
                if (cache.size(item.track.id) != 0L) return@runCatching false
            }
            if (item.file.exists() && !item.file.delete()) return@runCatching false
            val stem = item.file.nameWithoutExtension
            File(item.file.parentFile, "$stem.json").delete()
            File(item.file.parentFile, "$stem.lyrics.json").delete()
            File(item.file.parentFile, "$stem.lrc").delete()
            File(item.file.parentFile, "$stem.cover.jpg").delete()
            ArtworkRepo.removeOfflineCover(item.track.id)
            true
        }.getOrDefault(false)
        if (!removed) { toast("Impossible de supprimer certains fichiers."); return false }
        ctx?.let { c -> updateQueue(c) { DownloadQueue.remove(it, item.track.id) } }
        refresh()
        return true
    }

    fun totalBytes(): Long = _items.value.sumOf { it.bytes }

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
        sourceKey: String,
        limit: Long = Long.MAX_VALUE
    ): Boolean {
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
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > limit) return false
        writeAtomic(file, bytes)
        return true
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
                val id = o.getString("id")
                val rawDash = o.optString("dashManifestUrl").takeIf { it.isNotBlank() }
                val dash = rawDash?.takeIf(CustomSources::reusableManifestUrl)
                if (rawDash != null && dash == null) return@runCatching
                val dashBytes = if (dash != null) {
                    if (file.name != fileFor(c, id, "mpd").name || !file.isFile || file.length() > 64 ||
                        file.readText(Charsets.US_ASCII) != "KETVOICE_DASH_OFFLINE_V1") return@runCatching
                    runCatching { OfflineDashStore.get(c).verifyOnce(id, dash) }.getOrNull() ?: return@runCatching
                } else {
                    if (!file.exists() || file.length() < 10_000) return@runCatching
                    0L
                }
                val coverFile = File(dir, file.nameWithoutExtension + ".cover.jpg").takeIf { it.isFile && it.length() > 0 }
                val cover = coverFile?.let { Uri.fromFile(it).toString() } ?: o.optString("coverUrl").takeIf { it.isNotEmpty() }
                val lyricsFile = o.optString("lyricsFile").takeIf { it.isNotEmpty() }?.let { File(dir, it) }
                val canvasName = o.optString("canvasFile").takeIf { it.isNotEmpty() }
                val canvasFile = canvasName?.let { File(it) }
                    ?: o.optString("canvasSpotifyId").takeIf { it.isNotEmpty() }
                        ?.let { File(c.filesDir, "canvas/$it.mp4") }
                val track = Track(
                    id = id,
                    title = o.getString("title"),
                    artist = o.getString("artist"),
                    album = o.optString("album"),
                    durationMs = o.optLong("durationMs"),
                    albumId = o.optString("albumId").takeIf { it.isNotBlank() },
                    artistId = o.optString("artistId").takeIf { it.isNotBlank() },
                    spotifyId = o.optString("canvasSpotifyId", o.optString("spotifyId")).takeIf { it.isNotBlank() },
                    streamUrl = if (dash == null) Uri.fromFile(file).toString() else null,
                    lyricsProvider = o.optString("lyricsSource", "LRCLIB"),
                    coverUrl = cover
                )
                if (coverFile != null) ArtworkRepo.acceptOfflineCover(track.id, coverFile)
                list += Item(
                    track = track,
                    file = file,
                    format = o.optString("format", "MP3"),
                    bytes = file.length() + dashBytes,
                    lyricsFile = lyricsFile?.takeIf { it.exists() && it.length() > 0 },
                    canvasFile = canvasFile?.takeIf { it.exists() && it.length() > 10_000 },
                    hasLyrics = lyricsFile?.let { it.exists() && it.length() > 0 } == true,
                    hasCanvas = canvasFile?.let { it.exists() && it.length() > 10_000 } == true,
                    dashManifestUrl = dash
                )
            }
        }
        _items.value = list.sortedByDescending { it.file.lastModified() }
    }

    private class LimitExceeded : Exception()

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val atomic = android.util.AtomicFile(file)
        val out = atomic.startWrite()
        try { out.write(bytes); atomic.finishWrite(out) }
        catch (failure: Exception) { atomic.failWrite(out); throw failure }
    }

    internal suspend fun fetch(url: String, dest: File, id: String, limit: Long = Long.MAX_VALUE, cancelled: () -> Boolean = { false }): Boolean {
        val tmp = File(dest.parentFile, dest.name + ".part")
        tmp.delete()
        var validator: String? = null
        try {
        repeat(3) {
          awaitTransfer(id, DownloadPhase.Transferring)
          if (cancelled()) { tmp.delete(); throw CancellationException() }
          var connection: HttpURLConnection? = null
          try {
            var current = URL(url)
            val offset = tmp.takeIf { it.exists() && validator != null }?.length() ?: 0L
            var responseCode = 0
            for (hop in 0..5) {
                if (cancelled()) throw CancellationException()
                if (current.protocol != "https") return false
                connection = openAudioConnection(current).apply {
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
                connections[id] = connection
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
            val ranged = responseCode == 206 && DownloadValidation.validRange(offset,
                range?.groupValues?.get(1)?.toLongOrNull(), range?.groupValues?.get(2)?.toLongOrNull(),
                range?.groupValues?.get(3)?.toLongOrNull())
            val resumed = ranged && offset > 0L
            if (responseCode == 206 && !ranged) {
                tmp.delete()
                return@repeat
            }
            val incomingValidator = c.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") } ?: c.getHeaderField("Last-Modified")
            if (resumed && validator != null && incomingValidator != null && validator != incomingValidator) {
                tmp.delete(); validator = null
                return@repeat
            }
            validator = incomingValidator
            val total = if (ranged) range!!.groupValues[3].toLong() else c.contentLengthLong
            task(id) { it.copy(total = total.takeIf { it > 0 }, restarted = it.restarted || (offset > 0 && !resumed)) }
            if (total > limit) { tmp.delete(); return false }
            val buf = ByteArray(128 * 1024)
            var written = if (resumed) offset else 0L
            var lastTick = 0L
            c.inputStream.use { input ->
                FileOutputStream(tmp, resumed).use { out ->
                    while (true) {
                        awaitTransfer(id, DownloadPhase.Transferring)
                        if (cancelled()) throw CancellationException()
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > limit - written) throw LimitExceeded()
                        out.write(buf, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        if (now - lastTick > 200) {
                            lastTick = now
                            if (total > 0) {
                                val percent = (written * 98 / total).toInt().coerceIn(0, 98)
                                _progress.update { it + (id to percent) }
                            }
                            task(id) { it.copy(bytes = written) }
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
          } catch (cancelled: CancellationException) {
              tmp.delete(); throw cancelled
          } catch (_: LimitExceeded) {
              tmp.delete(); return false
          } catch (_: Exception) {

          } finally {
               connection?.disconnect()
               connection?.let { connections.remove(id, it) }
          }
        }
        tmp.delete()
        return false
        } catch (cancelled: CancellationException) {
            tmp.delete()
            throw cancelled
        }
    }

    private fun offlineCover(track: Track, available: Long): ByteArray? {
        val limit = minOf(512 * 1024L, available)
        if (limit < 1024) return null
        var url = URL(ArtworkRepo.coverForTrack(track) ?: return null)
        repeat(5) {
            if (url.protocol != "https") return null
            val c = url.openConnection() as HttpURLConnection
            try {
                connections[track.id] = c
                c.connectTimeout = 8000; c.readTimeout = 12000; c.instanceFollowRedirects = false
                val status = c.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    url = URL(url, c.getHeaderField("Location") ?: return null)
                    return@repeat
                }
                if (status !in 200..299 || c.contentLengthLong > limit) return null
                val output = java.io.ByteArrayOutputStream()
                c.inputStream.use { input ->
                    val bytes = ByteArray(8192)
                    while (true) {
                        val count = input.read(bytes); if (count < 0) break
                        if (count > limit - output.size()) return null
                        output.write(bytes, 0, count)
                    }
                }
                val data = output.toByteArray()
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 50_000_000) return null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
                return try {
                    val image = java.io.ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, image)
                    image.toByteArray().takeIf { it.size <= limit }
                } finally { bitmap.recycle() }
            } finally { connections.remove(track.id, c); c.disconnect() }
        }
        return null
    }

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
