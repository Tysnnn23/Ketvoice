package com.ketvoice.app.sync

import com.ketvoice.app.account.RestHttp
import com.ketvoice.app.account.RestRejected
import com.ketvoice.app.account.SupabaseHttp
import com.ketvoice.app.account.requireOk
import com.ketvoice.app.data.Track
import org.json.JSONArray
import org.json.JSONObject

/** A shared playlist's line in the server's list. */
internal data class SharedHead(val id: String, val name: String, val owner: String)

/** A person on a shared playlist, with what the members sheet shows. */
data class SharedMember(val userId: String, val owner: Boolean, val displayName: String, val handle: String,
                        val avatarPath: String, val isPublic: Boolean)

/** An invitation: the short code and when it stops working (an ISO time as the server wrote it). */
data class SharedInvite(val code: String, val expiresAt: String) {
    /** What the share sheet offers: opens Ketvoice on the playlist's invitation. */
    val link: String get() = "ketvoice://join/$code"
}

/** What joining with a code did: [status] is joined, already, invalid or full. */
data class JoinResult(val status: String, val playlistId: String?, val name: String?)

/** How a title travels to the server and back. */
internal object SharedTracks {
    private val core = setOf("id", "title", "artist", "album", "durationMs", "coverUrl")

    /** A title only this device has (its own files) cannot be heard by the others. */
    fun shareable(track: Track): Boolean = track.id.isNotBlank() && track.id.length <= 256 && !track.id.startsWith("local:") &&
        track.title.isNotBlank()

    /** The row to insert: the fields the app needs to show the title, the rest in `extra` (at most 2 KB). */
    fun body(list: String, row: String, track: Track, pos: Double, user: String): JSONObject {
        val full = SyncTracks.json(track)
        val extra = JSONObject()
        full.keys().forEach { key -> if (key !in core) extra.put(key, full.get(key)) }
        if (extra.toString().length > 1800) extra.keys().asSequence().toList().forEach { extra.remove(it) }
        return JSONObject().put("id", row).put("playlist_id", list).put("track_id", track.id)
            .put("title", track.title.take(300)).put("artist", track.artist.take(300)).put("album", track.album.take(300))
            .put("cover_url", full.optString("coverUrl").takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
            .put("duration_ms", track.durationMs.coerceIn(0L, 86_400_000L)).put("extra", extra)
            .put("added_by", user).put("pos", pos)
    }

    fun parse(row: JSONObject): SharedRow? = runCatching {
        val json = JSONObject()
        row.optJSONObject("extra")?.let { extra -> extra.keys().forEach { json.put(it, extra.get(it)) } }
        json.put("id", row.getString("track_id")).put("title", row.getString("title")).put("artist", row.optString("artist"))
            .put("album", row.optString("album")).put("durationMs", row.optLong("duration_ms"))
        if (!row.isNull("cover_url")) json.put("coverUrl", row.getString("cover_url"))
        val track = SyncTracks.parse(json.toString()) ?: return null
        SharedRow(row.getString("id"), track, row.optString("added_by").takeIf { !row.isNull("added_by") && it.isNotBlank() },
            row.optString("added_at"), row.getDouble("pos"))
    }.getOrNull()
}

/**
 * The shared playlists' tables and functions through PostgREST (see the migration of 2026-11-18): row
 * level security lets only members read and write. [http] is the real session, or a fake one in tests.
 */
internal class SharedClient(private val http: RestHttp, private val user: () -> String) {
    private companion object {
        const val PLAYLISTS = "/rest/v1/ketvoice_shared_playlists"
        const val TRACKS = "/rest/v1/ketvoice_shared_tracks"
        const val MEMBERS = "/rest/v1/ketvoice_shared_members"
        val MINIMAL = mapOf("Prefer" to "return=minimal")
        /** A title already on the playlist is not an error: whoever added it first wins. */
        val IGNORE_DUPLICATES = mapOf("Prefer" to "return=minimal,resolution=ignore-duplicates")
    }

    private fun q(value: String) = SupabaseHttp.encode(value)

    suspend fun lists(): List<SharedHead> {
        val response = http.call("GET", "$PLAYLISTS?select=id,name,owner_id&order=created_at.asc&limit=100", null, emptyMap())
            .requireOk("Shared playlists read")
        val rows = JSONArray(response.text)
        return (0 until rows.length()).map { rows.getJSONObject(it) }.map { SharedHead(it.getString("id"), it.getString("name"), it.getString("owner_id")) }
    }

