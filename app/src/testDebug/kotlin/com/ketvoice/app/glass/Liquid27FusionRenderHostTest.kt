package com.ketvoice.app.glass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * Compiles the fused Liquid 27 AGSL with host Skia and evaluates it in software.
 * Proves the smooth union: separate at strength 0, bridged when close and strong.
 * Not a device GPU result.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Liquid27FusionRenderHostTest {
    private val w = 640
    private val h = 200

    private fun backdrop(): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(Color.rgb(255, 64, 90), Color.rgb(255, 200, 40), Color.rgb(40, 140, 255)), null, Shader.TileMode.CLAMP) })
        val stripe = Paint().apply { color = Color.BLACK }
        for (x in 0 until w step 36) c.drawRect(x.toFloat(), 0f, x + 10f, h.toFloat(), stripe)
        return b
    }

    /** Home circle, mini-player capsule, Search circle on one row, [gap] px apart. */
    private fun shapes(gap: Float): List<FusedShape> {
        val top = 50f; val size = 100f
        val home = Rect(20f, top, 20f + size, top + size)
        val mini = Rect(home.right + gap, top, w - 20f - size - gap, top + size)
        val search = Rect(mini.right + gap, top, mini.right + gap + size, top + size)
        return listOf(FusedShape(mini, 50f), FusedShape(home, 50f), FusedShape(search, 50f))
    }

    private fun render(name: String, gap: Float, fuse: Float, dark: Boolean = false): Bitmap {
        val source = backdrop()
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
        val shader = RuntimeShader(LIQUID27_FUSED_AGSL)
        shader.setInputShader("content", BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        val (lx, ly) = GlassLight.direction()
        applyLiquid27FusedUniforms(shader, w.toFloat(), h.toFloat(), 0f, 2f, GlassParams.navigation(dark = dark),
            shapes(gap), fuse, .5f, Liquid27Light(lx, ly, 0f, 0f, 0f))
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader })
        val dir = File("build/host-ui-preview/liquid27").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return out
    }

    @Test fun withoutStrengthTheControlsStaySeparate() {
        val source = backdrop()
        val out = render("fused-separate", gap = 16f, fuse = 0f)
        // Middle of the gap between Home and the mini-player, at mid-height.
        val x = 20 + 100 + 8; val y = 100
        assertEquals("The gap shows the untouched backdrop", source.getPixel(x, y), out.getPixel(x, y))
        assertNotEquals("Inside Home there is glass", source.getPixel(70, 100), out.getPixel(70, 100))
    }

    @Test fun closeControlsPullALiquidBridge() {
        val source = backdrop()
        val out = render("fused-bridge", gap = 16f, fuse = 52f)
        val x = 20 + 100 + 8; val y = 100
        assertNotEquals("The gap is now glass", source.getPixel(x, y), out.getPixel(x, y))
        // Far above the row nothing is drawn: the bridge is local, not a bounding box.
        assertEquals(source.getPixel(x, 8), out.getPixel(x, 8))
        render("fused-bridge-dark", gap = 16f, fuse = 52f, dark = true)
        render("fused-mid", gap = 34f, fuse = 52f)
    }

    @Test fun distantControlsDoNotBridge() {
        val source = backdrop()
        val out = render("fused-distant", gap = 90f, fuse = 52f)
        val x = 20 + 100 + 45; val y = 100
        assertEquals(source.getPixel(x, y), out.getPixel(x, y))
    }
}
