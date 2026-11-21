package com.ketvoice.app.data

import android.content.Context
import com.ketvoice.desktop.canvas.VideoFrames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Canvas videos on the desktop: the Android `CanvasRepo`'s rules, with FFmpeg for the stills and no mobile network.
 *
 * Files live in `<data>/files/canvas/<name>.mp4`, the names in the `ketvoice_canvas` preferences, as on the phone:
 * one entry per title (its catalogue id, or `kvov-`/`kvpub-`/`kvlink-` for an owner's choice, a published Canvas
 * and a link), `album:<artist>:<album>` for an album's first Canvas, `page:<id>` for what an album page showed and
 * `none:<id>` when the catalogue said lately that a title has none. A title whose own Canvas is not known yet shows its
 * album's while it is looked up (a stand-in, never kept as its own). Each kept file gets a still of its first frame,
 * `<name>.poster.jpg`, shown at once while the video starts.
 */
object CanvasRepo {
    private const val PREFS = "ketvoice_canvas"
    private const val NONE_PREFIX = "none:"
    internal const val POSTER_SUFFIX = ".poster.jpg"
    /** A title the catalogue knows no Canvas for is not asked again before this long. */
    internal const val NONE_FOR_MS = 3L * 24 * 60 * 60 * 1000
    /** A Canvas from another site is kept up to this size. */
    private const val LINK_MAX_BYTES = 40L * 1024 * 1024
    private const val MIN_BYTES = 10_000L

    private val _paths = MutableStateFlow<Map<String, String>>(emptyMap())
    val paths: StateFlow<Map<String, String>> = _paths
    private val _loading = MutableStateFlow<Set<String>>(emptySet())
    /** Track ids whose Canvas is being looked up or downloaded now. */
    val loading: StateFlow<Set<String>> = _loading
    private val _posters = MutableStateFlow(0)
    /** Bumped whenever a still is written, so a view waiting for one shows it. */
    val posters: StateFlow<Int> = _posters

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val failed = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val shared = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val postering = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var context: Context? = null
    /** Bumped by [reset]: a lookup still running from before it shows nothing (the tests switch data folders). */
    @Volatile private var generation = 0

    /** The network and the decoder, replaced by the tests. */
    internal var lookup: suspend (Track) -> KetvoiceApi.CanvasResult? = { KetvoiceApi.canvas(it) }
    internal var fetchFile: (String, File, Long) -> File? = { url, dest, max -> download(url, dest, max) }

    fun init(ctx: Context = Context.app) {
        if (context != null) return
        context = ctx.applicationContext
        val dir = dir(ctx)
        _paths.value = prefs(ctx).all.mapNotNull { (key, value) ->
            if (key.startsWith(NONE_PREFIX) || key.startsWith("album:") || value !is String) return@mapNotNull null
            File(dir, "$value.mp4").takeIf { it.isFile && it.length() > MIN_BYTES }?.let { key to it.absolutePath }
        }.toMap()
    }

    /** Forgets everything in memory (the tests start each case from a new data folder). */
    internal fun reset(ctx: Context?) {
        generation++; context = null; _paths.value = emptyMap(); failed.clear(); shared.clear()
        lookup = { KetvoiceApi.canvas(it) }; fetchFile = { url, dest, max -> download(url, dest, max) }
        if (ctx != null) init(ctx)
    }

    fun dropMissing() { _paths.value = _paths.value.filterValues { it.startsWith("https://") || File(it).isFile } }

    /** The Canvas to show for [track]: none while the owner has switched it off for this title or its album. */
    fun pathFor(track: Track): String? = if (MediaOverrides.canvasDisabled(track)) null else _paths.value[track.id]

    fun localPath(id: String): File? = _paths.value[id]?.let(::File)?.takeIf { it.isFile && it.length() > MIN_BYTES }

    fun pageKey(pageId: String): String = "page:$pageId"
    fun pageFile(pageId: String): File? = localPath(pageKey(pageId))

    /** An album page remembers the Canvas its header showed: the next opening shows it before its titles arrive. */
    fun rememberPage(pageId: String, path: String) {
        val ctx = context ?: return
        if (pageId.isBlank() || _paths.value[pageKey(pageId)] == path) return
        val file = File(path).takeIf { it.isFile && it.length() > MIN_BYTES } ?: return
        prefs(ctx).edit().putString(pageKey(pageId), file.nameWithoutExtension).apply()
        _paths.value = _paths.value + (pageKey(pageId) to file.absolutePath)
    }

    /** The Canvas already on this computer for an album (one for all its titles), or null. */
    fun albumFile(album: String, artist: String): File? {
        val ctx = context ?: return null
        val name = catalogueName(prefs(ctx).getString(albumKey(album, artist), null)) ?: return null
        return File(dir(ctx), "$name.mp4").takeIf { it.isFile && it.length() > MIN_BYTES }
    }

