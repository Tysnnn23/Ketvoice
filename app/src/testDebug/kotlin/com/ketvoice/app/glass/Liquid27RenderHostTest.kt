package com.ketvoice.app.glass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * Compiles the real Liquid 27 AGSL with host Skia and evaluates it in software over
 * a fixture backdrop. This proves the shader compiles and behaves as designed; it is
 * not a device GPU result (RenderEffect compositing and blur are not run on host).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Liquid27RenderHostTest {
    private val w = 600
    private val h = 220
    private val panelW = 520f
    private val panelH = 120f
    private val left = 40f
    private val top = 50f

    private fun backdrop(): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(Color.rgb(255, 64, 90), Color.rgb(255, 200, 40), Color.rgb(40, 140, 255)), null, Shader.TileMode.CLAMP) })
        val stripe = Paint().apply { color = Color.BLACK }
        for (x in 0 until w step 36) c.drawRect(x.toFloat(), 0f, x + 10f, h.toFloat(), stripe)
        return b
    }

    private fun render(name: String, dark: Boolean, frost: Float, press: Float = 0f, radius: Float = 9999f): Bitmap {
        val source = backdrop()
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
        val shader = RuntimeShader(LIQUID27_AGSL)
        // content.eval(p) must return the backdrop pixel under the panel's local point p.
        val content = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(android.graphics.Matrix().apply { setTranslate(-left, -top) })
        }
        shader.setInputShader("content", content)
        val params = GlassParams.pill(dark = dark)
        val (lx, ly) = GlassLight.direction()
        applyLiquid27Uniforms(shader, panelW, panelH, 0f, 2f, params, glassCornerRadiiOf(radius), frost,
            Liquid27Light(lx, ly, panelW * .3f, panelH * .5f, press))
        canvas.save()
        canvas.translate(left, top)
        canvas.drawRect(0f, 0f, panelW, panelH, Paint().apply { this.shader = shader })
        canvas.restore()
        val dir = File("build/host-ui-preview/liquid27").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return out
    }

    private fun luma(c: Int) = .2126f * Color.red(c) + .7152f * Color.green(c) + .0722f * Color.blue(c)

    @Test fun shaderCompilesForBothThemesAndEveryFrost() {
        for (dark in listOf(false, true)) for (frost in listOf(0f, .5f, 1f)) {
            render("capsule-${if (dark) "dark" else "light"}-${(frost * 100).toInt()}", dark, frost)
        }
        render("card-light-50", dark = false, frost = .5f, radius = 28f)
        render("capsule-light-50-pressed", dark = false, frost = .5f, press = 1f)
    }

    @Test fun outsideTheCapsuleTheBackdropIsUntouched() {
        val source = backdrop()
        val out = render("outside", dark = false, frost = .5f)
        // Corner of the panel's bounding box lies outside a capsule.
        val x = (left + 3).toInt(); val y = (top + 3).toInt()
        assertEquals(source.getPixel(x, y), out.getPixel(x, y))
    }

    @Test fun clearTransmitsTheBackdropAndTintedFrostsIt() {
        val source = backdrop()
        // A black stripe crossing the centre of the panel.
        val x = (left + panelW / 2).toInt().let { it - it % 36 + 4 }
        val y = (top + panelH / 2).toInt()
        assertTrue(luma(source.getPixel(x, y)) < 10f)
        val clear = luma(render("probe-clear", dark = false, frost = 0f).getPixel(x, y))
        val tinted = luma(render("probe-tinted", dark = false, frost = 1f).getPixel(x, y))
        assertTrue("Clear glass lifts dark content for legibility but stays see-through ($clear)", clear < 235f)
        assertTrue("Tinted glass is milkier than clear ($clear → $tinted)", tinted > clear)
    }

    @Test fun darkMaterialStaysDarkOverABrightBackdrop() {
        val x = (left + panelW * .62f).toInt().let { it - it % 36 + 20 }
        val y = (top + panelH / 2).toInt()
        val dark = luma(render("probe-dark", dark = true, frost = .5f).getPixel(x, y))
        assertTrue("Dark glass keeps light labels readable ($dark)", dark < 120f)
    }

    @Test fun pressAddsLightUnderTheFinger() {
        val x = (left + panelW * .3f).toInt().let { it - it % 36 + 20 }
        val y = (top + panelH / 2).toInt()
        val idle = luma(render("probe-idle", dark = true, frost = .5f).getPixel(x, y))
        val pressed = luma(render("probe-pressed", dark = true, frost = .5f, press = 1f).getPixel(x, y))
        assertTrue("Touch glow brightens the glass ($idle → $pressed)", pressed > idle)
    }
}
