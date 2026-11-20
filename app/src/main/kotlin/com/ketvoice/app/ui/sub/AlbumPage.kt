package com.ketvoice.app.ui.sub

import androidx.compose.ui.input.nestedscroll.nestedScroll

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
import kotlin.math.roundToInt
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
    // The Canvas the hero plays, when this listener has it on: what the framing screen works on.
    val heroCanvasOn = AppSettings.albumHeroCanvas.value && !Motion.reduced
    val canvasPaths by CanvasRepo.paths.collectAsState()
    // The titles of this page belong to the album the page keys its visuals by: what is saved here
    // reaches them in the player too, whatever album name and artist each title carries.
    val pageAlbumKey = com.ketvoice.app.data.MediaOverrides.albumKey(title, com.ketvoice.app.data.MediaOverrides.mainArtist(artist))
    LaunchedEffect(pageAlbumKey, tracks) { if (pageAlbumKey != null && tracks.isNotEmpty()) com.ketvoice.app.data.MediaOverrides.linkAlbum(tracks, pageAlbumKey) }
    val framingCanvas = tracks.firstOrNull()?.takeIf { !com.ketvoice.app.data.MediaOverrides.canvasDisabled(it) }?.let { canvasPaths[it.id] }.takeIf { heroCanvasOn }
    // 7.3: the Canvas the header shows, only when the listener chose a Canvas style (ui/sub/AlbumHeader).
    // 7.5 motion75: remembered under the page's id too, so that a second opening shows it before the titles arrive.
    val heroCanvas = rememberHeaderCanvas(tracks.firstOrNull(), request.id.takeIf { it.isNotBlank() })
    val pull = rememberHeaderPull()
    // The page takes its colour from what its hero shows: the Canvas while one plays there (the
    // cover's until a frame of it is read), the cover as soon as the Canvas is off or gone.
    val coverAccent = com.ketvoice.app.theme.rememberCoverAccent(cover)?.first
    val canvasAccent = com.ketvoice.app.theme.rememberCanvasAccent(heroCanvas)?.first
    val accent = AlbumPresentation.pageAccent(heroCanvas != null, canvasAccent, coverAccent)
    // The page itself takes the colour the artwork is mostly made of; the vivid accent is for buttons and links.
    val tone = AlbumPresentation.pageAccent(heroCanvas != null, com.ketvoice.app.theme.rememberCanvasTone(heroCanvas),
        com.ketvoice.app.theme.rememberCoverTone(cover))
    // Like the artist page: the page takes its colour from its artwork, and the artwork dissolves into it.
    val pageTint by androidx.compose.animation.animateColorAsState(
        artistPageTint(baseTheme.background, (tone ?: accent).takeIf { AppSettings.albumColors.value }, baseTheme.isDark),
        tween(Motion.duration(520)), label = "album-page-tint")
    val pageCard = androidx.compose.ui.graphics.lerp(pageTint, baseTheme.onBackground, if (baseTheme.isDark) .08f else .05f)
    // 7.3: the cover's colour for Play, Shuffle, links, marks and selection, always readable on this page.
    val theme = baseTheme.copy(
        background = pageTint,
        card = pageCard,
        accent = if (AppSettings.albumColors.value) com.ketvoice.app.theme.CoverLegibility.pageAccent(accent, baseTheme.accent, pageTint, pageCard)
            else baseTheme.accent)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
    // 7.5 motion75: over the motion artwork the bar takes the title once the large title has passed under it.
    val density = LocalDensity.current
    val screen = androidx.compose.ui.platform.LocalConfiguration.current
    val motionHeader = heroCanvas != null && AlbumHeader.style(AppSettings.albumHeader.value, true) == HeaderStyle.Canvas &&
        !AlbumPresentation.sideBySide(screen.screenWidthDp.toFloat(), density.fontScale)
    // Only the motion artwork zooms when pulled; a cover page keeps the usual overscroll.
    pull.enabled = motionHeader && !Motion.reduced
    val titlePassesPx = with(density) {
        (AlbumHeader.motionHeight(screen.screenWidthDp.toFloat(), screen.screenHeightDp.toFloat()).dp - MOTION_WORDS_OVERLAP - top).toPx() }
    val collapse = remember(motionHeader, titlePassesPx) { derivedStateOf {
        val hero = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "album-hero" }
        if (motionHeader) AlbumHeader.titleCollapse(hero?.offset, titlePassesPx, with(density) { 44.dp.toPx() }, list.firstVisibleItemIndex)
        else AlbumPresentation.collapse(hero?.offset, hero?.size ?: 0, list.firstVisibleItemIndex)
    } }
    val scroll = remember { derivedStateOf {
        if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat() else 0f
    } }
    val backdrop = rememberGlassBackdrop(theme.background)
    // The owner's framing of the hero, as every listener sees it, and the draft while it is being adjusted.
    val mainArtist = com.ketvoice.app.data.MediaOverrides.mainArtist(artist)
    val savedFrame = com.ketvoice.app.data.MediaOverrides.albumFrame(title, mainArtist)
    var draft by remember(title, artist) { mutableStateOf<com.ketvoice.app.data.Frame?>(null) }
    // The player's own framing of this album: published, and the draft while it waits to be.
    val savedPlayerFrame = com.ketvoice.app.data.MediaOverrides.album(title, mainArtist)?.playerFrame
    var playerDraft by remember(title, artist) { mutableStateOf<com.ketvoice.app.data.Frame?>(null) }
    var framingMode by remember(title, artist) { mutableStateOf(FramingScope.Page) }
    var framingOpen by remember(title, artist) { mutableStateOf(false) }
    var framingBusy by remember { mutableStateOf(false) }
    var framingError by remember { mutableStateOf("") }
    val framingScope = rememberCoroutineScope()
    /** Sends the page's or the player's framing (or gives it back to the default) and clears the draft once the server has it. */
    fun publishFraming(frame: com.ketvoice.app.data.Frame, forPlayer: Boolean, restore: Boolean) {
        val target = com.ketvoice.app.data.MediaOverrides.albumKey(title, mainArtist) ?: return
        framingBusy = true; framingError = ""
        framingScope.launch {
            try {
                val api = com.ketvoice.app.data.PublicationApi
                val label = "$title — $mainArtist"
                when {
                    forPlayer -> api.saveOverride(com.ketvoice.app.data.MediaOverrides.ALBUM, target, label,
                        player = if (restore) com.ketvoice.app.data.PublicationApi.PlayerChange(followFrame = true)
                            else com.ketvoice.app.data.PublicationApi.PlayerChange(frame = frame))
                    restore -> api.saveOverride(com.ketvoice.app.data.MediaOverrides.ALBUM, target, label, restore = listOf("frame"))
                    else -> api.saveOverride(com.ketvoice.app.data.MediaOverrides.ALBUM, target, label, frame = frame)
                }
                draft = null; playerDraft = null
            } catch (failure: Exception) {
                framingError = failure.message?.take(200) ?: "Publication impossible"
            } finally { framingBusy = false }
        }
    }
    androidx.activity.compose.BackHandler(enabled = (draft != null || playerDraft != null) && !framingOpen) {
        if (!framingBusy) { draft = null; playerDraft = null }
    }
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val pageBoxAspect = configuration.screenWidthDp.coerceAtLeast(1) /
        AlbumPresentation.heroHeight(configuration.screenWidthDp.toFloat(), configuration.screenHeightDp.toFloat())
    val playerBoxAspect = AlbumPresentation.playerArtworkAspect(configuration.screenWidthDp.toFloat(), configuration.screenHeightDp.toFloat())
    val refresh = {
        ArtworkRepo.invalidate(tracks)
        ArtworkRepo.enrich(tracks)
        onRetry()
    }
    // The artist's other releases, from the artist page the catalogue gives (cached with it, data/ArtistPages).
    val artistId = tracks.firstOrNull()?.artistId.orEmpty()
    val moreBy by produceState(emptyList<Album>(), artistId, mainArtist, loading, request.id) {
        if (loading || mainArtist.isBlank()) return@produceState
        // Only a catalogue album (YouTube Music or Deezer) asks the network; others use what is already cached.
        val catalogue = request.id.startsWith("MPRE") || (request.id.isNotEmpty() && request.id.all(Char::isDigit))
        val artistPage = ArtistPages.cachedOrFetch(artistId, mainArtist, network = catalogue)
        value = AlbumPresentation.moreBy(artistPage?.albums.orEmpty(), request.id, title)
    }
    CompositionLocalProvider(LocalTheme provides theme, LocalBackdropLayer provides backdrop,
        *com.ketvoice.app.theme.coverAccentLocals(theme.accent)) {
        Box(Modifier.fillMaxSize().background(theme.background).nestedScroll(pull)) {
            BackdropCapture(backdrop, Modifier.fillMaxSize()) {
                // Wide: the artwork stands beside the title and the titles take the whole width below (ui/adaptive/CollectionPanes).
                com.ketvoice.app.ui.adaptive.CollectionPanes(Modifier.fillMaxSize(), list, "album-list",
                    contentPadding = PaddingValues(bottom = LocalAdaptiveLayout.current.bottomPaddingDp.dp + 20.dp),
                    headerKey = "album-hero", header = {
                        AlbumHero(title, artist, page?.releaseDate, cover, tracks, savedIds, loading, top, refresh,
                            frame = draft ?: savedFrame, canvas = heroCanvas, scroll = { scroll.value }, pull = { pull.value })
                    }) {
                    if (loading) items(5, key = { "album-loading:$it" }) { CatalogSkeletonRow(it) }
                    else if (tracks.isEmpty()) item {
                        EmptyState("Catalogue indisponible", "Vérifiez votre connexion, puis réessayez.", "Réessayer", icon = com.ketvoice.app.ui.common.KetIcons.Wifi, onAction = onRetry)
                    }
                    itemsIndexed(tracks, key = { index, track -> "${track.id}:$index" }) { index, track ->
                        StaggeredReveal(order = index.coerceAtMost(5),
                            modifier = if (Motion.reduced) Modifier else Modifier.animateItem()) {
                            SwipeTrackActions(track) {
                                AlbumTrackRow(track, index, current.current?.id == track.id, track.id in savedIds, mainArtist) {
                                    Player.setShuffle(false)
                                    Player.playQueue(tracks, index)
                                }
                            }
                        }
                    }
                    // The foot of the album, as Apple Music closes it: the release date, then its titles and length.
                    if (!loading && tracks.isNotEmpty()) item(key = "album-footer") { AlbumFooter(page?.releaseDate, tracks) }
                    // 7.2, as Spotify closes an album: titles of the same artist and style, asked once this comes near.
                    if (!loading && tracks.isNotEmpty()) item(key = "album-more-like-this", contentType = "more-like-this") {
                        MoreLikeThisSection("album:" + request.id, tracks, fromPlaylist = false)
                    }
                    // « Plus de … »: the artist's other releases, when the catalogue knows them.
                    if (moreBy.isNotEmpty()) item(key = "album-more") {
                        val wideRows = com.ketvoice.app.ui.adaptive.LocalLargeScreen.current != null
                        ReleaseShelf(AppText.t("Plus de $mainArtist"), moreBy, 20.dp, if (wideRows) 168.dp else 150.dp,
                            "album-more", localizeTitle = false) { ArtistDiscography.caption(it, singles = true) }
                    }
                }
            }
            // After the framing screen the framing is published at once: what the page shows is what everyone
            // sees. Only a failed sending leaves the draft on the page, with its error and a way to try again.
            (draft ?: playerDraft)?.let { current ->
                // One draft at a time: the page's, shown on the page itself, or the player's.
                val forPlayer = draft == null
                fun send(restore: Boolean) = publishFraming(current, forPlayer, restore)
                if (!framingOpen) AlbumFramingBar(framingBusy, framingError, if (forPlayer) savedPlayerFrame != null else savedFrame != null,
                    Modifier.align(Alignment.BottomCenter), forPlayer = forPlayer,
                    onEdit = { framingMode = if (forPlayer) FramingScope.Player else FramingScope.Page; framingOpen = true },
                    onCancel = { draft = null; playerDraft = null }, onDefault = { send(restore = true) },
                    onPublish = { send(restore = false) })
            }
            // Floating controls sample the scrolled page, not an opaque full-width header band. Once the hero has
            // scrolled away the album's name settles in the middle of the bar over the page's faded edge.
            val publisher by com.ketvoice.app.data.PublisherSession.allowed.collectAsState()
            val owner = publisher && title.isNotBlank() && artist.isNotBlank()
            val canPlay = tracks.isNotEmpty() && !loading
            ScrollEdgeTitle(title, { collapse.value }, Modifier.align(Alignment.TopCenter), tag = "album-collapsed-title",
                localize = false, sidePadding = barSidePadding(if (owner) 3 else 1, canPlay), textTop = 8.dp)
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassBackButton { AppUi.closeOverlay() }
                Spacer(Modifier.weight(1f))
                if (canPlay) Box(Modifier.graphicsLayer { alpha = collapse.value }
                    .then(if (collapse.value < .98f) Modifier.clearAndSetSemantics {} else Modifier)) {
                    LiquidGlassButton({ Player.setShuffle(false); Player.playQueue(tracks) }, Modifier.size(44.dp),
                        enabled = collapse.value >= .98f) {
                        Icon(KetIcons.Play, "Lecture", tint = theme.accent, modifier = Modifier.size(20.dp))
                    }
                }
                // Visuals and framing (verified owner) sit with Share in one capsule of glass.
                com.ketvoice.app.ui.common.GlassButtonPad(height = 44.dp, actions = listOfNotNull(
                    com.ketvoice.app.ui.common.PadAction(KetIcons.Appearance, "Modifier les visuels", "album-visuals") {
                        val original = page?.cover?.takeIf { it.isNotBlank() } ?: tracks.firstOrNull()?.let { art["t:${it.id}"] ?: it.coverUrl }
                        val main = com.ketvoice.app.data.MediaOverrides.mainArtist(artist)
                        AppUi.openVisuals(listOf(com.ketvoice.app.data.VisualTarget.album(title, main, original, tracks.firstOrNull(), tracks),
                            com.ketvoice.app.data.VisualTarget.artist(main, null)))
                    }.takeIf { owner },
                    com.ketvoice.app.ui.common.PadAction(androidx.compose.material.icons.Icons.Filled.Crop, "Cadrer l'image", "album-framing",
                        enabled = !framingBusy) { framingError = ""; framingMode = FramingScope.Page; framingOpen = true }.takeIf { owner },
                    com.ketvoice.app.ui.common.PadAction(KetIcons.Share, "Partager", "album-share") {
                        ShareLinks.shareAlbum(context, request.id, title, artist)
                    }), tag = "album-actions")
            }
            // The framing screen covers everything, the floating bar and mini-player included.
            if (framingOpen) androidx.compose.ui.window.Dialog({ framingOpen = false },
                androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                FramingEditor(tracks.firstOrNull(), cover, framingCanvas ?: heroCanvas, draft ?: savedFrame, pageBoxAspect,
                    onCancel = { framingOpen = false },
                    onDone = { draft = it; playerDraft = null; framingOpen = false; publishFraming(it, forPlayer = false, restore = false) },
                    playerInitial = playerDraft ?: savedPlayerFrame, playerBoxAspect = playerBoxAspect, startScope = framingMode,
                    onPlayerDone = { playerDraft = it; draft = null; framingOpen = false; publishFraming(it, forPlayer = true, restore = false) })
            }
        }
    }
}

