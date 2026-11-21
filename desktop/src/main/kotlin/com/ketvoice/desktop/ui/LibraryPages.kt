package com.ketvoice.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.Track
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.desktop.ui.LibraryViews.SortKey
import com.ketvoice.desktop.ui.theme.DeskGlyphs
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet

@Composable
private fun librarySongs(): List<Track> {
    val lib = LocalLibrary.current
    val f by lib.favourites.collectAsState(); val p by lib.playlists.collectAsState(); val d by lib.downloads.collectAsState()
    return remember(f, p, d) { LibraryViews.songs(lib) }
}

/** « Titres »: Music for macOS's table — sortable columns, striped rows, double-click plays. */
@Composable
fun SongsPage() {
    val ket = LocalKet.current
    val songs = librarySongs()
    var key by remember { mutableStateOf(SortKey.TITLE) }
    var ascending by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<String?>(null) }
    val sorted = remember(songs, key, ascending) { LibraryViews.sort(songs, key, ascending) }
    fun sortBy(k: SortKey) { if (key == k) ascending = !ascending else { key = k; ascending = true } }
    Column(Modifier.fillMaxSize().padding(horizontal = Ket27Desk.Space.page).testTag("page:songs")) {
        Spacer(Modifier.height(22.dp))
        KText("Titres", Ket27Desk.Type.largeTitle)
        Spacer(Modifier.height(16.dp))
        if (songs.isEmpty()) { EmptyMessage("Aucun titre", "Les titres que vous aimez, téléchargez ou ajoutez à une playlist apparaissent ici."); return }
        Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(44.dp))
            HeaderCell("Titre", SortKey.TITLE, key, ascending, Modifier.weight(3f)) { sortBy(it) }
            HeaderCell("Artiste", SortKey.ARTIST, key, ascending, Modifier.weight(2f)) { sortBy(it) }
            HeaderCell("Album", SortKey.ALBUM, key, ascending, Modifier.weight(2f)) { sortBy(it) }
            HeaderCell("Durée", SortKey.DURATION, key, ascending, Modifier.width(70.dp)) { sortBy(it) }
        }
        Box(Modifier.fillMaxWidth().height(.5.dp).background(ket.hairline))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            itemsIndexed(sorted, key = { _, t -> t.id }) { i, t ->
                TableRow(t, sorted, i, striped = i % 2 == 1, selected = selected == t.id) { selected = t.id }
            }
        }
    }
}

@Composable
private fun HeaderCell(label: String, k: SortKey, current: SortKey, ascending: Boolean, modifier: Modifier, onClick: (SortKey) -> Unit) {
    val ket = LocalKet.current
    Row(modifier.clickable { onClick(k) }.testTag("sort:${k.name}"), verticalAlignment = Alignment.CenterVertically) {
        KText(label, Ket27Desk.Type.caption, color = if (k == current) ket.label else ket.secondary, weight = FontWeight.SemiBold)
        if (k == current) Icon(if (ascending) DeskGlyphs.ChevronUp else DeskGlyphs.ChevronDown, null, Modifier.padding(start = 4.dp).size(10.dp), tint = ket.secondary)
    }
}

@Composable
private fun TableRow(t: Track, list: List<Track>, index: Int, striped: Boolean, selected: Boolean, onSelect: () -> Unit) {
    val ket = LocalKet.current
    val (source, hovered) = rememberHover()
    val playing = isCurrent(t)
    val bg = when { selected -> ket.accent.copy(alpha = .9f); hovered -> ket.hover; striped -> ket.fill.copy(alpha = ket.fill.alpha * .45f); else -> Color.Transparent }
    val fg = if (selected) Color.White else ket.label
    val fg2 = if (selected) Color.White.copy(alpha = .85f) else ket.secondary
    Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(6.dp)).background(bg).hoverable(source)
        .trackInteractions(t, list, index, onSelect).padding(horizontal = 8.dp).testTag("row:${t.id}"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(44.dp)) { Artwork(t.coverUrl, 30.dp, radius = 4.dp) }
        Cell(t.title, Modifier.weight(3f), if (playing && !selected) ket.accent else fg, FontWeight.Medium)
        Cell(t.artist, Modifier.weight(2f), fg2)
        Cell(t.album, Modifier.weight(2f), fg2)
        Cell(formatTime(t.durationMs), Modifier.width(70.dp), fg2)
    }
}

