package com.ketvoice.app.social

import com.ketvoice.app.data.Track
import com.ketvoice.app.together.TogetherLink
import com.ketvoice.app.together.TrackRef
import org.json.JSONArray
import org.json.JSONObject

/** A friend who shares what they listen to: the title (null between titles) and the Ketogether room they are in. */
data class FriendNow(val handle: String, val name: String, val accent: String?, val hasAvatar: Boolean, val track: Track?,
                     val playing: Boolean, val room: TogetherLink.Target?)

/**
 * « Activité des amis » (opt-in on both sides, see 20261125120300_social_presence.sql): what this device writes while
 * « Partager mon écoute » is on, and how the friends' answer is read.
 */
object FriendsNow {
    private val handle = Regex("[a-z0-9_.]{3,24}")

    /** `ketvoice_friends_now()` → friends, newest first; anything malformed is left out. */
    fun parse(rows: JSONArray?): List<FriendNow> = (0 until (rows?.length() ?: 0)).mapNotNull { i ->
        val o = rows!!.optJSONObject(i) ?: return@mapNotNull null
        val tag = o.optString("handle").takeIf { handle.matches(it) } ?: return@mapNotNull null
        FriendNow(tag, SocialText.name(o.optString("display_name")).ifBlank { tag },
            ProfileRules.accent(o.optString("accent").takeIf { !o.isNull("accent") }), o.optBoolean("has_avatar"),
            o.optJSONObject("track")?.let(ProfileCodec::track), o.optBoolean("playing"),
            o.optString("room").takeIf { !o.isNull("room") && it.isNotBlank() }?.let(TogetherLink::parse)?.takeIf { it.fingerprint != null })
    }

    /** The title this device shares: catalogue fields only, never a local file (the server cleans it again). */
    fun trackJson(track: Track?): JSONObject? {
        val t = track?.takeIf { ShareUrls.shareableId(it.id) && it.title.isNotBlank() } ?: return null
        return JSONObject().put("id", t.id).put("title", SocialText.name(t.title, 300)).put("artist", SocialText.name(t.artist, 300))
            .put("album", SocialText.name(t.album, 300)).put("durationMs", t.durationMs.coerceIn(0, 86_400_000)).apply {
                TrackRef.publicCover(t.coverUrl)?.let { put("coverUrl", it) }
                t.videoId?.let { put("videoId", it) }; t.spotifyId?.let { put("spotifyId", it) }; t.isrc?.let { put("isrc", it) }
                t.addon?.let { put("addon", it) }; t.addonId?.let { put("addonId", it) }
                t.albumId?.let { put("albumId", it) }; t.artistId?.let { put("artistId", it) }
            }
    }

    /** The room as the server stores it (`CODE-FINGERPRINT`), only for a session with a known host key. */
    fun roomWire(code: String?, fingerprint: String?): String? =
        if (code.isNullOrBlank() || fingerprint.isNullOrBlank()) null else TogetherLink.parse("$code-$fingerprint")?.let { "${it.code}-${it.fingerprint}" }

    /** What is worth writing again: a new title, play/pause, another room, or a minute since the last write. */
    fun due(last: Snapshot?, next: Snapshot, now: Long, refreshMs: Long = 60_000L): Boolean =
        last == null || last.trackId != next.trackId || last.playing != next.playing || last.room != next.room || now - last.at >= refreshMs

    data class Snapshot(val trackId: String?, val playing: Boolean, val room: String?, val at: Long)
}
