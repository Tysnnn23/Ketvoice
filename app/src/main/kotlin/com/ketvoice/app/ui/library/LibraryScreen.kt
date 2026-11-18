package com.ketvoice.app.ui.library

import com.ketvoice.app.theme.pageBackground
import com.ketvoice.app.ui.common.trackRowClick
import com.ketvoice.app.ui.common.asHeading
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.*
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen() {
    val theme = LocalTheme.current
    val context = LocalContext.current
    val recent by ListeningHistory.tracks.collectAsState()
    val known by ListeningHistory.knownTracks.collectAsState()
    val downloads by Downloader.items.collectAsState()
    val playlists by PlaylistStore.lists.collectAsState()
    val loadError by PlaylistStore.loadError.collectAsState()
    val scope = rememberCoroutineScope()
    // Shared playlists (6.3) are read again when the Library opens.
    LaunchedEffect(Unit) { com.ketvoice.app.sync.SharedPlaylists.refreshSoon() }
    var error by remember { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var view by rememberSaveable { mutableIntStateOf(0) }
    var order by rememberSaveable { mutableIntStateOf(0) }
    var album by rememberSaveable { mutableStateOf<String?>(null) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    // The sidebar of a large window opens a section of the library directly, and lights the one shown (ui/tablet).
    val askedSection = com.ketvoice.app.ui.adaptive.LargeScreenNav.librarySection.value
    LaunchedEffect(askedSection) { if (askedSection != null) {
        view = askedSection; album = null; selecting = false; selected = emptyList(); query = ""
        com.ketvoice.app.ui.adaptive.LargeScreenNav.librarySection.value = null } }
    SideEffect { com.ketvoice.app.ui.adaptive.LargeScreenNav.shownLibrarySection.intValue = view }
    val all = remember(recent, known, downloads) { (recent + downloads.map { it.track } + known).distinctBy { it.id } }
    val groups = remember(all) { localAlbums(all) }
    val albums = remember(groups, order) {
        val byTrack = groups.associateBy { it.tracks.first().id }
        AlbumOrder.sort(groups.map { it.tracks.first() }, order).mapNotNull { byTrack[it.id] }
    }
    val chosen = groups.firstOrNull { it.key == album }
    val shown = (chosen?.tracks ?: all).filter { track ->
        query.isBlank() || listOf(track.title, track.artist, track.album).any { it.contains(query.trim(), true) }
    }
    val selectedTracks = all.filter { it.id in selected }
    // The library of Apple Music (Ket 27): categories in a list, then what was added lately.
    val appleRoot = view == 0 && query.isBlank() && chosen == null
    val list = rememberLazyListState()
    LaunchedEffect(all) { selected = selected.filter { id -> all.any { it.id == id } } }
    // Ket 27: the large title shrinks while the list scrolls and hands over to a small one in the bar.
    val handover = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
    val collapse = { ScrollTitle.collapse(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, handover) }
    LaunchedEffect(list) {
        val fold = com.ketvoice.app.ui.chrome.ChromeCollapse()
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> AppUi.bottomCollapsed.value = fold.update(index, offset) }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw java.io.IOException()
                val parsed = if (raw.trimStart().startsWith("{")) PlaylistExchange.parseJson(raw) else PlaylistExchange.parseM3u(raw)
                PlaylistStore.create(parsed.name, parsed.entries, parsed.spotifyUrl)
            }.onFailure { error = "Fichier de playlist illisible ou invalide" }
        }
    }
    fun pin(playlist: LocalPlaylist) { scope.launch {
        runCatching { PlaylistStore.pin(playlist.id, !playlist.pinned) }.onFailure { error = "Impossible de lire les playlists" }
    } }
    val backdrop = com.ketvoice.app.glass.rememberGlassBackdrop(theme.background)
    Box(Modifier.fillMaxSize().pageBackground(theme)) {
    com.ketvoice.app.glass.BackdropCapture(backdrop, Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().testTag("library-list"), state = list,
        contentPadding = PaddingValues(top = 70.dp, bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp)) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp)) {
                com.ketvoice.app.ui.common.PageTitle("Bibliothèque", Modifier.shrinkingTitle(collapse).asHeading(),
                    maxLines = Int.MAX_VALUE)
                Text(AppText.t(if (all.size == 1) "1 titre" else "${all.size} titres") + " · " +
                    AppText.t("Playlists") + " " + playlists.size, localize = false, color = theme.secondary,
                    fontSize = 14.sp, modifier = Modifier.padding(top = 5.dp, bottom = 18.dp))
                CompactSearchField(query, { query = it }, {}, Modifier.testTag("library-search"))
            }
            if (!appleRoot) LazyRow(Modifier.testTag("library-filters"), contentPadding = PaddingValues(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(listOf("Tout", "Playlists", "Albums", "Titres", "Artistes")) { index, label ->
                    CollectionFilter(label, view == index, {
                        view = index; album = null; selecting = false; selected = emptyList()
                    }, Modifier.padding(bottom = 10.dp))
                }
            }
        }
        if (chosen != null) item(key = "album-heading") {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                com.ketvoice.app.ui.common.KetTextButton("Albums", { album = null })
                Text(chosen.title, localize = false, color = theme.onBackground, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(chosen.artist, localize = false, color = theme.secondary, fontSize = 14.sp)
                CollectionPlaybackActions(chosen.tracks, Modifier.fillMaxWidth().padding(top = 16.dp))
            }
        }
        if (appleRoot) {
            item(key = "apple-categories") {
                AppleLibraryCategories(listOf(
                    Triple("Playlists", KetIcons.Music) { view = 1 }, Triple("Artistes", KetIcons.Account) { view = 4 },
                    Triple("Albums", KetIcons.Album) { view = 2 }, Triple("Titres", KetIcons.Lyrics) { view = 3 },
                    Triple("Favoris", KetIcons.Heart) { AppUi.openCatalog(CatalogRequest("favorites", "local", "Favoris")) },
                    Triple("Téléchargements", KetIcons.Download) { AppUi.openDownloads() },
                    Triple("Musique locale", KetIcons.Folder) { AppUi.openLocalFiles() },
                    Triple("Replay", KetIcons.Clock) { AppUi.openReplay() }))
            }
            // Smart library: playlists generated by mood, with the import and duplicates entries.
            item(key = "ambiances") { com.ketvoice.app.ui.smartlib.AmbiancesShelf() }
            if (playlists.any { it.pinned }) item(key = "pinned") {
                LibrarySection("Épinglés")
                PlaylistShelf(playlists.filter { it.pinned }, if (loadError) null else ::pin)
            }
            if (groups.isNotEmpty()) item(key = "apple-recent") {
                LibrarySection("Ajouts récents")
                CoverGrid(groups.take(8), Modifier.testTag("library-recent")) { group, side ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { album = group.key }) {
                        Cover(group.title, ArtworkRepo.coverForTrack(group.tracks.first()) ?: group.tracks.first().coverUrl, side, 10.dp)
                        Text(group.title, localize = false, color = theme.onBackground, fontWeight = FontWeight.Medium,
                            fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
                        Text(group.artist, localize = false, color = theme.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (view in 0..1 && chosen == null && !appleRoot) {
            val rows = playlists.filter { query.isBlank() || it.name.contains(query.trim(), true) }
                .filterNot { view == 0 && query.isBlank() && it.pinned }
            item(key = "playlists") {
                LibrarySection("Playlists")
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    com.ketvoice.app.ui.common.KetTextButton("Créer une playlist", { AppUi.showCreatePlaylist.value = true }, enabled = !loadError)
                    com.ketvoice.app.ui.common.KetTextButton("Importer des playlists", { AppUi.openPlaylistImport() }, enabled = !loadError)
                }
                com.ketvoice.app.ui.common.KetTextButton("Rejoindre avec un code", { SharedUi.openJoin(null) }, enabled = !loadError,
                    modifier = Modifier.padding(horizontal = 14.dp))
                // The Playlists view shows a grid of large covers; "All" keeps a row.
                if (rows.isNotEmpty()) { if (view == 1) PlaylistGrid(rows, if (loadError) null else ::pin) else PlaylistShelf(rows, if (loadError) null else ::pin) }
                com.ketvoice.app.ui.common.KetTextButton("Importer un fichier · .json / .m3u", { importFile.launch(arrayOf("application/json", "text/*", "audio/x-mpegurl", "audio/mpegurl", "*/*")) },
                    enabled = !loadError, modifier = Modifier.padding(horizontal = 14.dp))
                if (loadError || error.isNotBlank()) Text(if (error.isNotBlank()) error else "Impossible de lire les playlists",
                    color = theme.secondary, modifier = Modifier.padding(18.dp))
            }
        }
        if ((view == 0 || view == 2) && !appleRoot) {
            val rows = albums.filter { query.isBlank() || it.title.contains(query.trim(), true) || it.artist.contains(query.trim(), true) }
            if (rows.isNotEmpty() && chosen == null) item(key = "albums") {
                LibrarySection("Albums")
                // Ket 27: the orders as glass pills, the one in use filled.
                if (rows.size > 1) LazyRow(Modifier.testTag("library-sort"), contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    item { Text("Trier", color = theme.secondary, fontSize = 13.sp, modifier = Modifier.padding(end = 2.dp)) }
                    itemsIndexed(AlbumOrder.labels) { index, label -> CollectionFilter(label, order == index, { order = index }) }
                }
                if (view == 2) CoverGrid(rows, Modifier.testTag("library-albums")) { group, side ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button) { album = group.key }) {
                        Cover(group.title, ArtworkRepo.coverForTrack(group.tracks.first()) ?: group.tracks.first().coverUrl, side, 18.dp)
                        Text(group.title, localize = false, color = theme.onBackground, fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                        Text(group.artist, localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else LazyRow(Modifier.testTag("library-albums"), contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(rows, key = { it.key }) { group ->
                        Column(Modifier.width(148.dp).clickable(role = Role.Button) { album = group.key }) {
                            Cover(group.title, ArtworkRepo.coverForTrack(group.tracks.first()) ?: group.tracks.first().coverUrl, 148.dp, 18.dp)
                            Text(group.title, localize = false, color = theme.onBackground, fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                            Text(group.artist, localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (view == 2 && chosen == null && albums.isEmpty()) item(key = "no-albums") {
                EmptyState("Aucun album", "Les titres sans album restent disponibles dans Titres.", "Titres", icon = com.ketvoice.app.ui.common.KetIcons.Album, onAction = { view = 3 })
            }
        }
        if ((view == 0 || view == 4) && chosen == null && !appleRoot) {
            val artists = all.filter { it.artist.isNotBlank() }.groupBy { it.artist.substringBefore(',').trim() }
                .filterKeys { query.isBlank() || it.contains(query.trim(), true) }
            if (artists.isNotEmpty()) item(key = "artists") {
                LibrarySection("Artistes")
                // The Artists view shows them as a grid of portraits; "All" keeps a row.
                if (view == 4) CoverGrid(artists.entries.toList(), Modifier.testTag("library-artist-grid")) { (name, tracks), side ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) {
                        AppUi.openArtist(tracks.first().artistId.orEmpty(), name)
                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Cover(name, ArtworkRepo.coverForTrack(tracks.first()) ?: tracks.first().coverUrl, side, side / 2)
                        Text(name, localize = false, color = theme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                        Text(AppText.t(if (tracks.size == 1) "1 titre" else "${tracks.size} titres"), localize = false,
                            color = theme.secondary, fontSize = 12.sp, maxLines = 1)
                    }
                } else LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(artists.entries.toList(), key = { it.key }) { (name, tracks) ->
                        val face = ArtworkRepo.coverForTrack(tracks.first()) ?: tracks.first().coverUrl
                        val zoom = com.ketvoice.app.ui.motion.rememberZoomSource(face, round = true)
                        Column(Modifier.width(100.dp).clickable { zoom.openArtist(tracks.first().artistId.orEmpty(), name) },
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Cover(name, face, 90.dp, 45.dp, modifier = com.ketvoice.app.ui.motion.zoomSourceOf(zoom))
                            Text(name, localize = false, color = theme.onBackground, fontSize = 13.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
        if ((view == 0 && !appleRoot) || view == 3 || chosen != null) {
            item(key = "tracks-heading") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { LibrarySection("Titres") }
                    if (all.isNotEmpty()) com.ketvoice.app.ui.common.KetTextButton(if (selecting) "Annuler" else "Sélectionner",
                        { selecting = !selecting; selected = emptyList() })
                }
                if (selecting) Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    com.ketvoice.app.ui.common.KetTextButton("Tout sélectionner", { selected = shown.map { it.id } })
                    IconButton(onClick = { Player.playQueue(selectedTracks) }, enabled = selectedTracks.isNotEmpty()) { Icon(KetIcons.Play, "Lecture", tint = theme.accent) }
                    IconButton(onClick = { Player.enqueue(selectedTracks); selected = emptyList(); selecting = false }, enabled = selectedTracks.isNotEmpty()) {
                        Icon(Icons.Filled.QueueMusic, "Ajouter à la file", tint = theme.accent)
                    }
                    // Several titles into a playlist at once (5.9.1).
                    IconButton(onClick = { AppUi.addTracksToPlaylist.value = selectedTracks; selected = emptyList(); selecting = false },
                        enabled = selectedTracks.isNotEmpty(), modifier = Modifier.testTag("library-selection-playlist")) {
                        Icon(KetIcons.Plus, "Ajouter à une playlist", tint = theme.accent)
                    }
                    Text(selected.size.toString(), localize = false, color = theme.secondary)
                }
            }
            items(shown, key = { it.id }) { track ->
                com.ketvoice.app.ui.common.SwipeTrackActions(track,
                    if (com.ketvoice.app.ui.motion.Motion.reduced) Modifier else Modifier.animateItem(), enabled = !selecting) {
                Row(Modifier.fillMaxWidth().then(
                    if (selecting) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Checkbox) {
                        selected = if (track.id in selected) selected - track.id else selected + track.id
                    } else Modifier.trackRowClick(track) { Player.playQueue(shown, shown.indexOfFirst { it.id == track.id }) }
                ).padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) com.ketvoice.app.ui.common.KetCheckbox(track.id in selected, onCheckedChange = null)
                    DownloadCover(track, ArtworkRepo.coverForTrack(track) ?: track.coverUrl, 48.dp, 10.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        TrackTitle(track.title, color = theme.onBackground)
                        Text(track.artist, localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!selecting) {
                        val menuAnchor = com.ketvoice.app.ui.common.rememberRowAnchor()
                        IconButton(onClick = { menuAnchor.open(track) },
                            modifier = menuAnchor.modifier) { Icon(KetIcons.More, "Options", tint = theme.secondary) }
                    }
                }
                }
            }
        }
        if (all.isEmpty() && playlists.isEmpty()) item {
            EmptyState("Votre bibliothèque est vide", "Écoutez un titre ou téléchargez-le pour le retrouver ici, même hors ligne.", "Rechercher des titres", icon = KetIcons.Library, onAction = ::openTrackSearch)
        }
        else if (query.isNotBlank() && shown.isEmpty() && playlists.none { it.name.contains(query.trim(), true) }) item {
            EmptyState("Aucun résultat", "Essayez un autre titre ou artiste.", "Effacer", icon = KetIcons.Search, onAction = { query = "" })
        }
    }
    }
    ScrollEdgeTitle("Bibliothèque", collapse, Modifier.align(Alignment.TopCenter), tag = "library-inline-title")
    CompositionLocalProvider(com.ketvoice.app.glass.LocalBackdropLayer provides backdrop) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            LiquidGlassButton({ AppUi.showCreatePlaylist.value = true }, Modifier.size(48.dp), enabled = !loadError) {
                Icon(KetIcons.Plus, "Créer une playlist", tint = theme.onBackground)
            }
            Spacer(Modifier.width(10.dp))
            LiquidGlassButton({ AppUi.showAccount.value = true }, Modifier.size(48.dp)) {
                // Signed in to Ketvoice: the profile photo in place of the glyph.
                if (com.ketvoice.app.account.AccountSession.state.collectAsState().value.signedIn)
                    com.ketvoice.app.ui.common.AccountAvatar("K", 36.dp)
                else Icon(KetIcons.Account, "Compte", tint = theme.onBackground)
            }
        }
    }
    }
}

@Composable
internal fun PlaylistShelf(rows: List<LocalPlaylist>, onPin: ((LocalPlaylist) -> Unit)? = null) {
    val theme = LocalTheme.current
    LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(rows, key = { it.id }) { playlist ->
            val zoom = com.ketvoice.app.ui.motion.rememberZoomSource(18.dp) { place -> PlaylistArtwork(playlist, playlist.name, place) }
            Column(Modifier.width(148.dp)) {
                PlaylistArtwork(playlist, playlist.name, Modifier.size(148.dp).then(com.ketvoice.app.ui.motion.zoomSourceOf(zoom))
                    .clickable { zoom.openPlaylist(playlist.id) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(playlist.name, localize = false, color = theme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(top = 6.dp))
                    if (onPin != null) IconButton(onClick = { onPin(playlist) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.PushPin, if (playlist.pinned) "Désépingler" else "Épingler",
                            tint = if (playlist.pinned) theme.accent else theme.secondary, modifier = Modifier.size(18.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (playlist.entries.size == 1) "1 titre" else "${playlist.entries.size} titres", color = theme.secondary, fontSize = 12.sp)
                    if (com.ketvoice.app.data.PlaylistRules.isShared(playlist.id)) SharedBadge(Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

/** Number of columns of a cover grid for a pane [widthDp] wide: two on a phone, more on wider panes. */
internal fun coverGridColumns(widthDp: Float): Int = ((widthDp - 36f + 14f) / (170f + 14f)).toInt().coerceIn(2, 6)

/** Large covers in a grid, Apple Music style: each cell is told the side its cover must take. */
@Composable
internal fun <T> CoverGrid(rows: List<T>, modifier: Modifier = Modifier, cell: @Composable (T, androidx.compose.ui.unit.Dp) -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        val columns = coverGridColumns(maxWidth.value + 36f)
        val side = (maxWidth - 14.dp * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            rows.chunked(columns).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    line.forEach { item -> Box(Modifier.width(side)) { cell(item, side) } }
                }
            }
        }
    }
}

@Composable
internal fun PlaylistGrid(rows: List<LocalPlaylist>, onPin: ((LocalPlaylist) -> Unit)? = null) {
    val theme = LocalTheme.current
    CoverGrid(rows, Modifier.testTag("library-playlist-grid")) { playlist, side ->
        Column(Modifier.fillMaxWidth()) {
            val zoom = com.ketvoice.app.ui.motion.rememberZoomSource(18.dp) { place -> PlaylistArtwork(playlist, playlist.name, place) }
            PlaylistArtwork(playlist, playlist.name, Modifier.size(side).then(com.ketvoice.app.ui.motion.zoomSourceOf(zoom))
                .clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button) { zoom.openPlaylist(playlist.id) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text(playlist.name, localize = false, color = theme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (playlist.entries.size == 1) "1 titre" else "${playlist.entries.size} titres", color = theme.secondary, fontSize = 12.sp)
                        if (com.ketvoice.app.data.PlaylistRules.isShared(playlist.id)) SharedBadge(Modifier.padding(start = 6.dp))
                    }
                }
                if (onPin != null) IconButton(onClick = { onPin(playlist) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.PushPin, if (playlist.pinned) "Désépingler" else "Épingler",
                        tint = if (playlist.pinned) theme.accent else theme.secondary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun LibrarySection(title: String) {
    com.ketvoice.app.ui.common.SectionHeader(title, Modifier.padding(horizontal = 18.dp, vertical = 16.dp))
}

/** The categories of the library as Apple Music lists them: a symbol in the accent, a name, a chevron. */
@Composable
private fun AppleLibraryCategories(rows: List<Triple<String, androidx.compose.ui.graphics.vector.ImageVector, () -> Unit>>) {
    val theme = LocalTheme.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("library-categories")) {
        rows.forEachIndexed { index, (title, icon, open) ->
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = open).heightIn(min = 50.dp)
                .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = theme.accent, modifier = Modifier.size(24.dp))
                Text(title, color = theme.onBackground, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 16.dp))
                Icon(KetIcons.ChevronRight, null, tint = theme.secondary.copy(alpha = .6f), modifier = Modifier.size(16.dp))
            }
            if (index != rows.lastIndex) Box(Modifier.padding(start = 44.dp).fillMaxWidth().height(.5.dp).background(theme.hairline))
        }
    }
}
