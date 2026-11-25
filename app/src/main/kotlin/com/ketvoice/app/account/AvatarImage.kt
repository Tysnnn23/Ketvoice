package com.ketvoice.app.account

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The profile photo: the picked picture is decoded at a bounded size (orientation applied by the
 * decoder), framed square by the listener, scaled to [SIDE] pixels and compressed to JPEG under [MAX_BYTES].
 */
internal object AvatarImage {
    const val SIDE = AvatarCrop.SIDE
    const val MAX_BYTES = AvatarCrop.MAX_BYTES
    private const val DECODE_MAX = 2048

    /** See [AvatarCrop.square] (pure, shared with the desktop). */
    fun square(width: Int, height: Int, zoom: Float, panX: Float, panY: Float): CropSquare = AvatarCrop.square(width, height, zoom, panX, panY)

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
