package com.ketvoice.app.ui.sub

import com.ketvoice.app.ui.common.trackRowClick
import com.ketvoice.app.ui.common.SwipeTrackActions
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
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import com.ketvoice.app.ui.common.TrackActionFeedback
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
fun CatalogScreen(request: CatalogRequest? = AppUi.catalogRequest.value) {
    request ?: return
    val theme = LocalTheme.current
    val recent by ListeningHistory.tracks.collectAsState()
    val known by ListeningHistory.knownTracks.collectAsState()
    val downloads by Downloader.items.collectAsState()
    val art by ArtworkRepo.covers.collectAsState()
    val player by Player.state.collectAsState()
    var page by remember(request) { mutableStateOf<CatalogPage?>(null) }
    var loading by remember(request) { mutableStateOf(request.kind in listOf("album", "genre", "mix")) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(request, attempt) {
        if (request.kind in listOf("album", "genre", "mix")) {
            loading = true
            try {
                page = if (request.kind == "mix") {
                    // A personal mix: built from the listening history, completed by the catalogue.
                    PersonalMixes.tracks(request.id)?.let { CatalogPage(request.title, "", it.firstOrNull()?.coverUrl, it) }
                } else if (request.kind == "album" && request.id.startsWith("MPRE")) {
                    YouTubeCatalog.album(request.id)
                } else CatalogRepo.page(request)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                page = null
            } finally { loading = false }
        }
    }
    val tracks = when (request.kind) {
        "favorites" -> (known + recent + downloads.map { it.track }).distinctBy { it.id }.filter { it.id in AppSettings.favoriteIds.value }
        "history" -> recent
        "all" -> (known + recent + downloads.map { it.track }).distinctBy { it.id }
        else -> page?.tracks.orEmpty()
    }
    LaunchedEffect(tracks) { ArtworkRepo.enrich(tracks) }
    if (request.kind == "album") {
        AlbumPage(request, page, tracks, loading, onRetry = { attempt++ })
        return
    }
    // Canvas du hero : résolu pour le premier titre quand les Canvas sont actifs.
    val shareContext = androidx.compose.ui.platform.LocalContext.current
    val saveScope = rememberCoroutineScope()
    var saving by remember(request) { mutableStateOf(false) }
    var savedAsPlaylist by remember(request) { mutableStateOf(false) }
    // Local collections and mixes are named by the app, in French: show and save them in the
    // interface language. A genre or an album keeps the name the catalogue gave it.
    val appNamed = request.kind in listOf("favorites", "history", "all", "mix")
    val shownTitle = if (appNamed) AppText.t(request.title) else request.title
    Column(Modifier.fillMaxSize().background(theme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassBackButton { AppUi.closeOverlay() }
            Spacer(Modifier.width(14.dp))
            Text(page?.title ?: request.title, color = theme.onBackground, fontSize = 24.sp,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = theme.accent, modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
        } else if (tracks.isEmpty()) {
            val remote = request.kind in listOf("album", "genre", "mix")
            EmptyState(if (remote) "Catalogue indisponible" else "Aucun titre dans cette collection.",
                when (request.kind) {
                    "favorites" -> "Ajoutez des titres avec l'étoile du lecteur pour les retrouver ici."
                    "history" -> "Vos titres écoutés apparaîtront ici. Lancez une première écoute."
                    "album", "genre", "mix" -> "Vérifiez votre connexion, puis réessayez."
                    else -> "Écoutez un titre pour commencer votre collection."
                }, if (remote) "Réessayer" else "Rechercher des titres") {
                    if (remote) attempt++ else openTrackSearch()
                }
        } else LazyVerticalGrid(
            columns = if (LocalAdaptiveLayout.current.hasRail) GridCells.Adaptive(400.dp) else GridCells.Fixed(1),
            contentPadding = PaddingValues(bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                // Header of the collection now matches the playlist/album presentation.
                CollectionHero(
                    title = shownTitle,
                    subtitle = AppText.t(buildString {
                        append("${tracks.size} titre${if (tracks.size > 1) "s" else ""}")
                        val minutes = tracks.sumOf { it.durationMs } / 60_000
                        if (minutes > 0) append(" · $minutes min")
                        when (request.kind) {
                            "favorites" -> append(" · Vos favoris")
                            "history" -> append(" · Écoutés récemment")
                            "downloads" -> append(" · Hors ligne")
                            "mix" -> append(" · Fait pour vous")
                        }
                    }),
                    covers = tracks.take(4).map { ArtworkRepo.coverForTrack(it) ?: it.coverUrl },
                    seed = request.id + request.kind,
                    onPlay = { if (tracks.isNotEmpty()) { Player.setShuffle(false); Player.playQueue(tracks, 0) } },
                    onShuffle = { if (tracks.isNotEmpty()) { Player.playShuffled(tracks) } },
                    onShare = { com.ketvoice.app.util.ShareLinks.shareCollection(shareContext, request.kind, request.title, tracks.size) },
                    onRefresh = {
                        ArtworkRepo.invalidate(tracks)
                        ArtworkRepo.enrich(tracks)
                        attempt++
                    }
                )
            }
            if (request.kind == "mix") item(span = { GridItemSpan(maxLineSpan) }) {
                // A mix changes with the listening history; saving it keeps this exact selection.
                TextButton(enabled = !saving && !savedAsPlaylist, onClick = {
                    // One playlist per mix page: a second tap must not create a copy.
                    saving = true
                    saveScope.launch {
                        val saved = runCatching {
                            PlaylistStore.create(shownTitle.take(80), tracks.map(PlaylistRules::entry),
                                cover = tracks.firstOrNull()?.let { ArtworkRepo.coverForTrack(it) ?: it.coverUrl })
                        }.isSuccess
                        savedAsPlaylist = saved
                        saving = false
                        TrackActionFeedback.snackbar.currentSnackbarData?.dismiss()
                        TrackActionFeedback.snackbar.showSnackbar(AppText.t(
                            if (saved) "Playlist enregistrée dans la bibliothèque" else "Impossible d'enregistrer la playlist"))
                    }
                }, modifier = Modifier.padding(horizontal = 10.dp).testTag("mix-save")) {
                    Text(if (savedAsPlaylist) "Playlist enregistrée dans la bibliothèque" else "Enregistrer comme playlist",
                        color = if (savedAsPlaylist) theme.secondary else theme.accent, fontWeight = FontWeight.SemiBold)
                }
            }
            itemsIndexed(tracks, key = { i, t -> "${t.id}:$i" }) { index, track ->
                val active = player.current?.id == track.id
                SwipeTrackActions(track) {
                    com.ketvoice.app.ui.common.TrackRow(track, art["t:${track.id}"] ?: track.coverUrl, active, player.isPlaying,
                        { Player.playQueue(tracks, index) })
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
