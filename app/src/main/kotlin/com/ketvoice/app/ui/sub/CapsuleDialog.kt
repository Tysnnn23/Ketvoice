package com.ketvoice.app.ui.sub

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.TrackTitle

@Composable
fun CapsuleDialog(onClose: () -> Unit) {
    val theme = LocalTheme.current
    val history by ListeningHistory.tracks.collectAsState()
    val known by ListeningHistory.knownTracks.collectAsState()
    val downloaded by Downloader.items.collectAsState()
    val shelves by HomeFeed.shelves.collectAsState()
    val capsule by OfflineCapsule.state.collectAsState()
    var minutes by remember { mutableIntStateOf(60) }
    var megabytes by remember { mutableIntStateOf(256) }
    var discoveries by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<List<Track>>(emptyList()) }
    var excluded by remember { mutableStateOf(emptySet<String>()) }
    val selected = preview.filterNot { it.id in excluded }
    val ready = OfflineCapsule.readyTracks()
    val readyItems = downloaded.filter { it.track.id in ready.map { t -> t.id }.toSet() }
    AlertDialog(onDismissRequest = onClose, title = { Text("Capsule hors ligne") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .60f).dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Préparez une réserve pour votre trajet. Favoris en priorité, puis historique et sélection choisie. Aucun fichier existant n'est supprimé ou remplacé.", color = theme.secondary)
            CapsuleChoice("Durée souhaitée", listOf(30, 60, 120, 180).map { "$it min" }, "$minutes min", !capsule.busy) { minutes = listOf(30,60,120,180)[it]; preview = emptyList() }
            CapsuleChoice("Budget des fichiers", listOf(128,256,512,1024).map { "$it Mo" }, "$megabytes Mo", !capsule.busy) { megabytes = listOf(128,256,512,1024)[it]; preview = emptyList() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(discoveries, { discoveries = it; preview = emptyList() }, enabled = !capsule.busy)
                Text("Inclure la sélection d'accueil", color = theme.onBackground)
            }
            Text("Durée et taille de la sélection sont estimées. Le budget est contrôlé sur les fichiers réellement écrits, hors cache partagé. Pochettes et paroles sont conservées si disponibles et si elles tiennent dans le budget. Les vidéos Canvas ne sont pas ajoutées à la capsule.", fontSize = 12.sp, color = theme.secondary)
            if (!capsule.busy) TextButton(onClick = {
                preview = CapsulePlan.choose(history + known + Player.state.value.queue + downloaded.map { it.track } +
                    (if (discoveries) shelves.flatMap { it.tracks } else emptyList()), AppSettings.favoriteIds.value,
                    minutes * 60_000L, megabytes * 1_048_576L, downloaded.associate { it.track.id to Downloader.storedBytes(it) })
                excluded = emptySet()
            }) { Text("Voir la sélection", color = theme.accent) }
            if (preview.isNotEmpty()) {
                Text("${selected.size} titres sélectionnés", color = theme.accent)
                preview.forEach { track ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(track.id !in excluded, { checked -> excluded = if (checked) excluded - track.id else excluded + track.id }, enabled = !capsule.busy)
                        Column(Modifier.weight(1f)) { TrackTitle(track.title, theme.onBackground, fontSize = 14.sp)
                            Text(track.artist, localize = false, color = theme.secondary, fontSize = 12.sp) }
                    }
                }
            } else if (!capsule.busy && history.isEmpty() && known.isEmpty()) Text("Écoutez ou recherchez quelques titres, puis créez votre sélection.", color = theme.secondary)
            if (capsule.tracks.isNotEmpty()) {
                Text("${ready.size} / ${capsule.tracks.size} audios prêts", color = theme.accent)
                Text("${readyItems.count { it.hasLyrics }} paroles · ${readyItems.count { it.track.coverUrl?.startsWith("file:") == true }} pochettes enregistrées", color = theme.secondary)
                if (capsule.busy) {
                    LinearProgressIndicator(progress = { capsule.attempted.toFloat() / capsule.tracks.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                    Text("Gardez Ketvoice ouvert pendant la préparation.", color = theme.secondary)
                }
                if (capsule.message.isNotBlank()) Text(capsule.message, color = theme.secondary)
                if (ready.isNotEmpty()) TextButton(onClick = { Player.playQueue(ready); onClose() }) { Text("Écouter la capsule", color = theme.accent) }
            }
        } }, confirmButton = {
            if (!capsule.busy) TextButton(enabled = selected.isNotEmpty(), onClick = {
                OfflineCapsule.prepare(selected, minutes, megabytes * 1_048_576L)
            }) { Text(if (capsule.tracks.isEmpty()) "Préparer la capsule" else "Remplacer la sélection", color = theme.accent) }
        }, dismissButton = {
            Row { if (capsule.busy) TextButton(onClick = OfflineCapsule::cancel) { Text("Arrêter") }
                TextButton(onClick = onClose) { Text("Fermer") } }
        })
}

@Composable
private fun CapsuleChoice(label: String, options: List<String>, value: String, enabled: Boolean, onChoose: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label)
        Box { TextButton(enabled = enabled, onClick = { open = true }) { Text(value) }
            DropdownMenu(open, { open = false }) { options.forEachIndexed { index, option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { open = false; onChoose(index) })
            } }
        }
    }
}
