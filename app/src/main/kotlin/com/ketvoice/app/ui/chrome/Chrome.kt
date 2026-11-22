package com.ketvoice.app.ui.chrome

import com.ketvoice.app.ui.motion.symbolBounce
import com.ketvoice.app.ui.motion.symbolSlide
import androidx.compose.ui.platform.testTag
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.motion.Motion
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.customActions
import com.ketvoice.app.ui.player.trackSwipe
import com.ketvoice.app.ui.player.trackSwipeContent
import androidx.compose.ui.semantics.semantics
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.ui.motion.Haptics
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.T
import com.ketvoice.app.theme.DesignTokens
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.EBadge
import com.ketvoice.app.ui.player.pressScale

/**
 * Chrome flottant iOS 26 : pilule mini-lecteur (avec progression) + capsule
 * d'onglets en verre liquide à sélection glissante + bouton recherche.
 *
 * Au scroll (état réduit) : le mini-lecteur se condense et rejoint la rangée
 * des onglets — une seule ligne fine avec Accueil/Explorer/Bibliothèque à
 * gauche, le lecteur réduit (pochette + transport) au centre et la recherche
 * à droite. On remonte → tout reprend sa place.
 */

private val noRipple: MutableInteractionSource = MutableInteractionSource()

@Composable
fun MiniPlayerPill(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** 0 above the tab bar .. 1 docked in the folded row; read while measuring and drawing only (8.1). */
    fold: () -> Float = { 0f },
    playSize: Dp = DesignTokens.Space.touch,
    onOpen: () -> Unit
) {
    // The cover is laid out at its full size and scaled down as the pill docks (44 → 32 dp).
    val coverSize = MINI_COVER
    val ps = Player.state.collectAsState().value
    val th = LocalTheme.current
    val current = ps.current ?: return
    val art by ArtworkRepo.covers.collectAsState()
    val cover = com.ketvoice.app.data.MediaOverrides.cover(current, art["t:${current.id}"] ?: current.coverUrl)

    val pillShape = RoundedCornerShape(27.dp)
    // Swipe the pill sideways to change track, docked (collapsed) too (7.1), with a short spring.
    val swipe = com.ketvoice.app.ui.player.rememberTrackSwipe()
    GlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .layout { measurable, constraints ->
                val h = (54.dp - 6.dp * fold()).roundToPx().coerceIn(constraints.minHeight, constraints.maxHeight)
                val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .onGloballyPositioned { AppUi.miniPlayerBounds.value = it.boundsInRoot() }
            .shadow(
                elevation = if (th.isDark) 2.dp else 4.dp,
                shape = pillShape,
                spotColor = Color.Black.copy(alpha = 0.14f)
            ),
        shape = pillShape,
        params = GlassParams.pill(dark = th.isDark),
        enabled = AppSettings.liquidGlass.value
    ) {
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxSize()
                    .trackSwipe(swipe, threshold = if (compact) 44.dp else 64.dp, settle = { com.ketvoice.app.ui.player.miniSwipeSpring() })
                    .semantics {
                        customActions = listOf(
                            androidx.compose.ui.semantics.CustomAccessibilityAction(com.ketvoice.app.data.AppText.t("Suivant")) { Player.next(); true },
                            androidx.compose.ui.semantics.CustomAccessibilityAction(com.ketvoice.app.data.AppText.t("Précédent")) { Player.previous(); true })
                    }
                    .clickable(noRipple, null) { onOpen() }
                    .padding(start = 6.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The cover comes alive as its Canvas when the title has one and motion is welcome.
                val canvases by com.ketvoice.app.data.CanvasRepo.paths.collectAsState()
                val canvas = canvases[current.id]?.takeIf { !com.ketvoice.app.data.MediaOverrides.canvasHiddenInPlayer(current) }?.takeIf {
                    AppSettings.animatedArtwork.value && !Motion.reduced && !AppSettings.highPerformanceMode.value
                }
                val coverPlace = Modifier.onGloballyPositioned { AppUi.miniCoverBounds.value = it.boundsInRoot() }
                // The mini-player shows the mix too (7.3): both covers side by side with a wave between them.
                Box(Modifier.foldScale { (MINI_COVER - 12.dp * fold()) / MINI_COVER }) {
                com.ketvoice.app.ui.player.MiniMixCovers(current, coverSize, if (compact) 999.dp else 9.dp) {
                    if (canvas != null) Box(coverPlace.size(coverSize).clip(RoundedCornerShape(if (compact) 999.dp else 9.dp))
                        .testTag("mini-canvas")) {
                        com.ketvoice.app.ui.player.ArtworkCanvas(current.copy(coverUrl = cover), canvas, ps.isPlaying, Modifier.fillMaxSize())
                    } else Box(coverPlace.size(coverSize).clip(RoundedCornerShape(if (compact) 999.dp else 9.dp))) {
                        Cover(current.title, cover, size = coverSize, radius = if (compact) 999.dp else 9.dp)
                    }
                }
                }
                Spacer(Modifier.width(if (compact) 4.dp else 11.dp))
                Column(
                    Modifier.weight(1f).graphicsLayer { alpha = miniLabelAlpha(fold().coerceIn(0f, 1f)) }
                        .trackSwipeContent(swipe, travel = .55f)
                        .onGloballyPositioned { com.ketvoice.app.ui.player.MiniPlayerAnchors.title = it.boundsInRoot() }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (current.explicit) {
                            EBadge()
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            current.title,
                            localize = false,
                            style = T.bodyBold.copy(fontSize = 15.sp),
                            color = th.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // Screen readers announce the new title when the track changes.
                            modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Ketogether (6.1): a small live sign before the artist while a session runs.
                        com.ketvoice.app.ui.together.KetogetherLivePill(Modifier.padding(end = 5.dp), compact = true, onDark = th.isDark)
                        Text(
                            current.artist,
                            localize = false,
                            style = T.sub.copy(fontSize = 12.5.sp),
                            color = th.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                Box(Modifier.onGloballyPositioned { com.ketvoice.app.ui.player.MiniPlayerAnchors.play = it.boundsInRoot() }) {
                    MiniTransportButton(playing = ps.playWhenReady, size = playSize)
                }
                Spacer(Modifier.width(2.dp))
                // Next stays when the tab bar folds (7.1): iOS 26's Music lost it there.
                Box(Modifier.onGloballyPositioned { com.ketvoice.app.ui.player.MiniPlayerAnchors.next = it.boundsInRoot() }
                    .testTag("mini-next")) {
                    MiniTransportNext()
                }
            }

            // Ligne de progression animée (composant isolé du tic)
            MiniProgressLine(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            )
        }
    }
}

/** Mini-lecteur condensé de l'état réduit : pochette + transport, sans texte. */
@Composable
private fun MiniPlayerCompact(modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val ps = Player.state.collectAsState().value
    val th = LocalTheme.current
    val current = ps.current ?: return
    val art by ArtworkRepo.covers.collectAsState()
    val cover = com.ketvoice.app.data.MediaOverrides.cover(current, art["t:${current.id}"] ?: current.coverUrl)
    val pillShape = RoundedCornerShape(999.dp)
    GlassPanel(
        modifier = modifier
            .height(52.dp)
            .shadow(
                elevation = if (th.isDark) 1.dp else 3.dp,
                shape = pillShape,
                spotColor = Color.Black.copy(alpha = 0.12f)
            ),
        shape = pillShape,
        params = GlassParams.pill(blur = 20f, dark = th.isDark),
        enabled = AppSettings.liquidGlass.value
    ) {
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxSize()
                    .clickable(noRipple, null) { onOpen() }
                    .padding(start = 5.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Cover(current.title, cover, size = 38.dp, radius = 999.dp)
                Spacer(Modifier.width(4.dp))
                Spacer(Modifier.weight(1f))
                MiniTransportButton(playing = ps.playWhenReady, size = 36.dp)
            }
            MiniProgressLine(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            )
        }
    }
}

/** Bouton play/pause du mini-lecteur (morphing + appui). */
@Composable
private fun MiniTransportButton(playing: Boolean, size: androidx.compose.ui.unit.Dp = DesignTokens.Space.touch) {
    val th = LocalTheme.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val haptic = com.ketvoice.app.ui.motion.rememberHaptics()
    Box(
        Modifier
            .pressScale(min = 0.82f, isPressed = pressed)
            .size(size)
            .clip(CircleShape)
            .clickable(src, null) { haptic(Haptics.Kind.Press); Player.toggle() },
        contentAlignment = Alignment.Center
    ) {
        com.ketvoice.app.ui.player.MorphingTransportGlyph(playing, Modifier.size(28.dp), th.onBackground)
    }
}

/** Bouton suivant du mini-lecteur (appui). */
@Composable
private fun MiniTransportNext(size: androidx.compose.ui.unit.Dp = DesignTokens.Space.touch) {
    val th = LocalTheme.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val haptic = com.ketvoice.app.ui.motion.rememberHaptics()
    var taps by remember { mutableIntStateOf(0) }
    Box(
        Modifier
            .pressScale(min = 0.82f, isPressed = pressed)
            .size(size)
            .clip(CircleShape)
            .clickable(src, null) { taps++; haptic(Haptics.Kind.Press); Player.next() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.SkipNext,
            contentDescription = "Suivant",
            tint = th.onBackground,
            modifier = Modifier.size(28.dp).symbolSlide(taps, 1)
        )
    }
}

/** Fine ligne de progression sous le mini-lecteur — collecte la position indépendamment. */
@Composable
private fun MiniProgressLine(modifier: Modifier = Modifier) {
    // Interpolation linéaire continue : plus aucun saut entre les tics de position.
    // Read while drawing (6.6), and followed in an effect (7.6): the line is no longer composed at each 200 ms tick.
    val smooth = rememberProgressGlide()
    // A hairline inset from the pill's edges: a faint track and the accent filling it.
    val accent = LocalTheme.current.accent
    Box(
        modifier
            .padding(start = 18.dp, end = 18.dp, bottom = 3.dp)
            .height(2.5.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(LocalTheme.current.onBackground.copy(alpha = .10f))
            .testTag("mini-progress")
            .drawBehind {
                val filled = androidx.compose.ui.geometry.Size(size.width * smooth.value.coerceIn(0f, 1f), size.height)
                if (filled.width <= 0f) return@drawBehind
                val corner = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                drawRoundRect(accent, size = filled, cornerRadius = corner)
            }
    )
}

private data class TabSpec(val icon: ImageVector, val label: String, val index: Int)

/**
 * Chrome flottant complet : mini-lecteur + navigation + recherche.
 * `collapsed` (scroll vers le bas) condense tout en une seule rangée.
 *
 * 8.0 « Barre d'onglets qui se transforme »: the fold is a spring that starts with the page's own scroll speed
 * ([ChromeCollapse.flingVelocity]) and can be caught and reversed half-way; the tabs melt into one capsule
 * ([GlassTabCapsule]), the mini-player leaves the bar and joins the folded row through the liquid bridge of the
 * fused glass. Under reduced motion the chrome snaps to the other layout.
 */
@Composable
fun FloatingChrome(collapsed: Boolean, onOpenPlayer: () -> Unit, searching: Boolean = false, typing: Boolean = false) {
    // 8.4: the Cristal look has its own bar; Liquid 27 keeps this one, unchanged.
    if (com.ketvoice.app.glass.GlassLook.cristal) { CristalChrome(collapsed, onOpenPlayer, searching, typing); return }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Le repli Accueil + mini + Recherche tient dès 320 dp (52+8+176+8+52+24).
        // L'ancien seuil de 380 dp empêchait l'animation sur les téléphones 360 dp.
        val compact = collapsed && !searching && maxWidth >= 320.dp
        val width = maxWidth
        val hasTrack = Player.state.collectAsState().value.current != null
        // While the keyboard is up only the field stays, just above it.
        val miniShown = hasTrack && !typing
        if (Motion.reduced) {
            // Motion snaps under reduced motion (Ket 27): the other layout is simply there, one body, no crossfade.
            val fold = if (compact) 1f else 0f
            val open = if (searching) 1f else 0f
            ChromeBody(width, { fold }, { open }, compact, searching, miniShown, onOpenPlayer)
            return@BoxWithConstraints
        }
        val target = if (compact) 1f else 0f
        val morph = remember { androidx.compose.animation.core.Animatable(target) }
        val travelPx = with(androidx.compose.ui.platform.LocalDensity.current) { MINI_TRAVEL.toPx() }
        LaunchedEffect(target) {
            // Interruptible: a new target takes over from the current position and speed.
            morph.animateTo(target, spring(dampingRatio = DesignTokens.Motion.CHROME_DAMPING,
                stiffness = DesignTokens.Motion.CHROME_STIFFNESS),
                initialVelocity = morph.velocity + ChromeCollapse.flingVelocity(target, ChromeCollapse.scrollVelocity, travelPx))
        }
        // Phones (6.0.0): the search bubble stretches into the field, a little past it and back, like a drop.
        val stretch = animateFloatAsState(if (searching) 1f else 0f,
            spring(dampingRatio = .78f, stiffness = 360f), label = "search-stretch")
        // 8.1: the springs are read while measuring and drawing; until then the whole chrome (both glass
        // capsules, the mini-player and every tab) was composed again at each frame of the fold.
        ChromeBody(width, remember(morph) { { morph.value } }, remember(stretch) { { stretch.value } },
            compact, searching, miniShown, onOpenPlayer)
    }
}

/** The mini-player's way down from above the bar into the folded row. */
private val MINI_TRAVEL = 66.dp

@Composable
private fun ChromeBody(width: Dp, fraction: () -> Float, stretch: () -> Float, compact: Boolean, searching: Boolean,
                       miniShown: Boolean, onOpenPlayer: () -> Unit) {
    // The spring may pass its end a little; the layout stays within the fold.
    val f = { fraction().coerceIn(0f, 1f) }
    // The tab bar is 62 dp high, as on iOS 27.
    val fullHeight = (if (miniShown) 126.dp else 64.dp) + 2.dp
    // Replié : Accueil (52 dp) + 8 dp + mini-lecteur + 8 dp + Recherche (52 dp).
    val navigationY = { if (miniShown) MINI_TRAVEL * (1f - f()) else 0.dp }
    // Liquid 27: Home, the mini-player and Search are one glass body. At rest they
    // are separate; while the chrome morphs they pull liquid bridges and merge like
    // droplets (the mini-player's neck as it leaves the bar), then split again.
    val fusion = com.ketvoice.app.glass.rememberGlassFusion()
    val fuseReach = with(androidx.compose.ui.platform.LocalDensity.current) { 26.dp.toPx() }
    val reduced = Motion.reduced
    fusion.strengthOf = {
        val morph = maxOf(f(), kotlin.math.sin(Math.PI * stretch().coerceIn(0f, 1f)).toFloat() * .8f)
        if (reduced) 0f else com.ketvoice.app.glass.fusionStrength(morph, fuseReach)
    }
    // Docked once the pill is two thirds of the way: composed again once, not at every frame.
    val compactPill by remember { androidx.compose.runtime.derivedStateOf { f() > .65f } }
    Box(Modifier.fillMaxWidth().layout { measurable, constraints ->
        val h = (fullHeight + (58.dp - fullHeight) * f()).roundToPx().coerceIn(constraints.minHeight, constraints.maxHeight)
        val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }) {
        com.ketvoice.app.glass.GlassFusionLayer(fusion, LocalTheme.current.isDark, Modifier.matchParentSize())
        androidx.compose.runtime.CompositionLocalProvider(com.ketvoice.app.glass.LocalGlassFusion provides fusion) {
        GlassTabCapsule(compact, Modifier.offset { androidx.compose.ui.unit.IntOffset(12.dp.roundToPx(), navigationY().roundToPx()) },
            availableWidth = width, homeOnly = compact || searching,
            onPill = if (searching) ({ AppUi.closeOverlay(); Unit }) else ({ ChromeCollapse.restoreAll() }),
            pillLabel = if (searching) "Fermer la recherche" else "Afficher les onglets")
        if (miniShown) MiniPlayerPill(
            Modifier.layout { measurable, constraints ->
                val w = (width - 144.dp * f()).roundToPx().coerceIn(constraints.minWidth, constraints.maxWidth)
                val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
                layout(placeable.width, placeable.height) { placeable.place((72.dp * f()).roundToPx(), 0) }
            },
            compact = compactPill,
            fold = f,
            onOpen = onOpenPlayer)
        SearchBubble(stretch, searching,
            Modifier.layout { measurable, constraints ->
                val fold = f()
                val restSide = 60f - 8f * fold
                val bubble = FloatingSearch.bubble(stretch(), width.value, restSide, width.value - 76f + 12f * fold, pillEnd = 72f, margin = 16f)
                val bw = bubble.width.dp.roundToPx().coerceIn(constraints.minWidth, constraints.maxWidth)
                val bh = bubble.height.dp.roundToPx().coerceIn(constraints.minHeight, constraints.maxHeight)
                val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(bw, bh))
                layout(placeable.width, placeable.height) {
                    placeable.place(bubble.x.dp.roundToPx(),
                        (navigationY() + ((restSide - bubble.height) / 2f).coerceAtLeast(0f).dp).roundToPx())
                }
            })
        }
    }
}

