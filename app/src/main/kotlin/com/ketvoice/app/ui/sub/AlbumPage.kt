package com.ketvoice.app.ui.sub

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.*
import com.ketvoice.app.glass.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.*
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.StaggeredReveal
import com.ketvoice.app.ui.player.formatSecs
import com.ketvoice.app.util.ShareLinks

/** Artwork and identity share one scroll origin; only floating controls sample it. */
@Composable
internal fun AlbumPage(request: CatalogRequest, page: CatalogPage?, tracks: List<Track>, loading: Boolean,
                       onRetry: () -> Unit) {
    val baseTheme = LocalTheme.current
    val list = rememberLazyListState()
    val current by Player.state.collectAsState()
    val saved by Downloader.items.collectAsState()
    val savedIds = remember(saved) { saved.mapTo(hashSetOf()) { it.track.id } }
    val context = LocalContext.current
    val title = page?.title ?: request.title
    val artist = page?.subtitle.orEmpty()
    val cover = page?.cover ?: tracks.firstOrNull()?.coverUrl
    val accent = com.ketvoice.app.theme.rememberCoverAccent(cover)?.first
    val heroTone = accent ?: gradientFor(request.id).last()
    val theme = baseTheme.copy(accent = if (AppSettings.albumColors.value) accent ?: baseTheme.accent else baseTheme.accent)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
    val collapse = remember { derivedStateOf {
        val hero = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "album-hero" }
        if (hero == null) if (list.firstVisibleItemIndex > 0) 1f else 0f
        else ((-hero.offset).toFloat() / (hero.size * .72f).coerceAtLeast(1f)).coerceIn(0f, 1f)
    } }
    val backdrop = rememberGlassBackdrop(theme.background)
    CompositionLocalProvider(LocalTheme provides theme, LocalBackdropLayer provides backdrop) {
        Box(Modifier.fillMaxSize().background(theme.background)) {
            BackdropCapture(backdrop, Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize().testTag("album-list"), state = list,
                    contentPadding = PaddingValues(bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp + 20.dp)) {
                    item(key = "album-hero") {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            if (maxWidth >= 720.dp) {
                                Row(Modifier.fillMaxWidth().padding(top = top), verticalAlignment = Alignment.CenterVertically) {
                                    AlbumArtwork(cover, tracks.firstOrNull(), heroTone, request.id,
                                        Modifier.weight(.44f).height(280.dp))
                                    AlbumIdentity(title, artist, page?.releaseDate, tracks, loading,
                                        Modifier.weight(.56f).padding(top = 8.dp))
                                }
                            } else {
                                val artworkHeight = (maxWidth * .82f).coerceIn(240.dp, 370.dp) + top
                                Column {
                                    val pull by remember { derivedStateOf {
                                        if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat() else 0f
                                    } }
                                    AlbumArtwork(cover, tracks.firstOrNull(), heroTone, request.id,
                                        Modifier.fillMaxWidth().height(artworkHeight), pull)
                                    AlbumIdentity(title, artist, page?.releaseDate, tracks, loading)
                                }
                            }
                        }
                    }
                    if (loading) items(5, key = { "album-loading:$it" }) { CatalogSkeletonRow() }
                    else if (tracks.isEmpty()) item {
                        EmptyState("Catalogue indisponible", "Vérifiez votre connexion, puis réessayez.", "Réessayer", onAction = onRetry)
                    }
                    itemsIndexed(tracks, key = { index, track -> "${track.id}:$index" }) { index, track ->
                        Box(Modifier.then(if (Motion.reduced) Modifier else Modifier.animateItem())) {
                            SwipeTrackActions(track) {
                                Column(Modifier.fillMaxWidth()) {
                                    Row(Modifier.fillMaxWidth()
                                        .background(if (current.current?.id == track.id) theme.accent.copy(alpha = .06f) else Color.Transparent)
                                        .clickable(role = Role.Button) { Player.setShuffle(false); Player.playQueue(tracks, index) }
                                        .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Text("${index + 1}", localize = false, color = theme.secondary, fontSize = 13.sp,
                                            textAlign = TextAlign.Center, modifier = Modifier.width(30.dp))
                                        Column(Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
                                            TrackTitle(track.title, color = if (current.current?.id == track.id) theme.accent else theme.onBackground,
                                                selected = current.current?.id == track.id)
                                            if (track.artist.isNotBlank()) Text(track.artist, localize = false, color = theme.secondary,
                                                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        if (track.id in savedIds) Icon(Icons.Filled.DownloadDone, "Téléchargé", tint = theme.secondary,
                                            modifier = Modifier.padding(horizontal = 6.dp).size(16.dp))
                                        if (track.durationMs > 0) Text(formatSecs(track.durationMs), localize = false,
                                            color = theme.secondary, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                                        androidx.compose.material3.IconButton(onClick = { AppUi.trackMenuTrack.value = track }, modifier = Modifier.size(48.dp)) {
                                            Icon(Icons.Filled.MoreHoriz, "Options", tint = theme.secondary, modifier = Modifier.size(22.dp))
                                        }
                                    }
                                    Box(Modifier.padding(start = 58.dp, end = 18.dp).fillMaxWidth().height(.5.dp).background(theme.hairline))
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().drawBehind { drawRect(theme.background.copy(alpha = .96f * collapse.value)) }
                .statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                LiquidGlassButton({ AppUi.closeOverlay() }, Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = theme.onBackground)
                }
                 Text(title, localize = false, color = theme.onBackground, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                     modifier = Modifier.weight(1f).padding(horizontal = 14.dp).graphicsLayer { alpha = collapse.value })
                if (tracks.isNotEmpty()) Row(Modifier.graphicsLayer { alpha = collapse.value }
                    .then(if (collapse.value < .98f) Modifier.clearAndSetSemantics {} else Modifier)) {
                    CollectionQuickActions(tracks, enabled = collapse.value >= .98f)
                }
                LiquidGlassButton({ ShareLinks.shareAlbum(context, request.id, title, artist) }, Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Share, "Partager", tint = theme.onBackground, modifier = Modifier.size(21.dp))
                }
            }
        }
    }
}

@Composable
private fun AlbumArtwork(cover: String?, track: Track?, tone: Color, seed: String, modifier: Modifier, pull: Float = 0f) {
    val theme = LocalTheme.current
    val context = LocalContext.current
    var ready by remember(cover) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (ready) 1f else 0f, tween(Motion.duration(280)), label = "albumArtworkReveal")
    val image = remember(context, cover) { coil.request.ImageRequest.Builder(context).data(cover).crossfade(false).build() }
    val canvasEnabled = AppSettings.albumHeroCanvas.value && !Motion.reduced && !AppSettings.highPerformanceMode.value
    val paths by CanvasRepo.paths.collectAsState()
    LaunchedEffect(track?.id, canvasEnabled) { if (canvasEnabled && track != null) CanvasRepo.resolve(track) }
    val canvas = track?.let { paths[it.id] }
    Box(modifier.clipToBounds().background(Brush.linearGradient(gradientFor(seed))).testTag("album-hero-visual")) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationY = if (Motion.reduced) 0f else pull * .12f
            scaleX = 1.025f; scaleY = 1.025f
            compositingStrategy = CompositingStrategy.Offscreen
        }.drawWithCache {
            val mask = Brush.verticalGradient(0f to Color.White, .86f to Color.White, 1f to Color.Transparent)
            onDrawWithContent { drawContent(); drawRect(mask, blendMode = BlendMode.DstIn) }
        }) {
            when {
                canvasEnabled && track != null && canvas != null -> com.ketvoice.app.ui.player.ArtworkCanvas(
                    track, canvas, playing = true, forceCanvas = true, modifier = Modifier.fillMaxSize())
                cover != null -> AsyncImage(image, null, contentScale = ContentScale.Crop,
                    onSuccess = { ready = true }, modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }
                        .testTag(if (ready) "album-artwork-loaded" else "album-artwork-loading"))
            }
        }
        // The photograph fades into the actual page, not a broad white veil.
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to tone.copy(alpha = .04f), .78f to Color.Transparent, 1f to theme.background)))
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AlbumIdentity(title: String, artist: String, date: String?, tracks: List<Track>, loading: Boolean,
                          modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val foreground = if (theme.accent.luminance() > .179f) Color.Black else Color.White
    val total = remember(tracks) { tracks.sumOf { it.durationMs } }
    val duration = when {
        total <= 0L -> null
        total >= 3_600_000L -> AppText.t("${total / 3_600_000L} h ${(total % 3_600_000L) / 60_000L} min")
        else -> AppText.t("${(total / 60_000L).coerceAtLeast(1L)} min")
    }
    val count = AppText.t(if (tracks.size == 1) "1 titre" else "${tracks.size} titres")
    StaggeredReveal(order = 0, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, localize = false, color = theme.onBackground, fontSize = 32.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, lineHeight = 36.sp,
                maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (artist.isNotBlank()) Text(artist, localize = false, color = theme.accent,
                fontSize = 17.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(role = Role.Button) { AppUi.openArtist(tracks.firstOrNull()?.artistId.orEmpty(), artist) }
                    .heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically).padding(vertical = 6.dp))
            if (tracks.any { it.explicit }) EBadge()
            if (!loading) Text(listOfNotNull(date?.take(4), count, duration).joinToString("  ·  "),
                localize = false, color = theme.secondary, fontSize = 13.sp)
            CollectionPlaybackActions(tracks, Modifier.padding(top = 12.dp, bottom = 8.dp).widthIn(max = 520.dp).fillMaxWidth(),
                enabled = !loading && tracks.isNotEmpty())
        }
    }
}
