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
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.drawscope.translate
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
import androidx.compose.ui.layout.layout
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
    /** How many steps the travel is felt in (Ket 27 ticks at each); 0 picks them from the range. */
    steps: Int = 0,
    /**
     * > 0: a stepped Ket 27 slider of that many steps (so one more stop): the value only ever lands
     * on a stop, a tick marks each one on the rail, the arrows of a keyboard move one stop, and the
     * thumb settles onto its stop when released (at once under reduced motion).
     */
    snapSteps: Int = 0,
    onValueChangeFinished: () -> Unit = {}
) {
    val theme = LocalTheme.current
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val snap = snapSteps > 0
    fun snapped(f: Float) = if (snap) SliderSteps.index(f, snapSteps).toFloat() / snapSteps else f
    val fraction = ((value - valueRange.start) / span).let { if (it.isFinite()) snapped(it.coerceIn(0f, 1f)) else 0f }
    var dragFraction by remember { mutableFloatStateOf(fraction) }
    var dragging by remember { mutableStateOf(false) }
    // The press of iOS 26/27 (SliderPress): the rail swells, the glass thumb grows, and a drag past an
    // end pulls the rail like a rubber band. rawFraction keeps the unclamped travel of the drag for it.
    val press = rememberSliderPress()
    var rawFraction by remember { mutableFloatStateOf(fraction) }
    val reduced = com.ketvoice.app.ui.motion.Motion.reduced
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    LaunchedEffect(fraction, dragging) { if (!dragging) dragFraction = fraction }
    var widthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    // Ket 27: the slider of iOS 27 — a capsule of glass wider than it is tall, on a slightly thicker
    // track, and a tick in the hand at each step of its travel.
    val thumb = 36.dp
    val thumbHeight = 24.dp
    val tick = com.ketvoice.app.ui.motion.rememberHaptics()
    val stepCount = if (snap) snapSteps else if (steps > 0) steps else SliderSteps.count(valueRange.start, valueRange.endInclusive)
    var lastStep by remember { mutableStateOf(SliderSteps.index(fraction, stepCount)) }
    fun feel(next: Float) {
        val step = SliderSteps.index(next, stepCount)
        if (step != lastStep) { lastStep = step; tick(com.ketvoice.app.ui.motion.Haptics.Kind.Tick) }
    }

    /** The rail as drawn now, in px: the thumb's travel runs between half a thumb in from each end. */
    fun railFor(width: Float, withStretch: Boolean = true) = SliderPress.track(SliderPress.Glass, density, 0f, width,
        press.expansion, if (withStretch) press.stretch else 0f, reduced, inset = with(density) { thumb.toPx() } / 2f)

    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .onSizeChanged { widthPx = it.width }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(valueRange.start + fraction * span, valueRange,
                    if (snap) (snapSteps - 1).coerceAtLeast(0) else 0)
                if (!enabled) disabled()
                setProgress { next ->
                    if (!enabled || !next.isFinite()) false else {
                        val f = snapped(((next.coerceIn(valueRange) - valueRange.start) / span).coerceIn(0f, 1f))
                        change(valueRange.start + f * span); finished(); true
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
                    val stride = if (snap) 1f / snapSteps else .05f
                    // From the last value sent, not the last composed one: quick presses each count.
                    val next = snapped((dragFraction + sign * stride).coerceIn(0f, 1f))
                    dragFraction = next
                    change(valueRange.start + next * span)
                    finished()
                    true
                }
            }
            .focusable(enabled)
            .sliderPress(press, enabled)
            .pointerInput(enabled, widthPx, valueRange) {
                if (enabled) detectTapGestures { point ->
                    val physical = railFor(widthPx.toFloat(), withStretch = false).fractionAt(point.x)
                    val next = if (direction == LayoutDirection.Rtl) 1f - physical else physical
                    dragFraction = snapped(next); feel(next); change(valueRange.start + snapped(next) * span); finished()
                }
            }
            .draggable(
                orientation = Orientation.Horizontal,
                enabled = enabled,
                onDragStarted = { dragging = true; dragFraction = fraction; rawFraction = fraction; press.press()
                    lastStep = SliderSteps.index(fraction, stepCount) },
                onDragStopped = { dragging = false; press.release(); finished() },
                state = rememberDraggableState { delta ->
                    if (widthPx > 0) {
                        val rail = railFor(widthPx.toFloat(), withStretch = false)
                        val travel = (rail.length - 2f * rail.inset).coerceAtLeast(1f)
                        val logicalDelta = if (direction == LayoutDirection.Rtl) -delta else delta
                        rawFraction += logicalDelta / travel
                        dragFraction = rawFraction.coerceIn(0f, 1f)
                        // Past an end, the rail follows as a rubber band (in physical px: the end being pulled).
                        val over = (rawFraction - dragFraction) * travel
                        press.pull(if (direction == LayoutDirection.Rtl) -over else over, SliderPress.Glass.maxStretch * density.density)
                        feel(dragFraction)
                        change(valueRange.start + snapped(dragFraction) * span)
                    }
                }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        // A stepped slider's thumb follows the finger, then settles onto its stop.
        val settled by androidx.compose.animation.core.animateFloatAsState(fraction,
            if (snap && !com.ketvoice.app.ui.motion.Motion.reduced)
                androidx.compose.animation.core.spring(dampingRatio = .82f, stiffness = 700f)
            else androidx.compose.animation.core.snap(), label = "slider-stop")
        val displayedFraction = if (dragging) dragFraction else if (snap) settled else fraction
        val track = com.ketvoice.app.glass.rememberGlassBackdrop(theme.card)
        val pressure by androidx.compose.animation.core.animateFloatAsState(
            if ((dragging || press.held) && !com.ketvoice.app.ui.motion.Motion.reduced) 1f else 0f,
            com.ketvoice.app.ui.motion.Motion.pressSpring(dragging || press.held), label = "slider-pressure")
        // The glass reads what lies under the thumb from this capture: it bleeds past the slider's ends so
        // a thumb carried out by the swell or the rubber band still has the rail under it.
        val bleed = 24.dp
        com.ketvoice.app.glass.BackdropCapture(track, Modifier.matchParentSize().layout { measurable, constraints ->
            val extra = bleed.roundToPx()
            val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(
                constraints.maxWidth + 2 * extra, constraints.maxHeight))
            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-extra, 0) }
        }) {
            val railColor = theme.onBackground.copy(alpha = .12f)
            val fillColor = theme.accent
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                val edge = bleed.toPx()
                // The fill runs from the logical start to the thumb's centre.
                translate(left = edge) {
                    drawPressTrack(railFor(size.width - 2f * edge), displayedFraction, railColor, fillColor,
                        press.expansion, highlight = false, direction = direction,
                        fillBrush = theme.season?.fill)
                }
            }
            if (snap) Box(Modifier.matchParentSize().padding(horizontal = bleed)) {
                SliderTicks(snapSteps, thumb, theme.onBackground.copy(alpha = .30f))
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(com.ketvoice.app.glass.LocalBackdropLayer provides track) {
            // The glass thumb rides on the rail as it now stands, and grows a little while held.
            GlassPanel(
                Modifier
                    .layout { measurable, constraints ->
                        val e = if (reduced) 0f else press.expansion.coerceIn(-.2f, 1.3f)
                        val w = (thumb.toPx() + 8.dp.toPx() * e).roundToIntSafe()
                        val h = (thumbHeight.toPx() + 4.dp.toPx() * e).roundToIntSafe()
                        val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
                        // As wide as the slider, so the thumb is placed in physical px whatever the direction.
                        val full = if (constraints.hasBoundedWidth) constraints.maxWidth else widthPx
                        layout(full, placeable.height) {
                            val physical = if (direction == LayoutDirection.Rtl) 1f - displayedFraction else displayedFraction
                            placeable.place((railFor(widthPx.toFloat()).xOf(physical) - placeable.width / 2f).roundToIntSafe(), 0)
                        }
                    },
                CircleShape,
                GlassParams.circle(dark = theme.isDark).copy(blurPx = 2f, tintAlpha = .16f,
                    opticalPressure = pressure),
                enabled = AppSettings.liquidGlass.value
            ) {}
        }
    }
}

