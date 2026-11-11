package com.ketvoice.app.ui.sub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
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
import com.ketvoice.app.data.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.*
import com.ketvoice.app.ui.motion.Motion

/** Transfers are not playable files. Keep the two collections separate. */
@Composable
fun DownloadsScreen() {
    val theme = LocalTheme.current
    val saved by Downloader.items.collectAsState()
    val active by Downloader.activeTracks.collectAsState()
    val tasks by Downloader.tasks.collectAsState()
    val busy by Downloader.busy.collectAsState()
    val artwork by ArtworkRepo.covers.collectAsState()
    val player by Player.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val sections = remember(saved, active, tasks, query) { downloadSections(saved, active.values, tasks, query) }
    val adaptive = LocalAdaptiveLayout.current
    val total = remember(saved) { saved.sumOf { it.bytes } }

    Column(Modifier.fillMaxSize().background(theme.background)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            GlassBackButton { AppUi.closeOverlay() }
            Text("Téléchargements", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = theme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp).weight(1f))
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(360.dp),
            modifier = Modifier.weight(1f).testTag("downloads-list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = adaptive.bottomPaddingDp.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "summary") {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(theme.accent.copy(alpha = .10f)),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.DownloadDone, null, tint = theme.accent, modifier = Modifier.size(28.dp))
                        }
                        Column(Modifier.padding(start = 14.dp).weight(1f)) {
                            Text("Disponibles hors ligne", color = theme.onBackground,
                                fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text(AppText.t(if (saved.size == 1) "1 titre" else "${saved.size} titres") +
                                " · " + android.text.format.Formatter.formatShortFileSize(context, total),
                                localize = false, color = theme.secondary, fontSize = 13.sp)
                        }
                    }
                    if (saved.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val foreground = if (theme.accent.luminance() > .179f) Color.Black else Color.White
                        Row(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(theme.accent)
                            .clickable(role = Role.Button) { Player.setShuffle(false); Player.playQueue(saved.map { it.track }) }
                            .heightIn(min = 52.dp).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Icon(Icons.Filled.PlayArrow, null, tint = foreground)
                            Text("Lecture", color = foreground, fontWeight = FontWeight.SemiBold)
                        }
                        Row(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(theme.card)
                            .clickable(role = Role.Button) { Player.playQueue(saved.map { it.track }.shuffled()); Player.setShuffle(true) }
                            .heightIn(min = 52.dp).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Icon(Icons.Filled.Shuffle, null, tint = theme.accent, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Aléatoire", color = theme.accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    TextButton(onClick = { AppUi.showCapsule.value = true }, contentPadding = PaddingValues(0.dp)) {
                        Text("Préparer une capsule hors ligne", color = theme.accent)
                    }
                    if (saved.isNotEmpty() || active.isNotEmpty()) {
                        CompactSearchField(query, { query = it }, { keyboard?.hide() }, Modifier.testTag("downloads-search"))
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
                item(span = { GridItemSpan(maxLineSpan) }, key = "saved-heading") { DownloadSectionTitle("Titres", sections.saved.size) }
                items(sections.saved, key = { it.track.id }) { item ->
                    val track = item.track
                    Row(Modifier.then(if (Motion.reduced) Modifier else Modifier.animateItem())
                        .fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(if (player.current?.id == track.id) theme.accent.copy(alpha = .07f) else theme.card)
                        .clickable(role = Role.Button) {
                            val tracks = sections.saved.map { it.track }
                            Player.setShuffle(false)
                            Player.playQueue(tracks, tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
                        }.padding(start = 10.dp, end = 2.dp, top = 10.dp, bottom = 10.dp).testTag("saved-download"),
                        verticalAlignment = Alignment.CenterVertically) {
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
                        IconButton(onClick = { deleteId = track.id }, modifier = Modifier.size(48.dp).testTag("download-delete")) {
                            Icon(Icons.Filled.DeleteOutline, "Supprimer le téléchargement", tint = theme.secondary, modifier = Modifier.size(21.dp))
                        }
                    }
                }
            }
            if (sections.saved.isEmpty() && sections.transfers.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                if (query.isNotBlank()) EmptyState("Aucun résultat", "Essayez un autre titre ou artiste.", "Effacer", onAction = { query = "" })
                else EmptyState("Aucun téléchargement", "Pour écouter hors ligne, ouvrez le menu d'un titre puis choisissez Télécharger.",
                    "Rechercher des titres", onAction = ::openTrackSearch)
            }
        }
    }
    saved.firstOrNull { it.track.id == deleteId }?.let { item ->
        AlertDialog(onDismissRequest = { deleteId = null }, containerColor = theme.card,
            title = { Text("Supprimer le téléchargement", color = theme.onBackground) },
            text = { Text(item.track.title, localize = false, color = theme.secondary) },
            confirmButton = { TextButton(onClick = { deleteId = null; Downloader.delete(item) }) { Text("Supprimer", color = theme.accent) } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Annuler", color = theme.secondary) } })
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
