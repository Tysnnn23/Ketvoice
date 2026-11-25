package com.ketvoice.desktop.ui.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The glass drawn for real (Skia's CPU backend runs the same SkSL): stripes behind a surface come out bent and frosted
 * inside it and untouched outside, a change behind it shows at the next frame, and the three modes differ.
 * Renders `build/desktop-ui-preview/glass-<mode>.png`.
 */
class GlassRenderTest {
    private val shift = mutableFloatStateOf(0f)

    private fun scene(mode: GlassMode) = ImageComposeScene(600, 400, Density(1f)) {
        CompositionLocalProvider(LocalGlassMode provides mode) {
            val b = rememberGlassBackdrop()
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().glassSource(b)) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(Color(0xFF101820))
                        var x = -200f + shift.floatValue
                        var i = 0
                        while (x < size.width + 200) {
                            drawRect(if (i % 2 == 0) Color(0xFFE53935) else Color(0xFFFFEB3B), Offset(x, 0f), Size(18f, size.height))
                            x += 36f; i++
                        }
                        drawCircle(Color(0xFF42A5F5), 60f, Offset(300f, 200f))
                    }
                }
                Box(Modifier.offset(150.dp, 100.dp).size(300.dp, 200.dp).glass(b, 40.dp, Color.Black.copy(alpha = .12f), GlassStyle.Clear))
            }
        }
    }

    private fun Image.at(x: Int, y: Int) = Bitmap.makeFromImage(this).getColor(x, y)

    private fun shoot(mode: GlassMode): Image {
        val s = scene(mode)
        try {
            val image = s.render(0)
            File("build/desktop-ui-preview").apply { mkdirs() }.resolve("glass-${mode.name.lowercase()}.png")
                .writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            return image
        } finally { s.close() }
    }

    @Test fun theGlassBendsWhatIsBehindItAndFollowsItsChanges() {
        shift.floatValue = 0f
        val s = scene(GlassMode.LIQUID)
        try {
            val a = s.render(0)
            // Outside the surface the stripes are as drawn (x = 50 is in a dark gap: -200 + 7 × 36 = 52 starts a stripe).
            assertEquals(0xFF101820.toInt(), a.at(40, 50))
            shift.floatValue = 18f
            val b = s.render(16_000_000)
            assertNotEquals(a.at(300, 120), b.at(300, 120), "the surface follows what moved behind it")
            assertNotEquals(a.at(40, 50), b.at(40, 50))
        } finally { s.close() }
    }

    @Test fun eachModeDrawsItsOwnGlass() {
        shift.floatValue = 0f
        val liquid = shoot(GlassMode.LIQUID)
        val frosted = shoot(GlassMode.FROSTED)
        val tinted = shoot(GlassMode.TINTED)
        // Near the rim the shader bends the stripes; the plain blur does not.
        val rim = 160 to 200
        assertNotEquals(liquid.at(rim.first, rim.second), frosted.at(rim.first, rim.second))
        // The flat tint covers what is behind more than the glass does.
        assertNotEquals(tinted.at(300, 150), liquid.at(300, 150))
        assertNotEquals(tinted.at(300, 150), frosted.at(300, 150))
        assertTrue(Bitmap.makeFromImage(tinted).getColor(300, 150).let { (it shr 16 and 0xFF) + (it shr 8 and 0xFF) } <
            Bitmap.makeFromImage(liquid).getColor(300, 150).let { (it shr 16 and 0xFF) + (it shr 8 and 0xFF) })
    }
}
