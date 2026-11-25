package com.ketvoice.app.scrobble

import org.json.JSONArray
import org.json.JSONObject

/*
 * Scrobbling (Last.fm and ListenBrainz), shared so a later phone release can use it. Pure: the platform feeds the
 * player's state and sends what comes out. Rules: « en cours » when a title starts; a scrobble once half of it, or
 * 4 minutes, has really been heard (seeking forward does not count); titles under 30 s are never scrobbled; a play
 * that went through an excluded moment (a Ketmix preview, the lyrics editor) is never scrobbled.
 */

/** What a service needs of a title. [durationMs] 0 when unknown. */
data class ScrobbleTrack(val id: String, val title: String, val artist: String, val album: String = "", val durationMs: Long = 0L)

/** One listen to send: the title and when it started (epoch ms). */
data class Scrobble(val track: ScrobbleTrack, val startedAt: Long)

sealed interface ScrobbleEvent {
    data class NowPlaying(val track: ScrobbleTrack) : ScrobbleEvent
    data class Listened(val scrobble: Scrobble) : ScrobbleEvent
}

object ScrobbleRules {
    const val MIN_TRACK_MS = 30_000L
    const val MAX_THRESHOLD_MS = 240_000L

    /** A title can be scrobbled: named, by a named artist, and not under 30 s (an unknown length is allowed). */
    fun eligible(track: ScrobbleTrack): Boolean =
        track.title.isNotBlank() && track.artist.isNotBlank() && (track.durationMs <= 0 || track.durationMs >= MIN_TRACK_MS)

    /** How long it must really be heard: half of it, at most 4 minutes (4 minutes when its length is unknown). */
    fun threshold(durationMs: Long): Long = if (durationMs > 0) minOf(durationMs / 2, MAX_THRESHOLD_MS) else MAX_THRESHOLD_MS
}

/**
 * Follows the title being played and how long it was really heard. Feed it the player's state a few times a second
 * ([update]); it answers with « now playing » once per play and the scrobble once the rules allow it.
 */
class ScrobbleTracker {
    private var track: ScrobbleTrack? = null
    private var startedAt = 0L
    private var heard = 0L
    private var lastPos = 0L
    private var lastAt = 0L
    private var announced = false
    private var done = false
    private var tainted = false

    /** Heard so far of the current play (tests). */
    val heardMs: Long get() = heard

    /**
     * [excluded] marks a moment that must never count (a Ketmix preview, the lyrics editor's preview): heard during
     * such a moment, the whole play is left out, even if the listener keeps listening afterwards.
     */
    fun update(current: ScrobbleTrack?, playing: Boolean, positionMs: Long, now: Long, excluded: Boolean = false): List<ScrobbleEvent> {
        if (current?.id != track?.id) start(current, positionMs, now)
        else if (restarted(positionMs)) start(current, positionMs, now)
        val t = track ?: return emptyList()
        if (excluded && playing) tainted = true
        if (playing) heard += progress(positionMs, now)
        lastPos = positionMs
        lastAt = now
        if (tainted || !ScrobbleRules.eligible(t)) return emptyList()
        val out = ArrayList<ScrobbleEvent>(2)
        if (playing && !announced) { announced = true; out += ScrobbleEvent.NowPlaying(t) }
        if (!done && heard >= ScrobbleRules.threshold(t.durationMs)) { done = true; out += ScrobbleEvent.Listened(Scrobble(t, startedAt)) }
        return out
    }

    private fun start(current: ScrobbleTrack?, positionMs: Long, now: Long) {
        track = current
        startedAt = now - positionMs.coerceAtLeast(0)
        heard = 0; lastPos = positionMs; lastAt = now
        announced = false; done = false; tainted = false
    }

    /** The same title begun again (repeat one, or « précédent » to its start) after it was scrobbled. */
    private fun restarted(positionMs: Long): Boolean = done && positionMs < 5_000 && lastPos - positionMs > 10_000

