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
import androidx.compose.ui.graphics.graphicsLayer
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
    // 7.1: Titles (Récents, Titre A–Z, Artiste A–Z) and Artists (Récents, Nom A–Z) sort by name too, with an A–Z index.
    var trackOrder by rememberSaveable { mutableIntStateOf(0) }
    var artistOrder by rememberSaveable { mutableIntStateOf(0) }
    var album by rememberSaveable { mutableStateOf<String?>(null) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    // Library › Playlists (Apple Music): a list or a grid, its order and its filter, from the « … » of the bar.
    var playlistGrid by rememberSaveable { mutableStateOf(false) }
    var playlistSortName by rememberSaveable { mutableStateOf(PlaylistSort.Recent.name) }
    var playlistFilterName by rememberSaveable { mutableStateOf(PlaylistFilter.All.name) }
    val playlistSort = PlaylistSort.entries.firstOrNull { it.name == playlistSortName } ?: PlaylistSort.Recent
    val playlistFilter = PlaylistFilter.entries.firstOrNull { it.name == playlistFilterName } ?: PlaylistFilter.All
    val owner = playlistOwner()
    val large = com.ketvoice.app.ui.adaptive.LocalLargeScreen.current
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
            .let { sorted -> if (order in 1..2) IndexLetters.grouped(sorted) { if (order == 1) it.title else it.artist } else sorted }
    }
    val chosen = remember(groups, album) { groups.firstOrNull { it.key == album } }
    // Worked out when what they come from changes, not at every recomposition of the page (6.6).
    val shown = remember(chosen, all, query, view, trackOrder) {
        val q = query.trim()
        val found = (chosen?.tracks ?: all).filter { track ->
            q.isEmpty() || track.title.contains(q, true) || track.artist.contains(q, true) || track.album.contains(q, true)
        }
        if (chosen != null || view != 3 || trackOrder !in 1..2) found
        else {
            val name: (Track) -> String = { if (trackOrder == 1) it.title else it.artist }
            IndexLetters.grouped(found.sortedWith(compareBy(libraryCollator(), name)), name)
        }
    }
    val albumRows = remember(albums, query) {
        val q = query.trim()
        albums.filter { q.isEmpty() || it.title.contains(q, true) || it.artist.contains(q, true) }
    }
    val selectedTracks = remember(all, selected) { val ids = selected.toHashSet(); all.filter { it.id in ids } }
    val artists = remember(all, query, artistOrder) {
        all.filter { it.artist.isNotBlank() }.groupBy { it.artist.substringBefore(',').trim() }
            .filterKeys { query.isBlank() || it.contains(query.trim(), true) }.entries.toList()
            .let { found -> if (artistOrder == 1) IndexLetters.grouped(found.sortedWith(compareBy(libraryCollator()) { it.key })) { it.key } else found }
    }
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
    // The large title names what is shown: the library, or the category open in it.
    val pageTitle = when (view) { 1 -> "Playlists"; 2 -> "Albums"; 3 -> "Titres"; 4 -> "Artistes"; else -> "Bibliothèque" }
    // 7.0: what each album holds on the device, for the sign under its cover.
    val downloadedIds = remember(downloads) { downloads.mapTo(HashSet()) { it.track.id } }
    // An album opens as a real album page, with the download state of the album and of each title (7.0).
    if (chosen != null) {
        Box(Modifier.fillMaxSize().pageBackground(theme).statusBarsPadding()) {
            AlbumPage(chosen, { album = null }, backLabel = if (view == 2) "Albums" else "Bibliothèque",
                bottomPadding = LocalAdaptiveLayout.current.bottomPaddingDp.dp)
        }
        return
    }
    val backdrop = com.ketvoice.app.glass.rememberGlassBackdrop(theme.background)
    BoxWithConstraints(Modifier.fillMaxSize().pageBackground(theme)) {
    // The width the page's rows and covers share, its margins taken off.
    val paneDp = maxWidth.value - 2 * pageMargin.value
    com.ketvoice.app.glass.BackdropCapture(backdrop, Modifier.fillMaxSize()) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // The cover grids (Albums, Artists) are rows of the list itself, composed as they scroll in (6.6): a whole
    // grid in one item composed and measured every cover of the library at once when the view opened.
    // A large window sizes these covers as every artwork grid of the app does (LargeScreen.gridColumns).
    val gridGap = if (large != null) com.ketvoice.app.ui.adaptive.LargeScreen.GRID_GAP_DP.dp else 14.dp
    val gridColumns = if (large != null) com.ketvoice.app.ui.adaptive.LargeScreen.gridColumns(maxWidth.value - 36f)
        else coverGridColumns(maxWidth.value)
    val gridSide = (maxWidth - 36.dp - gridGap * (gridColumns - 1)) / gridColumns
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().testTag("library-list"), state = list,
        contentPadding = PaddingValues(top = 70.dp, bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp)) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp)) {
                com.ketvoice.app.ui.common.PageTitle(pageTitle, Modifier.shrinkingTitle(collapse).asHeading().testTag("library-title"),
                    maxLines = Int.MAX_VALUE)
                Text(if (view == 1) AppText.t(if (playlists.size == 1) "1 playlist" else "${playlists.size} playlists")
                    else AppText.t(if (all.size == 1) "1 titre" else "${all.size} titres") + " · " +
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
            // 7.2 (Apple Music, Spotify): Favoris and Téléchargements lead with their own covers, then the
            // categories, then the listener's playlists themselves, in the order chosen under Playlists.
            item(key = "library-pinned", contentType = "library-pinned") {
                LibraryPinnedCollections(AppSettings.favoriteIds.value.size, downloads)
            }
            // Beside the sidebar the categories are already listed there; beside a rail they stand in two columns.
            if (large?.navigation != com.ketvoice.app.ui.adaptive.NavigationMode.Sidebar) item(key = "apple-categories") {
                AppleLibraryCategories(columns = if (large != null) 2 else 1, rows = listOf(
                    Triple("Artistes", KetIcons.Account) { view = 4 }, Triple("Albums", KetIcons.Album) { view = 2 },
                    Triple("Titres", KetIcons.Lyrics) { view = 3 },
                    Triple("Musique locale", KetIcons.Folder) { AppUi.openLocalFiles() },
                    Triple("Replay", KetIcons.Clock) { AppUi.openReplay() },
                    Triple("Statistiques", com.ketvoice.app.ui.stats.StatsIcon) { AppUi.openStats() }))
            }
            item(key = "library-playlists-heading") { LibrarySection("Playlists", onMore = { view = 1 }) }
            playlistsSection(PlaylistsLayout.arrange(playlists, "", playlistSort, PlaylistFilter.All), false, paneDp, owner,
                enabled = !loadError, onPin = if (loadError) null else ::pin,
                onNew = { AppUi.showCreatePlaylist.value = true }, onImport = { AppUi.openPlaylistImport() })
            // Smart library: playlists generated by mood, with the import and duplicates entries.
            item(key = "ambiances") { com.ketvoice.app.ui.smartlib.AmbiancesShelf() }
            if (groups.isNotEmpty()) item(key = "apple-recent") {
                LibrarySection("Ajouts récents")
                // A phone shows two lines of two; a wide pane two whole lines of as many covers as fit.
                CoverGrid(groups.take(if (large == null) 8 else 2 * com.ketvoice.app.ui.adaptive.LargeScreen.gridColumns(paneDp - 4f)), Modifier.testTag("library-recent")) { group, side ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { album = group.key }) {
                        Cover(group.title, ArtworkRepo.coverForTrack(group.tracks.first()) ?: group.tracks.first().coverUrl, side, 10.dp)
                        Text(group.title, localize = false, color = theme.onBackground, fontWeight = FontWeight.Medium,
                            fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
                        Text(group.artist, localize = false, color = theme.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        AlbumDownloadBadge(AlbumDownloads.state(group, downloadedIds), Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
        if (view == 1 && chosen == null) {
            val rows = PlaylistsLayout.arrange(playlists, query, playlistSort, playlistFilter)
            playlistsSection(rows, playlistGrid, paneDp, owner, enabled = !loadError, onPin = if (loadError) null else ::pin,
                onNew = { AppUi.showCreatePlaylist.value = true }, onImport = { AppUi.openPlaylistImport() })
            if (loadError || error.isNotBlank()) item(key = "playlists-error") {
                Text(if (error.isNotBlank()) error else "Impossible de lire les playlists", color = theme.secondary,
                    modifier = Modifier.padding(18.dp))
            }
            if (rows.isEmpty() && playlists.isNotEmpty()) item(key = "playlists-none") {
                EmptyState("Aucun résultat", "Essayez un autre nom ou un autre filtre.", "Effacer", icon = KetIcons.Search,
                    onAction = { query = ""; playlistFilterName = PlaylistFilter.All.name })
            }
        }
        if (view == 0 && chosen == null && !appleRoot) {
            // Searching the whole library: the playlists whose name matches, as a shelf.
            val rows = playlists.filter { it.name.contains(query.trim(), true) }
            if (rows.isNotEmpty()) item(key = "playlists") {
                LibrarySection("Playlists", onMore = { view = 1 })
                PlaylistShelf(rows, if (loadError) null else ::pin)
            }
        }
        if ((view == 0 || view == 2) && !appleRoot) {
            val rows = albumRows
            if (rows.isNotEmpty() && chosen == null) item(key = "albums") {
                LibrarySection("Albums")
                // Ket 27: the orders as glass pills, the one in use filled.
                if (rows.size > 1) LazyRow(Modifier.testTag("library-sort"), contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    item { Text("Trier", color = theme.secondary, fontSize = 13.sp, modifier = Modifier.padding(end = 2.dp)) }
                    itemsIndexed(AlbumOrder.labels) { index, label -> CollectionFilter(label, order == index, { order = index }) }
                }
                if (view != 2) LazyRow(Modifier.testTag("library-albums"), contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(rows, key = { it.key }) { group ->
                        com.ketvoice.app.ui.sub.PrefetchAlbumCanvas(group.tracks.first())
                        Column(Modifier.width(148.dp).clickable(role = Role.Button) { album = group.key }) {
                            Cover(group.title, ArtworkRepo.coverForTrack(group.tracks.first()) ?: group.tracks.first().coverUrl, 148.dp, 18.dp)
                            Text(group.title, localize = false, color = theme.onBackground, fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                            Text(group.artist, localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            AlbumDownloadBadge(AlbumDownloads.state(group, downloadedIds), Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
            if (view == 2 && chosen == null) coverGridRows("albums", rows, gridColumns, gridSide, gridGap, "library-albums", { it.key }) { group, side ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button) { album = group.key }) {
                    Cover(group.title, ArtworkRepo.coverForTrack(group.tracks.first()) ?: group.tracks.first().coverUrl, side, 18.dp)
                    Text(group.title, localize = false, color = theme.onBackground, fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    Text(group.artist, localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    AlbumDownloadBadge(AlbumDownloads.state(group, downloadedIds), Modifier.padding(top = 2.dp))
                }
            }
            if (view == 2 && chosen == null && albums.isEmpty()) item(key = "no-albums") {
                EmptyState("Aucun album", "Les titres sans album restent disponibles dans Titres.", "Titres", icon = com.ketvoice.app.ui.common.KetIcons.Album, onAction = { view = 3 })
            }
        }
        if ((view == 0 || view == 4) && chosen == null && !appleRoot) {
            if (artists.isNotEmpty()) item(key = "artists") {
                LibrarySection("Artistes")
                if (view == 4 && artists.size > 1) LibrarySortPills(listOf("Récents", "Nom A–Z"), artistOrder, "library-artist-sort") { artistOrder = it }
                // The Artists view shows them as a grid of portraits (the rows below); "All" keeps a row.
                if (view != 4) LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(artists, key = { it.key }) { (name, tracks) ->
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
            if (view == 4) coverGridRows("artists", artists, gridColumns, gridSide, gridGap, "library-artist-grid", { it.key }) { (name, tracks), side ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) {
                    AppUi.openArtist(tracks.first().artistId.orEmpty(), name)
                }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Cover(name, ArtworkRepo.coverForTrack(tracks.first()) ?: tracks.first().coverUrl, side, side / 2)
                    Text(name, localize = false, color = theme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    Text(AppText.t(if (tracks.size == 1) "1 titre" else "${tracks.size} titres"), localize = false,
                        color = theme.secondary, fontSize = 12.sp, maxLines = 1)
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
                if (view == 3 && chosen == null && all.size > 1 && !selecting)
                    LibrarySortPills(AlbumOrder.labels, trackOrder, "library-track-sort") { trackOrder = it }
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
            items(shown, key = { it.id }, contentType = { "library-track" }) { track ->
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
    // The A–Z index (7.1): only on a list sorted by name, long enough to need it, without a search.
    val jumps = remember(view, chosen, query, shown, albumRows, artists, order, trackOrder, artistOrder, gridColumns) {
        when {
            query.isNotBlank() || chosen != null -> null
            view == 3 && trackOrder in 1..2 -> AlphaJumps(IndexLetters.firsts(shown) { if (trackOrder == 1) it.title else it.artist }, shown.size, 1)
            view == 2 && order in 1..2 -> AlphaJumps(IndexLetters.firsts(albumRows) { if (order == 1) it.title else it.artist }, albumRows.size, gridColumns)
            view == 4 && artistOrder == 1 -> AlphaJumps(IndexLetters.firsts(artists) { it.key }, artists.size, gridColumns)
            else -> null
        }?.takeIf { IndexLetters.shown(it.count, it.letters.size) }
    }
    // It comes in once the page's header has scrolled away, so it never lies over the title, the search or the filters.
    val pastHeader by remember(list) { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    val indexShown by androidx.compose.animation.core.animateFloatAsState(if (jumps != null && pastHeader) 1f else 0f,
        com.ketvoice.app.ui.motion.Motion.Springs.SMOOTH.motion(), label = "alphabet-index")
    if (jumps != null && indexShown > 0f) {
        val jumpScope = rememberCoroutineScope()
        val bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp
        AlphabetIndex(jumps.letters.map { it.first }, { i ->
            jumpScope.launch { list.scrollToItem(jumps.item(list.layoutInfo.totalItemsCount, i)) }
        }, Modifier.align(Alignment.CenterEnd).padding(top = 116.dp, bottom = bottom)
            .graphicsLayer { alpha = indexShown }, available = maxHeight - 116.dp - bottom)
    }
    }
    }
    ScrollEdgeTitle(pageTitle, collapse, Modifier.align(Alignment.TopCenter), tag = "library-inline-title")
    CompositionLocalProvider(com.ketvoice.app.glass.LocalBackdropLayer provides backdrop) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            if (view == 1) {
                KetMenuButton(listOf(
                    listOf(KetMenuItem("Liste", KetIcons.Lyrics, checked = !playlistGrid, tag = "playlists-view-list") { playlistGrid = false },
                        KetMenuItem("Grille", KetIcons.Album, checked = playlistGrid, tag = "playlists-view-grid") { playlistGrid = true }),
                    PlaylistSort.entries.map { option ->
                        KetMenuItem(option.label, checked = option == playlistSort, tag = "playlists-sort:" + option.name) { playlistSortName = option.name }
                    },
                    PlaylistFilter.entries.map { option ->
                        KetMenuItem(option.label, checked = option == playlistFilter, tag = "playlists-filter:" + option.name) { playlistFilterName = option.name }
                    },
                    listOf(KetMenuItem("Rejoindre avec un code", KetIcons.Link, enabled = !loadError) { SharedUi.openJoin(null) },
                        KetMenuItem("Importer un fichier · .json / .m3u", KetIcons.Folder, enabled = !loadError) {
                            importFile.launch(arrayOf("application/json", "text/*", "audio/x-mpegurl", "audio/mpegurl", "*/*"))
                        })), "Options", tag = "playlists-menu")
                Spacer(Modifier.width(10.dp))
            }
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
    // As wide as the other shelves of a large window (whole cards and a sliver of the next); 148 dp on a phone.
    val cell = com.ketvoice.app.ui.adaptive.shelfCell(148.dp, 18.dp)
    LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(rows, key = { it.id }, contentType = { "playlist-shelf" }) { playlist ->
            val zoom = com.ketvoice.app.ui.motion.rememberZoomSource(10.dp) { place -> PlaylistArtwork(playlist, playlist.name, place, 10.dp) }
            Column(Modifier.width(cell)) {
                PlaylistArtwork(playlist, playlist.name, Modifier.size(cell).then(com.ketvoice.app.ui.motion.zoomSourceOf(zoom))
                    .clickable { zoom.openPlaylist(playlist.id) }, 10.dp)
                // The name and its count as one block, the pin beside them at the top (a full touch target).
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).padding(top = 8.dp)) {
                        Text(playlist.name, localize = false, color = theme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 1.dp)) {
                            Text(if (playlist.entries.size == 1) "1 titre" else "${playlist.entries.size} titres", color = theme.secondary, fontSize = 12.sp)
                            if (com.ketvoice.app.data.PlaylistRules.isShared(playlist.id)) SharedBadge(Modifier.padding(start = 6.dp))
                        }
                    }
                    if (onPin != null) IconButton(onClick = { onPin(playlist) }, modifier = Modifier.size(48.dp).offset(x = 12.dp)) {
                        Icon(Icons.Filled.PushPin, if (playlist.pinned) "Désépingler" else "Épingler",
                            tint = if (playlist.pinned) theme.accent else theme.secondary, modifier = Modifier.size(16.dp))
                    }
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
    // A large window sizes its covers as every artwork grid of the app does (LargeScreen.gridColumns).
    val large = com.ketvoice.app.ui.adaptive.LocalLargeScreen.current != null
    val gap = if (large) com.ketvoice.app.ui.adaptive.LargeScreen.GRID_GAP_DP.dp else 14.dp
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        val columns = if (large) com.ketvoice.app.ui.adaptive.LargeScreen.gridColumns(maxWidth.value) else coverGridColumns(maxWidth.value + 36f)
        val side = (maxWidth - gap * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            rows.chunked(columns).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    line.forEach { item -> Box(Modifier.width(side)) { cell(item, side) } }
                }
            }
        }
    }
}

/**
 * The same cover grid as [CoverGrid], one lazy item per line of [columns] cells [side] wide (6.6): only the lines
 * on screen are composed. The first line carries [tag].
 */
internal fun <T> LazyListScope.coverGridRows(name: String, rows: List<T>, columns: Int, side: androidx.compose.ui.unit.Dp,
                                             gap: androidx.compose.ui.unit.Dp = 14.dp, tag: String, key: (T) -> Any,
                                             cell: @Composable (T, androidx.compose.ui.unit.Dp) -> Unit) {
    val lines = rows.chunked(columns.coerceAtLeast(1))
    itemsIndexed(lines, key = { _, line -> name + ':' + key(line.first()) }, contentType = { _, _ -> name }) { index, line ->
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = if (index == 0) 0.dp else 18.dp)
            .then(if (index == 0) Modifier.testTag(tag) else Modifier), horizontalArrangement = Arrangement.spacedBy(gap)) {
            line.forEach { item -> Box(Modifier.width(side)) { cell(item, side) } }
        }
    }
}

@Composable
private fun LibrarySection(title: String, onMore: (() -> Unit)? = null) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(12.dp))
        .then(if (onMore != null) Modifier.clickable(role = Role.Button, onClick = onMore) else Modifier)
        .padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        com.ketvoice.app.ui.common.SectionHeader(title, Modifier.weight(1f, fill = false))
        if (onMore != null) Icon(KetIcons.ChevronRight, null, tint = theme.secondary, modifier = Modifier.padding(start = 4.dp).size(18.dp))
    }
}

/** The categories of the library as Apple Music lists them: a symbol in the accent, a name, a chevron. */
@Composable
private fun AppleLibraryCategories(columns: Int, rows: List<Triple<String, androidx.compose.ui.graphics.vector.ImageVector, () -> Unit>>) {
    val theme = LocalTheme.current
    val perColumn = ((rows.size + columns - 1) / columns).coerceAtLeast(1)
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("library-categories"), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
    rows.chunked(perColumn).forEach { part ->
    Column(Modifier.weight(1f)) {
        part.forEachIndexed { index, (title, icon, open) ->
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = open).heightIn(min = 50.dp)
                .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = theme.accent, modifier = Modifier.size(24.dp))
                Text(title, color = theme.onBackground, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 16.dp))
                Icon(KetIcons.ChevronRight, null, tint = theme.secondary.copy(alpha = .6f), modifier = Modifier.size(16.dp))
            }
            if (index != part.lastIndex) Box(Modifier.padding(start = 44.dp).fillMaxWidth().height(.5.dp).background(theme.hairline))
        }
    }
    }
    }
}

/**
 * Where the A–Z index jumps (7.1): the first entry of each letter, in a section of [count] entries laid
 * [columns] to a row. The section is the last of the list (nothing follows a non-empty sorted list), so
 * its first row is [total] minus its rows.
 */
private class AlphaJumps(val letters: List<Pair<String, Int>>, val count: Int, val columns: Int) {
    fun item(total: Int, letter: Int): Int {
        val perRow = columns.coerceAtLeast(1)
        val rows = (count + perRow - 1) / perRow
        val position = letters.getOrNull(letter)?.second ?: 0
        return ((total - rows).coerceAtLeast(0) + position / perRow).coerceIn(0, (total - 1).coerceAtLeast(0))
    }
}

private fun libraryCollator(): java.util.Comparator<String> {
    val collator = java.text.Collator.getInstance(java.util.Locale.getDefault()).apply { strength = java.text.Collator.PRIMARY }
    return java.util.Comparator { a, b -> collator.compare(a, b) }
}

/** The orders of a Library section, as the plain filter capsules of the albums. */
@Composable
private fun LibrarySortPills(labels: List<String>, selected: Int, tag: String, onSelect: (Int) -> Unit) {
    val theme = LocalTheme.current
    LazyRow(Modifier.testTag(tag), contentPadding = PaddingValues(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        item { Text("Trier", color = theme.secondary, fontSize = 13.sp, modifier = Modifier.padding(end = 2.dp)) }
        itemsIndexed(labels) { index, label -> CollectionFilter(label, selected == index, { onSelect(index) }) }
    }
}

/**
 * Favoris and Téléchargements at the head of the library, as rows like a playlist's: the heart cover, the
 * mosaic of the latest downloads, the name and how many titles.
 */
@Composable
private fun LibraryPinnedCollections(favorites: Int, downloads: List<Downloader.Item>) {
    val mosaic = remember(downloads) { downloadMosaic(downloads) }
    fun count(n: Int) = AppText.t(if (n == 1) "1 titre" else "$n titres")
    Column(Modifier.fillMaxWidth().padding(horizontal = pageMargin - 4.dp).padding(bottom = 6.dp).testTag("library-pinned")) {
        PinnedRow("Favoris", count(favorites), "library-liked", { AppUi.openCatalog(CatalogRequest("favorites", "local", "Favoris")) }) { LikedCover() }
        PinnedRow("Téléchargements", count(downloads.size), "library-downloads", { AppUi.openDownloads() }) { MosaicCover(mosaic) }
    }
}

@Composable
private fun PinnedRow(title: String, subtitle: String, tag: String, onClick: () -> Unit, cover: @Composable () -> Unit) {
    val theme = LocalTheme.current
    Column(Modifier.testTag(tag)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 72.dp).padding(start = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))) { cover() }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, color = theme.onBackground, style = com.ketvoice.app.theme.Ket27.Type.body, maxLines = 1)
                Text(subtitle, localize = false, color = theme.secondary, style = com.ketvoice.app.theme.Ket27.Type.footnote,
                    maxLines = 1, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(KetIcons.ChevronRight, null, tint = theme.secondary.copy(alpha = .6f), modifier = Modifier.padding(end = 12.dp).size(16.dp))
        }
        Box(Modifier.padding(start = 74.dp).fillMaxWidth().height(.5.dp).background(theme.hairline))
    }
}
