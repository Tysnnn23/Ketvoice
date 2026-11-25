package com.ketvoice.desktop.connect

import android.content.Context
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.account.SupabaseHttp
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.scrobble.ListenBrainzBody
import com.ketvoice.app.scrobble.Scrobble
import com.ketvoice.app.scrobble.ScrobbleEvent
import com.ketvoice.app.scrobble.ScrobbleQueue
import com.ketvoice.app.scrobble.ScrobbleRetry
import com.ketvoice.app.scrobble.ScrobbleTrack
import com.ketvoice.app.scrobble.ScrobbleTracker
import com.ketvoice.app.util.moveOver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** One scrobbling service as the scrobbler drives it. */
internal interface ScrobbleService {
    val name: String
    /** Linked and switched on. */
    fun ready(): Boolean
    suspend fun nowPlaying(track: ScrobbleTrack)
    /** [Sent.Ok] removes them from the queue, [Sent.Drop] too (refused for good), [Sent.Retry] keeps them. */
    suspend fun send(items: List<Scrobble>): Sent
    fun synced()
}

internal enum class Sent { OK, DROP, RETRY }

/**
 * Scrobbling on Windows: follows the player ([ScrobbleTracker]'s rules), « en cours » to each linked service at the
 * start of a title, the listen once it counts, kept on disk until the service has it (offline, the service down).
 * Ketmix Studio's previews play on their own engine and never reach the player; while the lyrics editor is open,
 * or anything in [exclusions] says so, the play is left out.
 */
object Scrobbler {
    /** Moments that must never be scrobbled (the lyrics editor's preview, …), registered at start. */
    val exclusions = java.util.concurrent.CopyOnWriteArrayList<() -> Boolean>()
    private val tracker = ScrobbleTracker()
    internal val services = ArrayList<Pair<ScrobbleService, ScrobbleQueue>>()
    private val flushing = Mutex()
    private var failures = 0
    private var nextFlush = 0L

    fun start(context: Context, scope: CoroutineScope) {
        val dir = File(context.filesDir, "scrobbles").apply { mkdirs() }
        services.clear()
        services += ListenBrainz to queue(File(dir, "listenbrainz-v1.json"), 365L * 24 * 3600_000L)
        services += LastFm to queue(File(dir, "lastfm-v1.json"), 14L * 24 * 3600_000L)
        scope.launch(Dispatchers.Default) {
            while (true) {
                tick(System.currentTimeMillis())
                delay(1_000)
            }
        }
    }

    private fun queue(file: File, maxAge: Long) = ScrobbleQueue(
        { runCatching { file.takeIf(File::isFile)?.readText() }.getOrNull() },
        { text -> runCatching { File(file.parentFile, file.name + ".tmp").apply { writeText(text) }.moveOver(file) } }, maxAgeMs = maxAge)

    /** How many listens wait for [service]. */
    fun pending(service: String): Int = services.firstOrNull { it.first.name == service }?.second?.size ?: 0

    internal suspend fun tick(now: Long) {
        val s = Player.state.value
        val excluded = exclusions.any { runCatching(it).getOrDefault(false) }
        val events = tracker.update(s.current?.let(::of), s.isPlaying, s.positionMs, now, excluded)
        for (e in events) when (e) {
            is ScrobbleEvent.NowPlaying -> services.filter { it.first.ready() }.forEach { (svc, _) -> runCatching { svc.nowPlaying(e.track) } }
            is ScrobbleEvent.Listened -> { services.filter { it.first.ready() }.forEach { it.second.add(e.scrobble) }; nextFlush = 0 }
        }
        if (now >= nextFlush) flush(now)
    }

    fun of(track: Track): ScrobbleTrack = ScrobbleTrack(track.id, track.title, track.artist, track.album, track.durationMs)

    /** Sends what waits, 50 at a time; after a failure, waits longer before the next try. */
    suspend fun flush(now: Long = System.currentTimeMillis()) = flushing.withLock {
        var failed = false
        for ((svc, queue) in services) {
            if (!svc.ready()) continue
            while (true) {
                val batch = queue.peek(50, now)
                if (batch.isEmpty()) break
                when (runCatching { svc.send(batch) }.getOrDefault(Sent.RETRY)) {
                    Sent.OK -> { queue.remove(batch); svc.synced() }
                    Sent.DROP -> queue.remove(batch)
                    Sent.RETRY -> { failed = true; break }
                }
            }
        }
        failures = if (failed) failures + 1 else 0
        nextFlush = now + if (failed) ScrobbleRetry.delayMs(failures) else 60_000L
    }
}

private val http by lazy {
    OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
}
private val JSON_TYPE = "application/json".toMediaType()

