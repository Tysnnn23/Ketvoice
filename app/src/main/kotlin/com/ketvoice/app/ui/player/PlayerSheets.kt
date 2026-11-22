package com.ketvoice.app.ui.player

import kotlinx.coroutines.launch
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Tv
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.AppSettings
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

@Composable
fun TrackMenuSheet(track: Track, onClose: () -> Unit) {
    val art by ArtworkRepo.covers.collectAsState()
    val cover = com.ketvoice.app.data.MediaOverrides.cover(track, art["t:${track.id}"] ?: track.coverUrl)
    val radius = with(LocalDensity.current) { 28.dp.toPx() }
    GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(10.dp),
        shape = RoundedCornerShape(28.dp),
        params = GlassParams.sheet(radius, dark = com.ketvoice.app.ui.common.sheetDark())
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = com.ketvoice.app.ui.common.sheetMaxHeight())
                .verticalScroll(rememberScrollState())
                .pointerInput(Unit) { detectTapGestures {} }
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
                    .background(com.ketvoice.app.ui.common.sheetInk().copy(alpha = 0.35f))
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
                        color = com.ketvoice.app.ui.common.sheetInk(),
                        style = T.bodyBold,
                        fontSize = 17.sp,
                        maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                Text(
                    track.artist,
                    localize = false,
                    color = com.ketvoice.app.ui.common.sheetInk().copy(alpha = 0.78f),
                    style = T.sub,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Filled.Close, "Fermer", tint = com.ketvoice.app.ui.common.sheetInk().copy(alpha = 0.7f),
                modifier = Modifier.size(48.dp).clickable(noRip, null) { onClose() }.padding(12.dp)
            )
        }

        val downloaded = Downloader.itemFor(track.id) != null
        val favorite = track.id in AppSettings.favoriteIds.value
        val shareContext = androidx.compose.ui.platform.LocalContext.current
        // The actions used most, one touch away: round glass buttons above the list.
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            QuickTrackAction(if (favorite) "Favori" else "Favoris", if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                Modifier.weight(1f), active = favorite) {
                AppSettings.favoriteIds.value = if (favorite) AppSettings.favoriteIds.value - track.id else AppSettings.favoriteIds.value + track.id
                com.ketvoice.app.data.SettingsStore.save()
            }
            QuickTrackAction("Lire ensuite", Icons.AutoMirrored.Filled.PlaylistPlay, Modifier.weight(1f)) {
                Player.playNext(track); onClose()
            }
            QuickTrackAction("Ajouter à la file", Icons.Filled.PlaylistAdd, Modifier.weight(1f)) {
                Player.enqueue(listOf(track)); onClose()
            }
            QuickTrackAction(if (downloaded) "Téléchargé ✓" else "Télécharger", if (downloaded) Icons.Filled.Check else Icons.Filled.Download,
                Modifier.weight(1f), active = downloaded) { if (!downloaded) Downloader.download(track); onClose() }
            // Partage réel : lien listen.html — l'app du destinataire ouvre le
            // morceau, un navigateur affiche la page de téléchargement.
            QuickTrackAction("Partager", Icons.Filled.Share, Modifier.weight(1f)) {
                onClose()
                com.ketvoice.app.ui.AppUi.shareCard.value = track
            }
        }
        if (com.ketvoice.app.together.Ketogether.ui.collectAsState().value.active) MenuItemRow("Ajouter à Ketogether", com.ketvoice.app.ui.together.KetogetherIcon) {
            onClose(); com.ketvoice.app.together.Ketogether.add(track)
        }
        MenuItemRow("Ajouter à une playlist", Icons.Filled.PlaylistAdd) {
            onClose(); com.ketvoice.app.ui.AppUi.addToPlaylist.value = track
        }
        MenuItemRow("Lancer la radio", androidx.compose.material.icons.Icons.Filled.Radio) {
            onClose(); Player.startRadio(track)
        }
        MenuItemRow("À propos de ce titre", com.ketvoice.app.ui.common.KetIcons.Info) {
            onClose(); com.ketvoice.app.ui.common.TrackCredits.open.value = track
        }
        // A story card of the line being sung (if this title is playing), else of the title alone.
        val momentScope = androidx.compose.runtime.rememberCoroutineScope()
        val momentAccent = com.ketvoice.app.theme.rememberCoverAccent(cover)?.first
        MenuItemRow("Partager un moment", Icons.Filled.Share) {
            val lines = com.ketvoice.app.util.MomentCard.currentLine(track)
            momentScope.launch {
                runCatching {
                    val file = com.ketvoice.app.util.MomentCard.render(shareContext, track, lines,
                        momentAccent?.let { c -> android.graphics.Color.argb((c.alpha * 255).toInt(), (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()) })
                    com.ketvoice.app.util.MomentCard.share(shareContext, file, track)
                }
                onClose()
            }
        }
        MenuItemRow("Égaliseur", Icons.Filled.Tune) {
            onClose(); com.ketvoice.app.ui.AppUi.showEqualizer.value = true
        }
        MenuItemRow("Éditer les paroles…", com.ketvoice.app.ui.common.KetIcons.Lyrics) {
            onClose(); LyricsEditor.open(track)
        }
        var reverbOpen by remember { mutableStateOf(false) }
        MenuItemRow("Reverb", Icons.Filled.Equalizer) { reverbOpen = !reverbOpen }
        if (reverbOpen) Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            val processing by androidx.compose.runtime.produceState(false) {
                while (true) {
                    value = com.ketvoice.app.player.ReverbControl.processingAudio()
                    kotlinx.coroutines.delay(350)
                }
            }
            Text("Réverbération", color = com.ketvoice.app.ui.common.sheetInk(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Ambiance de salle réglable, traitée dans le flux audio.",
                color = com.ketvoice.app.ui.common.sheetInk().copy(alpha = .72f), fontSize = 12.sp)
            if (AppSettings.reverbAmount.value > 0) Text(
                if (processing) "Traitement audio actif" else "En attente du flux audio",
                color = com.ketvoice.app.ui.common.sheetInk().copy(alpha = .72f), fontSize = 12.sp)
            ReverbSlider("Intensité", AppSettings.reverbAmount.value, { amount ->
                Player.setReverb(amount, AppSettings.reverbSize.value, AppSettings.reverbDamping.value)
            })
            ReverbSlider("Taille de la salle", AppSettings.reverbSize.value, { size ->
                Player.setReverb(AppSettings.reverbAmount.value.coerceAtLeast(30), size, AppSettings.reverbDamping.value)
            })
            ReverbSlider("Amortissement", AppSettings.reverbDamping.value, { damping ->
                Player.setReverb(AppSettings.reverbAmount.value.coerceAtLeast(30), AppSettings.reverbSize.value, damping)
            })
            MenuItemRow("Réinitialiser", Icons.Filled.RestartAlt) {
                Player.setReverb(35, 50, 35)
                com.ketvoice.app.data.SettingsStore.save()
            }
            MenuItemRow("Désactiver", Icons.Filled.Close) {
                Player.setReverb(0, AppSettings.reverbSize.value, AppSettings.reverbDamping.value)
                com.ketvoice.app.data.SettingsStore.save()
            }
        }
        // Exclusion du Canvas par morceau : visible uniquement quand les Canvas
        // vidéo sont activés dans les paramètres. Persisté dans CanvasRepo.
        if (AppSettings.animatedArtwork.value) {
            val excluded = CanvasRepo.isExcluded(track.id)
            MenuItemRow(
                if (excluded) "Réactiver le Canvas pour ce morceau" else "Exclure ce morceau du Canvas",
                if (excluded) Icons.Filled.Videocam else Icons.Filled.VideocamOff
            ) {
                CanvasRepo.setExcluded(track.id, !excluded)
                onClose()
            }
        }
        val publisher by com.ketvoice.app.data.PublisherSession.allowed.collectAsState()
        if (publisher && com.ketvoice.app.data.ImportedSong.id(track) != null) MenuItemRow("Modifier dans Studio", Icons.Filled.Tune) {
            onClose()
            com.ketvoice.app.ui.AppUi.openPublisher(track, edit = true)
        }
        if (publisher && com.ketvoice.app.data.ImportedSong.id(track) == null) MenuItemRow("Importer des lyrics", Icons.Filled.PlaylistAdd) {
            onClose()
            com.ketvoice.app.ui.AppUi.openPublisher(track)
        }
        // Catalogue titles: the owner can lay their own cover, Canvas or artist photo over
        // the catalogue's, for the title, its album or its artist, and restore them later.
        if (publisher && com.ketvoice.app.data.ImportedSong.id(track) == null) MenuItemRow("Modifier les visuels", Icons.Filled.Image) {
            onClose()
            val original = art["t:${track.id}"] ?: track.coverUrl
            com.ketvoice.app.ui.AppUi.openVisuals(listOf(
                com.ketvoice.app.data.VisualTarget.track(track, original),
                track.album.takeIf { it.isNotBlank() }?.let { com.ketvoice.app.data.VisualTarget.album(it, com.ketvoice.app.data.MediaOverrides.mainArtist(track.artist), original, track) },
                com.ketvoice.app.data.VisualTarget.artist(com.ketvoice.app.data.MediaOverrides.mainArtist(track.artist), null)))
        }
        run {
            val remaining by SleepTimer.remainingSeconds.collectAsState()
            val afterTrack by SleepTimer.endOfTrack.collectAsState()
            var timerOpen by remember { mutableStateOf(false) }
            MenuItemRow(when {
                afterTrack -> "Pause à la fin du titre"
                remaining > 0 -> "Pause dans ${(remaining + 59) / 60} min"
                else -> "Minuterie"
            }, Icons.Filled.Bedtime) { timerOpen = !timerOpen }
            if (timerOpen) {
                MenuItemRow("À la fin du titre", Icons.Filled.Bedtime) {
                    SleepTimer.stopAfterTrack()
                    onClose()
                }
                SleepTimer.CHOICES.forEach { minutes ->
                    MenuItemRow("Pause dans $minutes min", Icons.Filled.Bedtime) {
                        SleepTimer.set(minutes)
                        onClose()
                    }
                }
                if (afterTrack || remaining > 0) MenuItemRow("Annuler la minuterie", Icons.Filled.Bedtime) {
                    SleepTimer.cancel()
                    onClose()
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun ReverbSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = com.ketvoice.app.ui.common.sheetInk().copy(alpha = .82f), fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text("$value%", localize = false, color = com.ketvoice.app.ui.common.sheetInk().copy(alpha = .82f), fontSize = 12.sp)
    }
    com.ketvoice.app.ui.common.GlassSlider(
        value = value.toFloat(),
        valueRange = 0f..100f,
        onValueChange = { onChange(it.toInt()) },
        onValueChangeFinished = { com.ketvoice.app.data.SettingsStore.save() },
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = com.ketvoice.app.data.AppText.t(label)
        }
    )
}

@Composable
private fun MenuItemRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color = com.ketvoice.app.ui.common.sheetInk(),
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
        Text(label, color = com.ketvoice.app.ui.common.sheetInk(), fontSize = 17.sp)
    }
}

