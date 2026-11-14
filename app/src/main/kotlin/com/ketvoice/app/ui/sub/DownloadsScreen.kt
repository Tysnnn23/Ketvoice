package com.ketvoice.app.ui.sub

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
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
    val base = remember(saved, active, tasks, query, filter) { downloadSections(saved, active.values, tasks, query, filter) }
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
    val summary = remember(saved) { downloadSummary(saved) }
    val freeBytes = remember(saved) { runCatching { android.os.StatFs(context.filesDir.path).availableBytes }.getOrDefault(0L) }
    fun size(bytes: Long) = android.text.format.Formatter.formatShortFileSize(context, bytes)
    val showTitles = view == 0 || chosen != null || chosenArtist != null

    val backdrop = com.ketvoice.app.glass.rememberGlassBackdrop(theme.background)
    Box(Modifier.fillMaxSize().background(theme.background)) {
    com.ketvoice.app.glass.BackdropCapture(backdrop, Modifier.fillMaxSize()) {
        LazyVerticalGrid(columns = GridCells.Adaptive(360.dp),
            modifier = Modifier.fillMaxSize().testTag("downloads-list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 82.dp,
                bottom = adaptive.bottomPaddingDp.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "summary") {
                // The most recent cover glows behind the header, edge to edge.
                val recent = remember(saved) { saved.sortedByDescending { it.file.lastModified() }.take(3).map { it.track } }
                com.ketvoice.app.ui.common.CollectionAmbient(
                    recent.firstOrNull()?.let { artwork["t:${it.id}"] ?: it.coverUrl }, Modifier.fillMaxWidth(),
                    strength = .8f, glow = true, bleed = 20.dp) {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Téléchargements", color = theme.onBackground, fontSize = 34.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, modifier = Modifier.asHeading())
                    DownloadSummaryCard(summary, freeBytes, ::size,
                        recent.map { it.title to (artwork["t:${it.id}"] ?: it.coverUrl) }) { AppUi.showCapsule.value = true }
                    if (saved.isNotEmpty()) CollectionPlaybackActions(saved.map { it.track }, Modifier.fillMaxWidth())
                    if (saved.isNotEmpty() || active.isNotEmpty()) {
                        CompactSearchField(query, { query = it }, { keyboard?.hide() }, Modifier.testTag("downloads-search"))
                        if (saved.isNotEmpty()) DownloadViewSwitch(view) { view = it; albumKey = null; artistKey = null }
                        if (active.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(DownloadFilter.entries) { option ->
                                CollectionFilter(option.label, filter == option, {
                                    filterName = option.name; albumKey = null; artistKey = null
                                })
                            }
                        }
                        if (showTitles && sections.saved.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("downloads-sort")) {
                            item { Text("Trier", color = theme.secondary, fontSize = 13.sp, modifier = Modifier.padding(end = 2.dp)) }
                            items(DownloadSort.entries) { option ->
                                CollectionFilter(option.label, sort == option, { sortName = option.name })
                            }
                        }
                        if (selecting) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp)).background(theme.accent.copy(alpha = .08f)).padding(horizontal = 8.dp)) {
                            TextButton(onClick = { selected = sections.saved.map { it.track.id } }) { Text("Tout sélectionner", color = theme.accent) }
                            IconButton(onClick = { deleteIds = selected }, enabled = selected.isNotEmpty()) { Icon(KetIcons.Trash, "Supprimer", tint = theme.accent) }
                            Text(selected.size.toString(), localize = false, color = theme.secondary,
                                modifier = Modifier.align(Alignment.CenterVertically))
                        }
                    }
                    val crumb = chosen?.title ?: chosenArtist?.name
                    if (crumb != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { albumKey = null; artistKey = null }, contentPadding = PaddingValues(end = 10.dp)) {
                            Icon(KetIcons.ArrowLeft, null, tint = theme.accent, modifier = Modifier.size(18.dp))
                            Text(if (chosen != null) "Albums" else "Artistes", color = theme.accent, modifier = Modifier.padding(start = 6.dp))
                        }
                        Text(crumb, localize = false, color = theme.onBackground, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (deleteError) Text("Impossible de supprimer certains fichiers.", color = theme.secondary)
                }
                }
            }
            if (sections.transfers.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "transfers-heading") { DownloadSectionTitle("Transferts", sections.transfers.size) }
                items(sections.transfers, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { track ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(theme.card)
                        .padding(16.dp).testTag("transfer-download")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Cover(track.title, artwork["t:${track.id}"] ?: track.coverUrl, 48.dp, 10.dp)
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
                if (view == 1 && chosen == null) items(groups, key = { "offline-album:${it.key}" }) { group ->
                    val ids = group.tracks.map { it.id }
                    DownloadGroupRow(
                        title = group.title, subtitle = group.artist,
                        meta = AppText.t(if (ids.size == 1) "1 titre" else "${ids.size} titres") + " · " +
                            size(base.saved.filter { it.track.id in ids }.sumOf { it.bytes }),
                        cover = ArtworkRepo.coverForTrack(group.tracks.first()) ?: group.tracks.first().coverUrl,
                        round = false, checked = if (selecting) ids.all { it in selected } else null) {
                        if (selecting) selected = if (ids.all { it in selected }) selected - ids.toSet() else (selected + ids).distinct()
                        else albumKey = group.key
                    }
                }
                else if (view == 2 && chosenArtist == null) items(artists, key = { "offline-artist:${it.key}" }) { artist ->
                    val ids = artist.items.map { it.track.id }
                    DownloadGroupRow(
                        title = artist.name, subtitle = null,
                        meta = AppText.t(if (ids.size == 1) "1 titre" else "${ids.size} titres") + " · " + size(artist.bytes),
                        cover = artist.items.first().track.let { ArtworkRepo.coverForTrack(it) ?: it.coverUrl },
                        round = true, checked = if (selecting) ids.all { it in selected } else null) {
                        if (selecting) selected = if (ids.all { it in selected }) selected - ids.toSet() else (selected + ids).distinct()
                        else artistKey = artist.key
                    }
                }
                else items(sections.saved, key = { it.track.id }) { item ->
                    val track = item.track
                    val current = player.current?.id == track.id
                    SwipeTrackActions(track, if (Motion.reduced) Modifier else Modifier.animateItem(), enabled = !selecting) {
                    Row(Modifier
                        .fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(if (current) theme.accent.copy(alpha = .08f) else Color.Transparent)
                        .then(if (selecting) Modifier.clickable(role = Role.Checkbox) {
                            selected = if (track.id in selected) selected - track.id else selected + track.id
                        } else Modifier.trackRowClick(track) {
                            val tracks = sections.saved.map { it.track }
                            Player.setShuffle(false)
                            Player.playQueue(tracks, tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
                        }).padding(start = 8.dp, end = 2.dp, top = 8.dp, bottom = 8.dp).testTag("saved-download"),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (selecting) Checkbox(track.id in selected, onCheckedChange = null)
                        Cover(track.title, artwork["t:${track.id}"] ?: track.coverUrl, 56.dp, 12.dp)
                        Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            TrackTitle(track.title, color = if (current) theme.accent else theme.onBackground)
                            Text(track.artist, localize = false, color = theme.secondary, fontSize = 13.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                QualityBadge(item.format)
                                Text(size(item.bytes), localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 1)
                                if (item.hasLyrics) Icon(KetIcons.Lyrics, "Paroles enregistrées", tint = theme.secondary,
                                    modifier = Modifier.size(14.dp))
                                if (item.hasCanvas) Icon(KetIcons.Canvas, "Canvas enregistré", tint = theme.secondary,
                                    modifier = Modifier.size(14.dp))
                            }
                        }
                        if (!selecting) IconButton(onClick = { deleteIds = listOf(track.id) }, modifier = Modifier.size(48.dp).testTag("download-delete")) {
                            Icon(KetIcons.Trash, "Supprimer le téléchargement", tint = theme.secondary, modifier = Modifier.size(20.dp))
                        }
                    }
                    }
                }
                if (view == 1 && chosen == null && groups.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "no-albums") {
                    EmptyState("Aucun album", "Les titres sans album restent disponibles dans Titres.", "Titres", onAction = { view = 0 })
                }
            }
            if (sections.saved.isEmpty() && sections.transfers.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                if (query.isNotBlank() || filter != DownloadFilter.All) EmptyState("Aucun résultat", "Essayez un autre titre ou artiste.", "Effacer",
                    onAction = { query = ""; filterName = DownloadFilter.All.name; albumKey = null; artistKey = null })
                else EmptyState("Aucun téléchargement", "Pour écouter hors ligne, ouvrez le menu d'un titre puis choisissez Télécharger.",
                    "Rechercher des titres", onAction = ::openTrackSearch)
            }
        }
    }
    CompositionLocalProvider(com.ketvoice.app.glass.LocalBackdropLayer provides backdrop) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            GlassBackButton { AppUi.closeOverlay() }
            Spacer(Modifier.weight(1f))
            if (saved.isNotEmpty()) LiquidGlassButton({ selecting = !selecting; selected = emptyList() },
                Modifier.widthIn(min = 48.dp).height(48.dp), shape = RoundedCornerShape(24.dp)) {
                Text(if (selecting) "Annuler" else "Sélectionner", color = theme.onBackground,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp))
            }
        }
    }
    }
    if (deleteItems.isNotEmpty()) {
        AlertDialog(onDismissRequest = { if (!deleting) deleteIds = emptyList() }, containerColor = theme.card,
            title = { Text("Supprimer le téléchargement", color = theme.onBackground) },
            text = { Column {
                Text(deleteItems.take(3).joinToString("\n") { it.track.title }, localize = false, color = theme.secondary)
                Text(AppText.t(if (deleteItems.size == 1) "1 titre" else "${deleteItems.size} titres") + " · " + android.text.format.Formatter.formatShortFileSize(context, deleteItems.sumOf { it.bytes }),
                    localize = false, color = theme.secondary)
            } },
            confirmButton = { TextButton(enabled = !deleting, onClick = {
                deleting = true
                scope.launch {
                    val failed = withContext(Dispatchers.IO) { deleteItems.filterNot { runCatching { Downloader.delete(it) }.getOrDefault(false) } }
                    selected = failed.map { it.track.id }; selecting = failed.isNotEmpty(); deleteError = failed.isNotEmpty()
                    deleteIds = emptyList(); deleting = false
                }
            }) { Text("Supprimer", color = theme.accent) } },
            dismissButton = { TextButton(enabled = !deleting, onClick = { deleteIds = emptyList() }) { Text("Annuler", color = theme.secondary) } })
    }
}

