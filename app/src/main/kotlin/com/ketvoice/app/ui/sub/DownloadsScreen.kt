package com.ketvoice.app.ui.sub

import com.ketvoice.app.theme.pageBackground
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import com.ketvoice.app.ui.common.trackRowClick
import com.ketvoice.app.ui.common.SwipeTrackActions
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.data.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.*
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.library.localAlbums
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Transfers are not playable files. Keep the two collections separate. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun DownloadsScreen() {
    val theme = LocalTheme.current
    val saved by Downloader.items.collectAsState()
    val active by Downloader.activeTracks.collectAsState()
    val tasks by Downloader.tasks.collectAsState()
    val busy by Downloader.busy.collectAsState()
    val artwork by ArtworkRepo.covers.collectAsState()
    val player by Player.state.collectAsState()
    // 7.0: what « Hors ligne intelligent » downloaded carries « Auto »; Settings can open the list of those alone.
    val autoIds by SmartOffline.autoIds.collectAsState()
    val autoOnly = SmartOffline.showAutoOnly.value
    DisposableEffect(Unit) { onDispose { SmartOffline.showAutoOnly.value = false } }
    var query by rememberSaveable { mutableStateOf("") }
    var deleteIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    // 0 = titles, 1 = albums, 2 = artists
    var view by rememberSaveable { mutableIntStateOf(0) }
    var albumKey by rememberSaveable { mutableStateOf<String?>(null) }
    var artistKey by rememberSaveable { mutableStateOf<String?>(null) }
    var filterName by rememberSaveable { mutableStateOf(DownloadFilter.All.name) }
    var sortName by rememberSaveable { mutableStateOf(DownloadSort.Recent.name) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    // Transfer states only matter while something is being transferred.
    val filter = if (active.isEmpty()) DownloadFilter.All
        else DownloadFilter.entries.firstOrNull { it.name == filterName } ?: DownloadFilter.All
    val sort = DownloadSort.entries.firstOrNull { it.name == sortName } ?: DownloadSort.Recent
    val base = remember(saved, active, tasks, query, filter, autoOnly, autoIds) {
        if (autoOnly) downloadSections(saved.filter { it.track.id in autoIds }, emptyList(), tasks, query, filter)
        else downloadSections(saved, active.values, tasks, query, filter)
    }
    val groups = remember(base.saved) { localAlbums(base.saved.map { it.track }) }
    val artists = remember(base.saved) { downloadArtists(base.saved) }
    val chosen = groups.firstOrNull { it.key == albumKey }
    val chosenArtist = artists.firstOrNull { it.key == artistKey }
    val narrowed = when {
        chosen != null -> base.copy(saved = base.saved.filter { item -> chosen.tracks.any { it.id == item.track.id } }, transfers = emptyList())
        chosenArtist != null -> base.copy(saved = chosenArtist.items, transfers = emptyList())
        else -> base
    }
    val sections = remember(narrowed, sort) { narrowed.copy(saved = sortDownloads(narrowed.saved, sort)) }
    val deleteItems = saved.filter { it.track.id in deleteIds }
    LaunchedEffect(saved) { selected = selected.filter { id -> saved.any { it.track.id == id } } }
    val adaptive = LocalAdaptiveLayout.current
    val large = com.ketvoice.app.ui.adaptive.LocalLargeScreen.current != null
    val summary = remember(saved) { downloadSummary(saved) }
    val freeBytes = remember(saved) { runCatching { android.os.StatFs(context.filesDir.path).availableBytes }.getOrDefault(0L) }
    fun size(bytes: Long) = android.text.format.Formatter.formatShortFileSize(context, bytes)
    val showTitles = view == 0 || chosen != null || chosenArtist != null

    val backdrop = com.ketvoice.app.glass.rememberGlassBackdrop(theme.background)
    BoxWithConstraints(Modifier.fillMaxSize().pageBackground(theme)) {
    // The width the rows and covers share once the page's margins are taken off.
    val paneDp = maxWidth.value - 2 * DownloadsLayout.EDGE_DP
    val columns = DownloadsLayout.columns(paneDp)
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val handover = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
    // 0 while the large title is in view, 1 once it has scrolled away: the bar then names the page.
    val collapse by remember { androidx.compose.runtime.derivedStateOf {
        ScrollTitle.collapse(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset, handover)
    } }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
    com.ketvoice.app.glass.BackdropCapture(backdrop, Modifier.fillMaxSize()) {
        // Ket 27, after Apple Music's « Téléchargé »: a large title, Play and Shuffle, the storage, then the list.
        // Albums and artists are a grid of covers; titles are rows, two side by side on a wide pane.
        LazyVerticalGrid(columns = GridCells.Fixed(columns), state = grid,
            modifier = Modifier.fillMaxSize().testTag("downloads-list"),
            contentPadding = PaddingValues(start = DownloadsLayout.EDGE_DP.dp, end = DownloadsLayout.EDGE_DP.dp,
                top = top + 4.dp, bottom = adaptive.bottomPaddingDp.dp),
            horizontalArrangement = Arrangement.spacedBy(DownloadsLayout.GAP_DP.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "summary", contentType = "downloads-header") {
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column {
                        com.ketvoice.app.ui.common.PageTitle("Téléchargements", Modifier.shrinkingTitle { collapse }.asHeading()
                            .testTag("downloads-title"), maxLines = 2)
                        Text(listOf(AppText.t(if (summary.count == 1) "1 titre" else "${summary.count} titres"),
                            size(summary.bytes).takeIf { summary.count > 0 }.orEmpty(), playtimeLabel(summary.durationMs))
                            .filter { it.isNotBlank() }.joinToString(" · "), localize = false, color = theme.secondary,
                            style = com.ketvoice.app.theme.Ket27.Type.subheadline, modifier = Modifier.padding(top = 2.dp).testTag("downloads-meta"))
                    }
                    // On a wide pane Play/Shuffle and the storage stand side by side; on a phone, one above the other.
                    val play: @Composable (Modifier) -> Unit = { place ->
                        if (saved.isNotEmpty()) com.ketvoice.app.ui.common.CollectionActionPair(
                            { Player.setShuffle(false); Player.playQueue(saved.map { it.track }) },
                            { Player.playShuffled(saved.map { it.track }) }, place, minHeight = 50.dp)
                    }
                    if (large && paneDp >= 700f) Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) { play(Modifier.widthIn(max = 440.dp)) }
                        DownloadSummaryCard(summary, freeBytes, ::size, Modifier.weight(1f)) { AppUi.showCapsule.value = true }
                    } else {
                        play(Modifier.widthIn(max = 640.dp))
                        DownloadSummaryCard(summary, freeBytes, ::size, Modifier.widthIn(max = 640.dp)) { AppUi.showCapsule.value = true }
                    }
                    com.ketvoice.app.ui.smartlib.DuplicatesHint(saved)
                    if (saved.isNotEmpty() || active.isNotEmpty()) {
                        CompactSearchField(query, { query = it }, { keyboard?.hide() }, Modifier.testTag("downloads-search"))
                        if (saved.isNotEmpty()) DownloadViewSwitch(view, modifier = Modifier.widthIn(max = 640.dp)) { view = it; albumKey = null; artistKey = null }
                        if (active.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(DownloadFilter.entries) { option ->
                                CollectionFilter(option.label, filter == option, {
                                    filterName = option.name; albumKey = null; artistKey = null
                                })
                            }
                        }
                        if (selecting) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp)).background(theme.card).padding(horizontal = 8.dp)) {
                            com.ketvoice.app.ui.common.KetTextButton("Tout sélectionner", { selected = sections.saved.map { it.track.id } })
                            IconButton(onClick = { deleteIds = selected }, enabled = selected.isNotEmpty()) { Icon(KetIcons.Trash, "Supprimer", tint = DESTRUCTIVE) }
                            Text(selected.size.toString(), localize = false, color = theme.secondary,
                                modifier = Modifier.align(Alignment.CenterVertically))
                        }
                    }
                    val crumb = chosen?.title ?: chosenArtist?.name
                    if (crumb == null && autoOnly) SmartOfflineCrumb { SmartOffline.showAutoOnly.value = false }
                    if (crumb != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        com.ketvoice.app.ui.common.BackLink(if (chosen != null) "Albums" else "Artistes", { albumKey = null; artistKey = null })
                        Text(crumb, localize = false, color = theme.onBackground, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (deleteError) Text("Impossible de supprimer certains fichiers.", color = theme.secondary)
                }
            }
            if (sections.transfers.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "transfers-heading") { DownloadSectionTitle("Transferts", sections.transfers.size) }
                items(sections.transfers, key = { it.id }, span = { GridItemSpan(DownloadsLayout.rowSpan(maxLineSpan, paneDp)) },
                    contentType = { "download-transfer" }) { track ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(theme.card)
                        .padding(14.dp).testTag("transfer-download")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Cover(track.title, com.ketvoice.app.data.MediaOverrides.cover(track, artwork["t:${track.id}"] ?: track.coverUrl), 48.dp, 6.dp)
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                TrackTitle(track.title, color = theme.onBackground)
                                Text(track.artist, localize = false, color = theme.secondary, fontSize = 13.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        DownloadTaskControls(track, tasks[track.id], track.id in busy)
                    }
                }
            }
            if (sections.saved.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "saved-heading") {
                    DownloadSectionTitle(when { showTitles -> "Titres"; view == 1 -> "Albums"; else -> "Artistes" },
                        when { showTitles -> sections.saved.size; view == 1 -> groups.size; else -> artists.size })
                }
                if (view == 1 && chosen == null) items(groups, key = { "offline-album:${it.key}" }, contentType = { "download-cell" }) { group ->
                    val ids = group.tracks.map { it.id }
                    DownloadCoverCell(
                        title = group.title, subtitle = group.artist,
                        meta = AppText.t(if (ids.size == 1) "1 titre" else "${ids.size} titres") + " · " +
                            size(base.saved.filter { it.track.id in ids }.sumOf { it.bytes }),
                        cover = ArtworkRepo.coverForTrack(group.tracks.first()) ?: group.tracks.first().coverUrl,
                        round = false, checked = if (selecting) ids.all { it in selected } else null) {
                        if (selecting) selected = if (ids.all { it in selected }) selected - ids.toSet() else (selected + ids).distinct()
                        else albumKey = group.key
                    }
                }
                else if (view == 2 && chosenArtist == null) items(artists, key = { "offline-artist:${it.key}" }, contentType = { "download-cell" }) { artist ->
                    val ids = artist.items.map { it.track.id }
                    DownloadCoverCell(
                        title = artist.name, subtitle = null,
                        meta = AppText.t(if (ids.size == 1) "1 titre" else "${ids.size} titres") + " · " + size(artist.bytes),
                        cover = artist.items.first().track.let { ArtworkRepo.coverForTrack(it) ?: it.coverUrl },
                        round = true, checked = if (selecting) ids.all { it in selected } else null) {
                        if (selecting) selected = if (ids.all { it in selected }) selected - ids.toSet() else (selected + ids).distinct()
                        else artistKey = artist.key
                    }
                }
                else items(sections.saved, key = { it.track.id }, span = { GridItemSpan(DownloadsLayout.rowSpan(maxLineSpan, paneDp)) },
                    contentType = { "download-row" }) { item ->
                    val track = item.track
                    SwipeTrackActions(track, if (Motion.reduced) Modifier else Modifier.animateItem(), enabled = !selecting) {
                        SavedDownloadRow(item, player.current?.id == track.id, track.id in autoIds, selecting, track.id in selected,
                            cover = com.ketvoice.app.data.MediaOverrides.cover(track, artwork["t:${track.id}"] ?: track.coverUrl),
                            size = size(item.bytes),
                            onClick = {
                                if (selecting) selected = if (track.id in selected) selected - track.id else selected + track.id
                                else {
                                    val tracks = sections.saved.map { it.track }
                                    Player.setShuffle(false)
                                    Player.playQueue(tracks, tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
                                }
                            }, onDelete = { deleteIds = listOf(track.id) })
                    }
                }
                if (view == 1 && chosen == null && groups.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "no-albums") {
                    EmptyState("Aucun album", "Les titres sans album restent disponibles dans Titres.", "Titres", icon = com.ketvoice.app.ui.common.KetIcons.Album, onAction = { view = 0 })
                }
            }
            if (sections.saved.isEmpty() && sections.transfers.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                if (query.isNotBlank() || filter != DownloadFilter.All) EmptyState("Aucun résultat", "Essayez un autre titre ou artiste.", "Effacer", icon = com.ketvoice.app.ui.common.KetIcons.Search,
                    onAction = { query = ""; filterName = DownloadFilter.All.name; albumKey = null; artistKey = null })
                else EmptyState("Aucun téléchargement", "Pour écouter hors ligne, ouvrez le menu d'un titre puis choisissez Télécharger.",
                    "Rechercher des titres", icon = com.ketvoice.app.ui.common.KetIcons.Download, onAction = ::openTrackSearch)
            }
        }
    }
    CompositionLocalProvider(com.ketvoice.app.glass.LocalBackdropLayer provides backdrop) {
        com.ketvoice.app.ui.common.CollectionTopBar(AppText.t("Téléchargements"), collapse, { AppUi.closeOverlay() },
            onPlay = if (saved.isNotEmpty() && !selecting) ({ Player.setShuffle(false); Player.playQueue(saved.map { it.track }) }) else null,
            titleTag = "downloads-collapsed-title") {
            // Sized to its word, so the bar's capsule keeps the room to name the page once the header has gone.
            if (saved.isNotEmpty()) LiquidGlassButton({ selecting = !selecting; selected = emptyList() },
                Modifier.widthIn(min = 48.dp).width(IntrinsicSize.Max).height(48.dp), shape = RoundedCornerShape(24.dp)) {
                Text(if (selecting) "Annuler" else "Sélectionner", color = theme.onBackground,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp))
            }
            // The order of the titles, from the bar's « … » as Apple Music sorts its lists.
            if (saved.size > 1) com.ketvoice.app.ui.common.KetMenuButton(listOf(
                DownloadSort.entries.map { option ->
                    com.ketvoice.app.ui.common.KetMenuItem(option.label, checked = option == sort, tag = "downloads-sort:" + option.name) { sortName = option.name }
                },
                listOf(com.ketvoice.app.ui.common.KetMenuItem("Préparer une capsule hors ligne", KetIcons.Storage) { AppUi.showCapsule.value = true })),
                "Options", tag = "downloads-menu")
        }
    }
    }
    if (deleteItems.isNotEmpty()) {
        com.ketvoice.app.ui.common.AppAlertDialog(onDismissRequest = { if (!deleting) deleteIds = emptyList() },
            modifier = Modifier.testTag("downloads-delete-alert"),
            title = { Text("Supprimer le téléchargement") },
            text = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(deleteItems.take(3).joinToString("\n") { it.track.title }, localize = false)
                Text(AppText.t(if (deleteItems.size == 1) "1 titre" else "${deleteItems.size} titres") + " · " + android.text.format.Formatter.formatShortFileSize(context, deleteItems.sumOf { it.bytes }),
                    localize = false)
            } },
            confirmButton = { com.ketvoice.app.ui.common.KetTextButton("Supprimer", enabled = !deleting, primary = true, destructive = true, onClick = {
                deleting = true
                scope.launch {
                    val failed = withContext(Dispatchers.IO) { deleteItems.filterNot { runCatching { Downloader.delete(it) }.getOrDefault(false) } }
                    // Deleted by hand: « Hors ligne intelligent » never brings these back on its own.
                    SmartOffline.noteDeletedByHand(deleteItems.filterNot { it in failed }.map { it.track.id })
                    selected = failed.map { it.track.id }; selecting = failed.isNotEmpty(); deleteError = failed.isNotEmpty()
                    deleteIds = emptyList(); deleting = false
                }
            }) },
            dismissButton = { com.ketvoice.app.ui.common.KetTextButton("Annuler", enabled = !deleting, onClick = { deleteIds = emptyList() }) })
    }
}