@Composable
fun AudioOutputSheet(onClose: () -> Unit) {
    val castingTo by com.ketvoice.app.cast.CastState.connected.collectAsState()
    val radius = with(LocalDensity.current) { 26.dp.toPx() }
    GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(10.dp),
        shape = RoundedCornerShape(26.dp),
        params = GlassParams.sheet(radius, dark = com.ketvoice.app.ui.common.sheetDark())
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures {} }
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
        ) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .width(44.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(com.ketvoice.app.ui.common.sheetInk().copy(alpha = 0.35f))
            )
        }
        Text(
            "Sortie audio",
            color = com.ketvoice.app.ui.common.sheetInk(),
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
        )

        LocalOutputs(castingTo != null, onClose)
        CastDevicesSection(onClose)
        RemoteDevicesSection(onClose)

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
            color = com.ketvoice.app.ui.common.sheetInk().copy(alpha = 0.5f),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
        )
        MenuItemRow("Égaliseur", Icons.Filled.Equalizer) {
            onClose();com.ketvoice.app.ui.AppUi.showEqualizer.value=true
        }
        MenuItemRow("Ketogether", com.ketvoice.app.ui.together.KetogetherIcon) {
            onClose(); com.ketvoice.app.together.Ketogether.sheet.value = true
        }
        MenuItemRow("Transférer sur le même Wi-Fi", Icons.Filled.Wifi) {
            onClose();com.ketvoice.app.ui.AppUi.showHandoff.value=true
        }
        Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * « Choix de la sortie audio » (8.4): every real output of the phone, the one Ketvoice plays to ticked. Kept
 * current while the sheet is open; a choice moves only Ketvoice's music ([com.ketvoice.app.player.AudioOutputs]).
 */
