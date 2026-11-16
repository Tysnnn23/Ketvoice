package com.ketvoice.app.ui.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.StudioCanvas
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.GlassCapsuleButton
import com.ketvoice.app.ui.common.GlassSegmentedControl
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import java.io.File

/**
 * Chooses a Canvas from a file, a direct link to a video, or a link to an API (JSON that
 * names the video, or a Spotify track link), then shows what the video is before it is
 * sent: format, weight, duration and orientation.
 */
@Composable
internal fun CanvasEditor(state: StudioState, chosen: File?, onChosen: (File?) -> Unit, published: Boolean = false,
                          onRemove: (() -> Unit)? = null, removeEnabled: Boolean = false,
                          linked: String? = null, onLink: ((String?) -> Unit)? = null) {
    val theme = LocalTheme.current
    var source by remember { mutableIntStateOf(0) }
    var link by remember { mutableStateOf("") }
    var checks by remember(chosen) { mutableStateOf<List<StudioCanvas.Check>>(emptyList()) }
    LaunchedEffect(chosen) { checks = chosen?.let { state.files.canvasChecks(it) }.orEmpty() }
    // A file that fails a blocking check is never kept for sending.
    LaunchedEffect(checks) {
        if (chosen != null && checks.isNotEmpty() && !StudioCanvas.canSend(checks)) {
            state.error = checks.first { it.level == StudioCanvas.Level.Blocking }.label
            onChosen(null)
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.run { onLink?.invoke(null); onChosen(state.files.copy(uri, StudioCanvas.MAX_BYTES, setOf("mp4", "m4v", "mov"))) }
    }
    // Ket 27: a group of the inset list (26 dp corners), the Canvas's tile and name at its head.
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(com.ketvoice.app.theme.Ket27.Metrics.groupRadius)).background(theme.card)
        .padding(16.dp).testTag("studio-canvas"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.ketvoice.app.ui.settings.Ket27Tile(KetIcons.Canvas, 4)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Canvas", color = theme.onBackground, style = com.ketvoice.app.theme.Ket27.Type.body)
                Text(when {
                    linked != null -> "Lien · diffusé depuis son adresse, sans limite de taille"
                    chosen != null -> "Nouvelle vidéo · à envoyer"
                    published -> "En ligne"
                    else -> "Aucun · vidéo courte en boucle derrière le titre"
                }, color = theme.secondary, style = com.ketvoice.app.theme.Ket27.Type.footnote)
            }
            if (chosen != null || linked != null) com.ketvoice.app.ui.common.KetTextButton("Annuler",
                { onChosen(null); onLink?.invoke(null) }, Modifier.heightIn(min = 48.dp))
        }
        GlassSegmentedControl(listOf("Fichier", "Lien vidéo", "API"), source, { source = it }, tag = "studio-canvas-source")
        when (source) {
            0 -> GlassCapsuleButton("Choisir un MP4", null, { pick.launch(arrayOf("video/mp4", "video/quicktime", "video/*")) },
                Modifier.fillMaxWidth(), enabled = !state.busy, tag = "studio-canvas-file", minHeight = 48.dp)
            else -> {
                com.ketvoice.app.ui.common.KetTextField(link, { link = it.take(2048) }, if (source == 1) "Lien direct vers une vidéo MP4" else "Lien d'API ou lien de titre Spotify",
                    Modifier.fillMaxWidth().testTag("studio-canvas-link"), enabled = !state.busy)
                GlassCapsuleButton(if (onLink != null) "Utiliser ce lien" else "Récupérer la vidéo", null, {
                    val parsed = StudioCanvas.classify(link)
                    if (parsed == null) state.error = "Lien HTTPS requis"
                    // A link is not downloaded: it is checked, then streamed from where it is.
                    else if (onLink != null) state.run { val url = StudioCanvas.resolve(parsed); onChosen(null); onLink(url) }
                    else state.run { onChosen(StudioCanvas.download(parsed, state.files.newFile("mp4"))) }
                }, Modifier.fillMaxWidth(), enabled = !state.busy && link.isNotBlank(), tag = "studio-canvas-fetch", minHeight = 48.dp)
                if (onLink != null) Text("La vidéo est lue depuis ce lien : rien n'est téléchargé ni stocké, sans limite de taille.",
                    color = theme.secondary, style = com.ketvoice.app.theme.Ket27.Type.footnote)
            }
        }
        if (chosen != null && checks.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("studio-canvas-checks")) {
            checks.forEach { check ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val tint = when (check.level) {
                        StudioCanvas.Level.Ok -> Color(0xFF34C759)
                        StudioCanvas.Level.Warning -> Color(0xFFFF9F0A)
                        StudioCanvas.Level.Blocking -> Color(0xFFFF453A)
                    }
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(tint))
                    Text(check.label, color = theme.onBackground, style = com.ketvoice.app.theme.Ket27.Type.footnote, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        if (published && chosen == null && onRemove != null) GlassCapsuleButton("Retirer le Canvas", null, onRemove, Modifier.fillMaxWidth(),
            enabled = !state.busy && removeEnabled, tag = "studio-canvas-remove", minHeight = 44.dp)
    }
}
