package com.ketvoice.app.ui.library

import com.ketvoice.app.theme.pageBackground
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.DesignTokens
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.*
import com.ketvoice.app.ui.settings.SettingsAction
import com.ketvoice.app.ui.settings.SettingsSheet
import com.ketvoice.app.ui.motion.zoomTarget
import kotlinx.coroutines.launch

/** The playlist page, worked out without a screen. */
internal object PlaylistPage {
    /** The artwork sits beside the title on a wide pane, and on a phone lying on its side; centred above it otherwise. */
    fun beside(widthDp: Float, heightDp: Float, large: Boolean): Boolean =
        (large && widthDp >= 700f) || (heightDp < 600f && widthDp > heightDp && widthDp >= 600f)

    /** Side of the artwork: large and centred on a phone, beside the text on a wide pane, small on a short window. */
    fun artwork(widthDp: Float, heightDp: Float, beside: Boolean): Float = when {
        beside && heightDp < 600f -> 112f
        beside -> com.ketvoice.app.ui.adaptive.LargeScreen.headerArtwork(widthDp)
        else -> (widthDp * .64f).coerceIn(180f, 280f)
    }

    /** The foot of the list, as Apple Music closes a playlist: « 12 titres, 1 h 3 min » (French parts, translated at display). */
    fun footer(count: Int, durationMs: Long): List<String> {
        val minutes = durationMs.coerceAtLeast(0L) / 60_000L
        return listOfNotNull(if (count == 1) "1 titre" else "$count titres", when {
            minutes <= 0L -> null
            minutes >= 60L -> "${minutes / 60} h ${minutes % 60} min"
            else -> "$minutes min"
        })
    }