@Composable
private fun Cell(text: String, modifier: Modifier, color: Color, weight: FontWeight? = null) =
    KText(text, Ket27Desk.Type.footnote, modifier.padding(end = 12.dp), color = color, weight = weight, translate = false)

/** Albums and artists as Music's grids: covers sized to the window. */
@Composable
private fun <T> Grid(title: String, tag: String, items: List<T>, min: androidx.compose.ui.unit.Dp, empty: String, cell: @Composable (T) -> Unit) {
    LazyVerticalGrid(GridCells.Adaptive(min), Modifier.fillMaxSize().testTag("page:$tag"),
        contentPadding = PaddingValues(start = Ket27Desk.Space.page, end = Ket27Desk.Space.page, top = 22.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(Ket27Desk.Space.l), verticalArrangement = Arrangement.spacedBy(Ket27Desk.Space.xl)) {
        item(span = { GridItemSpan(maxLineSpan) }) { KText(title, Ket27Desk.Type.largeTitle, Modifier.padding(bottom = 8.dp)) }
        if (items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyMessage(title, empty) }
        items(items) { cell(it) }
    }
}

@Composable
fun AlbumsPage() {
    val nav = LocalNavigator.current
    val actions = LocalTrackActions.current
    val songs = librarySongs()
    val albums = remember(songs) { LibraryViews.albums(songs) }
    Grid("Albums", "albums", albums, 180.dp, "Les albums de vos titres apparaissent ici.") { a ->
        androidx.compose.foundation.layout.BoxWithConstraints { AlbumCard(a, maxWidth, { nav.push(Route.Album(a)) }, {
            val list = songs.filter { it.album.equals(a.title, true) }; actions.play(list, 0) }) }
    }
}

@Composable
fun RecentlyAddedPage() {
    val nav = LocalNavigator.current
    val lib = LocalLibrary.current
    val added by lib.added.collectAsState()
    val songs = librarySongs()
    val albums = remember(songs, added) { LibraryViews.recentlyAdded(songs, added) }
    Grid("Récemment ajouté", "recent", albums, 180.dp, "Ce que vous ajoutez à votre bibliothèque apparaît ici.") { a ->
        androidx.compose.foundation.layout.BoxWithConstraints { AlbumCard(a, maxWidth, { nav.push(Route.Album(a)) }) }
    }
}

@Composable
fun ArtistsPage() {
    val nav = LocalNavigator.current
    val songs = librarySongs()
    val artists = remember(songs) { LibraryViews.artists(songs) }
    Grid("Artistes", "artists", artists, 160.dp, "Les artistes de vos titres apparaissent ici.") { a ->
        androidx.compose.foundation.layout.BoxWithConstraints { ArtistCard(a, maxWidth) { nav.push(Route.Artist(a)) } }
    }
}

/** « Téléchargements »: each title with its progress ring, a check once on the disk. */
@Composable
fun DownloadsPage() {
    val ket = LocalKet.current
    val downloads by LocalLibrary.current.downloads.collectAsState()
    val tracks = downloads.map { it.track }
    Page("Téléchargements", "downloads") {
        if (downloads.isEmpty()) { item { EmptyMessage("Aucun téléchargement", "Téléchargez des titres depuis leur menu pour les écouter hors connexion.") }; return@Page }
        val pending = downloads.count { !it.done }
        item("meta") {
            KText(if (pending > 0) "${downloads.size - pending} / ${downloads.size}" else "${downloads.size}", Ket27Desk.Type.footnote,
                Modifier.padding(bottom = 14.dp), color = ket.secondary, translate = false)
        }
        downloads.forEachIndexed { i, d ->
            item(d.track.id) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompactTrackRow(d.track, tracks, i, Modifier.weight(1f))
                    Box(Modifier.size(40.dp).testTag("download:${d.track.id}"), contentAlignment = Alignment.Center) {
                        if (d.done) Icon(KetIcons.Check, null, Modifier.size(16.dp), tint = ket.secondary) else ProgressRing(d.progress)
                    }
                }
            }
        }
    }
}

