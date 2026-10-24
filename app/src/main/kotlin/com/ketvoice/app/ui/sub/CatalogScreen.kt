package com.ketvoice.app.ui.sub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.player.formatSecs
import com.ketvoice.app.ui.common.DownloadCover
import com.ketvoice.app.ui.common.gradientFor
import com.ketvoice.app.ui.common.TrackTitle
import com.ketvoice.app.ui.common.EmptyState
import com.ketvoice.app.ui.common.openTrackSearch

@Composable
fun CatalogScreen() {
    val request = AppUi.catalogRequest.value ?: return
    val theme = LocalTheme.current
    val recent by ListeningHistory.tracks.collectAsState()
    val known by ListeningHistory.knownTracks.collectAsState()
    val downloads by Downloader.items.collectAsState()
    val art by ArtworkRepo.covers.collectAsState()
    val player by Player.state.collectAsState()
    var page by remember(request) { mutableStateOf<CatalogPage?>(null) }
    var loading by remember(request) { mutableStateOf(request.kind in listOf("album", "genre")) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(request, attempt) {
        if (request.kind in listOf("album", "genre")) {
            loading = true
            page = if (request.kind == "album" && request.id.startsWith("MPRE")) {
                YouTubeCatalog.album(request.id)
            } else CatalogRepo.page(request)
            loading = false
        }
    }
    val tracks = when (request.kind) {
        "favorites" -> (known + recent + downloads.map { it.track }).distinctBy { it.id }.filter { it.id in AppSettings.favoriteIds.value }
        "history" -> recent
        "all" -> (known + recent + downloads.map { it.track }).distinctBy { it.id }
        else -> page?.tracks.orEmpty()
    }
    LaunchedEffect(tracks) { ArtworkRepo.enrich(tracks) }
    Column(Modifier.fillMaxSize().background(theme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = theme.onBackground,
                modifier = Modifier.size(30.dp).clickable { AppUi.overlay.value = AppUi.NONE })
            Spacer(Modifier.width(14.dp))
            Text(page?.title ?: request.title, color = theme.onBackground, fontSize = 24.sp,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        // Mosaïque 2x2 : les 4 premières pochettes de la collection en guise de grande cover
        if (tracks.isNotEmpty()) {
            val mosaicCovers = tracks.take(4).map { ArtworkRepo.coverForTrack(it) ?: it.coverUrl }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(Modifier.size(168.dp).clip(RoundedCornerShape(18.dp))) {
                    for (i in 0 until 4) {
                        val url = mosaicCovers.getOrNull(i)
                        Box(Modifier.weight(1f).fillMaxHeight().background(
                            if (url != null) Brush.linearGradient(listOf(Color.Black, Color.Black)) else Brush.linearGradient(gradientFor(request.id + i))
                        )) {
                            if (url != null) {
                                AsyncImage(model = url, contentDescription = null,
                                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            } else {
                                Text(tracks.getOrNull(i)?.title?.take(1)?.uppercase() ?: "♪",
                                    localize = false, color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 26.sp, fontWeight = FontWeight.Black,
                                    modifier = Modifier.align(Alignment.Center))
                            }
                        }
                    }
                }
            }
        }
        if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = theme.accent, modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
        } else if (tracks.isEmpty()) {
            val remote = request.kind in listOf("album", "genre")
            EmptyState(if (remote) "Catalogue indisponible" else "Aucun titre dans cette collection.",
                when (request.kind) {
                    "favorites" -> "Ajoutez des titres avec l'étoile du lecteur pour les retrouver ici."
                    "history" -> "Vos titres écoutés apparaîtront ici. Lancez une première écoute."
                    "album", "genre" -> "Vérifiez votre connexion, puis réessayez."
                    else -> "Écoutez un titre pour commencer votre collection."
                }, if (remote) "Réessayer" else "Rechercher des titres") {
                    if (remote) attempt++ else openTrackSearch()
                }
        } else LazyVerticalGrid(
            columns = if (LocalAdaptiveLayout.current.hasRail) GridCells.Adaptive(400.dp) else GridCells.Fixed(1),
            contentPadding = PaddingValues(bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp)) {
            if (page?.cover != null) item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(page!!.title, page!!.cover, 100.dp, 12.dp)
                    Text(page!!.subtitle, color = theme.secondary, modifier = Modifier.padding(16.dp))
                }
            }
            itemsIndexed(tracks, key = { i, t -> "${t.id}:$i" }) { index, track ->
                val active = player.current?.id == track.id
                Row(Modifier.fillMaxWidth().clickable { Player.playQueue(tracks, index) }.padding(horizontal = 18.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    DownloadCover(track, art["t:${track.id}"] ?: track.coverUrl, 52.dp, 8.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        TrackTitle(track.title, color = if (active) theme.accent else theme.onBackground, selected = active)
                        Text(track.artist, localize = false, color = theme.secondary, fontSize = if (com.ketvoice.app.BuildConfig.IS_QUEST) 14.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (track.durationMs > 0) Text(formatSecs(track.durationMs), localize = false, color = theme.secondary, fontSize = if (com.ketvoice.app.BuildConfig.IS_QUEST) 14.sp else 12.sp)
                    Icon(Icons.Filled.MoreVert, "Options", tint = theme.secondary,
                        modifier = Modifier.size(36.dp).clickable { AppUi.trackMenuTrack.value = track }.padding(6.dp))
                }
            }
        }
    }
}
