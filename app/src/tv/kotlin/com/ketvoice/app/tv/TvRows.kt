package com.ketvoice.app.tv

import com.ketvoice.app.data.Album
import com.ketvoice.app.data.CatalogAlbum
import com.ketvoice.app.data.CatalogChart
import com.ketvoice.app.data.HomeShelf
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.MixCard
import com.ketvoice.app.data.PersonalMixes
import com.ketvoice.app.data.Track

/** What a card of a row shows and does (a title plays its row from it; anything else opens a page). */
enum class TvItemKind { Track, Album, Playlist, Mix, Artist, Collection }

data class TvItem(
    val key: String,
    val kind: TvItemKind,
    val title: String,
    val subtitle: String,
    val image: String?,
    val route: TvRoute? = null,
    /** For a title: the row's titles, played from [index]. */
    val queue: List<Track> = emptyList(),
    val index: Int = 0,
    /** The title is the app's own French text (translated at display), not a song or an artist's name. */
    val appText: Boolean = false,
    /** For a release: its kind as French source text (« Album », « Single », « EP ») and its year, when known. */
    val release: String = "",
    val year: String = "",
)

/** A titled row of cards. [title] is French source text, translated at display. */
data class TvShelf(val key: String, val title: String, val items: List<TvItem>)

/**
 * The rows of Home and Library, built from the same data the phone's pages read (listening history, playlists,
 * favourites, downloads, personal mixes, new releases, the account's synced library). Pure: host-tested.
 */
object TvRows {
    const val ROW_MAX = 24

    data class HomeInput(
        val recent: List<Track> = emptyList(),
        val playlists: List<LocalPlaylist> = emptyList(),
        val favorites: List<Track> = emptyList(),
        val downloads: List<Track> = emptyList(),
        val mixes: List<MixCard> = emptyList(),
        val releases: List<CatalogAlbum> = emptyList(),
        val artists: List<PersonalMixes.ArtistTaste> = emptyList(),
        val artistPictures: Map<String, String> = emptyMap(),
        val feed: List<HomeShelf> = emptyList(),
        val feedAlbums: List<Album> = emptyList(),
        val chart: CatalogChart? = null,
        val cover: (Track) -> String? = { it.coverUrl },
    )

    fun home(input: HomeInput): List<TvShelf> = listOfNotNull(
        tracks("recent", "Écoutés récemment", input.recent.distinctBy { it.id }, input.cover),
        shelf("library", "Votre bibliothèque", libraryCards(input.favorites, input.downloads, input.recent, input.playlists, input.cover)),
        shelf("mixes", "Mixes pour vous", input.mixes.map(::mix)),
        shelf("releases", "Nouveautés de vos artistes", input.releases.map(::album)),
        shelf("artists", "Vos artistes", input.artists.take(16).map { artist(it, input.artistPictures, input.cover) }),
    ) + input.feed.mapIndexedNotNull { i, s -> tracks("feed:$i:${s.title}", s.title, s.tracks, input.cover) } +
        listOfNotNull(
            shelf("feed-albums", "Albums pour vous", input.feedAlbums.map(::album)),
            input.chart?.let { tracks("chart", "Tendances", it.tracks, input.cover) },
            input.chart?.let { c -> shelf("chart-albums", "Albums du moment", c.albums.map(::album)) },
        )

    /** Favourites, downloads, history and the listener's playlists, as cards (empty ones are left out). */
    fun libraryCards(favorites: List<Track>, downloads: List<Track>, history: List<Track>, playlists: List<LocalPlaylist>,
                     cover: (Track) -> String?): List<TvItem> = buildList {
        if (favorites.isNotEmpty()) add(TvItem("favorites", TvItemKind.Collection, "Favoris", count(favorites.size),
            favorites.firstNotNullOfOrNull(cover), TvRoute.Collection("favorites", "favorites", "Favoris"), appText = true))
        if (downloads.isNotEmpty()) add(TvItem("downloads", TvItemKind.Collection, "Téléchargements", count(downloads.size),
            downloads.firstNotNullOfOrNull(cover), TvRoute.Collection("downloads", "downloads", "Téléchargements"), appText = true))
        if (history.isNotEmpty()) add(TvItem("history", TvItemKind.Collection, "Historique", count(history.size),
            history.firstNotNullOfOrNull(cover), TvRoute.Collection("history", "history", "Historique"), appText = true))
        playlists.filter { it.entries.isNotEmpty() }.sortedByDescending { it.pinned }.forEach { p ->
            val image = p.coverUrl ?: p.entries.firstNotNullOfOrNull { e -> e.track?.let(cover) }
            add(TvItem("pl:${p.id}", TvItemKind.Playlist, p.name, count(p.entries.size), image,
                TvRoute.Collection("playlist", p.id, p.name, image)))
        }
    }