/** The shared collection header: blurred cover, floating artwork, centred identity. */
@Composable
private fun AlbumHero(title: String, artist: String, date: String?, cover: String?,
                      tracks: List<Track>, savedIds: Set<String>, loading: Boolean,
                      top: androidx.compose.ui.unit.Dp, onRefresh: () -> Unit,
                      frame: com.ketvoice.app.data.Frame? = null, canvas: String? = null,
                      scroll: () -> Float = { 0f }, pull: () -> Float = { 0f }) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val duration = remember(tracks) { AlbumPresentation.totalDuration(tracks) }?.let(AppText::t)
    val count = AppText.t(if (tracks.size == 1) "1 titre" else "${tracks.size} titres")
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val beside = AlbumPresentation.sideBySide(maxWidth.value, density.fontScale)
        val stacked = maxWidth < 320.dp && density.fontScale >= 1.2f
        val meta = if (loading) null else listOfNotNull(date?.takeIf { it.isNotBlank() }, count, duration).joinToString("  ·  ")
        // A wide pane keeps the artwork beside the text; a phone opens on the artwork itself.
        val style = AlbumHeader.style(AppSettings.albumHeader.value, canvas != null)
        // The cover's own colour for its soft shadow: the vivid accent of the picture, else its main tone.
        val shadow = com.ketvoice.app.theme.rememberCoverAccent(cover)?.first ?: com.ketvoice.app.theme.rememberCoverTone(cover)
        // A wide pane keeps the artwork beside the text (a chosen Canvas plays in its frame); a phone centres the
        // cover, or opens on the Canvas itself.
        if (beside) CollectionHero(cover, top, title, eyebrow = "Album", titleTag = "album-title",
            subtitle = artist.takeIf { it.isNotBlank() },
            onSubtitle = { AppUi.openArtist(tracks.firstOrNull()?.artistId.orEmpty(), artist) },
            meta = meta, explicit = !loading && tracks.any { it.explicit }, beside = true, artworkTag = "album-hero-visual",
            artwork = { AlbumArtwork(cover, tracks.firstOrNull(), frame, canvas) }) {
            AlbumPlaybackActions(tracks, savedIds, loading, stacked, onRefresh, Modifier.widthIn(max = 560.dp).fillMaxWidth())
        } else if (style != HeaderStyle.Canvas || canvas == null) Box(Modifier.fillMaxWidth().testTag("album-header-cover")) {
            CollectionHero(cover, top, title, eyebrow = "Album", titleTag = "album-title",
                subtitle = artist.takeIf { it.isNotBlank() },
                onSubtitle = { AppUi.openArtist(tracks.firstOrNull()?.artistId.orEmpty(), artist) },
                meta = meta, explicit = !loading && tracks.any { it.explicit }, artworkTag = "album-hero-visual",
                shadowTint = shadow, ambient = true,
                artwork = { AlbumArtwork(cover, tracks.firstOrNull(), null, null) }) {
                AlbumPlaybackActions(tracks, savedIds, loading, stacked, onRefresh, Modifier.widthIn(max = 560.dp).fillMaxWidth())
            }
        } else {
            val theme = LocalTheme.current
            val window = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.toFloat()
            // 7.5 motion75, as Apple Music's motion artwork: the Canvas is the header, edge to edge from the top of the
            // screen (the owner's framing applies), its foot dissolving into the page colour under the words.
            val height = AlbumHeader.motionHeight(maxWidth.value, window).dp
            Column(Modifier.fillMaxWidth().testTag("album-header-canvas"), horizontalAlignment = Alignment.CenterHorizontally) {
                MotionHeaderArtwork(canvas, height, playing = true, scroll = scroll, pull = pull, frame = frame, cover = cover,
                    modifier = Modifier.testTag("album-hero-visual"))
                // Compact identity block: title, artist and details read as one group.
                // The words sit on the Canvas's fade, as the Canvas reaches the top of the screen.
                Column(Modifier.fillMaxWidth().pullUp(MOTION_WORDS_OVERLAP).padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, localize = false, color = theme.onBackground, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = (-.8).sp, lineHeight = 34.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.testTag("album-title"))
                    if (artist.isNotBlank()) Text(artist, localize = false, color = theme.accent, fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.clip(RoundedCornerShape(10.dp))
                            .clickable(role = Role.Button) { AppUi.openArtist(tracks.firstOrNull()?.artistId.orEmpty(), artist) }
                            .padding(horizontal = 8.dp, vertical = 2.dp))
                    if (!meta.isNullOrBlank()) Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("album-metadata")) {
                        Text(meta, localize = false, color = theme.secondary, fontSize = 13.sp, lineHeight = 17.sp, textAlign = TextAlign.Center)
                        if (tracks.any { it.explicit }) EBadge()
                    }
                    Spacer(Modifier.height(12.dp))
                    AlbumPlaybackActions(tracks, savedIds, loading, stacked, onRefresh, Modifier.widthIn(max = 560.dp).fillMaxWidth())
                }
            }
        }
    }
}

