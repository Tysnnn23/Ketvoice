package com.ketvoice.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * The widgets in the Ket 27 look (6.1), composed by Glance into RemoteViews and inflated on the host as the
 * launcher would, then drawn to PNGs under build/host-ui-preview. A host render, not a launcher.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetsGlassHostTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A cover with two colours, like a real one. */
    private fun cover(a: Int, b: Int): Bitmap {
        val out = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawRect(0f, 0f, 224f, 224f, Paint().apply { shader = LinearGradient(0f, 0f, 224f, 224f, a, b, Shader.TileMode.CLAMP) })
        canvas.drawCircle(150f, 80f, 46f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE08A.toInt() })
        return out
    }

    private fun render(name: String, widthDp: Int, heightDp: Int, content: @Composable () -> Unit): View {
        val result = runBlocking { GlanceRemoteViews().compose(context, DpSize(widthDp.dp, heightDp.dp)) { content() } }
        val density = context.resources.displayMetrics.density
        val w = (widthDp * density).toInt(); val h = (heightDp * density).toInt()
        val host = FrameLayout(context)
        val view = result.remoteViews.apply(context, host)
        host.addView(view, FrameLayout.LayoutParams(w, h))
        val margin = (16 * density).toInt()
        host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w + 2 * margin, h + 2 * margin, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
        // A wallpaper behind, so the rim of light shows.
        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, bitmap.height.toFloat(), 0xFF35506B.toInt(), 0xFF1B2433.toInt(), Shader.TileMode.CLAMP)
        })
        canvas.translate(margin.toFloat(), margin.toFloat())
        host.draw(canvas)
        val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/widget-$name.png")
        file.parentFile?.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return host
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is android.view.ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun state(cover: Bitmap?, lyric: String?): WidgetsState {
        val tint = WidgetGlass.tint(cover)
        return WidgetsState(title = "Midnight City", artist = "M83", playing = true, cover = cover, tint = tint,
            ground = WidgetGlass.ground(cover, tint), plainGround = WidgetGlass.ground(null, tint), progress = .38f, lyric = lyric,
            weekMinutes = 214, weekPlays = 57, weekArtist = "M83", weekBars = listOf(.2f, .5f, .08f, 1f, .6f, 0f, .7f),
            mixId = "mix-1", mixLabel = "Ce soir", mixTitle = "Synthwave du soir", mixCover = cover)
    }

    @Test fun theLargePlayerShowsTheSungLine() {
        val view = render("glass-player-lyric", 340, 170) {
            GlassPlayerContent(state(cover(0xFF3A1C71.toInt(), 0xFFD76D77.toInt()), "Waiting in a car, waiting for a ride in the dark"))
        }
        val shown = texts(view)
        assertTrue(shown.toString(), "Waiting in a car, waiting for a ride in the dark" in shown)
        assertTrue("Midnight City" in shown)
        val plain = render("glass-player-no-lyrics", 340, 170) { GlassPlayerContent(state(cover(0xFF0F5132.toInt(), 0xFF7BD389.toInt()), null)) }
        assertFalse("no line without synced lyrics", texts(plain).any { it.startsWith("Waiting") })
        render("glass-player-empty", 340, 170) { GlassPlayerContent(WidgetsState()) }
    }

    @Test fun theOtherWidgets() {
        val s = state(cover(0xFFB34700.toInt(), 0xFF1D3557.toInt()), null)
        render("mini-controls", 180, 64) { MiniControlsContent(s) }
        render("week", 180, 180) { WeekContent(s) }
        render("mix", 180, 180) { MixContent(s) }
        val now = WidgetNowPlaying("Midnight City", "M83", true, s.cover, s.ground)
        render("now-playing-row", 340, 64) { WidgetContent(now) }
        render("now-playing-card", 340, 140) { WidgetContent(now) }
    }

    @Test fun aGreyCoverGivesGraphiteGlassAndTextStaysReadable() {
        val grey = WidgetGlass.tintOf(0xFF808080.toInt(), null, colourful = false)
        assertEquals(0xFF26262B.toInt(), grey.base)
        val vivid = WidgetGlass.tintOf(0xFFFFE600.toInt(), 0xFF00C2FF.toInt(), colourful = true)
        // White text on the deep glass: at least WCAG AA for normal text.
        val contrast = androidx.core.graphics.ColorUtils.calculateContrast(android.graphics.Color.WHITE, vivid.base)
        assertTrue("contrast $contrast", contrast >= 4.5)
        val ground = WidgetGlass.ground(null, vivid)
        assertEquals(Bitmap.Config.RGB_565, ground.config)
        assertEquals(WidgetGlass.GROUND_W, ground.width)
    }
}
