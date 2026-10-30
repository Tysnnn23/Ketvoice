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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import com.ketvoice.app.ui.common.GlassBackButton
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
    // Canvas du hero : résolu pour le premier titre quand les Canvas sont actifs.
    val canvasTrack = tracks.firstOrNull()?.takeIf { AppSettings.animatedArtwork.value }
    LaunchedEffect(canvasTrack?.id) { canvasTrack?.let { CanvasRepo.resolve(it) } }
    val shareContext = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize().background(theme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassBackButton { AppUi.closeOverlay() }
            Spacer(Modifier.width(14.dp))
            Text(page?.title ?: request.title, color = theme.onBackground, fontSize = 24.sp,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        // Header immersif quand la page porte une vraie pochette (albums) :
        // pochette centrée en grand (canvas si dispo), fond flouté plein cadre,
        // boutons Lecture et Partager. La mosaïque reste le repli sans pochette.
        val heroCover = page?.cover
        if (heroCover != null && !loading) {
            AlbumHero(
                title = page?.title ?: request.title,
                subtitle = page?.subtitle.orEmpty(),
                releaseDate = page?.releaseDate,
                cover = heroCover,
                trackCount = tracks.size,
                canvasTrack = canvasTrack,
                onPlay = { if (tracks.isNotEmpty()) Player.playQueue(tracks, 0) },
                onShare = {
                    com.ketvoice.app.util.ShareLinks.shareAlbum(
                        shareContext, request.id, page?.title ?: request.title, page?.subtitle.orEmpty()
                    )
                }
            )
        } else if (tracks.isNotEmpty() && !loading) {
            // Mosaïque 2x2 en repli : les 4 premières pochettes de la collection.
            Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(Modifier.size(168.dp).clip(RoundedCornerShape(18.dp))) {
                    for (i in 0 until 4) {
                        val url = tracks.getOrNull(i)?.let { ArtworkRepo.coverForTrack(it) } ?: tracks.getOrNull(i)?.coverUrl
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

/**
 * Header d'album refondu : pochette EN GRAND et CENTRÉE (canvas vidéo si
 * disponible sur le premier titre), fond flouté plein cadre, titre/artiste/
 * année centrés, boutons Lecture et Partager côte à côte.
 */
@Composable
private fun AlbumHero(
    title: String,
    subtitle: String,
    releaseDate: String?,
    cover: String,
    trackCount: Int,
    canvasTrack: Track?,
    onPlay: () -> Unit,
    onShare: () -> Unit
) {
    val theme = LocalTheme.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val radius = with(density) { 24.dp.toPx() }
    Box(Modifier.fillMaxWidth()) {
        // Fond : pochette floutée et agrandie, aucun bord dur.
        AsyncImage(
            model = cover, contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { scaleX = 1.4f; scaleY = 1.4f }
                .blur(40.dp, BlurredEdgeTreatment.Unbounded)
        )
        // Dégradés : raccord haut vers la barre, raccord bas vers la liste.
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(
            theme.background.copy(alpha = .6f), Color.Transparent, Color.Transparent,
            theme.background.copy(alpha = .78f), theme.background
        ))))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Canvas vidéo si disponible sur le premier titre, pochette sinon.
            Box(
                Modifier
                    .size(216.dp)
                    .shadow(18.dp, RoundedCornerShape(24.dp), spotColor = Color.Black.copy(alpha = 0.45f))
                    .clip(RoundedCornerShape(24.dp))
            ) {
                if (canvasTrack != null) {
                    com.ketvoice.app.ui.player.ArtworkCanvas(
                        track = canvasTrack,
                        canvasPath = CanvasRepo.paths.collectAsState().value[canvasTrack.id],
                        playing = false,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Cover(title, cover, 216.dp, 24.dp)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(title, localize = false, color = theme.onBackground,
                fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = (-.6).sp,
                lineHeight = 29.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            val meta = buildList {
                if (subtitle.isNotBlank()) add(subtitle)
                if (releaseDate != null) add("Sorti en $releaseDate")
                add("$trackCount titres")
            }.joinToString("  ·  ")
            if (meta.isNotBlank()) {
                Text(meta, localize = false, color = theme.secondary, fontSize = 13.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = 7.dp))
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(23.dp))
                        .background(theme.accent)
                        .clickable(enabled = trackCount > 0, onClick = onPlay)
                        .padding(horizontal = 26.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(21.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Lecture", color = Color.Black, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(23.dp))
                        .background(theme.onBackground.copy(alpha = 0.10f))
                        .clickable(onClick = onShare)
                        .padding(horizontal = 22.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Share, "Partager", tint = theme.onBackground, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Partager", color = theme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