/** How far the title, artist and details reach up over the motion artwork's fade (7.5 motion75). */
private val MOTION_WORDS_OVERLAP = 112.dp

/** The cover, or its Canvas when the listener enabled it, inside the floating frame. */
@Composable
private fun BoxScope.AlbumArtwork(cover: String?, track: Track?, frame: com.ketvoice.app.data.Frame? = null, canvas: String? = null) {
    val theme = LocalTheme.current
    val context = LocalContext.current
    var ready by remember(cover) { mutableStateOf(false) }
    var failed by remember(cover) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (ready) 1f else 0f,
        tween(Motion.duration(420), easing = Motion.EASE_STANDARD), label = "albumArtworkReveal")
    val image = remember(context, cover) { coil.request.ImageRequest.Builder(context).data(cover).crossfade(false).build() }
    // The Canvas comes resolved from the page (ui/sub/AlbumHeader), only when the listener chose a Canvas style.
    val canvasEnabled = AlbumHeader.wantsCanvas(AppSettings.albumHeroCanvas.value, AppSettings.albumHeader.value, Motion.reduced)
    val video = track != null && canvas != null
    // Width over height of what is drawn, once known; a Canvas is upright and a cover square until then.
    var aspect by remember(cover, canvas, video) { mutableFloatStateOf(0f) }
    // The owner's framing: the chosen rectangle of the picture fills the hero. The whole picture is
    // drawn at its own size, centred, and then moved: a picture cropped to the hero first would
    // leave empty bands wherever the rectangle reaches past what the hero shows.
    val shape = if (aspect > 0f) aspect else if (video) 9f / 16f else 1f
    val framing = if (frame == null) Modifier.fillMaxSize()
        else Modifier.fillMaxSize().framedPicture(frame, shape) { if (video) 1f else alpha }
    if (cover == null || failed) Icon(KetIcons.Album, null, tint = theme.secondary.copy(alpha = .32f),
        modifier = Modifier.fillMaxSize(.42f).align(Alignment.Center))
    when {
        video -> MotionArtwork(canvas!!, playing = true, modifier = Modifier.fillMaxSize(), frame = frame, cover = cover,
            onAspect = { aspect = it })
        cover != null -> AsyncImage(image, null, contentScale = ContentScale.Crop,
            onSuccess = { loaded ->
                ready = true; failed = false
                val size = loaded.painter.intrinsicSize
                if (size.width > 0f && size.height > 0f) aspect = size.width / size.height
            }, onError = { failed = true },
            modifier = (if (frame == null) framing.graphicsLayer { this.alpha = alpha } else framing)
                .testTag(if (ready) "album-artwork-loaded" else "album-artwork-loading"))
    }
    // The Canvas is late: a small pill says it is coming (6.2.3).
    // Wanted but not here yet: the cover shows meanwhile, with the pill.
    if (canvasEnabled && canvas == null && track != null) com.ketvoice.app.ui.common.CanvasLoadingPill(track,
        wanted = !com.ketvoice.app.data.MediaOverrides.canvasDisabled(track), modifier = Modifier.align(Alignment.TopEnd).padding(10.dp))
}