/** The mini-player's labels fade out while the pill moves, and the title comes back once it has docked (iOS 26). */
internal fun miniLabelAlpha(f: Float): Float = if (f < .5f) (1f - f * 2f) else ((f - .5f) * 2f).coerceIn(0f, 1f)

private val MINI_COVER = 44.dp

/**
 * Lays its content out at full size and shows it scaled by [scale] from the top start corner, taking only the scaled
 * room: a size that follows an animation without composing the content again.
 */
private fun Modifier.foldScale(scale: () -> Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val k = scale().let { if (it.isFinite()) it.coerceIn(0f, 1f) else 1f }
    layout((placeable.width * k).roundToInt(), (placeable.height * k).roundToInt()) {
        placeable.placeWithLayer(0, 0) {
            scaleX = k; scaleY = k
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
        }
    }
}

/**
 * Search, as a glass bubble that stretches into the search field (phones, 6.0.0). At rest a touch opens
 * the search page; stretched, it holds the field shared with that page ([FloatingSearch.query]).
 */
@Composable
private fun SearchBubble(stretchOf: () -> Float, searching: Boolean, modifier: Modifier = Modifier) {
    val th = LocalTheme.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val haptic = com.ketvoice.app.ui.motion.rememberHaptics()
    val shape = RoundedCornerShape(percent = 50)
    // The glyph leaves early and the field arrives late, so the two never read over each other.
    val fieldAlpha = { ((stretchOf() - .5f) / .4f).coerceIn(0f, 1f) }
    val glyphAlpha = { (1f - stretchOf() * 2.2f).coerceIn(0f, 1f) }
    val glyphShown by remember { androidx.compose.runtime.derivedStateOf { glyphAlpha() > 0f } }
    val fieldShown by remember { androidx.compose.runtime.derivedStateOf { stretchOf() > .01f } }
    Box(modifier
        .pressScale(min = .9f, isPressed = pressed && !searching)
        .shadow(if (th.isDark) 0.dp else 2.5.dp, shape, spotColor = Color.Black.copy(alpha = 0.12f))
        .testTag("search-bubble")) {
        GlassPanel(Modifier.matchParentSize(), shape, GlassParams.circle(dark = th.isDark), enabled = AppSettings.liquidGlass.value) {}
        // The glyph gives way to the field as the bubble stretches.
        val glyph: @Composable (String?) -> Unit = { label ->
            Icon(com.ketvoice.app.ui.common.Ket27Glyphs.Search, contentDescription = label, tint = th.onBackground,
                modifier = Modifier.size(25.dp).graphicsLayer { alpha = glyphAlpha() })
        }
        if (!searching) Box(Modifier.matchParentSize().clip(shape)
            .clickable(src, null, role = Role.Button) { haptic(Haptics.Kind.Press); FloatingSearch.open() },
            contentAlignment = Alignment.Center) { glyph("Rechercher") }
        else if (glyphShown) Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) { glyph(null) }
        if (searching || fieldShown) {
            val requester = remember { androidx.compose.ui.focus.FocusRequester() }
            val focus = androidx.compose.ui.platform.LocalFocusManager.current
            val asked = FloatingSearch.focusAsked.value
            LaunchedEffect(searching, asked) {
                if (searching && asked) {
                    // A frame for the field to be placed before it takes the focus (and brings the keyboard).
                    kotlinx.coroutines.delay(32)
                    runCatching { requester.requestFocus() }
                    FloatingSearch.focusAsked.value = false
                }
                if (!searching) focus.clearFocus()
            }
            com.ketvoice.app.ui.common.CompactSearchField(FloatingSearch.query.value, { FloatingSearch.query.value = it },
                { FloatingSearch.onSubmit?.invoke() },
                Modifier.matchParentSize().graphicsLayer { alpha = fieldAlpha() }
                    .focusRequester(requester).testTag("floating-search-field"),
                filled = false)
        }
    }
}

