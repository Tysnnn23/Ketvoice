package com.ketvoice.app.sync

import android.content.Context
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.account.ServerClock
import com.ketvoice.app.account.SupabaseHttp
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** What another of the listener's devices plays (or played last), offered here: « Reprendre ici ». */
data class RemoteSession(val deviceId: String, val deviceName: String, val queue: List<Track>, val index: Int,
                         val positionMs: Long, val playing: Boolean, val at: Long) {
    val track: Track get() = queue[index]

    /** Where the title is now: a title still playing there has moved on since it was written. */
    fun positionAt(now: Long): Long {
        val moved = if (playing) (now - at).coerceIn(0, 6 * 3600_000L) else 0L
        val end = track.durationMs.takeIf { it > 0 }?.minus(1_000) ?: Long.MAX_VALUE
        return (positionMs + moved).coerceIn(0, end.coerceAtLeast(0))
    }

    /** Still playing there: written by a device that was playing, less than a minute ago. */
    fun live(now: Long): Boolean = playing && now - at < 60_000L
}

internal object NowPlayingDoc {
    const val QUEUE_LIMIT = 50

    fun encode(state: PlayerUiState, deviceId: String, deviceName: String, at: Long): JSONObject? {
        val current = state.current ?: return null
        if (current.id.startsWith("local:")) return null
        val (queue, index) = window(state.queue, state.index)
        return JSONObject().put("device_id", deviceId).put("device_name", deviceName).put("playing", state.isPlaying)
            .put("position_ms", state.positionMs.coerceAtLeast(0)).put("at", at).put("index", index)
            .put("queue", JSONArray(queue.map(SyncTracks::json)))
    }

    /** The titles around the one playing (this device's own files left out), and where it now stands among them. */
    fun window(queue: List<Track>, index: Int): Pair<List<Track>, Int> {
        val playable = queue.withIndex().filter { !it.value.id.startsWith("local:") }
        val at = playable.indexOfFirst { it.index == index }.coerceAtLeast(0)
        val from = minOf(at - 5, playable.size - QUEUE_LIMIT).coerceAtLeast(0)
        val slice = playable.subList(from, minOf(playable.size, from + QUEUE_LIMIT)).map { it.value }
        return slice to (at - from)
    }

    fun decode(doc: JSONObject?): RemoteSession? = runCatching {
        doc ?: return null
        val rows = doc.getJSONArray("queue")
        val queue = (0 until minOf(rows.length(), QUEUE_LIMIT)).mapNotNull { SyncTracks.parse(rows.getJSONObject(it).toString()) }
        val index = doc.getInt("index")
        if (index !in queue.indices) return null
        RemoteSession(doc.getString("device_id"), doc.optString("device_name").take(40).ifBlank { "Appareil" }, queue, index,
            doc.optLong("position_ms").coerceAtLeast(0), doc.optBoolean("playing"), doc.optLong("at"))
    }.getOrNull()
}

/**
 * « Continuer sur cet appareil »: while signed in and playing, this device writes what it plays every
 * 15 s (and once when it pauses or changes title); another device reads it when it comes to the front or
 * shows Home, and offers to resume there. Never written while signed out.
 */
object NowPlayingSync {
    const val KIND = "now_playing"
    private const val EVERY_MS = 15_000L
    /** A session older than this is not offered any more. */
    const val OFFER_FOR_MS = 12L * 3600_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var publisher: Job? = null
    private var context: Context? = null
    private val _remote = MutableStateFlow<RemoteSession?>(null)
    val remote: StateFlow<RemoteSession?> = _remote
    private val dismissed = MutableStateFlow(0L)

    internal fun signedIn(context: Context) {
        this.context = context.applicationContext
        if (publisher == null) publisher = scope.launch {
            launch { watchPlaying() }
            publish()
        }
        refresh()
    }

    internal fun signedOut() {
        publisher?.cancel(); publisher = null
        _remote.value = null
    }

    private suspend fun publish() {
        var lastSent = 0L
        var lastKey = ""
        while (isActiveSession()) {
            val state = Player.state.value
            val key = (state.current?.id ?: "") + state.isPlaying
            val due = state.isPlaying && System.currentTimeMillis() - lastSent >= EVERY_MS
            if ((due || key != lastKey) && state.current != null) {
                if (runCatching { send(state) }.isSuccess) { lastSent = System.currentTimeMillis(); lastKey = key }
            }
            delay(if (state.isPlaying) 1_000L else 2_000L)
        }
    }

    private fun isActiveSession(): Boolean = AccountSession.userId() != null

    /** Playing a title here hides the offer to resume another device's. */
    private suspend fun watchPlaying() {
        Player.state.map { it.isPlaying }.distinctUntilChanged().collect { playing -> if (playing) _remote.value = null }
    }

    private suspend fun send(state: PlayerUiState) {
        val ctx = context ?: return
        val doc = NowPlayingDoc.encode(state.copy(positionMs = Player.positionMs.value), DeviceIdentity.id(ctx), DeviceIdentity.name(), ServerClock.now()) ?: return
        CloudLibrary.putNowPlaying(doc, DeviceIdentity.id(ctx))
    }

    /** Reads what the other devices play; called when the app comes to the front and while Home is shown. */
    fun refresh() {
        val ctx = context ?: return
        if (AccountSession.userId() == null) return
        scope.launch { runCatching { _remote.value = offer(fetch(), DeviceIdentity.id(ctx)) } }
    }

    private suspend fun fetch(): RemoteSession? {
        val uid = AccountSession.userId() ?: return null
        val response = AccountSession.authorized { token ->
            SupabaseHttp.request("GET", "/rest/v1/ketvoice_library?select=doc&user_id=eq.$uid&kind=eq.$KIND", token = token)
        }
        if (!response.ok) return null
        val rows = JSONArray(response.text)
        return if (rows.length() == 0) null else NowPlayingDoc.decode(rows.getJSONObject(0).optJSONObject("doc"))
    }

    /** Offered when it is another device's, recent, not dismissed, and this device is not playing that very title. */
    internal fun offer(session: RemoteSession?, myId: String, now: Long = ServerClock.now(),
                       local: PlayerUiState = Player.state.value, dismissedAt: Long = dismissed.value): RemoteSession? = session?.takeIf {
        it.deviceId != myId && now - it.at < OFFER_FOR_MS && it.at != dismissedAt &&
            !(local.isPlaying) && local.current?.id != it.track.id
    }

    fun dismiss() { _remote.value?.let { dismissed.value = it.at }; _remote.value = null }

    /** « Reprendre ici »: the same queue, the same title, where it is now. */
    fun resume(session: RemoteSession) {
        _remote.value = null
        Player.playTrack(session.track, session.queue, session.index, session.positionAt(ServerClock.now()))
    }

    /** For host renders: an offer shown without any network. */
    internal fun showForTest(session: RemoteSession?) { _remote.value = session }
}
