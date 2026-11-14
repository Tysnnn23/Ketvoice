package com.ketvoice.app.ui.sub

import com.ketvoice.app.ui.common.trackRowClick
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
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

/** The page and hero have separate capture sources; neither samples its own glass. */
@Composable
internal fun AlbumPage(request: CatalogRequest, page: CatalogPage?, tracks: List<Track>, loading: Boolean,
                       onRetry: () -> Unit) {
    val baseTheme = LocalTheme.current
    val list = rememberLazyListState()
    val current by Player.state.collectAsState()
    val saved by Downloader.items.collectAsState()
    val art by ArtworkRepo.covers.collectAsState()
    val savedIds = remember(saved) { saved.mapTo(hashSetOf()) { it.track.id } }
    val context = LocalContext.current
    val title = page?.title ?: request.title
    val artist = page?.subtitle?.takeIf { it.isNotBlank() } ?: tracks.firstOrNull()?.artist.orEmpty()
    val cover = page?.cover?.takeIf { it.isNotBlank() } ?: tracks.firstOrNull()?.let { art["t:${it.id}"] ?: it.coverUrl }
    val accent = com.ketvoice.app.theme.rememberCoverAccent(cover)?.first
    val theme = baseTheme.copy(accent = if (AppSettings.albumColors.value) accent ?: baseTheme.accent else baseTheme.accent)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
    val collapse = remember { derivedStateOf {
        val hero = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "album-hero" }
        AlbumPresentation.collapse(hero?.offset, hero?.size ?: 0, list.firstVisibleItemIndex)
    } }
    val scroll = remember { derivedStateOf {
        if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat() else 0f
    } }
    val backdrop = rememberGlassBackdrop(theme.background)
    val refresh = {
        ArtworkRepo.invalidate(tracks)
        ArtworkRepo.enrich(tracks)
        onRetry()
    }
    CompositionLocalProvider(LocalTheme provides theme, LocalBackdropLayer provides backdrop) {
        Box(Modifier.fillMaxSize().background(theme.background)) {
            BackdropCapture(backdrop, Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize().testTag("album-list"), state = list,
                    contentPadding = PaddingValues(bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp + 20.dp)) {
                    item(key = "album-hero") {
                        AlbumHero(title, artist, page?.releaseDate, cover, accent ?: theme.accent,
                            tracks, savedIds, loading, top, scroll, refresh)
                    }
                    if (loading) items(5, key = { "album-loading:$it" }) { CatalogSkeletonRow() }
                    else if (tracks.isEmpty()) item {
                        EmptyState("Catalogue indisponible", "Vérifiez votre connexion, puis réessayez.", "Réessayer", onAction = onRetry)
                    }
                    itemsIndexed(tracks, key = { index, track -> "${track.id}:$index" }) { index, track ->
                        StaggeredReveal(order = index.coerceAtMost(5),
                            modifier = if (Motion.reduced) Modifier else Modifier.animateItem()) {
                            SwipeTrackActions(track) {
                                AlbumTrackRow(track, index, current.current?.id == track.id, track.id in savedIds) {
                                    Player.setShuffle(false)
                                    Player.playQueue(tracks, index)
                                }
                            }
                        }
                    }
                }
            }
            // Floating controls sample the scrolled page, not an opaque full-width header band.
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LiquidGlassButton({ AppUi.closeOverlay() }, Modifier.size(48.dp)) {
                    Icon(KetIcons.ArrowLeft, "Retour", tint = theme.onBackground)
                }
                Box(Modifier.weight(1f)) {
                    if (collapse.value > 0f) {
                        GlassPanel(Modifier.fillMaxWidth().graphicsLayer { alpha = collapse.value }
                            .testTag("album-collapsed-title"), RoundedCornerShape(24.dp), GlassParams.pill(dark = theme.isDark)) {
                            Text(title, localize = false, color = theme.onBackground, fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                }
                if (tracks.isNotEmpty()) {
                    Row(Modifier.graphicsLayer { alpha = collapse.value }
                        .then(if (collapse.value < .98f) Modifier.clearAndSetSemantics {} else Modifier),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AlbumGlassAction(KetIcons.Play, "Lecture", enabled = !loading && collapse.value >= .98f) {
                            Player.setShuffle(false); Player.playQueue(tracks)
                        }
                        AlbumGlassAction(KetIcons.Shuffle, "Aléatoire", enabled = !loading && collapse.value >= .98f) {
                            Player.playShuffled(tracks)
                        }
                    }
                }
                LiquidGlassButton({ ShareLinks.shareAlbum(context, request.id, title, artist) }, Modifier.size(48.dp)) {
                    Icon(KetIcons.Share, "Partager", tint = theme.onBackground)
                }
            }
        }
    }
}