@Composable
private fun SearchCircleButton(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val th = LocalTheme.current
    com.ketvoice.app.ui.common.LiquidGlassButton(
        onClick = { AppUi.openSearch() },
        modifier = modifier
            .size(size)
            .shadow(
                elevation = if (th.isDark) 0.dp else 2.5.dp,
                shape = CircleShape,
                spotColor = Color.Black.copy(alpha = 0.12f)
            ),
        shape = CircleShape
    ) {
        Box(
            Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                com.ketvoice.app.ui.common.Ket27Glyphs.Search,
                contentDescription = "Rechercher",
                tint = th.onBackground,
                modifier = Modifier.size(size * 0.42f)
            )
        }
    }
}

/** Capsule d'onglets en verre : pleine (labels + lentille glissante) ou réduite (icônes). */
@Composable
internal fun GlassTabCapsule(collapsed: Boolean, modifier: Modifier = Modifier,
                             availableWidth: Dp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp,
                             homeOnly: Boolean = false, onPill: () -> Unit = { ChromeCollapse.restoreAll() },
                             pillLabel: String = "Afficher les onglets") {
    val th = LocalTheme.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val haptic = com.ketvoice.app.ui.motion.rememberHaptics()
    var held by remember { mutableStateOf(false) }
    var hover by remember { mutableFloatStateOf(0f) }
    // Ket 27 draws its own symbols, in the manner of iOS.
    val tabs = listOf(
        TabSpec(com.ketvoice.app.ui.common.Ket27Glyphs.Home, "Accueil", AppUi.HOME),
        TabSpec(com.ketvoice.app.ui.common.Ket27Glyphs.Explore, "Explorer", AppUi.EXPLORER),
        TabSpec(com.ketvoice.app.ui.common.Ket27Glyphs.Library, "Bibliothèque", AppUi.LIBRARY)
    )

    val fullWidth = (availableWidth - 92.dp).coerceIn(144.dp, 310.dp)
    // Read in the layout and draw phases only: the folding bar is not composed again at every frame.
    val widthState = animateDpAsState(
        targetValue = when {
            homeOnly -> FOLDED_SIDE
            collapsed -> 144.dp
            else -> fullWidth
        },
        animationSpec = if (Motion.reduced) androidx.compose.animation.core.snap() else spring(
            dampingRatio = DesignTokens.Motion.CHROME_DAMPING, stiffness = DesignTokens.Motion.CHROME_STIFFNESS),
        label = "tabWidth"
    )
    // 8.0: 0 the full bar .. 1 folded into one capsule, from its width; the tabs melt along it.
    val melt = { TabMelt.fold(widthState.value.value, fullWidth.value, FOLDED_SIDE.value) }
    // While the capsule is still wider than its folded size the tabs stay, melting; then the pill takes over.
    val melting by remember(fullWidth) { androidx.compose.runtime.derivedStateOf { melt() < TabMelt.PILL_FROM } }
    // Read while measuring (8.1): the capsule is not composed again at each frame of its fold.
    val heightState = animateDpAsState(
        targetValue = if (homeOnly || collapsed) 52.dp else Ket27.Metrics.tabBar,
        animationSpec = if (Motion.reduced) androidx.compose.animation.core.snap() else spring(
            dampingRatio = DesignTokens.Motion.CHROME_DAMPING, stiffness = DesignTokens.Motion.CHROME_STIFFNESS),
        label = "tabHeight"
    )
    val folded = homeOnly && !melting
    val full = !folded && (!collapsed || homeOnly)
    // 8.3 « Pastille physique »: the selection is moved by PillPhysics; held, it follows the finger as a lens.
    val current = tabs.indexOfFirst { it.index == AppUi.tab.value }.coerceAtLeast(0)
    val selected = if (held) kotlin.math.round(hover).toInt() else current
    val pill = remember { PillMotion(current.toFloat()) }
    val reduced = Motion.reduced
    androidx.compose.runtime.SideEffect {
        pill.target = if (held) hover else selected.toFloat(); pill.held = held; pill.reduced = reduced
    }
    LaunchedEffect(pill) { pill.run() }
    // Lifted, the pill bends the bar's own glass (recorded into barGlass while it is up); the refractive glass only.
    val optical = com.ketvoice.app.glass.currentGlassRenderMode(com.ketvoice.app.glass.GlassStyles.current,
        AppSettings.liquidGlass.value, captured = false) == com.ketvoice.app.glass.GlassRenderMode.Optical
    val lifted by remember(pill) { androidx.compose.runtime.derivedStateOf { pill.lift > TabPill.LENS_FROM } }
    val barGlass = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    // The bar records its glass for the lens only while the pill is up: at rest it costs nothing.
    val lens = full && optical && lifted

    Box(
        modifier = modifier
            .layout { measurable, constraints ->
                val w = widthState.value.roundToPx().coerceIn(constraints.minWidth, constraints.maxWidth)
                val h = heightState.value.roundToPx().coerceIn(constraints.minHeight, constraints.maxHeight)
                val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w, minHeight = h, maxHeight = h))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .pointerInput(collapsed, homeOnly) {
                if (homeOnly || collapsed) return@pointerInput
                // One gesture grabs the lens: a hold, or a slide along the bar. A plain tap is left to the tabs.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Null: still held when the long press is reached, which grabs it too.
                    val grabbed = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var result: Boolean? = null
                        while (result == null) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            result = when {
                                change == null || !change.pressed -> false
                                kotlin.math.abs(change.position.x - down.position.x) > viewConfiguration.touchSlop -> true
                                else -> null
                            }
                        }
                        result
                    } ?: true
                    if (!grabbed) return@awaitEachGesture
                    held = true
                    hover = lensPosition(down.position.x, size.width.toFloat())
                    var released = false
                    while (!released) {
                        // Read before the tabs, so that letting go of the lens is not also a tap on a tab.
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (change.pressed) hover = lensPosition(change.position.x, size.width.toFloat()) else released = true
                    }
                    if (released) {
                        focus.clearFocus()
                        haptic(Haptics.Kind.Tick)
                        AppUi.selectTab(kotlin.math.round(hover).toInt().coerceIn(0, 2))
                    }
                    held = false
                }
            }
    ) {
        GlassPanel(
            modifier = Modifier.matchParentSize(),
            shape = RoundedCornerShape(30.dp),
            params = GlassParams.navigation(dark = th.isDark),
            enabled = AppSettings.liquidGlass.value,
            export = if (lens) barGlass else null
        ) {
            if (folded) FoldedTabPill(tabs, onPill, pillLabel)
            else if (!full) CompactTabs(tabs, heightState)
        }
        // The full bar draws over its glass, outside it: the lifted pill rises a little past the bar's edges.
        if (full) androidx.compose.runtime.CompositionLocalProvider(com.ketvoice.app.glass.LocalGlassFusion provides null,
            com.ketvoice.app.glass.LocalOnGlass provides true) {
            FullTabs(Modifier.matchParentSize(), tabs, pill, held, selected, hover, heightState, melt, barGlass.takeIf { optical })
        }
    }
}

