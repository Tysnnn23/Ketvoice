package com.ketvoice.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.ketvoice.desktop.ui.theme.KetColors

/**
 * The sidebar's and the bar's material: a flat colour, or under « Verre Windows 11 » WinUI's in-app Acrylic, its
 * luminosity tint ([color]) and the 2 % grain that keeps it from looking like plain transparency.
 */
internal fun Modifier.material(ket: KetColors, color: Color = ket.material): Modifier =
    if (!ket.acrylic) background(color) else background(color).drawBehind { drawRect(Acrylic.grain, alpha = .02f) }

internal object Acrylic {
    private const val SIDE = 128

    /** A fixed grey noise tile, repeated (made once, as WinUI's noise texture). */
    val grain: ShaderBrush by lazy { ShaderBrush(ImageShader(noise(), TileMode.Repeated, TileMode.Repeated)) }

    internal fun noise(seed: Long = 0x4B56L): ImageBitmap {
        val image = java.awt.image.BufferedImage(SIDE, SIDE, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val random = java.util.Random(seed)
        for (y in 0 until SIDE) for (x in 0 until SIDE) {
            val v = random.nextInt(256)
            image.setRGB(x, y, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
        }
        return image.toComposeImageBitmap()
    }
}
