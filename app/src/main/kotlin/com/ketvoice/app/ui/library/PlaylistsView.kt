package com.ketvoice.app.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.PlaylistRules
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.KetMenu
import com.ketvoice.app.ui.common.KetMenuItem
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.pageMargin

/** How Library › Playlists orders its playlists (Apple Music's « Trier par »). */
internal enum class PlaylistSort(val label: String) { Recent("Ajout récent"), Title("Titre"), Size("Nombre de titres") }

/** Which playlists Library › Playlists shows. */
internal enum class PlaylistFilter(val label: String) { All("Toutes les playlists"), Pinned("Épinglées"), Shared("Partagées") }

/** The list of Library › Playlists, worked out without a screen. */
internal object PlaylistsLayout {
    /** The playlists shown for [query], [filter] and [sort]; the stored lists are never changed. */
    fun arrange(lists: List<LocalPlaylist>, query: String, sort: PlaylistSort, filter: PlaylistFilter): List<LocalPlaylist> {
        val term = query.trim()
        val kept = lists.filter { playlist ->
            (term.isEmpty() || playlist.name.contains(term, ignoreCase = true)) && when (filter) {
                PlaylistFilter.All -> true
                PlaylistFilter.Pinned -> playlist.pinned
                PlaylistFilter.Shared -> PlaylistRules.isShared(playlist.id)
            }
        }
        return when (sort) {
            PlaylistSort.Recent -> kept.sortedByDescending { it.createdAt }
            PlaylistSort.Title -> kept.sortedBy { it.name.lowercase() }
            PlaylistSort.Size -> kept.sortedByDescending { it.entries.size }
        }
    }

    /** « Playlist · owner », as Apple Music writes it under each playlist (French parts, translated at display). */
    fun subtitle(playlist: LocalPlaylist, owner: String): List<String> = when {
        PlaylistRules.isShared(playlist.id) -> listOf("Playlist partagée")
        playlist.spotifyUrl != null -> listOf("Playlist", "Spotify")
        else -> listOf("Playlist", owner)
    }
}

/** Who « owns » the listener's own playlists: their Ketvoice name, or « Vous ». */
@Composable
internal fun playlistOwner(): String {
    val profile by com.ketvoice.app.account.Profiles.profile.collectAsState()
    return profile.displayName.takeIf { it.isNotBlank() } ?: AppText.t("Vous")
}

private fun subtitleText(playlist: LocalPlaylist, owner: String): String =
    PlaylistsLayout.subtitle(playlist, owner).joinToString(" · ") { if (it == owner) it else AppText.t(it) }

/**
 * Library › Playlists (Ket 27, after Apple Music): « Nouvelle playlist » and the import first, then the
 * playlists as a list of rows or a grid of covers. On a wide pane the rows stand two by two and the grid
 * takes as many columns as the width allows ([com.ketvoice.app.ui.adaptive.LargeScreen.gridColumns]).
 */
internal fun LazyListScope.playlistsSection(rows: List<LocalPlaylist>, grid: Boolean, paneDp: Float, owner: String,
                                            enabled: Boolean, onPin: ((LocalPlaylist) -> Unit)?, onNew: () -> Unit,
                                            onImport: () -> Unit) {
    item(key = "playlists-actions", contentType = "playlists-actions") {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp).testTag("playlists-actions")) {
            PlaylistActionRow("Nouvelle playlist", KetIcons.Plus, enabled, "playlists-new", onNew)
            PlaylistActionRow("Importer des playlists", KetIcons.Link, enabled, "playlists-import", onImport, last = true)
        }
    }
    if (grid) {
        val columns = com.ketvoice.app.ui.adaptive.LargeScreen.gridColumns(paneDp)
        val cell = com.ketvoice.app.ui.adaptive.LargeScreen.cellWidth(paneDp, columns, com.ketvoice.app.ui.adaptive.LargeScreen.GRID_GAP_DP).dp
        items(rows.chunked(columns).size, key = { "playlists-grid:" + rows[it * columns].id }, contentType = { "playlists-grid" }) { line ->
            Row(Modifier.fillMaxWidth().padding(horizontal = pageMargin).padding(top = 10.dp, bottom = 8.dp)
                .then(if (line == 0) Modifier.testTag("library-playlist-grid") else Modifier),
                horizontalArrangement = Arrangement.spacedBy(com.ketvoice.app.ui.adaptive.LargeScreen.GRID_GAP_DP.dp)) {
                rows.subList(line * columns, minOf(rows.size, line * columns + columns)).forEach { playlist ->
                    PlaylistGridCell(playlist, cell, subtitleText(playlist, owner), onPin)
                }
            }
        }
    } else {
        val columns = com.ketvoice.app.ui.adaptive.LargeScreen.listColumns(paneDp)
        items((rows.size + columns - 1) / columns, key = { "playlists-row:" + rows[it * columns].id }, contentType = { "playlists-row" }) { line ->
            Row(Modifier.fillMaxWidth().padding(horizontal = pageMargin - 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val shown = rows.subList(line * columns, minOf(rows.size, line * columns + columns))
                shown.forEach { playlist ->
                    PlaylistListRow(playlist, subtitleText(playlist, owner), onPin, Modifier.weight(1f))
                }
                repeat(columns - shown.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** « Nouvelle playlist » / « Importer des playlists »: a tile in the accent colour where a cover would be. */
@Composable
private fun PlaylistActionRow(label: String, icon: ImageVector, enabled: Boolean, tag: String, onClick: () -> Unit, last: Boolean = false) {
    val theme = LocalTheme.current
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag(tag)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = pageMargin, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(theme.onBackground.copy(alpha = if (theme.isDark) .10f else .06f)),
                contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (enabled) theme.accent else theme.secondary, modifier = Modifier.size(24.dp))
            }
            Text(label, color = if (enabled) theme.accent else theme.secondary, style = Ket27.Type.body,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp))
        }
        // The hairline starts where the text starts, after the 56 dp tile, as iOS insets it.
        if (!last) Box(Modifier.padding(start = pageMargin + 70.dp).fillMaxWidth().height(.5.dp).background(theme.hairline))
    }
}