/**
 * Folded (Settings → "Barre d'onglets réductible", iOS 26): the bar is a pill showing the tab you are on; a touch
 * unfolds it again where the page is.
 */
@Composable
private fun FoldedTabPill(tabs: List<TabSpec>, onPill: () -> Unit, pillLabel: String) {
    val th = LocalTheme.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val haptic = com.ketvoice.app.ui.motion.rememberHaptics()
    val current = tabs.firstOrNull { it.index == AppUi.tab.value } ?: tabs.first()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = Motion.pressSpring(pressed),
        label = "homePress"
    )
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { scaleX = press; scaleY = press }
            .testTag("folded-tab-pill")
            .clickable(interactionSource = src, indication = null, role = Role.Button,
                onClickLabel = com.ketvoice.app.data.AppText.t(pillLabel)) {
                focus.clearFocus()
                haptic(Haptics.Kind.Tick)
                onPill()
            },
        contentAlignment = Alignment.Center
    ) {
        // The selected tab's tint, as in the bar it melted from.
        Icon(
            current.icon,
            contentDescription = current.label,
            tint = th.accent,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * Where the selection pill is drawn: a third of the capsule per tab, flowing to the middle one as the bar melts,
 * taller and wider as it lifts, stretched by its acceleration. Read while laying out and drawing only.
 */
private fun Modifier.tabPill(pill: PillMotion, height: androidx.compose.runtime.State<Dp>, melt: () -> Float): Modifier = this
    .layout { measurable, constraints ->
        val slot = constraints.maxWidth / 3f
        val lift = pill.lift
        val growW = TabPill.LIFT_WIDTH_DP.dp.toPx() * lift
        val w = (slot - 10.dp.toPx() + growW).roundToInt().coerceAtLeast(0)
        val h = ((height.value - 10.dp).toPx() + TabPill.LIFT_HEIGHT_DP.dp.toPx() * lift).roundToInt().coerceAtLeast(0)
        val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
        val x = slot * pill.x + 5.dp.toPx() - growW / 2f + TabMelt.towardsMiddle(pill.x, slot, melt())
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(x.roundToInt(), (constraints.maxHeight - h) / 2)
        }
    }
    .graphicsLayer {
        scaleX = pill.stretchX; scaleY = pill.stretchY
        alpha = TabMelt.lensAlpha(melt())
    }

/** Places its content only while the pill is lifted: at rest the lens is not drawn at all. */
private fun Modifier.whileLifted(pill: PillMotion): Modifier = this
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { if (pill.lift > TabPill.LENS_FROM) placeable.place(0, 0) }
    }
    .then(Modifier.testTag("tab-pill-lens"))

