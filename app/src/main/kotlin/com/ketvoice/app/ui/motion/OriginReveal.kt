package com.ketvoice.app.ui.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp

/** Pure geometry for the reversible detail-page transition. */
internal data class RevealTransform(
    val scale: Float,
    val translateX: Float,
    val translateY: Float,
    val alpha: Float
)

@Composable
fun rememberRevealOrigin(): MutableState<Rect?> = remember { mutableStateOf(null) }

fun Modifier.captureRevealOrigin(origin: MutableState<Rect?>): Modifier = onGloballyPositioned {
    val bounds = it.boundsInWindow()
    if (bounds.width > 0f && bounds.height > 0f) origin.value = bounds
}

internal fun revealTransform(origin: Rect?, width: Float, height: Float, progress: Float): RevealTransform {
    val p = progress.coerceIn(0f, 1f)
    if (origin == null || width <= 0f || height <= 0f) {
        return RevealTransform(1f, 0f, 0f, p)
    }
    // Never start from an invisible dot: a small card still opens with a
    // recognisable surface, and a large card does not overshoot the screen.
    val startScale = (origin.width / width).coerceIn(0.22f, 0.92f)
    val scale = lerp(startScale, 1f, p)
    val startX = origin.center.x - width / 2f
    val startY = origin.center.y - height / 2f
    return RevealTransform(
        scale = scale,
        translateX = startX * (1f - p),
        translateY = startY * (1f - p),
        alpha = (0.45f + 0.55f * p).coerceIn(0f, 1f)
    )
}

/** One-time staggered entrance that never changes layout size. */
@Composable
fun StaggeredReveal(
    order: Int,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    var shown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val a by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = if (Motion.reduced) snap()
        else tween(durationMillis = 380, delayMillis = (order * 45).coerceAtMost(260), easing = Motion.EASE_STANDARD),
        label = "stagger$order"
    )
    Box(
        modifier.graphicsLayer {
            alpha = a
            translationY = (1f - a) * 10.dp.toPx()
        },
        content = content
    )
}
