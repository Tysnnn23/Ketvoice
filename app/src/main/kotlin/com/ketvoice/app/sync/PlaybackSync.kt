package com.ketvoice.app.sync

import org.json.JSONObject
import kotlin.math.abs

/*
 * Live sync between the listener's devices (Windows 2.0, shared so a later phone release can use it). Each device keeps
 * one row of `ketvoice_devices` (owner-only RLS): its name and kind, when it was last seen, and what it plays or played
 * last in the `now_playing` document's format ([NowPlayingDoc]). The phone of today writes only the account's single
 * `now_playing` document; both are read and merged here, newest first per device.
 */

/** One of the account's devices. [kind]: `phone`, `tablet`, `quest`, `pc` or `web`; [session] what it plays or played last. */
data class DeviceState(
    val deviceId: String,
    val name: String,
    val kind: String,
    /** The server's time (ms) of its last write. */
    val lastSeen: Long,
    val session: RemoteSession? = null,
    /** Signed out from another device: its session was revoked on the server. */
    val revoked: Boolean = false,
)

/** A row of `ketvoice_devices` to and from JSON (the `state` column holds the now-playing document plus `seen`). */
object DeviceRow {
    private val ID = Regex("[A-Za-z0-9-]{8,64}")
    val KINDS = setOf("phone", "tablet", "quest", "pc", "web")

    fun encode(deviceId: String, name: String, kind: String, seen: Long, sessionId: String?, playing: JSONObject?): JSONObject {
        require(ID.matches(deviceId) && kind in KINDS)
        val state = (playing ?: JSONObject()).put("seen", seen)
        return JSONObject().put("device_id", deviceId).put("name", clean(name)).put("kind", kind)
            .put("session_id", sessionId?.takeIf { UUID.matches(it) } ?: JSONObject.NULL).put("state", state)
    }

    fun decode(row: JSONObject): DeviceState? {
        val id = row.optString("device_id").takeIf(ID::matches) ?: return null
        val state = row.optJSONObject("state") ?: JSONObject()
        val kind = row.optString("kind").takeIf { it in KINDS } ?: "phone"
        val session = if (state.has("queue")) NowPlayingDoc.decode(state)?.copy(deviceId = id) else null
        return DeviceState(id, clean(row.optString("name")), kind, state.optLong("seen"), session,
            revoked = !row.isNull("revoked_at") && row.optString("revoked_at").isNotBlank())
    }

    fun clean(name: String): String = name.filterNot(Char::isISOControl).trim().take(40).ifBlank { "Appareil" }

    private val UUID = Regex("[0-9a-fA-F-]{36}")
}

/** What the devices' rows, the older single document and the live room say, merged into one list and one offer. */
object PlaybackMerge {
    /** Offered for this long after the other device last wrote (the phone's NowPlayingSync.OFFER_FOR_MS, 12 h). */
    const val OFFER_FOR_MS = 12L * 3600_000L

    /**
     * One entry per device: the newest session wins (by its own `at`), last seen is the latest heard of. The single
     * `now_playing` document comes from a device that may have no row yet (a phone of today): it is added as a phone.
     */
    fun devices(rows: List<DeviceState>, nowPlaying: RemoteSession?): List<DeviceState> {
        val byId = LinkedHashMap<String, DeviceState>()
        rows.forEach { row -> byId[row.deviceId] = byId[row.deviceId]?.let { merge(it, row) } ?: row }
        if (nowPlaying != null) {
            val doc = DeviceState(nowPlaying.deviceId, nowPlaying.deviceName, "phone", nowPlaying.at, nowPlaying)
            byId[doc.deviceId] = byId[doc.deviceId]?.let { merge(it, doc.copy(kind = it.kind, name = it.name)) } ?: doc
        }
        return byId.values.sortedByDescending { it.lastSeen }
    }

    fun merge(a: DeviceState, b: DeviceState): DeviceState {
        val newer = if (b.lastSeen >= a.lastSeen) b else a
        val session = listOfNotNull(a.session, b.session).maxByOrNull { it.at }
        return newer.copy(lastSeen = maxOf(a.lastSeen, b.lastSeen), session = session, revoked = a.revoked || b.revoked)
    }

    /**
     * « Reprendre là où tu en étais »: the most recent session of another device, written less than [OFFER_FOR_MS] ago,
     * not the one dismissed, while this device is not playing and does not already have that title.
     */
    fun offer(devices: List<DeviceState>, myId: String, now: Long, localPlaying: Boolean, localTrackId: String?,
              dismissedAt: Long = 0L): RemoteSession? {
        if (localPlaying) return null
        return devices.asSequence().filter { it.deviceId != myId && !it.revoked }.mapNotNull { it.session }
            .filter { now - it.at < OFFER_FOR_MS && it.at != dismissedAt && it.track.id != localTrackId }
            .maxByOrNull { it.at }
    }
}

/**
 * When this device writes its state: at once on another title, play/pause or a jump of more than [JUMP_MS]; every
 * [PLAYING_EVERY_MS] while playing; every [IDLE_EVERY_MS] otherwise, so « vu il y a … » stays true.
 */
object PublishPolicy {
    const val PLAYING_EVERY_MS = 15_000L
    const val IDLE_EVERY_MS = 300_000L
    const val JUMP_MS = 5_000L

    data class Mark(val trackId: String?, val playing: Boolean, val positionMs: Long, val at: Long)

    fun due(last: Mark?, now: Mark): Boolean {
        if (last == null || last.trackId != now.trackId || last.playing != now.playing) return true
        val elapsed = now.at - last.at
        val expected = if (now.playing) last.positionMs + elapsed else last.positionMs
        if (abs(now.positionMs - expected) > JUMP_MS) return true
        return elapsed >= if (now.playing) PLAYING_EVERY_MS else IDLE_EVERY_MS
    }
}
