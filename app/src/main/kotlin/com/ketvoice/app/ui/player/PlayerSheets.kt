package com.ketvoice.app.ui.player

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Wifi
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.Track
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.SleepTimer
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.T
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.EBadge

private val noRip: MutableInteractionSource = MutableInteractionSource()

/**
 * Sheets du lecteur (captures 19-20) — ONLY des actions réelles :
 *  - TrackMenuSheet : Lire ensuite (file réelle) + Télécharger (vrai fichier) ;
 *    les fausses options (Rétablir l'original, Convertir en vidéo…) ont été retirées.
 *  - AudioOutputSheet : sortie réelle (appareil + volume) —opaque, en français.
 */
@Composable
fun TrackMenuSheet(track: Track, onClose: () -> Unit) {
    val art by ArtworkRepo.covers.collectAsState()
    val cover = art["t:${track.id}"] ?: track.coverUrl
    val radius = with(LocalDensity.current) { 28.dp.toPx() }
    GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(10.dp),
        shape = RoundedCornerShape(28.dp),
        params = GlassParams.sheet(radius, dark = true)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 650.dp)
                .verticalScroll(rememberScrollState())
                .clickable(noRip, null) { }
        ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .width(44.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.35f))
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Cover(track.title, cover, size = 56.dp, radius = 8.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (track.explicit) {
                        EBadge()
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        track.title,
                        localize = false,
                        color = Color.White,
                        style = T.bodyBold,
                        fontSize = 17.sp,
                        maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                Text(
                    track.artist,
                    localize = false,
                    color = Color.White.copy(alpha = 0.78f),
                    style = T.sub,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Filled.Close, null, tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.clickable(noRip, null) { onClose() }
            )
        }

        MenuItemRow("Lire ensuite", Icons.Filled.PlaylistAdd) {
            Player.playNext(track)
            onClose()
        }
        MenuItemRow("Ajouter à une playlist", Icons.Filled.PlaylistAdd) {
            onClose(); com.ketvoice.app.ui.AppUi.addToPlaylist.value = track
        }
        val downloaded = Downloader.itemFor(track.id) != null
        MenuItemRow(
            if (downloaded) "Téléchargé ✓" else "Télécharger",
            Icons.Filled.Download
        ) {
            if (!downloaded) Downloader.download(track)
            onClose()
        }
        val publisher by com.ketvoice.app.data.PublisherSession.allowed.collectAsState()
        if (publisher && com.ketvoice.app.data.ImportedSong.id(track) != null) MenuItemRow("Modifier l'audio ou la pochette", Icons.Filled.Download) {
            onClose()
            com.ketvoice.app.ui.AppUi.openPublisher(track, edit = true)
        }
        if (publisher) MenuItemRow("Importer des lyrics", Icons.Filled.PlaylistAdd) {
            onClose()
            com.ketvoice.app.ui.AppUi.openPublisher(track)
        }
        if (com.ketvoice.app.BuildConfig.IS_QUEST) {
            val remaining by SleepTimer.remainingSeconds.collectAsState()
            var timerOpen by remember { mutableStateOf(false) }
            MenuItemRow(if (remaining > 0) "Pause dans ${(remaining + 59) / 60} min" else "Minuterie",
                Icons.Filled.Bedtime) { timerOpen = !timerOpen }
            if (timerOpen) listOf(15, 30, 60, 0).forEach { minutes ->
                MenuItemRow(if (minutes == 0) "Annuler la minuterie" else "Pause dans $minutes min", Icons.Filled.Bedtime) {
                    SleepTimer.set(minutes)
                    onClose()
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun MenuItemRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(noRip, null) { onClick() }
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(25.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, color = Color.White, fontSize = 17.sp)
    }
}

@Composable
fun AudioOutputSheet(onClose: () -> Unit) {
    val radius = with(LocalDensity.current) { 26.dp.toPx() }
    GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(10.dp),
        shape = RoundedCornerShape(26.dp),
        params = GlassParams.sheet(radius, dark = true)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(noRip, null) { }
        ) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .width(44.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.35f))
            )
        }
        Text(
            "Sortie audio",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
        )

        OutputCard(
            title = if (com.ketvoice.app.BuildConfig.IS_QUEST) "Ce casque" else "Ce téléphone",
            sub = "Haut-parleur, écouteurs ou Bluetooth",
            icon = Icons.Filled.Smartphone,
            trailing = { Icon(Icons.Filled.Check, null, tint = LocalTheme.current.accent) },
            onClick = onClose
        )
        // Réglage du volume réel (rail custom, pas de Slider natif)
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 7.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF2C2C2E))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            val vol = remember { mutableFloatStateOf(Player.volumeValue()) }
            VolumeRail(
                value = vol.floatValue,
                modifier = Modifier.fillMaxWidth()
            ) {
                vol.floatValue = it
                Player.setVolume(it)
            }
        }
        Text(
            "Le volume règle la lecture de Ketvoice sur cet appareil.",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
        )
        MenuItemRow("Égaliseur", Icons.Filled.Equalizer) {
            onClose();com.ketvoice.app.ui.AppUi.showEqualizer.value=true
        }
        MenuItemRow("Transférer sur le même Wi-Fi", Icons.Filled.Wifi) {
            onClose();com.ketvoice.app.ui.AppUi.showHandoff.value=true
        }
        Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun OutputCard(
    title: String,
    sub: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    trailing: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 7.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF2C2C2E))
            .clickable(noRip, null) { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(21.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp)
        }
        trailing()
    }
}
