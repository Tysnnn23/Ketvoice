package com.ketvoice.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
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
                    val direction = if (closing) -1 else 1
                    (fadeIn(tween(Motion.duration())) +
                        slideInHorizontally(tween(Motion.duration(), easing = Motion.EASE_STANDARD)) { direction * it / 5 })
                        .togetherWith(fadeOut(tween(Motion.duration())))
                }
            },
            label = "nav"
        ) { (tab, entry) ->
            states.SaveableStateProvider(navigationPageKey(tab, entry)) { content(tab, entry) }
        }
    }
}
