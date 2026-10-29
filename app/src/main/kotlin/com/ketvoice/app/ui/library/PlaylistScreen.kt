package com.ketvoice.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import com.ketvoice.app.ui.motion.collectionEntry
import com.ketvoice.app.ui.motion.rememberSeenCollections
import kotlinx.coroutines.launch

@Composable
fun PlaylistScreen() {
    val rows by PlaylistStore.lists.collectAsState()
    val playlist = rows.firstOrNull { it.id == AppUi.playlistId.value }
    val th = LocalTheme.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var rename by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var name by remember(playlist?.id) { mutableStateOf(playlist?.name.orEmpty()) }
    var editing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = ""
        scope.launch {
            try { block() } catch (_: Exception) { error = "Impossible d'enregistrer la playlist" } finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().background(th.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassBackButton { if (editing) editing = false else AppUi.closeOverlay() }
            Spacer(Modifier.weight(1f))
            Text(if (editing) "Glissez ⠿ pour réordonner" else "Playlist", color = th.secondary,
                fontSize = if (com.ketvoice.app.BuildConfig.IS_QUEST) 15.sp else 13.sp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(38.dp).clip(CircleShape).clickable(enabled = playlist != null) {
                // La feuille est rendue par AppRoot (au-dessus du chrome flottant) ;
                // ici on ne fait que publier la requête avec ses actions.
                val target = playlist ?: return@clickable
                AppUi.playlistMenu.value = AppUi.PlaylistMenuRequest(
                    playlist = target,
                    onRename = { name = target.name; rename = true },
                    onExportJson = { action { PlaylistExchange.exportJson(context, target) } },
                    onExportM3u = { action { PlaylistExchange.exportM3u(context, target) } },
                    onDelete = { deleting = true }
                )
            },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.MoreHoriz, "Actions de la playlist", tint = th.onBackground, modifier = Modifier.size(22.dp))
            }
        }
        if (playlist == null) {
            EmptyState("Playlist introuvable", "Cette collection n'est plus disponible sur cet appareil.", "Bibliothèque") {
                AppUi.closeOverlay()
            }
            return@Column
        }
        val playable = playlist.entries.mapNotNull { it.track }
        val seen = rememberSeenCollections()

        // ---- Hero premium, centré comme une page d'album du lecteur ----
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(6.dp))
            Box {
                PlaylistArtwork(playlist, playlist.name,
                    Modifier.size(if (com.ketvoice.app.BuildConfig.IS_QUEST) 156.dp else 136.dp)
                        .collectionEntry("playlist:${playlist.id}", seen, 0))
                Box(Modifier.size(if (com.ketvoice.app.BuildConfig.IS_QUEST) 156.dp else 136.dp)
                    .graphicsLayer { alpha = .22f }.blur(18.dp)) {
                    PlaylistArtwork(playlist, playlist.name, Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(playlist.name, localize = false, color = th.onBackground,
                fontSize = if (com.ketvoice.app.BuildConfig.IS_QUEST) 28.sp else 25.sp,
                fontWeight = FontWeight.Black, letterSpacing = (-.6).sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(buildString {
                append("${playable.size} / ${playlist.entries.size} titres disponibles")
                if (playlist.spotifyUrl != null) append(" · Import Spotify")
            }, color = th.secondary, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (playlist.spotifyUrl != null) Text(
                "La liste peut être incomplète : touchez un titre manquant pour le retrouver.",
                color = th.secondary, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).heightIn(min = 46.dp)
                    .clip(RoundedCornerShape(23.dp)).background(if (playable.isEmpty()) th.card else th.accent)
                    .clickable(enabled = playable.isNotEmpty()) { Player.playQueue(playable) },
                    contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PlayArrow, null, tint = if (playable.isEmpty()) th.secondary else Color.Black,
                            modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Écouter", color = if (playable.isEmpty()) th.secondary else Color.Black,
                            fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                }
                Box(Modifier.weight(1f).heightIn(min = 46.dp)
                    .clip(RoundedCornerShape(23.dp)).background(th.card)
                    .clickable(enabled = !busy && playlist.entries.size > 1) { editing = !editing },
                    contentAlignment = Alignment.Center) {
                    Text(if (editing) "Terminer" else "Modifier l'ordre", color = th.accent,
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(8.dp))
        }

        if (playlist.entries.isEmpty()) {
            EmptyState("Votre playlist est vide", "Ouvrez le menu d'un titre et choisissez Ajouter à une playlist.",
                "Rechercher des titres", onAction = ::openTrackSearch)
        } else {
            val list = rememberLazyListState()
            var dragIndex by remember { mutableIntStateOf(-1) }
            var dragOffset by remember { mutableFloatStateOf(0f) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list,
                contentPadding = PaddingValues(bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp)) {
                itemsIndexed(playlist.entries, key = { _, entry -> entry.id }) { index, entry ->
                    val rowModifier = if (index == dragIndex) Modifier.graphicsLayer { translationY = dragOffset } else Modifier
                    PlaylistRow(entry, index, editing, busy, th, rowModifier
                        .then(if (com.ketvoice.app.BuildConfig.IS_QUEST || !editing) Modifier else Modifier.animateItem()),
                        dragHandle = if (!editing) Modifier else Modifier.pointerInput(entry.id) {
                            detectVerticalDragGestures(
                                onDragStart = { dragOffset = 0f; dragIndex = index },
                                onVerticalDrag = { change, delta ->
                                    change.consume()
                                    dragOffset += delta
                                    val infos = list.layoutInfo.visibleItemsInfo
                                    val origin = infos.firstOrNull { it.index == index } ?: return@detectVerticalDragGestures
                                    val center = origin.offset + origin.size / 2f + dragOffset
                                    val below = infos.firstOrNull { it.index == index + 1 }
                                    val above = infos.firstOrNull { it.index == index - 1 }
                                    if (below != null && center > below.offset + below.size / 2f) {
                                        scope.launch { PlaylistStore.move(playlist.id, index, index + 1) }
                                        dragIndex = index + 1
                                        dragOffset -= below.size
                                    } else if (above != null && center < above.offset + above.size / 2f) {
                                        scope.launch { PlaylistStore.move(playlist.id, index, index - 1) }
                                        dragIndex = index - 1
                                        dragOffset += above.size
                                    }
                                },
                                onDragEnd = { dragIndex = -1; dragOffset = 0f },
                                onDragCancel = { dragIndex = -1; dragOffset = 0f })
                        },
                        onPlay = {
                            if (entry.track != null) Player.playQueue(playable, playlist.entries.take(index).count { it.track != null })
                            else {
                                AppUi.playlistResolution.value = AppUi.PlaylistResolution(playlist.id, entry.id)
                                AppUi.pushOverlay(AppUi.SEARCH)
                                AppUi.searchSeed.value = "${entry.title} ${entry.artist}"
                            }
                        },
                        onRemove = { action { PlaylistStore.remove(playlist.id, entry.id) } })
                }
            }
        }
        if (rename) AppAlertDialog(onDismissRequest = { if (!busy) rename = false }, title = { Text("Renommer la playlist") },
            text = { OutlinedTextField(name, { name = it.take(80) }, label = { Text("Nom de la playlist") }, singleLine = true) },
            confirmButton = { TextButton(enabled = !busy && name.isNotBlank(), onClick = { action { PlaylistStore.rename(playlist.id, name); rename = false } }) { Text("Enregistrer") } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Annuler") } })
        if (deleting) AppAlertDialog(onDismissRequest = { if (!busy) deleting = false }, title = { Text("Supprimer cette playlist ?") },
            text = { Text("Seule la playlist est supprimée. Vos titres et téléchargements sont conservés.") },
            confirmButton = { TextButton(enabled = !busy, onClick = { action { PlaylistStore.delete(playlist.id); AppUi.closeOverlay() } }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Annuler") } })
        // Le menu d'actions est rendu par AppRoot (AppUi.playlistMenu) : il passe
        // au-dessus du mini-lecteur et de la navigation au lieu d'être masqué.
    }
}

@Composable
private fun PlaylistRow(entry: PlaylistEntry, index: Int, editing: Boolean, busy: Boolean, th: com.ketvoice.app.theme.KetTheme,
                        modifier: Modifier = Modifier, dragHandle: Modifier = Modifier,
                        onPlay: () -> Unit, onRemove: () -> Unit) {
    val cover = entry.track?.let { ArtworkRepo.coverForTrack(it) } ?: entry.track?.coverUrl
    Row(modifier.fillMaxWidth().heightIn(min = 66.dp).clickable(enabled = !busy, onClick = onPlay)
        .padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(dragHandle.width(28.dp), contentAlignment = Alignment.Center) {
            if (editing) Icon(Icons.Filled.DragHandle, "Déplacer ${entry.title}",
                tint = th.secondary, modifier = Modifier.size(20.dp))
            else Text("${index + 1}", localize = false, color = th.secondary, fontSize = 13.sp)
        }
        Cover(entry.title, cover, 48.dp, 6.dp)
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            TrackTitle(entry.title, th.onBackground, fontSize = 16.sp)
            Text(entry.artist, localize = false, color = th.secondary, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (entry.track == null) Text("Non retrouvé · choisir une version", color = th.accent, fontSize = 12.sp)
        }
        Box(Modifier.size(36.dp).clip(CircleShape).clickable(enabled = !busy, onClick = onRemove),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, "Retirer ${entry.title}", tint = th.secondary, modifier = Modifier.size(18.dp))
        }
    }
}