/** The full bar's selection (flat pill and, while lifted, its lens over [barGlass]), its three tabs and, held, its dots. */
@Composable
private fun FullTabs(modifier: Modifier, tabs: List<TabSpec>, pill: PillMotion, held: Boolean, selected: Int, hover: Float,
                     height: androidx.compose.runtime.State<Dp>, melt: () -> Float,
                     barGlass: com.kyant.backdrop.backdrops.LayerBackdrop?) {
    val th = LocalTheme.current
    val haptic = com.ketvoice.app.ui.motion.rememberHaptics()
    val lastHoverTab = remember { mutableIntStateOf(-1) }
    // Each tab crossed by the held lens is felt, as on iOS.
    if (held) {
        val over = kotlin.math.round(hover).toInt().coerceIn(0, 2)
        LaunchedEffect(over) { if (lastHoverTab.intValue != -1 && lastHoverTab.intValue != over) haptic(Haptics.Kind.Tick); lastHoverTab.intValue = over }
    } else lastHoverTab.intValue = -1
    Box(modifier) {
        val pillShape = RoundedCornerShape(999.dp)
        // The flat pill: a real GlassPanel over the page (refraction, rim light, shadow), not a white capsule.
        GlassPanel(
            modifier = Modifier.tabPill(pill, height, melt)
                // A seasonal theme (6.2): the selection wears a thin edge of its colours.
                .then(th.season?.let { look -> with(look) { Modifier.seasonEdge(pillShape, true) } } ?: Modifier),
            shape = pillShape,
            params = GlassParams.selection(dark = th.isDark),
            enabled = AppSettings.liquidGlass.value
        ) {}
        // Lifted, a lens of clear glass over the bar's own glass takes over, and hands back to the flat pill as it sinks.
        // It stays composed (its glass has formed once) and is laid out only while the pill is up.
        if (barGlass != null) androidx.compose.runtime.CompositionLocalProvider(com.ketvoice.app.glass.LocalBackdropLayer provides barGlass) {
            val lensParams = remember(th.isDark) { TabPill.lensParams(th.isDark) }
            GlassPanel(
                modifier = Modifier.whileLifted(pill).tabPill(pill, height, melt).graphicsLayer { alpha = TabPill.lensAlpha(pill.lift) },
                shape = pillShape, params = lensParams, enabled = AppSettings.liquidGlass.value
            ) {}
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 4.dp).selectableGroup()) {
            tabs.forEach { tab -> FullTab(tab, selected == tab.index, pill, melt) }
        }
        if (held) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { dot -> Box(Modifier.size(3.dp).clip(CircleShape)
                .background(th.onBackground.copy(alpha = if (dot == selected) .9f else .3f))) }
        }
    }
}

