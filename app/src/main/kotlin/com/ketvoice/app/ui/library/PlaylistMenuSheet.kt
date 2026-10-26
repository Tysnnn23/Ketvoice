package com.ketvoice.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text

private val noRip = MutableInteractionSource()

/** Feuille d'actions d'une playlist, dans le style du menu du lecteur. */
@Composable
fun PlaylistMenuSheet(
    playlist: LocalPlaylist,
    onClose: () -> Unit,
    onDownload: () -> Unit,
    onPlayNext: () -> Unit,
    onRename: () -> Unit,
    onExportJson: () -> Unit,
    onExportM3u: () -> Unit,
    onDelete: () -> Unit
) {
    val playable = playlist.entries.mapNotNull { it.track }
    val radius = with(LocalDensity.current) { 28.dp.toPx() }
    // Posée dans le contenu capturé par le backdrop : verre statique, sinon
    // la boucle drawBackdrop fait planter le RenderThread.
    GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(10.dp),
        shape = RoundedCornerShape(28.dp),
        params = GlassParams.sheet(radius, dark = true),
        captured = true
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp)
            .verticalScroll(rememberScrollState()).clickable(noRip, null) { }) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(44.dp).height(5.dp).clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = .35f)))
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                PlaylistArtwork(playlist, playlist.name, Modifier.size(56.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, localize = false, color = Color.White, fontSize = 17.sp,
                        fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("${playable.size} / ${playlist.entries.size} titres disponibles",
                        color = Color.White.copy(alpha = .78f), fontSize = 13.sp)
                }
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Filled.Close, null, tint = Color.White.copy(alpha = .7f),
                    modifier = Modifier.clickable(noRip, null) { onClose() })
            }
            if (playable.isNotEmpty()) {
                PlaylistMenuRow("Tout lire ensuite", Icons.Filled.PlaylistAdd) { onPlayNext(); onClose() }
                val pending = playable.count { Downloader.itemFor(it.id) == null }
                PlaylistMenuRow(
                    if (pending == 0) "Tous les titres sont téléchargés ✓" else "Télécharger la playlist ($pending)",
                    Icons.Filled.Download,
                    enabled = pending > 0
                ) { onDownload(); onClose() }
            }
            PlaylistMenuRow("Renommer", Icons.Filled.DriveFileRenameOutline) { onRename(); onClose() }
            PlaylistMenuRow("Exporter (.json)", Icons.Filled.UploadFile) { onExportJson(); onClose() }
            PlaylistMenuRow("Exporter (.m3u)", Icons.Filled.IosShare) { onExportM3u(); onClose() }
            PlaylistMenuRow("Supprimer la playlist", Icons.Filled.Delete,
                tint = Color(0xFFFF6B6B)) { onDelete(); onClose() }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun PlaylistMenuRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color = Color.White,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(Modifier.fillMaxWidth()
        .clickable(noRip, null, enabled = enabled) { onClick() }
        .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (enabled) tint else Color.White.copy(alpha = .4f), modifier = Modifier.size(25.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, color = if (enabled) tint else Color.White.copy(alpha = .4f), fontSize = 17.sp)
    }
}
