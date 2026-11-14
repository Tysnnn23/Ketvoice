package com.ketvoice.app.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.player.Player
import com.ketvoice.app.ui.motion.Haptics
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.rememberHaptics
import kotlinx.coroutines.launch

internal enum class SwipeSkip { Next, Previous, None }

/** What a released horizontal swipe does: towards the start is "next", like turning a page. */
internal fun swipeSkip(offsetPx: Float, thresholdPx: Float): SwipeSkip = when {
    !offsetPx.isFinite() || thresholdPx <= 0f -> SwipeSkip.None
    offsetPx <= -thresholdPx -> SwipeSkip.Next
    offsetPx >= thresholdPx -> SwipeSkip.Previous
    else -> SwipeSkip.None
}

/** Opacity of the swiped content: fully visible at rest, faint once it is about to leave. */
internal fun swipeAlpha(offsetPx: Float, thresholdPx: Float): Float =
    if (thresholdPx <= 0f) 1f else 1f - (kotlin.math.abs(offsetPx) / (thresholdPx * 2.4f)).coerceIn(0f, .85f)

/** Follows the finger during a track swipe; shared by the gesture and the content it moves. */
internal class TrackSwipeState {
    val offset = Animatable(0f)
    var thresholdPx = 1f
}

@Composable
internal fun rememberTrackSwipe(): TrackSwipeState = remember { TrackSwipeState() }

/**
 * Swipe sideways to change track. The gesture only claims horizontal drags, so a
 * vertical drag on the same surface still closes or opens the player.
 */
@Composable
internal fun Modifier.trackSwipe(state: TrackSwipeState, threshold: Dp = 72.dp): Modifier {
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val thresholdPx = with(LocalDensity.current) { threshold.toPx() }
    state.thresholdPx = thresholdPx
    return pointerInput(thresholdPx) {
        var armed = false
        detectHorizontalDragGestures(
            onDragStart = { armed = false },
            onHorizontalDrag = { change, dx ->
                change.consume()
                val next = (state.offset.value + dx).coerceIn(-thresholdPx * 2f, thresholdPx * 2f)
                scope.launch { state.offset.snapTo(next) }
                val past = kotlin.math.abs(next) >= thresholdPx
                // One tick when the swipe becomes long enough to act.
                if (past && !armed) haptic(Haptics.Kind.Tick)
                armed = past
            },
            onDragCancel = { scope.launch { state.offset.animateTo(0f, Motion.gentleSpring()) } },
            onDragEnd = {
                val skip = swipeSkip(state.offset.value, thresholdPx)
                scope.launch {
                    if (skip == SwipeSkip.None) {
                        state.offset.animateTo(0f, Motion.gentleSpring())
                        return@launch
                    }
                    val direction = if (skip == SwipeSkip.Next) -1f else 1f
                    // The old title leaves on the side it was pushed to; the new one enters from the other.
                    if (!Motion.reduced) state.offset.animateTo(direction * thresholdPx * 2.2f, tween(110))
                    if (skip == SwipeSkip.Next) Player.next() else Player.previous()
                    if (!Motion.reduced) state.offset.snapTo(-direction * thresholdPx * 1.1f)
                    state.offset.animateTo(0f, Motion.gentleSpring())
                }
            })
    }
}

/** Applied to what the swipe moves. Reduced motion keeps the gesture and drops the movement. */
internal fun Modifier.trackSwipeContent(state: TrackSwipeState, travel: Float = 1f): Modifier = graphicsLayer {
    if (Motion.reduced) return@graphicsLayer
    translationX = state.offset.value * travel
    alpha = swipeAlpha(state.offset.value, state.thresholdPx)
}
