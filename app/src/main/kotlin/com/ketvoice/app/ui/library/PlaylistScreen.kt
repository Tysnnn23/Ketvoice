package com.ketvoice.app.ui.library

import androidx.compose.ui.draw.shadow
import com.ketvoice.app.ui.common.trackRowClick
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.DesignTokens
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.*
import com.ketvoice.app.ui.settings.SettingsAction
import com.ketvoice.app.ui.settings.SettingsSheet
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
    val player by Player.state.collectAsState()
    val list = rememberLazyListState()
    val collapse by remember { derivedStateOf {
        val hero = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "playlist-hero" }
        if (hero == null) if (list.firstVisibleItemIndex > 0) 1f else 0f
        else ((-hero.offset).toFloat() / (hero.size * .75f).coerceAtLeast(1f)).coerceIn(0f, 1f)
    } }
    val covers by ArtworkRepo.covers.collectAsState()
    val firstTrack = playlist?.entries?.firstNotNullOfOrNull { it.track }
    val heroCover = playlist?.coverUrl ?: firstTrack?.let { covers["t:${it.id}"] ?: it.coverUrl }
    val sampledAccent = com.ketvoice.app.theme.rememberCoverAccent(if (AppSettings.albumColors.value) heroCover else null)?.first
    val heroTone = sampledAccent ?: th.accent
    val onAccent = if (th.accent.luminance() > .179f) Color.Black else Color.White

    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = ""
        scope.launch {
            try { block() } catch (_: Exception) { error = "Impossible d'enregistrer la playlist" } finally { busy = false }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(th.background).statusBarsPadding()) {
    val compactHeader = maxHeight < 600.dp
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassBackButton { if (editing) editing = false else AppUi.closeOverlay() }
            val genericTitle = com.ketvoice.app.data.AppText.t("Playlist")
            val editingTitle = com.ketvoice.app.data.AppText.t("Glissez ⠿ pour réordonner")
            Box(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                if (editing) Text(editingTitle, localize = false, color = th.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = if (com.ketvoice.app.BuildConfig.IS_QUEST) 15.sp else 13.sp)
                else {
                    Text(genericTitle, localize = false, color = th.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = if (com.ketvoice.app.BuildConfig.IS_QUEST) 15.sp else 13.sp,
                        modifier = Modifier.then(if (collapse >= .5f) Modifier.clearAndSetSemantics {} else Modifier)
                            .graphicsLayer { alpha = 1f - collapse })
                    Text(playlist?.name.orEmpty(), localize = false, color = th.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = if (com.ketvoice.app.BuildConfig.IS_QUEST) 15.sp else 13.sp,
                        modifier = Modifier.then(if (collapse < .5f) Modifier.clearAndSetSemantics {} else Modifier)
                            .graphicsLayer { alpha = collapse })
                }
            }
            Spacer(Modifier.width(10.dp))
            val quickTracks = playlist?.entries?.mapNotNull { it.track }.orEmpty()
            if (quickTracks.isNotEmpty() && !editing && collapse > 0f) {
                Box(Modifier.graphicsLayer { alpha = collapse }
                    .then(if (collapse < .98f) Modifier.clearAndSetSemantics {} else Modifier)) {
                    LiquidGlassButton({ Player.playQueue(quickTracks) }, Modifier.size(DesignTokens.Space.touch),
                        enabled = collapse >= .98f) {
                        Icon(KetIcons.Play, "Écouter", tint = th.accent, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(4.dp))
            }
            LiquidGlassButton(onClick = {
                playlist?.let { target ->
                    com.ketvoice.app.util.ShareLinks.sharePlaylist(context, target.id, target.name, target.entries.size)
                }
            }, modifier = Modifier.size(DesignTokens.Space.touch), enabled = playlist != null) {
                Icon(Icons.Filled.Share, "Partager la playlist", tint = th.onBackground, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(4.dp))
            LiquidGlassButton(onClick = {
                // La feuille est rendue par AppRoot (au-dessus du chrome flottant) ;
                // ici on ne fait que publier la requête avec ses actions.
                val target = playlist ?: return@LiquidGlassButton
                AppUi.playlistMenu.value = AppUi.PlaylistMenuRequest(
                    playlist = target,
                    onRename = { name = target.name; rename = true },
                    onExportJson = { action { PlaylistExchange.exportJson(context, target) } },
                    onExportM3u = { action { PlaylistExchange.exportM3u(context, target) } },
                    onDelete = { deleting = true }
                )
            }, modifier = Modifier.size(DesignTokens.Space.touch), enabled = playlist != null) {
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

        // The hero scrolls with the tracks (like the album page) and never fades:
        // a fixed header left a reserved empty area when the list scrolled.
        var dragIndex by remember { mutableIntStateOf(-1) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        var dragMovePending by remember { mutableStateOf(false) }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("playlist-tracks"),
            state = list,
            contentPadding = PaddingValues(bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp)
        ) {
            item(key = "playlist-hero") {
                val totalMinutes = playable.sumOf { it.durationMs } / 60_000
                // The shared collection header; a short window puts the cover beside the text.
                com.ketvoice.app.ui.common.CollectionHero(heroCover, 0.dp, playlist.name,
                    Modifier.testTag("playlist-header"),
                    meta = listOfNotNull(
                        com.ketvoice.app.data.AppText.t("${playable.size} / ${playlist.entries.size} titres disponibles"),
                        if (totalMinutes > 0) com.ketvoice.app.data.AppText.t("$totalMinutes min") else null,
                        if (playlist.spotifyUrl != null) com.ketvoice.app.data.AppText.t("Import Spotify") else null
                    ).joinToString(" · "),
                    beside = compactHeader, artworkTag = "playlist-cover", artworkSide = if (compactHeader) 104.dp else null,
                    artwork = { PlaylistArtwork(playlist, playlist.name, Modifier.fillMaxSize()) }) {
                    if (playlist.spotifyUrl != null) Text(
                        "La liste peut être incomplète : touchez un titre manquant pour le retrouver.",
                        color = th.secondary, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(bottom = 6.dp))
                    // Play and Shuffle lead, as on every collection; reordering is a quieter third action.
                    com.ketvoice.app.ui.common.CollectionActionPair({ Player.playQueue(playable) }, { Player.playShuffled(playable) },
                        Modifier.widthIn(max = 560.dp), playLabel = "Écouter", enabled = playable.isNotEmpty() && !editing,
                        playTag = "playlist-play", shuffleTag = "playlist-shuffle", minHeight = if (compactHeader) 48.dp else 54.dp)
                    Spacer(Modifier.height(if (compactHeader) 6.dp else 10.dp))
                    com.ketvoice.app.ui.common.GlassCapsuleButton(if (editing) "Terminer" else "Modifier l'ordre", null,
                        { editing = !editing }, Modifier.widthIn(max = 240.dp), enabled = !busy && playlist.entries.size > 1,
                        tag = "playlist-reorder", minHeight = 44.dp)
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }

            if (playlist.entries.isEmpty()) {
                // État vide affiché comme premier item
                item {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        EmptyState("Votre playlist est vide",
                            "Ouvrez le menu d'un titre et choisissez Ajouter à une playlist.",
                            "Rechercher des titres", onAction = ::openTrackSearch)
                    }
                }
            } else {
                itemsIndexed(playlist.entries, key = { _, entry -> entry.id }) { index, entry ->
                    val currentIndex by rememberUpdatedState(index)
                    val rowModifier = if (index == dragIndex) Modifier.graphicsLayer { translationY = dragOffset } else Modifier
                    PlaylistRow(entry, index, editing, busy, th, rowModifier
                        .then(if (com.ketvoice.app.BuildConfig.IS_QUEST || !editing) Modifier else Modifier.animateItem()),
                        dragHandle = if (!editing) Modifier else Modifier.pointerInput(entry.id) {
                            detectVerticalDragGestures(
                                onDragStart = { dragOffset = 0f; dragIndex = currentIndex },
                                onVerticalDrag = { change, delta ->
                                    change.consume()
                                    dragOffset += delta
                                    if (dragMovePending) return@detectVerticalDragGestures
                                    val index = currentIndex
                                    val infos = list.layoutInfo.visibleItemsInfo
                                    // Index 0 is the scrolling hero.
                                    val origin = infos.firstOrNull { it.index == index + 1 } ?: return@detectVerticalDragGestures
                                    val center = origin.offset + origin.size / 2f + dragOffset
                                    val below = infos.firstOrNull { it.index == index + 2 }
                                    val above = infos.firstOrNull { it.index == index }
                                    if (below != null && center > below.offset + below.size / 2f) {
                                        dragMovePending = true
                                        scope.launch { try { PlaylistStore.move(playlist.id, index, index + 1) } catch (_: Exception) { error = "Impossible d'enregistrer la playlist" } finally { dragMovePending = false } }
                                        dragIndex = index + 1
                                        dragOffset -= below.size
                                    } else if (above != null && center < above.offset + above.size / 2f) {
                                        dragMovePending = true
                                        scope.launch { try { PlaylistStore.move(playlist.id, index, index - 1) } catch (_: Exception) { error = "Impossible d'enregistrer la playlist" } finally { dragMovePending = false } }
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
                        onRemove = { action { PlaylistStore.remove(playlist.id, entry.id) } },
                        active = entry.track?.id == player.current?.id)
                }
            }
        }
        if (rename) SettingsSheet(onDismiss = { if (!busy) rename = false }) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("Renommer la playlist", color = th.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Nom de la playlist", color = th.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                val label = AppText.t("Nom de la playlist")
                BasicTextField(name, { name = it.take(80) }, singleLine = true,
                    textStyle = TextStyle(color = th.onBackground, fontSize = 16.sp), cursorBrush = SolidColor(th.accent),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }.clip(RoundedCornerShape(12.dp))
                        .background(th.onBackground.copy(alpha = .07f)).heightIn(min = 48.dp).padding(12.dp))
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                    SettingsAction("Annuler", { rename = false }, enabled = !busy)
                    SettingsAction("Enregistrer", { action { PlaylistStore.rename(playlist.id, name); rename = false } },
                        enabled = !busy && name.isNotBlank(), primary = true)
                }
            }
        }
        if (deleting) SettingsSheet(onDismiss = { if (!busy) deleting = false }) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("Supprimer cette playlist ?", color = th.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Seule la playlist est supprimée. Vos titres et téléchargements sont conservés.",
                    color = th.secondary, modifier = Modifier.padding(top = 12.dp))
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                    SettingsAction("Annuler", { deleting = false }, enabled = !busy)
                    SettingsAction("Supprimer", { action { PlaylistStore.delete(playlist.id); AppUi.closeOverlay() } },
                        enabled = !busy, primary = true)
                }
            }
        }
        // Le menu d'actions est rendu par AppRoot (AppUi.playlistMenu) : il passe
        // au-dessus du mini-lecteur et de la navigation au lieu d'être masqué.
    }
    }
}

