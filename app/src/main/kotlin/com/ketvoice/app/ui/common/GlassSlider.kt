package com.ketvoice.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.platform.LocalDensity
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
            .focusable(enabled)
            .pointerInput(enabled, widthPx, valueRange) {
                if (enabled) detectTapGestures { point ->
                    val travel = (widthPx - thumb.toPx()).coerceAtLeast(1f)
                    val next = ((point.x - thumb.toPx() / 2f) / travel).coerceIn(0f, 1f)
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
                        dragFraction = (dragFraction + delta / travel).coerceIn(0f, 1f)
                        change(valueRange.start + dragFraction * span)
                    }
                }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        // Rainure : verre en creux (lumière en haut, ombre portée en bas).
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (theme.isDark) 0.10f else 0.42f),
                            Color.White.copy(alpha = if (theme.isDark) 0.05f else 0.20f)
                        )
                    )
                )
                .border(
                    0.6.dp,
                    Color.White.copy(alpha = if (theme.isDark) 0.16f else 0.55f),
                    RoundedCornerShape(999.dp)
                )
        )
        // Partie parcourue : accent légèrement dégradé pour éviter l'aplat.
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(theme.accent.copy(alpha = 0.82f), theme.accent)
                    )
                )
        )
        // Pouce : petite lentille de verre posée sur la piste.
        val travel = with(density) {
            (widthPx.toFloat() - thumb.toPx()).coerceAtLeast(0f).toDp()
        }
        GlassPanel(
            Modifier
                .size(thumb)
                .offset(x = travel * fraction),
            CircleShape,
            GlassParams.circle(dark = theme.isDark),
            enabled = AppSettings.liquidGlass.value
        ) {}
    }
}
