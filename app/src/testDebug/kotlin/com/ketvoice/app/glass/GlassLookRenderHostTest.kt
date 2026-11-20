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
 * The real Liquid 27 AGSL evaluated by host Skia (software, not a device GPU, no blur pass) at
 * every transparency level and under a tilted light: clearer levels let more of the backdrop
 * through, the default is today's material, and the highlight moves with the tilt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassLookRenderHostTest {
    private val w = 600; private val h = 220
    private val panelW = 520f; private val panelH = 120f
    private val left = 40f; private val top = 50f

    private fun backdrop(): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(Color.rgb(255, 64, 90), Color.rgb(255, 200, 40), Color.rgb(40, 140, 255)), null, Shader.TileMode.CLAMP) })
        val stripe = Paint().apply { color = Color.BLACK }
        for (x in 0 until w step 36) c.drawRect(x.toFloat(), 0f, x + 10f, h.toFloat(), stripe)
        return b
    }

    private fun render(name: String, dark: Boolean, at: Float, tiltX: Float = 0f, tiltY: Float = 0f,
                       tinted: Boolean = false, role: GlassRole = GlassRole.Control): Bitmap {
        val source = backdrop()
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
        val shader = RuntimeShader(LIQUID27_AGSL)
        shader.setInputShader("content", BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(android.graphics.Matrix().apply { setTranslate(-left, -top) })
        })
        val base = if (role == GlassRole.Sheet) GlassParams.sheet(60f, dark = dark) else GlassParams.pill(dark = dark)
        // 7.5: Teinté of 7.1 is the opaque part of the one intensity.
        val level = if (tinted) GlassClarity.TINTED else at
        com.ketvoice.app.data.AppSettings.glassIntensity.value = level
        val params = Ket27Glass.regular(base, level)
        val (lx, ly) = GlassLight.direction(GlassLight.degrees(tiltX))
        applyLiquid27Uniforms(shader, panelW, panelH, 0f, 2f, params, glassCornerRadiiOf(9999f), GlassClarity.frost(level),
            Liquid27Light(lx, ly, 0f, 0f, 0f, GlassLight.glint(tiltY)), GlassClarity.clarity(level))
        com.ketvoice.app.data.AppSettings.glassIntensity.value = GlassClarity.DEFAULT
        canvas.save(); canvas.translate(left, top)
        canvas.drawRect(0f, 0f, panelW, panelH, Paint().apply { this.shader = shader })
        canvas.restore()
        val dir = File("build/host-ui-preview/look").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return out
    }

    private fun luma(c: Int) = .2126f * Color.red(c) + .7152f * Color.green(c) + .0722f * Color.blue(c)

    /** 7.1: Teinté is clearly more opaque than Clair, menus and sheets more than the tab bar, and their text reads on anything. */
    @Test fun tintedAndSheetsAreClearlyMoreOpaqueAndReadable() {
        val x = (left + panelW / 2).toInt().let { it - it % 36 + 4 }
        val y = (top + panelH / 2).toInt()
        for (dark in listOf(false, true)) {
            val tag = if (dark) "dark" else "light"
            val clear = render("mode-$tag-clear", dark, GlassClarity.DEFAULT)
            val tinted = render("mode-$tag-tinted", dark, GlassClarity.DEFAULT, tinted = true)
            val sheet = render("mode-$tag-sheet", dark, GlassClarity.DEFAULT, role = GlassRole.Sheet)
            if (!dark) {
                // Over the black stripe: the milk of Teinté shows far more than Clair's, a sheet's more still.
                assertTrue("tinted ${luma(tinted.getPixel(x, y))} vs clear ${luma(clear.getPixel(x, y))}",
                    luma(tinted.getPixel(x, y)) > luma(clear.getPixel(x, y)) + 40f)
                assertTrue(luma(sheet.getPixel(x, y)) > luma(tinted.getPixel(x, y)))
            }
            // Body text on the sheet, across the whole panel (stripes, red, yellow, blue): at least 4.5:1.
            val ink = if (dark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black
            for (px in (left.toInt() + 30) until (left + panelW).toInt() - 30 step 3) {
                val ratio = com.ketvoice.app.theme.Contrast.contrastRatio(ink, androidx.compose.ui.graphics.Color(sheet.getPixel(px, y)))
                assertTrue("$tag sheet at $px: $ratio", ratio >= 4.5f)
            }
        }
    }

    @Test fun everyLevelCompilesAndClearerLevelsShowMoreOfTheBackdrop() {
        // A black stripe crossing the middle of the panel: clear glass lets it show, tinted glass milks it.
        val x = (left + panelW / 2).toInt().let { it - it % 36 + 4 }
        val y = (top + panelH / 2).toInt()
        for (dark in listOf(false, true)) {
            val probes = (0..6).map { level ->
                luma(render("level-${if (dark) "dark" else "light"}-$level", dark, GlassClarity.fromLevel(level, false)).getPixel(x, y))
            }
            if (!dark) {
                // Light glass lifts dark content less the clearer it is.
                for (i in 0 until probes.lastIndex) assertTrue("light: level $i (${probes[i]}) ≤ level ${i + 1} (${probes[i + 1]})", probes[i] <= probes[i + 1] + .5f)
                assertTrue("The clearest glass is clearly clearer than the default (${probes[0]} vs ${probes[2]})",
                    probes[0] < probes[2] - 8f)
            }
        }
    }

    @Test fun theDefaultLevelIsTheMaterialOfEarlierVersions() {
        // Before the setting: frost 0, no clarity uniform — the same pixels as the default level now.
        val source = backdrop()
        fun draw(clarity: Float?): Bitmap {
            val out = source.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(out)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            val shader = RuntimeShader(LIQUID27_AGSL)
            shader.setInputShader("content", BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(android.graphics.Matrix().apply { setTranslate(-left, -top) })
            })
            val (lx, ly) = GlassLight.direction(GlassLight.REST_DEGREES)
            if (clarity == null) applyLiquid27Uniforms(shader, panelW, panelH, 0f, 2f, GlassParams.pill(), glassCornerRadiiOf(9999f), 0f,
                Liquid27Light(lx, ly, 0f, 0f, 0f))
            else applyLiquid27Uniforms(shader, panelW, panelH, 0f, 2f, GlassParams.pill(), glassCornerRadiiOf(9999f),
                GlassClarity.frost(GlassClarity.DEFAULT), Liquid27Light(lx, ly, 0f, 0f, 0f), clarity)
            canvas.save(); canvas.translate(left, top)
            canvas.drawRect(0f, 0f, panelW, panelH, Paint().apply { this.shader = shader }); canvas.restore()
            return out
        }
        val before = draw(null); val now = draw(GlassClarity.clarity(GlassClarity.DEFAULT))
        assertTrue(before.sameAs(now))
    }

    @Test fun theHighlightMovesWithTheTilt() {
        // The lit rim at the top-left of the capsule against the top-right, as the phone turns.
        val rest = render("tilt-rest", dark = true, at = GlassClarity.DEFAULT)
        val turned = render("tilt-right", dark = true, at = GlassClarity.DEFAULT, tiltX = 1f)
        val back = render("tilt-left", dark = true, at = GlassClarity.DEFAULT, tiltX = -1f)
        val tipped = render("tilt-towards", dark = true, at = GlassClarity.DEFAULT, tiltY = 1f)
        // Sum the rim band along the top edge.
        fun rimTop(b: Bitmap, from: Float, to: Float): Float {
            var sum = 0f
            val y = (top + 1.5f).toInt()
            for (x in (left + panelW * from).toInt() until (left + panelW * to).toInt()) sum += luma(b.getPixel(x, y))
            return sum
        }
        fun rimLeft(b: Bitmap): Float {
            var sum = 0f
            val x = (left + 3f).toInt()
            for (y in (top + panelH * .3f).toInt() until (top + panelH * .7f).toInt()) sum += luma(b.getPixel(x, y))
            return sum
        }
        assertNotEquals("A tilt changes the glass", false, !rest.sameAs(turned))
        // Turned one way, the light comes more from the top: the top rim brightens against the left one.
        val restRatio = rimTop(rest, .4f, .6f) / rimLeft(rest)
        val turnedRatio = rimTop(turned, .4f, .6f) / rimLeft(turned)
        val backRatio = rimTop(back, .4f, .6f) / rimLeft(back)
        assertTrue("turned $turnedRatio > rest $restRatio > back $backRatio", turnedRatio > restRatio && restRatio > backRatio)
        // Tipped towards the light, the glint is brighter.
        assertTrue(rimTop(tipped, .1f, .9f) > rimTop(rest, .1f, .9f))
    }
}