/** What a playlist's « … » holds in the Library: play it, shuffle it, pin it. */
private fun playlistMenu(playlist: LocalPlaylist, onPin: ((LocalPlaylist) -> Unit)?): List<List<KetMenuItem>> {
    val playable = playlist.entries.mapNotNull { it.track }
    return listOf(
        listOf(KetMenuItem("Lire", KetIcons.Play, enabled = playable.isNotEmpty()) { Player.setShuffle(false); Player.playQueue(playable) },
            KetMenuItem("Aléatoire", KetIcons.Shuffle, enabled = playable.isNotEmpty()) { Player.playShuffled(playable) },
            KetMenuItem("Lire ensuite", KetIcons.Playlist, enabled = playable.isNotEmpty()) { Player.enqueue(playable) }),
        listOfNotNull(onPin?.let { pin ->
            KetMenuItem(if (playlist.pinned) "Désépingler" else "Épingler", androidx.compose.material.icons.Icons.Filled.PushPin, tag = "playlist-pin:" + playlist.id) { pin(playlist) }
        }))
}

@Composable
private fun PlaylistListRow(playlist: LocalPlaylist, subtitle: String, onPin: ((LocalPlaylist) -> Unit)?, modifier: Modifier) {
    val theme = LocalTheme.current
    val zoom = com.ketvoice.app.ui.motion.rememberZoomSource(8.dp) { place -> PlaylistArtwork(playlist, playlist.name, place, 8.dp) }
    var menu by remember { mutableStateOf(false) }
    Column(modifier.testTag("playlist-row")) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { zoom.openPlaylist(playlist.id) }
            .heightIn(min = 72.dp).padding(start = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaylistArtwork(playlist, playlist.name, Modifier.size(56.dp).then(com.ketvoice.app.ui.motion.zoomSourceOf(zoom)), 8.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(playlist.name, localize = false, color = theme.onBackground, style = Ket27.Type.body,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (playlist.pinned) Icon(androidx.compose.material.icons.Icons.Filled.PushPin, "Épinglée", tint = theme.secondary,
                        modifier = Modifier.padding(start = 6.dp).size(13.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Text(subtitle, localize = false, color = theme.secondary, style = Ket27.Type.footnote,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (PlaylistRules.isShared(playlist.id)) SharedBadge(Modifier.padding(start = 6.dp))
                }
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                    Icon(KetIcons.More, "Options de ${playlist.name}", tint = theme.secondary, modifier = Modifier.size(20.dp))
                }
                KetMenu(menu, { menu = false }, playlistMenu(playlist, onPin))
            }
        }
        Box(Modifier.padding(start = 74.dp).fillMaxWidth().height(.5.dp).background(theme.hairline))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistGridCell(playlist: LocalPlaylist, side: Dp, subtitle: String, onPin: ((LocalPlaylist) -> Unit)?) {
    val theme = LocalTheme.current
    val zoom = com.ketvoice.app.ui.motion.rememberZoomSource(10.dp) { place -> PlaylistArtwork(playlist, playlist.name, place, 10.dp) }
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.width(side)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .combinedClickable(role = Role.Button, onLongClickLabel = AppText.t("Options de ${playlist.name}"),
                onLongClick = { menu = true }) { zoom.openPlaylist(playlist.id) }
            .padding(bottom = 6.dp).testTag("playlist-cell")) {
            PlaylistArtwork(playlist, playlist.name, Modifier.size(side).then(com.ketvoice.app.ui.motion.zoomSourceOf(zoom)), 10.dp)
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(playlist.name, localize = false, color = theme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (playlist.pinned) Icon(androidx.compose.material.icons.Icons.Filled.PushPin, "Épinglée", tint = theme.secondary,
                    modifier = Modifier.padding(start = 4.dp).size(12.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(subtitle, localize = false, color = theme.secondary, style = Ket27.Type.footnote, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (PlaylistRules.isShared(playlist.id)) SharedBadge(Modifier.padding(start = 6.dp))
            }
        }
        Box(Modifier.align(Alignment.TopEnd)) { KetMenu(menu, { menu = false }, playlistMenu(playlist, onPin)) }
    }
}