@Composable
private fun LocalOutputs(casting: Boolean, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val outputs by remember(context) { com.ketvoice.app.player.AudioOutputs.watch(context) }
        .collectAsState(initial = remember(context) { com.ketvoice.app.player.AudioOutputs.read(context) })
    val chosen by com.ketvoice.app.player.AudioOutputs.chosen.collectAsState()
    val active = if (casting) null else com.ketvoice.app.player.AudioOutputs.active(outputs, chosen)
    // Nothing read (a host without audio devices): the phone itself, as before.
    val shown = outputs.ifEmpty { listOf(com.ketvoice.app.player.AudioOutput(-1, 0, com.ketvoice.app.player.OutputKind.Phone, "")) }
    shown.forEach { output ->
        OutputCard(
            title = outputTitle(output),
            sub = outputSub(output, (!casting && output == active) || (active == null && !casting && output.id == -1)),
            icon = outputIcon(output.kind),
            trailing = { if (!casting && (output == active || (active == null && output.id == -1)))
                Icon(Icons.Filled.Check, null, tint = LocalTheme.current.accent) },
            onClick = {
                if (casting) com.ketvoice.app.cast.CastFlavor.disconnect()
                com.ketvoice.app.player.AudioOutputs.choose(output.takeIf { it.id >= 0 })
                onClose()
            },
            modifier = Modifier.testTag("audio-output-" + output.kind.name)
        )
    }
}

