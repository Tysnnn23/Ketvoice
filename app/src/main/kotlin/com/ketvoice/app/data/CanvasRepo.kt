package com.ketvoice.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
    private var appContext: Context? = null
    private val failed = java.util.concurrent.ConcurrentHashMap<String, Long>()

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
    }

    suspend fun resolve(track: Track, force: Boolean = false): Unit = resolve(track, force, again = true)

    private suspend fun resolve(track: Track, force: Boolean, again: Boolean): Unit = withContext(Dispatchers.IO) {
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
            if (!givesWay(current, curated, curatedName)) return
            _paths.value = _paths.value - track.id
            if (currentName.startsWith("kvov-")) ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(track.id).apply()
            if (curatedName != null) failed.remove(track.id)
        }
        dropStale()
        if (localPath(track.id) != null || (!force && recentlyFailed())) return@withContext
        mutex.withLock {
            // Another resolution may have run while this one waited: what is shown is checked again against the choice of now.
            curated = MediaOverrides.canvas(track); curatedName = curated?.let { "kvov-" + sha(it) }
            dropStale()
            if (localPath(track.id) != null || (!force && recentlyFailed())) return@withLock
            try {
                val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                // A canvas published for the album by its owner takes priority:
                // it is the same file for every track of that album.
                val published = curated ?: track.canvasUrl?.takeIf { it.startsWith("https://") }
                // A Canvas the owner gave as a link to another site is streamed from there, never downloaded.
                if (published != null && streamedFromItsLink(published)) {
                    _paths.value = _paths.value + (track.id to published)
                    return@withLock
                }
                if (published != null) {
                    val name = curatedName ?: ("kvpub-" + (track.addonId ?: track.id)
                        .replace(Regex("[^A-Za-z0-9_-]"), "_").take(72))
                    val target = File(canvasDir(ctx), "$name.mp4")
                    if (target.exists() && target.length() > 10_000) {
                        prefs.edit().putString(track.id, name).apply()
                        _paths.value = _paths.value + (track.id to target.absolutePath)
                        return@withLock
                    }
                    if (!force && !networkAllowed(ctx)) return@withLock
                    if (download(published, target) == null) {
                        failed[track.id] = System.currentTimeMillis()
                        return@withLock
                    }
                    prefs.edit().putString(track.id, name).apply()
                    _paths.value = _paths.value + (track.id to target.absolutePath)
                    return@withLock
                }
                // A file named after an owner's choice ("kvov-…", or a published "kvpub-…") is never taken
                // for the catalogue's Canvas once that choice is gone: a downloaded title remembers its name.
                fun catalogueName(name: String?) = name?.takeUnless { it.startsWith("kvov-") || it.startsWith("kvpub-") }
                val albumKey = albumKey(track)
                val albumSpotifyId = catalogueName(prefs.getString(albumKey, null))
                var spotifyId = catalogueName(track.spotifyId) ?: catalogueName(prefs.getString(track.id, null))
                var dest: File? = spotifyId?.let { File(canvasDir(ctx), "$it.mp4") }

                if (dest?.exists() != true && spotifyId == null && albumSpotifyId != null) {
                    dest = File(canvasDir(ctx), "$albumSpotifyId.mp4")
                }
                val alreadyDownloaded = dest?.let { it.exists() && it.length() > 10_000 } == true

                if (!alreadyDownloaded) {
                    if (!force && !networkAllowed(ctx)) return@withLock
                    val resolved = KetvoiceApi.canvas(track.copy(spotifyId = spotifyId)) ?: run {
                        failed[track.id] = System.currentTimeMillis()
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
                        download(resolved.url, target) ?: run {
                            failed[track.id] = System.currentTimeMillis()
                            return@withLock
                        }
                    }
                }
                dest?.let {

                    prefs.edit().putString(track.id, it.nameWithoutExtension).apply()
                    _paths.value = _paths.value + (track.id to it.absolutePath)
                }
            } catch (_: Exception) {
                failed[track.id] = System.currentTimeMillis()
            }
        }
        // The owner changed the choice while this resolution ran: once more, for the choice of now.
        if (again && MediaOverrides.canvas(track) != curated) resolve(track, force, again = false)
    }

    /** True when the Canvas shown for a title ([current], a file or a link) is not the one the owner's choice ([curated]) calls for. */
    internal fun givesWay(current: String, curated: String?, curatedName: String?): Boolean {
        val name = File(current).nameWithoutExtension
        if (curated == null) return current.startsWith("https://") || name.startsWith("kvov-")
        return if (streamedFromItsLink(curated)) current != curated else name != curatedName
    }

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

    private fun albumKey(track: Track): String {
        val album = track.album.trim().lowercase(java.util.Locale.ROOT)
        val artist = track.artist.substringBefore(',').trim().lowercase(java.util.Locale.ROOT)
        return if (album.isBlank()) "album:${track.id}" else "album:$artist:$album"
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

    private fun download(url: String, dest: File): File? {
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
            val tmp = File(dest.parentFile, dest.name + ".part")
            if (tmp.exists()) tmp.delete()
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
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
