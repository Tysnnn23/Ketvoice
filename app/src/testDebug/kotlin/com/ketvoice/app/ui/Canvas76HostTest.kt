package com.ketvoice.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.adaptive.LocalLargeScreen
import com.ketvoice.app.ui.player.CanvasFit
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * 7.6 canvas76 (owner: « un scale intelligent sur les Canvas … qu'on voie bien le Canvas sans que ça cache et que ça
 * ne rende pas trop grand, mais le Canvas est complet »): each surface shows the whole Canvas. Renders with an upright
 * 9:16 still and a wide 16:9 still, light and dark (a video cannot decode on the host: its still is what shows).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Canvas76HostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context get() = compose.activity.applicationContext
    private val request = CatalogRequest("album", "MPREqaLegends", "Legends Never Dies")
    private val tracks = listOf("Anxiety (Intro)", "Conversations", "Titanic").mapIndexed { i, t ->
        Track("qa:c76:$i", t, "Juice WRLD", "Legends Never Dies", durationMs = 180_000L, coverUrl = "fixture:cover",
            artistId = "UCqaJuice", albumId = "MPREqaLegends", spotifyId = "sp-c76-$i")
    }
    private val page = CatalogPage("Legends Never Dies", "Juice WRLD", "fixture:cover", tracks, "2020")
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Class<*>, name: String) = ReflectionHelpers.getStaticField<MutableStateFlow<T>>(owner, name)
    private val state get() = Player.state as MutableStateFlow<PlayerUiState>

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = false
        AppSettings.systemReduceMotion.value = false
        AppSettings.albumHeroCanvas.value = true
        AppSettings.animatedArtwork.value = true
        AppSettings.albumHeader.value = 1
        pictures()
        File(context.filesDir, "canvas").deleteRecursively()
        context.getSharedPreferences("ketvoice_canvas", Context.MODE_PRIVATE).edit().clear().commit()
        CanvasRepo.mayFetch = { _, _ -> false }
        CanvasRepo.init(context)
    }

    @After fun reset() {
        AppSettings.albumHeader.value = 0
        field<Map<String, String>>(CanvasRepo::class.java, "_paths").value = emptyMap()
    }

    @Test fun theShapeDecidesFillOrWhole() {
        // Close shapes fill (at most 12 % cut off), others are shown whole, as large as the place allows.
        assertTrue(CanvasFit.place(1080f, 1920f, 9f / 16f).fill)
        assertTrue(CanvasFit.place(400f, 760f, 9f / 16f).fill)
        CanvasFit.place(376f, 343f, 9f / 16f).let { assertFalse(it.fill); assertEquals(343f, it.height); assertEquals(192.9f, it.width, .1f) }
        CanvasFit.place(400f, 544f, 16f / 9f).let { assertFalse(it.fill); assertEquals(400f, it.width); assertEquals(225f, it.height, .1f) }
        // The album header: the whole upright Canvas at full width would be 811 dp; it stops at 72 % of the window.
        assertEquals(633.6f, com.ketvoice.app.ui.sub.AlbumHeader.motionHeight(400f, 880f), .1f)
        // A wide Canvas gets a short header that holds it whole at full width.
        assertEquals(349f, com.ketvoice.app.ui.sub.AlbumHeader.motionHeight(400f, 880f, 16f / 9f, 24f), .1f)
    }

    @Test fun albumUprightDark() = album(upright = true, dark = true, "c76-album-upright-dark")
    @Test fun albumUprightLight() = album(upright = true, dark = false, "c76-album-upright-light")
    @Test fun albumWideDark() = album(upright = false, dark = true, "c76-album-wide-dark")
    @Test fun albumWideLight() = album(upright = false, dark = false, "c76-album-wide-light")
    @Test fun playerUpright() = player(upright = true, "c76-player-upright")
    @Test fun playerWide() = player(upright = false, "c76-player-wide")

    @Test @Config(qualifiers = "w860dp-h400dp-land-mdpi")
    fun standbyUpright() {
        val video = kept(tracks[1], upright = true)
        field<Map<String, String>>(CanvasRepo::class.java, "_paths").value = mapOf(tracks[1].id to video.absolutePath)
        val clock = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 14); set(java.util.Calendar.MINUTE, 7) }
        show(true) { com.ketvoice.app.ui.standby.StandbyScreen(tracks[1], playing = true, onClose = {}, clock = { clock }) }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithTag("canvas-poster", useUnmergedTree = true).assertExists()
        image("c76-standby-upright")
    }

    private fun album(upright: Boolean, dark: Boolean, name: String) {
        val video = kept(tracks[0], upright)
        field<Map<String, String>>(CanvasRepo::class.java, "_paths").value = mapOf(tracks[0].id to video.absolutePath)
        show(dark) { com.ketvoice.app.ui.sub.AlbumPage(request, page, tracks, false, {}) }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithTag("canvas-poster", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(if (upright) "canvas-whole" else "canvas-fill", useUnmergedTree = true).assertExists()
        image(name)
    }

    private fun player(upright: Boolean, name: String) {
        val video = kept(tracks[1], upright)
        field<Map<String, String>>(CanvasRepo::class.java, "_paths").value = mapOf(tracks[1].id to video.absolutePath)
        state.value = PlayerUiState(queue = listOf(tracks[1]), queueEntryIds = listOf("fixture"), index = 0, durationMs = 180_000, positionMs = 61_000)
        compose.mainClock.autoAdvance = false
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.player.FullPlayerScreen(onDismiss = {}) } }
        repeat(6) { compose.mainClock.advanceTimeBy(400) }
        compose.onNodeWithTag("canvas-whole", useUnmergedTree = true).assertExists()
        image(name)
    }

    /** A Canvas kept on the device with its still, as CanvasRepo leaves it. */
    private fun kept(track: Track, upright: Boolean): File {
        val dir = File(context.filesDir, "canvas").apply { mkdirs() }
        val video = File(dir, track.spotifyId + ".mp4")
        video.writeBytes(ByteArray(40_000).also { b -> "\u0000\u0000\u0000\u0018ftypmp42".toByteArray(Charsets.ISO_8859_1).copyInto(b) })
        CanvasRepo.extractFrame = { still(upright) }
        assertNotNull(CanvasRepo.ensurePoster(video))
        runCatching {
            val posters = Class.forName("com.ketvoice.app.ui.sub.CanvasPosters")
            (ReflectionHelpers.getField<android.util.LruCache<*, *>>(posters.getField("INSTANCE").get(null), "cache")).evictAll()
        }
        return video
    }

    private fun show(dark: Boolean, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KetvoiceTheme(useDark = dark) {
                val config = androidx.compose.ui.platform.LocalConfiguration.current
                CompositionLocalProvider(LocalLargeScreen provides null,
                    com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout provides
                        com.ketvoice.app.ui.adaptive.AdaptiveLayout(config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat()),
                    androidx.compose.foundation.LocalOverscrollFactory provides null) { content() }
            }
        }
    }

    /** A drawn still: a figure in the middle, warm at the left edge, cool at the right, a mark at each end. */
    private fun still(upright: Boolean): Bitmap {
        val (w, h) = if (upright) 540 to 960 else 960 to 540
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0xFF7A2E2E.toInt(), 0xFF3A2B5C.toInt(), 0xFF1E5A6E.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null; paint.color = 0xCCF2D7A6.toInt()
        canvas.drawCircle(w / 2f, h * .45f, minOf(w, h) * .24f, paint)
        // What must stay visible: a band at the very top and at the very bottom of the video.
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawRect(w * .3f, 0f, w * .7f, h * .03f, paint)
        canvas.drawRect(w * .3f, h * .97f, w * .7f, h.toFloat(), paint)
        return bitmap
    }

    private fun pictures() {
        val resources = compose.activity.resources
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(compose.activity).components {
            add(coil.intercept.Interceptor { chain ->
                val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val paint = Paint().apply { shader = LinearGradient(0f, 0f, 0f, 400f, 0xFF3A1C71.toInt(), 0xFFDD2476.toInt(), Shader.TileMode.CLAMP) }
                canvas.drawRect(0f, 0f, 400f, 400f, paint)
                coil.request.SuccessResult(android.graphics.drawable.BitmapDrawable(resources, bitmap), chain.request, coil.decode.DataSource.MEMORY)
            })
        }.build())
    }

    private fun image(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/canvas76/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