@Composable
private fun AlbumHero(title: String, artist: String, date: String?, cover: String?, tone: Color,
                      tracks: List<Track>, savedIds: Set<String>, loading: Boolean,
                      top: androidx.compose.ui.unit.Dp, scroll: State<Float>, onRefresh: () -> Unit) {
    val theme = LocalTheme.current
    val density = LocalDensity.current
    val targetSurface = lerp(theme.background, tone, if (theme.isDark) .18f else .11f)
    val surface by androidx.compose.animation.animateColorAsState(targetSurface,
        tween(Motion.duration(420), easing = Motion.EASE_STANDARD), label = "album-colour-continuation")
    val heroBackdrop = rememberGlassBackdrop(theme.background)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = AlbumPresentation.sideBySide(maxWidth.value, density.fontScale)
        val artworkHeight = (maxWidth * .88f).coerceIn(260.dp, 380.dp) + top
        Box(Modifier.fillMaxWidth()) {
            // Only artwork and colour integration enter this local source. Text/actions are siblings.
            BackdropCapture(heroBackdrop, Modifier.matchParentSize().testTag("album-hero-backdrop")) {
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        AlbumArtwork(cover, tracks.firstOrNull(), surface,
                            Modifier.weight(.44f).fillMaxHeight(), scroll, wide = true)
                        Box(Modifier.weight(.56f).fillMaxHeight().background(
                            Brush.verticalGradient(0f to surface, .35f to surface, 1f to theme.background)))
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        AlbumArtwork(cover, tracks.firstOrNull(), surface,
                            Modifier.fillMaxWidth().height(artworkHeight), scroll)
                        Box(Modifier.fillMaxWidth().weight(1f).background(
                            Brush.verticalGradient(0f to surface, .38f to surface, 1f to theme.background)))
                    }
                }
            }
            CompositionLocalProvider(LocalBackdropLayer provides heroBackdrop) {
                if (wide) {
                    Row(Modifier.fillMaxWidth().heightIn(min = top + 320.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(.44f))
                        AlbumIdentity(title, artist, date, tracks, savedIds, loading, onRefresh,
                            Modifier.weight(.56f).padding(top = top, bottom = 48.dp))
                    }
                } else {
                    Column(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.height(artworkHeight - 12.dp))
                        AlbumIdentity(title, artist, date, tracks, savedIds, loading, onRefresh,
                            Modifier.padding(bottom = 20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumArtwork(cover: String?, track: Track?, surface: Color, modifier: Modifier,
                         scroll: State<Float>, wide: Boolean = false) {
    val theme = LocalTheme.current
    val context = LocalContext.current
    var ready by remember(cover) { mutableStateOf(false) }
    var failed by remember(cover) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (ready) 1f else 0f,
        tween(Motion.duration(420), easing = Motion.EASE_STANDARD), label = "albumArtworkReveal")
    val image = remember(context, cover) { coil.request.ImageRequest.Builder(context).data(cover).crossfade(false).build() }
    val canvasEnabled = AppSettings.albumHeroCanvas.value && !Motion.reduced && !AppSettings.highPerformanceMode.value
    val paths by CanvasRepo.paths.collectAsState()
    LaunchedEffect(track?.id, canvasEnabled) { if (canvasEnabled && track != null) CanvasRepo.resolve(track) }
    val canvas = track?.let { paths[it.id] }
    Box(modifier.clipToBounds().background(surface).testTag("album-hero-visual")) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationY = AlbumPresentation.artworkTranslation(scroll.value, size.height, Motion.reduced)
        }) {
            if (cover == null || failed) Icon(KetIcons.Album, null, tint = theme.secondary.copy(alpha = .32f),
                modifier = Modifier.size(80.dp).align(Alignment.Center))
            when {
                canvasEnabled && track != null && canvas != null -> com.ketvoice.app.ui.player.ArtworkCanvas(
                    track, canvas, playing = true, forceCanvas = true, modifier = Modifier.fillMaxSize())
                cover != null -> AsyncImage(image, null, contentScale = ContentScale.Crop,
                    onSuccess = { ready = true; failed = false }, onError = { failed = true },
                    modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }
                        .testTag(if (ready) "album-artwork-loaded" else "album-artwork-loading"))
            }
        }
        // A single image-colour transition, with zero slope at either end: no white fog or alpha mask.
        Box(Modifier.matchParentSize().drawWithCache {
            val bottom = Brush.verticalGradient(*(0..16).map { step ->
                val p = step / 16f
                (.64f + .36f * p) to (if (wide) theme.background else surface)
                    .copy(alpha = AlbumPresentation.smoothProgress(p))
            }.toTypedArray())
            val edge = if (wide) Brush.horizontalGradient(*(0..16).map { step ->
                val p = step / 16f
                (.78f + .22f * p) to surface.copy(alpha = AlbumPresentation.smoothProgress(p))
            }.toTypedArray()) else null
            onDrawBehind {
                edge?.let { drawRect(it) }
                drawRect(bottom)
            }
        })
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AlbumIdentity(title: String, artist: String, date: String?, tracks: List<Track>, savedIds: Set<String>,
                          loading: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val total = remember(tracks) { tracks.sumOf { it.durationMs.coerceAtLeast(0L) } }
    val duration = when {
        total <= 0L -> null
        total >= 3_600_000L -> AppText.t("${total / 3_600_000L} h ${(total % 3_600_000L) / 60_000L} min")
        else -> AppText.t("${(total / 60_000L).coerceAtLeast(1L)} min")
    }
    val count = AppText.t(if (tracks.size == 1) "1 titre" else "${tracks.size} titres")
    StaggeredReveal(order = 0, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Album", color = theme.secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
            Text(title, localize = false, color = theme.onBackground, fontSize = 36.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, lineHeight = 42.sp,
                modifier = Modifier.testTag("album-title"))
            if (artist.isNotBlank()) Text(artist, localize = false, color = theme.onBackground,
                fontSize = 17.sp, fontWeight = FontWeight.Medium, lineHeight = 23.sp,
                modifier = Modifier.clickable(role = Role.Button) { AppUi.openArtist(tracks.firstOrNull()?.artistId.orEmpty(), artist) }
                    .heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically).padding(vertical = 6.dp))
            if (!loading) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.testTag("album-metadata")) {
                    Text(listOfNotNull(date?.takeIf { it.isNotBlank() }, count, duration).joinToString("  ·  "),
                        localize = false, color = theme.secondary, fontSize = 13.sp, lineHeight = 19.sp)
                    if (tracks.any { it.explicit }) EBadge()
                }
            }
            AlbumPlaybackActions(tracks, savedIds, loading, onRefresh,
                Modifier.padding(top = 12.dp, bottom = 12.dp).widthIn(max = 560.dp).fillMaxWidth())
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AlbumPlaybackActions(tracks: List<Track>, savedIds: Set<String>, loading: Boolean,
                                 onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val enabled = !loading && tracks.isNotEmpty()
    val missing = remember(tracks, savedIds) { tracks.distinctBy { it.id }.filter { it.id !in savedIds && !it.id.startsWith("local:") } }
    BoxWithConstraints(modifier) {
        val stacked = maxWidth < 320.dp && LocalDensity.current.fontScale >= 1.2f
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = if (stacked) 1 else 2,
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AlbumPlayButton(Modifier.weight(1f), enabled) {
                    Player.setShuffle(false); Player.playQueue(tracks)
                }
                AlbumGlassLabel(KetIcons.Shuffle, "Aléatoire", Modifier.weight(1f), enabled) {
                    Player.playShuffled(tracks)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                AlbumGlassAction(if (missing.isEmpty() && enabled) KetIcons.Check else KetIcons.Download,
                    if (missing.isEmpty() && enabled) "Téléchargé" else "Télécharger",
                    enabled = enabled && missing.isNotEmpty(), tag = "album-download") {
                    missing.forEach { Downloader.download(it) }
                }
                AlbumGlassAction(KetIcons.Refresh, "Actualiser", enabled = !loading, tag = "album-refresh", onClick = onRefresh)
                Spacer(Modifier.weight(1f))
                Text("Titres", color = theme.secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(tracks.size.toString(), localize = false, color = theme.secondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun AlbumPlayButton(modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val theme = LocalTheme.current
    val foreground = if (theme.accent.luminance() > .179f) Color.Black else Color.White
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !Motion.reduced) .975f else 1f,
        Motion.pressSpring(pressed), label = "album-play-press")
    Row(modifier.heightIn(min = 60.dp).graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(RoundedCornerShape(30.dp)).background(theme.accent.copy(alpha = if (enabled) 1f else .35f))
        .clickable(interactions, null, enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 16.dp).testTag("album-play"),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(KetIcons.Play, null, tint = foreground, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text("Lecture", color = foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AlbumGlassLabel(icon: ImageVector, label: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val theme = LocalTheme.current
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !Motion.reduced) .975f else 1f,
        Motion.pressSpring(pressed), label = "album-secondary-press")
    Box(modifier) {
        GlassPanel(Modifier.matchParentSize().graphicsLayer { scaleX = scale; scaleY = scale },
            RoundedCornerShape(30.dp), GlassParams.pill(dark = theme.isDark)) {}
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(30.dp))
            .clickable(interactions, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else .45f }.padding(horizontal = 16.dp, vertical = 16.dp)
            .testTag("album-shuffle"), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = theme.onBackground, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = theme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AlbumGlassAction(icon: ImageVector, label: String, enabled: Boolean, tag: String? = null, onClick: () -> Unit) {
    val theme = LocalTheme.current
    LiquidGlassButton(onClick, Modifier.size(48.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier),
        shape = CircleShape, enabled = enabled) {
        Icon(icon, label, tint = theme.onBackground, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun AlbumTrackRow(track: Track, index: Int, active: Boolean, downloaded: Boolean, onPlay: () -> Unit) {
    val theme = LocalTheme.current
    val ink by androidx.compose.animation.animateColorAsState(if (active) theme.accent else theme.onBackground,
        tween(Motion.duration(180)), label = "album-track-state")
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp)
        .background(if (active) theme.accent.copy(alpha = .065f) else Color.Transparent)
        .trackRowClick(track, onClick = onPlay)
        .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            if (active) Icon(KetIcons.Music, null, tint = ink, modifier = Modifier.size(20.dp))
            else Text("${index + 1}", localize = false, color = theme.secondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        Column(Modifier.weight(1f).padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            TrackTitle(track.title, color = ink, selected = active)
            if (track.artist.isNotBlank()) Text(track.artist, localize = false, color = theme.secondary,
                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (LocalDensity.current.fontScale >= 1.3f && track.durationMs > 0)
                Text(formatSecs(track.durationMs), localize = false, color = theme.secondary, fontSize = 12.sp)
        }
        if (downloaded) Icon(KetIcons.Check, "Téléchargé", tint = theme.secondary,
            modifier = Modifier.padding(start = 8.dp).size(16.dp))
        if (LocalDensity.current.fontScale < 1.3f && track.durationMs > 0) Text(formatSecs(track.durationMs), localize = false,
            color = theme.secondary, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp))
        androidx.compose.material3.IconButton(onClick = { AppUi.trackMenuTrack.value = track }, modifier = Modifier.size(48.dp)) {
            Icon(KetIcons.More, "Options", tint = theme.secondary, modifier = Modifier.size(24.dp))
        }
    }
}