    /** m:ss of a title, shown beside it on a wide pane. */
    fun duration(ms: Long): String? {
        if (ms <= 0L) return null
        val seconds = ms / 1000L
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }
}

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
    val owner = playlistOwner()
    val large = com.ketvoice.app.ui.adaptive.LocalLargeScreen.current != null && !com.ketvoice.app.BuildConfig.IS_QUEST

    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = ""
        scope.launch {
            try { block() } catch (_: Exception) { error = "Impossible d'enregistrer la playlist" } finally { busy = false }
        }
    }
    androidx.activity.compose.BackHandler(enabled = editing) { editing = false }

    BoxWithConstraints(Modifier.fillMaxSize().pageBackground(th)) {
    val beside = PlaylistPage.beside(maxWidth.value, maxHeight.value, large)
    val side = PlaylistPage.artwork(maxWidth.value - 2 * pageMargin.value, maxHeight.value, beside).dp
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
    if (playlist == null) {
        Column(Modifier.fillMaxSize().padding(top = top)) {
            EmptyState("Playlist introuvable", "Cette collection n'est plus disponible sur cet appareil.", "Bibliothèque", icon = com.ketvoice.app.ui.common.KetIcons.Playlist) {
                AppUi.closeOverlay()
            }
        }
    } else {
        val playable = playlist.entries.mapNotNull { it.track }
        val totalMs = remember(playable) { playable.sumOf { it.durationMs.coerceAtLeast(0L) } }
        var dragIndex by remember { mutableIntStateOf(-1) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        var dragMovePending by remember { mutableStateOf(false) }

        // One column on every window: the header (artwork beside its title on a wide pane) scrolls away with the titles.
        LazyColumn(Modifier.fillMaxSize().testTag("playlist-tracks"), state = list,
            contentPadding = PaddingValues(top = top, bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp)) {
            item(key = "playlist-hero", contentType = "playlist-hero") {
                PlaylistHeader(playlist, playable, owner, beside, side, editing, error,
                    onPlay = { Player.setShuffle(false); Player.playQueue(playable) }, onShuffle = { Player.playShuffled(playable) })
            }
            if (playlist.entries.isEmpty()) {
                item(key = "playlist-empty") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        EmptyState("Votre playlist est vide",
                            "Ouvrez le menu d'un titre et choisissez Ajouter à une playlist.",
                            "Rechercher des titres", icon = com.ketvoice.app.ui.common.KetIcons.Playlist, onAction = ::openTrackSearch)
                    }
                }
            } else {
                itemsIndexed(playlist.entries, key = { _, entry -> entry.id }, contentType = { _, _ -> "playlist-entry" }) { index, entry ->
                    val currentIndex by rememberUpdatedState(index)
                    val rowModifier = if (index == dragIndex) Modifier.graphicsLayer { translationY = dragOffset } else Modifier
                    PlaylistRow(entry, editing, busy, large && beside, th, rowModifier
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
                                    if (below != null && below.key != "playlist-footer" && center > below.offset + below.size / 2f) {
                                        dragMovePending = true
                                        scope.launch { try { PlaylistStore.move(playlist.id, index, index + 1) } catch (_: Exception) { error = "Impossible d'enregistrer la playlist" } finally { dragMovePending = false } }
                                        dragIndex = index + 1
                                        dragOffset -= below.size
                                    } else if (above != null && index > 0 && center < above.offset + above.size / 2f) {
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
                item(key = "playlist-footer", contentType = "playlist-footer") {
                    Text(PlaylistPage.footer(playlist.entries.size, totalMs).joinToString(", ") { AppText.t(it) }, localize = false,
                        color = th.secondary, style = Ket27.Type.footnote,
                        modifier = Modifier.fillMaxWidth().padding(start = pageMargin, end = pageMargin, top = 18.dp, bottom = 8.dp)
                            .testTag("playlist-footer"))
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
                // A shared playlist: its owner deletes it for everybody, another member leaves it (6.3).
                val shared = com.ketvoice.app.data.PlaylistRules.isShared(playlist.id)
                val leaves = shared && !com.ketvoice.app.sync.SharedPlaylists.isOwner(playlist.id)
                Text(if (leaves) "Quitter cette playlist ?" else "Supprimer cette playlist ?", color = th.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(when {
                    leaves -> "Elle disparaît de votre bibliothèque. Les autres membres la gardent."
                    shared -> "La playlist est supprimée pour tous ses membres."
                    else -> "Seule la playlist est supprimée. Vos titres et téléchargements sont conservés."
                }, color = th.secondary, modifier = Modifier.padding(top = 12.dp))
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                    SettingsAction("Annuler", { deleting = false }, enabled = !busy)
                    SettingsAction(if (leaves) "Quitter" else "Supprimer", { action { PlaylistStore.delete(playlist.id); AppUi.closeOverlay() } },
                        enabled = !busy, primary = true)
                }
            }
        }
    }
    // The bar floats over the page: back, the playlist's name once the header has gone, its actions.
    // The page's edge fades under the bar once the header has gone (iOS's scroll edge), so the name stays readable.
    if (collapse > .01f) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().graphicsLayer { alpha = collapse }
        .background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to th.background, .7f to th.background.copy(alpha = .9f),
            1f to th.background.copy(alpha = 0f))).statusBarsPadding().height(80.dp))
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassBackButton { if (editing) editing = false else AppUi.closeOverlay() }
        // The name settles beside the back button as the header leaves: it keeps the room the buttons leave.
        Box(Modifier.weight(1f).padding(start = 4.dp).graphicsLayer { alpha = collapse }
            .then(if (collapse < .5f) Modifier.clearAndSetSemantics {} else Modifier).testTag("playlist-collapsed-title")) {
            if (collapse > .01f) Text(playlist?.name.orEmpty(), localize = false, color = th.onBackground, style = Ket27.Type.headline,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.asHeading())
        }
        val quickTracks = playlist?.entries?.mapNotNull { it.track }.orEmpty()
        if (editing) LiquidGlassButton({ editing = false }, Modifier.height(48.dp).widthIn(min = 48.dp).testTag("playlist-reorder-done"),
            shape = RoundedCornerShape(24.dp)) {
            Text("Terminer", color = th.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp))
        } else if (quickTracks.isNotEmpty() && collapse > 0f) {
            Box(Modifier.graphicsLayer { alpha = collapse }
                .then(if (collapse < .98f) Modifier.clearAndSetSemantics {} else Modifier)) {
                LiquidGlassButton({ Player.setShuffle(false); Player.playQueue(quickTracks) }, Modifier.size(DesignTokens.Space.touch),
                    enabled = collapse >= .98f) {
                    Icon(KetIcons.Play, "Écouter", tint = th.accent, modifier = Modifier.size(22.dp))
                }
            }
        }
        LiquidGlassButton(onClick = {
            playlist?.let { target ->
                com.ketvoice.app.util.ShareLinks.sharePlaylist(context, target.id, target.name, target.entries.size)
            }
        }, modifier = Modifier.size(DesignTokens.Space.touch), enabled = playlist != null) {
            Icon(Icons.Filled.Share, "Partager la playlist", tint = th.onBackground, modifier = Modifier.size(20.dp))
        }
        LiquidGlassButton(onClick = {
            // The sheet is drawn by AppRoot (over the floating chrome); this page only posts the request and its actions.
            val target = playlist ?: return@LiquidGlassButton
            AppUi.playlistMenu.value = AppUi.PlaylistMenuRequest(
                playlist = target,
                onRename = { name = target.name; rename = true },
                onExportJson = { action { PlaylistExchange.exportJson(context, target) } },
                onExportM3u = { action { PlaylistExchange.exportM3u(context, target) } },
                onDelete = { deleting = true },
                onShare = { shareFromMenu(target) },
                onMembers = { SharedUi.members.value = target.id },
                onTogglePublic = { publicFromMenu(target, context) },
                onReorder = if (target.entries.size > 1 && !busy) ({ editing = true }) else null
            )
        }, modifier = Modifier.size(DesignTokens.Space.touch), enabled = playlist != null) {
            Icon(Icons.Filled.MoreHoriz, "Actions de la playlist", tint = th.onBackground, modifier = Modifier.size(22.dp))
        }
    }
    }
}

