package com.ketvoice.app.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
    val tracks = target.tracks
    val reached = canvasTargets(tracks, selected)
    val wholeAlbum = selected.isEmpty()
    fun run(done: String, block: suspend (List<Pair<String, String>>) -> Unit) = state.run {
        val items = reached.mapNotNull { t -> VisualTarget.track(t)?.let { it.key to it.label } }
        if (items.isEmpty() && !wholeAlbum) error("Aucun titre exploitable")
        block(items)
        state.info = done
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(theme.card).padding(16.dp).testTag("studio-title-canvas"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Canvas des titres", color = theme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
                Checkbox(ticked, null, colors = CheckboxDefaults.colors(checkedColor = theme.accent, uncheckedColor = theme.secondary))
                Text("${index + 1}", localize = false, color = theme.secondary, fontSize = 12.sp, modifier = Modifier.width(22.dp))
                Text(track.title, localize = false, color = theme.onBackground, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                canvasMark(track)?.let { StudioChip(it) }
            }
        }
        CanvasEditor(state, canvas, onChosen = { file -> canvas?.delete(); canvas = file })
        val enabled = !state.busy && rights && (reached.isNotEmpty() || wholeAlbum)
        GlassCapsuleButton("Appliquer le Canvas choisi", null, {
            val file = canvas ?: return@GlassCapsuleButton
            run("Canvas appliqué") { items ->
                if (wholeAlbum) PublicationApi.saveOverride(MediaOverrides.ALBUM, target.key, target.label, canvas = file, onProgress = state::onProgress)
                else PublicationApi.saveOverrides(MediaOverrides.TRACK, items, canvas = file, onProgress = state::onProgress)
                file.delete(); canvas = null
            }
        }, Modifier.fillMaxWidth(), prominent = true, enabled = enabled && canvas != null, tag = "studio-title-apply", minHeight = 52.dp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassCapsuleButton("Désactiver le Canvas", null, {
                run("Canvas désactivé") { items ->
                    if (wholeAlbum) PublicationApi.saveOverride(MediaOverrides.ALBUM, target.key, target.label, canvasOff = true)
                    else PublicationApi.saveOverrides(MediaOverrides.TRACK, items, canvasOff = true)
                }
            }, Modifier.fillMaxWidth(), enabled = enabled, tag = "studio-title-off", minHeight = 48.dp)
            GlassCapsuleButton("Rétablir le Canvas d'origine", KetIcons.Refresh, {
                run("Canvas d'origine rétabli") { items ->
                    if (wholeAlbum) PublicationApi.saveOverride(MediaOverrides.ALBUM, target.key, target.label, restore = listOf("canvas"))
                    else PublicationApi.saveOverrides(MediaOverrides.TRACK, items, restore = listOf("canvas"))
                }
            }, Modifier.fillMaxWidth(), enabled = enabled, tag = "studio-title-restore", minHeight = 48.dp)
        }
    }
}