/**
 * A saved title, as Apple Music lists a download: its artwork, its name, the download sign beside the artist,
 * what is stored with it (format, Auto, size, lyrics, Canvas, separated voice), and « … » to remove it.
 */
@Composable
private fun SavedDownloadRow(item: Downloader.Item, current: Boolean, auto: Boolean, selecting: Boolean, checked: Boolean,
                             cover: String?, size: String, onClick: () -> Unit, onDelete: () -> Unit) {
    val theme = LocalTheme.current
    val track = item.track
    var menu by remember { mutableStateOf(false) }
    val menuAnchor = com.ketvoice.app.ui.common.rememberRowAnchor()
    Column(Modifier.fillMaxWidth().testTag("saved-download")) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .then(if (selecting) Modifier.clickable(role = Role.Checkbox, onClick = onClick) else Modifier.trackRowClick(track, onClick = onClick))
            .padding(start = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) com.ketvoice.app.ui.common.KetCheckbox(checked, onCheckedChange = null)
            Cover(track.title, cover, 48.dp, 6.dp)
            Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                TrackTitle(track.title, color = if (current) theme.accent else theme.onBackground, selected = current, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(KetIcons.Download, "Téléchargé", tint = theme.secondary, modifier = Modifier.padding(end = 4.dp).size(12.dp).testTag("download-badge"))
                    Text(track.artist, localize = false, color = theme.secondary, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 2.dp)) {
                    QualityBadge(item.format)
                    if (auto) AutoBadge()
                    Text(size, localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 1)
                    if (item.hasLyrics) Icon(KetIcons.Lyrics, "Paroles enregistrées", tint = theme.secondary,
                        modifier = Modifier.size(14.dp))
                    if (item.hasCanvas) Icon(KetIcons.Canvas, "Canvas enregistré", tint = theme.secondary,
                        modifier = Modifier.size(14.dp))
                    // 6.0.0: separated by the model, voice and music apart (the voice level of the lyrics works on it).
                    if (separatedWhole(track.id)) Icon(KetIcons.Music, "Voix et musique séparées", tint = theme.secondary,
                        modifier = Modifier.size(14.dp).testTag("download-separated"))
                }
            }
            if (!selecting) Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).then(menuAnchor.modifier).testTag("download-more")) {
                    Icon(KetIcons.More, "Options de ${track.title}", tint = theme.secondary, modifier = Modifier.size(20.dp))
                }
                com.ketvoice.app.ui.common.KetMenu(menu, { menu = false }, listOf(
                    listOf(com.ketvoice.app.ui.common.KetMenuItem("Lire ensuite", androidx.compose.material.icons.Icons.AutoMirrored.Filled.PlaylistPlay) {
                        Player.queueWithUndo(track, true) },
                        com.ketvoice.app.ui.common.KetMenuItem("Plus d'options", KetIcons.More) { menuAnchor.open(track) }),
                    listOf(com.ketvoice.app.ui.common.KetMenuItem("Supprimer le téléchargement", KetIcons.Trash, destructive = true,
                        tag = "download-delete", onClick = onDelete))))
            }
        }
        // The hairline starts under the title, past the artwork.
        Box(Modifier.padding(start = if (selecting) 116.dp else 64.dp).fillMaxWidth().height(.5.dp).background(theme.hairline))
    }
}