/**
 * The head of a playlist, as Apple Music draws it: the artwork with a soft shadow, the name, who made it in the
 * accent colour, a short line about it, then Play and Shuffle side by side. Centred on a phone; on a wide pane
 * the artwork stands beside the text. The page itself stays plain.
 */
@Composable
private fun PlaylistHeader(playlist: LocalPlaylist, playable: List<Track>, owner: String, beside: Boolean, side: Dp,
                           editing: Boolean, error: String, onPlay: () -> Unit, onShuffle: () -> Unit) {
    val th = LocalTheme.current
    val shared = PlaylistRules.isShared(playlist.id)
    val mine = !shared || com.ketvoice.app.sync.SharedPlaylists.isOwner(playlist.id)
    val curator = if (mine) owner else AppText.t("Playlist partagée")
    val kind = when {
        shared -> "Playlist partagée"
        playlist.spotifyUrl != null -> "Import Spotify"
        else -> "Playlist"
    }
    val meta = listOfNotNull(AppText.t(kind).takeIf { mine || !shared },
        AppText.t("${playable.size} / ${playlist.entries.size} titres disponibles").takeIf { playable.size < playlist.entries.size })
        .joinToString(" · ")
    val zoom = com.ketvoice.app.ui.motion.rememberZoomTarget(12.dp)
    val artwork: @Composable () -> Unit = {
        val shape = RoundedCornerShape(if (side < 140.dp) 8.dp else 12.dp)
        Box(Modifier.size(side).zoomTarget(zoom)
            .graphicsLayer { alpha = zoom.alpha() }
            .shadow(if (side < 140.dp) 8.dp else 22.dp, shape, ambientColor = Color.Black.copy(alpha = .22f),
                spotColor = Color.Black.copy(alpha = .32f))
            .testTag("playlist-cover")) {
            PlaylistArtwork(playlist, playlist.name, Modifier.fillMaxSize(), if (side < 140.dp) 8.dp else 12.dp)
        }
    }
    val actions: @Composable (Modifier) -> Unit = { place ->
        com.ketvoice.app.ui.common.CollectionActionPair(onPlay, onShuffle, place, playLabel = "Lire",
            enabled = playable.isNotEmpty() && !editing, playTag = "playlist-play", shuffleTag = "playlist-shuffle",
            minHeight = if (side < 140.dp) 46.dp else 50.dp)
    }
    val note: @Composable (TextAlign) -> Unit = { align ->
        if (playlist.spotifyUrl != null) Text("La liste peut être incomplète : touchez un titre manquant pour le retrouver.",
            color = th.secondary, style = Ket27.Type.footnote, textAlign = align, modifier = Modifier.padding(top = 6.dp))
        if (error.isNotBlank()) Text(error, color = DESTRUCTIVE, style = Ket27.Type.footnote, textAlign = align,
            modifier = Modifier.padding(top = 6.dp))
    }
    if (beside) Row(Modifier.fillMaxWidth().padding(start = pageMargin + 4.dp, end = pageMargin, top = 4.dp, bottom = 22.dp)
        .testTag("playlist-header"), verticalAlignment = Alignment.Bottom) {
        artwork()
        Column(Modifier.weight(1f).padding(start = 28.dp)) {
            Text(playlist.name, localize = false, color = th.onBackground,
                style = if (side < 140.dp) Ket27.Type.title2 else Ket27.Type.largeTitle, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.asHeading().testTag("playlist-title"))
            Text(curator, localize = false, color = th.accent, style = Ket27.Type.title3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                if (meta.isNotBlank()) Text(meta, localize = false, color = th.secondary, style = Ket27.Type.footnote)
                if (shared) SharedBadge(Modifier.padding(start = 8.dp))
            }
            note(TextAlign.Start)
            Spacer(Modifier.height(if (side < 140.dp) 10.dp else 18.dp))
            actions(Modifier.widthIn(max = 420.dp))
        }
    } else Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 6.dp, bottom = 20.dp).testTag("playlist-header"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        artwork()
        Text(playlist.name, localize = false, color = th.onBackground, fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-.4).sp, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 22.dp).asHeading().testTag("playlist-title"))
        Text(curator, localize = false, color = th.accent, style = Ket27.Type.title3, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            if (meta.isNotBlank()) Text(meta, localize = false, color = th.secondary, style = Ket27.Type.footnote, textAlign = TextAlign.Center)
            if (shared) SharedBadge(Modifier.padding(start = 8.dp))
        }
        note(TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        actions(Modifier.widthIn(max = 520.dp))
    }
}

