package com.ketvoice.app.smartlib

import org.json.JSONObject

/**
 * A public Tidal playlist: its name, cover and size from the playlist's web page (schema.org data),
 * its titles from Tidal's public embed player. That player lists only the first 50 titles: a longer
 * playlist is imported in part, and the listener is told so.
 */
object TidalImport {
    val hosts = setOf("tidal.com", "www.tidal.com", "listen.tidal.com", "embed.tidal.com")
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val row = Regex("<list-item\\b([^>]*)>(.*?)</list-item>", RegexOption.DOT_MATCHES_ALL)

    /** The playlist's id in "tidal.com/browse/playlist/<uuid>", "listen.tidal.com/playlist/<uuid>"… */
    fun playlistId(link: String): String {
        val uri = PublicPage.uri(link)?.takeIf { it.host.lowercase() in hosts } ?: throw IllegalArgumentException(PublicPage.NOT_A_PLAYLIST)
        val parts = uri.path.orEmpty().trim('/').split('/')
        val at = parts.indexOfFirst { it == "playlist" || it == "playlists" }
        return parts.getOrNull(at + 1)?.takeIf { at >= 0 && uuid.matches(it) }?.lowercase() ?: throw IllegalArgumentException(PublicPage.NOT_A_PLAYLIST)
    }

    fun pageUrl(id: String) = "https://tidal.com/playlist/$id"
    fun embedUrl(id: String) = "https://embed.tidal.com/playlists/$id"

    /** [page] is the playlist page (null when it could not be read), [embed] the embed player. */
    fun parse(page: String?, embed: String, id: String): ImportPreview {
        val about = page?.let(::about)
        val songs = row.findAll(embed).mapNotNull { song(it.groupValues[1], it.groupValues[2]) }.toList()
        if (songs.isEmpty()) throw IllegalStateException(if (embed.contains("media-item-list")) "Aucun titre accessible dans cette playlist" else PublicPage.CHANGED)
        val total = about?.optInt("numTracks")?.takeIf { it > 0 }
        // Without the page, a full first screen of the player may hide more titles.
        val complete = if (total != null) songs.size >= total else songs.size < 50
        return ImportPreview(ImportService.Tidal, PublicPage.clean(about?.optString("name"), 80).ifBlank { "Tidal" }, pageUrl(id),
            PublicPage.cover(about?.optString("image"), "tidal.com"), songs.distinctBy { it.sourceId }, complete, total)
    }

    private fun about(html: String): JSONObject? {
        var rest = html
        while (true) {
            val text = PublicPage.script(rest, "application/ld+json") ?: return null
            runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.optString("@type") == "MusicPlaylist" }?.let { return it }
            rest = rest.substringAfter(text)
        }
    }

    private fun song(attributes: String, body: String): ImportSong? {
        if (!attributes.contains("product-type=\"track\"")) return null
        val id = Regex("product-id=\"(\\d{1,15})\"").find(attributes)?.groupValues?.get(1) ?: return null
        fun slot(name: String) = Regex("<(?:span|time) slot=\"$name\">(.*?)</(?:span|time)>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
        val title = PublicPage.clean(slot("title")?.let(PublicPage::text))
        val artists = slot("artist")?.let { Regex("<a\\b[^>]*>(.*?)</a>").findAll(it).map { a -> PublicPage.text(a.groupValues[1]) }.filter(String::isNotBlank).toList() }
            ?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: slot("artist")?.let(PublicPage::text)
        val artist = PublicPage.clean(artists)
        if (title.isBlank() || artist.isBlank()) return null
        return ImportSong("td:$id", title, artist, PublicPage.clock(slot("duration").orEmpty()))
    }
}
