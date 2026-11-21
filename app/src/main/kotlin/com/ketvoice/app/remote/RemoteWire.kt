package com.ketvoice.app.remote

import com.ketvoice.app.data.Track
import com.ketvoice.app.sync.SyncTracks
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Phone control's secret: 32 random bytes made once per account and kept in the account's own synced settings
 * (row level security lets only the account read them), so only the listener's own devices know it. The
 * Realtime room is named from it, and every message carries an HMAC made with a key drawn from it.
 */
object RemoteKey {
    private val shape = Regex("[A-Za-z0-9_-]{43}")
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun generate(random: SecureRandom = SecureRandom()): String = b64.encodeToString(ByteArray(32).also(random::nextBytes))
    fun valid(secret: String?): Boolean = secret != null && shape.matches(secret)

    /** `ketremote:<32 hex>`: a name nobody can guess without the secret. */
    fun room(secret: String): String = "ketremote:" + derive(secret, "room").joinToString("") { "%02x".format(it) }.take(32)
    fun macKey(secret: String): ByteArray = derive(secret, "mac")

    private fun derive(secret: String, label: String): ByteArray =
        hmac(Base64.getUrlDecoder().decode(secret), "ketvoice-remote-v1:$label".toByteArray())

    internal fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }
}

/**
 * Seals and opens the room's messages: `{v, b, s}` where `b` is the body's JSON text (type, sender, optional
 * recipient, time, nonce) and `s` its HMAC-SHA256. A message is dropped when its HMAC is wrong, when it is more
 * than [WINDOW_MS] away from this clock, when its nonce was already seen, or when it comes from this device or
 * is meant for another one.
 */
