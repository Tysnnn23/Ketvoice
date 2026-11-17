package com.ketvoice.app.together

import com.ketvoice.app.data.Track
import com.ketvoice.app.handoff.HandoffMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ketogether (6.1, group listening): what travels between the phones of a session.
 *
 * Only catalogue identities leave the host (the same ones the Wi-Fi handoff accepts: `yt:`, `kv:upload:`,
 * `um:`/`last:`), with the title, artist, album, length and, from a few artwork hosts, the cover. Local files,
 * custom sources and anything private never leave: such a title is sent as "unavailable" without any detail.
 */
data class TrackRef(val id: String, val title: String, val artist: String, val album: String = "",
                    val durationMs: Long = 0L, val explicit: Boolean = false, val cover: String? = null) {

    /** The title as a guest's player resolves it, through its own sources (like a title received by handoff). */
    fun toTrack(): Track {
        val upload = id.startsWith("kv:upload:")
        val tag = if (upload) "upload" else id.removePrefix("kv:").substringBefore(':').takeIf { it in setOf("um", "last") }
        return Track(id, title, artist, album, durationMs = durationMs, explicit = explicit, coverUrl = cover,
            addon = tag, addonId = if (tag != null) id.substringAfterLast(':') else null)
    }

    fun json(): JSONObject = JSONObject().put("id", id).put("ti", title).put("ar", artist).put("al", album)
        .put("d", durationMs).put("x", explicit).apply { cover?.let { put("c", it) } }

    companion object {
        private val coverHosts = listOf("ytimg.com", "googleusercontent.com", "ggpht.com", "supabase.co", "mzstatic.com",
            "scdn.co", "dzcdn.net")

        fun shareable(track: Track): Boolean = HandoffMessage.compatible(track)

        /** A cover is passed on only from the usual public artwork hosts, over https. */
        fun publicCover(url: String?): String? {
            if (url == null || url.length > 500 || !url.startsWith("https://")) return null
            val host = url.removePrefix("https://").substringBefore('/').substringBefore(':').lowercase()
            if (host.isEmpty() || host.any { !(it.isLetterOrDigit() || it == '.' || it == '-') }) return null
            return url.takeIf { coverHosts.any { suffix -> host == suffix || host.endsWith(".$suffix") } }
        }

        /** Null for a title that must not leave this phone. */
        fun of(track: Track, cover: String? = track.coverUrl): TrackRef? {
            if (!shareable(track)) return null
            return runCatching {
                TrackRef(track.id, HandoffMessage.clean(track.title.take(200)).ifBlank { return null },
                    HandoffMessage.clean(track.artist.take(200)).ifBlank { return null }, HandoffMessage.clean(track.album.take(200)),
                    track.durationMs.coerceIn(0, 86_400_000), track.explicit, publicCover(cover))
            }.getOrNull()
        }

        fun decode(o: JSONObject): TrackRef {
            val id = o.getString("id").also { require(it.length <= 120 && HandoffMessage.compatible(Track(it, "", "", ""))) }
            val title = HandoffMessage.clean(o.getString("ti")).also { require(it.isNotBlank()) }
            val artist = HandoffMessage.clean(o.getString("ar")).also { require(it.isNotBlank()) }
            val album = HandoffMessage.clean(o.optString("al"))
            val duration = o.optLong("d", 0L).also { require(it in 0..86_400_000) }
            val cover = if (o.has("c")) publicCover(o.getString("c")) else null
            return TrackRef(id, title, artist, album, duration, o.optBoolean("x"), cover)
        }
    }
}

/** An entry of the shared queue; [ref] is null for a title only the host can play. */
data class QueueItem(val entry: String, val ref: TrackRef?, val by: String? = null)

/** A title a guest asked for, waiting for the host when « Tout le monde peut ajouter » is off. */
data class Proposal(val id: String, val from: String, val fromName: String, val ref: TrackRef)

/** The host's rules: who drives the playback, who adds titles without asking. */
data class Policy(val everyoneControls: Boolean = false, val everyoneAdds: Boolean = true)

data class Member(val id: String, val name: String, val host: Boolean, val me: Boolean = false)

/** A reaction seen in the session, kept a few seconds for the pill and the sheet. */
data class Reaction(val emoji: String, val name: String, val at: Long, val serial: Long)

enum class TogetherRole { Host, Guest }

enum class TogetherPhase { Idle, Connecting, Live, Reconnecting, Ended }

