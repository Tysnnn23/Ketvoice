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

/**
 * CANVAS SPOTIFY via **Ketvoice API**.
 *
 * Le cookie `sp_dc`, le TOTP Spotify et la résolution de l'ID de piste sont
 * exécutés dans la fonction Supabase. L'application ne reçoit qu'une URL
 * `*.cnvs.mp4` signée, puis la conserve hors ligne dans
 * `filesDir/canvas/{spotifyId}.mp4`.
 *
 * Aucun cookie ni secret Spotify n'est stocké dans l'APK.
 */
object CanvasRepo {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val PREFS = "ketvoice_canvas"

    /** track.id Ketvoice → chemin local du MP4 déjà téléchargé. */
    private val _paths = MutableStateFlow<Map<String, String>>(emptyMap())
    val paths: StateFlow<Map<String, String>> = _paths

    private val mutex = Mutex()
    private var appContext: Context? = null
    private val failed = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Conservé pour les écrans qui forçaient l'ancien cache d'authentification. */
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

    /** Résout puis télécharge le canvas du morceau (retente après 10 min d'échec). */
    suspend fun resolve(track: Track) = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext
        // Le réglage « Pochette animée » coupe aussi le téléchargement.
        if (!AppSettings.animatedArtwork.value) return@withContext
        fun recentlyFailed() = failed[track.id]?.let {
            System.currentTimeMillis() - it < 10 * 60_000L
        } == true
        if (_paths.value.containsKey(track.id) || recentlyFailed()) return@withContext
        mutex.withLock {
            if (_paths.value.containsKey(track.id) || recentlyFailed()) return@withLock
            try {
                val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                var spotifyId = track.spotifyId ?: prefs.getString(track.id, null)
                var dest: File? = spotifyId?.let { File(canvasDir(ctx), "$it.mp4") }
                val alreadyDownloaded = dest?.let { it.exists() && it.length() > 10_000 } == true

                if (!alreadyDownloaded) {
                    if (!networkAllowed(ctx)) return@withLock
                    val resolved = KetvoiceApi.canvas(track.copy(spotifyId = spotifyId)) ?: run {
                        failed[track.id] = System.currentTimeMillis()
                        return@withLock
                    }
                    spotifyId = resolved.spotifyId
                    prefs.edit().putString(track.id, spotifyId).apply()
                    val target = File(canvasDir(ctx), "$spotifyId.mp4")
                    dest = target
                    if (!target.exists() || target.length() < 10_000) {
                        download(resolved.url, target) ?: run {
                            failed[track.id] = System.currentTimeMillis()
                            return@withLock
                        }
                    }
                }
                dest?.let { _paths.value = _paths.value + (track.id to it.absolutePath) }
            } catch (_: Exception) {
                failed[track.id] = System.currentTimeMillis()
            }
        }
    }

    /** Diagnostic : vérifie que Ketvoice API résout réellement un canvas. */
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

    // ----------------------------------------------------------- fichiers
    private fun canvasDir(context: Context): File =
        File(context.filesDir, "canvas").apply { if (!exists()) mkdirs() }

    private fun download(url: String, dest: File): File? {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 40_000
                setRequestProperty("User-Agent", UA)
            }
            if (c.responseCode !in 200..299) {
                c.disconnect()
                return null
            }
            val tmp = File(dest.parentFile, dest.name + ".part")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            c.disconnect()
            if (tmp.length() < 10_000) {
                tmp.delete()
                return null
            }
            if (dest.exists()) dest.delete()
            if (tmp.renameTo(dest)) dest else null
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
