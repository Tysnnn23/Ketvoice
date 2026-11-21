package com.ketvoice.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.adaptive.LocalLargeScreen
import com.ketvoice.app.ui.player.LyricsPane
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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
 * 8.2 look82: the player's living background (the cover's colours flowing, still in the performance mode), livelier
 * Cascade lyrics, and « À la une » on Home where only the centred card plays its Canvas. Recompositions are counted
 * while each moves (the motion is read in draw), and each screen is rendered light and dark.
 */
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h880dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Look82HostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context get() = compose.activity.applicationContext
    @Suppress("UNCHECKED_CAST") private val player get() = Player.state as MutableStateFlow<PlayerUiState>
    @Suppress("UNCHECKED_CAST") private val position get() = Player.positionMs as MutableStateFlow<Long>
    @Suppress("UNCHECKED_CAST") private val lyrics get() = LyricsRepo.state as MutableStateFlow<LyricsRepo.State>
    @Suppress("UNCHECKED_CAST") private val recent get() = ListeningHistory.tracks as MutableStateFlow<List<Track>>
    @Suppress("UNCHECKED_CAST") private fun <T> field(owner: Class<*>, name: String) = ReflectionHelpers.getStaticField<MutableStateFlow<T>>(owner, name)

    private val tracks = listOf("Lucid Dreams", "Robbery", "Wishing Well", "Righteous", "Bandit").mapIndexed { i, t ->
        Track("qa:l82:$i", t, "Juice WRLD", "Album $i", durationMs = 200_000L, coverUrl = "fixture:cover:$i", spotifyId = "sp-l82-$i")
    }

    private object Runs : CompositionTracer {
        @Volatile var on = false
        val ours = ConcurrentHashMap<String, Int>()
        override fun isTraceInProgress() = on
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            if (info.startsWith("com.ketvoice")) ours.merge(info.substringBefore(' ').substringAfterLast('.'), 1, Int::plus)
        }
        override fun traceEventEnd() {}
    }

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = false
        AppSettings.systemReduceMotion.value = false
        AppSettings.highPerformanceMode.value = false
        AppSettings.animatedArtwork.value = true
        AppSettings.canvasPreloadWifi.value = true
        pictures()
        CanvasRepo.mayFetch = { _, _ -> false }
        CanvasRepo.init(context)
        Composer.setTracer(Runs)
    }

    @After fun reset() {
        Runs.on = false; Runs.ours.clear()
        AppSettings.highPerformanceMode.value = false
        AppUi.showPlayer.value = false
        player.value = PlayerUiState(); position.value = 0L; lyrics.value = LyricsRepo.State(); recent.value = emptyList()
        field<Map<String, String>>(CanvasRepo::class.java, "_paths").value = emptyMap()
    }

    private fun note(line: String) {
        File("build/look82").apply { mkdirs() }.resolve("numbers.txt").appendText(line + "\n")
        println("look82 $line")
    }

    /** Runs [frames] frames of 16 ms (the position ticking every 200 ms) and returns (app runs, frames composed). */
    private fun count(frames: Int, tick: Boolean = true): Pair<Int, Int> {
        Runs.ours.clear(); Runs.on = true
        var composed = 0
        repeat(frames) { i ->
            if (tick && i % 12 == 0) position.value = position.value + 200
            val before = Runs.ours.values.sum()
            compose.mainClock.advanceTimeBy(16)
            if (Runs.ours.values.sum() != before) composed++
        }
        Runs.on = false
        return Runs.ours.values.sum() to composed
    }

    private fun bitmap(): Bitmap {
        lateinit var out: Bitmap
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            out = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
        }
        return out
    }

    private fun image(name: String) {
        val bitmap = bitmap()
        val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/look82/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    /** The mean per-channel difference of a strip at the left edge of the window (the background only). */
    private fun edgeDifference(a: Bitmap, b: Bitmap): Double {
        var sum = 0.0; var n = 0
        for (y in a.height * 3 / 10 until a.height * 45 / 100) for (x in 1 until 12) {
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            sum += Math.abs((p shr 16 and 255) - (q shr 16 and 255)) + Math.abs((p shr 8 and 255) - (q shr 8 and 255)) + Math.abs((p and 255) - (q and 255))
            n += 3
        }
        return sum / n
    }

    // ---- Fond de lecteur vivant ----

    @Test fun theFieldKeepsTheCoversColoursButNoPicture() {
        val side = 48
        // A sharp cover: red on the left, blue on the right, a white bar across the middle.
        val pixels = IntArray(side * side) { i ->
            val x = i % side; val y = i / side
            if (y in 22..25) 0xFFFFFFFF.toInt() else if (x < side / 2) 0xFFC0182A.toInt() else 0xFF1A3AB8.toInt()
        }
        com.ketvoice.app.ui.player.ArtworkFlow.colourField(pixels, side)
        var steepest = 0
        for (y in 0 until side) for (x in 1 until side) {
            val p = pixels[y * side + x]; val q = pixels[y * side + x - 1]
            steepest = maxOf(steepest, Math.abs((p shr 16 and 255) - (q shr 16 and 255)), Math.abs((p and 255) - (q and 255)))
        }
        assertTrue("no edge left in the field ($steepest)", steepest <= 16)
        val left = pixels[10 * side + 2]; val right = pixels[10 * side + side - 3]
        assertTrue("red stays at the left", (left shr 16 and 255) > (left and 255) + 40)
        assertTrue("blue stays at the right", (right and 255) > (right shr 16 and 255) + 40)
    }

    private fun fullPlayer(dark: Boolean) {
        player.value = PlayerUiState(queue = tracks, index = 0, isPlaying = true, playWhenReady = true, durationMs = 200_000)
        position.value = 1_000
        compose.mainClock.autoAdvance = false
        compose.setContent { KetvoiceTheme(useDark = dark) { com.ketvoice.app.ui.player.FullPlayerScreen(onDismiss = {}) } }
        repeat(8) { compose.mainClock.advanceTimeBy(400) }
    }

    @Test fun theFullPlayerComposesAlmostNothingWhileItsBackgroundFlows() {
        fullPlayer(dark = true)
        image("player-living-dark")
        val (runs, composed) = count(300)
        note("full player, 5 s of the living background: $runs app composable runs, $composed of 300 frames composed")
        // The position ticks recompose the time labels; the background itself adds nothing per frame.
        assertTrue("composed frames ($composed)", composed <= 40)
    }

    @Test fun theBackgroundAloneFlowsAndHoldsInThePerformanceMode() {
        // The background alone (the artwork above it has its own gentle motion): flowing, then held.
        compose.mainClock.autoAdvance = false
        compose.setContent { com.ketvoice.app.ui.player.AmbientArtwork("fixture:cover:2", com.ketvoice.app.ui.player.CoverAmbience(), playing = true) }
        repeat(8) { compose.mainClock.advanceTimeBy(400) }
        image("background-alone")
        val a = bitmap(); compose.mainClock.advanceTimeBy(3_000); val b = bitmap()
        val moving = edgeDifference(a, b)
        note("background alone, change over 3 s: ${"%.2f".format(moving)} per channel")
        assertTrue("the colours flow ($moving)", moving > .08)
        // Performance mode: the field slows to a stop and stays.
        compose.runOnIdle { AppSettings.highPerformanceMode.value = true; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(3_000)
        val c = bitmap(); compose.mainClock.advanceTimeBy(3_000); val d = bitmap()
        val held = edgeDifference(c, d)
        note("background alone, change over 3 s in the performance mode: ${"%.2f".format(held)} per channel")
        assertEquals("still in the performance mode", 0.0, held, .01)
    }

    @Test fun playerLight() { fullPlayer(dark = false); image("player-living-light") }

    // ---- Paroles plus vivantes ----

    private fun lyricsPane(dark: Boolean) {
        val words = { start: Long, text: String -> text.split(" ").mapIndexed { i, w ->
            MusicApi.LyricWord(start + i * 400L, if (i < text.split(" ").size - 1) "$w " else w, start + i * 400L + 380L) } }
        lyrics.value = LyricsRepo.State(lines = (0 until 8).map { i ->
            val text = listOf("Je regarde la ville", "Les lumières s'allument", "Et tout recommence", "On chante encore")[i % 4]
            MusicApi.LyricLine(i * 4_000L, text, words(i * 4_000L, text))
        }, isTimed = true, trackId = tracks[0].id)
        player.value = PlayerUiState(queue = tracks, index = 0, isPlaying = true, playWhenReady = true, durationMs = 200_000)
        position.value = 4_500L
        compose.mainClock.autoAdvance = false
        compose.setContent { KetvoiceTheme(useDark = dark) {
            Box(Modifier.fillMaxSize().background(Color(0xFF3A2440)).padding(24.dp)) { LyricsPane(tracks[0], true, {}, Modifier.fillMaxSize()) }
        } }
        repeat(6) { compose.mainClock.advanceTimeBy(400) }
    }

    @Test fun cascadeMovesInDrawWhenTheLineChanges() {
        lyricsPane(dark = true)
        val (still, stillFrames) = count(120)
        note("lyrics, 2 s inside one line: $still app runs, $stillFrames of 120 frames composed")
        position.value = 8_300L
        val (change, changeFrames) = count(90, tick = false)
        note("lyrics, line change and its springs (90 frames, no tick): $change app runs, $changeFrames frames composed")
        note("  " + Runs.ours.entries.sortedByDescending { it.value }.take(8).joinToString { "${it.key} ${it.value}" })
        image("lyrics-living-dark")
        // The springs, the fades and the word growth are read in layers and in the draw: a handful of frames compose.
        assertTrue("line change composed $changeFrames frames", changeFrames <= 12)
    }

    @Test fun lyricsLight() { lyricsPane(dark = false); position.value = 8_900L; repeat(4) { compose.mainClock.advanceTimeBy(300) }; image("lyrics-living-light") }

    // ---- À la une ----

    private fun kept(track: Track): File {
        val dir = File(context.filesDir, "canvas").apply { mkdirs() }
        val video = File(dir, track.spotifyId + ".mp4")
        video.writeBytes(ByteArray(40_000).also { b -> "\u0000\u0000\u0000\u0018ftypmp42".toByteArray(Charsets.ISO_8859_1).copyInto(b) })
        CanvasRepo.extractFrame = { still(0xFF8E3B46.toInt(), 0xFF233A6E.toInt()) }
        assertNotNull(CanvasRepo.ensurePoster(video))
        return video
    }

    private fun home(dark: Boolean) {
        recent.value = tracks
        field<Map<String, String>>(CanvasRepo::class.java, "_paths").value = tracks.take(2).associate { it.id to kept(it).absolutePath }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KetvoiceTheme(useDark = dark) {
                val config = androidx.compose.ui.platform.LocalConfiguration.current
                CompositionLocalProvider(LocalLargeScreen provides null,
                    com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout provides
                        com.ketvoice.app.ui.adaptive.AdaptiveLayout(config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat())) {
                    com.ketvoice.app.ui.home.HomeScreen()
                }
            }
        }
        repeat(8) { compose.mainClock.advanceTimeBy(400) }
    }

    @Test fun onlyTheCentredCardPlaysItsCanvas() {
        home(dark = true)
        compose.onNodeWithText("À la une").assertExists()
        compose.onAllNodesWithTag("home-featured-card", useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithTag("home-featured-canvas", useUnmergedTree = true).assertCountEquals(1)
        image("home-featured-dark")
        // A swipe brings the second card to the middle: it plays, the first goes back to its still.
        compose.onNodeWithTag("home-featured").performTouchInput { swipeLeft(startX = right * .8f, endX = left + right * .35f, durationMillis = 300) }
        val (runs, frames) = count(90, tick = false)
        note("home, swipe to the next featured card (90 frames): $runs app runs, $frames frames composed")
        compose.onAllNodesWithTag("home-featured-canvas", useUnmergedTree = true).assertCountEquals(1)
        image("home-featured-next-dark")
        // The performance mode shows stills only.
        compose.runOnIdle { AppSettings.highPerformanceMode.value = true; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        repeat(4) { compose.mainClock.advanceTimeBy(100) }
        compose.onAllNodesWithTag("home-featured-canvas", useUnmergedTree = true).assertCountEquals(0)
        assertTrue("swipe composed $frames frames", frames <= 30)
    }

    @Test fun homeLight() { home(dark = false); image("home-featured-light") }

    @Test fun featuredRules() {
        val f = com.ketvoice.app.ui.home.HomeFeatured
        assertTrue(f.plays(true, false, false, false, true, unmetered = false, preload = true))
        assertFalse("metered without the preload", f.plays(true, false, false, false, true, unmetered = false, preload = false))
        assertFalse("off screen", f.plays(false, false, false, false, true, true, true))
        assertFalse("under the player", f.plays(true, true, false, false, true, true, true))
        assertFalse("performance mode", f.plays(true, false, true, false, true, true, true))
        assertEquals(1, f.centred(0, 400, listOf(0 to (-300 until 20), 1 to (40 until 360), 2 to (380 until 700))))
        val words = com.ketvoice.app.ui.player.LyricsWords
        assertEquals(1f, words.grow(0f, -400), 0f)
        assertTrue("at most 4 %", (0..20).all { words.grow(it / 20f, -1) <= 1.04f })
        assertEquals("settled once sung", 1f, words.grow(1f, 400), 1e-4f)
        val cascade = com.ketvoice.app.ui.player.LyricsCascade
        assertTrue(cascade.pastAlpha(1) > cascade.pastAlpha(3))
        assertTrue(cascade.pastAlpha(9) > cascade.INACTIVE_ALPHA)
    }

    private fun still(top: Int, bottom: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(270, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, 480f, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 270f, 480f, paint)
        paint.shader = null; paint.color = 0xDDEFD3A8.toInt()
        canvas.drawCircle(135f, 200f, 70f, paint)
        return bitmap
    }

    /** Covers: each fixture its own two colours and a round figure, so a cover is a picture, not a flat colour. */
    private fun pictures() {
        val resources = compose.activity.resources
        val pairs = listOf(0xFFB23A48 to 0xFF2E3A87, 0xFF2F7D6B to 0xFFE0B04A, 0xFF5B2A86 to 0xFFE06D3A,
            0xFF1F4E79 to 0xFF9AD0EC, 0xFF7A1F2B to 0xFF141414)
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(compose.activity).components {
            add(coil.intercept.Interceptor { chain ->
                val key = chain.request.data.toString()
                val (a, b) = pairs[(key.lastOrNull()?.digitToIntOrNull() ?: 0) % pairs.size]
                val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, 0f, 400f, 400f, a.toInt(), b.toInt(), Shader.TileMode.CLAMP) }
                canvas.drawRect(0f, 0f, 400f, 400f, paint)
                paint.shader = null; paint.color = 0xFFF1E4CF.toInt()
                canvas.drawCircle(200f, 170f, 80f, paint)
                coil.request.SuccessResult(android.graphics.drawable.BitmapDrawable(resources, bitmap), chain.request, coil.decode.DataSource.MEMORY)
            })
        }.build())
    }
}
