package com.ketvoice.app.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.ketvoice.app.BuildConfig
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.ui.motion.Haptics
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.rememberHaptics
import kotlinx.coroutines.launch

/**
 * Where a bottom sheet may rest (Ket 27): at half the height of its window, and at its full
 * height. A sheet shorter than that has one resting place, its own height. Kept apart from
 * the composable so that it can be checked without a screen.
 */
internal object SheetDetents {
    /** The share of its window a sheet shows at the half detent. */
    const val HALF = .5f
    /** A sheet no taller than this share of the window has a single detent. */
    const val SINGLE_UNTIL = .62f
    /** Released with more than this share of the lowest detent hidden, the sheet closes. */
    const val CLOSE_SHARE = .3f
    /** A release faster than this (dp per second) goes to the next detent in its direction. */
    const val FLING_DP = 700f
    const val DAMPING = .82f
    const val STIFFNESS = 380f

    /** The visible heights a sheet of [content] px can rest at in a window of [container] px, lowest first. */
    fun heights(content: Float, container: Float): List<Float> {
        if (!content.isFinite() || !container.isFinite() || content <= 0f || container <= 0f) return emptyList()
        val full = minOf(content, container)
        return if (full <= container * SINGLE_UNTIL) listOf(full) else listOf(container * HALF, full)
    }

    /**
     * Where a released sheet goes: the visible height to settle at, or 0 to close. [visible] is what
     * shows now; [velocity] is in px per second, positive downwards; [fling] is the speed that counts as a flick.
     */
    fun settle(visible: Float, velocity: Float, detents: List<Float>, fling: Float): Float {
        if (detents.isEmpty()) return 0f
        val v = if (velocity.isFinite()) velocity else 0f
        val edge = 1f
        if (v > fling) return detents.lastOrNull { it < visible - edge } ?: 0f
        if (v < -fling) return detents.firstOrNull { it > visible + edge } ?: detents.last()
        if (visible < detents.first() * (1f - CLOSE_SHARE)) return 0f
        return detents.minByOrNull { kotlin.math.abs(it - visible) } ?: detents.first()
    }

    /** How dark the scrim is for a sheet showing [visible] px: full once the lowest detent is reached. */
    fun scrim(visible: Float, detents: List<Float>): Float {
        val low = detents.firstOrNull() ?: return 0f
        return if (low <= 0f) 0f else (visible / low).coerceIn(0f, 1f)
    }
}

/** Whether sheets rest on detents and follow the finger: Ket 27, never on Quest (a pointer does not drag a sheet well). */
internal val springSheets: Boolean get() = Ket27.on && !BuildConfig.IS_QUEST

/** The tallest a sheet's content may be: 650 dp in the classic interface, the room its window leaves under Ket 27. */
internal val LocalSheetMaxHeight = compositionLocalOf { 650.dp }

@Composable
internal fun sheetMaxHeight(): Dp = LocalSheetMaxHeight.current

/**
 * A bottom sheet with detents and a spring, shared by every sheet of Ket 27 (title menu, playlist
 * menu, lyrics sources, audio output, the pickers and dialogs of the settings…). The sheet rises
 * to half height, follows the finger, settles on the nearest detent with a spring, and closes when
 * pulled down past the lowest one. A list inside it scrolls only once the sheet is at full height.
 * With reduced motion every move snaps.
 *
 * [content] is told the tallest it may be; it should not be taller.
 */
@Composable
internal fun SpringSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier, scrim: Float = .35f,
                         content: @Composable (maxHeight: Dp) -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val density = LocalDensity.current
    val fling = with(density) { SheetDetents.FLING_DP.dp.toPx() }
    val visible = remember { Animatable(0f) }
    var contentPx by remember { mutableIntStateOf(0) }
    var opened by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var detents by remember { mutableStateOf(emptyList<Float>()) }
    val closeLabel = com.ketvoice.app.data.AppText.t("Fermer")

    fun settle(velocity: Float) {
        if (closing || detents.isEmpty()) return
        val target = SheetDetents.settle(visible.value, velocity, detents, fling)
        scope.launch {
            if (target <= 0f) {
                closing = true
                if (Motion.reduced) visible.snapTo(0f)
                else visible.animateTo(0f, spring(dampingRatio = 1f, stiffness = SheetDetents.STIFFNESS), initialVelocity = -velocity)
                dismiss()
            } else {
                if (kotlin.math.abs(target - visible.value) > 1f) haptic(Haptics.Kind.Tick)
                if (Motion.reduced) visible.snapTo(target)
                else visible.animateTo(target, spring(dampingRatio = SheetDetents.DAMPING, stiffness = SheetDetents.STIFFNESS), initialVelocity = -velocity)
            }
        }
    }
    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            if (!Motion.reduced) visible.animateTo(0f, spring(dampingRatio = 1f, stiffness = SheetDetents.STIFFNESS))
            dismiss()
        }
    }
    /** Moves the sheet by [delta] px (positive downwards) and says how much of it was used. */
    fun drag(delta: Float): Float {
        val top = detents.lastOrNull() ?: return 0f
        val next = (visible.value - delta).coerceIn(0f, top)
        val used = visible.value - next
        // Undispatched: several moves may arrive within one frame, and each must start from the last.
        if (used != 0f) scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { visible.snapTo(next) }
        return used
    }
    val connection = remember {
        object : NestedScrollConnection {
            // Going up, the sheet rises to its full height before the list inside it scrolls.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput && available.y < 0f) Offset(0f, drag(available.y)) else Offset.Zero
            // Going down, the list scrolls back to its top first; what is left pulls the sheet.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput && available.y > 0f) Offset(0f, drag(available.y)) else Offset.Zero
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (detents.any { kotlin.math.abs(it - visible.value) < 1f }) return Velocity.Zero
                settle(available.y)
                return available
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val container = constraints.maxHeight.toFloat()
        val room = maxHeight
        LaunchedEffect(contentPx, container) {
            val next = SheetDetents.heights(contentPx.toFloat(), container)
            detents = next
            if (next.isEmpty() || closing) return@LaunchedEffect
            if (!opened) {
                opened = true
                if (Motion.reduced) visible.snapTo(next.first())
                else visible.animateTo(next.first(), spring(dampingRatio = SheetDetents.DAMPING, stiffness = SheetDetents.STIFFNESS))
            } else if (!visible.isRunning && next.none { kotlin.math.abs(it - visible.value) < 1f }) {
                // The content changed height (a section unfolded): stay on the detent that is nearest now.
                val target = next.minByOrNull { kotlin.math.abs(it - visible.value) } ?: next.first()
                if (Motion.reduced) visible.snapTo(target)
                else visible.animateTo(target, spring(dampingRatio = 1f, stiffness = SheetDetents.STIFFNESS))
            }
        }
        if (scrim > 0f) Box(Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = scrim * SheetDetents.scrim(visible.value, detents))))
        Box(Modifier.fillMaxSize().semantics { contentDescription = closeLabel }
            .clickable(remember { MutableInteractionSource() }, null) { close() })
        CompositionLocalProvider(LocalSheetMaxHeight provides room) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                // What is not showing yet is pushed under the bottom edge.
                .offset { IntOffset(0, (contentPx - visible.value).toInt().coerceAtLeast(0)) }
                .onSizeChanged { contentPx = it.height }
                .nestedScroll(connection)
                .draggable(rememberDraggableState { delta -> drag(delta) }, Orientation.Vertical,
                    onDragStopped = { velocity -> settle(velocity) })
                .testTag("spring-sheet")) { content(room) }
        }
    }
}
