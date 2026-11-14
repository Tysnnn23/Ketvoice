package com.ketvoice.app.ui.sub

import com.ketvoice.app.data.Frame
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Geometry of a [Frame]: how to draw a picture so that the chosen rectangle fills a box,
 * and how to keep that rectangle inside the picture while it is edited. `aspect` is the
 * picture's width over its height; lengths are in widths of the picture.
 */
internal object Framing {
    /** What a layer needs to show the rectangle of [frame] filling a box. */
    class Layer(val scaleX: Float, val scaleY: Float, val rotation: Float, val translationX: Float, val translationY: Float)

    /** Width, in pixels, of the picture when it simply covers a box (ContentScale.Crop). */
    fun baseWidth(boxWidth: Float, boxHeight: Float, aspect: Float): Float =
        if (aspect > boxWidth / boxHeight) boxHeight * aspect else boxWidth

    /** Transform to apply to a picture that covers the box, so that the rectangle fills it instead. */
    fun layer(frame: Frame, boxWidth: Float, boxHeight: Float, aspect: Float): Layer {
        val unit = baseWidth(boxWidth, boxHeight, aspect)
        val zoom = max(boxWidth / (frame.w * unit), boxHeight / (frame.h * unit))
        return Layer(if (frame.flip) -zoom else zoom, zoom, frame.rotation, -frame.cx * unit * zoom, -frame.cy * unit * zoom)
    }

    /** The rectangle a page shows when nothing was framed: the picture covering a box of [boxAspect]. */
    fun default(aspect: Float, boxAspect: Float): Frame =
        if (aspect > boxAspect) Frame(w = boxAspect / aspect, h = 1f / aspect) else Frame(w = 1f, h = 1f / boxAspect)

    /** Size of the upright box around the picture once turned by [rotation]. */
    fun bounds(aspect: Float, rotation: Float): Pair<Float, Float> {
        val angle = Math.toRadians(rotation.toDouble())
        val c = abs(cos(angle)).toFloat(); val s = abs(sin(angle)).toFloat()
        return (c + s / aspect) to (s + c / aspect)
    }

    /** The whole picture, as far as its turn allows. */
    fun original(aspect: Float, rotation: Float = 0f, flip: Boolean = false): Frame {
        val (width, height) = bounds(aspect, rotation)
        return contain(Frame(0f, 0f, width, height, rotation, flip), aspect)
    }

    /** The largest centred square. */
    fun square(aspect: Float, rotation: Float = 0f, flip: Boolean = false): Frame {
        val (width, height) = bounds(aspect, rotation)
        val side = min(width, height)
        return contain(Frame(0f, 0f, side, side, rotation, flip), aspect)
    }

    private fun toPicture(x: Float, y: Float, rotation: Float): Pair<Float, Float> {
        val angle = Math.toRadians(-rotation.toDouble())
        val c = cos(angle).toFloat(); val s = sin(angle).toFloat()
        return (x * c - y * s) to (x * s + y * c)
    }