/** One tab of the full bar: its symbol and label, magnified under the held lens. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.FullTab(tab: TabSpec, isSel: Boolean, pill: PillMotion, melt: () -> Float) {
    val th = LocalTheme.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val haptic = com.ketvoice.app.ui.motion.rememberHaptics()
    val tabSource = remember(tab.index) { MutableInteractionSource() }
    val pressed by tabSource.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = Motion.pressSpring(pressed),
        label = "tabPress"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isSel) 1.07f else 1f,
        animationSpec = if (Motion.reduced) androidx.compose.animation.core.snap() else spring(dampingRatio = 0.78f, stiffness = 620f),
        label = "tabIcon"
    )
    val iconAlpha by animateFloatAsState(
        targetValue = if (isSel) 1f else 0.66f,
        animationSpec = tween(Motion.duration(190)),
        label = "tabIconAlpha"
    )
    val lift by animateFloatAsState(
        targetValue = if (isSel) 1.2f else 0f,
        animationSpec = if (Motion.reduced) androidx.compose.animation.core.snap() else spring(dampingRatio = 0.8f, stiffness = 600f),
        label = "tabLift"
    )
    // iOS tints the selected tab with the app's colour.
    val contentColor by animateColorAsState(
        targetValue = if (isSel) th.accent else th.onBackground.copy(alpha = 0.76f),
        animationSpec = tween(Motion.duration(180)),
        label = "tabColor"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = press; scaleY = press
                // Melting: every tab flows to the middle, the others fade, the current one stays.
                val m = melt()
                translationX = TabMelt.towardsMiddle(tab.index.toFloat(), size.width, m)
                alpha = if (isSel) 1f else TabMelt.otherAlpha(m)
            }
            .selectable(isSel, tabSource, null, role = Role.Tab) {
                focus.clearFocus()
                if (!isSel) haptic(Haptics.Kind.Tick)
                AppUi.selectTab(tab.index)
            }
    ) {
        Icon(
            tab.icon,
            contentDescription = tab.label,
            tint = contentColor.copy(alpha = iconAlpha),
            modifier = Modifier
                .size(23.dp).symbolBounce(isSel, enabled = isSel)
                .graphicsLayer {
                    // Under the held lens the tab is magnified, the more the closer the lens.
                    val magnified = 1f + HeldLens.magnify(pill.held, pill.lift, pill.x, tab.index)
                    scaleX = iconScale * magnified
                    scaleY = iconScale * magnified
                    // The icon settles to the middle of the capsule as its label leaves.
                    translationY = (-lift + TabMelt.ICON_DROP_DP * melt()) * density
                }
        )
        Spacer(Modifier.height(3.dp))
        Text(
            tab.label,
            modifier = Modifier.graphicsLayer { alpha = TabMelt.labelAlpha(melt()) },
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = contentColor.copy(alpha = iconAlpha),
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis
        )
    }
}

/** The collapsed bar: three symbols and the same lens language on a smaller scale. */
@Composable
private fun CompactTabs(tabs: List<TabSpec>, height: androidx.compose.runtime.State<Dp>) {
    val th = LocalTheme.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val haptic = com.ketvoice.app.ui.motion.rememberHaptics()
    val selected = tabs.indexOfFirst { it.index == AppUi.tab.value }.coerceAtLeast(0)
    val pos by animateFloatAsState(
        targetValue = selected.toFloat(),
        animationSpec = if (Motion.reduced) androidx.compose.animation.core.snap()
        else spring(dampingRatio = 0.86f, stiffness = 520f),
        label = "compactLens"
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val slot = maxWidth / 3
        // The same lens language survives the collapsed state instead of
        // turning into three static icons.
        GlassPanel(
            Modifier
                .offset(x = slot * pos + 5.dp)
                .size(width = slot - 10.dp, height = height.value - 14.dp)
                .align(Alignment.CenterStart)
                .then(th.season?.let { look -> with(look) { Modifier.seasonEdge(RoundedCornerShape(999.dp), true) } } ?: Modifier),
            RoundedCornerShape(999.dp),
            GlassParams.selection(dark = th.isDark),
            enabled = AppSettings.liquidGlass.value
        ) {}
        Row(Modifier.fillMaxSize().selectableGroup()) {
            tabs.forEach { tab ->
                val isSel = AppUi.tab.value == tab.index
                val tabSource = remember(tab.index) { MutableInteractionSource() }
                val pressed by tabSource.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = when {
                        pressed -> 0.9f
                        isSel -> 1.06f
                        else -> 1f
                    },
                    animationSpec = Motion.pressSpring(pressed),
                    label = "compactIcon"
                )
                val alpha by animateFloatAsState(
                    targetValue = if (isSel) 1f else 0.68f,
                    animationSpec = tween(Motion.duration(180)),
                    label = "compactAlpha"
                )
                Box(Modifier.weight(1f).fillMaxHeight()
                    .selectable(isSel, tabSource, null, role = Role.Tab) {
                        focus.clearFocus(); if (!isSel) haptic(Haptics.Kind.Tick); AppUi.selectTab(tab.index)
                    }, contentAlignment = Alignment.Center) {
                    Icon(
                        tab.icon, tab.label,
                        tint = th.onBackground.copy(alpha = alpha),
                        modifier = Modifier.size(25.dp).symbolBounce(isSel, enabled = isSel).graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                    )
                }
            }
        }
    }
}

