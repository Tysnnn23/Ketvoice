package com.ketvoice.desktop.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.player.PlayerArtworkSize

/**
 * 8.3 « Hauteur selon le format » on the desktop: the player's artwork takes the shape of what it shows (the Canvas's
 * video, else its still; a cover is square), edge to edge, never a whole Canvas in a box with bars of colour around it
 * and never stretched. The shared [PlayerArtworkSize] gives the sizes; a change of shape glides (reduced motion jumps).
 */
internal object ArtworkFormat {
    /** Width over height of a Canvas: its video's once decoding started, else its still's; null (a square) until known. */
    fun aspect(reported: Float, posterWidth: Int, posterHeight: Int): Float? = when {
        reported.isFinite() && reported > 0f -> reported
        posterWidth > 0 && posterHeight > 0 -> posterWidth.toFloat() / posterHeight
        else -> null
    }

    /**
     * The full-screen player's art column, as tall as the screen: as wide as the media's shape at that height, between
     * the shortest artwork and [capWidthDp] (a little over half the screen). A square cover gives `min(height, cap)`,
     * the column it always had; a wider media has its sides cut, a taller one leaves the lyrics more room.
     */
    fun sideDp(heightDp: Float, aspect: Float?, capWidthDp: Float): Float =
        PlayerArtworkSize.heightDp(heightDp, aspect?.let { 1f / it }, capWidthDp)
}

/** The aspect of [canvas] as known so far, and what its video reports once it decodes. */
@Composable
internal fun rememberCanvasAspect(canvas: String?): Pair<Float?, (Float) -> Unit> {
    var reported by remember(canvas) { mutableFloatStateOf(0f) }
    val poster = rememberCanvasPoster(canvas)
    val aspect = canvas?.let { ArtworkFormat.aspect(reported, poster?.width ?: 0, poster?.height ?: 0) }
    val report = remember(canvas) { { value: Float -> if (value.isFinite() && value > 0f) reported = value } }
    return aspect to report
}

/** A length that glides on the app's smooth spring toward [target] (jumps under reduced motion). */
@Composable
internal fun rememberGlide(target: Dp, label: String): State<Dp> =
    animateDpAsState(target, Motion.Springs.SMOOTH.motion(), label = label)

/**
 * The windowed full player's Canvas column: as tall as the window and, as wide as the Canvas's shape at that height,
 * between [MIN_SHARE] of the window (an upright Canvas is not a thin strip, its foot is cut a little instead) and what
 * the controls leave ([CONTROLS_DP] beside it, or half the window with the lyrics). The Canvas fills it edge to edge.
 */
internal object CanvasArea {
    const val MIN_SHARE = .38f
    const val CONTROLS_DP = 480f

    fun widthDp(windowWidthDp: Float, windowHeightDp: Float, aspect: Float?, lyrics: Boolean): Float {
        val shape = aspect?.takeIf { it.isFinite() && it > 0f } ?: CanvasSizing.UPRIGHT
        val least = windowWidthDp * MIN_SHARE
        val most = (if (lyrics) windowWidthDp * .5f else windowWidthDp - CONTROLS_DP).coerceAtLeast(least)
        return (windowHeightDp * shape).coerceIn(least, most)
    }
}

/** Its end edge dissolves into what is behind it (the player's moving colours), over the last [from] of its width. */
internal fun Modifier.featherEnd(from: Float = .86f): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(Brush.horizontalGradient(from to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        }

/**
 * The media laid out at its own shape across the box, from the top: when the box is shorter only its foot is cut,
 * when it is wider than the box its sides are cut evenly ([PlayerArtworkSize.cover]).
 */
internal fun Modifier.topAnchoredCrop(aspect: Float): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val boxWidth = constraints.maxWidth; val boxHeight = constraints.maxHeight
    val (width, height) = PlayerArtworkSize.cover(boxWidth, boxHeight, aspect)
    val placeable = measurable.measure(Constraints.fixed(width.coerceAtLeast(0), height.coerceAtLeast(0)))
    layout(boxWidth, boxHeight) { placeable.place((boxWidth - placeable.width) / 2, 0) }
}