@Composable
private fun DownloadSectionTitle(title: String, count: Int) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        com.ketvoice.app.ui.common.SectionHeader(title, Modifier.weight(1f))
        Text(count.toString(), localize = false, color = theme.secondary, style = com.ketvoice.app.theme.Ket27.Type.subheadline)
    }
}

/** How much is saved, how much of it is lossless and what room is left: one plain grouped card, as iOS shows storage. */
@Composable
private fun DownloadSummaryCard(summary: DownloadSummary, freeBytes: Long, size: (Long) -> String, modifier: Modifier = Modifier,
                                onCapsule: () -> Unit) {
    val theme = LocalTheme.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(theme.card)
        .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp).testTag("downloads-summary"),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Stockage", color = theme.onBackground, style = com.ketvoice.app.theme.Ket27.Type.headline, modifier = Modifier.weight(1f))
            if (summary.count > 0) Text(size(summary.bytes), localize = false, color = theme.secondary, style = com.ketvoice.app.theme.Ket27.Type.subheadline)
        }
        if (summary.count > 0) {
            // One bar for the saved bytes: the lossless share in the accent colour.
            Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(theme.onBackground.copy(alpha = .10f))) {
                if (summary.losslessShare > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(summary.losslessShare).background(theme.accent))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Legend(theme.accent, AppText.t("Sans perte") + " " + summary.lossless)
                Legend(theme.onBackground.copy(alpha = .28f), AppText.t("Compressé") + " " + (summary.count - summary.lossless))
            }
        }
        if (freeBytes > 0L) Text(AppText.t("Espace libre sur l'appareil") + " : " + size(freeBytes),
            localize = false, color = theme.secondary, fontSize = 12.sp)
        Box(Modifier.fillMaxWidth().height(.5.dp).background(theme.hairline))
        // The offline capsule belongs with storage: one quiet action at the foot of the card.
        Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).clickable(role = Role.Button, onClick = onCapsule),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Préparer une capsule hors ligne", color = theme.accent, style = com.ketvoice.app.theme.Ket27.Type.body,
                modifier = Modifier.weight(1f))
            Icon(KetIcons.ChevronRight, null, tint = theme.secondary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Text(label, localize = false, color = LocalTheme.current.secondary, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

/** Titles, albums or artists: one segmented control whose selection is a sliding lens of glass. */
@Composable
internal fun DownloadViewSwitch(view: Int, tag: String = "downloads-view", modifier: Modifier = Modifier, onView: (Int) -> Unit) {
    com.ketvoice.app.ui.common.GlassSegmentedControl(listOf("Titres", "Albums", "Artistes"), view, onView, modifier, tag = tag)
}

@Composable
internal fun DownloadGroupRow(title: String, subtitle: String?, meta: String, cover: String?, round: Boolean,
                             checked: Boolean?, onClick: () -> Unit) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(20.dp)).background(theme.card)
        .clickable(role = if (checked != null) Role.Checkbox else Role.Button, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (checked != null) com.ketvoice.app.ui.common.KetCheckbox(checked, onCheckedChange = null)
        Cover(title, cover, 68.dp, if (round) 34.dp else 14.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, localize = false, color = theme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, localize = false, color = theme.secondary, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 1)
        }
        if (checked == null) Icon(KetIcons.ChevronRight, null, tint = theme.secondary, modifier = Modifier.size(18.dp))
    }
}

