package com.ketvoice.app.ui.sub

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.blur
import com.ketvoice.app.ui.common.CollectionTopBar
import com.ketvoice.app.ui.common.CollectionHero
import com.ketvoice.app.ui.common.trackRowClick
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Crop
import kotlinx.coroutines.launch
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.layout.layout
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
    val cover = com.ketvoice.app.data.MediaOverrides.albumCover(title, artist, page?.cover?.takeIf { it.isNotBlank() }
        ?: tracks.firstOrNull()?.let { com.ketvoice.app.data.MediaOverrides.cover(it, art["t:${it.id}"] ?: it.coverUrl) })
    val accent = com.ketvoice.app.theme.rememberCoverAccent(cover)?.first
    // Like the artist page: the page takes its colour from the cover, and the cover dissolves into it.
    val pageTint by androidx.compose.animation.animateColorAsState(
        artistPageTint(baseTheme.background, accent.takeIf { AppSettings.albumColors.value }, baseTheme.isDark),
        tween(Motion.duration(520)), label = "album-page-tint")
    val theme = baseTheme.copy(
        background = pageTint,
        card = androidx.compose.ui.graphics.lerp(pageTint, baseTheme.onBackground, if (baseTheme.isDark) .08f else .05f),
        accent = if (AppSettings.albumColors.value) accent ?: baseTheme.accent else baseTheme.accent)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
    val collapse = remember { derivedStateOf {
        val hero = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "album-hero" }
        AlbumPresentation.collapse(hero?.offset, hero?.size ?: 0, list.firstVisibleItemIndex)
    } }
    val scroll = remember { derivedStateOf {
        if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat() else 0f
    } }
    val backdrop = rememberGlassBackdrop(theme.background)
    // The owner's framing of the hero, as every listener sees it, and the draft while it is being adjusted.
    val mainArtist = com.ketvoice.app.data.MediaOverrides.mainArtist(artist)
    val savedFrame = com.ketvoice.app.data.MediaOverrides.albumFrame(title, mainArtist)
    var draft by remember(title, artist) { mutableStateOf<com.ketvoice.app.data.Frame?>(null) }
    var framingBusy by remember { mutableStateOf(false) }
    var framingError by remember { mutableStateOf("") }
    val framingScope = rememberCoroutineScope()
    androidx.activity.compose.BackHandler(enabled = draft != null) { if (!framingBusy) draft = null }
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
                        AlbumHero(title, artist, page?.releaseDate, cover, tracks, savedIds, loading, top, refresh,
                            frame = draft ?: savedFrame, onFrame = if (draft != null) ({ draft = it }) else null)
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
            draft?.let { current ->
                AlbumFramingBar(current, framingBusy, framingError, Modifier.align(Alignment.BottomCenter),
                    onChange = { draft = it }, onCancel = { draft = null }, onPublish = {
                        val target = com.ketvoice.app.data.MediaOverrides.albumKey(title, mainArtist) ?: return@AlbumFramingBar
                        framingBusy = true; framingError = ""
                        framingScope.launch {
                            try {
                                if (current.covered().isIdentity) com.ketvoice.app.data.PublicationApi.saveOverride(
                                    com.ketvoice.app.data.MediaOverrides.ALBUM, target, "$title — $mainArtist", restore = listOf("frame"))
                                else com.ketvoice.app.data.PublicationApi.saveOverride(com.ketvoice.app.data.MediaOverrides.ALBUM, target,
                                    "$title — $mainArtist", frame = current.covered())
                                draft = null
                            } catch (failure: Exception) {
                                framingError = failure.message?.take(200) ?: "Publication impossible"
                            } finally { framingBusy = false }
                        }
                    })
            }
            // Floating controls sample the scrolled page, not an opaque full-width header band.
            CollectionTopBar(title, collapse.value, { AppUi.closeOverlay() },
                onPlay = if (tracks.isNotEmpty() && !loading) ({ Player.setShuffle(false); Player.playQueue(tracks) }) else null,
                titleTag = "album-collapsed-title") {
                val publisher by com.ketvoice.app.data.PublisherSession.allowed.collectAsState()
                if (publisher && title.isNotBlank() && artist.isNotBlank()) {
                    LiquidGlassButton({
                        val original = page?.cover?.takeIf { it.isNotBlank() } ?: tracks.firstOrNull()?.let { art["t:${it.id}"] ?: it.coverUrl }
                        val main = com.ketvoice.app.data.MediaOverrides.mainArtist(artist)
                        AppUi.openVisuals(listOf(com.ketvoice.app.data.VisualTarget.album(title, main, original, tracks.firstOrNull()),
                            com.ketvoice.app.data.VisualTarget.artist(main, null)))
                    }, Modifier.size(48.dp).testTag("album-visuals")) {
                        Icon(KetIcons.Appearance, "Modifier les visuels", tint = theme.onBackground)
                    }
                    Spacer(Modifier.width(8.dp))
                    // Framing: zoom and move the cover or Canvas right on this page, then publish it for everyone.
                    LiquidGlassButton({ framingError = ""; draft = savedFrame ?: com.ketvoice.app.data.Frame() },
                        Modifier.size(48.dp).testTag("album-framing"), enabled = draft == null) {
                        Icon(androidx.compose.material.icons.Icons.Filled.Crop, "Cadrer l'image", tint = theme.onBackground)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                LiquidGlassButton({ ShareLinks.shareAlbum(context, request.id, title, artist) }, Modifier.size(48.dp)) {
                    Icon(KetIcons.Share, "Partager", tint = theme.onBackground)
                }
            }
        }
    }
}

