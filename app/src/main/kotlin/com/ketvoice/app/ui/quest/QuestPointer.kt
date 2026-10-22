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
import com.ketvoice.app.BuildConfig

/** Horizon OS delivers standard pointer events; no hand-tracking permission. */
fun Modifier.questPointer(): Modifier = if (!BuildConfig.IS_QUEST) this else composed {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val glow by animateFloatAsState(if (hovered || focused) 1f else 0f, label = "quest-pointer")
    this.hoverable(source).onFocusChanged { focused = it.isFocused }.drawBehind {
        if (glow > 0f) {
            drawRoundRect(Color.White.copy(alpha = .10f * glow), cornerRadius = CornerRadius(18.dp.toPx()))
            drawRoundRect(Color.White.copy(alpha = .8f * glow), cornerRadius = CornerRadius(18.dp.toPx()),
                style = Stroke(2.dp.toPx()))
        }
    }
}
