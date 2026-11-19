package com.ketvoice.app.smartlib

import org.json.JSONObject

/**
 * A public SoundCloud set (`soundcloud.com/<compte>/sets/<nom>`), read from SoundCloud's mobile web
 * page. That page describes in full only its first titles (about 20) and names the others by an id
 * alone: those are left out and the listener is told the import is partial. ISRCs are kept when the
 * uploader published them.
 */
object SoundCloudImport {
    val hosts = setOf("soundcloud.com", "www.soundcloud.com", "m.soundcloud.com")
    const val SHORT_HOST = "on.soundcloud.com"
    private val name = Regex("[A-Za-z0-9_-]{1,100}")

    /** "<compte>/sets/<nom>" of a full link, or a message for the listener. A short link is expanded first. */
    fun setPath(link: String): String {
        val uri = PublicPage.uri(link)?.takeIf { it.host.lowercase() in hosts } ?: throw IllegalArgumentException(PublicPage.NOT_A_PLAYLIST)
        val parts = uri.path.orEmpty().trim('/').split('/')
        if (parts.size !in 3..4 || parts[1] != "sets" || !name.matches(parts[0]) || !name.matches(parts[2])) throw IllegalArgumentException(PublicPage.NOT_A_PLAYLIST)
        // A fourth part is the secret token of a private set's link.
        if (parts.size == 4 && !parts[3].matches(Regex("s-[A-Za-z0-9]{4,40}"))) throw IllegalArgumentException(PublicPage.NOT_A_PLAYLIST)
        return parts.joinToString("/")
    }

    fun isShort(link: String) = PublicPage.host(link) == SHORT_HOST

    fun pageUrl(path: String) = "https://m.soundcloud.com/$path"

    fun parse(html: String, path: String): ImportPreview {
        val entities = PublicPage.script(html, "__NEXT_DATA__")?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("initialStoreState")?.optJSONObject("entities")
            ?: throw IllegalStateException(PublicPage.CHANGED)
        val playlists = entities.optJSONObject("playlists") ?: throw IllegalStateException(PublicPage.NOT_FOUND)
        val set = playlists.keys().asSequence().mapNotNull { playlists.optJSONObject(it)?.optJSONObject("data") }
            .firstOrNull { it.optJSONArray("tracks") != null } ?: throw IllegalStateException(PublicPage.NOT_FOUND)
        if (set.optString("sharing", "public") != "public" && path.split('/').size < 4) throw IllegalStateException(PublicPage.NOT_FOUND)
        val tracks = entities.optJSONObject("tracks") ?: JSONObject()
        val users = entities.optJSONObject("users") ?: JSONObject()
        val refs = set.getJSONArray("tracks")
        val songs = (0 until refs.length()).mapNotNull { index ->
            val track = tracks.optJSONObject(refs.optString(index))?.optJSONObject("data") ?: return@mapNotNull null
            val title = PublicPage.clean(track.optString("title"))
            if (title.isBlank()) return@mapNotNull null
            val published = track.optJSONObject("publisher_metadata")
            val uploader = users.optJSONObject(track.optString("user"))?.optJSONObject("data")?.optString("username")
            val artist = PublicPage.clean(published?.optString("artist")?.takeIf { it.isNotBlank() && it != "null" } ?: uploader)
            if (artist.isBlank()) return@mapNotNull null
            val length = track.optLong("full_duration").takeIf { it > 0 } ?: track.optLong("duration")
            ImportSong("sc:${track.optLong("id")}", title, artist, length.coerceIn(0, 86_400_000),
                published?.optString("isrc")?.takeIf { it.matches(Regex("[A-Za-z]{2}[A-Za-z0-9]{3}\\d{7}")) })
        }
        val total = set.optInt("track_count").takeIf { it > 0 } ?: refs.length()
        if (songs.isEmpty()) throw IllegalStateException(if (total == 0) "Aucun titre accessible dans cette playlist" else PublicPage.CHANGED)
        val cover = PublicPage.cover(set.optString("artwork_url").replace("-large.", "-t500x500."), "sndcdn.com")
        return ImportPreview(ImportService.SoundCloud, PublicPage.clean(set.optString("title"), 80).ifBlank { "SoundCloud" },
            "https://soundcloud.com/$path", cover, songs.distinctBy { it.sourceId }, songs.size >= total, total)
    }
}