@Composable
private fun AlbumPlaybackActions(tracks: List<Track>, savedIds: Set<String>, loading: Boolean, stacked: Boolean,
                                 onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val enabled = !loading && tracks.isNotEmpty()
    val missing = remember(tracks, savedIds) { tracks.distinctBy { it.id }.filter { it.id !in savedIds && !it.id.startsWith("local:") } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        com.ketvoice.app.ui.common.CollectionActionPair({ Player.setShuffle(false); Player.playQueue(tracks) },
            { Player.playShuffled(tracks) }, enabled = enabled, playTag = "album-play", shuffleTag = "album-shuffle",
            minHeight = 50.dp, stacked = stacked)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            // Download and refresh share one capsule, like the actions at the top of the page.
            com.ketvoice.app.ui.common.GlassButtonPad(listOf(
                com.ketvoice.app.ui.common.PadAction(if (missing.isEmpty() && enabled) KetIcons.Check else KetIcons.Download,
                    if (missing.isEmpty() && enabled) "Téléchargé" else "Télécharger", "album-download",
                    enabled = enabled && missing.isNotEmpty()) { missing.forEach { Downloader.download(it) } },
                // The whole album into a playlist (5.9.1).
                com.ketvoice.app.ui.common.PadAction(KetIcons.Plus, "Ajouter l'album à une playlist", "album-add-playlist", enabled = enabled) {
                    com.ketvoice.app.ui.AppUi.addTracksToPlaylist.value = tracks.distinctBy { it.id } },
                com.ketvoice.app.ui.common.PadAction(KetIcons.Refresh, "Actualiser", "album-refresh", enabled = !loading, onClick = onRefresh)))
            Spacer(Modifier.weight(1f))
            Text("Titres", color = theme.secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(tracks.size.toString(), localize = false, color = theme.secondary, fontSize = 12.sp)
        }
    }
}