/** The shared collection header: blurred cover, floating artwork, centred identity. */
@Composable
private fun AlbumHero(title: String, artist: String, date: String?, cover: String?,
                      tracks: List<Track>, savedIds: Set<String>, loading: Boolean,
                      top: androidx.compose.ui.unit.Dp, onRefresh: () -> Unit,
                      frame: com.ketvoice.app.data.Frame? = null, onFrame: ((com.ketvoice.app.data.Frame) -> Unit)? = null) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val total = remember(tracks) { tracks.sumOf { it.durationMs.coerceAtLeast(0L) } }
    val duration = when {
        total <= 0L -> null
        total >= 3_600_000L -> AppText.t("${total / 3_600_000L} h ${(total % 3_600_000L) / 60_000L} min")
        else -> AppText.t("${(total / 60_000L).coerceAtLeast(1L)} min")
    }
    val count = AppText.t(if (tracks.size == 1) "1 titre" else "${tracks.size} titres")
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val beside = AlbumPresentation.sideBySide(maxWidth.value, density.fontScale)
        val stacked = maxWidth < 320.dp && density.fontScale >= 1.2f
        val meta = if (loading) null else listOfNotNull(date?.takeIf { it.isNotBlank() }, count, duration).joinToString("  ·  ")
        // A wide pane keeps the artwork beside the text; a phone opens on the artwork itself.
        if (beside) CollectionHero(cover, top, title, eyebrow = "Album", titleTag = "album-title",
            subtitle = artist.takeIf { it.isNotBlank() },
            onSubtitle = { AppUi.openArtist(tracks.firstOrNull()?.artistId.orEmpty(), artist) },
            meta = meta, explicit = !loading && tracks.any { it.explicit }, beside = true, artworkTag = "album-hero-visual",
            artwork = { AlbumArtwork(cover, tracks.firstOrNull(), frame, onFrame) }) {
            AlbumPlaybackActions(tracks, savedIds, loading, stacked, onRefresh, Modifier.widthIn(max = 560.dp).fillMaxWidth())
        } else {
            val theme = LocalTheme.current
            val window = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.toFloat()
            val height = AlbumPresentation.heroHeight(maxWidth.value, window).dp
            val whole = AlbumPresentation.showsWholeCover(maxWidth.value, height.value)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(height).clipToBounds().testTag("album-hero-visual")) {
                    if (whole) {
                        // Too wide for a square: the cover stays whole over a soft, enlarged copy of itself.
                        if (cover != null) AsyncImage(remember(cover) {
                            coil.request.ImageRequest.Builder(context).data(cover).size(40).crossfade(false).build()
                        }, null, contentScale = ContentScale.Crop, filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
                            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.2f; scaleY = 1.2f; alpha = .75f }
                                .blur(40.dp))
                        Box(Modifier.size(height).align(Alignment.Center).clipToBounds()) { AlbumArtwork(cover, tracks.firstOrNull(), frame, onFrame) }
                    } else AlbumArtwork(cover, tracks.firstOrNull(), frame, onFrame)
                    // The floating buttons stay readable over a bright cover.
                    Box(Modifier.fillMaxWidth().height(96.dp).background(Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = .28f), Color.Transparent))))
                    // A long dissolve into the page colour: no line where the cover ends.
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height * .46f).background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            .24f to theme.background.copy(alpha = .10f),
                            .52f to theme.background.copy(alpha = .46f),
                            .80f to theme.background.copy(alpha = .86f),
                            1f to theme.background)))
                }
                // Compact identity block: title, artist and details read as one group.
                Column(Modifier.fillMaxWidth().pullUp(18.dp).padding(start = 24.dp, end = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Album", color = theme.secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
                    Text(title, localize = false, color = theme.onBackground, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = (-.7).sp, lineHeight = 34.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.testTag("album-title"))
                    if (artist.isNotBlank()) Text(artist, localize = false, color = theme.accent, fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) { AppUi.openArtist(tracks.firstOrNull()?.artistId.orEmpty(), artist) }
                            .heightIn(min = 44.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 8.dp))
                    if (!meta.isNullOrBlank()) Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("album-metadata")) {
                        Text(meta, localize = false, color = theme.secondary, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
                        if (tracks.any { it.explicit }) EBadge()
                    }
                    Spacer(Modifier.height(8.dp))
                    AlbumPlaybackActions(tracks, savedIds, loading, stacked, onRefresh, Modifier.widthIn(max = 560.dp).fillMaxWidth())
                }
            }
        }
    }
}

