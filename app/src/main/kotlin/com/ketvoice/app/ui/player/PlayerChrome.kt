package com.ketvoice.app.ui.player

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.ketvoice.app.ui.motion.Motion

/** Keep controls mounted through the exit, without changing their measured size. */
@Composable
internal fun PlayerChrome(visible: Boolean, modifier: Modifier = Modifier,
                          onOpacity: ((Float) -> Unit)? = null, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = visible
    val easing = CubicBezierEasing(.22f, 1f, .36f, 1f)
    val transition = rememberTransition(state, label = "player-chrome")
    val opacity = transition.animateFloat(transitionSpec = { tween(Motion.duration(if (targetState) 280 else 240)) }, label = "opacity") {
        if (it) 1f else 0f
    }
    val travel = transition.animateFloat(transitionSpec = { tween(Motion.duration(if (targetState) 360 else 300), easing = easing) }, label = "travel") {
        if (it) 0f else 1f
    }
    // Optional host-test observation of the actual animation value. Normal
    // rendering reads it only in the draw layer, never recomposing each frame.
    LaunchedEffect(transition, onOpacity) {
        if (onOpacity != null) snapshotFlow { opacity.value }.collect { onOpacity(it) }
    }
    if (state.currentState || state.targetState || !state.isIdle) {
        Box(modifier.graphicsLayer {
            alpha = opacity.value
            translationY = if (Motion.reduced) 0f else travel.value * 18.dp.toPx()
        }) { content() }
    }
}