    suspend fun rows(list: String): List<SharedRow> {
        val path = "$TRACKS?select=id,track_id,title,artist,album,cover_url,duration_ms,extra,added_by,added_at,pos" +
            "&playlist_id=eq.${q(list)}&order=pos.asc,added_at.asc,id.asc&limit=${SharedMerge.MAX_TRACKS}"
        val rows = JSONArray(http.call("GET", path, null, emptyMap()).requireOk("Shared titles read").text)
        return SharedMerge.ordered((0 until rows.length()).mapNotNull { SharedTracks.parse(rows.getJSONObject(it)) })
    }

    suspend fun members(list: String): List<SharedMember> {
        val body = JSONObject().put("p_playlist", list).toString()
        val rows = JSONArray(http.call("POST", "/rest/v1/rpc/ketvoice_shared_playlist_members", body, emptyMap())
            .requireOk("Members read").text)
        return (0 until rows.length()).map { rows.getJSONObject(it) }.map {
            SharedMember(it.getString("user_id"), it.optString("role") == "owner", it.optString("display_name"),
                if (it.isNull("handle")) "" else it.optString("handle"), if (it.isNull("avatar_path")) "" else it.optString("avatar_path"),
                it.optBoolean("is_public"))
        }
    }

    suspend fun invite(list: String): SharedInvite {
        val body = JSONObject().put("p_playlist", list).put("p_days", 7).toString()
        val json = JSONObject(http.call("POST", "/rest/v1/rpc/ketvoice_create_shared_invite", body, emptyMap()).requireOk("Invitation").text)
        return SharedInvite(json.getString("code"), json.optString("expires_at"))
    }

    /** Joins with a code. The server answers with a status; too many wrong codes are refused for a while. */
    suspend fun join(code: String): JoinResult {
        val body = JSONObject().put("code", code.trim().uppercase()).toString()
        val json = JSONObject(http.call("POST", "/rest/v1/rpc/ketvoice_join_shared_playlist", body, emptyMap()).requireOk("Join").text)
        return JoinResult(json.optString("status"), json.optString("playlist_id").ifBlank { null }, json.optString("name").ifBlank { null })
    }

    suspend fun removeMember(list: String, member: String) {
        http.call("DELETE", "$MEMBERS?playlist_id=eq.${q(list)}&user_id=eq.${q(member)}", null, MINIMAL).requireOk("Remove member")
    }

    /** Sends one write; Adds that follow each other come in [addAll]. A refusal is [RestRejected]. */
    suspend fun send(op: SharedOp) {
        when (op) {
            is SharedOp.Create -> http.call("POST", PLAYLISTS, JSONObject().put("id", op.list).put("owner_id", user()).put("name", op.name).toString(), MINIMAL)
                .let { if (it.status != 409) it.requireOk("Create") }
            is SharedOp.Add -> addAll(listOf(op))
            is SharedOp.Remove -> http.call("DELETE", "$TRACKS?id=eq.${q(op.row)}&playlist_id=eq.${q(op.list)}", null, MINIMAL).requireOk("Remove")
            is SharedOp.Move -> http.call("PATCH", "$TRACKS?id=eq.${q(op.row)}&playlist_id=eq.${q(op.list)}",
                JSONObject().put("pos", op.pos).toString(), MINIMAL).requireOk("Move")
            is SharedOp.Rename -> http.call("PATCH", "$PLAYLISTS?id=eq.${q(op.list)}", JSONObject().put("name", op.name).toString(), MINIMAL).requireOk("Rename")
            is SharedOp.Delete -> http.call("DELETE", "$PLAYLISTS?id=eq.${q(op.list)}", null, MINIMAL).requireOk("Delete")
            is SharedOp.Leave -> removeMember(op.list, user())
        }
    }

    /** Several titles of one playlist in one call (a whole playlist being shared). */
    suspend fun addAll(adds: List<SharedOp.Add>) {
        if (adds.isEmpty()) return
        val body = JSONArray(adds.map { SharedTracks.body(it.list, it.row, it.track, it.pos, user()) }).toString()
        http.call("POST", "$TRACKS?on_conflict=playlist_id,track_id", body, IGNORE_DUPLICATES).requireOk("Add")
    }
}
