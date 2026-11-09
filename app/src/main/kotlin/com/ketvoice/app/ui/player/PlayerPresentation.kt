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
            CompositionLocalProvider(LocalPlayerDismissOffset provides drag) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    shape = mask; clip = true
                    alpha = if (reduced) 1f else (p * 4f).coerceIn(0f, 1f)
                }) { content() }
            }
        }
    }
}
