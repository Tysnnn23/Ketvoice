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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 8.3 (owner: the Canvas and the cover « bien visibles, rien de coupé, sans rien d'artificiel »): the phone player's
 * artwork keeps the full width, edge to edge from the top, and takes the height of what it shows (width over the
 * media's shape), down to the top of the controls at most, so the title sits on its foot and the seek bar and the
 * buttons always keep their place. Never a whole Canvas in a smaller box with bars (owner rule of 8.1).
 */
internal object PlayerArtworkSize {
    /** The shortest artwork, for a very wide picture: its sides are then cut, never its height padded. */
    const val MIN_DP = 220f

    /** What the controls under the title take: the lyrics line, the seek bar, the transport, volume and bottom bar. */
    fun controlsDp(safeHeightDp: Float): Float = max(safeHeightDp * .405f, 300f)

    /** The lowest the artwork reaches: the top of the controls. */
    fun capDp(safeHeightDp: Float): Float = safeHeightDp - controlsDp(safeHeightDp)

    /** Height of the artwork for a media of [aspect] (width over height; unknown is a square) at [widthDp]. */
    fun heightDp(widthDp: Float, aspect: Float?, capDp: Float): Float {
        val shape = aspect?.takeIf { it.isFinite() && it > 0f } ?: 1f
        val cap = capDp.coerceAtLeast(1f)
        return (widthDp / shape).coerceIn(min(MIN_DP, cap), cap)
    }

    /**
     * 9.0 « Taille de la pochette »: the same height scaled by [scale] (.85 smaller, 1.15 larger; 1 is [heightDp]).
     * Still full width and top aligned, still never past the controls: a smaller box cuts the media's foot, a larger
     * one its sides — never bars, never a stretched picture.
     */
    fun heightDp(widthDp: Float, aspect: Float?, capDp: Float, scale: Float): Float {
        if (scale == 1f || !scale.isFinite() || scale <= 0f) return heightDp(widthDp, aspect, capDp)
        val shape = aspect?.takeIf { it.isFinite() && it > 0f } ?: 1f
        val cap = capDp.coerceAtLeast(1f)
        val top = if (scale < 1f) cap * scale else cap
        return (widthDp / shape * scale).coerceIn(min(MIN_DP * scale, top), top)
    }

    /**
     * Size of a media of [aspect] covering a box of [boxWidth] × [boxHeight] px: the full width at its own shape,
     * or, for a media wider than the box, the full height (its sides cut evenly).
     */
    fun cover(boxWidth: Int, boxHeight: Int, aspect: Float): Pair<Int, Int> {
        if (!aspect.isFinite() || aspect <= 0f || boxWidth <= 0 || boxHeight <= 0) return boxWidth to boxHeight
        return if (aspect > boxWidth.toFloat() / boxHeight) (boxHeight * aspect).roundToInt().coerceIn(1, 8_192) to boxHeight
        else boxWidth to (boxWidth / aspect).roundToInt().coerceIn(1, 8_192)
    }
}

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
