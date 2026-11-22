package com.ketvoice.desktop.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
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
 * The windowed full player's Canvas: the column's full width from its top, at the media's height (down to the window's
 * foot at most, then only the foot is cut), its foot dissolving into the player's moving colours.
 */
@Composable
internal fun PlayerCanvasByFormat(canvas: String, playing: Boolean, modifier: Modifier = Modifier) {
    val (aspect, onAspect) = rememberCanvasAspect(canvas)
    val poster = rememberCanvasPoster(canvas)
    BoxWithConstraints(modifier.testTag("canvas-by-format")) {
        val target = PlayerArtworkSize.heightDp(maxWidth.value, aspect, maxHeight.value).dp
        val height by rememberGlide(target, "canvas-height")
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(height).clipToBounds()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(.86f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            }.testTag("canvas-edge")) {
            Box(Modifier.fillMaxSize().topAnchoredCrop(aspect ?: 1f)) {
                poster?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                CanvasVideo(canvas, playing && canvasMoves(), Modifier.fillMaxSize(), onAspect = onAspect)
            }
        }
    }
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
