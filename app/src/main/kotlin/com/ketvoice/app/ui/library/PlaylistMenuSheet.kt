package com.ketvoice.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
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
    onDelete: () -> Unit,
    onShare: () -> Unit = {},
    onMembers: () -> Unit = {},
    onTogglePublic: () -> Unit = {},
    onReorder: (() -> Unit)? = null
) {
    val shared = com.ketvoice.app.data.PlaylistRules.isShared(playlist.id)
    val owner = !shared || com.ketvoice.app.sync.SharedPlaylists.isOwner(playlist.id)
    val published = playlist.id in com.ketvoice.app.account.PublicProfiles.published.collectAsState().value
    val playable = playlist.entries.mapNotNull { it.track }
    val radius = with(LocalDensity.current) { 28.dp.toPx() }
    // Rendue au niveau racine (AppRoot), hors de la couche capturée : le verre est
    // donc DYNAMIQUE — il échantillonne réellement le contenu derrière la feuille.
    GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(10.dp),
        shape = RoundedCornerShape(28.dp),
        params = GlassParams.sheet(radius, dark = com.ketvoice.app.ui.common.sheetDark())
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = com.ketvoice.app.ui.common.sheetMaxHeight())
            .verticalScroll(rememberScrollState()).pointerInput(Unit) { detectTapGestures {} }) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(44.dp).height(5.dp).clip(RoundedCornerShape(3.dp))
                    .background(com.ketvoice.app.ui.common.sheetInk().copy(alpha = .35f)))
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                PlaylistArtwork(playlist, playlist.name, Modifier.size(56.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, localize = false, color = com.ketvoice.app.ui.common.sheetInk(), fontSize = 17.sp,
                        fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("${playable.size} / ${playlist.entries.size} titres disponibles",
                        color = com.ketvoice.app.ui.common.sheetInk().copy(alpha = .78f), fontSize = 13.sp)
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(50)).clickable(noRip, null) { onClose() },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, "Fermer", tint = com.ketvoice.app.ui.common.sheetInk().copy(alpha = .7f), modifier = Modifier.size(20.dp))
                }
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
            // 6.3: shared playlists (several accounts edit together) and the public profile.
            if (owner) PlaylistMenuRow("Partager la playlist", Icons.Filled.IosShare) { onShare(); onClose() }
            if (shared) PlaylistMenuRow("Membres", Icons.Filled.Group) { onMembers(); onClose() }
            if (!shared) PlaylistMenuRow(if (published) "Retirer cette playlist de mon profil" else "Rendre cette playlist publique",
                Icons.Filled.Public) { onTogglePublic(); onClose() }
            if (owner) PlaylistMenuRow("Renommer", Icons.Filled.DriveFileRenameOutline) { onRename(); onClose() }
            if (onReorder != null) PlaylistMenuRow("Modifier l'ordre", Icons.Filled.SwapVert) { onReorder(); onClose() }
            PlaylistMenuRow("Exporter (.json)", Icons.Filled.UploadFile) { onExportJson(); onClose() }
            PlaylistMenuRow("Exporter (.m3u)", Icons.Filled.IosShare) { onExportM3u(); onClose() }
            PlaylistMenuRow(if (shared && !owner) "Quitter la playlist" else "Supprimer la playlist", Icons.Filled.Delete,
                tint = Color(0xFFFF6B6B)) { onDelete(); onClose() }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun PlaylistMenuRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color = com.ketvoice.app.ui.common.sheetInk(),
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(Modifier.fillMaxWidth()
        .clickable(noRip, null, enabled = enabled) { onClick() }
        .heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (enabled) tint else com.ketvoice.app.ui.common.sheetInk().copy(alpha = .4f), modifier = Modifier.size(25.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, color = if (enabled) tint else com.ketvoice.app.ui.common.sheetInk().copy(alpha = .4f), fontSize = 17.sp)
    }
}