@Composable
private fun DownloadSectionTitle(title: String, count: Int) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = theme.onBackground, fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = Modifier.weight(1f))
        Text(count.toString(), localize = false, color = theme.secondary, fontSize = 13.sp)
    }
}

/** How much is saved, how long it plays, how much of it is lossless and what room is left. */
@Composable
private fun DownloadSummaryCard(summary: DownloadSummary, freeBytes: Long, size: (Long) -> String,
                                covers: List<Pair<String, String?>> = emptyList(), onCapsule: () -> Unit) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
        .background(Brush.linearGradient(listOf(theme.accent.copy(alpha = .18f), theme.card.copy(alpha = .86f))))
        .border(.8.dp, Brush.verticalGradient(listOf(theme.onBackground.copy(alpha = .14f), theme.onBackground.copy(alpha = .03f))),
            RoundedCornerShape(28.dp))
        .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp).testTag("downloads-summary"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(AppText.t(if (summary.count == 1) "1 titre" else "${summary.count} titres"),
                    localize = false, color = theme.onBackground, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = (-.5).sp)
                Text(listOf(size(summary.bytes), playtimeLabel(summary.durationMs)).filter { it.isNotBlank() }.joinToString(" · "),
                    localize = false, color = theme.secondary, fontSize = 14.sp)
            }
            // What is saved, shown: the latest covers fanned like records. An empty library keeps the glyph.
            if (covers.isEmpty()) Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).background(theme.accent.copy(alpha = .16f)),
                contentAlignment = Alignment.Center) {
                Icon(KetIcons.Download, null, tint = theme.accent, modifier = Modifier.size(24.dp))
            } else Box(Modifier.width((52 + 26 * (covers.size - 1)).dp).height(60.dp).testTag("downloads-covers")) {
                covers.forEachIndexed { index, (title, url) ->
                    val tilt = (index - (covers.size - 1) / 2f) * 7f
                    Box(Modifier.align(Alignment.CenterStart).offset(x = (26 * index).dp).zIndex(index.toFloat())
                        .graphicsLayer { rotationZ = if (Motion.reduced) 0f else tilt }
                        .shadow(6.dp, RoundedCornerShape(11.dp))) {
                        Cover(title, url, 52.dp, 11.dp)
                    }
                }
            }
        }
        if (summary.count > 0) {
            // One bar for the saved bytes: the lossless share in the accent colour.
            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(theme.onBackground.copy(alpha = .10f))) {
                if (summary.losslessShare > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(summary.losslessShare)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Brush.horizontalGradient(listOf(theme.accent.copy(alpha = .78f), theme.accent))))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Legend(theme.accent, AppText.t("Sans perte") + " " + summary.lossless)
                Legend(theme.onBackground.copy(alpha = .28f), AppText.t("Compressé") + " " + (summary.count - summary.lossless))
            }
        }
        if (freeBytes > 0L) Text(AppText.t("Espace libre sur l'appareil") + " : " + size(freeBytes),
            localize = false, color = theme.secondary, fontSize = 12.sp)
        // The offline capsule belongs with storage: one quiet action inside the card.
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onCapsule), verticalAlignment = Alignment.CenterVertically) {
            Icon(KetIcons.Storage, null, tint = theme.accent, modifier = Modifier.size(18.dp))
            Text("Préparer une capsule hors ligne", color = theme.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(start = 10.dp))
            Icon(KetIcons.ChevronRight, null, tint = theme.accent, modifier = Modifier.size(16.dp))
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

/** Titles, albums or artists: one segmented control instead of scattered text links. */
@Composable
internal fun DownloadViewSwitch(view: Int, tag: String = "downloads-view", onView: (Int) -> Unit) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(theme.card).padding(4.dp).selectableGroup()
        .testTag(tag)) {
        listOf("Titres", "Albums", "Artistes").forEachIndexed { index, label ->
            val selected = view == index
            Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                .background(if (selected) theme.onBackground.copy(alpha = .10f) else Color.Transparent)
                .selectable(selected, role = Role.Tab) { onView(index) }, contentAlignment = Alignment.Center) {
                Text(label, color = if (selected) theme.onBackground else theme.secondary, fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun DownloadGroupRow(title: String, subtitle: String?, meta: String, cover: String?, round: Boolean,
                             checked: Boolean?, onClick: () -> Unit) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(20.dp)).background(theme.card)
        .clickable(role = if (checked != null) Role.Checkbox else Role.Button, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (checked != null) Checkbox(checked, onCheckedChange = null)
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
