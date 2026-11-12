package com.ketvoice.app.ui.sub

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
    var albumView by rememberSaveable { mutableStateOf(false) }
    var albumKey by rememberSaveable { mutableStateOf<String?>(null) }
    var filterName by rememberSaveable { mutableStateOf(DownloadFilter.All.name) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val filter = DownloadFilter.entries.firstOrNull { it.name == filterName } ?: DownloadFilter.All
    val base = remember(saved, active, tasks, query, filter) { downloadSections(saved, active.values, tasks, query, filter) }
    val groups = remember(base.saved) { localAlbums(base.saved.map { it.track }) }
    val chosen = groups.firstOrNull { it.key == albumKey }
    val sections = if (chosen == null) base else base.copy(saved = base.saved.filter { item -> chosen.tracks.any { it.id == item.track.id } }, transfers = emptyList())
    val deleteItems = saved.filter { it.track.id in deleteIds }
    LaunchedEffect(saved) { selected = selected.filter { id -> saved.any { it.track.id == id } } }
    val adaptive = LocalAdaptiveLayout.current
    val total = remember(saved) { saved.sumOf { it.bytes } }

    val backdrop = com.ketvoice.app.glass.rememberGlassBackdrop(theme.background)
    Box(Modifier.fillMaxSize().background(theme.background)) {
    com.ketvoice.app.glass.BackdropCapture(backdrop, Modifier.fillMaxSize()) {
        LazyVerticalGrid(columns = GridCells.Adaptive(360.dp),
            modifier = Modifier.fillMaxSize().testTag("downloads-list"),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 82.dp,
                bottom = adaptive.bottomPaddingDp.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "summary") {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Téléchargements", color = theme.onBackground, fontSize = 36.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                    Text("Disponibles hors ligne", color = theme.secondary, fontSize = 15.sp)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(theme.card)
                        .padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(KetIcons.Download, null, tint = theme.accent, modifier = Modifier.size(28.dp))
                        Column(Modifier.padding(start = 16.dp).weight(1f)) {
                            Text(AppText.t(if (saved.size == 1) "1 titre" else "${saved.size} titres"),
                                localize = false, color = theme.onBackground, fontSize = 23.sp,
                                fontWeight = FontWeight.SemiBold)
                            Text(android.text.format.Formatter.formatShortFileSize(context, total),
                                localize = false, color = theme.secondary, fontSize = 13.sp)
                        }
                        Icon(KetIcons.Check, null, tint = theme.secondary, modifier = Modifier.size(22.dp))
                    }
                    if (saved.isNotEmpty()) CollectionPlaybackActions(saved.map { it.track }, Modifier.fillMaxWidth())
                    TextButton(onClick = { AppUi.showCapsule.value = true }, contentPadding = PaddingValues(0.dp)) {
                        Text("Préparer une capsule hors ligne", color = theme.accent)
                    }
                    if (saved.isNotEmpty() || active.isNotEmpty()) {
                        CompactSearchField(query, { query = it }, { keyboard?.hide() }, Modifier.testTag("downloads-search"))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(DownloadFilter.entries) { option ->
                                CollectionFilter(option.label, filter == option, {
                                    filterName = option.name; albumKey = null
                                })
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { albumView = !albumView; albumKey = null }) { Text(if (albumView) "Titres" else "Albums", color = theme.accent) }
                            if (chosen != null) TextButton(onClick = { albumKey = null }) { Text("Albums", color = theme.accent) }
                            if (selecting) {
                                TextButton(onClick = { selected = sections.saved.map { it.track.id } }) { Text("Tout sélectionner", color = theme.accent) }
                                IconButton(onClick = { deleteIds = selected }, enabled = selected.isNotEmpty()) { Icon(KetIcons.Trash, "Supprimer", tint = theme.accent) }
                                Text(selected.size.toString(), localize = false, color = theme.secondary)
                            }
                        }
                    }
                    if (chosen != null) Text(chosen.title, localize = false, color = theme.onBackground, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    if (deleteError) Text("Impossible de supprimer certains fichiers.", color = theme.secondary)
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
                item(span = { GridItemSpan(maxLineSpan) }, key = "saved-heading") { DownloadSectionTitle("Titres", sections.saved.size) }
                if (albumView && chosen == null) items(groups, key = { "offline-album:${it.key}" }) { group ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(theme.card).clickable {
                        if (selecting) {
                            val ids = group.tracks.map { it.id }
                            selected = if (ids.all { it in selected }) selected - ids.toSet() else (selected + ids).distinct()
                        } else albumKey = group.key
                    }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selecting) Checkbox(group.tracks.all { it.id in selected }, onCheckedChange = null)
                        Cover(group.title, ArtworkRepo.coverForTrack(group.tracks.first()) ?: group.tracks.first().coverUrl, 64.dp, 12.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(group.title, localize = false, color = theme.onBackground, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(group.artist, localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (group.tracks.size == 1) "1 titre" else "${group.tracks.size} titres", color = theme.secondary, fontSize = 12.sp)
                        }
                    }
                }
                else items(sections.saved, key = { it.track.id }) { item ->
                    val track = item.track
                    Row(Modifier.then(if (Motion.reduced) Modifier else Modifier.animateItem())
                        .fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(if (player.current?.id == track.id) theme.accent.copy(alpha = .07f) else Color.Transparent)
                        .clickable(role = if (selecting) Role.Checkbox else Role.Button) {
                            if (selecting) { selected = if (track.id in selected) selected - track.id else selected + track.id; return@clickable }
                            val tracks = sections.saved.map { it.track }
                            Player.setShuffle(false)
                            Player.playQueue(tracks, tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
                        }.padding(start = 10.dp, end = 2.dp, top = 10.dp, bottom = 10.dp).testTag("saved-download"),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (selecting) Checkbox(track.id in selected, onCheckedChange = null)
                        Cover(track.title, artwork["t:${track.id}"] ?: track.coverUrl, 52.dp, 10.dp)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            TrackTitle(track.title, color = if (player.current?.id == track.id) theme.accent else theme.onBackground)
                            Text(track.artist, localize = false, color = theme.secondary, fontSize = 13.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${item.format} · ${android.text.format.Formatter.formatShortFileSize(context, item.bytes)}",
                                localize = false, color = theme.secondary, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val extras = listOfNotNull(if (item.hasLyrics) AppText.t("Paroles enregistrées") else null,
                                if (item.hasCanvas) AppText.t("Canvas enregistré") else null)
                            if (extras.isNotEmpty()) Text(extras.joinToString(" · "), localize = false,
                                color = theme.accent, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (!selecting) IconButton(onClick = { deleteIds = listOf(track.id) }, modifier = Modifier.size(48.dp).testTag("download-delete")) {
                            Icon(KetIcons.Trash, "Supprimer le téléchargement", tint = theme.secondary, modifier = Modifier.size(21.dp))
                        }
                    }
                }
                if (albumView && chosen == null && groups.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "no-albums") {
                    EmptyState("Aucun album", "Les titres sans album restent disponibles dans Titres.", "Titres", onAction = { albumView = false })
                }
            }
            if (sections.saved.isEmpty() && sections.transfers.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                if (query.isNotBlank() || filter != DownloadFilter.All) EmptyState("Aucun résultat", "Essayez un autre titre ou artiste.", "Effacer",
                    onAction = { query = ""; filterName = DownloadFilter.All.name; albumKey = null })
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
