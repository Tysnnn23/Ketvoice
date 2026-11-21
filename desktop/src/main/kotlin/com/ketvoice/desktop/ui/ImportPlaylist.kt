package com.ketvoice.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.smartlib.ImportMatch
import com.ketvoice.app.smartlib.ImportPreview
import com.ketvoice.app.smartlib.PlaylistImport
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Where the import dialog is: the link, then the service's preview, then the titles found in Ketvoice. */
@Stable
class ImportState(link: String = "") {
    var link by mutableStateOf(link)
    var preview by mutableStateOf<ImportPreview?>(null)
    var matches by mutableStateOf<List<ImportMatch>>(emptyList())
    var done by mutableStateOf(0)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val found: Int get() = matches.count { it.track != null }

    fun run(scope: CoroutineScope, block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { block() }
            catch (c: kotlinx.coroutines.CancellationException) { throw c }
            catch (e: IllegalArgumentException) { error = e.message }
            catch (e: IllegalStateException) { error = e.message }
            catch (_: java.io.IOException) { error = "Import impossible. Vérifiez votre connexion." }
            catch (_: Exception) { error = "Import impossible. Réessayez plus tard." }
            finally { busy = false }
        }
    }
}

/** Whether the import dialog is open (the sidebar's link button, the empty Playlists state). */
object ImportDialog { val open = mutableStateOf(false) }

/** The shared importer (PlaylistImport): public links of Spotify, Deezer, Apple Music, Tidal and SoundCloud, no account. */
interface PlaylistImporter {
    suspend fun preview(link: String): ImportPreview
    suspend fun resolve(preview: ImportPreview, progress: (Int, Int) -> Unit): List<ImportMatch>
}

object LiveImporter : PlaylistImporter {
    override suspend fun preview(link: String) = PlaylistImport.preview(link)
    override suspend fun resolve(preview: ImportPreview, progress: (Int, Int) -> Unit) =
        PlaylistImport.resolve(preview, { q: String -> MusicApi.searchAll(q) }, progress = progress)
}

/** « Importer des playlists », over the window: paste a link, look at the preview, find the titles, create it. */
@Composable
fun ImportPlaylistDialog(onDismiss: () -> Unit, state: ImportState = remember { ImportState() }, importer: PlaylistImporter = LiveImporter) {
    val ket = LocalKet.current
    val library = LocalLibrary.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val preview = state.preview
    fun primary() {
        when {
            preview == null ->
                state.run(scope) { state.preview = importer.preview(state.link.trim()); state.matches = emptyList(); state.done = 0 }
            state.matches.isEmpty() -> state.run(scope) { state.matches = importer.resolve(preview) { done, _ -> state.done = done } }
            else -> {
                library.importPlaylist(preview.name, PlaylistImport.entries(state.matches), preview.cover) { id -> nav.root(Route.Playlist(id)) }
                onDismiss()
            }
        }
    }
    Popup(alignment = Alignment.Center, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true),
        onKeyEvent = { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onDismiss(); true } else false }) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (ket.dark) .45f else .25f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss), contentAlignment = Alignment.Center) {
            Column(Modifier.width(540.dp).shadow(24.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(ket.raised)
                .clickable(remember { MutableInteractionSource() }, null) {}.padding(22.dp).testTag("import-dialog")) {
                KText("Importer des playlists", Ket27Desk.Type.headline, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Paragraph("Par lien public, sans compte ni clé : Ketvoice ne garde aucun accès à ces services.", ket.secondary)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp)).background(ket.fill).padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart) {
                    if (state.link.isEmpty()) KText("Lien de la playlist", Ket27Desk.Type.subheadline, color = ket.tertiary)
                    BasicTextField(state.link, { if (it.trim() != state.link.trim()) { state.preview = null; state.matches = emptyList() }; state.link = it.take(500) },
                        Modifier.fillMaxWidth().focusRequester(focus).testTag("import-link"), singleLine = true, cursorBrush = SolidColor(ket.accent),
                        textStyle = androidx.compose.material3.LocalTextStyle.current.merge(Ket27Desk.Type.subheadline).copy(color = ket.label),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { primary() }))
                }
                state.error?.let { Spacer(Modifier.height(8.dp)); Paragraph(it, destructiveRed, Modifier.testTag("import-error")) }
                if (preview != null) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (preview.cover != null) { Artwork(preview.cover, 52.dp, radius = 8.dp); Spacer(Modifier.width(12.dp)) }
                        Column(Modifier.weight(1f)) {
                            KText(preview.name, Ket27Desk.Type.subheadline, weight = FontWeight.SemiBold, translate = false)
                            KText("${preview.service.label} · ${AppText.t("${preview.songs.size} titres")}", Ket27Desk.Type.footnote, color = ket.secondary, translate = false)
                        }
                    }
                    if (!preview.complete) {
                        Spacer(Modifier.height(8.dp))
                        val total = preview.publishedCount
                        Paragraph(if (total != null && total > preview.songs.size)
                            "Ce service ne montre publiquement que ${preview.songs.size} titres sur $total. Seuls ceux-ci seront importés."
                        else "Ce service ne montre publiquement qu'une partie de cette playlist. Seuls ces titres seront importés.", ket.secondary)
                    }
                    if (state.busy && state.matches.isEmpty() && preview.songs.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        ProgressLine(state.done.toFloat() / preview.songs.size)
                        Spacer(Modifier.height(6.dp))
                        KText("${state.done} / ${preview.songs.size} titres analysés", Ket27Desk.Type.footnote, color = ket.secondary)
                    }
                    if (state.matches.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        KText("${state.found} / ${state.matches.size} titres retrouvés", Ket27Desk.Type.footnote, Modifier.testTag("import-found"), color = ket.secondary)
                        val missing = state.matches.filter { it.track == null }
                        if (missing.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Paragraph("Ils resteront dans la playlist avec leur nom, sans être annoncés comme jouables.", ket.tertiary)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    if (state.busy) Spinner()
                    CapsuleButton("Annuler", null, onDismiss, filled = false, accent = ket.accent, modifier = Modifier.testTag("import-cancel"))
                    val label = when {
                        preview == null -> "Charger l'aperçu"
                        state.matches.isEmpty() -> "Retrouver les titres dans Ketvoice"
                        else -> "Créer avec les titres affichés"
                    }
                    CapsuleButton(label, null, { if (!state.busy && state.link.isNotBlank()) primary() }, filled = true,
                        accent = if (state.busy || state.link.isBlank()) ket.accent.copy(alpha = .45f) else ket.accent, modifier = Modifier.testTag("import-go"))
                }
            }
        }
    }
}

@Composable
private fun Paragraph(text: String, color: Color, modifier: Modifier = Modifier) =
    androidx.compose.material3.Text(AppText.t(text), modifier, color = color,
        style = androidx.compose.material3.LocalTextStyle.current.merge(Ket27Desk.Type.footnote))

/** Ket 27's thin progress bar. */
@Composable
private fun ProgressLine(fraction: Float) {
    val ket = LocalKet.current
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(ket.fill)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(ket.accent))
    }
}
