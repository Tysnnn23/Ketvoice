package com.ketvoice.app.smartlib

import com.ketvoice.app.data.YouTubeApi
import com.ketvoice.app.data.YouTubeJson
import org.json.JSONObject
import java.net.URI

/**
 * Public YouTube and YouTube Music playlists (Windows 2.0 import; the phone's import does not offer it): the playlist
 * id of the link (`list=`), then YouTube Music's own public page of it (`browse` of `VL<id>`, without the listener's
 * session), read as the app's YouTube catalogue reads albums. Only the first page the service shows is read.
 */
object YouTubeImport {
    val hosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")
    private val ID = Regex("[A-Za-z0-9_-]{10,64}")

    /** Whether [link] is a YouTube link carrying a playlist. */
    fun isLink(link: String): Boolean = runCatching { playlistId(link) }.isSuccess

    /** The playlist id of a YouTube link; throws with a message for the listener otherwise. */
    fun playlistId(link: String): String {
        val uri = runCatching { URI(link.trim()) }.getOrNull()
        require(uri != null && uri.scheme in setOf("https", "http") && uri.host?.lowercase() in hosts) { "Lien YouTube invalide" }
        val list = uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("list=") }?.substringAfter('=')
        require(list != null && ID.matches(list)) { "Collez le lien d'une playlist YouTube publique" }
        return list
    }

    suspend fun preview(link: String, browse: suspend (String) -> JSONObject? = { id ->
        YouTubeApi.request("browse", JSONObject().put("browseId", "VL$id"), authenticated = false)
    }): ImportPreview {
        val id = playlistId(link)
        val raw = browse(id) ?: throw IllegalStateException("YouTube est injoignable. Réessayez plus tard.")
        return parse(raw, id)
    }

    /** A playlist page: its title, cover and rows (title, artists, length, video id). */
    fun parse(raw: JSONObject, id: String): ImportPreview {
        val header = listOf("musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer", "musicEditablePlaylistDetailHeaderRenderer")
            .firstNotNullOfOrNull { YouTubeJson.objects(raw, it).firstOrNull() }
        val name = PublicPage.clean(YouTubeJson.text(header?.optJSONObject("title")), 80).ifBlank { "YouTube" }
        val cover = header?.let { YouTubeJson.image(it.optJSONObject("thumbnail")) }?.takeIf { it.startsWith("https://") }
        val songs = YouTubeJson.objects(raw, "musicResponsiveListItemRenderer").mapNotNull(::song).distinctBy { it.sourceId }
            .take(PlaylistImport.MAX_SONGS)
        require(songs.isNotEmpty()) { "Aucun titre accessible dans cette playlist" }
        val more = YouTubeJson.objects(raw, "continuationItemRenderer").isNotEmpty() || YouTubeJson.objects(raw, "nextContinuationData").isNotEmpty()
        return ImportPreview(ImportService.YouTube, name, "https://music.youtube.com/playlist?list=$id", cover, songs, complete = !more)
    }

    private fun song(row: JSONObject): ImportSong? {
        val columns = row.optJSONArray("flexColumns") ?: return null
        fun column(i: Int) = columns.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
        val title = PublicPage.clean(YouTubeJson.text(column(0)), 200)
        // The second column is « Artist • Album » (or « Artist • 3,4 k vues »): the artists come before the first dot.
        val artist = PublicPage.clean(YouTubeJson.text(column(1)).substringBefore(" • "), 200)
        val video = YouTubeJson.objects(row, "watchEndpoint").firstNotNullOfOrNull { it.optString("videoId").takeIf(String::isNotBlank) }
            ?: row.optJSONObject("playlistItemData")?.optString("videoId")
        if (title.isBlank() || artist.isBlank() || video.isNullOrBlank()) return null
        val length = row.optJSONArray("fixedColumns")?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
            ?.optJSONObject("text")?.let(YouTubeJson::text).orEmpty()
        return ImportSong(video, title, artist, durationMs(length))
    }

    /** « 3:45 » or « 1:02:03 » in milliseconds; 0 when unreadable. */
    fun durationMs(text: String): Long {
        val parts = text.trim().split(':').map { it.toLongOrNull() ?: return 0L }
        if (parts.size !in 2..3) return 0L
        return parts.fold(0L) { a, b -> a * 60 + b } * 1000
    }
}
