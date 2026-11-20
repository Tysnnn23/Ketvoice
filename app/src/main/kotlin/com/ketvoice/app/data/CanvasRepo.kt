package com.ketvoice.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object CanvasRepo {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val PREFS = "ketvoice_canvas"
    private const val EXCLUDED_KEY = "canvas_excluded_tracks"

    private val _paths = MutableStateFlow<Map<String, String>>(emptyMap())
    val paths: StateFlow<Map<String, String>> = _paths

    // Morceaux exclus du Canvas à la demande (menu ••• du lecteur). Persisté :
    // l'exclusion survit au redémarrage, même si la vidéo est déjà téléchargée.
    private val _excluded = MutableStateFlow<Set<String>>(emptySet())
    val excluded: StateFlow<Set<String>> = _excluded

    fun isExcluded(trackId: String): Boolean = trackId in _excluded.value

    fun setExcluded(trackId: String, value: Boolean) {
        _excluded.value = if (value) _excluded.value + trackId else _excluded.value - trackId
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(EXCLUDED_KEY, _excluded.value).apply()
    }

    private val mutex = Mutex()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    /** A Canvas fetched from another site is kept up to this size; a larger one keeps streaming. */
    private const val LINK_MAX_BYTES = 40L * 1024 * 1024
    private val keeping = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var appContext: Context? = null
    private val failed = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Titles showing their album's Canvas (7.5) while their own, if any, is looked for. */
    private val shared = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** A title the catalogue knows no Canvas for is not asked again before this long (7.5). */
    internal const val NONE_FOR_MS = 3L * 24 * 60 * 60 * 1000
    /** The network, replaced by the host tests (a fake that counts requests and bytes). */
    internal var lookup: suspend (Track) -> KetvoiceApi.CanvasResult? = { KetvoiceApi.canvas(it) }
    internal var fetchFile: (String, File, Long) -> File? = { url, dest, max -> download(url, dest, max) }
    internal var mayFetch: (Context, Boolean) -> Boolean = { context, ahead -> mayDownload(context, ahead) }

    /** Forgets cached paths whose file was removed by the disk budget; they resolve again on demand. */
    fun dropMissing() {
        _paths.value = _paths.value.filterValues { File(it).isFile }
    }

    fun resetAuth() {
        failed.clear()
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _excluded.value = prefs.getStringSet(EXCLUDED_KEY, emptySet()).orEmpty().toSet()
            val dir = canvasDir(context.applicationContext)
            val map = mutableMapOf<String, String>()
            prefs.all.forEach { (key, value) ->
                if (value is String && value.isNotBlank()) {
                    val f = File(dir, "$value.mp4")
                    if (f.exists() && f.length() > 10_000) map[key] = f.absolutePath
                }
            }
            _paths.value = map
        }
        // 7.2: big Canvas are encoded again, smaller and as sharp, while the phone charges.
        if (android.os.Build.FINGERPRINT != "robolectric") runCatching { CanvasCompactWorker.schedule(context) }
    }

    /**
     * Looks for the Canvas of [track] and keeps it on the device. [ahead] is the Wi-Fi preload (6.2.3): it works
     * behind other apps too (the caller checked the conditions) but never on a metered network.
     */
    suspend fun resolve(track: Track, force: Boolean = false, ahead: Boolean = false): Unit {
        // A title that has its Canvas is not "loading": the pill of the player only shows while one is being fetched.
        // 7.5: a Canvas that is used is touched, so the cache limit lets the least used go first, not the oldest.
        localPath(track.id)?.let { DiskBudget.touch(it); return resolveNow(track, force, again = true, ahead = ahead) }
        setLoading(track.id, true)
        try { resolveNow(track, force, again = true, ahead = ahead) } finally { setLoading(track.id, false) }
    }

    /** Track ids whose Canvas is being looked up or downloaded now (6.2.3). */
    val loading: StateFlow<Set<String>> get() = _loading
    private val _loading = MutableStateFlow<Set<String>>(emptySet())
    private val loadingCount = HashMap<String, Int>()

    internal fun setLoading(id: String, on: Boolean) = synchronized(loadingCount) {
        val n = (loadingCount[id] ?: 0) + if (on) 1 else -1
        if (n > 0) loadingCount[id] = n else loadingCount.remove(id)
        _loading.value = loadingCount.keys.toSet()
    }

    /** Wi-Fi or Ethernet that is not metered: what the preload requires, whatever « Canvas en données mobiles » says. */
    fun unmetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    private suspend fun resolveNow(track: Track, force: Boolean, again: Boolean, ahead: Boolean): Unit = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext

        // The player's Canvas and the album page's are two separate settings: either is enough to look.
        if (!AppSettings.animatedArtwork.value && !AppSettings.albumHeroCanvas.value) return@withContext
        // Switched off by the owner for this title or its album, on the page and in the player: nothing is looked up or downloaded.
        if (MediaOverrides.canvasUnused(track)) {
            _paths.value = _paths.value - track.id
            return@withContext
        }
        // Exclusion utilisateur : jamais de Canvas pour ce morceau, ni résolution
        // ni lecture — seule la pochette statique est affichée.
        if (track.id in _excluded.value) return@withContext
        fun recentlyFailed() = failed[track.id]?.let {
            System.currentTimeMillis() - it < 10 * 60_000L
        } == true
        // A Canvas the owner chose for this title or its album replaces the catalogue's.
        // Its file name follows the URL, so a new choice is fetched and a restored
        // original resolves again from the catalogue.
        var curated = MediaOverrides.canvas(track)
        var curatedName = curated?.let { "kvov-" + sha(it) }
        // What is shown now gives way whenever it is not what the owner chose: the catalogue's Canvas
        // already on the device when a Canvas was chosen (it used to stay, the new one never shown),
        // an earlier choice, or a choice that was since removed.
        fun dropStale() {
            val current = _paths.value[track.id] ?: return
            val currentName = File(current).nameWithoutExtension
            if (!givesWay(current, curated, curatedName, track.canvasUrl)) return
            _paths.value = _paths.value - track.id; shared.remove(track.id)
            if (currentName.startsWith("kvov-") || currentName.startsWith("kvlink-")) ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(track.id).apply()
            if (curatedName != null) failed.remove(track.id)
        }
        dropStale()
        // 7.5: what is on the device shows at once, before the lock (another title's lookup may hold it for a minute)
        // and before any request. The album's Canvas stands in for a title whose own is not known yet.
        if (localPath(track.id) == null) fromDevice(ctx, track, curated, curatedName)
        fun known() = localPath(track.id) != null && (track.id !in shared || knownWithout(ctx, track.id))
        if (known() || (!force && recentlyFailed())) return@withContext
        mutex.withLock {
            // Another resolution may have run while this one waited: what is shown is checked again against the choice of now.
            curated = MediaOverrides.canvas(track); curatedName = curated?.let { "kvov-" + sha(it) }
            dropStale()
            if (known() || (!force && recentlyFailed())) return@withLock
            try {
                val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                // A canvas published for the album by its owner takes priority:
                // it is the same file for every track of that album.
                val published = curated ?: track.canvasUrl?.takeIf { it.startsWith("https://") }
                // A Canvas the owner gave as a link to another site: played from the link the first time, and kept on
                // the device once loaded (6.2.2), so it opens at once, online or not, and goes with a download.
                if (published != null && streamedFromItsLink(published)) {
                    val name = linkName(published)
                    val target = File(canvasDir(ctx), "$name.mp4")
                    if (target.exists() && target.length() > 10_000) {
                        prefs.edit().putString(track.id, name).apply()
                        put(track.id, target.absolutePath)
                        return@withLock
                    }
                    if (force) {
                        // A download keeps the Canvas with the song: it is fetched now, or the link stays as it was.
                        if (fetchFile(published, target, LINK_MAX_BYTES) != null) {
                            prefs.edit().putString(track.id, name).apply()
                            put(track.id, target.absolutePath)
                        } else put(track.id, published)
                        return@withLock
                    }
                    put(track.id, published)
                    if (networkAllowed(ctx)) this@CanvasRepo.scope.launch { keepLink(track.id, published, target, name) }
                    return@withLock
                }
                if (published != null) {
                    val name = curatedName ?: publishedName(track)
                    val target = File(canvasDir(ctx), "$name.mp4")
                    if (target.exists() && target.length() > 10_000) {
                        prefs.edit().putString(track.id, name).apply()
                        put(track.id, target.absolutePath)
                        return@withLock
                    }
                    // A Canvas only shows on screen: behind other apps it is not downloaded, so that the stream being heard keeps the network.
                    if (!force && !mayFetch(ctx, ahead)) return@withLock
                    if (fetchFile(published, target, Long.MAX_VALUE) == null) {
                        failed[track.id] = System.currentTimeMillis()
                        return@withLock
                    }
                    prefs.edit().putString(track.id, name).apply()
                    put(track.id, target.absolutePath)
                    return@withLock
                }
                // A file named after an owner's choice ("kvov-…", or a published "kvpub-…") is never taken
                // for the catalogue's Canvas once that choice is gone: a downloaded title remembers its name.
                fun catalogueName(name: String?) = name?.takeUnless { it.startsWith("kvov-") || it.startsWith("kvpub-") || it.startsWith("kvlink-") }
                val albumKey = albumKey(track)
                val albumSpotifyId = catalogueName(prefs.getString(albumKey, null))
                var spotifyId = catalogueName(track.spotifyId) ?: catalogueName(prefs.getString(track.id, null))
                var dest: File? = spotifyId?.let { File(canvasDir(ctx), "$it.mp4") }

                if (dest?.exists() != true && spotifyId == null && albumSpotifyId != null) {
                    dest = File(canvasDir(ctx), "$albumSpotifyId.mp4")
                }
                val alreadyDownloaded = dest?.let { it.exists() && it.length() > 10_000 } == true

                if (!alreadyDownloaded) {
                    // A Canvas only shows on screen: behind other apps it is not downloaded, so that the stream being heard keeps the network.
                    if (!force && !mayFetch(ctx, ahead)) return@withLock
                    val resolved = lookup(track.copy(spotifyId = spotifyId)) ?: run {
                        failed[track.id] = System.currentTimeMillis()
                        // Its album's Canvas stays; the catalogue is not asked again for this title for a while.
                        if (track.id in shared) prefs.edit().putLong(NONE_PREFIX + track.id, System.currentTimeMillis()).apply()
                        return@withLock
                    }
                    spotifyId = resolved.spotifyId
                    prefs.edit()
                        .putString(track.id, spotifyId)
                        .putString(albumKey, spotifyId)
                        .apply()
                    val target = File(canvasDir(ctx), "$spotifyId.mp4")
                    dest = target
                    if (!target.exists() || target.length() < 10_000) {
                        fetchFile(resolved.url, target, Long.MAX_VALUE) ?: run {
                            failed[track.id] = System.currentTimeMillis()
                            return@withLock
                        }
                    }
                }
                dest?.let {

                    prefs.edit().putString(track.id, it.nameWithoutExtension).apply()
                    put(track.id, it.absolutePath)
                }
            } catch (_: Exception) {
                failed[track.id] = System.currentTimeMillis()
            }
        }
        // The owner changed the choice while this resolution ran: once more, for the choice of now.
        if (again && MediaOverrides.canvas(track) != curated) resolveNow(track, force, again = false, ahead = ahead)
    }

    /**
     * Keeps a link's Canvas on the device while it plays from the link. When the file is there the title shows it
     * instead of the link (the video starts again once, from the beginning of its loop).
     */
    private suspend fun keepLink(trackId: String, url: String, target: File, name: String) {
        if (!keeping.add(name)) return
        try {
            if (fetchFile(url, target, LINK_MAX_BYTES) == null) return
            val ctx = appContext ?: return
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(trackId, name).apply()
            // Only while the title still shows this very link: a new choice made meanwhile is left alone.
            if (_paths.value[trackId] == url) put(trackId, target.absolutePath)
        } finally { keeping.remove(name) }
    }

    private const val NONE_PREFIX = "none:"

    /** Shows [path] for [trackId]: its own Canvas, unless [album] says it is its album's, standing in. */
    private fun put(trackId: String, path: String, album: Boolean = false) {
        if (album) shared.add(trackId) else shared.remove(trackId)
        _paths.value = _paths.value + (trackId to path)
    }

    /** The catalogue said lately that this title has no Canvas of its own. */
    private fun knownWithout(ctx: Context, trackId: String): Boolean =
        System.currentTimeMillis() - ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(NONE_PREFIX + trackId, 0L) < NONE_FOR_MS

    /**
     * The Canvas of [track] already on the device, shown without the network (7.5): the owner's choice or the
     * published Canvas kept as a file, the title's own catalogue Canvas, else its album's (one for all its titles).
     */
    private fun fromDevice(ctx: Context, track: Track, curated: String?, curatedName: String?) {
        val dir = canvasDir(ctx)
        fun usable(name: String?) = name?.let { File(dir, "$it.mp4") }?.takeIf { it.isFile && it.length() > 10_000 }
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val published = curated ?: track.canvasUrl?.takeIf { it.startsWith("https://") }
        if (published != null) {
            val name = if (streamedFromItsLink(published)) linkName(published) else curatedName ?: publishedName(track)
            usable(name)?.let { DiskBudget.touch(it); prefs.edit().putString(track.id, name).apply(); put(track.id, it.absolutePath) }
            return
        }
        fun catalogueName(name: String?) = name?.takeUnless { it.startsWith("kvov-") || it.startsWith("kvpub-") || it.startsWith("kvlink-") }
        val own = catalogueName(track.spotifyId) ?: catalogueName(prefs.getString(track.id, null))
        val album = catalogueName(prefs.getString(albumKey(track), null))
        val ownFile = usable(own)
        val file = ownFile ?: usable(album) ?: return
        DiskBudget.touch(file)
        // A title with no catalogue id of its own takes its album's for good, as before; one with an id may have its own.
        if (ownFile != null || own == null) { prefs.edit().putString(track.id, file.nameWithoutExtension).apply(); put(track.id, file.absolutePath) }
        else put(track.id, file.absolutePath, album = true)
    }

    private fun publishedName(track: Track): String = "kvpub-" + (track.addonId ?: track.id).replace(Regex("[^A-Za-z0-9_-]"), "_").take(72)

    /** A Canvas file received from another phone (Air Ket, 7.0) becomes the title's and its album's. */
    internal fun adopt(trackId: String, album: String, artist: String, name: String) {
        val ctx = appContext ?: return
        val file = File(canvasDir(ctx), "$name.mp4").takeIf { it.isFile && it.length() > 10_000 } ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(trackId, name).apply { if (album.isNotBlank() && !prefs.contains(albumKey(album, artist))) putString(albumKey(album, artist), name) }.apply()
        put(trackId, file.absolutePath)
    }

    /** The Canvas already on the device for an album (one for all its titles), or null. */
    fun albumFile(album: String, artist: String): File? {
        val ctx = appContext ?: return null
        val name = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(albumKey(album, artist), null) ?: return null
        if (name.startsWith("kvov-") || name.startsWith("kvpub-") || name.startsWith("kvlink-")) return null
        return File(canvasDir(ctx), "$name.mp4").takeIf { it.exists() && it.length() > 10_000 }
    }

    /** True when the Canvas shown for a title ([current], a file or a link) is not the one the owner's choice ([curated]) calls for. */
    internal fun givesWay(current: String, curated: String?, curatedName: String?, catalogueLink: String? = null): Boolean {
        val name = File(current).nameWithoutExtension
        // 7.5: a published Canvas given as a link and kept on the device is the catalogue's own, not a stale choice
        // (it was dropped and put back on every look, and the page waited behind the lock in between).
        if (curated == null && catalogueLink != null && streamedFromItsLink(catalogueLink) &&
            (current == catalogueLink || name == linkName(catalogueLink))) return false
        if (curated == null) return current.startsWith("https://") || name.startsWith("kvov-") || name.startsWith("kvlink-")
        // A link's Canvas kept on the device (kvlink-…) is the same choice as the link itself.
        return if (streamedFromItsLink(curated)) current != curated && name != linkName(curated) else name != curatedName
    }

    /** The file name under which a link's Canvas is kept: it follows the link, so a new link is a new file. */
    internal fun linkName(url: String): String = "kvlink-" + sha(url)

    /** A Canvas address outside Ketvoice's own storage: played from its link, whatever its size. */
    internal fun streamedFromItsLink(url: String): Boolean =
        url.startsWith("https://") && "/storage/v1/object/public/ketvoice-public-media/" !in url

    private fun sha(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }

    /** The Canvas to show for [track]: none while the owner has switched it off for this title or its album. */
    fun pathFor(track: Track): String? =
        if (MediaOverrides.canvasDisabled(track)) null else _paths.value[track.id]

    fun localPath(trackId: String): File? {
        val path = _paths.value[trackId] ?: return null
        return File(path).takeIf { it.exists() && it.length() > 10_000 }
    }

    private fun albumKey(track: Track): String = albumKey(track.album, track.artist, track.id)

    private fun albumKey(album: String, artist: String, id: String = ""): String {
        val a = album.trim().lowercase(java.util.Locale.ROOT)
        val who = artist.substringBefore(',').trim().lowercase(java.util.Locale.ROOT)
        return if (a.isBlank()) "album:$id" else "album:$who:$a"
    }

    fun testPipeline(): Boolean {
        return try {
            val test = Track(
                id = "__test_pipeline__",
                title = "Mr. Brightside",
                artist = "The Killers",
                album = "",
                durationMs = 222_000L
            )
            runBlocking { KetvoiceApi.canvas(test) != null }
        } catch (_: Exception) {
            false
        }
    }

    private fun canvasDir(context: Context): File =
        File(context.filesDir, "canvas").apply { if (!exists()) mkdirs() }

    private fun download(url: String, dest: File, maxBytes: Long = Long.MAX_VALUE): File? {
        return try {
            var current = URL(url)
            var c: HttpURLConnection? = null
            var code = 0

            for (hop in 0..5) {
                c = (current.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept", "video/mp4,video/*;q=0.9,*/*;q=0.8")
                }
                code = c.responseCode
                val location = c.getHeaderField("Location")
                if (code in listOf(301, 302, 303, 307, 308) && !location.isNullOrBlank()) {
                    c.disconnect()
                    current = URL(current, location)
                    continue
                }
                break
            }
            val conn = c ?: return null
            if (code !in 200..299) {
                conn.disconnect()
                return null
            }
            // A link of another site may be anything: one that announces more than the limit is left to stream.
            if (conn.contentLengthLong > maxBytes) { conn.disconnect(); return null }
            val tmp = File(dest.parentFile, dest.name + ".part")
            if (tmp.exists()) tmp.delete()
            conn.inputStream.use { input -> tmp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024); var total = 0L
                while (true) {
                    val read = input.read(buffer); if (read < 0) break
                    total += read; if (total > maxBytes) { tmp.delete(); return null }
                    out.write(buffer, 0, read)
                }
            } }
            conn.disconnect()
            if (tmp.length() < 10_000) {
                tmp.delete()
                return null
            }

            val head = ByteArray(16)
            tmp.inputStream().use { it.read(head) }
            val ftyp = head[4].toInt() == 0x66 && head[5].toInt() == 0x74 &&
                head[6].toInt() == 0x79 && head[7].toInt() == 0x70
            if (!ftyp) {
                tmp.delete()
                return null
            }
            if (dest.exists()) dest.delete()
            if (tmp.renameTo(dest)) dest else run {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
                dest.takeIf { it.exists() && it.length() > 10_000 }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** A Canvas only shows on screen, so it is fetched in front; ahead of time (preload) the caller's conditions decide, on an unmetered network only. */
    private fun mayDownload(context: Context, ahead: Boolean): Boolean =
        if (ahead) unmetered(context) else networkAllowed(context) && com.ketvoice.app.automix.Automix.appInFront

    private fun networkAllowed(context: Context): Boolean {
        if (AppSettings.canvasWithMobileData.value) return true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}
