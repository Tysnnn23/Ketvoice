package com.ketvoice.app.social

import com.ketvoice.app.together.TogetherLink
import com.ketvoice.app.together.TrackRef
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Smart share links (Windows 2.0): pages of the website with a cover, a title and « Ouvrir dans Ketvoice », which
 * open the app through its `ketvoice://` links (the phone's, unchanged: `listen?track&title&artist&kind`, `u/<handle>`,
 * `join/<code>`). The website's `/listen` page and its `/album/MPRE…`, `/u/<handle>` pages already exist; a playlist
 * travels as a playlist link (`listen?playlist=<id>&name=…`, its snapshot read with `ketvoice_link_playlist`).
 */
object ShareUrls {
    const val SITE = "https://ketvoice-web.vercel.app"
    private val catalogAlbum = Regex("MPRE[A-Za-z0-9_-]{6,60}")
    private val catalogArtist = Regex("UC[A-Za-z0-9_-]{10,40}")
    private val handle = Regex("[a-z0-9_.]{3,24}")
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /** A title the link can name: never a file of this computer, never an empty one. */
    fun shareableId(id: String): Boolean = id.isNotBlank() && id.length <= 256 && !id.startsWith("local:") && !id.startsWith("file:")

    fun track(id: String, title: String, artist: String, cover: String? = null): String? {
        if (!shareableId(id) || title.isBlank()) return null
        return "$SITE/listen?" + query("track" to id, "title" to clip(title), "artist" to clip(artist), "cover" to TrackRef.publicCover(cover))
    }

    /** A catalogue album has its own page; any other album goes through `/listen` with `kind=album`. */
    fun album(id: String?, title: String, artist: String, cover: String? = null): String? {
        if (title.isBlank()) return null
        if (id != null && catalogAlbum.matches(id)) return "$SITE/album/$id"
        val key = id?.takeIf { shareableId(it) } ?: return null
        return "$SITE/listen?" + query("track" to key, "title" to clip(title), "artist" to clip(artist), "kind" to "album",
            "cover" to TrackRef.publicCover(cover))
    }

    fun playlist(linkId: String, name: String): String? {
        if (!uuid.matches(linkId)) return null
        return "$SITE/listen?" + query("playlist" to linkId, "name" to clip(name.ifBlank { "Playlist" }))
    }

    fun profile(handle: String): String? = handle.removePrefix("@").lowercase().takeIf { this.handle.matches(it) }?.let { "$SITE/u/$it" }

    /** A Ketogether room: the phone's own link (its page opens the app on the phone and on this PC). */
    fun room(code: String, fingerprint: String): String = TogetherLink.of(code, fingerprint)

    /** What an incoming link asks the app to open. */
    sealed interface Link {
        data class Track(val id: String, val title: String, val artist: String) : Link
        data class Album(val id: String, val title: String, val artist: String) : Link
        data class Artist(val id: String, val name: String) : Link
        data class Playlist(val id: String, val name: String) : Link
        data class Profile(val handle: String) : Link
        data class Room(val target: TogetherLink.Target) : Link
        /** A shared playlist's invitation (`ketvoice://join/<code>`): the phone and the web handle it. */
        data class Join(val code: String) : Link
    }

    /** `ketvoice://…`, the website's pages and the old GitHub Pages `listen.html`; null for anything else. */
    fun parse(raw: String): Link? {
        val text = raw.trim()
        if (text.length > 2000) return null
        val scheme = text.substringBefore("://", "").lowercase()
        val rest = text.substringAfter("://", "")
        val (where, params) = rest.substringBefore('#').let { it.substringBefore('?') to params(it.substringAfter('?', "")) }
        return when (scheme) {
            "ketvoice" -> fromApp(where.trimEnd('/'), params)
            "https" -> fromSite(where.trimEnd('/'), params)
            else -> null
        }
    }

    private fun fromApp(where: String, params: Map<String, String>): Link? {
        val host = where.substringBefore('/').lowercase()
        val tail = where.substringAfter('/', "")
        return when (host) {
            "listen" -> listen(params)
            "u" -> tail.removePrefix("@").lowercase().takeIf { handle.matches(it) }?.let(Link::Profile)
            "join" -> tail.uppercase().takeIf { it.length in 4..32 && it.all(Char::isLetterOrDigit) }?.let(Link::Join)
            else -> null
        }
    }

    private fun fromSite(where: String, params: Map<String, String>): Link? {
        val host = where.substringBefore('/').lowercase()
        val path = "/" + where.substringAfter('/', "")
        val ours = host == "ketvoice-web.vercel.app" || (host == "tysnnn23.github.io" && path.startsWith("/Ketvoice/listen"))
        if (!ours) return null
        if (path.startsWith("/Ketvoice/listen") || path == "/listen" || path == "/listen.html") return listen(params)
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val id = decode(parts[1])
        return when (parts[0]) {
            "u" -> id.removePrefix("@").lowercase().takeIf { handle.matches(it) }?.let(Link::Profile)
            "album" -> id.takeIf { catalogAlbum.matches(it) }?.let { Link.Album(it, "", "") }
            "artist" -> id.takeIf { catalogArtist.matches(it) }?.let { Link.Artist(it, "") }
            else -> null
        }
    }

    private fun listen(p: Map<String, String>): Link? {
        p["playlist"]?.let { id -> return if (uuid.matches(id)) Link.Playlist(id, clip(p["name"].orEmpty())) else null }
        val id = p["track"]?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return null
        val title = clip(p["title"].orEmpty())
        val artist = clip(p["artist"].orEmpty())
        return when (p["kind"]) {
            TogetherLink.KIND -> TogetherLink.parse(id)?.let(Link::Room)
            "album" -> Link.Album(id, title, artist)
            "artist" -> Link.Artist(id, artist.ifBlank { title })
            "airket" -> null
            else -> if (shareableId(id)) Link.Track(id, title, artist) else null
        }
    }

    private fun params(query: String): Map<String, String> = query.split('&').filter { '=' in it }.associate {
        decode(it.substringBefore('=')) to decode(it.substringAfter('='))
    }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault("")

    private fun clip(s: String) = SocialText.name(s, 200)

    private fun query(vararg pairs: Pair<String, String?>): String = pairs.filter { !it.second.isNullOrBlank() }
        .joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20") }
}
