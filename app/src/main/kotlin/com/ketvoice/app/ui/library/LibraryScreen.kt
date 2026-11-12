package com.ketvoice.app.ui.library

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
    var error by remember { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var view by rememberSaveable { mutableIntStateOf(0) }
    var order by rememberSaveable { mutableIntStateOf(0) }
    var sorting by remember { mutableStateOf(false) }
    var album by rememberSaveable { mutableStateOf<String?>(null) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
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
    val list = rememberLazyListState()
    LaunchedEffect(all) { selected = selected.filter { id -> all.any { it.id == id } } }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> AppUi.bottomCollapsed.value = index > 0 || offset > 24 }
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
    Box(Modifier.fillMaxSize().background(theme.background)) {
    com.ketvoice.app.glass.BackdropCapture(backdrop, Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().testTag("library-list"), state = list,
        contentPadding = PaddingValues(top = 70.dp, bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp)) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp)) {
                Text("Bibliothèque", color = theme.onBackground, fontSize = 38.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = (-1.2).sp)
                Text(AppText.t(if (all.size == 1) "1 titre" else "${all.size} titres") + " · " +
                    AppText.t("Playlists") + " " + playlists.size, localize = false, color = theme.secondary,
                    fontSize = 14.sp, modifier = Modifier.padding(top = 5.dp, bottom = 18.dp))
                CompactSearchField(query, { query = it }, {}, Modifier.testTag("library-search"))
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(listOf("Tout", "Playlists", "Albums", "Titres", "Artistes")) { index, label ->
                    CollectionFilter(label, view == index, {
                        view = index; album = null; selecting = false; selected = emptyList()
                    }, Modifier.padding(bottom = 10.dp))
                }
            }
        }
        if (chosen != null) item(key = "album-heading") {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                TextButton(onClick = { album = null }) { Text("Albums", color = theme.accent) }
                Text(chosen.title, localize = false, color = theme.onBackground, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(chosen.artist, localize = false, color = theme.secondary, fontSize = 14.sp)
                CollectionPlaybackActions(chosen.tracks, Modifier.fillMaxWidth().padding(top = 16.dp))
            }
        }
        if (view == 0 && query.isBlank() && chosen == null) {
            item(key = "shortcuts") {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(24.dp)).background(theme.card)) {
                    LibraryShortcut("Favoris", KetIcons.Heart, Modifier.fillMaxWidth()) { AppUi.openCatalog(CatalogRequest("favorites", "local", "Favoris")) }
                    LibraryShortcut("Téléchargements", KetIcons.Download, Modifier.fillMaxWidth()) { AppUi.openDownloads() }
                    LibraryShortcut("Musique locale", KetIcons.Folder, Modifier.fillMaxWidth()) { AppUi.openLocalFiles() }
                    LibraryShortcut("Replay", KetIcons.Clock, Modifier.fillMaxWidth()) { AppUi.openReplay() }
                }
            }
            if (playlists.any { it.pinned }) item(key = "pinned") {
                LibrarySection("Épinglés")
                PlaylistShelf(playlists.filter { it.pinned }, if (loadError) null else ::pin)
            }
        }
        if (view in 0..1 && chosen == null) {
            val rows = playlists.filter { query.isBlank() || it.name.contains(query.trim(), true) }
                .filterNot { view == 0 && query.isBlank() && it.pinned }
            item(key = "playlists") {
                LibrarySection("Playlists")
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { AppUi.showCreatePlaylist.value = true }, enabled = !loadError) { Text("Créer une playlist", color = theme.accent) }
                    TextButton(onClick = { AppUi.showSpotifyImport.value = true }, enabled = !loadError) { Text("Importer depuis Spotify", color = theme.accent) }
                }
                if (rows.isNotEmpty()) PlaylistShelf(rows, if (loadError) null else ::pin)
                TextButton(onClick = { importFile.launch(arrayOf("application/json", "text/*", "audio/x-mpegurl", "audio/mpegurl", "*/*")) },
                    enabled = !loadError, modifier = Modifier.padding(horizontal = 14.dp)) { Text("Importer un fichier · .json / .m3u", color = theme.accent) }
                if (loadError || error.isNotBlank()) Text(if (error.isNotBlank()) error else "Impossible de lire les playlists",
                    color = theme.secondary, modifier = Modifier.padding(18.dp))
            }
        }
        if (view == 0 || view == 2) {
            val rows = albums.filter { query.isBlank() || it.title.contains(query.trim(), true) || it.artist.contains(query.trim(), true) }
            if (rows.isNotEmpty() && chosen == null) item(key = "albums") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { LibrarySection("Albums") }
                    Box {
                        TextButton(onClick = { sorting = true }) { Text("Trier", color = theme.accent) }
                        DropdownMenu(expanded = sorting, onDismissRequest = { sorting = false }) {
                            AlbumOrder.labels.forEachIndexed { index, label -> DropdownMenuItem(text = { Text(label) }, onClick = { order = index; sorting = false }) }
                        }
                    }
                }
                LazyRow(Modifier.testTag("library-albums"), contentPadding = PaddingValues(horizontal = 18.dp),
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
                EmptyState("Aucun album", "Les titres sans album restent disponibles dans Titres.", "Titres", onAction = { view = 3 })
            }
        }
        if ((view == 0 || view == 4) && chosen == null) {
            val artists = all.filter { it.artist.isNotBlank() }.groupBy { it.artist.substringBefore(',').trim() }
                .filterKeys { query.isBlank() || it.contains(query.trim(), true) }
            if (artists.isNotEmpty()) item(key = "artists") {
                LibrarySection("Artistes")
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(artists.entries.toList(), key = { it.key }) { (name, tracks) ->
                        Column(Modifier.width(100.dp).clickable { AppUi.openArtist(tracks.first().artistId.orEmpty(), name) },
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Cover(name, ArtworkRepo.coverForTrack(tracks.first()) ?: tracks.first().coverUrl, 90.dp, 45.dp)
                            Text(name, localize = false, color = theme.onBackground, fontSize = 13.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
        if (view == 0 || view == 3 || chosen != null) {
            item(key = "tracks-heading") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { LibrarySection("Titres") }
                    if (all.isNotEmpty()) TextButton(onClick = { selecting = !selecting; selected = emptyList() }) {
                        Text(if (selecting) "Annuler" else "Sélectionner", color = theme.accent)
                    }
                }
                if (selecting) Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selected = shown.map { it.id } }) { Text("Tout sélectionner", color = theme.accent) }
                    IconButton(onClick = { Player.playQueue(selectedTracks) }, enabled = selectedTracks.isNotEmpty()) { Icon(KetIcons.Play, "Lecture", tint = theme.accent) }
                    IconButton(onClick = { Player.enqueue(selectedTracks); selected = emptyList(); selecting = false }, enabled = selectedTracks.isNotEmpty()) {
                        Icon(Icons.Filled.QueueMusic, "Ajouter à la file", tint = theme.accent)
                    }
                    Text(selected.size.toString(), localize = false, color = theme.secondary)
                }
            }
            items(shown, key = { it.id }) { track ->
                Row(Modifier.then(if (com.ketvoice.app.ui.motion.Motion.reduced) Modifier else Modifier.animateItem())
                    .fillMaxWidth().clickable {
                    if (selecting) selected = if (track.id in selected) selected - track.id else selected + track.id
                    else Player.playQueue(shown, shown.indexOfFirst { it.id == track.id })
                }.padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) Checkbox(track.id in selected, onCheckedChange = null)
                    DownloadCover(track, ArtworkRepo.coverForTrack(track) ?: track.coverUrl, 48.dp, 10.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        TrackTitle(track.title, color = theme.onBackground)
                        Text(track.artist, localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!selecting) IconButton(onClick = { AppUi.trackMenuTrack.value = track }) { Icon(KetIcons.More, "Options", tint = theme.secondary) }
                }
            }
        }
        if (all.isEmpty() && playlists.isEmpty()) item {
            EmptyState("Votre bibliothèque est vide", "Écoutez un titre ou téléchargez-le pour le retrouver ici, même hors ligne.", "Rechercher des titres", onAction = ::openTrackSearch)
        }
        else if (query.isNotBlank() && shown.isEmpty() && playlists.none { it.name.contains(query.trim(), true) }) item {
            EmptyState("Aucun résultat", "Essayez un autre titre ou artiste.", "Effacer", onAction = { query = "" })
        }
    }
    }
    CompositionLocalProvider(com.ketvoice.app.glass.LocalBackdropLayer provides backdrop) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            LiquidGlassButton({ AppUi.showCreatePlaylist.value = true }, Modifier.size(48.dp), enabled = !loadError) {
                Icon(KetIcons.Plus, "Créer une playlist", tint = theme.onBackground)
            }
            Spacer(Modifier.width(10.dp))
            LiquidGlassButton({ AppUi.showAccount.value = true }, Modifier.size(48.dp)) {
                Icon(KetIcons.Account, "Compte", tint = theme.onBackground)
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
            Column(Modifier.width(148.dp)) {
                PlaylistArtwork(playlist, playlist.name, Modifier.size(148.dp).clickable { AppUi.openPlaylist(playlist.id) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(playlist.name, localize = false, color = theme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(top = 6.dp))
                    if (onPin != null) IconButton(onClick = { onPin(playlist) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.PushPin, if (playlist.pinned) "Désépingler" else "Épingler",
                            tint = if (playlist.pinned) theme.accent else theme.secondary, modifier = Modifier.size(18.dp))
                    }
                }
                Text(if (playlist.entries.size == 1) "1 titre" else "${playlist.entries.size} titres", color = theme.secondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun LibrarySection(title: String) {
    Text(title, color = LocalTheme.current.onBackground, fontSize = 22.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp))
}

@Composable
private fun LibraryShortcut(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val theme = LocalTheme.current
    Row(modifier.clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 62.dp).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = theme.accent, modifier = Modifier.size(24.dp))
        Text(title, color = theme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 16.dp, end = 8.dp))
        Icon(KetIcons.ChevronRight, null, tint = theme.secondary, modifier = Modifier.size(18.dp))
    }
}