/**
 * ListenBrainz with the listener's own user token (pasted once, kept sealed by DPAPI through [ConnectPrefs], never in
 * the preferences and never synced). https://listenbrainz.readthedocs.io/en/latest/users/api/
 */
internal object ListenBrainz : ScrobbleService {
    private const val API = "https://api.listenbrainz.org/1"
    override val name = "listenbrainz"

    override fun ready(): Boolean = ConnectPrefs.listenBrainzOn.value && ConnectPrefs.listenBrainzToken() != null

    /** Checks [token]; the ListenBrainz user's name when it is valid. */
    suspend fun validate(token: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$API/validate-token").header("Authorization", "Token ${token.trim()}").build()
        http.newCall(request).execute().use { r ->
            val json = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull()
            json?.takeIf { r.isSuccessful && it.optBoolean("valid") }?.optString("user_name")?.ifBlank { null }
        }
    }

    override suspend fun nowPlaying(track: ScrobbleTrack) { post(ListenBrainzBody.playingNow(track)) }

    override suspend fun send(items: List<Scrobble>): Sent = when (val code = post(ListenBrainzBody.listens(items))) {
        in 200..299 -> Sent.OK
        401 -> { ConnectPrefs.setListenBrainzToken(null); Sent.RETRY } // the token was revoked: kept until linked again
        400 -> Sent.DROP
        else -> Sent.RETRY
    }

    override fun synced() = ConnectPrefs.synced(lastfm = false)

    private suspend fun post(body: JSONObject): Int = withContext(Dispatchers.IO) {
        val token = ConnectPrefs.listenBrainzToken() ?: return@withContext 401
        val request = Request.Builder().url("$API/submit-listens").header("Authorization", "Token $token")
            .post(body.toString().toRequestBody(JSON_TYPE)).build()
        runCatching { http.newCall(request).execute().use { it.code } }.getOrDefault(0)
    }
}

/**
 * Last.fm through the `lastfm` edge function: the API key and shared secret stay on the server (Supabase secrets), and
 * so does the listener's Last.fm session key, stored per account. The listener must be signed in to Ketvoice.
 */
internal object LastFm : ScrobbleService {
    private const val PATH = "/functions/v1/lastfm"
    override val name = "lastfm"

    override fun ready(): Boolean = ConnectPrefs.lastfmOn.value && ConnectPrefs.lastfmUser.value.isNotBlank() && AccountSession.userId() != null

    private suspend fun call(action: String, body: JSONObject = JSONObject()): Pair<Int, JSONObject?> = withContext(Dispatchers.IO) {
        if (AccountSession.userId() == null) return@withContext 401 to null
        val r = runCatching {
            AccountSession.authorized { token ->
                SupabaseHttp.request("POST", "$PATH/$action", body.toString().toByteArray(), token)
            }
        }.getOrNull() ?: return@withContext 0 to null
        r.status to runCatching { JSONObject(r.text) }.getOrNull()
    }

    /** Step 1: the Last.fm page where the listener allows Ketvoice (its address carries the public API key only). */
    suspend fun begin(): String? = call("auth/start").second?.optString("url")?.takeIf { it.startsWith("https://www.last.fm/api/auth") }

    /** Step 2, after allowing: the Last.fm name, or null when it was not allowed (yet). */
    suspend fun complete(): String? {
        val name = call("auth/complete").second?.optString("name")?.takeIf { it.isNotBlank() } ?: return null
        ConnectPrefs.setText(ConnectPrefs.lastfmUser, "lastfmUser", name)
        return name
    }

    /** What the server knows (the name when linked); keeps the shown name in step. */
    suspend fun refresh() {
        val (status, json) = call("status")
        if (status == 200 && json != null) ConnectPrefs.setText(ConnectPrefs.lastfmUser, "lastfmUser", json.optString("name").takeIf { json.optBoolean("connected") }.orEmpty())
    }

    suspend fun disconnect(): Boolean {
        val ok = call("disconnect").first == 200
        if (ok) ConnectPrefs.setText(ConnectPrefs.lastfmUser, "lastfmUser", "")
        return ok
    }

    override suspend fun nowPlaying(track: ScrobbleTrack) { call("nowplaying", JSONObject().put("track", ScrobbleQueue.json(Scrobble(track, 0)))) }

    override suspend fun send(items: List<Scrobble>): Sent {
        val (status, _) = call("scrobble", JSONObject().put("scrobbles", JSONArray(items.map(ScrobbleQueue::json))))
        return when (status) {
            200 -> Sent.OK
            400, 422 -> Sent.DROP
            409 -> { ConnectPrefs.setText(ConnectPrefs.lastfmUser, "lastfmUser", ""); Sent.RETRY } // no longer linked
            else -> Sent.RETRY
        }
    }

    override fun synced() = ConnectPrefs.synced(lastfm = true)
}