private fun Float.roundToIntSafe(): Int = if (isFinite()) Math.round(this) else 0

/** The steps a slider is felt in: where a tick falls along its travel. */
internal object SliderSteps {
    /** A whole-number range of at most 24 is felt unit by unit (the equalizer's decibels); anything else in twentieths. */
    fun count(start: Float, end: Float): Int {
        val span = end - start
        return if (span.isFinite() && span >= 2f && span <= 24f && span == Math.round(span).toFloat()) Math.round(span) else 20
    }
    /** The step a fraction of the travel (0..1) falls on. */
    fun index(fraction: Float, steps: Int): Int =
        if (steps <= 0 || !fraction.isFinite()) 0 else Math.round(fraction.coerceIn(0f, 1f) * steps)
    /** Whether going from one fraction to another crosses onto a new step: a tick is due. */
    fun crossed(from: Float, to: Float, steps: Int): Boolean = index(from, steps) != index(to, steps)
}

/** The stops of a stepped slider: a small dot on the rail at each one, under the thumb's centre. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SliderTicks(steps: Int, thumb: androidx.compose.ui.unit.Dp,
                                                                    color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
        val inset = thumb.toPx() / 2f
        val travel = (size.width - inset * 2f).coerceAtLeast(0f)
        val radius = 1.5.dp.toPx()
        for (i in 0..steps) {
            val x = inset + travel * i / steps
            drawCircle(color, radius, androidx.compose.ui.geometry.Offset(
                if (layoutDirection == LayoutDirection.Rtl) size.width - x else x, size.height / 2f))
        }
    }
}
