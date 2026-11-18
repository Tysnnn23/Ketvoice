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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * Every widget of 6.4 at every launcher cell, in light and dark: composed by Glance into RemoteViews for the
 * breakpoint the launcher would pick ([WidgetSizing.pick]), inflated on the host at the cell's real size (as an
 * AppWidgetHost does) and drawn into one contact sheet per widget under build/host-ui-preview. A host render, not
 * a launcher: the system corner radius is AOSP's, and fonts are the host's.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetsHostTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val density get() = context.resources.displayMetrics.density

    /** Cells (dp) a phone launcher gives: 2×1, 4×1, a squarish 2×2, 4×2 (two heights), 4×4. */
    private val strips = listOf(130 to 80, 300 to 80)
    private val cards = listOf(160 to 160, 330 to 160, 300 to 210, 330 to 340)

    private fun cover(a: Int, b: Int): Bitmap {
        val out = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawRect(0f, 0f, 256f, 256f, Paint().apply { shader = LinearGradient(0f, 0f, 256f, 256f, a, b, Shader.TileMode.CLAMP) })
        canvas.drawCircle(170f, 90f, 52f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE08A.toInt() })
        return out
    }

    private fun playing(): WidgetsState {
        val c = cover(0xFF3A1C71.toInt(), 0xFFD76D77.toInt())
        val tint = WidgetLook.tint(c)
        val mix = cover(0xFF0F5132.toInt(), 0xFF7BD389.toInt())
        val top = cover(0xFFB34700.toInt(), 0xFF1D3557.toInt())
        return WidgetsState(title = "Midnight City", artist = "M83", playing = true, cover = c, tint = tint,
            ground = WidgetLook.ground(c, tint), plainGround = WidgetLook.ground(null, tint), progress = .38f,
            lyric = "Waiting in a car, waiting for a ride in the dark", nextLyric = "The night city grows",
            weekMinutes = 1214, weekPlays = 57, weekArtist = "M83", weekBars = listOf(.2f, .5f, .08f, 1f, .6f, 0f, .7f),
            mixId = "mix-1", mixLabel = "Ce soir", mixTitle = "Synthwave du soir", mixCover = mix,
            mixGround = WidgetLook.ground(mix, WidgetLook.tint(mix)),
            ketmix = KetmixWidgetState(on = true, nextTitle = "Outro", nextArtist = "M83", nextCover = mix),
            replay = ReplayWidgetState("2026-11", "Novembre", 2_380, 412, "M83", "Wait", "M83", top, WidgetLook.ground(top, WidgetLook.tint(top))))
    }

    private fun render(composeSize: DpSize, widthDp: Int, heightDp: Int, content: @Composable () -> Unit): Pair<Bitmap, List<String>> {
        val result = runBlocking { GlanceRemoteViews().compose(context, composeSize) { content() } }
        val w = (widthDp * density).toInt(); val h = (heightDp * density).toInt()
        val host = FrameLayout(context)
        val view = result.remoteViews.apply(context, host)
        host.addView(view, FrameLayout.LayoutParams(w, h))
        host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
        host.draw(canvas)
        return rounded(bitmap, host) to texts(host)
    }

    /** The rounding each view asks of the launcher (clip to its outline), outermost first. */
    private fun radii(view: View): List<Float> {
        val own = if (view.clipToOutline && view.outlineProvider != null) {
            val outline = android.graphics.Outline()
            view.outlineProvider.getOutline(view, outline)
            listOf(outline.radius)
        } else emptyList()
        return own + if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { radii(view.getChildAt(it)) } else emptyList()
    }

    /**
     * The host's software canvas ignores outline clipping, which a launcher's hardware rendering applies: the card is
     * cut to the radius its view asks for here, so the sheet shows the corners a phone would.
     */
    private fun rounded(bitmap: Bitmap, host: View): Bitmap {
        val radius = radii(host).firstOrNull() ?: return bitmap
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = android.graphics.BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        canvas.drawRoundRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), radius, radius, paint)
        return out
    }

    @Test fun theCardTakesTheSystemRadiusAndButtonsAreRound() {
        val p = playing()
        val result = runBlocking { GlanceRemoteViews().compose(context, WidgetSizing.LARGE) { PlayerContent(p, withLine = false) } }
        val host = FrameLayout(context)
        val w = (330 * density).toInt(); val h = (340 * density).toInt()
        host.addView(result.remoteViews.apply(context, host), FrameLayout.LayoutParams(w, h))
        host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, w, h)
        val all = radii(host)
        val system = context.resources.getDimension(android.R.dimen.system_app_widget_background_radius)
        assertEquals("the card follows the launcher's radius", system, all.first(), .5f)
        // The play button (56 dp) is a circle.
        assertTrue(all.toString(), all.any { kotlin.math.abs(it - 28 * density) < 1f })
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> if (view.visibility == View.VISIBLE) listOf(view.text.toString()) else emptyList()
        is android.view.ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun theme(night: Boolean) = RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")

    /**
     * One contact sheet: a row per theme (light, then dark) and per state, the cells side by side on a wallpaper.
     * Returns the texts shown, per theme and cell.
     */
    private fun sheet(name: String, sizes: Set<DpSize>, cells: List<Pair<Int, Int>>, states: List<WidgetsState>,
                      content: @Composable (WidgetsState) -> Unit): Map<String, List<String>> {
        val gap = 16
        val rowH = cells.maxOf { it.second } + gap
        val sheetW = cells.sumOf { it.first + gap } + gap
        val rows = states.size * 2
        val out = Bitmap.createBitmap((sheetW * density).toInt(), ((rows * rowH + gap) * density).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val shown = LinkedHashMap<String, List<String>>()
        var row = 0
        for (night in listOf(false, true)) {
            theme(night)
            states.forEachIndexed { si, state ->
                val top = (gap + row * rowH) * density
                canvas.drawRect(0f, top - gap * density / 2, out.width.toFloat(), top + rowH * density, Paint().apply {
                    shader = LinearGradient(0f, top, out.width.toFloat(), top + rowH * density,
                        if (night) 0xFF1B2433.toInt() else 0xFF9FB7D9.toInt(), if (night) 0xFF0C0F16.toInt() else 0xFFE7D7E9.toInt(), Shader.TileMode.CLAMP)
                })
                var left = gap * density
                cells.forEach { (w, h) ->
                    val pick = WidgetSizing.pick(sizes, w.toFloat(), h.toFloat())
                    val (bitmap, words) = render(pick, w, h) { content(state) }
                    canvas.drawBitmap(bitmap, left, top, null)
                    shown["${if (night) "dark" else "light"}/$si/${w}x$h"] = words
                    left += (w + gap) * density
                }
                row++
            }
        }
        theme(false)
        val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/widgets-6.4-$name.png")
        file.parentFile?.mkdirs(); file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return shown
    }

    @Test fun thePlayers() {
        val p = playing()
        val paused = p.copy(playing = false, lyric = null, nextLyric = null)
        val shown = sheet("now-playing", WidgetSizing.players, strips + cards, listOf(p, WidgetsState())) { PlayerContent(it, withLine = false) }
        assertTrue(shown.toString(), shown.getValue("light/0/330x160").contains("Midnight City"))
        assertTrue("an empty widget says so", shown.getValue("dark/1/160x160").contains(com.ketvoice.app.data.AppText.t("Rien en lecture")))
        val player = sheet("player", WidgetSizing.players, strips + cards, listOf(p, paused)) { PlayerContent(it, withLine = true) }
        assertTrue("the large player sings", player.getValue("light/0/330x340").contains("Waiting in a car, waiting for a ride in the dark"))
        assertTrue("the roomy medium card sings", player.getValue("light/0/300x210").contains("Waiting in a car, waiting for a ride in the dark"))
        assertFalse("no line while paused", player.getValue("light/1/330x340").any { it.startsWith("Waiting") })
        sheet("mini", WidgetSizing.mini, strips + listOf(160 to 160), listOf(p, WidgetsState())) { PlayerContent(it, withLine = false, mini = true) }
    }

    @Test fun theLiveWidgets() {
        val p = playing()
        val noLyrics = p.copy(lyric = null, nextLyric = null)
        val lyrics = sheet("lyrics", WidgetSizing.live, strips + cards, listOf(p, noLyrics, WidgetsState())) { LyricsContent(it) }
        assertTrue(lyrics.getValue("dark/0/330x160").contains("The night city grows"))
        assertTrue(lyrics.getValue("light/1/330x160").contains(com.ketvoice.app.data.AppText.t("Pas de paroles synchronisées")))
        val off = p.copy(ketmix = KetmixWidgetState())
        val mixing = p.copy(ketmix = p.ketmix.copy(mixing = true))
        val ketmix = sheet("ketmix", WidgetSizing.live, strips + cards, listOf(p, mixing, off)) { KetmixContent(it) }
        assertTrue(ketmix.getValue("light/0/330x160").contains("Outro"))
        assertFalse(ketmix.getValue("light/2/330x160").contains("Outro"))
    }

    @Test fun theListeningWidgets() {
        val p = playing()
        val week = sheet("week", WidgetSizing.cards, cards, listOf(p, WidgetsState())) { WeekContent(it) }
        assertTrue(week.toString(), week.getValue("light/0/160x160").any { it.contains("214") })
        sheet("mix", WidgetSizing.cards, cards, listOf(p, p.copy(mixCover = null, mixGround = null), WidgetsState())) { MixContent(it) }
        val replay = sheet("replay", WidgetSizing.cards, cards, listOf(p, WidgetsState())) { ReplayContent(it) }
        assertTrue(replay.getValue("light/0/330x340").contains("Wait"))
        assertTrue(replay.getValue("dark/0/330x340").contains("M83"))
    }

    @Test fun coversGiveMiddleTonesThatBothVeilsKeepReadable() {
        assertEquals(WidgetLook.GRAPHITE, WidgetLook.tintOf(0xFF808080.toInt(), null, colourful = false))
        for (base in listOf(0xFFFFE600.toInt(), 0xFF00C2FF.toInt(), 0xFF101010.toInt(), 0xFFFFFFFF.toInt(), 0xFFB00020.toInt())) {
            val tint = WidgetLook.tintOf(base, 0xFF00C2FF.toInt(), colourful = true)
            for (end in listOf(tint.base, tint.second)) {
                // The veils over the ground: white at 68 % in light mode, black at 52 % in dark mode.
                val light = androidx.core.graphics.ColorUtils.compositeColors(0xADFFFFFF.toInt(), end)
                val dark = androidx.core.graphics.ColorUtils.compositeColors(0x85000000.toInt(), end)
                val inLight = androidx.core.graphics.ColorUtils.calculateContrast(0xFF111114.toInt(), light)
                val inDark = androidx.core.graphics.ColorUtils.calculateContrast(android.graphics.Color.WHITE, dark)
                assertTrue("light ${Integer.toHexString(end)}: $inLight", inLight >= 7.0)
                assertTrue("dark ${Integer.toHexString(end)}: $inDark", inDark >= 4.5)
            }
        }
        val ground = WidgetLook.ground(null, WidgetLook.DEFAULT)
        assertEquals(WidgetLook.GROUND, ground.width)
        assertEquals(WidgetLook.GROUND, ground.height)
    }
}
