package com.ketvoice.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.*
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.settings.SettingsSheet
import com.ketvoice.app.ui.settings.SettingsAction
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.*

private fun userError(failure: Exception): String = if (failure is IllegalArgumentException || failure is IllegalStateException)
    failure.message ?: "Impossible d'enregistrer la playlist" else "Impossible d'enregistrer la playlist"

@Composable
fun PlaylistDialogs() {
    if (AppUi.showCreatePlaylist.value) CreatePlaylistDialog()
    // « Importer des playlists » (Spotify and Deezer links), see AppUi.openPlaylistImport.
    if (AppUi.showSpotifyImport.value) PlaylistImportSheet({ AppUi.showSpotifyImport.value = false })
}

@Composable
private fun CreatePlaylistDialog() {
    val th = LocalTheme.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    SettingsSheet(onDismiss = { if (!saving) AppUi.showCreatePlaylist.value = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max = com.ketvoice.app.ui.common.sheetMaxHeight()).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Créer une playlist", color = th.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            SettingsTextInput(name, { name = it.take(80) }, "Nom de la playlist", !saving, Modifier.testTag("playlist-name"))
            if (error.isNotBlank()) Text(error, color = com.ketvoice.app.ui.common.DESTRUCTIVE)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SettingsAction("Annuler", { AppUi.showCreatePlaylist.value = false }, enabled = !saving)
                SettingsAction("Créer", {
                    saving = true; error = ""
                    scope.launch {
                        try {
                            val entries = AppUi.pendingPlaylistTracks().orEmpty().distinctBy { it.id }.map { PlaylistRules.entry(it) }
                            val id = PlaylistStore.create(name,entries)
                            AppUi.clearPendingPlaylistTracks(); AppUi.showCreatePlaylist.value = false; AppUi.openPlaylist(id)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = userError(failure) }
                        finally { saving = false }
                    }
                }, enabled = !saving && name.isNotBlank(), primary = true, modifier = Modifier.testTag("create-confirm"))
            }
        }
    }
}

@Composable
internal fun SettingsTextInput(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean,
                              modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val accessible = AppText.t(label)
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = theme.secondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        BasicTextField(value, onChange, enabled = enabled, singleLine = true,
            textStyle = TextStyle(color = theme.onBackground, fontSize = 16.sp), cursorBrush = SolidColor(theme.accent),
            modifier = modifier.fillMaxWidth().semantics { contentDescription = accessible }.clip(RoundedCornerShape(12.dp))
                .background(theme.onBackground.copy(alpha = .07f)).heightIn(min = 48.dp).padding(12.dp))
    }
}

/**
 * Feuille « Ajouter à une playlist » : verre liquide, liste des playlists avec
 * leurs vraies couvertures (mosaïque pour les imports Spotify), création rapide.
 * Rendue par AppRoot au-dessus du chrome flottant.
 */
@Composable
fun AddToPlaylistSheet(tracks: List<Track>, onClose: () -> Unit) {
    val rows by PlaylistStore.lists.collectAsState()
    val scope = rememberCoroutineScope()
    var savingId by remember { mutableStateOf<String?>(null) }
    val radius = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.toPx() }
    GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(10.dp).then(com.ketvoice.app.ui.common.sheetEdge(RoundedCornerShape(28.dp))),
        shape = RoundedCornerShape(28.dp),
        params = GlassParams.sheet(radius, dark = com.ketvoice.app.ui.common.sheetDark())
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null) { }
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(44.dp).height(5.dp).clip(RoundedCornerShape(3.dp))
                    .background(com.ketvoice.app.ui.common.sheetInk().copy(alpha = .35f)))
            }
            Text("Ajouter à une playlist", color = com.ketvoice.app.ui.common.sheetInk(), fontSize = 21.sp,
                fontWeight = FontWeight.Black, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = if (tracks.size > 1) 2.dp else 12.dp))
            if (tracks.size > 1) Text(AppText.t("{0} titres").replace("{0}", tracks.size.toString()), localize = false,
                color = com.ketvoice.app.ui.common.sheetInk().copy(alpha = .6f), fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, bottom = 10.dp))
            if (rows.isEmpty()) {
                Text("Créez votre première playlist pour y ajouter ce titre.",
                    color = com.ketvoice.app.ui.common.sheetInk().copy(alpha = .6f), fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    items(rows, key = { it.id }) { list ->
                        val saving = savingId == list.id
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = !saving) {
                                    savingId = list.id
                                    scope.launch {
                                        try { PlaylistStore.addAll(list.id, tracks); onClose() }
                                        finally { savingId = null }
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PlaylistArtwork(list, list.name, Modifier.size(52.dp))
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f)) {
                                Text(list.name, localize = false, color = com.ketvoice.app.ui.common.sheetInk(), fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                val available = list.entries.count { it.track != null }
                                Text("$available / ${list.entries.size} titres disponibles",
                                    color = com.ketvoice.app.ui.common.sheetInk().copy(alpha = .6f), fontSize = 12.5.sp)
                            }
                            if (saving) com.ketvoice.app.ui.common.ActivitySpinner(size = 20.dp, color = com.ketvoice.app.ui.common.sheetInk())
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(com.ketvoice.app.ui.common.sheetInk().copy(alpha = .12f))
                    .clickable { AppUi.showCreatePlaylist.value = true }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Add, null, tint = com.ketvoice.app.ui.common.sheetInk(), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text("Créer une playlist", color = com.ketvoice.app.ui.common.sheetInk(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}
