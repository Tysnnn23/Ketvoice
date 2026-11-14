package com.ketvoice.app.ui.chrome

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
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
    height: Dp = 54.dp,
    coverSize: Dp = 44.dp,
    playSize: Dp = DesignTokens.Space.touch,
    labelAlpha: Float = 1f,
    onOpen: () -> Unit
) {
    val ps = Player.state.collectAsState().value
    val th = LocalTheme.current
    val current = ps.current ?: return
    val art by ArtworkRepo.covers.collectAsState()
    val cover = art["t:${current.id}"] ?: current.coverUrl

    val pillShape = RoundedCornerShape(27.dp)
    // Swipe the pill sideways to change track; the collapsed pill is too small for it.
    val swipe = com.ketvoice.app.ui.player.rememberTrackSwipe()
    GlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .height(height)
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
                    .then(if (compact) Modifier else Modifier.trackSwipe(swipe))
                    .semantics {
                        customActions = listOf(
                            androidx.compose.ui.semantics.CustomAccessibilityAction(com.ketvoice.app.data.AppText.t("Suivant")) { Player.next(); true },
                            androidx.compose.ui.semantics.CustomAccessibilityAction(com.ketvoice.app.data.AppText.t("Précédent")) { Player.previous(); true })
                    }
                    .clickable(noRipple, null) { onOpen() }
                    .padding(start = 6.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Cover(
                    current.title,
                    cover,
                    size = coverSize,
                    radius = if (compact) 999.dp else 9.dp,
                    modifier = Modifier.onGloballyPositioned {
                        AppUi.miniCoverBounds.value = it.boundsInRoot()
                    }
                )
                Spacer(Modifier.width(if (compact) 4.dp else 11.dp))
                Column(
                    Modifier.weight(1f).graphicsLayer { alpha = labelAlpha }
                        .trackSwipeContent(swipe, travel = .55f)
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
                    Text(
                        current.artist,
                        localize = false,
                        style = T.sub.copy(fontSize = 12.5.sp),
                        color = th.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(4.dp))
                MiniTransportButton(playing = ps.playWhenReady, size = playSize)
                Spacer(Modifier.width(2.dp))
                MiniTransportNext()
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
    val cover = art["t:${current.id}"] ?: current.coverUrl
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
    Box(
        Modifier
            .pressScale(min = 0.82f, isPressed = pressed)
            .size(size)
            .clip(CircleShape)
            .clickable(src, null) { haptic(Haptics.Kind.Press); Player.next() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.SkipNext,
            contentDescription = "Suivant",
            tint = th.onBackground,
            modifier = Modifier.size(28.dp)
        )
    }
}

/** Fine ligne de progression sous le mini-lecteur — collecte la position indépendamment. */
@Composable
private fun MiniProgressLine(modifier: Modifier = Modifier) {
    val posMs by Player.positionMs.collectAsState()
    val durationMs = Player.state.collectAsState().value.durationMs.coerceAtLeast(1L)
    val fraction = (posMs.toFloat() / durationMs).coerceIn(0f, 1f)
    // Interpolation linéaire continue : plus aucun saut entre les tics de position.
    val smooth by animateFloatAsState(
        targetValue = fraction,
        animationSpec = if (Motion.reduced) androidx.compose.animation.core.snap()
        else tween(durationMillis = 500, easing = androidx.compose.animation.core.LinearEasing),
        label = "miniProgress"
    )
    Box(
        modifier
            .height(3.dp)
            .background(Color.Transparent)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(smooth)
                .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                .background(LocalTheme.current.accent)
        )
    }
}

private data class TabSpec(val icon: ImageVector, val label: String, val index: Int)

/**
 * Chrome flottant complet : mini-lecteur + navigation + recherche.
 * `collapsed` (scroll vers le bas) condense tout en une seule rangée.
 */
@Composable
fun FloatingChrome(collapsed: Boolean, onOpenPlayer: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Le repli Accueil + mini + Recherche tient dès 320 dp (52+8+176+8+52+24).
        // L'ancien seuil de 380 dp empêchait l'animation sur les téléphones 360 dp.
        val compact = collapsed && maxWidth >= 320.dp
        // Base compactness on the actual pane, not the device's full window width.
        val fraction by animateFloatAsState(if (compact) 1f else 0f,
            if (Motion.reduced) androidx.compose.animation.core.snap() else spring(
                dampingRatio = DesignTokens.Motion.CHROME_DAMPING,
                stiffness = DesignTokens.Motion.CHROME_STIFFNESS), label = "chrome-morph")
        val width = maxWidth
        val f = fraction
        val hasTrack = Player.state.collectAsState().value.current != null
        val fullHeight = if (hasTrack) 126.dp else 64.dp
        val height = fullHeight + (58.dp - fullHeight) * f
        // Replié : Accueil (52 dp) + 8 dp + mini-lecteur + 8 dp + Recherche (52 dp).
        val miniWidth = maxWidth - 144.dp * f
        val miniX = 72.dp * f
        val miniHeight = 54.dp - 6.dp * f
        val coverSize = 44.dp - 12.dp * f
        val labelAlpha = (1f - f * 1.8f).coerceIn(0f, 1f)
        val navigationY = if (hasTrack) 66.dp * (1f - f) else 0.dp
        // Liquid 27: Home, the mini-player and Search are one glass body. At rest they
        // are separate; while the chrome morphs they pull liquid bridges and merge like
        // droplets, then split again. Other styles and fallbacks keep three panels.
        val fusion = com.ketvoice.app.glass.rememberGlassFusion()
        val fuseReach = with(androidx.compose.ui.platform.LocalDensity.current) { 26.dp.toPx() }
        fusion.strengthPx = if (Motion.reduced) 0f else com.ketvoice.app.glass.fusionStrength(f, fuseReach)
        Box(Modifier.fillMaxWidth().height(height)) {
            com.ketvoice.app.glass.GlassFusionLayer(fusion, LocalTheme.current.isDark, Modifier.matchParentSize())
            androidx.compose.runtime.CompositionLocalProvider(com.ketvoice.app.glass.LocalGlassFusion provides fusion) {
            GlassTabCapsule(compact, Modifier.offset(x = 12.dp, y = navigationY),
                availableWidth = width, homeOnly = compact)
            if (hasTrack) MiniPlayerPill(
                Modifier.offset(x = miniX).width(miniWidth),
                compact = f > .65f,
                height = miniHeight,
                coverSize = coverSize,
                labelAlpha = labelAlpha,
                onOpen = onOpenPlayer)
            SearchCircleButton(size = 60.dp - 8.dp * f,
                modifier = Modifier.offset(x = width - 76.dp + 12.dp * f, y = navigationY))
            }
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
                Icons.Filled.Search,
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
                             homeOnly: Boolean = false) {
    val th = LocalTheme.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val haptic = com.ketvoice.app.ui.motion.rememberHaptics()
    var held by remember { mutableStateOf(false) }
    var hover by remember { mutableFloatStateOf(0f) }
    val bulge by animateFloatAsState(if (held && !Motion.reduced) 1.055f else 1f,
        if (Motion.reduced) androidx.compose.animation.core.snap() else spring(dampingRatio = .82f, stiffness = 500f), label = "held-glass")
    val tabs = listOf(
        TabSpec(Icons.Filled.Home, "Accueil", AppUi.HOME),
        TabSpec(Icons.Filled.GridView, "Explorer", AppUi.EXPLORER),
        TabSpec(Icons.Filled.LibraryMusic, "Bibliothèque", AppUi.LIBRARY)
    )

    val widthAnim by animateDpAsState(
        targetValue = when {
            homeOnly -> 52.dp
            collapsed -> 144.dp
            else -> (availableWidth - 92.dp).coerceIn(144.dp, 310.dp)
        },
        animationSpec = if (Motion.reduced) androidx.compose.animation.core.snap() else spring(
            dampingRatio = DesignTokens.Motion.CHROME_DAMPING, stiffness = DesignTokens.Motion.CHROME_STIFFNESS),
        label = "tabWidth"
    )
    val heightAnim by animateDpAsState(
        targetValue = if (homeOnly || collapsed) 52.dp else 60.dp,
        animationSpec = if (Motion.reduced) androidx.compose.animation.core.snap() else spring(
            dampingRatio = DesignTokens.Motion.CHROME_DAMPING, stiffness = DesignTokens.Motion.CHROME_STIFFNESS),
        label = "tabHeight"
    )

    GlassPanel(
        modifier = modifier
            .width(widthAnim)
            .height(heightAnim)
            .pointerInput(collapsed, homeOnly) {
                if (homeOnly) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { start -> held = true; hover = lensPosition(start.x, size.width.toFloat()) },
                    onDrag = { change, _ ->
                        change.consume()
                        hover = lensPosition(change.position.x, size.width.toFloat())
                    },
                    onDragEnd = {
                        focus.clearFocus()
                        haptic(Haptics.Kind.Tick)
                        AppUi.selectTab(kotlin.math.round(hover).toInt().coerceIn(0, 2))
                        held = false
                    }, onDragCancel = { held = false })
            },
        shape = RoundedCornerShape(30.dp),
        params = GlassParams.navigation(dark = th.isDark),
        enabled = AppSettings.liquidGlass.value
    ) {
        if (homeOnly) {
            // Replié : la capsule entière devient un bouton Accueil en verre.
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
                    .clickable(interactionSource = src, indication = null, role = Role.Button) {
                        focus.clearFocus()
                        haptic(Haptics.Kind.Tick)
                        AppUi.selectTab(AppUi.HOME)
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Home,
                    contentDescription = "Accueil",
                    tint = th.onBackground,
                    modifier = Modifier.size(24.dp)
                )
            }
        } else if (!collapsed) {
            // Sélection glissante : la pilule se déplace d'onglet en onglet.
            val selected = if (held) kotlin.math.round(hover).toInt() else tabs.indexOfFirst { it.index == AppUi.tab.value }.coerceAtLeast(0)
            // Droplet lens: the edge facing the destination leaves first on a stiff spring,
            // the other edge follows on a softer one, so the lens stretches like a drop
            // pulled between two tabs and then gathers itself at the destination.
            val lensTarget = if (held) hover else selected.toFloat()
            val lensLead by animateFloatAsState(
                targetValue = lensTarget,
                animationSpec = if (Motion.reduced) androidx.compose.animation.core.snap() else spring(dampingRatio = 0.82f, stiffness = 900f),
                label = "lensLead"
            )
            val lensTrail by animateFloatAsState(
                targetValue = lensTarget,
                animationSpec = if (Motion.reduced) androidx.compose.animation.core.snap() else spring(dampingRatio = 0.9f, stiffness = 330f),
                label = "lensTrail"
            )
            Box(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val slot = maxWidth / 3
                    // Lentille de verre mobile : un vrai GlassPanel (réfraction,
                    // reflets, ombre), pas une capsule blanche. Elle s'étire un
                    // peu pendant le déplacement puis se stabilise.
                    val pillShape = RoundedCornerShape(999.dp)
                    val lens = dropLens(lensLead, lensTrail)
                    GlassPanel(
                        modifier = Modifier
                            .offset(x = slot * lens.start + 5.dp)
                            .size(width = slot * lens.span - 10.dp, height = heightAnim - 10.dp)
                            .align(Alignment.CenterStart)
                            .graphicsLayer { scaleX = bulge; scaleY = bulge * lens.squash },
                        shape = pillShape,
                        params = GlassParams.selection(dark = th.isDark),
                        enabled = AppSettings.liquidGlass.value
                    ) {}
                }
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp).selectableGroup()
                ) {
                    tabs.forEach { tab ->
                        val isSel = selected == tab.index
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
                        // Weight morphs numerically: no bold/semibold swap that
                        // would reflow the label in one frame.
                        val weight by animateFloatAsState(
                            targetValue = if (isSel) 700f else 500f,
                            animationSpec = if (Motion.reduced) androidx.compose.animation.core.snap() else spring(dampingRatio = 0.9f, stiffness = 520f),
                            label = "tabWeight"
                        )
                        val targetContentColor = when {
                            isSel && !th.isDark -> Color(0xFF141414)
                            isSel && th.isDark -> Color.White
                            else -> th.onBackground.copy(alpha = 0.76f)
                        }
                        val contentColor by animateColorAsState(
                            targetValue = targetContentColor,
                            animationSpec = tween(Motion.duration(180)),
                            label = "tabColor"
                        )
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .graphicsLayer { scaleX = press; scaleY = press }
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
                                    .size(23.dp)
                                    .graphicsLayer {
                                        scaleX = iconScale
                                        scaleY = iconScale
                                        translationY = -lift * density
                                    }
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                tab.label,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight(weight.toInt().coerceIn(100, 900)),
                                color = contentColor.copy(alpha = iconAlpha),
                                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (held) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(3) { dot -> Box(Modifier.size(3.dp).clip(CircleShape)
                        .background(th.onBackground.copy(alpha = if (dot == selected) .9f else .3f))) }
                }
            }
        } else {
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
                        .size(width = slot - 10.dp, height = heightAnim - 14.dp)
                        .align(Alignment.CenterStart),
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
                                modifier = Modifier.size(25.dp).graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                }
                            )
                        }
                    }
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

internal fun lensPosition(x: Float, width: Float): Float = (x * 3f / width.coerceAtLeast(1f) - .5f).coerceIn(0f, 2f)

private fun Modifier.graphicsLayerScale(scale: Float): Modifier =
    this.then(
        Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    )

/** Droplet lens geometry, in tab slots: where it starts, how many slots it spans, and its squash. */
internal data class DropLens(val start: Float, val span: Float, val squash: Float)

/**
 * The lens spans from its slower edge to its faster edge plus one slot. At rest both edges
 * agree and it is exactly one slot wide; mid-flight it is longer and slightly thinner.
 */
internal fun dropLens(lead: Float, trail: Float): DropLens {
    val a = if (lead.isFinite()) lead else 0f
    val b = if (trail.isFinite()) trail else a
    val stretch = kotlin.math.abs(a - b).coerceAtMost(1.2f)
    return DropLens(minOf(a, b), 1f + stretch, 1f - (stretch * .1f).coerceAtMost(.08f))
}
