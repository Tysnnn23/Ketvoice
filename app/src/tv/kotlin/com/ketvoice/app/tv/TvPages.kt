package com.ketvoice.app.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.ArtistPage
import com.ketvoice.app.data.ArtistPages
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.CatalogPage
import com.ketvoice.app.data.CatalogRepo
import com.ketvoice.app.data.CatalogRequest
import com.ketvoice.app.data.PersonalMixes
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.collectPick
import com.ketvoice.app.ui.common.pick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Library: the collections, then albums, artists, favourite and downloaded titles, from the phone's own data. */
@Composable
fun TvLibraryPage() {
    val actions = LocalTvActions.current
    val library = rememberTvLibrary()
    val shelves by produceState(emptyList<TvShelf>(), library) {
        value = withContext(Dispatchers.Default) {
            TvRows.library(library.favorites, library.downloads, library.recent, library.known, library.playlists,
                PersonalMixes.topArtists(library.known, library.counts, library.favoriteIds), emptyMap(), library::cover)
        }
    }
    TvPivot(pivot = .14f, slack = .15f) {
        LazyColumn(Modifier.fillMaxSize().testTag("tv-library"), contentPadding = PaddingValues(top = TvDesign.Safe.vertical, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(TvDesign.Size.rowSpacing)) {
            item(key = "title") { TvPageTitle("Bibliothèque") }
            items(shelves, key = { it.key }) { shelf ->
                TvShelfRow(shelf, actions::open, onMenu = actions::options, first = shelf == shelves.first())
            }
            if (shelves.isEmpty()) item(key = "empty") {
                TvEmpty("Votre bibliothèque est vide", "Connectez votre compte dans Réglages : vos favoris, playlists et écoutes du téléphone arrivent ici.")
            }
        }
    }
}

/**
 * An album, a playlist, a mix, the favourites, the downloads or the history: the cover and the buttons on the left,
 * the titles on the right (each one plays the list from itself).
 */
@Composable
fun TvCollectionPage(route: TvRoute.Collection) {
    val actions = LocalTvActions.current
    val library = rememberTvLibrary()
    var attempt by remember { mutableIntStateOf(0) }
    val remote by produceState<CatalogPage?>(null, route, attempt) {
        if (library.isLocal(route.kind)) return@produceState
        value = runCatching {
            when (route.kind) {
                "mix" -> PersonalMixes.tracks(route.id)?.let { CatalogPage(route.title, "", it.firstOrNull()?.coverUrl, it) }
                else -> CatalogRepo.page(CatalogRequest(route.kind, route.id, route.title))
            }
        }.getOrNull() ?: CatalogPage(route.title, "", route.cover, emptyList())
    }
    val tracks = if (library.isLocal(route.kind)) library.collection(route.kind, route.id) else remote?.tracks.orEmpty()
    LaunchedEffect(tracks) { ArtworkRepo.enrich(tracks.take(60)) }
    val loading = !library.isLocal(route.kind) && remote == null
    val cover = route.cover ?: remote?.cover ?: tracks.firstNotNullOfOrNull(library::cover)
    val appNamed = route.kind in setOf("favorites", "downloads", "history", "mix")
    val listHead = remember { androidx.compose.ui.focus.FocusRequester() }
    val subtitle = remote?.subtitle?.takeIf { it.isNotBlank() } ?: TvRows.count(tracks.size)
    Box(Modifier.fillMaxSize()) {
        TvBackdrop(cover, Modifier.fillMaxSize().underRail(), veil = .72f)
        Row(Modifier.fillMaxSize().padding(top = TvDesign.Safe.vertical)) {
            Column(Modifier.width(372.dp).fillMaxHeight().padding(start = TvDesign.Safe.horizontal, end = 24.dp)) {
                TvCover(cover, 232.dp, RoundedCornerShape(TvDesign.Radius.panel), placeholder = KetIcons.Album)
                Spacer(Modifier.height(18.dp))
                Text(route.title, localize = appNamed, style = TvDesign.Type.title, color = TvDesign.Ink.primary, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("collection-title"))
                Text(subtitle, localize = remote?.subtitle.isNullOrBlank(), style = TvDesign.Type.body, color = TvDesign.Ink.secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("Lecture", KetIcons.Play, { actions.play(tracks) }, Modifier.pageItem("buttons", "play", first = true),
                        primary = true, tag = "collection-play")
                    TvButton("Aléatoire", KetIcons.Shuffle, { actions.play(tracks, shuffle = true) }, Modifier.pageItem("buttons", "shuffle")
                        // Right from the buttons: the list's first title (or the last one focused), never the nearest by geometry.
                        .focusProperties { if (tracks.isNotEmpty()) right = listHead },
                        tag = "collection-shuffle")
                }
            }
            TvTrackList(tracks, library, loading, onRetry = { attempt++ }, modifier = Modifier.weight(1f).fillMaxHeight(), head = listHead)
        }
    }
}

/** The titles of a page, one per line: number, cover, title and artist, duration. */
@Composable
fun TvTrackList(tracks: List<Track>, library: TvLibrary, loading: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier,
                head: androidx.compose.ui.focus.FocusRequester = remember { androidx.compose.ui.focus.FocusRequester() },
                row: String = "tracks") {
    val actions = LocalTvActions.current
    val playingId by remember { Player.state.pick { it.current?.id } }.collectPick()
    Box(modifier) {
        when {
            loading -> Text("Chargement…", style = TvDesign.Type.body, color = TvDesign.Ink.secondary, modifier = Modifier.padding(24.dp))
            tracks.isEmpty() -> Column(Modifier.padding(end = TvDesign.Safe.horizontal)) {
                TvEmpty("Aucun titre", "Cette liste est vide, ou le catalogue n'a pas répondu.")
                TvButton("Réessayer", KetIcons.Refresh, onRetry, Modifier.padding(start = TvDesign.Safe.horizontal).pageItem(row, "retry"))
            }
            else -> TvPivot(pivot = .3f) {
                // Entering the list again: its last focused title.
                LazyColumn(Modifier.fillMaxSize().focusRestorer(head).focusGroup().testTag("track-list"), state = rememberLazyListState(),
                    contentPadding = PaddingValues(end = TvDesign.Safe.horizontal, bottom = 60.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(tracks, key = { i, t -> "${t.id}:$i" }) { index, track ->
                        TvTrackRow(index, track, library.cover(track), track.id == playingId,
                            Modifier.pageItem(row, "${track.id}:$index").then(if (index == 0) Modifier.focusRequester(head) else Modifier),
                            onClick = { actions.play(tracks, index) }, onMenu = { actions.menu.value = track })
                    }
                }
            }
        }
    }
}

@Composable
fun TvTrackRow(index: Int, track: Track, cover: String?, playing: Boolean, focus: Modifier, onClick: () -> Unit, onMenu: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(focus.tvFocusable(onClick, shape, onLongClick = onMenu, scale = 1.02f, edge = 2.dp, tag = "track-$index")
        .fillMaxWidth().height(TvDesign.Size.trackRow).clip(shape)
        .background(if (playing) Color.White.copy(alpha = .1f) else Color.Transparent).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("${index + 1}", localize = false, style = TvDesign.Type.caption, color = TvDesign.Ink.tertiary, modifier = Modifier.width(32.dp))
        TvCover(cover, 40.dp, RoundedCornerShape(8.dp), placeholder = KetIcons.Music)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, localize = false, style = TvDesign.Type.label, color = TvDesign.Ink.primary, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(track.artist, localize = false, style = TvDesign.Type.caption, color = TvDesign.Ink.secondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        if (track.durationMs > 0) Text(TvTime.format(track.durationMs), localize = false, style = TvDesign.Type.caption,
            color = TvDesign.Ink.tertiary)
    }
}

/** An artist: the photo behind, the name, play and radio, then popular titles, albums and similar artists. */
@Composable
fun TvArtistPage(route: TvRoute.Artist) {
    val actions = LocalTvActions.current
    val library = rememberTvLibrary()
    var attempt by remember { mutableIntStateOf(0) }
    val page by produceState<ArtistPage?>(null, route, attempt) {
        value = runCatching { ArtistPages.cachedOrFetch(route.id.takeIf { it.startsWith("UC") }.orEmpty(), route.name) }.getOrNull()
    }
    var loaded by remember(route, attempt) { mutableStateOf(false) }
    LaunchedEffect(page) { if (page != null) loaded = true }
    val photo = page?.artist?.photo ?: route.picture
    val own = remember(library, route.name) {
        library.known.filter { PersonalMixes.primaryArtist(it).equals(route.name, ignoreCase = true) }
    }
    val top = page?.tracks?.ifEmpty { null } ?: own
    val shelves = remember(page, own) { artistShelves(page, own, library) }
    Box(Modifier.fillMaxSize()) {
        TvBackdrop(photo, Modifier.fillMaxSize().underRail(), veil = .6f)
        TvPivot(pivot = .16f, slack = .15f) {
            LazyColumn(Modifier.fillMaxSize().testTag("tv-artist"), contentPadding = PaddingValues(top = TvDesign.Safe.vertical, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(TvDesign.Size.rowSpacing)) {
                item(key = "header") {
                    Row(Modifier.padding(horizontal = TvDesign.Safe.horizontal), verticalAlignment = Alignment.CenterVertically) {
                        TvCover(photo, 168.dp, CircleShape, placeholder = KetIcons.Account)
                        Spacer(Modifier.width(28.dp))
                        Column {
                            Text(page?.artist?.name ?: route.name, localize = false, style = TvDesign.Type.display, color = TvDesign.Ink.primary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("artist-name"))
                            page?.listeners?.takeIf { it.isNotBlank() }?.let {
                                Text(it, localize = false, style = TvDesign.Type.body, color = TvDesign.Ink.secondary, maxLines = 1)
                            }
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TvButton("Lecture", KetIcons.Play, { actions.play(top) }, Modifier.pageItem("buttons", "play", first = true),
                                    primary = true, tag = "artist-play")
                                TvButton("Lancer la radio", KetIcons.Shuffle, {
                                    Player.startArtistRadio(route.name, route.id.takeIf { id -> id.all(Char::isDigit) && id.isNotEmpty() }, top)
                                    actions.nav.open(TvRoute.NowPlaying)
                                }, Modifier.pageItem("buttons", "radio"), tag = "artist-radio")
                            }
                        }
                    }
                }
                items(shelves, key = { it.key }) { shelf -> TvShelfRow(shelf, actions::open, onMenu = actions::options) }
                if (shelves.isEmpty() && loaded) item(key = "retry") {
                    TvButton("Réessayer", KetIcons.Refresh, { attempt++ }, Modifier.padding(start = TvDesign.Safe.horizontal))
                }
            }
        }
    }
}

private fun artistShelves(page: ArtistPage?, own: List<Track>, library: TvLibrary): List<TvShelf> {
    val top = page?.tracks.orEmpty().take(TvRows.ROW_MAX)
    return listOfNotNull(
        top.takeIf { it.isNotEmpty() }?.let { list -> TvShelf("top", "Titres populaires", list.mapIndexed { i, t -> TvRows.track(t, list, i, library::cover) }) },
        own.take(TvRows.ROW_MAX).takeIf { it.isNotEmpty() }?.let { list ->
            TvShelf("own", "Dans votre bibliothèque", list.mapIndexed { i, t -> TvRows.track(t, list, i, library::cover) }) },
        page?.albums?.takeIf { it.isNotEmpty() }?.let { albums -> TvShelf("albums", "Albums et singles", albums.map(TvRows::album)) },
        page?.related?.takeIf { it.isNotEmpty() }?.let { related ->
            TvShelf("related", "Artistes similaires", related.map { a ->
                TvItem("ar:${a.id}", TvItemKind.Artist, a.name, "", a.photo, TvRoute.Artist(a.id, a.name, a.photo))
            })
        },
    )
}

/** « 3:25 », « 1:02:10 ». */
object TvTime {
    fun format(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
