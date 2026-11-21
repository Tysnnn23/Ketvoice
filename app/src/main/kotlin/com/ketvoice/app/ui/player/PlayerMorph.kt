package com.ketvoice.app.ui.player

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs

/**
 * 8.0 « Lecteur qui se transforme » (owner, picked for 8.0.0): when the lyrics or the queue open, the artwork slides
 * small to the top beside the title, as in Apple Music, and grows back when they close. One progress value drives
 * everything (0 = artwork, 1 = small header); it is a spring, can be caught mid-way, and follows a drag up on the
 * artwork. Everything here is read in layout or draw, never in composition.
 */
internal object PlayerMorph {
    /** The artwork's place in the header: a 56 dp rounded square at the start of the title row. */
    const val SLOT_DP = 56f
    const val SLOT_RADIUS_DP = 8f
    /** How far the title starts in the header: the artwork and a 12 dp gap. */
    const val TITLE_INSET_DP = SLOT_DP + 12f
    /** The title is 22 sp under the artwork and 20 sp in the header (scaled, never re-laid out per frame). */
    const val HEADER_TITLE_SCALE = 20f / 22f
    /** A drag up that passes this part of the way, or flicks faster than [FLICK] per second, opens the lyrics. */
    const val COMMIT = .35f
    const val FLICK = 1.4f

    /** Host renders only: shows this progress instead of the player's own. */
    internal val pinned = mutableStateOf<Float?>(null)

    fun spring(): SpringSpec<Float> = spring(dampingRatio = .86f, stiffness = 330f)

    fun clamp(p: Float): Float = if (p.isFinite()) p.coerceIn(0f, 1f) else 0f
    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    /** [p] mapped to 0..1 between [from] and [to]. */
    fun ramp(p: Float, from: Float, to: Float): Float = clamp((p - from) / (to - from))

    /** With reduced motion nothing travels: the layout changes at the middle while it fades out and back in. */
    fun shape(p: Float, reduced: Boolean): Float = if (reduced) (if (clamp(p) < .5f) 0f else 1f) else clamp(p)
    fun fade(p: Float, reduced: Boolean): Float = if (reduced) abs(2f * clamp(p) - 1f) else 1f

    /** Whether a released drag opens the lyrics: far enough, or a quick flick up (progress per second). */
    fun commits(progress: Float, velocity: Float): Boolean = velocity > FLICK || (progress > COMMIT && velocity > -FLICK)

    /**
     * The square of the artwork that becomes the header's cover: as wide as the place where a Canvas is shown whole
     * (7.6 [CanvasFit]: below « Lecture depuis », above the title), centred in it.
     */
    fun square(frameWidth: Float, fitTop: Float, fitBottom: Float, sideInset: Float): Rect {
        val side = minOf(frameWidth - 2f * sideInset, fitBottom - fitTop).coerceAtLeast(1f)
        val left = (frameWidth - side) / 2f
        val top = fitTop + ((fitBottom - fitTop) - side).coerceAtLeast(0f) / 2f
        return Rect(left, top, left + side, top + side)
    }

    data class Art(val scale: Float, val translationX: Float, val translationY: Float, val clip: Rect, val radius: Float)

    /**
     * The artwork frame ([frame] px, laid out at the top-left of the player) at [p]: [square] travels to [slot] and
     * shrinks to its size, the clip closes from the whole frame onto the square and its corners round to [slotRadius]
     * (in the slot's pixels). Applied with the transform origin at the top-left.
     */
    fun artwork(frame: Size, square: Rect, slot: Rect, slotRadius: Float, p: Float): Art {
        val t = clamp(p)
        val scale = lerp(square.width, slot.width, t) / square.width
        val left = lerp(square.left, slot.left, t)
        val top = lerp(square.top, slot.top, t)
        val clip = Rect(lerp(0f, square.left, t), lerp(0f, square.top, t),
            lerp(frame.width, square.right, t), lerp(frame.height, square.bottom, t))
        return Art(scale, left - scale * square.left, top - scale * square.top, clip, lerp(0f, slotRadius, t) / scale)
    }

    /** A rounded rectangle anywhere inside the layer (its clip). */
    class ClipShape(private val rect: Rect, private val radius: Float) : Shape {
        override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
            Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
    }
}
