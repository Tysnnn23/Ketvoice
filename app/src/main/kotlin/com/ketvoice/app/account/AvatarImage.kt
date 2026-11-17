package com.ketvoice.app.account

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The square the listener framed, in the picture's own pixels. */
internal data class CropSquare(val left: Int, val top: Int, val side: Int)

/**
 * The profile photo: the picked picture is decoded at a bounded size (orientation applied by the
 * decoder), framed square by the listener, scaled to [SIDE] pixels and compressed to JPEG under [MAX_BYTES].
 */
internal object AvatarImage {
    const val SIDE = 512
    const val MAX_BYTES = 900 * 1024
    private const val DECODE_MAX = 2048

    /**
     * The square a frame shows: [zoom] ≥ 1 enlarges the picture in the frame, [panX] and [panY] in −1..1
     * move it from one edge to the other. Always inside the picture.
     */
    fun square(width: Int, height: Int, zoom: Float, panX: Float, panY: Float): CropSquare {
        require(width > 0 && height > 0)
        val side = (min(width, height) / zoom.coerceIn(1f, 8f)).roundToInt().coerceIn(1, min(width, height))
        val left = ((width - side) * (panX.coerceIn(-1f, 1f) + 1f) / 2f).roundToInt().coerceIn(0, width - side)
        val top = ((height - side) * (panY.coerceIn(-1f, 1f) + 1f) / 2f).roundToInt().coerceIn(0, height - side)
        return CropSquare(left, top, side)
    }

    fun decode(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > DECODE_MAX) {
                val scale = DECODE_MAX.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
    }

    fun render(bitmap: Bitmap, crop: CropSquare): Bitmap {
        val out = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.BLACK)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(bitmap, Rect(crop.left, crop.top, crop.left + crop.side, crop.top + crop.side), Rect(0, 0, SIDE, SIDE), paint)
        return out
    }

    /** JPEG at the best quality that fits [MAX_BYTES]. */
    fun compress(bitmap: Bitmap): ByteArray {
        var quality = 88
        while (true) {
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            if (bytes.size <= MAX_BYTES || quality <= 40) return bytes.also { require(it.size <= MAX_BYTES) }
            quality -= 12
        }
    }
}
