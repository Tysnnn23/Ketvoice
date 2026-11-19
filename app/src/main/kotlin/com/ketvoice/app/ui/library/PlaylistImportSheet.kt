package com.ketvoice.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.PlaylistStore
import com.ketvoice.app.smartlib.ImportMatch
import com.ketvoice.app.smartlib.ImportPreview
import com.ketvoice.app.smartlib.ImportService
import com.ketvoice.app.smartlib.PlaylistImport
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.KetProgressBar
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.settings.SettingsAction
import com.ketvoice.app.ui.settings.SettingsSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Where an import stands; kept apart from the sheet so that each step can be drawn on its own. */
class PlaylistImportState {
    var link by mutableStateOf("")
    var preview by mutableStateOf<ImportPreview?>(null)
    var matches by mutableStateOf<List<ImportMatch>>(emptyList())
    var done by mutableIntStateOf(0)
    var acknowledged by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf("")
    internal var job: Job? = null

    val total: Int get() = preview?.songs?.size ?: 0
    val found: Int get() = matches.count { it.track != null }
    /** A Spotify preview may be partial: the listener says so before it is imported. */
    val canCreate: Boolean get() = !busy && matches.isNotEmpty() && (preview?.complete == true || acknowledged)

    fun edit(value: String) { link = value.take(2000); preview = null; matches = emptyList(); acknowledged = false; done = 0; error = "" }

    fun run(scope: CoroutineScope, block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = ""
        job = scope.launch {
            try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = message(failure) } finally { busy = false }
        }
    }

    private fun message(failure: Exception): String = if (failure is IllegalArgumentException || failure is IllegalStateException)
        failure.message ?: "Import impossible. Réessayez plus tard." else "Import impossible. Vérifiez votre connexion."
}

/**
 * « Importer des playlists »: a public Spotify, Deezer, Apple Music, Tidal or SoundCloud playlist, by its
 * link, without an account or a key. Its titles are looked for in Ketvoice's catalogue with a progress bar;
 * those not found are listed and stay in the new playlist as names, never announced as playable.
 */
@Composable
fun PlaylistImportSheet(onClose: () -> Unit, state: PlaylistImportState = remember { PlaylistImportState() }) {
    val theme = LocalTheme.current
    SettingsSheet(onDismiss = { state.job?.cancel(); onClose() }) {
        Column(Modifier.fillMaxWidth().heightIn(max = com.ketvoice.app.ui.common.sheetMaxHeight())
            .verticalScroll(rememberScrollState()).padding(20.dp).testTag("playlist-import"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Importer des playlists", color = theme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Par lien public, sans compte ni clé : Ketvoice ne garde aucun accès à ces services.", color = theme.secondary)
            ImportSources(state.link)
            ImportLinkStep(state)
            state.preview?.let { ImportPreviewStep(state, it) }
            if (state.busy) KetProgressBar(if (state.total > 0 && state.done > 0) ({ state.done.toFloat() / state.total }) else null,
                Modifier.fillMaxWidth().testTag("playlist-import-progress"))
            if (state.error.isNotBlank()) Text(state.error, color = com.ketvoice.app.ui.common.DESTRUCTIVE)
            if (state.matches.isNotEmpty()) ImportResultStep(state)
            ImportActions(state, onClose)
        }
    }
}

