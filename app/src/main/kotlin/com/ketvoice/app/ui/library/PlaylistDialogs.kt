package com.ketvoice.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.*
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.AppAlertDialog
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.*

private fun userError(failure: Exception): String = if (failure is IllegalArgumentException || failure is IllegalStateException)
    failure.message ?: "Impossible d'enregistrer la playlist" else "Impossible d'enregistrer la playlist"

@Composable
fun PlaylistDialogs() {
    if (AppUi.showCreatePlaylist.value) CreatePlaylistDialog()
    if (AppUi.showSpotifyImport.value) SpotifyImportDialog { AppUi.showSpotifyImport.value = false }
}

@Composable
private fun CreatePlaylistDialog() {
    val th = LocalTheme.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    AppAlertDialog(onDismissRequest = { if (!saving) AppUi.showCreatePlaylist.value = false },
        title = { Text("Créer une playlist") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Nom de la playlist") }, singleLine = true,
                enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("playlist-name"))
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        } }, confirmButton = { TextButton(enabled = !saving && name.isNotBlank(), onClick = {
            saving = true; error = ""
            scope.launch {
                try {
                    val entries = AppUi.addToPlaylist.value?.let { listOf(PlaylistRules.entry(it)) }.orEmpty()
                    val id = PlaylistStore.create(name,entries)
                    AppUi.addToPlaylist.value = null; AppUi.showCreatePlaylist.value = false; AppUi.openPlaylist(id)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = userError(failure) }
                finally { saving = false }
            }
        }, modifier = Modifier.testTag("create-confirm")) { Text("Créer", color = th.accent) } },
        dismissButton = { TextButton(enabled = !saving, onClick = { AppUi.showCreatePlaylist.value = false }) { Text("Annuler") } })
}

/**
 * Feuille « Ajouter à une playlist » : verre liquide, liste des playlists avec
 * leurs vraies couvertures (mosaïque pour les imports Spotify), création rapide.
 * Rendue par AppRoot au-dessus du chrome flottant.
 */
@Composable
fun AddToPlaylistSheet(track: Track, onClose: () -> Unit) {
    val rows by PlaylistStore.lists.collectAsState()
    val scope = rememberCoroutineScope()
    var savingId by remember { mutableStateOf<String?>(null) }
    val radius = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.toPx() }
    GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(10.dp),
        shape = RoundedCornerShape(28.dp),
        params = GlassParams.sheet(radius, dark = true)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null) { }
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(44.dp).height(5.dp).clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = .35f)))
            }
            Text("Ajouter à une playlist", color = Color.White, fontSize = 21.sp,
                fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            if (rows.isEmpty()) {
                Text("Créez votre première playlist pour y ajouter ce titre.",
                    color = Color.White.copy(alpha = .6f), fontSize = 14.sp,
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
                                        try { PlaylistStore.add(list.id, track); onClose() }
                                        finally { savingId = null }
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PlaylistArtwork(list, list.name, Modifier.size(52.dp))
                            Spacer(Modifier.width(13.dp))
                            Column(Modifier.weight(1f)) {
                                Text(list.name, localize = false, color = Color.White, fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                val available = list.entries.count { it.track != null }
                                Text("$available / ${list.entries.size} titres disponibles",
                                    color = Color.White.copy(alpha = .6f), fontSize = 12.5.sp)
                            }
                            if (saving) CircularProgressIndicator(
                                Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = .12f))
                    .clickable { AppUi.showCreatePlaylist.value = true }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text("Créer une playlist", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
fun SpotifyImportDialog(onClose: () -> Unit) {
    val th = LocalTheme.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<SpotifyPreview?>(null) }
    var entries by remember { mutableStateOf<List<PlaylistEntry>>(emptyList()) }
    var progress by remember { mutableIntStateOf(0) }
    var acknowledged by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var operation by remember { mutableStateOf<Job?>(null) }
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy=true;error=""
        operation=scope.launch { try { block() } catch(cancelled:CancellationException){throw cancelled}
            catch(failure:Exception){error=userError(failure)} finally{busy=false} }
    }
    AppAlertDialog(onDismissRequest={operation?.cancel();onClose()}, title={Text("Importer depuis Spotify")},
        text={Column(Modifier.fillMaxWidth().heightIn(max=420.dp)
            .verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            PlaylistArtwork(null, "Spotify", Modifier.size(100.dp))
            Text("Import par lien public, sans connexion Spotify. Aucun fichier audio Spotify n'est téléchargé.",color=th.secondary)
            OutlinedTextField(link,{link=it.take(2000);preview=null;entries=emptyList();acknowledged=false},
                label={Text("Lien public Spotify")},enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth().testTag("spotify-link"))
            TextButton(enabled=!busy&&link.isNotBlank(),onClick={run{preview=SpotifyPlaylistImport.preview(link);entries=emptyList();progress=0}}){Text("Charger l'aperçu",color=th.accent)}
            preview?.let { source ->
                Text(source.name,localize=false,color=th.onBackground)
                Text("${source.entries.size} titres visibles dans l'aperçu public",color=th.secondary)
                Text("Spotify ne garantit pas que cet aperçu contient toute la playlist. Les titres privés ou absents ne sont pas inventés.",color=th.secondary)
                Row(verticalAlignment=Alignment.CenterVertically){Checkbox(acknowledged,{acknowledged=it},enabled=!busy)
                    Text("J'accepte d'importer cet aperçu, qui peut être incomplet",modifier=Modifier.weight(1f))}
                TextButton(enabled=!busy,onClick={run{entries=SpotifyPlaylistImport.resolve(source){progress=it}}}){Text("Retrouver les titres dans Ketvoice",color=th.accent)}
                if(busy)Text("$progress / ${source.entries.size} recherches terminées",color=th.secondary)
                if(entries.isNotEmpty()) {
                    Text("${entries.count{it.track!=null}} / ${entries.size} titres disponibles",color=th.accent)
                    Text("Les titres non retrouvés resteront visibles, mais ne seront pas annoncés comme jouables.",color=th.secondary)
                    entries.take(8).forEach { item ->
                        Column { Text(item.title,localize=false,color=th.onBackground,fontSize=14.sp)
                            Text(if(item.track!=null) "Disponible" else "Non retrouvé",color=th.secondary,fontSize=12.sp) }
                    }
                }
            }
            if(busy)LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        }}, confirmButton={TextButton(enabled=!busy&&acknowledged&&entries.isNotEmpty(),onClick={run{
            val source=preview ?: return@run
            val id=PlaylistStore.create(source.name,entries,source.url,source.cover)
            onClose();AppUi.openPlaylist(id)
        }}){Text("Créer avec les titres affichés",color=th.accent)}},
        dismissButton={TextButton(onClick={operation?.cancel();onClose()}){Text(if(busy)"Annuler" else "Fermer")}})
}
