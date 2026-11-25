package com.ketvoice.desktop.connect

import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.abs

/** What Discord shows of the title playing (a pure function of the player's state and the settings). */
object DiscordActivity {
    /** Discord's « Listening to ». */
    const val LISTENING = 2

    data class Key(val trackId: String, val startedAt: Long, val hide: Boolean, val share: String?)

    fun build(track: Track, positionMs: Long, durationMs: Long, now: Long, hideTitle: Boolean, shareUrl: String?): JSONObject {
        val start = now - positionMs.coerceAtLeast(0)
        val times = JSONObject().put("start", start)
        val activity = JSONObject().put("type", LISTENING).put("timestamps", times)
        if (hideTitle) return activity.put("details", AppText.t("Écoute de la musique")).put("state", "Ketvoice")
        val length = durationMs.takeIf { it > 0 } ?: track.durationMs
        if (length > 0) times.put("end", start + length)
        activity.put("details", text(track.title)).put("state", text(track.artist))
        val assets = JSONObject().put("large_text", text(track.album.ifBlank { track.title }))
        track.coverUrl?.takeIf { it.startsWith("https://") && it.length <= 256 }?.let { assets.put("large_image", it) }
        activity.put("assets", assets)
        if (shareUrl != null) activity.put("buttons", JSONArray().put(JSONObject().put("label", AppText.t("Écouter sur Ketvoice").take(32)).put("url", shareUrl)))
        return activity
    }

    /** Discord wants 2 to 128 characters. */
    fun text(value: String): String = value.trim().take(128).let { if (it.length < 2) it.padEnd(2, ' ') else it }
}

/** How Discord's status is doing, for « Connexions ». */
enum class DiscordStatus { OFF, WAITING, CONNECTED }

/**
 * « Statut Discord »: while a title plays (and the setting is on), Discord shows it; paused, stopped or switched off,
 * the activity is cleared. When Discord is not running, Ketvoice looks for it again every [RETRY_MS], so starting
 * Discord later is enough. Everything runs on one worker thread.
 */
object DiscordPresence {
    /** The Ketvoice Discord application (docs/discord-integration.md): public, the same as the account linking's. */
    const val CLIENT_ID = "1566454532890820668"
    private const val RETRY_MS = 15_000L
    private val _status = MutableStateFlow(DiscordStatus.OFF)
    val status: StateFlow<DiscordStatus> = _status
    private val _user = MutableStateFlow<String?>(null)
    val user: StateFlow<String?> = _user
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ketvoice-discord").apply { isDaemon = true } }
    private val dispatcher = worker.asCoroutineDispatcher()
    internal var ipc = DiscordIpc(CLIENT_ID)
    private var sent: DiscordActivity.Key? = null
    private var lastTry = 0L

    fun start(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                val wanted = wanted(System.currentTimeMillis())
                withContext(dispatcher) { apply(wanted) }
                delay(if (wanted == null && sent == null) 2_000 else 1_000)
            }
        }
    }

    /** The activity to show now, or null to show nothing. */
    internal fun wanted(now: Long): Pair<DiscordActivity.Key, JSONObject>? {
        if (!ConnectPrefs.discord.value) return null
        val s = Player.state.value
        val track = s.current ?: return null
        if (!s.isPlaying) return null
        val hide = ConnectPrefs.discordHideTitle.value
        val share = if (hide) null else ShareLinks.trackUrl(track)
        val key = DiscordActivity.Key(track.id, now - s.positionMs, hide, share)
        return key to DiscordActivity.build(track, s.positionMs, s.durationMs, now, hide, share)
    }

    /** On the worker: connect when needed, send only when what shows would change (Discord allows 5 updates per 20 s). */
    private fun apply(wanted: Pair<DiscordActivity.Key, JSONObject>?) {
        if (wanted == null) {
            if (sent != null && ipc.connected) ipc.send(null)
            sent = null
            _status.value = if (!ConnectPrefs.discord.value) DiscordStatus.OFF else if (ipc.connected) DiscordStatus.CONNECTED else DiscordStatus.WAITING
            return
        }
        if (!ipc.connected) {
            val now = System.currentTimeMillis()
            if (now - lastTry < RETRY_MS) { _status.value = DiscordStatus.WAITING; return }
            lastTry = now
            if (!ipc.connect()) { _status.value = DiscordStatus.WAITING; return }
            sent = null
        }
        _user.value = ipc.user
        if (!same(sent, wanted.first)) {
            if (ipc.send(wanted.second)) sent = wanted.first else sent = null
        }
        _status.value = if (ipc.connected) DiscordStatus.CONNECTED else DiscordStatus.WAITING
    }

    /** The same title, settings and start (within 3 s: the position drifts a little between two looks). */
    internal fun same(a: DiscordActivity.Key?, b: DiscordActivity.Key): Boolean =
        a != null && a.trackId == b.trackId && a.hide == b.hide && a.share == b.share && abs(a.startedAt - b.startedAt) < 3_000

    /** Quitting: Discord forgets the activity at once rather than when the pipe closes. */
    fun stop() {
        runCatching { worker.submit { if (ipc.connected) ipc.send(null); ipc.close() }.get(1, java.util.concurrent.TimeUnit.SECONDS) }
    }
}