@Composable
private fun PlaylistRow(entry: PlaylistEntry, index: Int, editing: Boolean, busy: Boolean, th: com.ketvoice.app.theme.KetTheme,
                        modifier: Modifier = Modifier, dragHandle: Modifier = Modifier,
                        onPlay: () -> Unit, onRemove: () -> Unit, active: Boolean = false) {
    val cover = entry.track?.let { ArtworkRepo.coverForTrack(it) } ?: entry.track?.coverUrl
    val track = entry.track
    val row: @Composable () -> Unit = {
    Row(Modifier.fillMaxWidth().heightIn(min = 66.dp)
        // Outside editing a resolved title behaves like every other list: tap, long-press, swipe.
        .then(if (!editing && track != null) Modifier.trackRowClick(track, enabled = !busy, onClick = onPlay)
            else Modifier.clickable(enabled = !busy, onClick = onPlay))
        .padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(dragHandle.width(if (editing) 48.dp else 28.dp).heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            if (editing) Icon(Icons.Filled.DragHandle, "Déplacer ${entry.title}",
                tint = th.secondary, modifier = Modifier.size(20.dp))
            else Text("${index + 1}", localize = false, color = th.secondary, fontSize = 13.sp)
        }
        if (entry.track != null) DownloadCover(entry.track, cover, 48.dp, 6.dp)
        else Cover(entry.title, cover, 48.dp, 6.dp)
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            TrackTitle(entry.title, if (active) th.accent else th.onBackground, fontSize = 16.sp, selected = active)
            Text(entry.artist, localize = false, color = th.secondary, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (entry.track == null) Text("Non retrouvé · choisir une version", color = th.accent, fontSize = 12.sp)
        }
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(enabled = !busy) {
            if (editing || entry.track == null) onRemove() else AppUi.trackMenuTrack.value = entry.track
        },
            contentAlignment = Alignment.Center) {
            Icon(if (editing || entry.track == null) Icons.Filled.Close else Icons.Filled.MoreHoriz,
                if (editing || entry.track == null) "Retirer ${entry.title}" else "Options de ${entry.title}",
                tint = th.secondary, modifier = Modifier.size(20.dp))
        }
    }
    }
    if (!editing && track != null) com.ketvoice.app.ui.common.SwipeTrackActions(track, modifier, enabled = !busy) { row() }
    else Box(modifier) { row() }
}
