package com.ketvoice.app.data

import org.json.JSONObject
import org.json.JSONArray

/** Catalogue evidence, never the words 'official' or 'VEVO' in an uploader title. */
object OfficialMusicParser {
    const val SONGS_FILTER = "EgWKAQIIAWoKEAkQChAFEAMQBA%3D%3D"
    private fun objects(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> node.keys().forEach { k ->
                val value = node.opt(k)
                if (k == key && value is JSONObject) out += value
                objects(value, key, out)
            }
            is JSONArray -> (0 until node.length()).forEach { objects(node.opt(it), key, out) }
        }
    }
    fun parse(raw: String, query: String = ""): List<Track> = runCatching {
        val rows = mutableListOf<JSONObject>()
        objects(JSONObject(raw), "musicResponsiveListItemRenderer", rows)
        rows.mapNotNull { row ->
            val cols = row.optJSONArray("flexColumns") ?: return@mapNotNull null
            fun runs(index: Int) = cols.optJSONObject(index)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")?.optJSONArray("runs") ?: JSONArray()
            val titleRuns = runs(0)
            // Read only the row's own play/title endpoint. A menu can contain
            // unrelated catalogue endpoints and must not certify an upload.
            val titleWatch = titleRuns.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
            val overlayWatch = row.optJSONObject("overlay")?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")?.optJSONObject("watchEndpoint")
            val watch = titleWatch ?: overlayWatch ?: return@mapNotNull null
            val type = watch.optJSONObject("watchEndpointMusicSupportedConfigs")?.optJSONObject("watchEndpointMusicConfig")
                ?.optString("musicVideoType")
            if (type != "MUSIC_VIDEO_TYPE_ATV") return@mapNotNull null
            val video = watch.optString("videoId")
            val declaredId = row.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
            if (video.length != 11 || (declaredId.isNotBlank() && declaredId != video)) return@mapNotNull null
            val title = (0 until titleRuns.length()).joinToString("") { titleRuns.optJSONObject(it)?.optString("text").orEmpty() }
            val variants = listOf("slowed", "sped up", "speed up", "nightcore", "8d", "bass boosted", "reverb", "karaoke")
            if (variants.any { title.contains(it, true) && !query.contains(it, true) }) return@mapNotNull null
            val meta = runs(1)
            val artists = mutableListOf<String>()
            var album = ""
            var albumId = ""
            var artistId = ""
            var duration = 0L
            for (i in 0 until meta.length()) {
                val run = meta.optJSONObject(i) ?: continue
                val name = run.optString("text").trim()
                val browse = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                val page = browse?.optJSONObject("browseEndpointContextSupportedConfigs")
                    ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType")
                if (page == "MUSIC_PAGE_TYPE_ARTIST") {
                    artists += name
                    if (artistId.isEmpty()) artistId = browse.optString("browseId")
                }
                if (page == "MUSIC_PAGE_TYPE_ALBUM") { album = name; albumId = browse.optString("browseId") }
                if (name.matches(Regex("\\d+:\\d{2}(?::\\d{2})?"))) duration = name.split(':').fold(0L) { v, part -> v * 60 + part.toLong() } * 1000
            }
            // Les « type beats », instrumentales et reprises ne font pas partie
            // du catalogue officiel de l'artiste même s'ils ont une page album.
            val catalogueText = "$title $album".lowercase()
            if (listOf("type beat", "instrumental", "reprod", "prod. by", "made by").any { catalogueText.contains(it) }) {
                return@mapNotNull null
            }
            // Missing metadata is not proof of official catalogue membership.
            if (title.isBlank() || artists.isEmpty() || !albumId.startsWith("MPRE")) return@mapNotNull null
            val explicit = row.optJSONArray("badges")?.toString()?.contains("MUSIC_EXPLICIT_BADGE") == true
            Track("yt:$video", title, artists.distinct().joinToString(", "), album, duration,
                explicit = explicit, coverUrl = YouTubeJson.image(row.optJSONObject("thumbnail")),
                videoId = video, youtubeCatalogVerified = true, albumId = albumId, artistId = artistId)
        }.distinctBy { it.videoId }.take(30)
    }.getOrDefault(emptyList())
}
