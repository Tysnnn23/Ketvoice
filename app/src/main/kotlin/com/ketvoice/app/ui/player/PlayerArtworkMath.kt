package com.ketvoice.app.ui.player

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