/**
 * A title of the album, numbered as on the record: its name with the explicit sign, the artists only
 * when they are not the album's own (a feature), the downloaded and separated marks, its length and
 * the menu. A tap plays from it, a long press or « … » opens its glass menu.
 */
@Composable
private fun AlbumTrackRow(track: Track, index: Int, active: Boolean, downloaded: Boolean, albumArtist: String,
                          onPlay: () -> Unit) {
    val theme = LocalTheme.current
    val ink by androidx.compose.animation.animateColorAsState(if (active) theme.accent else theme.onBackground,
        tween(Motion.duration(180)), label = "album-track-state")
    // The artist line says something only when it differs from the album's artist.
    val credit = track.artist.takeIf { it.isNotBlank() && !it.trim().equals(albumArtist.trim(), ignoreCase = true) }
    val separated = rememberSeparatedWhole(track.id, downloaded)
    val hairline = theme.onBackground.copy(alpha = .09f)
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp)
        .background(if (active) theme.accent.copy(alpha = .065f) else Color.Transparent)
        // A hairline under the title, from where the name starts, as Apple Music separates an album's titles.
        .drawWithCache {
            val start = 62.dp.toPx(); val stroke = .6.dp.toPx()
            onDrawBehind { drawLine(hairline, androidx.compose.ui.geometry.Offset(start, size.height - stroke / 2),
                androidx.compose.ui.geometry.Offset(size.width - 16.dp.toPx(), size.height - stroke / 2), stroke) }
        }
        .trackRowClick(track, onClick = onPlay)
        .padding(start = 20.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            if (active) com.ketvoice.app.ui.player.NowPlayingBars(
                com.ketvoice.app.player.Player.state.collectAsState().value.isPlaying, color = theme.accent)
            else Text("${index + 1}", localize = false, color = theme.secondary, fontSize = 15.sp, textAlign = TextAlign.Center)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, top = 2.dp, bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TrackTitle(track.title, color = ink, selected = active, modifier = Modifier.weight(1f, fill = false))
                if (track.explicit) EBadge(Modifier.padding(start = 6.dp).testTag("album-track-explicit"))
            }
            if (credit != null) Text(credit, localize = false, color = theme.secondary,
                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (LocalDensity.current.fontScale >= 1.3f && track.durationMs > 0)
                Text(formatSecs(track.durationMs), localize = false, color = theme.secondary, fontSize = 12.sp)
        }
        if (separated) Icon(KetIcons.Music, "Voix et musique séparées", tint = theme.secondary,
            modifier = Modifier.padding(start = 8.dp).size(15.dp))
        if (downloaded) Icon(KetIcons.Check, "Téléchargé", tint = theme.secondary,
            modifier = Modifier.padding(start = 8.dp).size(16.dp))
        if (LocalDensity.current.fontScale < 1.3f && track.durationMs > 0) Text(formatSecs(track.durationMs), localize = false,
            color = theme.secondary, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp))
        val menuAnchor = com.ketvoice.app.ui.common.rememberRowAnchor()
        androidx.compose.material3.IconButton(onClick = { menuAnchor.open(track) }, modifier = Modifier.size(48.dp).then(menuAnchor.modifier)) {
            Icon(KetIcons.More, "Options", tint = theme.secondary, modifier = Modifier.size(24.dp))
        }
    }
}

