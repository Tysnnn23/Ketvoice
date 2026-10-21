package com.ketvoice.app.data

import org.json.JSONArray
import org.json.JSONObject

data class MusicArtist(val id: String, val name: String, val photo: String?)
data class ArtistPage(
    val artist: MusicArtist,
    val description: String,
    val tracks: List<Track>,
    val albums: List<Album>,
    val related: List<MusicArtist>,
    val listeners: String = ""
)

/**
 * Catalogue YouTube Music authentifié ou public : recherche d'artistes,
 * pages d'artiste complètes et accueil personnel. Aucune donnée n'est
 * inventée : seuls les objets réellement renvoyés par YouTube sont exposés.
 */
object YouTubeCatalog {

    suspend fun searchArtists(query: String): List<MusicArtist> {
        if (query.isBlank()) return emptyList()
        val filtered = YouTubeApi.request("search", JSONObject().put("query", query)
            .put("params", "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"), authenticated = false)
        val filteredRows = filtered?.let(::artistRows).orEmpty()
        val rows = if (filteredRows.isNotEmpty()) filteredRows else {
            val raw = YouTubeApi.request("search", JSONObject().put("query", query), authenticated = false)
                ?: return emptyList()
            artistRows(raw)
        }
        return rows.mapNotNull { row ->
            val title = YouTubeJson.text(row.title)
            if (title.isBlank()) return@mapNotNull null
            MusicArtist(row.artistId, title, YouTubeJson.image(row.thumbnail))
        }.distinctBy { it.id }.take(8)
    }

