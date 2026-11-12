package com.ketvoice.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.theme.LocalTheme

/**
 * Curseur « liquid glass » : rainure en creux, remplissage coloré et bouton de
 * verre. Remplace le Slider Material, dont la piste et le pouce opaques
 * cassaient l'unité du reste de l'interface.
 */
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    onValueChangeFinished: () -> Unit = {}
) {
    val theme = LocalTheme.current
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
    var dragFraction by remember { mutableFloatStateOf(fraction) }
    var dragging by remember { mutableStateOf(false) }
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    LaunchedEffect(fraction, dragging) { if (!dragging) dragFraction = fraction }
    var widthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val thumb = 22.dp

    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .onSizeChanged { widthPx = it.width }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(valueRange.start + fraction * span, valueRange)
                if (!enabled) disabled()
                setProgress { next ->
                    if (!enabled || !next.isFinite()) false else {
                        change(next.coerceIn(valueRange)); finished(); true
                    }
                }
            }
            .onKeyEvent { event ->
                val sign = when (event.key) {
                    Key.DirectionLeft -> if (direction == LayoutDirection.Rtl) 1f else -1f
                    Key.DirectionRight -> if (direction == LayoutDirection.Rtl) -1f else 1f
                    else -> 0f
                }
                if (!enabled || event.type != KeyEventType.KeyDown || sign == 0f) false else {
                    change((valueRange.start + fraction * span + sign * span * .05f).coerceIn(valueRange))
                    finished()
                    true
                }
            }
            .focusable(enabled)
            .pointerInput(enabled, widthPx, valueRange) {
                if (enabled) detectTapGestures { point ->
                    val travel = (widthPx - thumb.toPx()).coerceAtLeast(1f)
                    val physical = ((point.x - thumb.toPx() / 2f) / travel).coerceIn(0f, 1f)
                    val next = if (direction == LayoutDirection.Rtl) 1f - physical else physical
                    dragFraction = next; change(valueRange.start + next * span); finished()
                }
            }
            .draggable(
                orientation = Orientation.Horizontal,
                enabled = enabled,
                onDragStarted = { dragging = true; dragFraction = fraction },
                onDragStopped = { dragging = false; finished() },
                state = rememberDraggableState { delta ->
                    if (widthPx > 0) {
                        val travel = (widthPx - with(density) { thumb.toPx() }).coerceAtLeast(1f)
                        val logicalDelta = if (direction == LayoutDirection.Rtl) -delta else delta
                        dragFraction = (dragFraction + logicalDelta / travel).coerceIn(0f, 1f)
                        change(valueRange.start + dragFraction * span)
                    }
                }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        val displayedFraction = if (dragging) dragFraction else fraction
        val track = com.ketvoice.app.glass.rememberGlassBackdrop(theme.card)
        val pressure by androidx.compose.animation.core.animateFloatAsState(
            if (dragging && !com.ketvoice.app.ui.motion.Motion.reduced) 1f else 0f,
            com.ketvoice.app.ui.motion.Motion.pressSpring(dragging), label = "slider-pressure")
        com.ketvoice.app.glass.BackdropCapture(track, Modifier.matchParentSize()) {
            Box(Modifier.align(Alignment.Center).fillMaxWidth().height(5.dp)
                .clip(CircleShape).background(theme.onBackground.copy(alpha = .12f)))
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(displayedFraction).height(5.dp)
                .clip(CircleShape).background(theme.accent))
        }
        val travel = with(density) {
            (widthPx.toFloat() - thumb.toPx()).coerceAtLeast(0f).toDp()
        }
        androidx.compose.runtime.CompositionLocalProvider(com.ketvoice.app.glass.LocalBackdropLayer provides track) {
            GlassPanel(
                Modifier.offset(x = travel * displayedFraction).size(thumb),
                CircleShape,
                GlassParams.circle(dark = theme.isDark).copy(blurPx = 2f, tintAlpha = .16f,
                    opticalPressure = pressure),
                enabled = AppSettings.liquidGlass.value
            ) {}
        }
    }
}
