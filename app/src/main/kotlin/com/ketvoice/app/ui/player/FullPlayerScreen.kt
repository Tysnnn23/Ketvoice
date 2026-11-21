package com.ketvoice.app.ui.player

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.*
import com.ketvoice.app.glass.LocalBackdropLayer
import com.ketvoice.app.glass.rememberGlassBackdrop
import com.ketvoice.app.player.Player
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout
import com.ketvoice.app.ui.common.Cover
import com.kyant.backdrop.backdrops.layerBackdrop
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.symbolBounce
import androidx.compose.animation.animateColorAsState
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.KetTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.luminance
import com.ketvoice.app.ui.common.TrackTitle

enum class PlayerPage { ARTWORK, LYRICS, QUEUE }

/** One player shell and one transport: changing pages never remounts the controls. */
@Composable
fun FullPlayerScreen(
    modifier: Modifier = Modifier,
    initialPage: PlayerPage = PlayerPage.ARTWORK,
    onDismiss: () -> Unit = { AppUi.showPlayer.value = false; AppUi.showLyrics.value = false }
) {
    val state by Player.state.collectAsState()
    val track = state.current
    if (track == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    var page by remember { mutableStateOf(if (AppUi.showLyrics.value) PlayerPage.LYRICS else initialPage) }
    // 8.0: the morph between the artwork and the small header (see PlayerMorph): a spring that a new page, or a
    // drag, takes over from wherever it is.
    val morph = remember { Animatable(if (page != PlayerPage.ARTWORK) 1f else 0f) }
    val morphProgress: () -> Float = remember { { PlayerMorph.pinned.value ?: morph.value } }
    val morphScope = rememberCoroutineScope()
    var morphDragging by remember { mutableStateOf(false) }
    var morphVelocity by remember { mutableFloatStateOf(0f) }
    // The pane that rises into the header layout: kept while the artwork grows back, so it can leave.
    var paneShown by remember { mutableStateOf(if (page == PlayerPage.QUEUE) PlayerPage.QUEUE else PlayerPage.LYRICS) }
    LaunchedEffect(page, morphDragging) {
        if (morphDragging) return@LaunchedEffect
        val target = if (page == PlayerPage.ARTWORK) 0f else 1f
        val velocity = morphVelocity
        morphVelocity = 0f
        // Reduced motion: no travel, a short fade through (PlayerMorph.shape / fade).
        if (Motion.reduced) morph.animateTo(target, tween(220)) else morph.animateTo(target, PlayerMorph.spring(), velocity)
    }
    var expanded by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf("") }
    var interaction by remember { mutableLongStateOf(0L) }
    var touching by remember { mutableStateOf(false) }
    val covers by ArtworkRepo.covers.collectAsState()
    val canvas by CanvasRepo.paths.collectAsState()
    val canvasExcluded by CanvasRepo.excluded.collectAsState()
    // Exclusion par morceau (menu •••) : pochette statique uniquement.
    val mixing = com.ketvoice.app.automix.Automix.ui.collectAsState().value.phase == com.ketvoice.app.automix.Automix.Phase.Mixing
    val canvasPath = canvas[track.id]?.takeIf { track.id !in canvasExcluded && !com.ketvoice.app.data.MediaOverrides.canvasHiddenInPlayer(track) }
    // How the owner framed this album's artwork for the player (or for its page, which the player follows).
    val artworkFrame = com.ketvoice.app.data.MediaOverrides.playerFrame(track)
    // Only a verified portrait 9:16 clip can enter the full-window mode.
    var canvasFocus by remember(track.id, canvasPath) { mutableStateOf(false) }
    var failedCanvas by remember(canvasPath) { mutableStateOf(false) }
    val canvasAspect by rememberCanvasAspectRatio(canvasPath.takeIf { AppSettings.animatedArtwork.value })
    val artInteraction = remember { MutableInteractionSource() }
    val lyrics by LyricsRepo.state.collectAsState()
    val cover = com.ketvoice.app.data.MediaOverrides.cover(track, covers["t:${track.id}"] ?: track.coverUrl)
    val sampled = rememberCoverAmbience(cover)
    // 7.4: background and accent glide together, over the same time as the colour fields.
    val ambientColor by animateColorAsState(sampled.color, tween(Motion.duration(PlayerColors.GLIDE_MS)), label = "album-background")
    val ambience = sampled.copy(color = ambientColor)
    val preferredAccent = LocalTheme.current.accent
    val albumAccent by animateColorAsState(
        if (AppSettings.albumColors.value) sampled.accent ?: preferredAccent else preferredAccent,
        tween(Motion.duration(PlayerColors.GLIDE_MS)), label = "album-accent")
    val context = LocalContext.current
    // The next title's colours are read ahead, so a change of title glides at once instead of after its cover loads.
    val nextCover = state.queue.getOrNull(state.index + 1)?.let { ArtworkRepo.coverForTrack(it) }
    LaunchedEffect(nextCover) { PlayerColors.warm(context, nextCover) }
    val backdrop = rememberGlassBackdrop(Color.Black)
    val controlsBackdrop = rememberGlassBackdrop(Color.Black)
    val adaptivePlayer = LocalAdaptiveLayout.current.adaptivePlayer
    fun navigate(next: PlayerPage) {
        if (next != PlayerPage.ARTWORK) paneShown = next
        page = next
        expanded = false
        canvasFocus = false
        interaction = android.os.SystemClock.uptimeMillis()
        AppUi.showLyrics.value = next == PlayerPage.LYRICS
    }
    fun closeInstant() {
        sheet = ""
        AppUi.showTrackMenu.value = false
        AppUi.showAudioOutput.value = false
        AppUi.showLyricsSource.value = false
        onDismiss()
    }
    LaunchedEffect(track.id) { LyricsRepo.load(track) }
    LaunchedEffect(page, sheet, interaction, touching, expanded, lyrics.lines.isNotEmpty(), AppUi.showAccount.value, adaptivePlayer) {
        if (!com.ketvoice.app.BuildConfig.IS_QUEST && !adaptivePlayer && page == PlayerPage.LYRICS && !expanded && !touching && sheet.isBlank() &&
            lyrics.lines.isNotEmpty() && !AppUi.showAccount.value) {
            kotlinx.coroutines.delay(2000)
            expanded = true
        }
    }

    // The player keeps its own colours and the accent.
    CompositionLocalProvider(LocalTheme provides KetTheme.dark().copy(accent = albumAccent, secondary = Color.White.copy(alpha = .78f))) {
    MaterialTheme(colorScheme = darkColorScheme(primary = albumAccent,
        onPrimary = if (albumAccent.luminance() > .179f) Color.Black else Color.White,
        background = com.ketvoice.app.theme.Palette.BackgroundDark,
        surface = com.ketvoice.app.theme.Palette.CardDark, onSurface = Color.White,
        onSurfaceVariant = com.ketvoice.app.theme.Palette.SecondaryTextDark,
        surfaceTint = Color.Transparent)) {
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val canvasReady = canvasPath != null && AppSettings.animatedArtwork.value && !failedCanvas &&
            CanvasPresentation.canFillScreen(canvasAspect, maxWidth.value / maxHeight.value)
        LaunchedEffect(canvasReady) { if (!canvasReady) canvasFocus = false }
        AnimatedContent(canvasFocus && canvasReady, transitionSpec = {
            fadeIn(tween(Motion.duration(300))).togetherWith(fadeOut(tween(Motion.duration(220))))
                .using(androidx.compose.animation.SizeTransform(clip = false))
        }, modifier = Modifier.fillMaxSize(), label = "canvas-focus-transition") { focus ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
        if (focus) {
            BackHandler { canvasFocus = false }
            CanvasFocusPlayer(track, canvasPath!!, state.isPlaying && canvasFocus, state.durationMs,
                onExit = { canvasFocus = false }, onFailure = { failedCanvas = true; canvasFocus = false })
            return@BoxWithConstraints
        }
        if (com.ketvoice.app.BuildConfig.IS_QUEST) {
            BackHandler { if (page != PlayerPage.ARTWORK) navigate(PlayerPage.ARTWORK) else closeInstant() }
            com.ketvoice.app.glass.BackdropCapture(backdrop, Modifier.fillMaxSize()) {
            QuestLandscapePlayer(if (canvasFocus) state.copy(isPlaying = false) else state, track, cover, canvasPath, ambience, page,
                onPage = ::navigate, onClose = ::closeInstant, onTrackMenu = { sheet = "track" },
                onSources = { sheet = "sources" }, onOutput = { sheet = "audio" },
                onCanvas = if (canvasReady) ({ canvasFocus = true }) else null)
            }
            CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
            if (sheet.isNotBlank()) PlayerSheetScrim({ sheet = "" }) {
                when (sheet) {
                    "sources" -> LyricsSourceSheet(track, onClose = { sheet = "" })
                    "track" -> TrackMenuSheet(track) { sheet = "" }
                    else -> AudioOutputSheet { sheet = "" }
                }
            }
            }
            return@BoxWithConstraints
        }
        if (adaptivePlayer) {
            // Swipe horizontal depuis un bord pour fermer le player sur tablette.
            // Les drags intérieurs (scrub, volume, paroles) ne sont jamais interceptés.
            var dragX by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
            com.ketvoice.app.glass.BackdropCapture(backdrop, Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = dragX }.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    val edge = size.width * 0.09f
                    if (down.position.x > edge && down.position.x < size.width - edge) return@awaitEachGesture
                    dragX = 0f
                    var total = 0f
                    var claimed = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: break
                        total += change.positionChange().x
                        if (!claimed && kotlin.math.abs(total) > viewConfiguration.touchSlop * 3f) claimed = true
                        if (claimed) {
                            dragX = total
                            change.consume()
                        }
                        if (!change.pressed) break
                    }
                    if (claimed && kotlin.math.abs(total) > 380f) closeInstant() else dragX = 0f
                }
            }) {
                TabletPlayer(if (canvasFocus) state.copy(isPlaying = false) else state, track, cover, canvasPath, ambience, page,
                    onPage = ::navigate, onClose = ::closeInstant, onTrackMenu = { sheet = "track" },
                    onSources = { sheet = "sources" }, onOutput = { sheet = "audio" },
                    onCanvas = if (canvasReady) ({ canvasFocus = true }) else null)
            }
            }
            BackHandler { if (page != PlayerPage.ARTWORK) navigate(PlayerPage.ARTWORK) else closeInstant() }
            CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
            if (sheet.isNotBlank()) PlayerSheetScrim({ sheet = "" }) {
                when (sheet) {
                    "sources" -> LyricsSourceSheet(track, onClose = { sheet = "" })
                    "track" -> TrackMenuSheet(track) { sheet = "" }
                    else -> AudioOutputSheet { sheet = "" }
                }
            }
            }
            return@BoxWithConstraints
        }
        val margin = maxWidth * .08f
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val safeHeight = maxHeight - bottomInset
        val wide = maxWidth > maxHeight
        val controlsWidth = if (wide) maxWidth * .48f else maxWidth
        val sceneWidth = if (wide && !expanded) maxWidth - controlsWidth else maxWidth
        // Tabletop (a flip-style phone half-opened on a table): the scene stays above the
        // hinge and the controls below it, so nothing important sits on the fold.
        val tabletop = if (wide) null else com.ketvoice.app.ui.adaptive.rememberTabletop()?.let { fold ->
            with(LocalDensity.current) {
                com.ketvoice.app.ui.adaptive.tabletopSplit(fold.hingeTopPx.toDp().value, fold.hingeBottomPx.toDp().value,
                    maxHeight.value, bottomInset.value)
            }
        }
        val lowerHeight = if (wide) (safeHeight - topInset - 6.dp) else tabletop?.controlsHeightDp?.dp
            ?: (safeHeight * .405f).coerceAtLeast(300.dp)
        val contentBottom = safeHeight - lowerHeight
        val headerTop = if (wide) topInset + 20.dp else (safeHeight * .093f).coerceAtLeast(topInset + 35.dp)
        val headerHeight = 58.dp
        val sceneTop = headerTop + headerHeight + 7.dp

        // ---- Fermeture progressive : l'écran entier suit le doigt ----
        // Translation verticale + fondu + coin supérieur arrondi qui se dessine
        // au fil du drag (feuille iOS). Relâché bas : rebord spring ; assez bas
        // ou rapide : glisse hors écran puis fermeture réelle.
        val presentationOffset = LocalPlayerDismissOffset.current
        val revealProgress = LocalPlayerReveal.current
        val dismissOffset = presentationOffset ?: remember { Animatable(0f) }
        val dismissScope = rememberCoroutineScope()
        val screenH = with(LocalDensity.current) { maxHeight.toPx() }
        fun settleDismiss() {
            dismissScope.launch {
                if (Motion.reduced) { dismissOffset.snapTo(0f); return@launch }
                dismissOffset.animateTo(
                    0f,
                    spring(dampingRatio = 0.78f, stiffness = 380f)
                )
            }
        }
        fun completeDismiss() {
            dismissScope.launch {
                if (presentationOffset == null && !Motion.reduced) dismissOffset.animateTo(
                    screenH, tween(Motion.duration(260), easing = FastOutSlowInEasing)
                )
                closeInstant()
                if (presentationOffset == null) dismissOffset.snapTo(0f)
            }
        }
        // Ket 27, opened from the mini-player: its cover, title, play and next buttons travel to their places in
        // one movement; the other controls rise in a little later, each on its own.
        val travels = presentationOffset != null && !Motion.reduced
        val lateRise = if (!travels) Modifier else Modifier.graphicsLayer {
            val rise = controlsReveal(revealProgress())
            alpha = rise
            translationY = (1f - rise) * 28.dp.toPx()
        }
        fun requestClose() {
            if (dismissOffset.value > 0f && dismissOffset.isRunning) return
            completeDismiss()
        }
        val dismissDrag = Modifier.playerDismissDrag(
            dismissOffset = dismissOffset,
            maxPx = screenH,
            onSettle = { settleDismiss() },
            onDismiss = { completeDismiss() }
        )
        fun close() {
            sheet = ""
            AppUi.showTrackMenu.value = false
            AppUi.showAudioOutput.value = false
            AppUi.showLyricsSource.value = false
            requestClose()
        }
        // Predictive back (Android 14+): while the gesture is held, the player follows it
        // toward the mini-player on the shared reveal timeline; releasing commits or cancels.
        androidx.activity.compose.PredictiveBackHandler { events ->
            val closes = !canvasFocus && sheet.isEmpty() && !expanded && page == PlayerPage.ARTWORK
            try {
                events.collect { event ->
                    if (closes && !Motion.reduced) dismissOffset.snapTo(predictiveBackTravel(event.progress, screenH))
                }
                when {
                    canvasFocus -> canvasFocus = false
                    sheet.isNotEmpty() -> sheet = ""
                    expanded -> expanded = false
                    page != PlayerPage.ARTWORK -> navigate(PlayerPage.ARTWORK)
                    else -> close()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                if (closes) settleDismiss()
                throw cancelled
            }
        }
        // Capture only the scene; sheets are siblings, never part of their own backdrop.
        com.ketvoice.app.glass.BackdropCapture(backdrop) {
            Box(Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = (dismissOffset.value / screenH).coerceIn(0f, 1f)
                    translationY = if (presentationOffset == null) dismissOffset.value else 0f
                    alpha = if (presentationOffset == null) 1f - p * 0.45f else 1f
                    // Coin de feuille qui se dessine au fil du drag.
                    if (presentationOffset == null && p > 0.008f && !Motion.reduced) {
                        val corner = (44f * p.coerceAtMost(1f)).dp.toPx()
                        shape = RoundedCornerShape(topStart = corner, topEnd = corner)
                        clip = true
                    } else if (clip) {
                        shape = RoundedCornerShape(0.dp)
                        clip = false
                    }
                }
                .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        touching = event.changes.any { it.pressed }
                        interaction = android.os.SystemClock.uptimeMillis()
                    }
                }
            }) {
                // The footer samples only this background sibling, never the
                // enclosing full-scene layer which also captures the footer.
                com.ketvoice.app.glass.BackdropCapture(controlsBackdrop, Modifier.matchParentSize()) {
                    PlayerSceneBackground(track, canvasPath, state.isPlaying && !canvasFocus, cover, ambience,
                        plain = false)
                    // The lyrics page glows in the cover's colours (6.0.0): a mesh over the ambient background.
                    if (!AppSettings.sonicCanvas.value) LyricsMesh(page == PlayerPage.LYRICS, cover, ambience, state.isPlaying)
                }

                // ---- 8.0: the player that morphs ----
                // One progress (0 artwork, 1 header) moves the artwork into the header beside the title, the title
                // beside it and the lyrics or queue into the freed space; read in layout and draw only.
                val reduced = Motion.reduced
                val inlineCanvas = canvasPath != null && AppSettings.animatedArtwork.value && !panoramicCanvasActive(canvasPath)
                val titleTop = if (wide) safeHeight - 66.dp else tabletop?.let { it.sceneHeightDp.dp - 66.dp }
                    ?: minOf(safeHeight * .514f, contentBottom - 61.dp)
                val artworkHeight = if (wide) safeHeight else tabletop?.sceneHeightDp?.dp ?: (safeHeight * .55f)
                val headerPad = if (wide) 18.dp else margin
                val density = LocalDensity.current
                val fitTop = topInset + 52.dp
                val fitBottom = titleTop - 8.dp
                val squarePx = with(density) { PlayerMorph.square(sceneWidth.toPx(), fitTop.toPx(), fitBottom.toPx(), 12.dp.toPx()) }
                val slotPx = with(density) {
                    androidx.compose.ui.geometry.Rect(headerPad.toPx(), (headerTop + (headerHeight - PlayerMorph.SLOT_DP.dp) / 2).toPx(),
                        (headerPad + PlayerMorph.SLOT_DP.dp).toPx(), (headerTop + (headerHeight + PlayerMorph.SLOT_DP.dp) / 2).toPx())
                }
                val slotRadiusPx = with(density) { PlayerMorph.SLOT_RADIUS_DP.dp.toPx() }
                val riseDistancePx = with(density) { (titleTop - headerTop).toPx().coerceAtLeast(1f) }
                // Composition only learns when the artwork starts leaving and when it has settled in the header.
                val leaving by remember { derivedStateOf { morphProgress() > .001f } }
                val settled by remember { derivedStateOf { page != PlayerPage.ARTWORK && morphProgress() >= .999f } }
                // A drag up on the artwork pulls the lyrics in; a drag down still closes the player.
                val artworkDrag = Modifier.playerDismissDrag(dismissOffset = dismissOffset, maxPx = screenH,
                    onSettle = { settleDismiss() }, onDismiss = { completeDismiss() },
                    rise = if (page != PlayerPage.ARTWORK || adaptivePlayer) null else MorphDrag(
                        onDrag = { delta ->
                            morphDragging = true
                            morphScope.launch { morph.snapTo(PlayerMorph.clamp(morph.value + delta / riseDistancePx)) }
                        },
                        onEnd = { velocityPx ->
                            val velocity = velocityPx / riseDistancePx
                            morphVelocity = velocity
                            if (PlayerMorph.commits(morph.value, velocity)) navigate(PlayerPage.LYRICS)
                            morphDragging = false
                        }))
                // Swipe the artwork sideways to change track; a vertical drag still closes the player.
                val trackSwipe = rememberTrackSwipe()
                // The breathing artwork (7.3): a gentle step back on pause, a spring forward on play.
                val pauseShrink = rememberArtworkBreath(state.playWhenReady)
                // The living artwork: a pulse with the bass, read in the layer so that it never recomposes the page.
                val living = AppSettings.livingArtwork.value && !Motion.reduced && !AppSettings.highPerformanceMode.value && state.isPlaying && !leaving
                val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
                DisposableEffect(lifecycle, living) {
                    fun update() = com.ketvoice.app.player.SonicMeter.observe("living-artwork",
                        living && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> update() }
                    lifecycle.addObserver(observer); update()
                    onDispose { lifecycle.removeObserver(observer); com.ketvoice.app.player.SonicMeter.observe("living-artwork", false) }
                }
                val sonic = com.ketvoice.app.player.SonicMeter.levels.collectAsState()
                val beat = remember { androidx.compose.animation.core.Animatable(0f) }
                LaunchedEffect(living) {
                    if (!living) { beat.animateTo(0f, tween(Motion.duration(240))); return@LaunchedEffect }
                    androidx.compose.runtime.snapshotFlow { sonic.value.low }.collect { low ->
                        beat.animateTo(LivingArtwork.target(low), tween(LivingArtwork.FOLLOW_MS))
                    }
                }
                val artworkTap = when {
                    page != PlayerPage.ARTWORK -> Modifier.clickable(interactionSource = artInteraction, indication = null,
                        onClickLabel = AppText.t("Revenir à la pochette")) { navigate(PlayerPage.ARTWORK) }
                        .semantics { contentDescription = AppText.t("Revenir à la pochette") }
                    canvasReady -> Modifier.clickable(interactionSource = artInteraction, indication = null,
                        onClickLabel = AppText.t("Voir le Canvas en plein écran")) { canvasFocus = true }
                        .semantics { contentDescription = AppText.t("Voir le Canvas en plein écran") }
                    else -> Modifier
                }
                Box(Modifier.width(sceneWidth).height(artworkHeight)
                    .graphicsLayer {
                        val raw = morphProgress()
                        val p = PlayerMorph.shape(raw, reduced)
                        alpha = PlayerMorph.fade(raw, reduced)
                        if (p > 0f) {
                            val art = PlayerMorph.artwork(size, squarePx, slotPx, slotRadiusPx, p)
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                            scaleX = art.scale; scaleY = art.scale
                            translationX = art.translationX; translationY = art.translationY
                            shape = PlayerMorph.ClipShape(art.clip, art.radius)
                            clip = true
                        } else {
                            scaleX = 1f; scaleY = 1f; translationX = 0f; translationY = 0f
                            clip = false
                        }
                    }
                    .then(artworkDrag).trackSwipe(trackSwipe, threshold = 84.dp).then(artworkTap)) {
                    if (!settled) PlayerArtworkFrame(inlineCanvas && artworkFrame == null, ambience.color,
                        Modifier.fillMaxSize()
                        // Shared element: the artwork grows out of the mini-player cover on the
                        // reveal timeline, and returns to it during a dismissal or a back gesture.
                        .graphicsLayer {
                            val k = 1f - PlayerMorph.shape(morphProgress(), reduced)
                            val morphFromMini = sharedCoverMorph(AppUi.miniCoverBounds.value, size.width, size.height, revealProgress())
                            // Paused, the artwork steps back with a small bounce; playing, it comes forward again.
                            val rest = pausedArtworkScale(pauseShrink.value) * (1f + beat.value)
                            val scale = PlayerMorph.lerp(1f, morphFromMini.scale * rest, k)
                            scaleX = scale; scaleY = scale
                            translationX = morphFromMini.translationX * k; translationY = morphFromMini.translationY * k
                            // Near the header, the whole cover takes over from the large artwork.
                            alpha = 1f - PlayerMorph.ramp(PlayerMorph.shape(morphProgress(), reduced), .55f, 1f)
                            if (pauseShrink.value > .01f && k > 0f) {
                                shape = androidx.compose.foundation.shape.RoundedCornerShape((28f * pauseShrink.value.coerceIn(0f, 1f) * k).dp)
                                clip = true
                            } else clip = false
                        },
                        solid = { PlayerMorph.shape(morphProgress(), reduced) }) {
                        Box(Modifier.fillMaxSize()) {
                            ArtworkCanvas(track, if (canvasFocus || panoramicCanvasActive(canvasPath)) null else canvasPath,
                                // Performance mode: the Canvas holds its frame while it travels and in the header.
                                state.isPlaying && !canvasFocus && !(AppSettings.highPerformanceMode.value && leaving),
                                Modifier.fillMaxSize().trackSwipeContent(trackSwipe), depth = true,
                                onCanvasFailure = { failedCanvas = true }, frame = artworkFrame,
                                // 8.1: edge to edge again, as before 7.6 (the owner no longer wants the whole, squared Canvas).
                                fit = null, ambient = ambience.color)
                            // Under « Lecture depuis », far from the controls: only when the Canvas is late (6.2.3).
                            com.ketvoice.app.ui.common.CanvasLoadingPill(track,
                                wanted = AppSettings.animatedArtwork.value && track.id !in canvasExcluded &&
                                    !com.ketvoice.app.data.MediaOverrides.canvasUnused(track) && !com.ketvoice.app.data.MediaOverrides.canvasHiddenInPlayer(track),
                                modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset + 58.dp)
                                    .graphicsLayer { alpha = 1f - PlayerMorph.ramp(morphProgress(), 0f, .3f) })
                        }
                    }
                    // The header's cover: the whole artwork, riding the same square, fading in as it arrives.
                    if (leaving) with(density) {
                        Cover(track.title, cover, size = squarePx.width.toDp(), radius = 0.dp,
                            modifier = Modifier.offset(squarePx.left.toDp(), squarePx.top.toDp())
                                .graphicsLayer { alpha = PlayerMorph.ramp(PlayerMorph.shape(morphProgress(), reduced), .45f, .95f) })
                    }
                }
                Text("Lecture depuis ${track.artist}", color = Color(0xFFCBCDD6), fontSize = 10.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    // 7.4: a soft shade keeps it readable over a very light cover or Canvas.
                    style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(
                        Color.Black.copy(alpha = .45f), blurRadius = 10f)),
                    modifier = Modifier.width(sceneWidth).padding(top = topInset + 32.dp).graphicsLayer {
                        val raw = morphProgress()
                        alpha = (1f - PlayerMorph.ramp(PlayerMorph.shape(raw, reduced), 0f, .35f)) * PlayerMorph.fade(raw, reduced)
                    })
                // One title row: under the artwork it is the large title; in the header it sits beside the small cover.
                PlayerTitle(track, false, cover, sheetAction = {
                    if (expanded && page != PlayerPage.ARTWORK) {
                        // 7.3: the share card first, with the link still one touch away.
                        com.ketvoice.app.ui.AppUi.shareCard.value = track
                    } else sheet = "track"
                }, share = expanded && page != PlayerPage.ARTWORK, titleLines = if (page == PlayerPage.ARTWORK) 2 else 1,
                    modifier = Modifier.width(sceneWidth)
                        .layout { measurable, constraints ->
                            val p = PlayerMorph.shape(morphProgress(), reduced)
                            val pad = PlayerMorph.lerp(margin.toPx(), headerPad.toPx(), p).toInt()
                            val placeable = measurable.measure(constraints.copy(minWidth = 0,
                                maxWidth = (constraints.maxWidth - 2 * pad).coerceAtLeast(0)))
                            val headerY = headerTop.toPx() + (headerHeight.toPx() - placeable.height) / 2f
                            layout(constraints.maxWidth, placeable.height) {
                                placeable.place(pad, PlayerMorph.lerp(titleTop.toPx(), headerY, p).toInt())
                            }
                        }
                        .graphicsLayer { alpha = PlayerMorph.fade(morphProgress(), reduced) }
                        .then(if (page != PlayerPage.ARTWORK) dismissDrag else Modifier),
                    // The title and artist make room for the small cover, a little smaller, in one movement.
                    textModifier = Modifier
                        .layout { measurable, constraints ->
                            val inset = (PlayerMorph.shape(morphProgress(), reduced) * PlayerMorph.TITLE_INSET_DP.dp.toPx()).toInt()
                                .coerceAtMost(constraints.maxWidth)
                            val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = constraints.maxWidth - inset))
                            layout(constraints.maxWidth, placeable.height) { placeable.place(inset, 0) }
                        }
                        .graphicsLayer {
                            val s = PlayerMorph.lerp(1f, PlayerMorph.HEADER_TITLE_SCALE, PlayerMorph.shape(morphProgress(), reduced))
                            scaleX = s; scaleY = s
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, .5f)
                        }
                        // Ket 27: the title and the artist travel up from the mini-player; the buttons beside them fade in.
                        .then(if (travels) Modifier.travelFromMini({ MiniPlayerAnchors.title }, revealProgress, anchorStart = true) else Modifier),
                    actionsModifier = lateRise)

                // Dismiss gestures are limited to the handle. No fullscreen
                // detector can consume taps, seek drags or lyric scrolling.
                PlayerHandle(Modifier.offset(x = (sceneWidth - 120.dp) / 2).padding(top = topInset),
                    onClose = { close() }, drag = dismissDrag)

                // The lyrics or the queue rise into the space the artwork left; between them the header stays.
                val paneVisible by remember { derivedStateOf { page != PlayerPage.ARTWORK || morphProgress() > .001f } }
                if (paneVisible) {
                    val sceneEnd by animateDpAsState(if (expanded || wide) safeHeight - 12.dp else contentBottom,
                        tween(Motion.duration(360), easing = androidx.compose.animation.core.CubicBezierEasing(.22f, 1f, .36f, 1f)), label = "lyrics-viewport")
                    Box(Modifier.offset(y = sceneTop).height((sceneEnd - sceneTop).coerceAtLeast(80.dp)).width(sceneWidth)
                        .graphicsLayer {
                            val raw = morphProgress()
                            val p = PlayerMorph.shape(raw, reduced)
                            alpha = if (reduced) p * PlayerMorph.fade(raw, true) else PlayerMorph.ramp(raw, .45f, 1f)
                            translationY = (1f - p) * 56.dp.toPx()
                        }) {
                        AnimatedContent(paneShown, transitionSpec = {
                            fadeIn(tween(Motion.duration())).togetherWith(fadeOut(tween(Motion.duration())))
                        }, label = "player-pane", modifier = Modifier.fillMaxSize()) { pane ->
                            if (pane == PlayerPage.QUEUE) QueuePane(state, Modifier.fillMaxSize().padding(horizontal = if (wide) 18.dp else margin))
                            else LyricsPane(track, expanded, onSource = { sheet = "sources" },
                                onEmptyTap = {
                                    if (expanded) {
                                        expanded = false
                                        interaction = android.os.SystemClock.uptimeMillis()
                                    }
                                },
                                modifier = Modifier.fillMaxSize().padding(horizontal = if (wide) 18.dp else margin * .8f),
                                fontScale = com.ketvoice.app.ui.quest.QuestLayout.lyricsScale(AppSettings.lyricsTextSize.value))
                        }
                    }
                }

                // 8.1 (owner: « quand on clique sur la cover dans l'écran des lyrics ou file d'attente, ça te remet à
                // l'écran principal »): the header's small cover, above the pane that would otherwise take the touch.
                if (page != PlayerPage.ARTWORK) with(density) {
                    Box(Modifier.offset(slotPx.left.toDp(), slotPx.top.toDp()).size(slotPx.width.toDp(), slotPx.height.toDp())
                        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
                            onClickLabel = AppText.t("Revenir à la pochette")) { navigate(PlayerPage.ARTWORK) }
                        .semantics { contentDescription = AppText.t("Revenir à la pochette") }
                        .testTag("header-cover-back"))
                }

                CompositionLocalProvider(LocalBackdropLayer provides controlsBackdrop) {
                PlayerChrome(!expanded, Modifier.align(if (wide) Alignment.BottomEnd else Alignment.BottomCenter).padding(bottom = bottomInset)
                    .then(if (travels) Modifier else Modifier.graphicsLayer {
                        val rise = controlsReveal(revealProgress())
                        alpha = rise
                        translationY = (1f - rise) * 28.dp.toPx()
                    })) {
                    Column(Modifier
                        .height(lowerHeight).width(controlsWidth).padding(horizontal = if (wide) 20.dp else margin)) {
                        Box(Modifier.height(34.dp).fillMaxWidth().then(lateRise), contentAlignment = Alignment.CenterStart) {
                            if (page == PlayerPage.LYRICS) {
                                Text("${lyrics.source.ifBlank { "Recherche des paroles…" }} · ${lyrics.syncDescription()}", color = Color(0xFFB5B5C0), fontSize = 12.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable { sheet = "sources" })
                            } else if (mixing) {
                                // While two titles play together, the mix itself takes this line.
                                MixStrip(Modifier.fillMaxWidth())
                            } else if (page == PlayerPage.QUEUE && AppSettings.automix.value) {
                                // On the queue this line says what Ketmix prepares; it never covers the list.
                                AutomixStatusLine(Modifier.fillMaxWidth(), fontSize = 12.sp, glyph = true)
                            } else LyricsPreviewLine { navigate(PlayerPage.LYRICS) }
                        }
                        ScrubBar(state.durationMs, Modifier.fillMaxWidth().then(lateRise), onSeek = Player::seekTo)
                        AudioStatsInfo(Modifier.fillMaxWidth().then(lateRise))
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            PlayerTransport(state.playWhenReady, Modifier.fillMaxSize(), Player::previous, Player::toggle, Player::next,
                                previousModifier = lateRise,
                                playModifier = if (travels) Modifier.travelFromMini({ MiniPlayerAnchors.play }, revealProgress) else Modifier,
                                nextModifier = if (travels) Modifier.travelFromMini({ MiniPlayerAnchors.next }, revealProgress) else Modifier)
                            if (state.buffering) Text("Chargement…", color = Color.White.copy(alpha = .42f), fontSize = 10.sp,
                                modifier = Modifier.align(Alignment.BottomCenter))
                            else if (state.failed) Text(state.quality, color = Color.White, fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.BottomCenter))
                        }
                        PlayerVolume(Modifier.fillMaxWidth().then(lateRise), compact = true)
                        Spacer(Modifier.height(8.dp))
                        if (page == PlayerPage.QUEUE) QueueActionsBar(state.shuffle, state.repeatOne, state.autoplay, state.repeatAll,
                            { Player.setShuffle(!state.shuffle) }, { Player.cycleRepeat() },
                            { Player.setAutoplay(!state.autoplay) }, { navigate(PlayerPage.ARTWORK) }, lateRise)
                        else PlayerBottomBar(page == PlayerPage.LYRICS,
                            onLyrics = { navigate(if (page == PlayerPage.LYRICS) PlayerPage.ARTWORK else PlayerPage.LYRICS) },
                            onOutput = { sheet = "audio" },
                            onProfile = { AppUi.showAccount.value = true },
                            onQueue = { navigate(PlayerPage.QUEUE) }, modifier = lateRise)
                        // Ketogether (6.1): during a session the device label is the session's live pill.
                        com.ketvoice.app.ui.together.KetogetherDeviceLabel(Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp, bottom = 13.dp).then(lateRise)) {
                        Text(if (com.ketvoice.app.BuildConfig.IS_QUEST) "Ce casque" else "Ce téléphone", color = Color(0xFF777984), fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 18.dp, bottom = 19.dp).then(lateRise))
                        }
                    }
                }
                }
            }
        }
        CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
            AnimatedContent(sheet, transitionSpec = { fadeIn(tween(Motion.duration())).togetherWith(fadeOut(tween(Motion.duration()))) }, label = "player-sheet") { selected ->
                if (selected.isNotBlank()) PlayerSheetScrim({ sheet = "" }) {
                    when (selected) {
                        "sources" -> LyricsSourceSheet(track, onClose = { sheet = "" })
                        "track" -> TrackMenuSheet(track) { sheet = "" }
                        else -> AudioOutputSheet { sheet = "" }
                    }
                }
            }
        }
    }
    }
    }
    }
    }
}

