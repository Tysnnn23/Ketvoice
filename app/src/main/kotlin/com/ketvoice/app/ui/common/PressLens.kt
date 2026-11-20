package com.ketvoice.app.ui.common

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.glass.LocalBackdropLayer
import com.ketvoice.app.ui.motion.Motion
import kotlinx.coroutines.flow.first

/**
 * « Contrôles qui deviennent verre » (7.5, after Apple's WWDC25 controls): while a finger holds a slider,
 * a small lens of glass forms where its value ends, over the track. It is the tab bar's held lens
 * ([GlassParams.lens]) on the track's own capture: it clears, magnifies and bends what lies under it,
 * with the glass's rim light, and springs back into the track on release.
 *
 * It rides on the press's own spring ([SliderPressState.expansion]), read in the layout and draw phases
 * only: holding and dragging never recompose it frame by frame. Under reduced motion, in the performance
 * mode or without Liquid Glass, no lens forms: the control keeps its simple swell.
 */
internal object PressLensLook {
    fun available(): Boolean = AppSettings.liquidGlass.value && !Motion.reduced && !AppSettings.highPerformanceMode.value

    /** The lens grows out of the track: from a little over half its size to its full size, a touch more on the spring's overshoot. */
    fun scale(expansion: Float): Float = .55f + .45f * (if (expansion.isFinite()) expansion.coerceIn(0f, 1.15f) else 0f)

    /** It is there as soon as the press has begun to swell, and gone before the track is back at rest. */
    fun alpha(expansion: Float): Float = (if (expansion.isFinite()) expansion * 1.8f else 0f).coerceIn(0f, 1f)

    /** The glass of the lens: clear, refracting more than a thumb at rest, a bright rim, no shadow or frost. */
    fun params(dark: Boolean): GlassParams = GlassParams.circle(dark = dark).copy(blurPx = 0f, tintAlpha = .04f,
        refractionHeightDp = 9f, refractionAmountDp = 14f, opticalPressure = 1f, lens = 1f,
        highlightAlpha = .72f, shadow = false, innerShadow = false)
}

/**
 * The lens of a held slider, drawn over [backdrop] (the capture of the track it sits on). [center] gives the
 * lens's centre in px within this box, read at layout time; the box must fill the slider.
 */
@Composable
internal fun PressLens(press: SliderPressState, backdrop: Backdrop, width: Dp, height: Dp, dark: Boolean,
                       modifier: Modifier = Modifier, center: (IntSize) -> Offset) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(press.held) {
        if (press.held) shown = true
        else if (shown) { snapshotFlow { press.expansion }.first { it < .02f }; shown = false }
    }
    if (!shown || !PressLensLook.available()) return
    val params = remember(dark) { PressLensLook.params(dark) }
    CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
        GlassPanel(modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints.fixed(width.roundToPx(), height.roundToPx()))
                val w = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
                val h = if (constraints.hasBoundedHeight) constraints.maxHeight else placeable.height
                layout(w, h) {
                    val c = center(IntSize(w, h))
                    placeable.place(Math.round(c.x - placeable.width / 2f), Math.round(c.y - placeable.height / 2f))
                }
            }
            .graphicsLayer {
                val e = press.expansion
                scaleX = PressLensLook.scale(e); scaleY = PressLensLook.scale(e); alpha = PressLensLook.alpha(e)
            }
            .testTag("press-lens"),
            RoundedCornerShape(minOf(width, height) / 2), params, enabled = AppSettings.liquidGlass.value) {}
    }
}

/** The usual size of the lens over a thin track (a scrub bar, a volume rail). */
internal val PressLensWidth = 34.dp
internal val PressLensHeight = 24.dp