/** Whether a downloaded title was separated whole (voice and music), read off the main thread and again as the library separates more. */
@Composable
private fun rememberSeparatedWhole(trackId: String, downloaded: Boolean): Boolean {
    if (!downloaded) return false
    val library by com.ketvoice.app.stems.StemLibrary.progress.collectAsState()
    val separated by produceState(false, trackId, library.done, library.running) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.ketvoice.app.stems.StemStore.hasWhole(trackId) }.getOrDefault(false)
        }
    }
    return separated
}

/** The foot of an album page: when it came out, then how many titles and how long, as Apple Music closes an album. */
@Composable
private fun AlbumFooter(date: String?, tracks: List<Track>) {
    val theme = LocalTheme.current
    val language = AppText.language()
    val released = remember(date, language) { AlbumPresentation.releaseDate(date, java.util.Locale.forLanguageTag(language.code)) }
    val count = AppText.t(if (tracks.size == 1) "1 titre" else "${tracks.size} titres")
    val duration = remember(tracks) { AlbumPresentation.totalDuration(tracks) }?.let(AppText::t)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 4.dp).testTag("album-footer"),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (released != null) Text(released, localize = false, color = theme.secondary, style = com.ketvoice.app.theme.Ket27.Type.footnote)
        Text(listOfNotNull(count, duration).joinToString(", "), localize = false, color = theme.secondary,
            style = com.ketvoice.app.theme.Ket27.Type.footnote)
    }
}

