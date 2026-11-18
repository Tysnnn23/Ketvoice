package com.ketvoice.app.sync

import com.ketvoice.app.account.RestHttp
import com.ketvoice.app.account.requireOk
import com.ketvoice.app.data.Track
import org.json.JSONArray
import org.json.JSONObject

/** What a line of the friends feed says happened. */
enum class FeedKind(val wire: String) {
    Published("published"), Added("added"), Listening("listening");

    companion object { fun of(wire: String): FeedKind? = entries.firstOrNull { it.wire == wire } }
}

/** The public playlist a line is about (its current name and cover, as the profile shows it). */
data class FeedPlaylist(val id: String, val name: String, val coverUrl: String?)

/**
 * A line of the friends feed, as `ketvoice_friends_feed` answers it: a handle and a display name, never an
 * e-mail address or a user id. [at] is the server's own time text, sent back unchanged to read the next page.
 */
data class FeedItem(val id: String, val kind: FeedKind, val at: String, val atMs: Long, val handle: String, val displayName: String,
                    val avatarRef: String?, val playlist: FeedPlaylist?, val track: Track?, val titles: Int,
                    val reactions: Map<String, Int>, val mine: String?) {
    val name: String get() = displayName.ifBlank { handle }
    val coverUrl: String? get() = playlist?.coverUrl ?: track?.coverUrl
}

/** The fixed set of reactions: the server stores the key, the app draws the emoji. */
object FeedReactions {
    val keys = listOf("heart", "fire", "clap", "wow", "music")
    private val emoji = mapOf("heart" to "❤️", "fire" to "🔥", "clap" to "👏", "wow" to "😮",
        "music" to "🎶")

    fun emoji(key: String): String = emoji[key].orEmpty()

    /** The caller's reaction moved from [item]'s current one to [next] (null takes it back): the counts follow. */
    fun apply(item: FeedItem, next: String?): FeedItem {
        val counts = item.reactions.toMutableMap()
        item.mine?.let { old -> counts[old] = (counts[old] ?: 1) - 1; if ((counts[old] ?: 0) <= 0) counts.remove(old) }
        next?.let { counts[it] = (counts[it] ?: 0) + 1 }
        return item.copy(reactions = counts, mine = next)
    }
}

/** A person in « Abonnés », « Abonnements » or « Bloqués »: [ref] is the follow or block line's own id. */
data class FollowPerson(val ref: String, val handle: String, val displayName: String, val avatarRef: String?,
                        val isPublic: Boolean, val followsBack: Boolean) {
    val name: String get() = displayName.ifBlank { handle.ifBlank { "Ketvoice" } }
}

data class FollowLists(val following: List<FollowPerson>, val followers: List<FollowPerson>, val blocked: List<FollowPerson>)

internal object FeedParser {
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val handle = Regex("[a-z0-9_.]{3,24}")

    fun page(text: String): List<FeedItem> = runCatching {
        val rows = JSONArray(text.ifBlank { "[]" })
        (0 until minOf(rows.length(), 100)).mapNotNull { rows.optJSONObject(it)?.let(::item) }
    }.getOrDefault(emptyList())

    fun item(json: JSONObject): FeedItem? = runCatching {
        val kind = FeedKind.of(json.getString("kind")) ?: return null
        val id = json.getString("id").takeIf { it.matches(uuid) } ?: return null
        val who = json.getString("handle").takeIf { it.matches(handle) } ?: return null
        val at = json.getString("at")
        val playlist = json.optJSONObject("playlist")?.let(::playlist)
        val track = json.optJSONObject("track")?.let { SyncTracks.parse(it.toString()) }
        if (kind == FeedKind.Listening && track == null || kind != FeedKind.Listening && playlist == null) return null
        FeedItem(id, kind, at, millis(at), who, json.optString("display_name").take(40), optUuid(json, "avatar_ref"), playlist, track,
            json.optInt("titles", 0).coerceIn(0, 200), reactions(json.optJSONObject("reactions")),
            json.optString("mine").takeIf { !json.isNull("mine") && it in FeedReactions.keys })
    }.getOrNull()

    private fun playlist(json: JSONObject): FeedPlaylist? = runCatching {
        FeedPlaylist(json.getString("id"), json.getString("name").take(80),
            json.optString("cover_url").takeIf { !json.isNull("cover_url") && it.startsWith("https://") && it.length <= 1000 })
    }.getOrNull()

    fun reactions(json: JSONObject?): Map<String, Int> =
        FeedReactions.keys.mapNotNull { key -> json?.optInt(key, 0)?.takeIf { it > 0 }?.let { key to it } }.toMap()

    private fun optUuid(json: JSONObject, key: String): String? = json.optString(key).takeIf { !json.isNull(key) && it.matches(uuid) }

    /** The server's time text (« 2026-11-18T10:00:00.123456+00:00 ») in milliseconds; 0 when it cannot be read. */
    fun millis(at: String): Long = runCatching { java.time.OffsetDateTime.parse(at).toInstant().toEpochMilli() }
        .recoverCatching { java.time.OffsetDateTime.parse(at.replace(' ', 'T')).toInstant().toEpochMilli() }.getOrDefault(0L)