    suspend fun artist(id: String): ArtistPage? {
        if (!id.matches(Regex("UC[A-Za-z0-9_-]+"))) return null
        val raw = YouTubeApi.request("browse", JSONObject().put("browseId", id)) ?: return null
        val header = YouTubeJson.objects(raw, "musicImmersiveHeaderRenderer").firstOrNull()
            ?: YouTubeJson.objects(raw, "musicVisualHeaderRenderer").firstOrNull() ?: return null
        val name = YouTubeJson.text(header.optJSONObject("title"))
        if (name.isBlank()) return null
        val artist = MusicArtist(id, name,
            YouTubeJson.image(header.optJSONObject("thumbnail") ?: header.optJSONObject("foregroundThumbnail") ?: header))
        val description = YouTubeJson.objects(raw, "musicDescriptionShelfRenderer").firstOrNull()
            ?.let { YouTubeJson.text(it.optJSONObject("description")) }.orEmpty()
        val listeners = YouTubeJson.text(header.optJSONObject("monthlyListenerCount"))
            .ifBlank {
                YouTubeJson.objects(raw, "subscribeButtonRenderer").firstOrNull()
                    ?.let { YouTubeJson.text(it.optJSONObject("subscriberCountText")) }.orEmpty()
            }
            .ifBlank {
                YouTubeJson.objects(raw, "musicDescriptionShelfRenderer").firstOrNull()
                    ?.let { YouTubeJson.text(it.optJSONObject("subheader")) }.orEmpty()
            }
        val releases = mutableListOf<Album>()
        val related = mutableListOf<MusicArtist>()
        YouTubeJson.objects(raw, "musicCarouselShelfRenderer").forEach { shelf ->
            val section = YouTubeJson.text(shelf.optJSONObject("header")
                ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")?.optJSONObject("title"))
            YouTubeJson.objects(shelf.optJSONArray("contents"), "musicTwoRowItemRenderer").forEach { item ->
                val endpoint = item.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                    ?: return@forEach
                val browseId = endpoint.optString("browseId")
                val type = endpoint.optJSONObject("browseEndpointContextSupportedConfigs")
                    ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType")
                val title = YouTubeJson.text(item.optJSONObject("title"))
                val picture = YouTubeJson.image(item.optJSONObject("thumbnailRenderer"))
                val subtitle = YouTubeJson.text(item.optJSONObject("subtitle"))
                when (type) {
                    "MUSIC_PAGE_TYPE_ALBUM" -> if (browseId.startsWith("MPRE") && title.isNotBlank()) {
                        val releaseType = when {
                            subtitle.contains("single", true) || section.contains("single", true) -> "Single"
                            subtitle.contains("EP", true) || section.contains("EP", true) -> "EP"
                            else -> "Album"
                        }
                        val year = Regex("\\b(?:19|20)\\d{2}\\b").find(subtitle)?.value
                        releases += Album(browseId, title, name,
                            listOf(releaseType, year).filterNotNull().joinToString(" · "), picture)
                    }
                    "MUSIC_PAGE_TYPE_ARTIST" -> if (browseId.startsWith("UC") && title.isNotBlank()) {
                        related += MusicArtist(browseId, title, picture)
                    }
                }
            }
        }
        return ArtistPage(artist, description, parseRows(raw, officialOnly = true).take(20),
            releases.distinctBy { it.id }, related.distinctBy { it.id }.take(12), listeners)
    }

    suspend fun album(id: String): CatalogPage? {
        if (!id.matches(Regex("MPRE[A-Za-z0-9_-]+"))) return null
        val raw = YouTubeApi.request("browse", JSONObject().put("browseId", id)) ?: return null
        val header = YouTubeJson.objects(raw, "musicResponsiveHeaderRenderer").firstOrNull()
            ?: YouTubeJson.objects(raw, "musicDetailHeaderRenderer").firstOrNull() ?: return null
        val title = YouTubeJson.text(header.optJSONObject("title"))
        val artistLinks = YouTubeJson.objects(raw, "browseEndpoint").filter {
            it.optJSONObject("browseEndpointContextSupportedConfigs")
                ?.optJSONObject("browseEndpointContextMusicConfig")
                ?.optString("pageType") == "MUSIC_PAGE_TYPE_ARTIST"
        }
        val artistId = artistLinks.firstOrNull()?.optString("browseId").orEmpty()
        val artistName = YouTubeJson.text(header.optJSONObject("straplineTextOne"))
            .ifBlank { YouTubeJson.text(header.optJSONObject("subtitle")) }
        val cover = YouTubeJson.image(header.optJSONObject("thumbnail"))
        val rows = parseRows(raw, albumFallback = title, albumIdFallback = id,
            artistFallback = artistName, artistIdFallback = artistId, officialOnly = true)
        if (rows.isEmpty()) return null
        val tracks = rows.map { if (it.coverUrl.isNullOrBlank()) it.copy(coverUrl = cover) else it }
        return CatalogPage(title, artistName, cover, tracks)
    }

    suspend fun personalHome(): List<HomeShelf> {
        if (!YouTubeMusicSession.profile.value.connected) return emptyList()
        val raw = YouTubeApi.request("browse", JSONObject().put("browseId", "FEmusic_home")) ?: return emptyList()
        return YouTubeJson.objects(raw, "musicCarouselShelfRenderer").mapNotNull { shelf ->
            val title = YouTubeJson.text(shelf.optJSONObject("header")
                ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")?.optJSONObject("title"))
            val tracks = parseRows(shelf.toString(), officialOnly = true).toMutableList()
            YouTubeJson.objects(shelf.optJSONArray("contents"), "musicTwoRowItemRenderer").forEach { item ->
                val watch = item.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
                    ?: return@forEach
                val type = watch.optJSONObject("watchEndpointMusicSupportedConfigs")
                    ?.optJSONObject("watchEndpointMusicConfig")?.optString("musicVideoType")
                if (type != "MUSIC_VIDEO_TYPE_ATV" && type != "MUSIC_VIDEO_TYPE_OMV") return@forEach
                val artists = item.optJSONObject("subtitle")?.optJSONArray("runs") ?: return@forEach
                val names = mutableListOf<String>()
                var channel = ""
                for (i in 0 until artists.length()) {
                    val run = artists.optJSONObject(i) ?: continue
                    val browse = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint") ?: continue
                    if (browse.optString("browseId").startsWith("UC")) {
                        names += run.optString("text")
                        channel = browse.optString("browseId")
                    }
                }
                val video = watch.optString("videoId")
                if (names.isNotEmpty() && video.length == 11) {
                    tracks += Track(
                        id = "yt:$video", title = YouTubeJson.text(item.optJSONObject("title")),
                        artist = names.joinToString(", "), album = "", videoId = video,
                        coverUrl = YouTubeJson.image(item.optJSONObject("thumbnailRenderer")),
                        youtubeCatalogVerified = true, artistId = channel
                    )
                }
            }
            if (tracks.isEmpty() || title.isBlank()) null
            else HomeShelf(title, "YouTube Music · ${YouTubeMusicSession.profile.value.name}",
                tracks.distinctBy { it.id }.take(20))
        }.distinctBy { it.title }.take(10)
    }

    // ---------------------------------------------------------------- rows
    private data class ArtistRow(val artistId: String, val title: JSONObject?, val thumbnail: JSONObject?)

    private fun artistRows(raw: JSONObject): List<ArtistRow> =
        YouTubeJson.objects(raw, "musicResponsiveListItemRenderer").mapNotNull { row ->
            val endpoint = row.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                ?: row.optJSONArray("flexColumns")?.optJSONObject(0)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)
                    ?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                ?: return@mapNotNull null
            val type = endpoint.optJSONObject("browseEndpointContextSupportedConfigs")
                ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType")
            val id = endpoint.optString("browseId")
            if (type != "MUSIC_PAGE_TYPE_ARTIST" || !id.startsWith("UC")) return@mapNotNull null
            val title = row.optJSONArray("flexColumns")?.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
            ArtistRow(id, title, row.optJSONObject("thumbnail"))
        }

    private fun parseRows(
        raw: Any?, albumFallback: String = "", albumIdFallback: String = "",
        artistFallback: String = "", artistIdFallback: String = "", officialOnly: Boolean
    ): List<Track> {
        val rows = mutableListOf<JSONObject>()
        fun visit(node: Any?) {
            when (node) {
                is JSONObject -> {
                    if (node.has("musicResponsiveListItemRenderer")) {
                        node.optJSONObject("musicResponsiveListItemRenderer")?.let { rows += it }
                    }
                    node.keys().forEach { key ->
                        if (key != "musicResponsiveListItemRenderer") visit(node.opt(key))
                    }
                }
                is JSONArray -> (0 until node.length()).forEach { visit(node.opt(it)) }
            }
        }
        visit(if (raw is String) runCatching { JSONObject(raw) }.getOrNull() else raw)
        return rows.mapNotNull { row ->
            val flex = row.optJSONArray("flexColumns") ?: return@mapNotNull null
            fun runsAt(index: Int): JSONArray = flex.optJSONObject(index)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")?.optJSONArray("runs") ?: JSONArray()
            val titleRuns = runsAt(0)
            val title = (0 until titleRuns.length())
                .joinToString("") { titleRuns.optJSONObject(it)?.optString("text").orEmpty() }
                .trim()
            if (title.isBlank()) return@mapNotNull null
            val titleWatch = titleRuns.optJSONObject(0)?.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")
            val overlayWatch = row.optJSONObject("overlay")?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")?.optJSONObject("watchEndpoint")
            val watch = titleWatch ?: overlayWatch ?: row.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")
            val video = watch?.optString("videoId").orEmpty()
            val type = watch?.optJSONObject("watchEndpointMusicSupportedConfigs")
                ?.optJSONObject("watchEndpointMusicConfig")?.optString("musicVideoType")
            if (video.length != 11) return@mapNotNull null
            if (officialOnly && type != "MUSIC_VIDEO_TYPE_ATV" && type != "MUSIC_VIDEO_TYPE_OMV") {
                return@mapNotNull null
            }
            val declaredId = row.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
            if (declaredId.isNotBlank() && declaredId != video) return@mapNotNull null
            val variants = listOf("slowed", "sped up", "speed up", "nightcore", "8d", "bass boosted",
                "reverb", "karaoke", "type beat", "instrumental")
            if (variants.any { title.contains(it, true) }) return@mapNotNull null

            val artists = mutableListOf<String>()
            var album = ""
            var albumId = ""
            var artistId = ""
            var durationMs = 0L
            fun scan(runs: JSONArray) {
                for (i in 0 until runs.length()) {
                    val run = runs.optJSONObject(i) ?: continue
                    val text = run.optString("text").trim()
                    val browse = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                    val page = browse?.optJSONObject("browseEndpointContextSupportedConfigs")
                        ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType")
                    if (page == "MUSIC_PAGE_TYPE_ARTIST") {
                        if (text.isNotBlank() && !text.equals("Artiste", true)) artists += text
                        if (artistId.isBlank()) artistId = browse.optString("browseId")
                    }
                    if (page == "MUSIC_PAGE_TYPE_ALBUM" && text.isNotBlank()) {
                        album = text
                        albumId = browse.optString("browseId")
                    }
                    if (durationMs == 0L && text.matches(Regex("\\d+:\\d{2}(?::\\d{2})?"))) {
                        durationMs = text.split(':').fold(0L) { value, part -> value * 60 + part.toLong() } * 1000
                    }
                }
            }
            for (i in 1 until flex.length()) scan(runsAt(i))
            val fixed = row.optJSONArray("fixedColumns")
            if (fixed != null) for (i in 0 until fixed.length()) {
                scan(fixed.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                    ?.optJSONObject("text")?.optJSONArray("runs") ?: JSONArray())
            }
            if (artists.isEmpty() && artistFallback.isNotBlank()) {
                artists += artistFallback
                artistId = artistIdFallback
            }
            if (album.isBlank()) album = albumFallback
            if (albumId.isBlank()) albumId = albumIdFallback
            Track(
                id = "yt:$video", title = title, artist = artists.distinct().joinToString(", "), album = album,
                durationMs = durationMs, videoId = video, youtubeCatalogVerified = true,
                coverUrl = YouTubeJson.image(row.optJSONObject("thumbnail")),
                albumId = albumId.takeIf { it.startsWith("MPRE") }, artistId = artistId.takeIf { it.startsWith("UC") }
            )
        }.distinctBy { it.videoId }.take(50)
    }
}
