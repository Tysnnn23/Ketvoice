package com.ketvoice.app.ui.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings

object ArtworkDepth {
    fun axis(position: Float, extent: Int): Float =
        if (extent <= 0 || !position.isFinite()) 0f else (position / extent * 2f - 1f).coerceIn(-1f, 1f)
}

/** Observes without consuming: scroll, artwork dismissal and taps still work. */
fun Modifier.artworkDepth(): Modifier = composed {
    val enabled = AppSettings.artworkDepth.value && !Motion.reduced
    var point by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val x by animateFloatAsState(if (enabled) point.x else 0f, tween(Motion.duration(180)), label = "art-depth-x")
    val y by animateFloatAsState(if (enabled) point.y else 0f, tween(Motion.duration(180)), label = "art-depth-y")
    this.onSizeChanged { size = it }.pointerInput(enabled) {
        point = Offset.Zero
        if (enabled) awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull()
                point = if (event.type == PointerEventType.Exit || event.type == PointerEventType.Release ||
                    change == null || change.isConsumed) Offset.Zero
                else Offset(ArtworkDepth.axis(change.position.x, size.width), ArtworkDepth.axis(change.position.y, size.height))
            }
        }
    }.graphicsLayer {
        rotationX = -y * 1.5f
        rotationY = x * 1.5f
        translationX = x * 2.dp.toPx()
        translationY = y * 2.dp.toPx()
        cameraDistance = 24.dp.toPx()
        // A tiny overscan keeps the underlying panel hidden at the edges.
        scaleX = if (enabled) 1.015f else 1f
        scaleY = scaleX
    }
}
