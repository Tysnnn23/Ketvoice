package com.ketvoice.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.revealTransform

/** Shared production/test host. Both animated pages retain their own route snapshot. */
@Composable
internal fun NavigationPresentation(
    target: Pair<Int, AppUi.OverlayEntry>,
    modifier: Modifier = Modifier,
    content: @Composable (Int, AppUi.OverlayEntry) -> Unit
) {
    val states = rememberSaveableStateHolder()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInWindow() }) {
        val width = constraints.maxWidth.toFloat()
        AnimatedContent(
            targetState = target,
            contentKey = { (tab, entry) -> navigationPageKey(tab, entry) },
            contentAlignment = Alignment.Center,
            transitionSpec = {
                val tabOnly = initialState.second.overlay == AppUi.NONE && targetState.second.overlay == AppUi.NONE &&
                    initialState.first != targetState.first
                val closing = AppUi.navKind.intValue == AppUi.NAV_POP
                val detail = if (closing) initialState.second else targetState.second
                if (Motion.reduced) {
                    fadeIn(tween(0)).togetherWith(fadeOut(tween(0)))
                } else if (tabOnly) {
                    val direction = targetState.first.compareTo(initialState.first)
                    (fadeIn(tween(260, easing = Motion.EASE_STANDARD)) +
                        slideInHorizontally(tween(300, easing = Motion.EASE_STANDARD)) { direction * it / 10 })
                        .togetherWith(fadeOut(tween(170, easing = Motion.EASE_ACCELERATE)) +
                            slideOutHorizontally(tween(220, easing = Motion.EASE_ACCELERATE)) { -direction * it / 14 })
                } else if (detail.isArtworkDetail() && AppUi.navKind.intValue != AppUi.NAV_JUMP) {
                    val origin = detail.origin?.translate(-bounds.left, -bounds.top)
                    val scale = revealTransform(origin, width, 1f, 0f).scale
                    val travel: (IntSize) -> IntOffset = { size ->
                        val t = revealTransform(origin, size.width.toFloat(), size.height.toFloat(), 0f)
                        IntOffset(t.translateX.toInt(), t.translateY.toInt())
                    }
                    if (closing) {
                        fadeIn(tween(240)).togetherWith(
                            scaleOut(spring(dampingRatio = .92f, stiffness = 430f), targetScale = scale) +
                                slideOut(spring(dampingRatio = .92f, stiffness = 430f), targetOffset = travel) + fadeOut(tween(220))
                        ).apply { targetContentZIndex = -1f }
                    } else {
                        (scaleIn(spring(dampingRatio = .92f, stiffness = 430f), initialScale = scale) +
                            slideIn(spring(dampingRatio = .92f, stiffness = 430f), initialOffset = travel) + fadeIn(tween(200)))
                            .togetherWith(fadeOut(tween(260)))
                    }
                } else {
                    // A push as on iOS: the new page slides in from the edge on a critically damped
                    // spring while the page underneath recedes a little and dims; back reverses it,
                    // with the leaving page kept on top.
                    val push = spring<IntOffset>(dampingRatio = 1f, stiffness = 420f, visibilityThreshold = IntOffset(1, 1))
                    if (closing) {
                        (slideInHorizontally(push) { -it / 4 } + fadeIn(tween(Motion.duration(280)), initialAlpha = .55f))
                            .togetherWith(slideOutHorizontally(push) { it } + fadeOut(tween(Motion.duration(320)), targetAlpha = .9f))
                            .apply { targetContentZIndex = -1f }
                    } else {
                        (slideInHorizontally(push) { it } + fadeIn(tween(Motion.duration(160)), initialAlpha = .9f))
                            .togetherWith(slideOutHorizontally(push) { -it / 4 } + fadeOut(tween(Motion.duration(320)), targetAlpha = .55f))
                    }
                }
            },
            label = "nav"
        ) { (tab, entry) ->
            // A page opened from a cover grows out of it as a card: its corners round off while it is
            // small and straighten as it fills the screen; closing it folds it back into the card.
            val card = entry.isArtworkDetail() && !Motion.reduced
            val opened by transition.animateFloat(transitionSpec = { spring(dampingRatio = .92f, stiffness = 430f) }, label = "page-card") {
                if (it == EnterExitState.Visible) 1f else 0f
            }
            val radius = with(androidx.compose.ui.platform.LocalDensity.current) { PageCard.radius(opened).dp.toPx() }
            androidx.compose.foundation.layout.Box(if (!card) Modifier else Modifier.graphicsLayer {
                if (radius > .5f) { shape = androidx.compose.foundation.shape.RoundedCornerShape(radius); clip = true }
            }) {
                states.SaveableStateProvider(navigationPageKey(tab, entry)) { content(tab, entry) }
            }
        }
    }
}

/** The corners of a page while it grows out of a cover (as on iOS): those of a card, then none. */
internal object PageCard {
    const val CARD_RADIUS = 34f
    /** Corner radius (dp) for an opening of 0..1: square once the page is nearly full. */
    fun radius(opened: Float): Float {
        val p = if (opened.isFinite()) opened.coerceIn(0f, 1f) else 1f
        if (p >= .97f) return 0f
        val r = 1f - p / .97f
        return CARD_RADIUS * r * (2f - r)
    }
}