    /** Wall time since the last look, but never more than the position moved: a seek forward is not listening. */
    private fun progress(positionMs: Long, now: Long): Long {
        val wall = (now - lastAt).coerceIn(0, 10_000)
        val moved = positionMs - lastPos
        return if (moved <= 0) 0 else minOf(wall + 1_500, moved)
    }
}

/**
 * Scrobbles waiting to be sent (offline, or the service failed), kept in a JSON file through [load]/[save], oldest
 * first. At most [max] are kept (the oldest go first); [maxAgeMs] drops what the service would refuse (Last.fm ignores
 * listens older than 14 days).
 */
class ScrobbleQueue(private val load: () -> String?, private val save: (String) -> Unit, val max: Int = 2_000,
                    val maxAgeMs: Long = 14L * 24 * 3600_000L) {
    private val items: MutableList<Scrobble> = read().toMutableList()

    val size: Int get() = synchronized(items) { items.size }

    fun add(item: Scrobble) = synchronized(items) {
        if (items.any { it.track.id == item.track.id && it.startedAt == item.startedAt }) return
        items += item
        while (items.size > max) items.removeAt(0)
        write()
    }

    /** The oldest [count] still worth sending at [now]. */
    fun peek(count: Int, now: Long): List<Scrobble> = synchronized(items) {
        if (items.removeAll { now - it.startedAt > maxAgeMs }) write()
        items.take(count)
    }

    /** [sent] reached the service (or it refused them for good): they leave the queue. */
    fun remove(sent: List<Scrobble>) = synchronized(items) {
        if (items.removeAll { it in sent }) write()
    }

    private fun write() = save(JSONArray(items.map(::json)).toString())

    private fun read(): List<Scrobble> = runCatching {
        val rows = JSONArray(load() ?: return emptyList())
        (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(::parse) }
    }.getOrDefault(emptyList())

    companion object {
        fun json(s: Scrobble): JSONObject = JSONObject().put("id", s.track.id).put("title", s.track.title).put("artist", s.track.artist)
            .put("album", s.track.album).put("durationMs", s.track.durationMs).put("at", s.startedAt)

        fun parse(o: JSONObject): Scrobble? {
            val t = ScrobbleTrack(o.optString("id"), o.optString("title"), o.optString("artist"), o.optString("album"), o.optLong("durationMs"))
            val at = o.optLong("at")
            return if (t.id.isBlank() || at <= 0 || !ScrobbleRules.eligible(t)) null else Scrobble(t, at)
        }
    }
}

/** When to try sending the queue again after [failures] failures in a row: 30 s, doubling, at most 30 min. */
object ScrobbleRetry {
    fun delayMs(failures: Int): Long = if (failures <= 0) 0L else minOf(30_000L shl minOf(failures - 1, 6), 30 * 60_000L)
}

/** ListenBrainz's submit-listens body (https://listenbrainz.readthedocs.io, « JSON documentation »). */
object ListenBrainzBody {
    fun playingNow(t: ScrobbleTrack): JSONObject =
        JSONObject().put("listen_type", "playing_now").put("payload", JSONArray().put(JSONObject().put("track_metadata", meta(t))))

    /** One listen is `single`, several (a queue flushed) `import`. */
    fun listens(items: List<Scrobble>): JSONObject = JSONObject().put("listen_type", if (items.size == 1) "single" else "import")
        .put("payload", JSONArray(items.map { JSONObject().put("listened_at", it.startedAt / 1000).put("track_metadata", meta(it.track)) }))

    private fun meta(t: ScrobbleTrack): JSONObject {
        val info = JSONObject().put("media_player", "Ketvoice").put("submission_client", "Ketvoice")
        if (t.durationMs > 0) info.put("duration_ms", t.durationMs)
        val m = JSONObject().put("artist_name", t.artist.take(500)).put("track_name", t.title.take(500)).put("additional_info", info)
        if (t.album.isNotBlank()) m.put("release_name", t.album.take(500))
        return m
    }
}
