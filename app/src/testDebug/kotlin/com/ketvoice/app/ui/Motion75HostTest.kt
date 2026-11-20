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
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.adaptive.LocalLargeScreen
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
 * 7.5 motion75 (owner, with a screenshot of « Legends Never Die »: « je voulais quelque chose comme Apple Music, et
 * aussi le cache de Canvas n'est même pas fonctionnel »): the album header is the Canvas itself, and a second opening
 * shows the Canvas's still at once without any request, even before the catalogue has sent the album's titles.
 * A fake network counts requests and bytes. A video cannot decode on the host: the still is what shows, which is
 * also the real state while a device's decoder starts. With `build/motion75-fixture/poster.jpg` present (a frame of
 * the album's real Canvas, never committed) the renders use it; otherwise a drawn fixture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Motion75HostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context get() = compose.activity.applicationContext
    private val requests = AtomicInteger()
    private val bytes = AtomicLong()

    private val request = CatalogRequest("album", "MPREqaLegends", "Legends Never Die")
    private val tracks = listOf("Anxiety (Intro)", "Conversations", "Titanic", "Bad Energy", "Righteous", "Blood On My Jeans")
        .mapIndexed { i, t ->
            Track("qa:ljd:$i", t, "Juice WRLD", "Legends Never Die", durationMs = 120_000L + i * 21_000L,
                coverUrl = "fixture:cover:3", artistId = "UCqaJuice", albumId = "MPREqaLegends", spotifyId = "sp-ljd-$i")
        }
    private val page = CatalogPage("Legends Never Die", "Juice WRLD", "fixture:cover:3", tracks, "2020")

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Class<*>, name: String) = ReflectionHelpers.getStaticField<MutableStateFlow<T>>(owner, name)

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = false
        AppSettings.systemReduceMotion.value = false
        AppSettings.albumHeroCanvas.value = true
        AppSettings.albumHeader.value = 1
        AppSettings.canvasWithMobileData.value = true
        pictures()
        File(context.filesDir, "canvas").deleteRecursively()
        context.getSharedPreferences("ketvoice_canvas", Context.MODE_PRIVATE).edit().clear().commit()
        CanvasRepo.mayFetch = { _, _ -> true }
        CanvasRepo.lookup = { track -> requests.incrementAndGet(); KetvoiceApi.CanvasResult("https://canvaz.example/ljd.cnvs.mp4", track.spotifyId!!) }
        CanvasRepo.fetchFile = { _, dest, _ -> requests.incrementAndGet(); clip(dest) }
        CanvasRepo.extractFrame = { still() }
        restart()
    }

    @After fun reset() {
        CanvasRepo.lookup = { KetvoiceApi.canvas(it) }
        AppSettings.albumHeader.value = 0
        AppSettings.canvasWithMobileData.value = false
        field<Map<String, String>>(CanvasRepo::class.java, "_paths").value = emptyMap()
    }

    /** A new session of the app: what is in memory is gone, what is on the device stays. */
    private fun restart() {
        val repo = CanvasRepo
        CanvasRepo::class.java.getDeclaredField("appContext").apply { isAccessible = true }.set(repo, null)
        field<Map<String, String>>(CanvasRepo::class.java, "_paths").value = emptyMap()
        runCatching { (CanvasRepo::class.java.getDeclaredField("shared").apply { isAccessible = true }.get(repo) as MutableSet<*>).clear() }
        runCatching {
            val posters = Class.forName("com.ketvoice.app.ui.sub.CanvasPosters")
            (ReflectionHelpers.getField<android.util.LruCache<*, *>>(posters.getField("INSTANCE").get(null), "cache")).evictAll()
        }
        CanvasRepo.resetAuth()
        CanvasRepo.init(context)
        requests.set(0); bytes.set(0)
    }

    private fun clip(dest: File): File {
        val body = ByteArray(3_113_426).also { b -> "\u0000\u0000\u0000\u0018ftypmp42".toByteArray(Charsets.ISO_8859_1).copyInto(b) }
        dest.parentFile?.mkdirs(); dest.writeBytes(body); bytes.addAndGet(body.size.toLong()); return dest
    }

    @Test fun secondOpenMakesNoRequestAndShowsTheStillAtOnce() {
        // First opening (as the header does it, see the renders below): the Canvas is looked up and kept, its still
        // written next to it, and the page remembers it under its own id.
        runBlocking { CanvasRepo.resolve(tracks[0]) }
        val video = File(CanvasRepo.paths.value.getValue(tracks[0].id))
        compose.waitUntil(10_000) { CanvasRepo.posterOf(video.absolutePath) != null }
        CanvasRepo.rememberPage(request.id, video.absolutePath)
        val first = requests.get() to bytes.get()
        val still = CanvasRepo.posterOf(video.absolutePath)!!
        println("motion75: first open ${first.first} requests, ${first.second} bytes; kept ${video.name} " +
            "(${video.length()} B) and ${still.name} (${still.length()} B)")
        assertEquals("the still sits next to the Canvas", video.nameWithoutExtension + ".poster.jpg", still.name)

        // Second opening, after a restart, before the catalogue has sent the album's titles.
        restart()
        val started = System.nanoTime()
        frame(dark = true) { com.ketvoice.app.ui.sub.AlbumPage(request, null, emptyList(), true, {}) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("canvas-poster", useUnmergedTree = true).assertExists()
        val shownMs = (System.nanoTime() - started) / 1_000_000.0
        compose.onNodeWithTag("album-header-canvas").assertExists()
        println("motion75: second open ${requests.get()} requests, ${bytes.get()} bytes; still shown in the first frame, " +
            "%.0f ms on the host from setContent".format(shownMs))
        assertEquals("no request on the second opening", 0, requests.get())
        assertEquals(0L, bytes.get())
    }

    @Test fun storedPochetteEtCanvasBecomesTheAnimatedCanvas() {
        assertEquals(1, com.ketvoice.app.ui.sub.AlbumHeader.normalize(2))
        assertEquals(1, PersonalBackup.setting("albumHeader", 2))
        assertEquals(0, PersonalBackup.setting("albumHeader", 0))
        assertEquals(listOf("Pochette", "Canvas animé"), com.ketvoice.app.ui.sub.AlbumHeader.LABELS)
        assertEquals(1f, com.ketvoice.app.ui.sub.AlbumHeader.stretch(0f, 600f))
        assertEquals(0f, com.ketvoice.app.ui.sub.AlbumHeader.parallax(300f, reduced = true))
        assertTrue(com.ketvoice.app.ui.sub.AlbumHeader.motionHeight(400f, 880f) in 600f..640f)
        assertEquals(960 to 540, CanvasPosterSize.fit(1920, 1080))
    }

    @Test fun headerTopDark() = render(dark = true, scrolled = false, "motion75-album-top-dark")
    @Test fun headerTopLight() = render(dark = false, scrolled = false, "motion75-album-top-light")
    @Test fun headerScrolledDark() = render(dark = true, scrolled = true, "motion75-album-scrolled-dark")
    @Test fun headerScrolledLight() = render(dark = false, scrolled = true, "motion75-album-scrolled-light")

    private fun render(dark: Boolean, scrolled: Boolean, name: String) {
        val dir = File(context.filesDir, "canvas").apply { mkdirs() }
        val video = clip(File(dir, "sp-ljd-0.mp4"))
        runBlocking { CanvasRepo.resolve(tracks[0]) }
        assertNotNull(CanvasRepo.ensurePoster(video))
        frame(dark) { com.ketvoice.app.ui.sub.AlbumPage(request, page, tracks, false, {}) }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithTag("canvas-poster", useUnmergedTree = true).assertExists()
        assertNotNull("the header remembers its Canvas under the page's id", CanvasRepo.pageFile(request.id))
        if (scrolled) {
            compose.onNodeWithTag("album-list").performTouchInput { swipeUp(startY = bottom * .85f, endY = bottom * .25f, durationMillis = 400) }
            compose.mainClock.advanceTimeBy(2_000)
        }
        image(name)
    }

    private fun frame(dark: Boolean, content: @Composable () -> Unit) {
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

    /** The real frame when present locally, else a drawn upright still. */
    private fun still(): Bitmap {
        File("build/motion75-fixture/poster.jpg").takeIf { it.isFile }?.let { f ->
            android.graphics.BitmapFactory.decodeFile(f.path)?.let { return it }
        }
        val bitmap = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, 960f, intArrayOf(0xFF2B1B4A.toInt(), 0xFF7A2E5C.toInt(), 0xFFE0A35A.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 540f, 960f, paint)
        paint.shader = null; paint.color = 0x66FFFFFF
        canvas.drawCircle(270f, 400f, 150f, paint)
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
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/motion75/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
