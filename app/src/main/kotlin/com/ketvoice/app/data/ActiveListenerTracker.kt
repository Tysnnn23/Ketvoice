package com.ketvoice.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Sends anonymous playback heartbeats to the Ketvoice backend so the website
 * can show the real-time live listener count ("LISTENING NOW").
 */
object ActiveListenerTracker {
    private const val HEARTBEAT_INTERVAL_MS = 25_000L
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var loopJob: Job? = null

    private var clientId: String = ""
    @Volatile private var currentTrack: Track? = null
    @Volatile private var isPlaying: Boolean = false

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("ketvoice_listener_tracker", Context.MODE_PRIVATE)
        var cid = prefs.getString("anon_client_id", null)
        if (cid.isNullOrBlank()) {
            cid = UUID.randomUUID().toString()
            prefs.edit().putString("anon_client_id", cid).apply()
        }
        clientId = cid
    }

    fun onPlaybackStateChanged(track: Track?, playing: Boolean) {
        currentTrack = track
        isPlaying = playing && track != null

        if (isPlaying) {
            startLoop()
        } else {
            stopLoop()
        }
    }

    private fun startLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            // Immediate heartbeat when playback begins
            sendHeartbeat(active = true)
            while (isActive && isPlaying) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (isActive && isPlaying) {
                    sendHeartbeat(active = true)
                }
            }
        }
    }

    private fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
        scope.launch {
            // Notify server that listening paused/stopped
            sendHeartbeat(active = false)
        }
    }

    private suspend fun sendHeartbeat(active: Boolean) = withContext(Dispatchers.IO) {
        val cid = clientId.ifBlank { return@withContext }
        val track = currentTrack
        var conn: HttpURLConnection? = null
        try {
            val url = URL("${KetvoiceApi.BASE}/listeners/heartbeat")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("apikey", KetvoiceApi.ANON_KEY)
                setRequestProperty("Authorization", "Bearer ${KetvoiceApi.ANON_KEY}")
                setRequestProperty("User-Agent", "Ketvoice-Android")
            }

            val body = JSONObject().apply {
                put("clientId", cid)
                put("active", active)
                if (active && track != null) {
                    put("title", track.title.take(120))
                    put("artist", track.artist.take(120))
                    // Pochette pour l'affichage « Listening Now » du site :
                    // uniquement une URL http(s) publique, jamais un fichier local.
                    val cover = track.coverUrl
                    if (cover != null && cover.startsWith("https://")) put("cover", cover.take(500))
                }
            }

            conn.outputStream.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { it.write(body.toString()) }
            }
            conn.responseCode // execute
        } catch (_: Throwable) {
            // Network hiccups or offline silently ignored
        } finally {
            conn?.disconnect()
        }
    }
}