/** Everything the interface shows of a session. Notices are French source strings, translated when drawn. */
data class TogetherUi(
    val phase: TogetherPhase = TogetherPhase.Idle,
    val role: TogetherRole? = null,
    val code: String = "",
    val fingerprint: String = "",
    val members: List<Member> = emptyList(),
    val hostName: String = "",
    val nowPlaying: TrackRef? = null,
    /** False while the host plays a title the guests cannot have (a local file, a private source). */
    val available: Boolean = true,
    val playing: Boolean = false,
    val upcoming: List<QueueItem> = emptyList(),
    val pending: List<Proposal> = emptyList(),
    val policy: Policy = Policy(),
    val notice: String? = null,
    val reaction: Reaction? = null,
    val endReason: String? = null,
    val offsetMs: Long? = null,
    val rttMs: Long? = null,
    val driftMs: Long? = null
) {
    val active: Boolean get() = phase == TogetherPhase.Connecting || phase == TogetherPhase.Live || phase == TogetherPhase.Reconnecting
    val canControl: Boolean get() = role == TogetherRole.Host || policy.everyoneControls
}

/** The messages of a session. Host messages travel signed; see [TogetherWire]. */
sealed interface TogetherMsg {
    /** The whole session from the host: on every change, on a guest's hello and every half-minute. */
    data class State(val sid: String, val seq: Long, val at: Long, val hostName: String, val current: TrackRef?,
                     val entry: String?, val available: Boolean, val positionMs: Long, val playing: Boolean,
                     val buffering: Boolean, val upcoming: List<QueueItem>, val pending: List<Proposal>,
                     val policy: Policy, val qv: Int) : TogetherMsg
    /** The host's heartbeat: where it is now. */
    data class Tick(val sid: String, val seq: Long, val at: Long, val entry: String?, val positionMs: Long,
                    val playing: Boolean, val buffering: Boolean, val qv: Int) : TogetherMsg
    data class Pong(val sid: String, val seq: Long, val to: String, val client: Long, val host: Long) : TogetherMsg
    data class End(val sid: String, val seq: Long, val reason: String) : TogetherMsg
    data class Answer(val sid: String, val seq: Long, val to: String, val proposal: String, val accepted: Boolean) : TogetherMsg
    data class Hello(val name: String) : TogetherMsg
    data class Ping(val client: Long) : TogetherMsg
    data class Request(val action: String, val positionMs: Long = 0L) : TogetherMsg
    data class Propose(val id: String, val ref: TrackRef) : TogetherMsg
    data class React(val emoji: String) : TogetherMsg
}

/** Encoding of [TogetherMsg]; decoding throws on anything malformed, the caller drops it. */
object TogetherCodec {
    const val MAX_UPCOMING = 40
    const val MAX_PENDING = 20
    const val MAX_TEXT = 16_000
    val reactions = listOf("❤️", "🔥", "👏", "😂", "🎶", "😮")
    val actions = setOf("play", "pause", "next", "previous", "seek")
    private val token = Regex("[A-Za-z0-9_-]{1,40}")

    fun encode(msg: TogetherMsg): String = when (msg) {
        is TogetherMsg.State -> JSONObject().put("t", "state").put("sid", msg.sid).put("seq", msg.seq).put("at", msg.at)
            .put("hn", msg.hostName).put("av", msg.available).put("pos", msg.positionMs).put("pl", msg.playing)
            .put("bu", msg.buffering).put("qv", msg.qv)
            .put("pc", msg.policy.everyoneControls).put("pa", msg.policy.everyoneAdds)
            .apply { msg.current?.let { put("cur", it.json()) }; msg.entry?.let { put("e", it) } }
            .put("up", JSONArray().apply { msg.upcoming.take(MAX_UPCOMING).forEach { item ->
                put(JSONObject().put("e", item.entry).apply { item.ref?.let { put("r", it.json()) }; item.by?.let { put("by", it) } })
            } })
            .put("pe", JSONArray().apply { msg.pending.take(MAX_PENDING).forEach { p ->
                put(JSONObject().put("id", p.id).put("f", p.from).put("fn", p.fromName).put("r", p.ref.json()))
            } })
        is TogetherMsg.Tick -> JSONObject().put("t", "tick").put("sid", msg.sid).put("seq", msg.seq).put("at", msg.at)
            .put("pos", msg.positionMs).put("pl", msg.playing).put("bu", msg.buffering).put("qv", msg.qv)
            .apply { msg.entry?.let { put("e", it) } }
        is TogetherMsg.Pong -> JSONObject().put("t", "pong").put("sid", msg.sid).put("seq", msg.seq).put("to", msg.to)
            .put("c", msg.client).put("h", msg.host)
        is TogetherMsg.End -> JSONObject().put("t", "end").put("sid", msg.sid).put("seq", msg.seq).put("why", msg.reason)
        is TogetherMsg.Answer -> JSONObject().put("t", "answer").put("sid", msg.sid).put("seq", msg.seq).put("to", msg.to)
            .put("p", msg.proposal).put("ok", msg.accepted)
        is TogetherMsg.Hello -> JSONObject().put("t", "hello").put("n", msg.name)
        is TogetherMsg.Ping -> JSONObject().put("t", "ping").put("c", msg.client)
        is TogetherMsg.Request -> JSONObject().put("t", "req").put("a", msg.action).put("pos", msg.positionMs)
        is TogetherMsg.Propose -> JSONObject().put("t", "prop").put("id", msg.id).put("r", msg.ref.json())
        is TogetherMsg.React -> JSONObject().put("t", "react").put("e", msg.emoji)
    }.toString()