class RemoteSeal(private val key: ByteArray, val me: String, private val clock: () -> Long,
                 private val random: SecureRandom = SecureRandom()) {
    private val seen = LinkedHashMap<String, Long>()

    fun seal(type: String, body: JSONObject = JSONObject(), to: String? = null): JSONObject {
        body.put("t", type).put("f", me).put("ts", clock()).put("n", nonce())
        if (to != null) body.put("to", to)
        val text = body.toString()
        return JSONObject().put("v", VERSION).put("b", text).put("s", sign(text))
    }

    fun open(payload: JSONObject): JSONObject? {
        if (payload.optInt("v") != VERSION) return null
        val text = payload.optString("b")
        if (text.isEmpty() || text.length > MAX_BODY) return null
        if (!MessageDigest.isEqual(payload.optString("s").toByteArray(), sign(text).toByteArray())) return null
        val body = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val from = body.optString("f")
        if (!ID.matches(from) || from == me) return null
        val to = body.optString("to")
        if (to.isNotEmpty() && to != me) return null
        val now = clock()
        val ts = body.optLong("ts", Long.MIN_VALUE)
        if (ts == Long.MIN_VALUE || abs(now - ts) > WINDOW_MS) return null
        val nonce = body.optString("n")
        if (!NONCE.matches(nonce)) return null
        seen.entries.removeAll { now - it.value > 2 * WINDOW_MS }
        if (seen.put("$from/$nonce", ts) != null) return null
        while (seen.size > MAX_SEEN) seen.remove(seen.keys.first())
        return body
    }

    private fun sign(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(RemoteKey.hmac(key, text.toByteArray(Charsets.UTF_8)))

    private fun nonce(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(12).also(random::nextBytes))

    companion object {
        const val VERSION = 1
        const val WINDOW_MS = 60_000L
        const val MAX_BODY = 60_000
        const val MAX_SEEN = 2_000
        val ID = Regex("[A-Za-z0-9_-]{8,40}")
        private val NONCE = Regex("[A-Za-z0-9_-]{16}")
    }
}

/** What a device plays, as the others see it. [upcoming] is the next titles with their place in the queue. */
data class RemoteNow(val track: Track?, val playing: Boolean, val positionMs: Long, val durationMs: Long, val at: Long,
                     val volume: Float, val index: Int, val upcoming: List<RemoteTitle>) {
    fun positionAt(now: Long): Long {
        val moved = if (playing) positionMs + (now - at).coerceAtLeast(0) else positionMs
        return if (durationMs > 0) moved.coerceIn(0, durationMs) else moved.coerceAtLeast(0)
    }
}

data class RemoteTitle(val index: Int, val title: String, val artist: String)

/** One of the listener's other devices in the room. [kind]: `phone`, `tablet`, `quest` or `pc`. */
data class RemoteDevice(val id: String, val name: String, val kind: String, val now: RemoteNow?)

sealed interface RemoteOp {
    data object Toggle : RemoteOp
    data object Next : RemoteOp
    data object Previous : RemoteOp
    data class Seek(val ms: Long) : RemoteOp
    data class Volume(val level: Float) : RemoteOp
    data class Jump(val index: Int) : RemoteOp
    data class Remove(val index: Int) : RemoteOp
}

/** Playback moving from one device to another: the queue, where it stands and whether it plays. */
data class RemoteHandoff(val queue: List<Track>, val index: Int, val positionMs: Long, val play: Boolean)

/** This device's player as phone control reads it. */
data class LocalPlayback(val queue: List<Track>, val index: Int, val playing: Boolean, val positionMs: Long,
                         val durationMs: Long, val volume: Float) {
    val current: Track? get() = queue.getOrNull(index)
}

/** The body of each message type. Only playback state and commands travel; never an account detail. */
object RemoteWire {
    const val MAX_UPCOMING = 25
    const val MAX_HANDOFF = 150
    private val kinds = setOf("phone", "tablet", "quest", "pc")

    fun state(name: String, kind: String, p: LocalPlayback, at: Long): JSONObject = JSONObject()
        .put("name", name.take(40)).put("kind", kind)
        .put("track", p.current?.takeUnless { it.id.isBlank() }?.let(SyncTracks::json) ?: JSONObject.NULL)
        .put("playing", p.playing).put("pos", p.positionMs.coerceAtLeast(0)).put("dur", p.durationMs.coerceAtLeast(0)).put("at", at)
        .put("vol", p.volume.coerceIn(0f, 1f).toDouble()).put("idx", p.index)
        .put("next", JSONArray(p.queue.withIndex().drop(p.index + 1).take(MAX_UPCOMING).map {
            JSONObject().put("i", it.index).put("title", it.value.title.take(200)).put("artist", it.value.artist.take(200))
        }))

    fun device(id: String, body: JSONObject, fallbackName: String?): RemoteDevice? = runCatching {
        val track = body.optJSONObject("track")?.let { SyncTracks.parse(it.toString()) }
        val rows = body.optJSONArray("next") ?: JSONArray()
        val upcoming = (0 until minOf(rows.length(), MAX_UPCOMING)).mapNotNull { rows.optJSONObject(it) }.map {
            RemoteTitle(it.getInt("i"), it.optString("title").take(200), it.optString("artist").take(200))
        }
        val now = RemoteNow(track, body.optBoolean("playing"), body.optLong("pos").coerceAtLeast(0), body.optLong("dur").coerceAtLeast(0),
            body.optLong("at"), body.optDouble("vol", 1.0).toFloat().coerceIn(0f, 1f), body.optInt("idx", -1), upcoming)
        val name = com.ketvoice.app.together.Names.clean(body.optString("name")) ?: fallbackName ?: "?"
        RemoteDevice(id, name, body.optString("kind").takeIf { it in kinds } ?: "phone", now)
    }.getOrNull()

    fun op(op: RemoteOp): JSONObject = when (op) {
        RemoteOp.Toggle -> JSONObject().put("op", "toggle")
        RemoteOp.Next -> JSONObject().put("op", "next")
        RemoteOp.Previous -> JSONObject().put("op", "previous")
        is RemoteOp.Seek -> JSONObject().put("op", "seek").put("ms", op.ms)
        is RemoteOp.Volume -> JSONObject().put("op", "volume").put("v", op.level.toDouble())
        is RemoteOp.Jump -> JSONObject().put("op", "jump").put("i", op.index)
        is RemoteOp.Remove -> JSONObject().put("op", "remove").put("i", op.index)
    }

    fun op(body: JSONObject): RemoteOp? = when (body.optString("op")) {
        "toggle" -> RemoteOp.Toggle
        "next" -> RemoteOp.Next
        "previous" -> RemoteOp.Previous
        "seek" -> body.optLong("ms", -1).takeIf { it >= 0 }?.let(RemoteOp::Seek)
        "volume" -> body.optDouble("v", Double.NaN).takeIf { it in 0.0..1.0 }?.let { RemoteOp.Volume(it.toFloat()) }
        "jump" -> body.optInt("i", -1).takeIf { it >= 0 }?.let(RemoteOp::Jump)
        "remove" -> body.optInt("i", -1).takeIf { it >= 0 }?.let(RemoteOp::Remove)
        else -> null
    }

    /** The queue around the current title; this device's own files stay here (the other one cannot read them). */
    fun handoff(p: LocalPlayback): RemoteHandoff? {
        val current = p.current ?: return null
        if (current.id.startsWith("local:")) return null
        val kept = p.queue.withIndex().filterNot { it.value.id.startsWith("local:") }
        val at = kept.indexOfFirst { it.index == p.index }
        val from = (at - MAX_HANDOFF / 3).coerceAtLeast(0)
        val window = kept.subList(from, minOf(kept.size, from + MAX_HANDOFF)).map { it.value }
        return RemoteHandoff(window, at - from, p.positionMs.coerceAtLeast(0), p.playing)
    }

    fun handoff(h: RemoteHandoff): JSONObject = JSONObject().put("idx", h.index).put("pos", h.positionMs).put("play", h.play)
        .put("queue", JSONArray(h.queue.map(SyncTracks::json)))

    fun handoff(body: JSONObject): RemoteHandoff? = runCatching {
        val rows = body.getJSONArray("queue")
        val queue = (0 until minOf(rows.length(), MAX_HANDOFF)).mapNotNull { rows.optJSONObject(it)?.let { o -> SyncTracks.parse(o.toString()) } }
            .filterNot { it.id.startsWith("local:") }
        val index = body.getInt("idx")
        if (index !in queue.indices) return null
        RemoteHandoff(queue, index, body.optLong("pos").coerceAtLeast(0), body.optBoolean("play", true))
    }.getOrNull()
}