@Deprecated("Remplacé par FloatingChrome (mini-lecteur + navigation + recherche)", ReplaceWith("FloatingChrome(false, onOpenPlayer)"))
@Composable
fun GlassBottomBar(
    modifier: Modifier = Modifier,
    collapsed: Boolean
) {
    Row(modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassTabCapsule(collapsed = collapsed, modifier = Modifier)
        Spacer(Modifier.width(10.dp))
        SearchCircleButton(size = if (collapsed) 52.dp else 60.dp)
    }
}

private val FOLDED_SIDE = 52.dp

/**
 * 8.0: the tabs melting into one capsule as the bar folds. [fold] 0 is the full bar, 1 the folded capsule; every
 * tab flows towards the middle slot, the labels leave first, the other tabs fade, and once the capsule is nearly
 * folded ([PILL_FROM]) the single pill of the current tab takes over.
 */
internal object TabMelt {
    const val PILL_FROM = .92f
    /** Half the label and its gap: the icon's way down to the capsule's middle. */
    const val ICON_DROP_DP = 8f

    fun fold(width: Float, full: Float, folded: Float): Float =
        if (full <= folded || !width.isFinite()) 0f else ((full - width) / (full - folded)).coerceIn(0f, 1f)

    /** Offset of the item in slot [index] (0..2) towards the middle slot, [slotPx] wide. */
    fun towardsMiddle(index: Float, slotPx: Float, fold: Float): Float = (1f - index) * slotPx * fold