/** The real stored format; lossless files stand out in the accent colour. */
@Composable
private fun QualityBadge(format: String) {
    val theme = LocalTheme.current
    val lossless = isLosslessFormat(format)
    val tone = if (lossless) theme.accent else theme.secondary
    Text(formatBadge(format), localize = false, color = tone,
        fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(tone.copy(alpha = .14f)).padding(horizontal = 6.dp, vertical = 2.dp))
}

/** Whether [trackId] was separated whole, read off the main thread and again whenever the library separates more. */
@Composable
private fun separatedWhole(trackId: String): Boolean {
    val library by com.ketvoice.app.stems.StemLibrary.progress.collectAsState()
    val separated by androidx.compose.runtime.produceState(false, trackId, library.done, library.running) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.ketvoice.app.stems.StemStore.hasWhole(trackId) }.getOrDefault(false)
        }
    }
    return separated
}

/** The grid of Downloads (Ket 27): covers for albums and artists, rows for titles. */
internal object DownloadsLayout {
    /** The narrowest a cover cell gets: two side by side on a phone. */
    const val CELL_DP = 156f

    /** A title row takes the whole line on a phone, half of it on a pane wide enough for four covers. */
    fun rowSpan(maxLineSpan: Int): Int = if (maxLineSpan >= 4) maxLineSpan / 2 else maxLineSpan.coerceAtLeast(1)

