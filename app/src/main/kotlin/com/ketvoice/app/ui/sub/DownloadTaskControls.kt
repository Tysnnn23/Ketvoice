package com.ketvoice.app.ui.sub

import androidx.compose.foundation.layout.*
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.*
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Text

@Composable
internal fun DownloadTaskControls(track: Track, task: DownloadTask?, busy: Boolean) {
    if (task == null || task.phase == DownloadPhase.Complete) return
    val theme = LocalTheme.current
    val label = when (task.phase) {
        DownloadPhase.Paused -> "En pause"
        DownloadPhase.WaitingWifi -> "En attente du Wi-Fi"
        DownloadPhase.Failed -> "Échec du téléchargement"
        DownloadPhase.Cancelled -> "Téléchargement annulé"
        DownloadPhase.Resolving -> "Recherche du flux…"
        DownloadPhase.Finalizing -> "Finalisation…"
        else -> "Téléchargement en cours"
    }
    Text(label, color = theme.secondary, fontSize = 12.sp)
    if (task.phase in setOf(DownloadPhase.Transferring, DownloadPhase.Paused, DownloadPhase.WaitingWifi)) {
        task.total?.takeIf { it > 0 }?.let { total ->
            LinearProgressIndicator(progress = { (task.bytes.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), color = theme.accent,
                trackColor = theme.hairline)
            task.remaining?.let { Text("${it / 1024} Ko restants", color = theme.secondary, fontSize = 11.sp) }
        } ?: if (task.phase == DownloadPhase.Transferring) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                color = theme.accent, trackColor = theme.hairline)
        } else Unit
    }
    if (task.restarted) Text("Source sans reprise : transfert redémarré", color = theme.secondary, fontSize = 11.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (busy || task.phase == DownloadPhase.Paused || task.phase == DownloadPhase.WaitingWifi) {
            if (task.phase != DownloadPhase.Finalizing) TextButton(onClick = {
                if (task.phase == DownloadPhase.Paused) Downloader.resume(track.id) else Downloader.pause(track.id)
            }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (task.phase == DownloadPhase.Paused) "Reprendre" else "Pause", color = theme.accent)
            }
            TextButton(onClick = { Downloader.cancel(track.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Annuler", color = theme.accent)
            }
        } else if (task.phase == DownloadPhase.Failed || task.phase == DownloadPhase.Cancelled) {
            TextButton(onClick = { Downloader.download(track) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Réessayer", color = theme.accent) }
            TextButton(onClick = { Downloader.cancel(track.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Annuler", color = theme.secondary) }
        }
    }
}