/** The cover, or its Canvas when the listener enabled it, inside the floating frame. */
@Composable
private fun BoxScope.AlbumArtwork(cover: String?, track: Track?, frame: com.ketvoice.app.data.Frame? = null,
                                  onFrame: ((com.ketvoice.app.data.Frame) -> Unit)? = null) {
    // Pinch and drag while the owner is framing; everyone else sees the published framing.
    val shown = (frame ?: com.ketvoice.app.data.Frame()).covered()
    val latest by rememberUpdatedState(shown)
    val framing = Modifier.graphicsLayer {
        scaleX = shown.scale; scaleY = shown.scale
        translationX = shown.x * size.width; translationY = shown.y * size.height
    }
    if (onFrame != null) Box(Modifier.matchParentSize().zIndex(2f).testTag("album-framing-surface").pointerInput(Unit) {
        detectTransformGestures { _, pan, zoom, _ ->
            val f = latest
            onFrame(f.copy(scale = f.scale * zoom, x = f.x + pan.x / size.width, y = f.y + pan.y / size.height).covered())
        }
    })
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
    if (cover == null || failed) Icon(KetIcons.Album, null, tint = theme.secondary.copy(alpha = .32f),
        modifier = Modifier.fillMaxSize(.42f).align(Alignment.Center))
    when {
        canvasEnabled && track != null && canvas != null -> com.ketvoice.app.ui.player.ArtworkCanvas(
            track, canvas, playing = true, forceCanvas = true, modifier = Modifier.fillMaxSize().then(framing))
        cover != null -> AsyncImage(image, null, contentScale = ContentScale.Crop,
            onSuccess = { ready = true; failed = false }, onError = { failed = true },
            modifier = Modifier.fillMaxSize().then(framing).graphicsLayer { this.alpha = alpha }
                .testTag(if (ready) "album-artwork-loaded" else "album-artwork-loading"))
    }
}