    /** True when the four corners of the rectangle are on the picture. */
    fun fits(frame: Frame, aspect: Float, slack: Float = 1e-3f): Boolean {
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
            val (x, y) = toPicture(frame.cx + sx * frame.w / 2f, frame.cy + sy * frame.h / 2f, frame.rotation)
            if (abs(x) > .5f + slack || abs(y) > .5f / aspect + slack) return false
        }
        return true
    }

    /**
     * The same rectangle brought back onto the picture: its centre first, then its size,
     * keeping its shape. Used after a turn, which can push corners off the picture.
     */
    fun contain(frame: Frame, aspect: Float): Frame {
        var centreX = frame.cx; var centreY = frame.cy
        repeat(24) {
            val (px, py) = toPicture(centreX, centreY, frame.rotation)
            // Largest share of the size whose four corners stay on the picture, from this centre.
            var share = 1f
            for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
                val (ex, ey) = toPicture(sx * frame.w / 2f, sy * frame.h / 2f, frame.rotation)
                if (abs(ex) > 1e-6f) share = min(share, (.5f - px * (if (ex > 0) 1 else -1)) / abs(ex))
                if (abs(ey) > 1e-6f) share = min(share, (.5f / aspect - py * (if (ey > 0) 1 else -1)) / abs(ey))
            }
            if (share >= 1f) return frame.copy(cx = centreX, cy = centreY)
            if (share > 0f && min(frame.w, frame.h) * share >= Frame.MIN_SIDE * 1.6f)
                return frame.copy(cx = centreX, cy = centreY, w = frame.w * share, h = frame.h * share)
            // Too close to an edge to keep a usable size: come back towards the middle.
            centreX *= .8f; centreY *= .8f
        }
        return frame.copy(cx = 0f, cy = 0f, w = max(Frame.MIN_SIDE, frame.w * .1f), h = max(Frame.MIN_SIDE, frame.h * .1f))
    }

    /** A quarter turn clockwise: the rectangle turns with the picture and keeps showing the same thing. */
    fun quarterTurn(frame: Frame): Frame =
        frame.copy(cx = -frame.cy, cy = frame.cx, w = frame.h, h = frame.w, rotation = frame.rotation + 90f).clamped()

    /** Mirror left to right: the rectangle follows what it was showing. */
    fun mirror(frame: Frame): Frame = frame.copy(cx = -frame.cx, rotation = -frame.rotation, flip = !frame.flip).clamped()

    /** Height of the album hero for a framed picture: the rectangle's own shape, within what a page can give. */
    fun heroHeightDp(frame: Frame, widthDp: Float, windowHeightDp: Float): Float =
        (widthDp / frame.aspect).coerceIn(200f, max(200f, windowHeightDp * .62f))

    /** Which part of the rectangle a finger took: an edge or corner ([x], [y] in −1, 0, 1), or the whole of it. */
    data class Grab(val x: Int, val y: Int) { val moves: Boolean get() = x == 0 && y == 0 }

    /** What a touch at ([px], [py]) takes of the rectangle drawn at these pixels, or null outside it. */
    fun grab(px: Float, py: Float, left: Float, top: Float, right: Float, bottom: Float, reach: Float): Grab? {
        if (px < left - reach || px > right + reach || py < top - reach || py > bottom + reach) return null
        // Small rectangles keep a middle to move them by: handles reach in by a third of a side at most.
        val inX = min(reach, (right - left) / 3f); val inY = min(reach, (bottom - top) / 3f)
        val x = if (px <= left + inX) -1 else if (px >= right - inX) 1 else 0
        val y = if (py <= top + inY) -1 else if (py >= bottom - inY) 1 else 0
        return Grab(x, y)
    }

    private const val SMALLEST = Frame.MIN_SIDE * 2f

    /**
     * The rectangle after a finger moved ([dx], [dy], in widths) while holding [grab]. A move
     * that would leave the picture is refused (per axis for a plain move), never resized.
     */
    fun drag(frame: Frame, grab: Grab, dx: Float, dy: Float, aspect: Float, square: Boolean): Frame {
        if (grab.moves) {
            val both = frame.copy(cx = frame.cx + dx, cy = frame.cy + dy)
            if (fits(both, aspect)) return both
            val horizontal = frame.copy(cx = frame.cx + dx)
            if (fits(horizontal, aspect)) return horizontal
            val vertical = frame.copy(cy = frame.cy + dy)
            return if (fits(vertical, aspect)) vertical else frame
        }
        val candidate = if (square) {
            // One number for both sides: what the finger asks of the side it holds.
            val asked = when {
                grab.x != 0 && grab.y != 0 -> (grab.x * dx + grab.y * dy) / 2f
                grab.x != 0 -> grab.x * dx
                else -> grab.y * dy
            }
            val side = max(SMALLEST, frame.w + asked)
            val change = side - frame.w
            frame.copy(cx = frame.cx + grab.x * change / 2f, cy = frame.cy + grab.y * change / 2f, w = side, h = side)
        } else {
            val width = max(SMALLEST, frame.w + grab.x * dx)
            val height = max(SMALLEST, frame.h + grab.y * dy)
            frame.copy(cx = frame.cx + grab.x * (width - frame.w) / 2f, cy = frame.cy + grab.y * (height - frame.h) / 2f,
                w = width, h = height)
        }
        return if (fits(candidate, aspect)) candidate else frame
    }

    /** Two fingers spreading or closing: the rectangle grows or shrinks around its centre. */
    fun pinch(frame: Frame, factor: Float, aspect: Float): Frame {
        if (!factor.isFinite() || factor <= 0f) return frame
        val f = factor.coerceIn(.5f, 2f)
        val limited = max(f, SMALLEST / min(frame.w, frame.h))
        val candidate = frame.copy(w = frame.w * limited, h = frame.h * limited)
        return if (fits(candidate, aspect)) candidate else frame
    }

    /** The fine angle (−45…45) and the quarter turns of a rotation. */
    fun tilt(rotation: Float): Float {
        val turned = ((rotation % 90f) + 90f) % 90f
        return if (turned > 45f) turned - 90f else turned
    }

    /** The same framing with another fine angle; the quarter turns are kept. */
    fun withTilt(frame: Frame, tilt: Float, aspect: Float): Frame {
        val quarters = frame.rotation - tilt(frame.rotation)
        return contain(frame.copy(rotation = quarters + tilt.coerceIn(-45f, 45f)).clamped(), aspect)
    }
}
