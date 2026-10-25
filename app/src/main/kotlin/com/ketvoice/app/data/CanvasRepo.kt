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

    private val _paths = MutableStateFlow<Map<String, String>>(emptyMap())
    val paths: StateFlow<Map<String, String>> = _paths

    private val mutex = Mutex()
    private var appContext: Context? = null
    private val failed = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun resetAuth() {
        failed.clear()
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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

    suspend fun resolve(track: Track, force: Boolean = false) = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext

        if (!AppSettings.animatedArtwork.value) return@withContext
        fun recentlyFailed() = failed[track.id]?.let {
            System.currentTimeMillis() - it < 10 * 60_000L
        } == true
        if (localPath(track.id) != null || (!force && recentlyFailed())) return@withContext
        mutex.withLock {
            if (localPath(track.id) != null || (!force && recentlyFailed())) return@withLock
            try {
                val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val albumKey = albumKey(track)
                val albumSpotifyId = prefs.getString(albumKey, null)
                var spotifyId = track.spotifyId ?: prefs.getString(track.id, null)
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
    }

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
