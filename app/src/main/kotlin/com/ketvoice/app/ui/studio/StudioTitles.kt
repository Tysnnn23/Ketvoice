package com.ketvoice.app.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.PublicationApi
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.VisualTarget
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.GlassCapsuleButton
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import java.io.File

/** What the owner did to a title's Canvas, in a word for its row. */
internal fun canvasMark(track: Track): String? {
    val own = MediaOverrides.track(track)
    return when {
        own?.canvas != null -> "Canvas personnalisé"
        MediaOverrides.canvasDisabled(track) -> "Canvas désactivé"
        else -> null
    }
}

/** What the owner decided for the music player alone, in a word for the title's row. */
internal fun playerCanvasMark(track: Track): String? =
    when (MediaOverrides.track(track)?.playerCanvas ?: MediaOverrides.albumOf(track)?.playerCanvas) {
        true -> "Affiché dans le lecteur"
        false -> "Masqué dans le lecteur"
        null -> null
    }

/** The titles that an action reaches: the ones ticked, or every title of the album when none is. */
internal fun canvasTargets(tracks: List<Track>, selected: Set<String>): List<Track> =
    (if (selected.isEmpty()) tracks else tracks.filter { it.id in selected }).distinctBy { MediaOverrides.keyFor(it) }

/**
 * The Canvas of an album's titles, one by one: tick titles, then switch their Canvas off, bring
 * the catalogue's own back, or give them all the Canvas chosen above. Only in the Studio; with no
 * title ticked the action is for the album itself.
 */