internal fun outputTitle(output: com.ketvoice.app.player.AudioOutput): String = when (output.kind) {
    com.ketvoice.app.player.OutputKind.Phone -> if (com.ketvoice.app.BuildConfig.IS_QUEST) "Ce casque" else "Ce téléphone"
    com.ketvoice.app.player.OutputKind.Wired -> output.name.takeIf { it.isNotBlank() && !it.equals(Build.MODEL, true) } ?: "Écouteurs filaires"
    com.ketvoice.app.player.OutputKind.Usb -> output.name.ifBlank { "Sortie USB" }
    com.ketvoice.app.player.OutputKind.Bluetooth -> output.name.ifBlank { "Appareil Bluetooth" }
    com.ketvoice.app.player.OutputKind.HearingAid -> output.name.ifBlank { "Appareils auditifs" }
    com.ketvoice.app.player.OutputKind.Hdmi -> output.name.ifBlank { "HDMI" }
}

private fun outputSub(output: com.ketvoice.app.player.AudioOutput, active: Boolean): String {
    val kind = when (output.kind) {
        com.ketvoice.app.player.OutputKind.Phone -> "Haut-parleur"
        com.ketvoice.app.player.OutputKind.Wired -> "Filaire"
        com.ketvoice.app.player.OutputKind.Usb -> "USB"
        com.ketvoice.app.player.OutputKind.Bluetooth -> "Bluetooth"
        com.ketvoice.app.player.OutputKind.HearingAid -> "Appareils auditifs"
        com.ketvoice.app.player.OutputKind.Hdmi -> "HDMI"
    }
    return if (active) com.ketvoice.app.data.AppText.t(kind) + " · " + com.ketvoice.app.data.AppText.t("En lecture ici") else kind
}

private fun outputIcon(kind: com.ketvoice.app.player.OutputKind): androidx.compose.ui.graphics.vector.ImageVector = when (kind) {
    com.ketvoice.app.player.OutputKind.Phone -> Icons.Filled.Smartphone
    com.ketvoice.app.player.OutputKind.Wired -> Icons.Filled.Headphones
    com.ketvoice.app.player.OutputKind.Usb -> Icons.Filled.Usb
    com.ketvoice.app.player.OutputKind.Bluetooth -> Icons.Filled.Bluetooth
    com.ketvoice.app.player.OutputKind.HearingAid -> Icons.Filled.Hearing
    com.ketvoice.app.player.OutputKind.Hdmi -> Icons.Filled.Tv
}

@Composable
private fun OutputCard(
    title: String,
    sub: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    trailing: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
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
                .background(com.ketvoice.app.ui.common.sheetInk().copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = com.ketvoice.app.ui.common.sheetInk(), modifier = Modifier.size(21.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = com.ketvoice.app.ui.common.sheetInk(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = com.ketvoice.app.ui.common.sheetInk().copy(alpha = 0.55f), fontSize = 13.sp)
        }
        trailing()
    }
}