    /**
     * [resolve] in the repository's own scope: a view asks and goes on; a download started for a page that closes
     * meanwhile still finishes (and the view never waits on the network).
     */
    fun request(track: Track, ahead: Boolean = false) { scope.launch { runCatching { resolve(track, ahead = ahead) } } }

    /** The next two titles of the queue, so each opens with its Canvas. */
    suspend fun aheadOf(queue: List<Track>, index: Int) {
        (1..2).mapNotNull { queue.getOrNull(index + it) }.forEach { runCatching { resolve(it, ahead = true) } }
    }

    /**
     * Looks for the Canvas of [track] and keeps it on this computer: what is on disk shows at once (its album's
     * standing in), then the owner's choice, the published Canvas or the catalogue's is fetched once.
     */
    suspend fun resolve(track: Track, force: Boolean = false, ahead: Boolean = false): Unit = withContext(Dispatchers.IO) {
        val ctx = context ?: run { init(); context } ?: return@withContext
        val gen = generation
        if (!AppSettings.animatedArtwork.value && !AppSettings.albumHeroCanvas.value) return@withContext
        if (MediaOverrides.canvasUnused(track)) { _paths.value = _paths.value - track.id; return@withContext }
        val curated = MediaOverrides.canvas(track)
        val published = curated ?: track.canvasUrl?.takeIf { it.startsWith("https://") }
        val wantedName = published?.let { nameFor(track, it, curated != null) }
        // A choice that changed: what shows gives way.
        _paths.value[track.id]?.let { current ->
            val name = File(current).nameWithoutExtension
            val stale = if (wantedName != null) name != wantedName else name.startsWith("kvov-") || name.startsWith("kvlink-")
            if (stale) { _paths.value = _paths.value - track.id; shared.remove(track.id) }
        }
        if (localPath(track.id) == null) fromDisk(ctx, track, wantedName)
        fun known() = localPath(track.id) != null && (track.id !in shared || knownWithout(ctx, track.id))
        fun recentlyFailed() = failed[track.id]?.let { System.currentTimeMillis() - it < 10 * 60_000L } == true
        localPath(track.id)?.let(::posterSoon)
        if (known() || (!force && recentlyFailed())) return@withContext
        setLoading(track.id, true)
        try {
            mutex.withLock {
                if (known()) return@withLock
                val prefs = prefs(ctx)
                if (published != null && wantedName != null) {
                    val target = File(dir(ctx), "$wantedName.mp4")
                    val max = if (wantedName.startsWith("kvlink-")) LINK_MAX_BYTES else Long.MAX_VALUE
                    if (!(target.isFile && target.length() > MIN_BYTES) && fetchFile(published, target, max) == null) {
                        failed[track.id] = System.currentTimeMillis(); return@withLock
                    }
                    prefs.edit().putString(track.id, wantedName).apply()
                    put(track.id, target, gen = gen)
                    return@withLock
                }
                val albumKey = albumKey(track.album, track.artist, track.id)
                val own = catalogueName(track.spotifyId) ?: catalogueName(prefs.getString(track.id, null))
                var file = own?.let { File(dir(ctx), "$it.mp4") }?.takeIf { it.isFile && it.length() > MIN_BYTES }
                if (file == null) {
                    val found = lookup(track.copy(spotifyId = own)) ?: run {
                        failed[track.id] = System.currentTimeMillis()
                        if (track.id in shared) prefs.edit().putLong(NONE_PREFIX + track.id, System.currentTimeMillis()).apply()
                        return@withLock
                    }
                    val target = File(dir(ctx), "${safe(found.spotifyId)}.mp4")
                    if (!(target.isFile && target.length() > MIN_BYTES) && fetchFile(found.url, target, Long.MAX_VALUE) == null) {
                        failed[track.id] = System.currentTimeMillis(); return@withLock
                    }
                    file = target
                }
                val edit = prefs.edit().putString(track.id, file.nameWithoutExtension)
                // The album's stand-in is the first Canvas found for it, not whichever title was asked last.
                val albumName = catalogueName(prefs.getString(albumKey, null))
                if (albumName == null || !File(dir(ctx), "$albumName.mp4").isFile) edit.putString(albumKey, file.nameWithoutExtension)
                edit.apply()
                put(track.id, file, gen = gen)
            }
        } catch (_: Exception) {
            failed[track.id] = System.currentTimeMillis()
        } finally { setLoading(track.id, false) }
    }

    /** The still kept for the Canvas file at [path], or null (a link, or not written yet). */
    fun posterOf(path: String?): File? {
        if (path == null || path.startsWith("https://")) return null
        val video = File(path)
        return File(video.parentFile, video.nameWithoutExtension + POSTER_SUFFIX).takeIf { it.isFile && it.length() > 0 }
    }

