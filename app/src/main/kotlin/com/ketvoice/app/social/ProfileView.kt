package com.ketvoice.app.social

import com.ketvoice.app.data.Track
import com.ketvoice.app.together.TrackRef
import org.json.JSONArray
import org.json.JSONObject

/** An artist of the month as a profile shows it. */
data class TopArtist(val name: String, val cover: String? = null, val id: String? = null)

/** A playlist pinned on a profile: a snapshot behind a playlist link ([link] is that link's id). */
data class PinnedList(val name: String, val cover: String?, val count: Int, val link: String?)

/**
 * A profile as the server answered it (`ketvoice_profile_view` for a signed-in listener, `ketvoice_public_profile_card`
 * for anyone). Only what the viewer may see is in it; [hidden] is a profile that shows a stranger nothing but its handle.
 */
data class ProfileView(
    val handle: String,
    val relation: Relation = Relation.Stranger,
    val friend: FriendState = FriendState.None,
    val ref: String? = null,
    val hidden: Boolean = false,
    val displayName: String = "",
    val bio: String = "",
    val accent: String? = null,
    val hasAvatar: Boolean = false,
    val hasBanner: Boolean = false,
    val isPublic: Boolean = false,
    /** Each module the viewer may see, with its reach (the owner sees every one). */
    val reaches: Map<ProfileModule, Reach> = emptyMap(),
    val top: List<TopArtist> = emptyList(),
    val pinned: List<PinnedList> = emptyList(),
    val listening: Track? = null,
    val listeningNow: Boolean = false,
) {
    fun shows(module: ProfileModule): Boolean = module in reaches
}

object ProfileCodec {
    private val handle = Regex("[a-z0-9_.]{3,24}")
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /** Null for a null answer (unknown, blocked) or anything malformed. */
    fun view(o: JSONObject?): ProfileView? {
        val tag = o?.optString("handle")?.takeIf { handle.matches(it) } ?: return null
        val modules = o.optJSONObject("modules") ?: JSONObject()
        val reaches = ProfileModule.entries.mapNotNull { m -> modules.optJSONObject(m.wire)?.let { m to Reach.of(it.optString("reach")) } }.toMap()
        val listening = o.optJSONObject("listening")
        return ProfileView(tag, Relation.of(o.optString("relation")), FriendState.of(o.optString("friend")),
            o.optString("ref").takeIf { !o.isNull("ref") && uuid.matches(it) }, o.optBoolean("private"),
            SocialText.name(o.optString("display_name"), 60), ProfileRules.cleanBio(o.optString("bio")),
            ProfileRules.accent(o.optString("accent").takeIf { !o.isNull("accent") }), o.optBoolean("has_avatar"),
            o.optBoolean("has_banner"), o.optBoolean("is_public"), reaches,
            top(modules.optJSONObject("top")?.optJSONObject("data")), pinned(modules.optJSONObject("pinned")?.optJSONObject("data")),
            listening?.let(::track), listening?.optBoolean("playing") == true)
    }

    /** The title of a `listening` or presence object, if it can be played or shown. */
    fun track(o: JSONObject): Track? {
        val id = o.optString("id").takeIf { it.isNotBlank() && it.length <= 256 && !it.startsWith("local:") } ?: return null
        val title = SocialText.name(o.optString("title"), 300).ifBlank { return null }
        fun opt(key: String) = o.optString(key).takeIf { o.has(key) && !o.isNull(key) && it.isNotBlank() }
        return Track(id, title, SocialText.name(o.optString("artist"), 300), SocialText.name(o.optString("album"), 300),
            durationMs = o.optLong("durationMs", 0L).coerceIn(0, 86_400_000), coverUrl = TrackRef.publicCover(opt("coverUrl")),
            addon = opt("addon"), addonId = opt("addonId"), videoId = opt("videoId"), spotifyId = opt("spotifyId"), isrc = opt("isrc"),
            albumId = opt("albumId"), artistId = opt("artistId"))
    }

    fun top(data: JSONObject?): List<TopArtist> {
        val rows = data?.optJSONArray("items") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i -> rows.optJSONObject(i) }.mapNotNull { r ->
            val name = SocialText.name(r.optString("name"), 120).ifBlank { return@mapNotNull null }
            TopArtist(name, TrackRef.publicCover(r.optString("cover").takeIf { it.isNotBlank() }), r.optString("id").takeIf { it.isNotBlank() && it.length <= 64 })
        }.take(ProfileRules.TOP_MAX)
    }

    fun pinned(data: JSONObject?): List<PinnedList> {
        val rows = data?.optJSONArray("lists") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i -> rows.optJSONObject(i) }.mapNotNull { r ->
            val name = SocialText.name(r.optString("name"), 80).ifBlank { return@mapNotNull null }
            PinnedList(name, TrackRef.publicCover(r.optString("cover").takeIf { it.isNotBlank() }), r.optInt("count").coerceIn(0, 10_000),
                r.optString("link").takeIf { uuid.matches(it) })
        }.take(ProfileRules.PINNED_MAX)
    }

    /** The `top` module's data, from the listener's statistics of the month. */
    fun topData(artists: List<TopArtist>): JSONObject = JSONObject().put("items", JSONArray().apply {
        artists.take(ProfileRules.TOP_MAX).forEach { a ->
            put(JSONObject().put("name", SocialText.name(a.name, 120)).apply {
                TrackRef.publicCover(a.cover)?.let { put("cover", it) }; a.id?.takeIf { it.length <= 64 }?.let { put("id", it) }
            })
        }
    })

    /** The `pinned` module's data. */
    fun pinnedData(lists: List<PinnedList>): JSONObject = JSONObject().put("lists", JSONArray().apply {
        lists.take(ProfileRules.PINNED_MAX).forEach { p ->
            put(JSONObject().put("name", SocialText.name(p.name, 80)).put("count", p.count.coerceAtLeast(0)).apply {
                TrackRef.publicCover(p.cover)?.let { put("cover", it) }; p.link?.takeIf { uuid.matches(it) }?.let { put("link", it) }
            })
        }
    })
}
