package com.ketvoice.app.account

import kotlin.math.min
import kotlin.math.roundToInt

/** The square the listener framed, in the picture's own pixels. */
internal data class CropSquare(val left: Int, val top: Int, val side: Int)

/** A frame of any shape (the profile banner's 3:1), in the picture's own pixels. */
internal data class CropRect(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * The framing of a profile picture, without any image code (shared by the phone's AvatarImage and the desktop):
 * [zoom] ≥ 1 enlarges the picture in the frame, pans in −1..1 move it from one edge to the other, and the frame
 * always stays inside the picture.
 */
internal object AvatarCrop {
    const val SIDE = 512
    const val MAX_BYTES = 900 * 1024
    /** The banner: 3:1, at most this wide (a JPEG of it stays well under [MAX_BYTES]). */
    const val BANNER_WIDTH = 1500
    const val BANNER_HEIGHT = 500

    fun square(width: Int, height: Int, zoom: Float, panX: Float, panY: Float): CropSquare {
        require(width > 0 && height > 0)
        val side = (min(width, height) / zoom.coerceIn(1f, 8f)).roundToInt().coerceIn(1, min(width, height))
        val left = ((width - side) * (panX.coerceIn(-1f, 1f) + 1f) / 2f).roundToInt().coerceIn(0, width - side)
        val top = ((height - side) * (panY.coerceIn(-1f, 1f) + 1f) / 2f).roundToInt().coerceIn(0, height - side)
        return CropSquare(left, top, side)
    }

    /** The largest [aspect] (width / height) frame the picture holds, zoomed and panned the same way. */
    fun frame(width: Int, height: Int, aspect: Float, zoom: Float, panX: Float, panY: Float): CropRect {
        require(width > 0 && height > 0 && aspect > 0f)
        val fitW = min(width.toFloat(), height * aspect)
        val w = (fitW / zoom.coerceIn(1f, 8f)).roundToInt().coerceIn(1, width)
        val h = (w / aspect).roundToInt().coerceIn(1, height)
        val left = ((width - w) * (panX.coerceIn(-1f, 1f) + 1f) / 2f).roundToInt().coerceIn(0, width - w)
        val top = ((height - h) * (panY.coerceIn(-1f, 1f) + 1f) / 2f).roundToInt().coerceIn(0, height - h)
        return CropRect(left, top, w, h)
    }
}