    /** Writes the still of [video] in the background when it has none. */
    internal fun posterSoon(video: File) {
        if (posterOf(video.absolutePath) != null || !postering.add(video.absolutePath)) return
        scope.launch { try { ensurePoster(video) } finally { postering.remove(video.absolutePath) } }
    }

    /** Writes the still of [video] now (off the UI thread): its first frame, at most 960 px long, as JPEG. */
    @Synchronized internal fun ensurePoster(video: File): File? {
        posterOf(video.absolutePath)?.let { return it }
        if (!video.isFile || video.length() <= MIN_BYTES) return null
        val frame = VideoFrames.firstFrame(video) ?: return null
        val target = File(video.parentFile, video.nameWithoutExtension + POSTER_SUFFIX)
        val tmp = File(video.parentFile, target.name + ".part")
        return try {
            val info = org.jetbrains.skia.ImageInfo(frame.width, frame.height, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.OPAQUE)
            org.jetbrains.skia.Image.makeRaster(info, frame.pixels, frame.width * 4).use { image ->
                val jpeg = image.encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, 82) ?: return null
                tmp.writeBytes(jpeg.bytes)
            }
            java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            _posters.value = _posters.value + 1
            target
        } catch (_: Exception) { tmp.delete(); null }
    }

    private fun fromDisk(ctx: Context, track: Track, wantedName: String?) {
        val dir = dir(ctx)
        fun usable(name: String?) = name?.let { File(dir, "$it.mp4") }?.takeIf { it.isFile && it.length() > MIN_BYTES }
        val prefs = prefs(ctx)
        if (wantedName != null) {
            usable(wantedName)?.let { prefs.edit().putString(track.id, wantedName).apply(); put(track.id, it) }
            return
        }
        val own = usable(catalogueName(track.spotifyId) ?: catalogueName(prefs.getString(track.id, null)))
        if (own != null) { put(track.id, own); return }
        usable(catalogueName(prefs.getString(albumKey(track.album, track.artist, track.id), null)))?.let { put(track.id, it, album = true) }
    }

    private fun put(id: String, file: File, album: Boolean = false, gen: Int = generation) {
        if (gen != generation) return
        if (album) shared.add(id) else shared.remove(id)
        _paths.value = _paths.value + (id to file.absolutePath)
        posterSoon(file)
    }

    private fun setLoading(id: String, on: Boolean) = synchronized(_loading) {
        _loading.value = if (on) _loading.value + id else _loading.value - id
    }

    private fun knownWithout(ctx: Context, id: String): Boolean =
        System.currentTimeMillis() - prefs(ctx).getLong(NONE_PREFIX + id, 0L) < NONE_FOR_MS

    private fun nameFor(track: Track, url: String, curated: Boolean): String = when {
        url.startsWith("https://") && "/storage/v1/object/public/ketvoice-public-media/" !in url -> "kvlink-" + sha(url)
        curated -> "kvov-" + sha(url)
        else -> "kvpub-" + (track.addonId ?: track.id).replace(Regex("[^A-Za-z0-9_-]"), "_").take(72)
    }

    private fun catalogueName(name: String?) = name?.takeUnless { it.isBlank() || it.startsWith("kvov-") || it.startsWith("kvpub-") || it.startsWith("kvlink-") }
    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
    private fun sha(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }

    private fun albumKey(album: String, artist: String, id: String = ""): String {
        val a = album.trim().lowercase(java.util.Locale.ROOT)
        val who = artist.substringBefore(',').trim().lowercase(java.util.Locale.ROOT)
        return if (a.isBlank()) "album:$id" else "album:$who:$a"
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dir(ctx: Context): File = File(ctx.filesDir, "canvas").apply { mkdirs() }

    /** Downloads [url] to [dest] through a `.part` file; only an MP4 (an `ftyp` box) of at least 10 kB is kept. */
    private fun download(url: String, dest: File, maxBytes: Long): File? {
        val tmp = File(dest.parentFile, dest.name + ".part")
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
                setRequestProperty("Accept", "video/mp4,video/*;q=0.9,*/*;q=0.8")
            }
            try {
                if (c.responseCode !in 200..299 || c.contentLengthLong > maxBytes) return null
                c.inputStream.use { input -> tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var total = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        total += n; if (total > maxBytes) return null
                        out.write(buf, 0, n)
                    }
                } }
            } finally { c.disconnect() }
            val head = ByteArray(8).also { b -> tmp.inputStream().use { it.read(b) } }
            if (tmp.length() < MIN_BYTES || String(head, 4, 4, Charsets.ISO_8859_1) != "ftyp") return null
            java.nio.file.Files.move(tmp.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            dest
        } catch (_: Exception) { null } finally { tmp.delete() }
    }
}