    /** A cached line back to JSON (the same shape as the server's, so one parser reads both). */
    fun json(item: FeedItem): JSONObject = JSONObject().put("id", item.id).put("kind", item.kind.wire).put("at", item.at)
        .put("handle", item.handle).put("display_name", item.displayName).put("avatar_ref", item.avatarRef ?: JSONObject.NULL)
        .put("playlist", item.playlist?.let { JSONObject().put("id", it.id).put("name", it.name).put("cover_url", it.coverUrl ?: JSONObject.NULL) }
            ?: JSONObject.NULL)
        .put("track", item.track?.let(SyncTracks::json) ?: JSONObject.NULL).put("titles", item.titles)
        .put("reactions", JSONObject(item.reactions as Map<*, *>)).put("mine", item.mine ?: JSONObject.NULL)

    fun lists(text: String): FollowLists {
        val json = JSONObject(text)
        return FollowLists(people(json.optJSONArray("following")), people(json.optJSONArray("followers")), people(json.optJSONArray("blocked")))
    }

    private fun people(rows: JSONArray?): List<FollowPerson> = (0 until (rows?.length() ?: 0)).mapNotNull { index ->
        val row = rows!!.optJSONObject(index) ?: return@mapNotNull null
        val ref = optUuid(row, "ref") ?: return@mapNotNull null
        FollowPerson(ref, if (row.isNull("handle")) "" else row.optString("handle"), row.optString("display_name").take(40),
            optUuid(row, "avatar_ref"), row.optBoolean("is_public"), row.optBoolean("following"))
    }
}

/**
 * The friends feed's functions through PostgREST (migration of 2026-11-19). Every call is an RPC: the tables
 * themselves are closed to listeners. [http] is the real session, or a fake in tests.
 */
internal class FriendsClient(private val http: RestHttp) {
    private companion object { const val RPC = "/rest/v1/rpc/" }

    private suspend fun rpc(name: String, body: JSONObject, what: String): String =
        http.call("POST", RPC + name, body.toString(), emptyMap()).requireOk(what).text

    /** A page of the feed, newest first: [before] is the `at` of the last line already shown (null for the first page). */
    suspend fun feed(before: String?, limit: Int): List<FeedItem> =
        FeedParser.page(rpc("ketvoice_friends_feed", JSONObject().put("before", before ?: JSONObject.NULL).put("limit", limit), "Feed"))

    /** Sets (or with null takes back) the caller's reaction; answers the counts and the caller's own as the server has them. */
    suspend fun react(activity: String, reaction: String?): Pair<Map<String, Int>, String?>? {
        val json = JSONObject(rpc("ketvoice_react", JSONObject().put("p_activity", activity).put("p_reaction", reaction ?: JSONObject.NULL), "React"))
        if (json.optString("status") == "unavailable") return null
        return FeedParser.reactions(json.optJSONObject("reactions")) to json.optString("mine").takeIf { !json.isNull("mine") && it in FeedReactions.keys }
    }

    private fun handleBody(handle: String) = JSONObject().put("handle", handle.trim().removePrefix("@").lowercase())

    /** following, already, unavailable, self, needs_handle or limit. */
    suspend fun follow(handle: String): String = JSONObject(rpc("ketvoice_follow", handleBody(handle), "Follow")).optString("status")

    suspend fun unfollow(handle: String): String = JSONObject(rpc("ketvoice_unfollow", handleBody(handle), "Unfollow")).optString("status")

    suspend fun block(handle: String): String = JSONObject(rpc("ketvoice_block", handleBody(handle), "Block")).optString("status")

    suspend fun lists(): FollowLists = FeedParser.lists(rpc("ketvoice_follow_lists", JSONObject(), "Follow lists"))

    /** [action] is unfollow, remove, block or unblock; answers done, not_found or limit. */
    suspend fun manage(ref: String, action: String): String {
        require(action in setOf("unfollow", "remove", "block", "unblock"))
        return JSONObject(rpc("ketvoice_follow_manage", JSONObject().put("p_ref", ref).put("p_action", action), "Follow manage")).optString("status")
    }

    /** « Publier mon activité » on the listener's own profile row (row level security: only their own). */
    suspend fun shareActivity(user: String): Boolean? {
        val rows = JSONArray(http.call("GET", "/rest/v1/ketvoice_profiles?select=share_activity&id=eq.$user", null, emptyMap())
            .requireOk("Activity setting").text)
        return rows.optJSONObject(0)?.takeIf { it.has("share_activity") }?.optBoolean("share_activity")
    }

    suspend fun setShareActivity(user: String, on: Boolean) {
        http.call("PATCH", "/rest/v1/ketvoice_profiles?id=eq.$user", JSONObject().put("share_activity", on).toString(),
            mapOf("Prefer" to "return=minimal")).requireOk("Activity setting")
    }
}