    fun labelAlpha(fold: Float): Float = (1f - fold * 2.5f).coerceIn(0f, 1f)
    fun otherAlpha(fold: Float): Float = (1f - fold * 1.7f).coerceIn(0f, 1f)
    fun lensAlpha(fold: Float): Float = (1f - fold * 1.4f).coerceIn(0f, 1f)
}

internal fun lensPosition(x: Float, width: Float): Float = (x * 3f / width.coerceAtLeast(1f) - .5f).coerceIn(0f, 2f)

private fun Modifier.graphicsLayerScale(scale: Float): Modifier =
    this.then(
        Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    )

/** The selection of the tab bar held under a finger (iOS 26 and 27): a lens that clears and magnifies. */
internal object HeldLens {
    /** How much the tab under the lens is magnified. */
    const val MAGNIFY = .32f

    /** How much the symbol of tab [index] is magnified: only under a held lens at [x] (slots), as far as it is lifted. */
    fun magnify(held: Boolean, lift: Float, x: Float, index: Int): Float {
        if (!held || !lift.isFinite() || !x.isFinite()) return 0f
        val under = (1f - kotlin.math.abs(x - index)).coerceIn(0f, 1f)
        return MAGNIFY * lift.coerceIn(0f, 1f) * under
    }
}

/**
 * The playing title's progress (0..1), gliding linearly between the position ticks (7.6). Read it while drawing:
 * it changes at every frame while a title plays, and the composable holding it is never composed again for it.
 */
@Composable
internal fun rememberProgressGlide(): androidx.compose.runtime.State<Float> {
    val glide = remember { androidx.compose.animation.core.Animatable(0f) }
    val reduced = Motion.reduced
    LaunchedEffect(reduced) {
        val durations = Player.state.map { it.durationMs.coerceAtLeast(1L) }.distinctUntilChanged()
        Player.positionMs.combine(durations) { p, d -> (p.toFloat() / d).coerceIn(0f, 1f) }.distinctUntilChanged()
            .collectLatest { f ->
                if (reduced) glide.snapTo(f)
                else glide.animateTo(f, tween(durationMillis = 500, easing = androidx.compose.animation.core.LinearEasing))
            }
    }
    return glide.asState()
}
