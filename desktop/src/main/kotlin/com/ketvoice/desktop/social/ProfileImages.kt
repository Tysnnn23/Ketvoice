package com.ketvoice.desktop.social

import com.ketvoice.app.account.AvatarCrop
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * The profile photo and banner on Windows: the picked picture (JPEG, PNG, BMP, GIF) is read at a bounded size,
 * framed by the listener (the phone's [AvatarCrop] rules: square for the photo, 3:1 for the banner), scaled and
 * compressed to a JPEG under the bucket's limit. Nothing but the JPEG's pixels leaves the computer (no file name,
 * no metadata: ImageIO writes none).
 */
object ProfileImages {
    /** Larger pictures are refused before decoding (a 60 MB PNG is not a profile photo). */
    const val MAX_FILE = 30L * 1024 * 1024
    private const val DECODE_MAX = 8000

    enum class Kind(val aspect: Float, val width: Int, val height: Int) {
        Avatar(1f, AvatarCrop.SIDE, AvatarCrop.SIDE), Banner(3f, AvatarCrop.BANNER_WIDTH, AvatarCrop.BANNER_HEIGHT)
    }

    fun read(file: File): BufferedImage? {
        if (!file.isFile || file.length() !in 1..MAX_FILE) return null
        val image = runCatching { ImageIO.read(file) }.getOrNull() ?: return null
        return image.takeIf { it.width in 1..DECODE_MAX && it.height in 1..DECODE_MAX }
    }

    /** The framed picture, at its final size, as a JPEG under [AvatarCrop.MAX_BYTES]. */
    fun render(image: BufferedImage, kind: Kind, zoom: Float = 1f, panX: Float = 0f, panY: Float = 0f): ByteArray {
        val crop = AvatarCrop.frame(image.width, image.height, kind.aspect, zoom, panX, panY)
        val out = BufferedImage(kind.width, kind.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.color = Color.BLACK
            g.fillRect(0, 0, kind.width, kind.height)
            g.drawImage(image, 0, 0, kind.width, kind.height, crop.left, crop.top, crop.left + crop.width, crop.top + crop.height, null)
        } finally { g.dispose() }
        return compress(out)
    }

    /** JPEG at the best quality that fits the bucket's limit. */
    fun compress(image: BufferedImage): ByteArray {
        var quality = .88f
        while (true) {
            val bytes = jpeg(image, quality)
            if (bytes.size <= AvatarCrop.MAX_BYTES || quality <= .4f) return bytes.also { require(it.size <= AvatarCrop.MAX_BYTES) }
            quality -= .12f
        }
    }

    private fun jpeg(image: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality }
            writer.write(null, IIOImage(image, null, null), param)
            writer.dispose()
        }
        return out.toByteArray()
    }

    /** A new object name in the listener's folder (the server's pattern: `<id>/avatar-<ms>.jpg`). */
    fun path(userId: String, kind: Kind, now: Long = System.currentTimeMillis()): String =
        "$userId/${if (kind == Kind.Avatar) "avatar" else "banner"}-$now.jpg"
}
