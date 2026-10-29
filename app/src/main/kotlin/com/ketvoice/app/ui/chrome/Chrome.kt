package com.ketvoice.app.ui.chrome

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.Palette
import com.ketvoice.app.theme.T
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.EBadge
import com.ketvoice.app.ui.player.pressScale

/**
 * Chrome flottant iOS 26 : pilule mini-lecteur (avec progression) + capsule
 * d'onglets en verre liquide à sélection glissante + bouton recherche.
 */

private val noRipple: MutableInteractionSource = MutableInteractionSource()

@Composable
fun MiniPlayerPill(modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val ps = Player.state.collectAsState().value
    val th = LocalTheme.current
    val current = ps.current ?: return
    val art by ArtworkRepo.covers.collectAsState()
    val cover = art["t:${current.id}"] ?: current.coverUrl

    val pillShape = RoundedCornerShape(29.dp)
    GlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .height(58.dp)
            .shadow(
                elevation = if (th.isDark) 2.dp else 4.dp,
                shape = pillShape,
                spotColor = Color.Black.copy(alpha = 0.14f)
            )
            .border(
                0.75.dp,
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (th.isDark) 0.38f else 0.7f),
                        Color.White.copy(alpha = if (th.isDark) 0.08f else 0.22f)
                    )
                ),
                pillShape
            ),
        shape = pillShape,
        params = GlassParams.pill(dark = th.isDark),
        enabled = AppSettings.liquidGlass.value
    ) {
        Box(Modifier.fillMaxSize()) {
            // Reflet spéculaire supérieur Liquid Glass
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (th.isDark) 0.25f else 0.45f),
                                Color.Transparent
                            )
                        )
                    )
            )
            Row(
                Modifier
                    .fillMaxSize()
                    .clickable(noRipple, null) { onOpen() }
                    .padding(start = 6.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Cover(
                    current.title,
                    cover,
                    size = 46.dp,
                    radius = 9.dp,
                    modifier = Modifier.onGloballyPositioned {
                        AppUi.miniCoverBounds.value = it.boundsInRoot()
                    }
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
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
                            overflow = TextOverflow.Ellipsis
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
                Spacer(Modifier.width(10.dp))
                MiniTransportButton(playing = ps.playWhenReady)
                Spacer(Modifier.width(16.dp))
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

/** Bouton play/pause du mini-lecteur (morphing + appui). */
@Composable
private fun MiniTransportButton(playing: Boolean) {
    val th = LocalTheme.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        Modifier
            .pressScale(min = 0.82f, isPressed = pressed)
            .size(34.dp)
            .clip(CircleShape)
            .clickable(src, null) { Player.toggle() },
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = playing,
            transitionSpec = {
                (fadeIn(tween(110)) + scaleIn(initialScale = 0.72f, animationSpec = tween(180)))
                    .togetherWith(fadeOut(tween(80)) + scaleOut(targetScale = 0.72f, animationSpec = tween(150)))
            },
            label = "miniPlay"
        ) { p ->
            Icon(
                if (p) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (p) "Pause" else "Lecture",
                tint = th.onBackground,
                modifier = Modifier.size(29.dp)
            )
        }
    }
}

/** Bouton suivant du mini-lecteur (appui). */
@Composable
private fun MiniTransportNext() {
    val th = LocalTheme.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Box(
        Modifier
            .pressScale(min = 0.82f, isPressed = pressed)
            .size(34.dp)
            .clip(CircleShape)
            .clickable(src, null) { Player.next() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.SkipNext,
            contentDescription = "Suivant",
            tint = th.onBackground,
            modifier = Modifier.size(27.dp)
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

@Composable
fun GlassBottomBar(
    modifier: Modifier = Modifier,
    collapsed: Boolean
) {
    val th = LocalTheme.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var held by remember { mutableStateOf(false) }
    var hover by remember { mutableFloatStateOf(0f) }
    val bulge by animateFloatAsState(if (held) 1.11f else 1f,
        spring(dampingRatio = .66f, stiffness = 450f), label = "held-glass")
    val tabs = listOf(
        TabSpec(Icons.Filled.Home, "Accueil", AppUi.HOME),
        TabSpec(Icons.Filled.GridView, "Explorer", AppUi.EXPLORER),
        TabSpec(Icons.Filled.LibraryMusic, "Bibliothèque", AppUi.LIBRARY)
    )

    val widthAnim by animateDpAsState(
        targetValue = if (collapsed) 190.dp else
            (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - 106).dp.coerceIn(180.dp, 300.dp),
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f),
        label = "tabWidth"
    )
    val heightAnim by animateDpAsState(
        targetValue = if (collapsed) 58.dp else 64.dp,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f),
        label = "tabHeight"
    )
    val searchSize by animateDpAsState(
        targetValue = if (collapsed) 58.dp else 64.dp,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f),
        label = "searchSize"
    )

    Row(modifier = modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassPanel(
            modifier = Modifier
                .width(widthAnim)
                .height(heightAnim)
                .pointerInput(collapsed) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { start -> held = true; hover = lensPosition(start.x, size.width.toFloat()) },
                        onDrag = { change, _ ->
                            change.consume()
                            hover = lensPosition(change.position.x, size.width.toFloat())
                        },
                        onDragEnd = {
                            focus.clearFocus()
                            AppUi.selectTab(kotlin.math.round(hover).toInt().coerceIn(0, 2))
                            held = false
                        }, onDragCancel = { held = false })
                },
            shape = RoundedCornerShape(32.dp),
            params = GlassParams.pill(dark = th.isDark),
            enabled = AppSettings.liquidGlass.value
        ) {
            if (!collapsed) {
                // Sélection glissante : la pilule se déplace d'onglet en onglet.
                val selected = if (held) kotlin.math.round(hover).toInt() else tabs.indexOfFirst { it.index == AppUi.tab.value }.coerceAtLeast(0)
                val pillX by animateFloatAsState(
                    targetValue = if (held) hover else selected.toFloat(),
                    animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
                    label = "pillX"
                )
                Box(Modifier.fillMaxSize()) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val slot = maxWidth / 3
// Lentille de verre mobile : un vrai GlassPanel (rÃ©fraction, reflets, ombre),
// pas une capsule blanche. Elle s'Ã©tire un peu pendant le dÃ©placement puis se stabilise.
val pillShape = RoundedCornerShape(999.dp)
val travel = kotlin.math.abs((if (held) hover else selected.toFloat()) - pillX)
val stretch = 1f + (travel * 0.16f).coerceAtMost(0.2f)
GlassPanel(
    modifier = Modifier
        .offset(x = slot * pillX + 5.dp)
        .size(width = slot - 10.dp, height = heightAnim - 10.dp)
        .align(Alignment.CenterStart)
        .graphicsLayer { scaleX = bulge * stretch; scaleY = bulge / (1f + (stretch - 1f) * 0.5f) },
    shape = pillShape,
    params = GlassParams.pill(blur = 4f, dark = th.isDark).copy(
        refractionPx = 24f,
        tintAlpha = if (th.isDark) 0.10f else 0.20f,
        saturation = 1.08f,
        highlightAlpha = if (th.isDark) 0.46f else 0.72f
    ),
    enabled = AppSettings.liquidGlass.value
) {}
                    }
                    Row(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 4.dp)
                    ) {
                        tabs.forEach { tab ->
                            val isSel = selected == tab.index
                            val iconScale by animateFloatAsState(
                                targetValue = if (isSel) 1.08f else 1f,
                                animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
                                label = "tabIcon"
                            )
                            val targetContentColor = when {
                                isSel && !th.isDark -> Color(0xFF141414)
                                isSel && th.isDark -> Color.White
                                else -> th.onBackground.copy(alpha = 0.52f)
                            }
                            val contentColor by animateColorAsState(
                                targetValue = targetContentColor,
                                animationSpec = tween(180),
                                label = "tabColor"
                            )
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable(noRipple, null) {
                                        focus.clearFocus()
                                        AppUi.selectTab(tab.index)
                                    }
                            ) {
                                Icon(
                                    tab.icon,
                                    contentDescription = null,
                                    tint = contentColor,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .graphicsLayerScale(iconScale)
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    tab.label,
                                    fontSize = 10.5.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.SemiBold,
                                    color = contentColor
                                )
                            }
                        }
                    }
                    if (held) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(3) { dot -> Box(Modifier.size(3.dp).clip(CircleShape)
                            .background(th.onBackground.copy(alpha = if (dot == selected) .9f else .3f))) }
                    }
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    tabs.forEach { tab ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable(noRipple, null) {
                            focus.clearFocus(); AppUi.selectTab(tab.index)
                        }, contentAlignment = Alignment.Center) {
                            Icon(tab.icon, tab.label, tint = th.onBackground.copy(alpha = if (AppUi.tab.value == tab.index) 1f else .55f), modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.width(10.dp))

        GlassPanel(
            modifier = Modifier
                .size(searchSize)
                .shadow(
                    elevation = if (th.isDark) 0.dp else 2.5.dp,
                    shape = CircleShape,
                    spotColor = Color.Black.copy(alpha = 0.12f)
                ),
            shape = CircleShape,
            params = GlassParams.circle(dark = th.isDark),
            enabled = AppSettings.liquidGlass.value
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(noRipple, null) { AppUi.openSearch() },
                contentAlignment = Alignment.Center
            ) {
                // Reflet spéculaire supérieur de la lentille circulaire
                Box(
                    Modifier
                        .fillMaxWidth(0.7f)
                        .height(10.dp)
                        .align(Alignment.TopCenter)
                        .clip(CircleShape)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = if (th.isDark) 0.35f else 0.5f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "Rechercher",
                    tint = th.onBackground,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
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
