package com.ketvoice.app.ui.sub

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadDone
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.T
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.DownloadCover
import com.ketvoice.app.ui.common.TrackTitle
import com.ketvoice.app.ui.common.EmptyState
import com.ketvoice.app.ui.common.openTrackSearch
import com.ketvoice.app.ui.motion.Motion
import java.util.Locale

/**
 * Téléchargements réels : pistes FLAC/MP3 enregistrées sur l'appareil,
 * jouables sans réseau. Suppression individuelle.
 */
@Composable
fun DownloadsScreen() {
    val th = LocalTheme.current
    val list by Downloader.items.collectAsState()
    val busy by Downloader.busy.collectAsState()
    val progress by Downloader.progress.collectAsState()
    val activeTracks by Downloader.activeTracks.collectAsState()
    val art by ArtworkRepo.covers.collectAsState()
    val total = list.sumOf { it.bytes }
    val shown = (activeTracks.values + list.map { it.track }).distinctBy { it.id }

    Column(
        Modifier
            .fillMaxSize()
            .background(th.background)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 14.dp, end = 16.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, "Retour",
                tint = th.onBackground,
                modifier = Modifier
                    .size(30.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { AppUi.overlay.value = AppUi.NONE }
            )
            Spacer(Modifier.width(12.dp))
            Text("Téléchargements", style = T.hugeTitle, color = th.onBackground, fontSize = 30.sp)
        }

        val percent = if (progress.isNotEmpty()) progress.values.average().toInt() else 0
        Text(
            buildString {
                append("${list.size} titre${if (list.size > 1) "s" else ""}")
                if (total > 0) append(" · ${formatSize(total)}")
                if (busy.isNotEmpty()) append(" · ${busy.size} en cours ($percent %)")
            },
            style = T.sub,
            color = th.secondary,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
        )

        // Barre de progression globale (réelle, alimentée par le flux de téléchargement)
        if (busy.isNotEmpty()) {
            Box(
                Modifier
                    .padding(horizontal = 18.dp, vertical = 2.dp)
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                    .background(th.hairline)
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth((percent / 100f).coerceIn(0.02f, 1f))
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                        .background(th.accent)
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        if (shown.isEmpty()) {
            EmptyState("Aucun téléchargement", "Pour écouter hors ligne, ouvrez le menu d'un titre puis choisissez Télécharger.",
                "Rechercher des titres", onAction = ::openTrackSearch)
        } else {
            val tracks = list.map { it.track }
            LazyVerticalGrid(
                columns = if (LocalAdaptiveLayout.current.hasRail) GridCells.Adaptive(400.dp) else GridCells.Fixed(1),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp)
            ) {
                items(count = shown.size, key = { shown[it].id }) { index ->
                    val track = shown[index]
                    val item = list.firstOrNull { it.track.id == track.id }
                    Row(
                        Modifier
                            .then(if (Motion.reduced) Modifier else Modifier.animateItem())
                            .fillMaxWidth()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                             .clickable(
                                enabled = item != null,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                Player.playQueue(tracks, tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
                            }
                            .padding(horizontal = 6.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DownloadCover(
                            track,
                            art["t:${track.id}"] ?: track.coverUrl,
                            size = 56.dp,
                            radius = 9.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            TrackTitle(track.title, color = th.onBackground)
                            Text(
                                if (item != null) "${track.artist} · ${item.format} · ${formatSize(item.bytes)}" else track.artist,
                                localize = false,
                                color = th.secondary,
                                style = T.sub,
                                fontSize = if (com.ketvoice.app.BuildConfig.IS_QUEST) 14.sp else 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (track.id in busy) Text(
                                if ((progress[track.id] ?: 0) >= 99) "Audio enregistré · finalisation…"
                                else "Téléchargement en cours · ${progress[track.id] ?: 0} %", color = th.accent,
                                fontSize = if (com.ketvoice.app.BuildConfig.IS_QUEST) 14.sp else 12.sp)
                            if (item != null && (item.hasLyrics || item.hasCanvas)) {
                                Text(
                                    listOfNotNull(
                                        if (item.hasLyrics) "Paroles enregistrées" else null,
                                        if (item.hasCanvas) "Canvas enregistré" else null
                                    ).joinToString(" · "),
                                    color = th.accent,
                                    fontSize = if (com.ketvoice.app.BuildConfig.IS_QUEST) 13.sp else 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        if (item != null) Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { Downloader.delete(item) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Delete, "Supprimer le téléchargement",
                                tint = th.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 Mo"
    val mo = bytes / 1_048_576.0
    return if (mo >= 1000) {
        String.format(Locale.FRANCE, "%.1f Go", mo / 1024.0)
    } else {
        String.format(Locale.FRANCE, "%.1f Mo", mo)
    }
}