@Composable
private fun PlayerHandle(modifier: Modifier, onClose: () -> Unit, drag: Modifier = Modifier) {
    Box(modifier.then(drag).width(120.dp).height(34.dp)
        .semantics { contentDescription = com.ketvoice.app.data.AppText.t("Fermer le lecteur") }
        .clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp, 5.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF989AA6)))
    }
}

@Composable
internal fun PlayerTitle(track: Track, compact: Boolean, cover: String?, sheetAction: () -> Unit,
                         modifier: Modifier = Modifier, share: Boolean = false, onCover: () -> Unit = {},
                         textModifier: Modifier = Modifier, actionsModifier: Modifier = Modifier,
                         titleLines: Int = if (compact) 1 else 2) {
    val favorite = track.id in AppSettings.favoriteIds.value
    var favoriteTap by remember(track.id) { mutableIntStateOf(0) }
    val favoriteHaptic = com.ketvoice.app.ui.motion.rememberHaptics()
    // Counts only the times the title became a favourite: those burst.
    var bursts by remember(track.id) { mutableIntStateOf(0) }
    val starColor by animateColorAsState(if (favorite) com.ketvoice.app.theme.LocalTheme.current.accent else Color(0xFFCED2E3),
        tween(Motion.duration()), label = "favorite-color")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (compact) { Cover(track.title, cover, size = 56.dp, radius = 8.dp,
            modifier = Modifier.semantics { contentDescription = com.ketvoice.app.data.AppText.t("Revenir à la pochette") }.clickable(onClick = onCover)); Spacer(Modifier.width(12.dp)) }
        // The title and artist cross-fade from one title to the next (6.2), like Apple Music.
        AnimatedContent(track, Modifier.weight(1f).then(textModifier), transitionSpec = { titleChange() },
            contentKey = { it.id }, label = "player-title") { track ->
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.explicit) {
                    Text("E", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.clip(RoundedCornerShape(2.dp)).background(Color.White).padding(horizontal = 2.dp))
                    Spacer(Modifier.width(5.dp))
                }
                TrackTitle(track.title, color = Color.White, fontSize = if (compact) 20.sp else 22.sp,
                    selected = true, maxLines = titleLines)
            }
            // Nom d'artiste cliquable → ouvre la page artiste; 7.2: the Lossless mark follows it when the audio is.
            Row(verticalAlignment = Alignment.CenterVertically) {
            Text(track.artist, localize = false, color = Color.White.copy(alpha = .82f), fontSize = 17.sp, letterSpacing = (-.5).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    // On n'exige plus un artistId : sans lui le clic ne faisait
                    // strictement rien. openArtist résout désormais par nom.
                    .clickable { AppUi.openArtist(track.artistId.orEmpty(), track.artist) }
                    .semantics { contentDescription = com.ketvoice.app.data.AppText.t("Ouvrir la page de ${track.artist}") }
            )
            com.ketvoice.app.ui.common.LosslessBadge(com.ketvoice.app.ui.common.rememberPlayingGrade(track.id),
                Modifier.padding(start = 8.dp), tint = Color.White.copy(alpha = .72f))
            }
        }
        }
        Spacer(Modifier.width(8.dp))
        Box(actionsModifier, contentAlignment = Alignment.Center) {
        com.ketvoice.app.ui.motion.HeartBurst(bursts, starColor, Modifier.align(Alignment.Center))
        SmallCircleButton({
            favoriteTap++
            if (!favorite) { favoriteHaptic(com.ketvoice.app.ui.motion.Haptics.Kind.Confirm); bursts++ }
            AppSettings.favoriteIds.value = if (favorite) AppSettings.favoriteIds.value - track.id else AppSettings.favoriteIds.value + track.id
            SettingsStore.save()
        }, active = favorite, size = 36.dp) {
            // Ket 27: the heart fills inside its outline and bounces (give way, spring past, settle).
            com.ketvoice.app.ui.motion.FillingHeart(favorite,
                com.ketvoice.app.ui.common.Ket27Glyphs.Heart, com.ketvoice.app.ui.common.Ket27Glyphs.HeartFilled,
                if (favorite) "Retirer des favoris" else "Ajouter aux favoris", starColor,
                Modifier.size(23.dp).symbolBounce(favoriteTap))
        }
        }
        Spacer(Modifier.width(8.dp))
        // 6.0.0: "…" unfolds the glass menu of the title from the button; its "More" item opens the full sheet.
        val menuAnchor = com.ketvoice.app.ui.common.rememberRowAnchor()
        Box(actionsModifier.then(menuAnchor.modifier)) {
        SmallCircleButton(if (share) sheetAction else ({ menuAnchor.open(track) }), size = 36.dp) {
            Icon(if (share) Icons.Filled.Share else Icons.Filled.MoreHoriz, if (share) "Partager" else "Options",
                tint = Color(0xFFCED2E3), modifier = Modifier.size(22.dp))
        }
        }
    }
}

