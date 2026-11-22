package com.ketvoice.app.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Frame
import com.ketvoice.app.ui.motion.Motion

/** The artwork's height: [target] for the media known so far, [height] the spring toward it (read in layout). */
internal class ArtworkHeight(val target: Dp, val height: Animatable<Dp, AnimationVector1D>, val onAspect: (Float) -> Unit)

/**
 * The height of the player's artwork for this title: the owner's framing gives its shape, else the media's own
 * (the Canvas's video size, else its still, else its file's metadata; the cover's picture), a square until known.
 * A change of shape (cover to Canvas, one title to the next) glides on a spring; reduced motion jumps. [fixed]
 * keeps the height of a lying window as it was.
 */
@Composable
internal fun rememberArtworkHeight(trackId: String, canvas: String?, width: Dp, cap: Dp, frame: Frame?,
                                   canvasAspect: Float?, fixed: Dp? = null, scale: Float = 1f): ArtworkHeight {
    val video = canvas?.takeIf { AppSettings.animatedArtwork.value }
    var reported by remember(trackId, video) { mutableFloatStateOf(0f) }
    val poster = com.ketvoice.app.ui.sub.rememberCanvasPoster(video)
    val posterAspect = poster?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height }
    val aspect = frame?.aspect ?: reported.takeIf { it > 0f } ?: video?.let { posterAspect ?: canvasAspect }
    val target = fixed ?: PlayerArtworkSize.heightDp(width.value, aspect, cap.value, scale).dp
    val height = remember { Animatable(target, Dp.VectorConverter) }
    LaunchedEffect(target) {
        if (Motion.reduced || fixed != null) height.snapTo(target) else height.animateTo(target, Motion.Springs.SMOOTH.spec())
    }
    val onAspect = remember(trackId, video) { { value: Float -> if (value.isFinite() && value > 0f) reported = value } }
    return ArtworkHeight(target, height, onAspect)
}

/** The box at the artwork's current height, read in layout so that its spring never recomposes the player. */
internal fun Modifier.artworkHeight(artwork: ArtworkHeight): Modifier = layout { measurable, constraints ->
    val height = artwork.height.value.roundToPx().coerceAtLeast(0)
    val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
    layout(placeable.width, height) { placeable.place(0, 0) }
}

/**
 * The media laid out at its own shape across the box, from the top: when the box is shorter only the foot is cut,
 * and a media that fits is shown whole (the box already has its shape).
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
