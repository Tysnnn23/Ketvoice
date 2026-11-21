package com.ketvoice.desktop.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.ketvoice.app.data.Track
import com.ketvoice.app.ui.common.Ket27Glyphs
import com.ketvoice.app.ui.common.KetIcons
import kotlinx.coroutines.launch

/** The pages of the window. */
sealed interface Route {
    data object Home : Route
    data object Explore : Route
    data object Search : Route
    data object RecentlyAdded : Route
    data object Artists : Route
    data object Albums : Route
    data object Songs : Route
    data object Downloads : Route
    data object Stats : Route
    data object Settings : Route
    data class Playlist(val id: String) : Route
    data class Album(val ref: AlbumRef) : Route
    data class Artist(val ref: ArtistRef) : Route
    data class Category(val category: com.ketvoice.desktop.ui.Category) : Route
}

/** A back stack: the sidebar replaces it, a link pushes onto it. */
@Stable
class Navigator(start: Route = Route.Home) {
    private val stack = mutableStateListOf(start)
    val current: Route by derivedStateOf { stack.last() }
    /** +1 when the last move went deeper, -1 back, 0 sideways (the sidebar): page transitions follow it. */
    var direction by mutableStateOf(0); private set
    val canGoBack: Boolean get() = stack.size > 1

    fun root(route: Route) { if (stack.size == 1 && stack[0] == route) return; direction = 0; stack.clear(); stack += route }
    fun push(route: Route) { if (stack.last() == route) return; direction = 1; stack += route }
    fun back(): Boolean { if (stack.size <= 1) return false; direction = -1; stack.removeAt(stack.lastIndex); return true }
    /** The sidebar entry this page belongs to. */
    val section: Route get() = stack.first()
}

val LocalNavigator = staticCompositionLocalOf { Navigator() }

/** The actions of a title, as its context menu, double-click and shortcuts call them. */
class TrackActions(private val player: PlayerControls, private val library: LibraryStore, private val nav: Navigator,
                   private val scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.MainScope()) {
    fun play(list: List<Track>, index: Int) = player.playQueue(list, index)
    fun playShuffled(list: List<Track>) { if (list.isEmpty()) return; player.setShuffle(true); player.playQueue(list.shuffled(), 0) }
    fun like(track: Track) = library.toggleFavourite(track)
    fun liked(track: Track) = library.favourites.value.any { it.id == track.id }
    fun openAlbum(track: Track) = nav.push(Route.Album(AlbumRef(track.albumId, track.album, track.artist.substringBefore(',').trim(), track.coverUrl)))
    fun openArtist(track: Track) = nav.push(Route.Artist(ArtistRef(track.artistId, track.artist.substringBefore(',').trim())))

    /**
     * The menu of several titles at once (an album card, an album or playlist page, the selection): play, « Lire
     * ensuite » in their order, the queue, a playlist, download. [load] fetches them (an album card's titles come
     * from the catalogue only when an action is chosen).
     */
    fun collectionMenu(load: suspend () -> List<Track>): List<MenuItem> {
        fun run(action: (List<Track>) -> Unit): () -> Unit = {
            scope.launch { val tracks = runCatching { load() }.getOrDefault(emptyList()); if (tracks.isNotEmpty()) action(tracks) }
        }
        return listOf(
            MenuItem("Lecture", KetIcons.Play, "play-all", action = run { play(it, 0) }),
            MenuItem("Lecture aléatoire", KetIcons.Shuffle, "shuffle-all", action = run(::playShuffled)),
            MenuItem("Lire ensuite", KetIcons.Playlist, "play-next", action = run { list -> list.asReversed().forEach(player::playNext) }),
            MenuItem("Ajouter à la file d'attente", KetIcons.Plus, "enqueue", action = run { list -> list.forEach(player::enqueue) }),
            MenuItem("Ajouter à une playlist", KetIcons.Playlist, "add-to-playlist", children =
                library.playlists.value.map { p -> MenuItem(p.name, null, "playlist", action = run { library.addAllToPlaylist(p.id, it) }) } +
                    MenuItem("Nouvelle playlist", KetIcons.Plus, "new-playlist",
                        action = run { library.createPlaylist(com.ketvoice.app.data.AppText.t("Nouvelle playlist"), it) })),
            MenuItem("-"),
            MenuItem("Télécharger", KetIcons.Download, "download", action = run { list -> list.forEach(library::download) }),
        )
    }

    fun menu(track: Track, onPlay: (() -> Unit)? = null): List<MenuItem> = buildList {
        if (onPlay != null) add(MenuItem("Lecture", KetIcons.Play, "play", action = onPlay))
        add(MenuItem("Lire ensuite", KetIcons.Playlist, "play-next") { player.playNext(track) })
        add(MenuItem("Ajouter à la file d'attente", KetIcons.Plus, "enqueue") { player.enqueue(track) })
        add(MenuItem("Ajouter à une playlist", KetIcons.Playlist, "add-to-playlist", children =
            library.playlists.value.map { p -> MenuItem(p.name, null, "playlist") { library.addToPlaylist(p.id, track) } } +
                MenuItem("Nouvelle playlist", KetIcons.Plus, "new-playlist") {
                    library.createPlaylist(com.ketvoice.app.data.AppText.t("Nouvelle playlist"), listOf(track))
                }))
        add(MenuItem("-"))
        add(MenuItem("Télécharger", KetIcons.Download, "download") { library.download(track) })
        if (track.album.isNotBlank()) add(MenuItem("Aller à l'album", KetIcons.Album, "go-album") { openAlbum(track) })
        add(MenuItem("Aller à l'artiste", KetIcons.Account, "go-artist") { openArtist(track) })
        add(MenuItem("-"))
        add(if (liked(track)) MenuItem("Retirer des favoris", Ket27Glyphs.HeartFilled, "like") { like(track) }
        else MenuItem("J'aime", Ket27Glyphs.Heart, "like") { like(track) })
    }
}

val LocalTrackActions = staticCompositionLocalOf<TrackActions> { error("No TrackActions") }