@Composable
private fun AlbumPlaybackActions(tracks: List<Track>, savedIds: Set<String>, loading: Boolean, stacked: Boolean,
                                 onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val enabled = !loading && tracks.isNotEmpty()
    val missing = remember(tracks, savedIds) { tracks.distinctBy { it.id }.filter { it.id !in savedIds && !it.id.startsWith("local:") } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        com.ketvoice.app.ui.common.CollectionActionPair({ Player.setShuffle(false); Player.playQueue(tracks) },
            { Player.playShuffled(tracks) }, enabled = enabled, playTag = "album-play", shuffleTag = "album-shuffle",
            stacked = stacked)
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
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp)
        .background(if (active) theme.accent.copy(alpha = .065f) else Color.Transparent)
        .trackRowClick(track, onClick = onPlay)
        .padding(start = 20.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            if (active) com.ketvoice.app.ui.player.NowPlayingBars(
                com.ketvoice.app.player.Player.state.collectAsState().value.isPlaying)
            else Text("${index + 1}", localize = false, color = theme.secondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, top = 2.dp, bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
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

/** Moves the content up into the space above it and gives that height back, so nothing is left empty below. */
private fun Modifier.pullUp(by: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shift = by.roundToPx().coerceAtMost(placeable.height)
    layout(placeable.width, placeable.height - shift) { placeable.place(0, -shift) }
}

/** The owner's framing controls: zoom, reset, cancel and publish, over the real album page. */
@Composable
private fun AlbumFramingBar(frame: com.ketvoice.app.data.Frame, busy: Boolean, error: String, modifier: Modifier,
                            onChange: (com.ketvoice.app.data.Frame) -> Unit, onCancel: () -> Unit, onPublish: () -> Unit) {
    val theme = LocalTheme.current
    // A solid sheet: its glass buttons sample the page behind it, so the sheet itself is not glass.
    Box(modifier.navigationBarsPadding().padding(12.dp).widthIn(max = 560.dp).fillMaxWidth()
        .clip(RoundedCornerShape(28.dp)).background(theme.card.copy(alpha = .96f))
        .border(.8.dp, theme.onBackground.copy(alpha = .10f), RoundedCornerShape(28.dp))
        .testTag("album-framing-bar")) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Cadrage de l'album", color = theme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Text("${(frame.scale * 100).toInt()} %", localize = false, color = theme.secondary, fontSize = 13.sp)
            }
            Text("Pincez et faites glisser l'image. Ce cadrage s'affiche pour tout le monde.", color = theme.secondary, fontSize = 12.sp)
            androidx.compose.material3.Slider(frame.scale, { onChange(frame.copy(scale = it).covered()) }, valueRange = 1f..3f,
                enabled = !busy, modifier = Modifier.testTag("album-framing-zoom"),
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = theme.accent, activeTrackColor = theme.accent))
            if (error.isNotBlank()) Text(error, localize = false, color = androidx.compose.material3.MaterialTheme.colorScheme.error, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.ketvoice.app.ui.common.GlassCapsuleButton("Rétablir", null, { onChange(com.ketvoice.app.data.Frame()) },
                    Modifier.weight(1f), enabled = !busy, tag = "album-framing-reset", minHeight = 48.dp)
                com.ketvoice.app.ui.common.GlassCapsuleButton("Annuler", null, onCancel, Modifier.weight(1f), enabled = !busy,
                    tag = "album-framing-cancel", minHeight = 48.dp)
                com.ketvoice.app.ui.common.GlassCapsuleButton(if (busy) "Envoi…" else "Publier", null, onPublish, Modifier.weight(1f),
                    prominent = true, enabled = !busy, tag = "album-framing-publish", minHeight = 48.dp)
            }
        }
    }
}
