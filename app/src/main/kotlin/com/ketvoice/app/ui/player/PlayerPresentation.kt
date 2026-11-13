package com.ketvoice.app.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.ketvoice.app.ui.motion.Motion

/** One timeline owns reveal, drag and dismissal. No duplicate flying cover. */
internal val LocalPlayerDismissOffset = staticCompositionLocalOf<Animatable<Float, androidx.compose.animation.core.AnimationVector1D>?> { null }

/**
 * The same timeline, readable from draw-phase lambdas: 0 = mini-player, 1 = full player.
 * Shared elements (the artwork, the controls) derive their transform from it, so they
 * can never drift from the reveal mask or from an interactive dismissal.
 */
internal val LocalPlayerReveal = staticCompositionLocalOf<() -> Float> { { 1f } }

internal data class CoverMorph(val scale: Float, val translationX: Float, val translationY: Float)

/**
 * Shared-element transform of the player artwork: at progress 0 its centre sits on the
 * mini-player cover and it is as wide as that cover; at 1 it is at rest. The artwork
 * frame is laid out at the top-left of the player, [restWidth] × [restHeight] px.
 */
internal fun sharedCoverMorph(mini: Rect?, restWidth: Float, restHeight: Float, progress: Float): CoverMorph {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 1f
    if (mini == null || mini.width <= 0f || mini.height <= 0f || restWidth <= 0f || restHeight <= 0f || p >= 1f) {
        return CoverMorph(1f, 0f, 0f)
    }
    val start = (mini.width / restWidth).coerceIn(.02f, 1f)
    return CoverMorph(
        scale = start + (1f - start) * p,
        translationX = (mini.center.x - restWidth / 2f) * (1f - p),
        translationY = (mini.center.y - restHeight / 2f) * (1f - p))
}

/** How far a held back gesture pulls the player toward the mini-player (at most 22 % of the height). */
internal fun predictiveBackTravel(progress: Float, height: Float): Float =
    (if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f) * height.coerceAtLeast(0f) * .22f

/** Controls rise into place slightly after the artwork: 0 while p < .2, 1 from p = .8. */
internal fun controlsReveal(progress: Float): Float = ((progress - .2f) / .6f).coerceIn(0f, 1f)

internal fun presentationBounds(origin: Rect, width: Float, height: Float, progress: Float): Rect {
    val p = progress.coerceIn(0f, 1f)
    return Rect(origin.left * (1f - p), origin.top * (1f - p),
        origin.right + (width - origin.right) * p, origin.bottom + (height - origin.bottom) * p)
}

/** Clip a stable full-size player instead of stretching its text/video between aspect ratios. */
@Composable
internal fun PlayerPresentation(visible: Boolean, origin: Rect?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reveal = remember { Animatable(0f) }
    val drag = remember { Animatable(0f) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val reduced = Motion.reduced
    BoxWithConstraints(modifier) {
        val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        LaunchedEffect(visible, reduced) {
            if (visible) {
                if (reveal.value == 0f) {
                    anchor = origin?.takeIf { it.width > 0 && it.height > 0 && it.top < h }
                    drag.snapTo(0f)
                }
            } else {
                // Absorb the interactive fraction before animating the exact same curve back.
                reveal.snapTo(reveal.value * (1f - drag.value / h).coerceIn(0f, 1f))
                drag.snapTo(0f)
            }
            if (reduced) reveal.snapTo(if (visible) 1f else 0f)
            else reveal.animateTo(if (visible) 1f else 0f,
                tween(if (visible) 420 else 320, easing = CubicBezierEasing(.22f, .8f, .2f, 1f)))
        }
        if (visible || reveal.value > 0f) {
            val p = (reveal.value * (1f - drag.value / h).coerceIn(0f, 1f)).coerceIn(0f, 1f)
            val start = anchor ?: Rect(w * .08f, h * .89f, w * .92f, h * .96f)
            val radius = with(androidx.compose.ui.platform.LocalDensity.current) { 27.dp.toPx() }
            val mask = remember(start, p, radius) {
                object : Shape {
                    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
                        val rect = presentationBounds(start, size.width, size.height, p)
                        return Outline.Generic(Path().apply { addRoundRect(RoundRect(rect, CornerRadius(radius * (1f - p)))) })
                    }
                }
            }
            val progress = remember(h) { { (reveal.value * (1f - drag.value / h).coerceIn(0f, 1f)).coerceIn(0f, 1f) } }
            CompositionLocalProvider(LocalPlayerDismissOffset provides drag, LocalPlayerReveal provides progress) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    shape = mask; clip = true
                    alpha = if (reduced) 1f else (p * 4f).coerceIn(0f, 1f)
                }) { content() }
            }
        }
    }
}