    /** « 12 titres » as source text with its number ({0} templates are translated by AppText). */
    fun count(n: Int): String = if (n == 1) "1 titre" else "$n titres"

    fun track(t: Track, queue: List<Track>, index: Int, cover: (Track) -> String?) =
        TvItem("t:${t.id}:$index", TvItemKind.Track, t.title, t.artist, cover(t), queue = queue, index = index)

    fun album(a: CatalogAlbum) = TvItem("al:${a.id}", TvItemKind.Album, a.title, a.artist, a.cover,
        TvRoute.Collection("album", a.id, a.title, a.cover), release = "Album", year = yearOf(a.releaseDate))

    fun album(a: Album) = TvItem("al:${a.id}", TvItemKind.Album, a.title, a.artist, a.coverUrl,
        TvRoute.Collection("album", a.id, a.title, a.coverUrl), release = releaseOf(a),
        year = com.ketvoice.app.ui.sub.ArtistDiscography.year(a)?.toString().orEmpty())

    /** « Album », « Single » or « EP », as the catalogue's type line says. */
    fun releaseOf(a: Album): String = when (com.ketvoice.app.ui.sub.ArtistDiscography.kind(a)) {
        com.ketvoice.app.ui.sub.ArtistDiscography.Kind.SINGLE -> "Single"
        com.ketvoice.app.ui.sub.ArtistDiscography.Kind.EP -> "EP"
        com.ketvoice.app.ui.sub.ArtistDiscography.Kind.ALBUM -> "Album"
    }

    /** The year of a « 2024-05-17 » release date, or empty. */
    fun yearOf(date: String?): String = date?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty()

    fun mix(card: MixCard): TvItem {
        val title = com.ketvoice.app.ui.home.mixTitle(card)
        return TvItem("mix:${card.id}", TvItemKind.Mix, title, com.ketvoice.app.ui.home.mixSubtitle(card), card.covers.firstOrNull(),
            TvRoute.Collection("mix", card.id, title, card.covers.firstOrNull()), appText = true)
    }

    fun artist(a: PersonalMixes.ArtistTaste, pictures: Map<String, String>, cover: (Track) -> String?): TvItem {
        val picture = pictures[a.name.lowercase()]
        return TvItem("ar:${a.name.lowercase()}", TvItemKind.Artist, a.name, "", picture ?: a.tracks.firstNotNullOfOrNull(cover),
            TvRoute.Artist(a.catalogId ?: "", a.name, picture))
    }

    private fun tracks(key: String, title: String, list: List<Track>, cover: (Track) -> String?): TvShelf? {
        val shown = list.take(ROW_MAX)
        return shelf(key, title, shown.mapIndexed { i, t -> track(t, shown, i, cover) })
    }

    private fun shelf(key: String, title: String, items: List<TvItem>): TvShelf? =
        items.distinctBy { it.key }.take(ROW_MAX).takeIf { it.isNotEmpty() }?.let { TvShelf(key, title, it) }

    /**
     * The albums of the listener's own titles (favourites, downloads, history), grouped by album and artist, most
     * titles first. A local album opens as `Collection("localAlbum", "<album>|<artist>")`.
     */
    fun localAlbums(tracks: List<Track>, cover: (Track) -> String?): List<TvItem> =
        tracks.filter { it.album.isNotBlank() }.distinctBy { it.id }
            .groupBy { albumKey(it) }
            .entries.sortedWith(compareByDescending<Map.Entry<String, List<Track>>> { it.value.size }.thenBy { it.key })
            .map { (key, list) ->
                val first = list.first()
                val image = list.firstNotNullOfOrNull(cover)
                TvItem("la:$key", TvItemKind.Album, first.album, first.artist, image, TvRoute.Collection("localAlbum", key, first.album, image))
            }

    fun albumKey(t: Track): String = t.album.trim().lowercase() + "|" + PersonalMixes.primaryArtist(t).lowercase()

    /** The Library page: the collections, then albums, artists, favourite titles and downloaded titles. */
    fun library(favorites: List<Track>, downloads: List<Track>, history: List<Track>, known: List<Track>,
                playlists: List<LocalPlaylist>, artists: List<PersonalMixes.ArtistTaste>, pictures: Map<String, String>,
                cover: (Track) -> String?): List<TvShelf> {
        val own = (favorites + downloads + known).distinctBy { it.id }
        return listOfNotNull(
            shelf("collections", "Playlists", libraryCards(favorites, downloads, history, playlists, cover)),
            shelf("albums", "Albums", localAlbums(own, cover)),
            shelf("artists", "Artistes", artists.take(ROW_MAX).map { artist(it, pictures, cover) }),
            tracks("favorite-tracks", "Favoris", favorites, cover),
            tracks("download-tracks", "Téléchargements", downloads, cover),
        )
    }
}