@Composable
private fun PlaylistRow(entry: PlaylistEntry, editing: Boolean, busy: Boolean, wide: Boolean, th: com.ketvoice.app.theme.KetTheme,
                        modifier: Modifier = Modifier, dragHandle: Modifier = Modifier,
                        onPlay: () -> Unit, onRemove: () -> Unit, active: Boolean = false) {
    val cover = entry.track?.let { ArtworkRepo.coverForTrack(it) } ?: entry.track?.coverUrl
    val track = entry.track
    val row: @Composable () -> Unit = {
    Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp)
        // Outside editing a resolved title behaves like every other list: tap, long-press, swipe.
        .then(if (!editing && track != null) Modifier.trackRowClick(track, enabled = !busy, onClick = onPlay)
            else Modifier.clickable(enabled = !busy, onClick = onPlay))
        .padding(start = if (editing) 4.dp else pageMargin, end = 4.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        if (editing) Box(dragHandle.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.DragHandle, "Déplacer ${entry.title}", tint = th.secondary, modifier = Modifier.size(20.dp))
        }
        if (entry.track != null) DownloadCover(entry.track, cover, 48.dp, 6.dp)
        else Cover(entry.title, cover, 48.dp, 6.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            TrackTitle(entry.title, if (active) th.accent else th.onBackground, fontSize = 16.sp, selected = active, maxLines = 1)
            Text(entry.artist, localize = false, color = th.secondary, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (entry.track == null) Text("Non retrouvé · choisir une version", color = th.accent, fontSize = 12.sp)
        }
        // A wide pane has room for the length of each title, as Apple Music shows it on iPad and Mac.
        if (wide) PlaylistPage.duration(entry.track?.durationMs ?: 0L)?.let {
            Text(it, localize = false, color = th.secondary, style = Ket27.Type.footnote, modifier = Modifier.padding(end = 8.dp))
        }
        val menuAnchor = com.ketvoice.app.ui.common.rememberRowAnchor()
        Box(Modifier.size(48.dp).then(menuAnchor.modifier).clip(CircleShape).clickable(enabled = !busy) {
            if (editing || entry.track == null) onRemove() else menuAnchor.open(entry.track)
        },
            contentAlignment = Alignment.Center) {
            Icon(if (editing || entry.track == null) Icons.Filled.Close else Icons.Filled.MoreHoriz,
                if (editing || entry.track == null) "Retirer ${entry.title}" else "Options de ${entry.title}",
                tint = th.secondary, modifier = Modifier.size(20.dp))
        }
    }
    // The hairline starts under the title, past the artwork, as iOS insets it.
    Box(Modifier.padding(start = if (editing) 4.dp + 48.dp + 60.dp else pageMargin + 60.dp).fillMaxWidth().height(.5.dp).background(th.hairline))
    }
    }
    if (!editing && track != null) com.ketvoice.app.ui.common.SwipeTrackActions(track, modifier, enabled = !busy) { row() }
    else Box(modifier) { row() }
}