/**
 * Drag vertical de fermeture progressive : suit le doigt via [dismissOffset]
 * (px, jamais négatif), avec une résistance douce au-delà de 75 % de l'écran.
 * Relâché court → [onSettle] (rebord spring) ; franchi le seuil → [onDismiss].
 */
@Composable
internal fun Modifier.playerDismissDrag(
    dismissOffset: androidx.compose.animation.core.Animatable<Float, *>,
    maxPx: Float,
    onSettle: () -> Unit,
    onDismiss: () -> Unit,
    /** 8.0: when set, a drag that starts upward drives the player's morph instead (see [MorphDrag]). */
    rise: MorphDrag? = null
): Modifier {
    val settle by rememberUpdatedState(onSettle)
    val dismiss by rememberUpdatedState(onDismiss)
    val riser by rememberUpdatedState(rise)
    // snapTo/animateTo sont suspendus : les callbacks de drag passent par un scope.
    val scope = rememberCoroutineScope()
    val fling = with(LocalDensity.current) { PlayerDismiss.FLING_DP.dp.toPx() }
    return this.pointerInput(maxPx) {
        val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
        // The first movement decides: up (with a morph to drive) pulls the lyrics in, anything else closes.
        var rising: MorphDrag? = null
        var decided = false
        detectVerticalDragGestures(
            onDragStart = { tracker.resetTracking(); rising = null; decided = false },
            onVerticalDrag = { change, dy ->
                tracker.addPosition(change.uptimeMillis, change.position)
                change.consume()
                if (!decided) { decided = true; rising = riser?.takeIf { dy < 0f && dismissOffset.value <= 0f } }
                rising?.let { it.onDrag(-dy); return@detectVerticalDragGestures }
                val next = dismissOffset.value + dy
                // Résistance : au-delà de 75 % de l'écran, le doigt « appuie ».
                val resisted = if (next < maxPx * 0.75f) next
                else maxPx * 0.75f + (next - maxPx * 0.75f) * 0.35f
                scope.launch { dismissOffset.snapTo(resisted.coerceIn(0f, maxPx)) }
            },
            onDragEnd = {
                rising?.let { it.onEnd(-tracker.calculateVelocity().y); rising = null; return@detectVerticalDragGestures }
                // Ket 27: a flick down closes the player from anywhere, a flick up keeps it; the spring leaves at the finger's speed.
                val velocity = tracker.calculateVelocity().y
                if (PlayerDismiss.closes(dismissOffset.value, velocity, maxPx, fling, velocityAware = true)) {
                    PlayerDismiss.throwAt(velocity)
                    dismiss()
                } else settle()
            },
            onDragCancel = { rising?.let { it.onEnd(0f); rising = null } ?: settle() }
        )
    }
}

/** A drag up on the artwork, in px: [onDrag] gets each upward step, [onEnd] the release speed (px per second, up > 0). */
internal class MorphDrag(val onDrag: (Float) -> Unit, val onEnd: (Float) -> Unit)

/** The living artwork's pulse: a breath with the bass, at most two and a half percent. */
internal object LivingArtwork {
    const val MAX = .025f
    const val FOLLOW_MS = 90
    /** The scale to add for a bass level of 0..1: nothing for the quiet floor, a little more for the kicks. */
    fun target(low: Float): Float = if (!low.isFinite()) 0f else (((low - .18f) / .82f).coerceIn(0f, 1f).let { it * it }) * MAX
}