    private fun JSONObject.tokenOf(key: String): String = getString(key).also { require(token.matches(it)) }
    private fun JSONObject.seqOf(): Long = getLong("seq").also { require(it >= 0) }
    private fun JSONObject.bool(key: String): Boolean = get(key).let { require(it is Boolean); it as Boolean }
    private fun JSONObject.position(key: String): Long = getLong(key).also { require(it in 0..86_400_000) }

    fun decode(text: String): TogetherMsg {
        require(text.length <= MAX_TEXT)
        val o = JSONObject(text)
        return when (o.getString("t")) {
            "state" -> {
                val upcoming = o.getJSONArray("up").also { require(it.length() <= MAX_UPCOMING) }.let { rows ->
                    (0 until rows.length()).map { i ->
                        val row = rows.getJSONObject(i)
                        QueueItem(row.tokenOf("e"), row.optJSONObject("r")?.let(TrackRef::decode),
                            if (row.has("by")) Names.clean(row.getString("by")) else null)
                    }
                }
                val pending = o.getJSONArray("pe").also { require(it.length() <= MAX_PENDING) }.let { rows ->
                    (0 until rows.length()).map { i ->
                        val row = rows.getJSONObject(i)
                        Proposal(row.tokenOf("id"), row.tokenOf("f"), Names.clean(row.getString("fn")) ?: error("name"),
                            TrackRef.decode(row.getJSONObject("r")))
                    }
                }
                TogetherMsg.State(o.tokenOf("sid"), o.seqOf(), o.getLong("at"), Names.clean(o.getString("hn")) ?: error("host"),
                    o.optJSONObject("cur")?.let(TrackRef::decode), if (o.has("e")) o.tokenOf("e") else null, o.bool("av"),
                    o.position("pos"), o.bool("pl"), o.bool("bu"), upcoming, pending,
                    Policy(o.bool("pc"), o.bool("pa")), o.getInt("qv"))
            }
            "tick" -> TogetherMsg.Tick(o.tokenOf("sid"), o.seqOf(), o.getLong("at"), if (o.has("e")) o.tokenOf("e") else null,
                o.position("pos"), o.bool("pl"), o.bool("bu"), o.getInt("qv"))
            "pong" -> TogetherMsg.Pong(o.tokenOf("sid"), o.seqOf(), o.tokenOf("to"), o.getLong("c"), o.getLong("h"))
            "end" -> TogetherMsg.End(o.tokenOf("sid"), o.seqOf(), o.tokenOf("why"))
            "answer" -> TogetherMsg.Answer(o.tokenOf("sid"), o.seqOf(), o.tokenOf("to"), o.tokenOf("p"), o.bool("ok"))
            "hello" -> TogetherMsg.Hello(Names.clean(o.getString("n")) ?: error("name"))
            "ping" -> TogetherMsg.Ping(o.getLong("c"))
            "req" -> TogetherMsg.Request(o.getString("a").also { require(it in actions) }, o.optLong("pos", 0L).coerceIn(0, 86_400_000))
            "prop" -> TogetherMsg.Propose(o.tokenOf("id"), TrackRef.decode(o.getJSONObject("r")))
            "react" -> TogetherMsg.React(o.getString("e").also { require(it in reactions) })
            else -> error("unknown")
        }
    }
}

/** Names shown in a session: a Discord name or a nickname chosen once, never an e-mail address. */
object Names {
    const val MAX = 24
    private val email = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

    fun looksLikeEmail(text: String): Boolean = email.containsMatchIn(text)

    /** A clean name or null (blank, too long, or an e-mail address). */
    fun clean(raw: String): String? {
        if (raw.length > 64) return null
        val text = raw.filterNot { it.isISOControl() || it in '‪'..'‮' || it in '⁦'..'⁩' || it == '​' }
            .trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty() || looksLikeEmail(text)) return null
        return text.take(MAX)
    }

    /** One or two letters for an avatar. */
    fun initials(name: String): String {
        val words = name.split(' ').filter { it.isNotBlank() }
        val letters = if (words.size >= 2) listOf(words[0], words[1]).map { it.codePointAt(0) }
            else words.firstOrNull()?.let { listOf(it.codePointAt(0)) } ?: emptyList()
        return letters.joinToString("") { String(Character.toChars(it)) }.uppercase().ifEmpty { "?" }
    }
}
