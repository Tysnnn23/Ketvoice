package com.ketvoice.app.ui.sub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.*
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.glass.BackdropCapture
import com.ketvoice.app.glass.LocalBackdropLayer
import com.ketvoice.app.glass.rememberGlassBackdrop
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.*
import com.ketvoice.app.ui.player.formatSecs
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.StaggeredReveal
import com.ketvoice.app.util.ShareLinks

/** One scrolling surface: artwork, metadata and tracks never form disconnected panels. */
@Composable
internal fun AlbumPage(request: CatalogRequest, page: CatalogPage?, tracks: List<Track>, loading: Boolean,
                       onRetry: () -> Unit) {
    val baseTheme = LocalTheme.current
    val list = rememberLazyListState()
    val collapse = remember { derivedStateOf {
        val hero = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "album-hero" }
        if (hero == null) if (list.firstVisibleItemIndex > 0) 1f else 0f
        else ((-hero.offset).toFloat() / (hero.size * .68f).coerceAtLeast(1f)).coerceIn(0f, 1f)
    } }
    val current by Player.state.collectAsState()
    val artwork by ArtworkRepo.covers.collectAsState()
    val context = LocalContext.current
    val title = page?.title ?: request.title
    val artist = page?.subtitle.orEmpty()
    val cover = page?.cover ?: tracks.firstOrNull()?.coverUrl
    val albumAccent = com.ketvoice.app.theme.rememberCoverAccent(cover)?.first
    val heroTone = albumAccent ?: gradientFor(request.id).last()
    val theme = baseTheme.copy(accent = if (AppSettings.albumColors.value) albumAccent ?: baseTheme.accent else baseTheme.accent)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 66.dp
    val onAccent = if (theme.accent.luminance() > .179f) Color.Black else Color.White
    val controlsBackdrop = rememberGlassBackdrop(theme.background)
    var imageReady by remember(cover) { mutableStateOf(false) }
    val imageAlpha by animateFloatAsState(if (imageReady) 1f else 0f,
        tween(Motion.duration(320)), label = "albumArtworkReveal")
    val imageRequest = remember(context, cover, Motion.reduced) {
        coil.request.ImageRequest.Builder(context).data(cover).crossfade(false).build()
    }
    Box(Modifier.fillMaxSize().background(theme.background)) {
        CompositionLocalProvider(LocalBackdropLayer provides controlsBackdrop, LocalTheme provides theme) {
        // Capture the scrolling artwork, not a disconnected blurred background.
        // Inline controls use static material to avoid sampling themselves.
        BackdropCapture(controlsBackdrop, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("album-list"), state = list,
            contentPadding = PaddingValues(bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp + 20.dp)) {
            item(key = "album-hero") {
                val canvasTrack = tracks.firstOrNull()
                val canvasEnabled = AppSettings.albumHeroCanvas.value &&
                    !AppSettings.reduceAnimations.value && !AppSettings.highPerformanceMode.value
                val paths by CanvasRepo.paths.collectAsState()
                LaunchedEffect(canvasTrack?.id, canvasEnabled) {
                    if (canvasEnabled && canvasTrack != null) CanvasRepo.resolve(canvasTrack)
                }
                val canvasPath = canvasTrack?.let { paths[it.id] }
                Column(Modifier.fillMaxWidth()) {
                    // ---- Full-bleed hero -------------------------------------------------
                    // The artwork is edge-to-edge and runs UNDER the status bar: the whole
                    // surface bleeds, only the content below it is inset. A parallax pull
                    // and a steady zoom keep it alive without any hard-edged rectangle,
                    // and the album colour washes the top instead of framing it.
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val heroHeight = (maxWidth * 1.06f).coerceIn(300.dp, 480.dp)
                        // Parallax: the artwork lags behind the scroll and keeps overscan
                        // above so the top edge never uncovers when pulled down.
                        val pull by remember { derivedStateOf {
                            if (list.firstVisibleItemIndex == 0)
                                list.firstVisibleItemScrollOffset.toFloat().coerceAtLeast(0f) else 0f
                        } }
                        Box(Modifier.fillMaxWidth().height(heroHeight + top).clipToBounds()
                            .background(Brush.verticalGradient(listOf(
                                heroTone.copy(alpha = .42f), heroTone.copy(alpha = .16f))))
                            .testTag("album-hero-visual")) {
                            Box(
                                Modifier.fillMaxWidth()
                                     .height(heroHeight + top)
                                     .graphicsLayer {
                                         // px stay px: never convert scroll pixels to dp.
                                         translationY = if (com.ketvoice.app.ui.motion.Motion.reduced) 0f else pull * .18f
                                          scaleX = 1.025f; scaleY = 1.025f
                                          compositingStrategy = CompositingStrategy.Offscreen
                                     }
                                     .drawWithCache {
                                         val dissolve = Brush.verticalGradient(
                                             0f to Color.White, .76f to Color.White, 1f to Color.Transparent)
                                         onDrawWithContent {
                                             drawContent()
                                             drawRect(dissolve, blendMode = BlendMode.DstIn)
                                         }
                                     }
                            ) {
                                when {
                                    canvasEnabled && canvasTrack != null && canvasPath != null ->
                                        com.ketvoice.app.ui.player.ArtworkCanvas(
                                             track = canvasTrack, canvasPath = canvasPath, playing = list.firstVisibleItemIndex == 0,
                                            forceCanvas = true, modifier = Modifier.fillMaxSize())
                                    cover != null -> AsyncImage(imageRequest, null, contentScale = ContentScale.Crop,
                                        onSuccess = { imageReady = true },
                                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = imageAlpha })
                                    else -> Box(Modifier.fillMaxSize().background(
                                        Brush.linearGradient(gradientFor(request.id))))
                                }
                            }
                            // A restrained local hue at the top. Never bleach the artwork.
                            Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                                 0f to heroTone.copy(alpha = .12f),
                                .24f to Color.Transparent,
                                1f to Color.Transparent)))
                            // Scrim behind the floating top bar so its icons stay legible.
                            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(top + 56.dp)
                                .background(Brush.verticalGradient(listOf(
                                    Color.Black.copy(alpha = .38f), Color.Transparent))))
                        }
                    }
                    // The artwork itself dissolves into this local colour, never
                    // into a white overlay. The tint then quietly meets the page.
                    Box(Modifier.fillMaxWidth().height(32.dp).background(Brush.verticalGradient(
                        listOf(heroTone.copy(alpha = .16f), Color.Transparent))))
                    StaggeredReveal(order = 0, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(14.dp))
                        Text(title, localize = false, color = theme.onBackground, fontSize = 29.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = (-.8).sp, lineHeight = 34.sp,
                            textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        if (tracks.any { it.explicit }) EBadge(Modifier.padding(top = 8.dp))
                        if (artist.isNotBlank()) Text(artist, localize = false, color = theme.accent, fontSize = 17.sp,
                            fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp).clickable {
                                AppUi.openArtist(tracks.firstOrNull()?.artistId.orEmpty(), artist)
                            }.padding(vertical = 5.dp))
                        val totalMs = tracks.sumOf { it.durationMs }
                        val duration = when {
                            totalMs <= 0L -> null
                            totalMs >= 3_600_000L -> AppText.t("${totalMs / 3_600_000L} h ${(totalMs % 3_600_000L) / 60_000L} min")
                            else -> AppText.t("${(totalMs / 60_000L).coerceAtLeast(1L)} min")
                        }
                        val count = AppText.t(if (tracks.size == 1) "1 titre" else "${tracks.size} titres")
                        val meta = listOfNotNull(page?.releaseDate?.take(4), count, duration)
                            .joinToString("  \u00b7  ")
                        if (!loading) Text(meta, localize = false, color = theme.secondary, fontSize = 13.sp,
                            modifier = Modifier.padding(top = 4.dp))
                        Spacer(Modifier.height(22.dp))
                        StaggeredReveal(order = 2, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.weight(1f).clip(RoundedCornerShape(50))
                                .background(theme.accent.copy(alpha = if (tracks.isEmpty()) .35f else 1f))
                                .clickable(enabled = tracks.isNotEmpty()) { Player.setShuffle(false); Player.playQueue(tracks) }
                                .heightIn(min = 50.dp).padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.PlayArrow, null, tint = onAccent, modifier = Modifier.size(21.dp))
                                Spacer(Modifier.width(6.dp));Text("Lecture", color = onAccent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            }
                            GlassPanel(Modifier.weight(1f), RoundedCornerShape(50),
                                GlassParams.pill(dark = theme.isDark), enabled = AppSettings.liquidGlass.value, captured = true) {
                                Row(Modifier.fillMaxWidth().clickable(enabled = tracks.isNotEmpty()) {
                                    Player.playQueue(tracks.shuffled()); Player.setShuffle(true)
                                }.heightIn(min = 50.dp).padding(horizontal = 14.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Shuffle, null, tint = theme.accent, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(6.dp));Text("Aléatoire", color = theme.accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                }
                            }
                        }
                        }
                        Spacer(Modifier.height(18.dp))
                    }
                    }
                }
            }
            if (loading) items(5, key = { "album-loading:$it" }) {
                CatalogSkeletonRow()
            }
            else if (tracks.isEmpty()) item {
                EmptyState("Catalogue indisponible", "Vérifiez votre connexion, puis réessayez.", "Réessayer", onAction = onRetry)
            }
            itemsIndexed(tracks, key = { index, track -> "${track.id}:$index" }) { index, track ->
                Box(Modifier.animateItem()) {
                com.ketvoice.app.ui.common.SwipeTrackActions(track) {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().clickable { Player.playQueue(tracks, index) }
                        .padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}", localize = false, color = theme.secondary, fontSize = 13.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.width(28.dp))
                        Spacer(Modifier.width(10.dp))
                        DownloadCover(track, artwork["t:${track.id}"] ?: track.coverUrl ?: cover, 44.dp, 8.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            TrackTitle(track.title, color = if (current.current?.id == track.id) theme.accent else theme.onBackground,
                                selected = current.current?.id == track.id)
                            Text(track.artist, localize = false, color = theme.secondary, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (track.durationMs > 0) Text(formatSecs(track.durationMs), localize = false,
                            color = theme.secondary, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                        Box(Modifier.size(48.dp).clip(CircleShape).clickable { AppUi.trackMenuTrack.value = track },
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.MoreHoriz, "Options", tint = theme.secondary, modifier = Modifier.size(22.dp))
                        }
                    }
                    Box(Modifier.padding(start = 100.dp, end = 20.dp).fillMaxWidth().height(.5.dp)
                        .background(theme.onBackground.copy(alpha = .07f)))
                }
                }
                }
            }
        }
        }
        Row(Modifier.fillMaxWidth().drawBehind { drawRect(theme.background.copy(alpha = .96f * collapse.value)) }
            .statusBarsPadding().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassPanel(Modifier.size(48.dp), CircleShape, GlassParams.circle(dark = theme.isDark)) {
                Box(Modifier.fillMaxSize().clickable { AppUi.closeOverlay() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = theme.onBackground)
                }
            }
            Text(title, localize = false, color = theme.onBackground,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 14.dp).graphicsLayer { alpha = collapse.value })
            GlassPanel(Modifier.size(48.dp), CircleShape, GlassParams.circle(dark = theme.isDark),
                enabled = AppSettings.liquidGlass.value) {
                Box(Modifier.fillMaxSize().clickable { ShareLinks.shareAlbum(context, request.id, title, artist) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Share, "Partager", tint = theme.onBackground, modifier = Modifier.size(21.dp))
                }
            }
        }
        }
    }
}