/** The five services, names only (no brand logos); the one the typed link belongs to is marked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportSources(link: String) {
    val theme = LocalTheme.current
    val current = remember(link) { runCatching { PlaylistImport.serviceOf(link) }.getOrNull() }
    FlowRow(Modifier.fillMaxWidth().testTag("playlist-import-sources"), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ImportService.entries.forEach { service ->
            val on = service == current
            Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) theme.accent.copy(alpha = .16f) else theme.onBackground.copy(alpha = .06f))
                .padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(service.label, localize = false, color = if (on) theme.accent else theme.onBackground, fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ImportLinkStep(state: PlaylistImportState) {
    val scope = rememberCoroutineScope()
    SettingsTextInput(state.link, state::edit, "Lien de la playlist", !state.busy, Modifier.testTag("playlist-import-link"))
    SettingsAction("Charger l'aperçu", { state.run(scope) { state.preview = PlaylistImport.preview(state.link); state.matches = emptyList(); state.done = 0 } },
        enabled = !state.busy && state.link.isNotBlank(), primary = state.preview == null)
}

@Composable
private fun ImportPreviewStep(state: PlaylistImportState, source: ImportPreview) {
    val theme = LocalTheme.current
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("playlist-import-preview")) {
        Cover(source.name, source.cover, 72.dp, 12.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(source.name, localize = false, color = theme.onBackground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(AppText.t("${source.songs.size} titres") + " · " + source.service.label, localize = false, color = theme.secondary, fontSize = 13.sp)
        }
    }
    if (!source.complete) PartialNotice(state, source)
    if (state.matches.isEmpty()) SettingsAction("Retrouver les titres dans Ketvoice", {
        state.run(scope) { state.matches = PlaylistImport.resolve(source, MusicApi::searchAll) { done, _ -> state.done = done } }
    }, enabled = !state.busy, primary = true)
    if (state.busy && state.total > 0) Text(AppText.t("${state.done} / ${state.total} titres analysés"), localize = false,
        color = theme.secondary, fontSize = 13.sp, modifier = Modifier.testTag("playlist-import-count"))
}

@Composable
private fun PartialNotice(state: PlaylistImportState, source: ImportPreview) {
    val theme = LocalTheme.current
    val total = source.publishedCount
    if (total != null && total > source.songs.size) {
        Text(AppText.t("Ce service ne montre publiquement que ${source.songs.size} titres sur $total. Seuls ceux-ci seront importés."),
            localize = false, color = theme.secondary, modifier = Modifier.testTag("playlist-import-partial"))
    } else if (source.service != ImportService.Spotify) {
        Text("Ce service ne montre publiquement qu'une partie de cette playlist. Seuls ces titres seront importés.", color = theme.secondary)
    } else {
        Text("Spotify ne garantit pas que cet aperçu contient toute la playlist. Les titres privés ou absents ne sont pas inventés.", color = theme.secondary)
    }
    Row(Modifier.fillMaxWidth().toggleable(state.acknowledged, enabled = !state.busy, role = Role.Checkbox) { state.acknowledged = it }
        .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(if (state.acknowledged) theme.accent else theme.onBackground.copy(alpha = .12f)),
            contentAlignment = Alignment.Center) { if (state.acknowledged) Text("✓", localize = false, color = Color.White, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(10.dp))
        Text("J'accepte d'importer cet aperçu, qui peut être incomplet", color = theme.onBackground, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ImportResultStep(state: PlaylistImportState) {
    val theme = LocalTheme.current
    val missing = state.matches.filter { it.track == null }
    Text(AppText.t("${state.found} / ${state.matches.size} titres retrouvés"), localize = false, color = theme.accent,
        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("playlist-import-found"))
    if (missing.isEmpty()) return
    Text("Titres non retrouvés", color = theme.onBackground, fontWeight = FontWeight.SemiBold)
    Text("Ils resteront dans la playlist avec leur nom, sans être annoncés comme jouables.", color = theme.secondary, fontSize = 13.sp)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(theme.onBackground.copy(alpha = .06f)).padding(12.dp)
        .testTag("playlist-import-missing"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        missing.take(30).forEach { match ->
            Text("${match.song.title} · ${match.song.artist}", localize = false, color = theme.onBackground, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (missing.size > 30) Text(AppText.t("et ${missing.size - 30} autres"), localize = false, color = theme.secondary, fontSize = 13.sp)
    }
}

@Composable
private fun ImportActions(state: PlaylistImportState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        SettingsAction("Créer avec les titres affichés", { state.run(scope) {
            val source = state.preview ?: return@run
            val id = PlaylistStore.create(source.name, PlaylistImport.entries(state.matches), source.url.takeIf { source.service == ImportService.Spotify }, source.cover)
            onClose(); AppUi.openPlaylist(id)
        } }, enabled = state.canCreate, primary = true)
        SettingsAction(if (state.busy) "Annuler" else "Fermer", { state.job?.cancel(); onClose() })
    }
}
