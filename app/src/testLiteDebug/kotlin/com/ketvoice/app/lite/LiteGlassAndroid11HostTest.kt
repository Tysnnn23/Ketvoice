package com.ketvoice.app.lite

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.glass.*
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.common.Text
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import kotlin.math.abs

/**
 * Ketvoice Lite's glass on Android 11 (no RenderEffect): the backdrop is blurred by SoftBlur, so the glass shows
 * the colours behind it diffused, not a flat panel nor a sharp window. Host Skia in software, not a phone's GPU.
 *
 * What the host can and cannot show: a page captured with `drawContent()` does not replay when a layer is drawn
 * in software (the app's real backdrops draw black on the host, with or without this change), so the scene's
 * backdrop paints its stripes itself. The host draws [BackdropBlur]'s software path; a phone draws its GPU pyramid,
 * which [gpuPyramidEmulated] reproduces with bitmaps and bilinear filtering (what HWUI does with scaled layers).
 *
 * Declared for Android 11 itself (sdk 30) and for Android 12 with the glass told it runs on 11 (sdk 31,
 * [GlassCompat.api] = 30). Without the Android 11 host runtime on the machine, pass `-ProbolectricSdks=31`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 31], qualifiers = "w400dp-h420dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiteGlassAndroid11HostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun android11() {
        GlassCompat.api = 30
        AppSettings.liquidGlass.value = true
        AppSettings.reduceAnimations.value = false
        AppSettings.reduceDynamicBlur.value = false
    }

    @After fun reset() {
        GlassCompat.api = android.os.Build.VERSION.SDK_INT
        AppSettings.highPerformanceMode.value = com.ketvoice.app.LiteTuning.defaultPerformanceMode()
        AppSettings.liquidGlass.value = true
    }

    private val colours = listOf(Color(0xFFE53935), Color(0xFFFDD835), Color(0xFF1E88E5), Color(0xFF43A047),
        Color(0xFF8E24AA), Color(0xFFFF7043))

    /** Twelve sharp stripes with a white mark in each: what a list of covers and titles looks like to the glass. */
    private fun DrawScope.stripes() {
        val h = size.height / 12f
        repeat(12) { i ->
            drawRect(colours[i % colours.size], Offset(0f, i * h), Size(size.width, h))
            drawRect(Color.White, Offset(36f + (i % 3) * 120f, i * h + h * .35f), Size(420f, h * .3f))
        }
    }

    private fun scene(dark: Boolean) {
        compose.setContent { KetvoiceTheme(useDark = dark) {
            val backdrop = rememberLayerBackdrop { stripes() }
            Box(Modifier.fillMaxSize()) {
                BackdropCapture(backdrop, Modifier.fillMaxSize()) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { stripes() }
                }
                CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        GlassPanel(Modifier.width(300.dp).height(64.dp).testTag("glass-pill"), RoundedCornerShape(32.dp),
                            GlassParams.pill(dark = dark)) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Lecture en cours") }
                        }
                        GlassPanel(Modifier.width(340.dp).height(120.dp).testTag("glass-card"), RoundedCornerShape(28.dp),
                            GlassParams.navigation(dark = dark)) { Box(Modifier.fillMaxSize()) }
                    }
                }
            }
        } }
        compose.waitForIdle()
    }

    private fun save(bitmap: Bitmap, name: String) {
        val dir = File("build/host-ui-preview/lite/android11").apply { mkdirs() }
        File(dir, "$name-sdk${android.os.Build.VERSION.SDK_INT}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun render(name: String): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        runCatching { ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true) } // absent before Android 13
        compose.runOnIdle { view.draw(canvas) }
        save(bitmap, name)
        return bitmap
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    /** Largest jump between vertical neighbours in a region: a sharp stripe edge jumps hundreds, a blur a few dozen. */
    private fun roughness(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Double {
        var most = 0
        for (x in left until right step 7) for (y in top until bottom - 1) {
            val p = bitmap.getPixel(x, y); val q = bitmap.getPixel(x, y + 1)
            most = maxOf(most, abs(android.graphics.Color.red(p) - android.graphics.Color.red(q)) +
                abs(android.graphics.Color.green(p) - android.graphics.Color.green(q)) +
                abs(android.graphics.Color.blue(p) - android.graphics.Color.blue(q)))
        }
        return most.toDouble()
    }

    private fun roughness(bitmap: Bitmap, tag: String): Double {
        val b = bounds(tag)
        return roughness(bitmap, b.left.toInt() + 60, b.top.toInt() + 40, b.right.toInt() - 60, b.bottom.toInt() - 40)
    }

    /** How far the colours spread down the middle of a panel: a flat panel has none, glass keeps the stripes' colours. */
    private fun spread(bitmap: Bitmap, tag: String): Int {
        val b = bounds(tag)
        val x = b.center.x.toInt()
        val column = (b.top.toInt() + 16 until b.bottom.toInt() - 16).map { bitmap.getPixel(x, it) }
        val reds = column.map { android.graphics.Color.red(it) }; val blues = column.map { android.graphics.Color.blue(it) }
        return maxOf(reds.max() - reds.min(), blues.max() - blues.min())
    }

    @Test fun glassBlursWhatIsBehindItOnAndroid11() {
        AppSettings.highPerformanceMode.value = true
        assertTrue(SoftBlur.needed())
        scene(dark = true)
        val blurred = render("glass-blurred-dark")
        val glassRough = roughness(blurred, "glass-card")
        val glassSpread = spread(blurred, "glass-card")
        val b = bounds("glass-card")
        // The same stripes beside the glass, sharp.
        val pageRough = roughness(blurred, 0, b.top.toInt() + 12, 60, b.bottom.toInt() - 12)
        AppSettings.liquidGlass.value = false
        compose.waitForIdle()
        val flat = render("glass-static-dark")
        val flatSpread = spread(flat, "glass-card")
        println("android11 glass: roughness=$glassRough (page $pageRough) spread=$glassSpread (static $flatSpread)")
        // The stripes' hard edges are gone...
        assertTrue("glass must blur: roughness $glassRough vs page $pageRough", glassRough < pageRough / 4)
        // ...but their colours still show through: it is glass, not a flat panel.
        assertTrue("glass must show the colours behind it, spread $glassSpread", glassSpread > 40)
    }

    @Test fun lightGlassToo() {
        AppSettings.highPerformanceMode.value = false
        scene(dark = false)
        val blurred = render("glass-blurred-light")
        // The card: the pill carries its label, whose letters are sharp on purpose.
        val b = bounds("glass-card")
        val pageRough = roughness(blurred, 0, b.top.toInt() + 12, 60, b.bottom.toInt() - 12)
        val glassRough = roughness(blurred, "glass-card")
        val glassSpread = spread(blurred, "glass-card")
        println("android11 light glass: roughness=$glassRough (page $pageRough) spread=$glassSpread")
        assertTrue("light glass must blur: $glassRough vs $pageRough", glassRough < pageRough / 4)
        assertTrue("light glass must show the colours behind it: $glassSpread", glassSpread > 40)
    }

    /**
     * The phone's path, emulated: the stripes drawn at a quarter of their size, halved, then enlarged back step by
     * step, every step a bilinear-filtered bitmap draw as HWUI draws a scaled offscreen layer.
     */
    @Test fun gpuPyramidEmulated() {
        val w = 1000; val h = 360
        val source = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            val stripe = h / 6f
            repeat(6) { i ->
                drawRect(0f, i * stripe, w.toFloat(), (i + 1) * stripe, Paint().apply { color = colours[i].toArgb() })
                drawRect(40f + i * 90f, i * stripe + stripe * .3f, 460f + i * 90f, i * stripe + stripe * .7f, Paint().apply { color = -1 })
            }
        }
        val sigma = SoftBlur.sigma(Ket27GlassProbe.radius)
        val factor = SoftBlur.pyramidFactor(sigma)
        val first = SoftBlur.firstStep(factor)
        val sizes = BackdropBlur.pyramidSizes(w.toFloat(), h.toFloat(), first, factor)
        val filter = Paint(Paint.FILTER_BITMAP_FLAG)
        fun scaled(src: Bitmap, size: androidx.compose.ui.unit.IntSize, by: Float) =
            Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply { scale(by, by); drawBitmap(src, 0f, 0f, filter) }
            }
        val levels = ArrayList<Bitmap>()
        levels += scaled(source, sizes[0], 1f / first)
        for (i in 1 until sizes.size) levels += scaled(levels.last(), sizes[i], .5f)
        var top = levels.last()
        for (i in sizes.size - 2 downTo 0) top = scaled(top, sizes[i], 2f)
        val out = scaled(top, androidx.compose.ui.unit.IntSize(w, h), first.toFloat())
        save(source, "pyramid-source")
        save(out, "pyramid-emulated-f$factor")
        val sharp = roughness(source, 0, 0, w, h)
        val soft = roughness(out, 0, 0, w, h)
        println("pyramid sigma=$sigma factor=$factor first=$first levels=${sizes.size} roughness $sharp -> $soft")
        assertTrue(soft < sharp / 4)
    }
}

/** A typical Liquid 27 control blur at the default transparency (GlassClarity.DEFAULT), in pixels. */
private object Ket27GlassProbe { const val radius = 16f }

private fun Color.toArgb(): Int = android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
