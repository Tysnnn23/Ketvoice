package com.ketvoice.app.smartlib

import org.json.JSONArray
import org.json.JSONObject

/**
 * A public Apple Music playlist read from its web page (`music.apple.com/<pays>/playlist/<nom>/pl.…`):
 * the page carries the playlist and its titles as data for anyone, without an account or a key.
 * Apple publishes no ISRC there; when the page lists fewer titles than the playlist has, the import is partial.
 */
object AppleMusicImport {
    val hosts = setOf("music.apple.com", "geo.music.apple.com", "embed.music.apple.com")
    private val id = Regex("pl\\.[A-Za-z0-9-]{8,80}")
    private val storefront = Regex("[a-z]{2}")

    /** The page address to read for [link], or a message for the listener. */
    fun pageUrl(link: String): String {
        val uri = PublicPage.uri(link)?.takeIf { it.host.lowercase() in hosts } ?: throw IllegalArgumentException(PublicPage.NOT_A_PLAYLIST)
        val parts = uri.path.orEmpty().trim('/').split('/')
        val country = parts.firstOrNull()?.takeIf { storefront.matches(it) } ?: "us"
        val at = parts.indexOf("playlist")
        val playlist = parts.lastOrNull()?.takeIf { at >= 0 && id.matches(it) } ?: throw IllegalArgumentException(PublicPage.NOT_A_PLAYLIST)
        return "https://music.apple.com/$country/playlist/$playlist"
    }

    fun parse(html: String, url: String): ImportPreview {
        val data = PublicPage.script(html, "serialized-server-data")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("data")?.optJSONArray("sections")
            ?: throw IllegalStateException(PublicPage.CHANGED)
        val header = section(data, "containerDetailHeaderLockup")?.optJSONObject(0) ?: throw IllegalStateException(PublicPage.CHANGED)
        val songs = section(data, "trackLockup")?.let(::songs).orEmpty()
        if (songs.isEmpty()) throw IllegalStateException(if (header.optInt("trackCount") > 0) PublicPage.CHANGED else "Aucun titre accessible dans cette playlist")
        val total = header.optInt("trackCount").takeIf { it > 0 }
        return ImportPreview(ImportService.AppleMusic, PublicPage.clean(header.optString("title"), 80).ifBlank { "Apple Music" }, url,
            artwork(header.optJSONObject("artwork")), songs.take(PlaylistImport.MAX_SONGS),
            complete = total == null || songs.size >= total, publishedCount = total)
    }

    private fun section(sections: JSONArray, kind: String): JSONArray? = (0 until sections.length())
        .mapNotNull { sections.optJSONObject(it) }.firstOrNull { it.optString("itemKind") == kind }?.optJSONArray("items")

    private fun songs(items: JSONArray): List<ImportSong> = (0 until items.length()).mapNotNull { index ->
        val item = items.optJSONObject(index) ?: return@mapNotNull null
        val title = PublicPage.clean(item.optString("title"))
        val artist = PublicPage.clean(item.optString("artistName"))
        if (title.isBlank() || artist.isBlank()) return@mapNotNull null
        val song = item.optJSONObject("contentDescriptor")?.optJSONObject("identifiers")?.optString("storeAdamID").orEmpty()
        ImportSong("am:${song.ifBlank { "$index" }}", title, artist, item.optLong("duration").coerceIn(0, 86_400_000))
    }

    /** Apple gives an address pattern ("…/{w}x{h}bb.{f}"): a square 600 px JPEG is asked for. */
    private fun artwork(artwork: JSONObject?): String? {
        val pattern = artwork?.optJSONObject("dictionary")?.optString("url") ?: return null
        return PublicPage.cover(pattern.replace("{w}", "600").replace("{h}", "600").replace("{f}", "jpg").replace("{c}", "bb"), "mzstatic.com")
    }
}