/** Moves the content up into the space above it and gives that height back, so nothing is left empty below. */
private fun Modifier.pullUp(by: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shift = by.roundToPx().coerceAtMost(placeable.height)
    layout(placeable.width, placeable.height - shift) { placeable.place(0, -shift) }
}

/** Over the real album page showing a framing that is not published yet: retouch it, drop it or publish it. */
@Composable
private fun AlbumFramingBar(busy: Boolean, error: String, hasPublished: Boolean, modifier: Modifier, forPlayer: Boolean = false,
                            onEdit: () -> Unit, onCancel: () -> Unit, onDefault: () -> Unit, onPublish: () -> Unit) {
    val theme = LocalTheme.current
    // A solid sheet: its glass buttons sample the page behind it, so the sheet itself is not glass.
    Box(modifier.navigationBarsPadding().padding(12.dp).widthIn(max = 560.dp).fillMaxWidth()
        .clip(RoundedCornerShape(28.dp)).background(theme.card.copy(alpha = .96f))
        .border(.8.dp, theme.onBackground.copy(alpha = .10f), RoundedCornerShape(28.dp))
        .testTag("album-framing-bar")) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when { busy -> "Publication du cadrage…"; error.isNotBlank() -> "Le cadrage n'a pas été publié"
                    forPlayer -> "Cadrage du lecteur"; else -> "Aperçu du cadrage" }, color = theme.onBackground, fontSize = 16.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.testTag("album-framing-heading"))
            Text(if (forPlayer) "Une fois publié, le lecteur affichera ce cadrage pour les titres de cet album. La page d'album ne change pas."
                else "Voici la page telle que tout le monde la verra une fois le cadrage publié.", color = theme.secondary, fontSize = 12.sp)
            if (error.isNotBlank()) Text(error, localize = false, color = com.ketvoice.app.ui.common.DESTRUCTIVE, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.ketvoice.app.ui.common.GlassCapsuleButton("Annuler", null, onCancel, Modifier.weight(1f), enabled = !busy,
                    tag = "album-framing-cancel", minHeight = 48.dp)
                com.ketvoice.app.ui.common.GlassCapsuleButton("Retoucher", null, onEdit, Modifier.weight(1f), enabled = !busy,
                    tag = "album-framing-edit", minHeight = 48.dp)
                com.ketvoice.app.ui.common.GlassCapsuleButton(if (busy) "Envoi…" else if (error.isNotBlank()) "Réessayer" else "Publier", null, onPublish, Modifier.weight(1f),
                    prominent = true, enabled = !busy, tag = "album-framing-publish", minHeight = 48.dp)
            }
            if (hasPublished) Text(if (forPlayer) "Suivre le cadrage de la page d'album" else "Revenir à l'affichage par défaut", color = theme.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = !busy, role = Role.Button, onClick = onDefault)
                    .heightIn(min = 44.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 6.dp).testTag("album-framing-default"))
        }
    }
}
