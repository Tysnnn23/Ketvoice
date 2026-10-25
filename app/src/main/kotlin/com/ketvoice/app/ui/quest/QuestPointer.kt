package com.ketvoice.app.ui.quest

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import com.ketvoice.app.ui.motion.Motion

fun Modifier.questPointer(): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    var point by remember { mutableStateOf<Offset?>(null) }
    val reduced = Motion.reduced
    val glow by animateFloatAsState(if (hovered || focused) 1f else 0f,
        androidx.compose.animation.core.tween(Motion.duration()), label = "glass-pointer")
    this.hoverable(source).onFocusChanged { focused = it.isFocused }.pointerInput(Unit) {
        awaitPointerEventScope { while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            point = event.changes.firstOrNull()?.position
        } }
    }.drawBehind {
        if (glow > 0f) {
            drawRoundRect(Brush.radialGradient(listOf(Color.White.copy(alpha = .22f * glow), Color.Transparent),
                center = if (reduced) center else point ?: center, radius = size.maxDimension.coerceAtLeast(1f)), cornerRadius = CornerRadius(18.dp.toPx()))
            drawRoundRect(Color.White.copy(alpha = .35f * glow), cornerRadius = CornerRadius(18.dp.toPx()),
                style = Stroke(1.dp.toPx()))
        }
    }
}
