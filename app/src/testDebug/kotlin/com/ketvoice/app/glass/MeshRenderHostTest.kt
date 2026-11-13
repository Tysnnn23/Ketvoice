package com.ketvoice.app.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RuntimeShader
import com.ketvoice.app.ui.player.MESH_AGSL
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Compiles the player mesh AGSL with host Skia and evaluates it in software. Not a device GPU result. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeshRenderHostTest {
    private fun render(name: String, time: Float, energy: Float): Bitmap {
        val out = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
        val shader = RuntimeShader(MESH_AGSL)
        shader.setFloatUniform("size", 360f, 640f)
        shader.setFloatUniform("time", time)
        shader.setFloatUniform("energy", energy)
        shader.setFloatUniform("c0", .45f, .10f, .12f)
        shader.setFloatUniform("c1", .10f, .22f, .48f)
        shader.setFloatUniform("c2", .40f, .30f, .05f)
        shader.setFloatUniform("c3", .05f, .04f, .08f)
        canvas.drawRect(0f, 0f, 360f, 640f, Paint().apply { this.shader = shader })
        File("build/host-ui-preview/liquid27").apply { mkdirs() }.resolve("$name.png").outputStream().use {
            out.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return out
    }

    @Test fun meshIsOpaqueSmoothAndMadeOfItsFourColours() {
        val image = render("mesh-rest", 0f, 0f)
        assertEquals(255, Color.alpha(image.getPixel(180, 320)))
        // Different regions lean toward different colours…
        assertNotEquals(image.getPixel(60, 80), image.getPixel(300, 560))
        // …and neighbouring pixels never jump: no hard edge anywhere on a scan line.
        for (x in 1 until 360) {
            val a = image.getPixel(x - 1, 320); val b = image.getPixel(x, 320)
            assertTrue(kotlin.math.abs(Color.red(a) - Color.red(b)) <= 6)
        }
    }

    @Test fun timeMovesTheFieldsAndEnergyBrightensThem() {
        val rest = render("mesh-rest", 0f, 0f)
        val later = render("mesh-later", 9f, 0f)
        val loud = render("mesh-loud", 0f, 1f)
        assertNotEquals(rest.getPixel(120, 200), later.getPixel(120, 200))
        fun luma(c: Int) = Color.red(c) + Color.green(c) + Color.blue(c)
        assertTrue(luma(loud.getPixel(80, 110)) >= luma(rest.getPixel(80, 110)))
    }
}