@Composable
internal fun StudioTitleCanvas(state: StudioState, target: VisualTarget, rights: Boolean) {
    val theme = LocalTheme.current
    var selected by remember(target.key) { mutableStateOf(setOf<String>()) }
    var canvas by remember(target.key) { mutableStateOf<File?>(null) }
    var canvasLink by remember(target.key) { mutableStateOf<String?>(null) }
    // Two modes: what everyone sees on the album page (the player follows it), or the music player alone.
    var playerOnly by remember(target.key) { mutableStateOf(false) }
    val tracks = target.tracks
    val reached = canvasTargets(tracks, selected)
    val wholeAlbum = selected.isEmpty()
    fun run(done: String, block: suspend (List<Pair<String, String>>) -> Unit) = state.run {
        val items = reached.mapNotNull { t -> VisualTarget.track(t)?.let { it.key to it.label } }
        if (items.isEmpty() && !wholeAlbum) error("Aucun titre exploitable")
        block(items)
        state.info = done
    }
    /**
     * An action for the whole album also reaches the titles that had a Canvas of their own, or had it
     * switched off: a title's own choice wins over its album's, so it is given back to the album.
     */
    suspend fun freeTitlesFromOwnCanvas() {
        val own = tracks.filter { MediaOverrides.track(it)?.let { o -> o.canvas != null || o.canvasOff } == true }
            .mapNotNull { t -> VisualTarget.track(t)?.let { it.key to it.label } }
        if (own.isNotEmpty()) PublicationApi.saveOverrides(MediaOverrides.TRACK, own, restore = listOf("canvas"))
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(theme.card).padding(16.dp).testTag("studio-title-canvas"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Canvas des titres", color = theme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("studio-canvas-scope")) {
            ScopeChip("Page d'album", !playerOnly, "studio-canvas-scope-page") { playerOnly = false }
            ScopeChip("Lecteur", playerOnly, "studio-canvas-scope-player") { playerOnly = true }
        }
        Text(if (playerOnly) "Ne change que ce que montre le lecteur : la page d'album reste comme elle est."
            else "Change la page d'album ; le lecteur la suit, sauf s'il a son propre réglage.", color = theme.secondary, fontSize = 12.sp)
        Text(if (wholeAlbum) "Aucun titre choisi : l'action vise tout l'album."
            else if (selected.size == 1) "1 titre choisi" else "${selected.size} titres choisis", color = theme.secondary, fontSize = 12.sp,
            modifier = Modifier.testTag("studio-title-count"))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Tout sélectionner", color = theme.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { selected = tracks.map { it.id }.toSet() }
                    .heightIn(min = 44.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 4.dp).testTag("studio-title-all"))
            Text("Aucun", color = theme.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { selected = emptySet() }
                    .heightIn(min = 44.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 4.dp).testTag("studio-title-none"))
        }
        tracks.forEachIndexed { index, track ->
            val ticked = track.id in selected
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Checkbox) {
                selected = if (ticked) selected - track.id else selected + track.id
            }.padding(vertical = 2.dp).testTag("studio-title-row"), verticalAlignment = Alignment.CenterVertically) {
                com.ketvoice.app.ui.common.KetCheckbox(ticked, null)
                Text("${index + 1}", localize = false, color = theme.secondary, fontSize = 12.sp, modifier = Modifier.width(22.dp))
                Text(track.title, localize = false, color = theme.onBackground, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                (if (playerOnly) playerCanvasMark(track) else canvasMark(track))?.let { StudioChip(it) }
            }
        }
        val enabled = !state.busy && rights && (reached.isNotEmpty() || wholeAlbum)
        if (playerOnly) {
            fun player(done: String, change: PublicationApi.PlayerChange) = run(done) { items ->
                if (wholeAlbum) PublicationApi.saveOverride(MediaOverrides.ALBUM, target.key, target.label, player = change)
                else PublicationApi.saveOverrides(MediaOverrides.TRACK, items, player = change)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassCapsuleButton("Masquer le Canvas dans le lecteur", null, {
                    player("Canvas masqué dans le lecteur", PublicationApi.PlayerChange(canvas = false))
                }, Modifier.fillMaxWidth(), enabled = enabled, tag = "studio-player-off", minHeight = 48.dp)
                GlassCapsuleButton("Afficher le Canvas dans le lecteur", null, {
                    player("Canvas affiché dans le lecteur", PublicationApi.PlayerChange(canvas = true))
                }, Modifier.fillMaxWidth(), enabled = enabled, tag = "studio-player-on", minHeight = 48.dp)
                GlassCapsuleButton("Suivre la page d'album", KetIcons.Refresh, {
                    player("Le lecteur suit la page d'album", PublicationApi.PlayerChange(followCanvas = true))
                }, Modifier.fillMaxWidth(), enabled = enabled, tag = "studio-player-follow", minHeight = 48.dp)
            }
            return@Column
        }
        CanvasEditor(state, canvas, onChosen = { file -> canvas?.delete(); canvas = file; if (file != null) canvasLink = null },
            linked = canvasLink, onLink = { canvasLink = it })
        GlassCapsuleButton("Appliquer le Canvas choisi", null, {
            val file = canvas
            val links = canvasLink?.let { mapOf("canvas" to it) } ?: emptyMap()
            if (file == null && links.isEmpty()) return@GlassCapsuleButton
            run("Canvas appliqué") { items ->
                if (wholeAlbum) {
                    PublicationApi.saveOverride(MediaOverrides.ALBUM, target.key, target.label, canvas = file,
                        onProgress = state::onProgress, links = links)
                    freeTitlesFromOwnCanvas()
                }
                else PublicationApi.saveOverrides(MediaOverrides.TRACK, items, canvas = file, onProgress = state::onProgress, links = links)
                file?.delete(); canvas = null; canvasLink = null
            }
        }, Modifier.fillMaxWidth(), prominent = true, enabled = enabled && (canvas != null || canvasLink != null),
            tag = "studio-title-apply", minHeight = 52.dp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassCapsuleButton("Désactiver le Canvas", null, {
                run("Canvas désactivé") { items ->
                    if (wholeAlbum) { PublicationApi.saveOverride(MediaOverrides.ALBUM, target.key, target.label, canvasOff = true); freeTitlesFromOwnCanvas() }
                    else PublicationApi.saveOverrides(MediaOverrides.TRACK, items, canvasOff = true)
                }
            }, Modifier.fillMaxWidth(), enabled = enabled, tag = "studio-title-off", minHeight = 48.dp)
            GlassCapsuleButton("Rétablir le Canvas d'origine", KetIcons.Refresh, {
                run("Canvas d'origine rétabli") { items ->
                    if (wholeAlbum) { PublicationApi.saveOverride(MediaOverrides.ALBUM, target.key, target.label, restore = listOf("canvas")); freeTitlesFromOwnCanvas() }
                    else PublicationApi.saveOverrides(MediaOverrides.TRACK, items, restore = listOf("canvas"))
                }
            }, Modifier.fillMaxWidth(), enabled = enabled, tag = "studio-title-restore", minHeight = 48.dp)
        }
    }
}

/** One of the two modes of the Canvas actions. */
@Composable
private fun ScopeChip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val theme = LocalTheme.current
    val state = AppText.t(if (selected) "Sélectionné" else "Non sélectionné")
    Box(Modifier.clip(RoundedCornerShape(999.dp))
        .background(if (selected) theme.accent.copy(alpha = .20f) else theme.onBackground.copy(alpha = .07f))
        .clickable(role = Role.Tab, onClick = onClick)
        .semantics { stateDescription = state }
        .heightIn(min = 44.dp).padding(horizontal = 16.dp).testTag(tag), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) theme.accent else theme.onBackground, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}