    /** The page's side margins and the gap between covers. */
    const val EDGE_DP = 16f
    const val GAP_DP = 18f

    /** Columns of the grid for a pane [paneDp] wide: covers about the same size on every window (two on a phone). */
    fun columns(paneDp: Float): Int = com.ketvoice.app.ui.adaptive.LargeScreen.gridColumns(paneDp)

    /** A title row takes the whole line, half of it once the pane holds two rows side by side (an even grid only). */
    fun rowSpan(maxLineSpan: Int, paneDp: Float): Int =
        if (com.ketvoice.app.ui.adaptive.LargeScreen.listColumns(paneDp) == 2 && maxLineSpan % 2 == 0) maxLineSpan / 2
        else maxLineSpan.coerceAtLeast(1)
}

/**
 * An album or an artist of Downloads as a cover in the grid, its name and what it holds below.
 * While selecting, a tap ticks all of its titles, and the tick sits on the cover.
 */
@Composable
internal fun DownloadCoverCell(title: String, subtitle: String?, meta: String, cover: String?, round: Boolean,
                               checked: Boolean?, onClick: () -> Unit) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp).clip(RoundedCornerShape(12.dp))
        .clickable(role = if (checked != null) Role.Checkbox else Role.Button, onClick = onClick).testTag("download-cover-cell"),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val side = maxWidth
            Box {
                Cover(title, cover, side, if (round) side / 2 else 10.dp)
                if (checked != null) com.ketvoice.app.ui.common.KetCheckbox(checked, onCheckedChange = null,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
        }
        Text(title, localize = false, color = theme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp))
        if (!subtitle.isNullOrBlank()) Text(subtitle, localize = false, color = theme.secondary, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
        Text(meta, localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp))
    }
}
