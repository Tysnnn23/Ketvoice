package com.ketvoice.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.ketvoice.app.data.AppLanguage
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.player.FullPlayerScreen
import com.ketvoice.app.ui.player.PlayerArtworkSize
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * 8.3 player83 (owner: the Canvas and the cover « bien visibles, rien de coupé, sans rien d'artificiel »): the phone
 * player's artwork stays full width and edge to edge, and takes the height of what it shows, down to the controls at
 * most. Drawn on the host with made-up pictures (a video cannot decode here: its still gives the Canvas's shape).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Player83HostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context get() = compose.activity.applicationContext
    private val track = Track("qa:player83", "Les heures bleues", "Nova Lune", "Horizons", durationMs = 200_000,
        coverUrl = "fixture:cover", spotifyId = "sp-player83")
    private val state get() = Player.state as MutableStateFlow<PlayerUiState>
    @Suppress("UNCHECKED_CAST")
    private val canvasPaths get() = ReflectionHelpers.getStaticField<MutableStateFlow<Map<String, String>>>(CanvasRepo::class.java, "_paths")
    /** Width and height of the made-up cover. */
    private var coverSize = 400 to 400

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = false
        AppSettings.systemReduceMotion.value = false
        AppSettings.livingArtwork.value = false
        AppSettings.animatedArtwork.value = true
        AppSettings.highPerformanceMode.value = false
        pictures()
        File(context.filesDir, "canvas").deleteRecursively()
        context.getSharedPreferences("ketvoice_canvas", Context.MODE_PRIVATE).edit().clear().commit()
        CanvasRepo.mayFetch = { _, _ -> false }
        CanvasRepo.init(context)
        state.value = PlayerUiState(queue = listOf(track), queueEntryIds = listOf("a"), index = 0, durationMs = 200_000, positionMs = 30_000)
    }

    @After fun reset() {
        AppSettings.livingArtwork.value = true
        canvasPaths.value = emptyMap()
        state.value = PlayerUiState()
    }

    @Test fun heightFollowsTheFormat() {
        // A 412 × 870 dp phone: its controls take 352 dp, so the artwork may reach 518 dp (the title on its foot).
        val cap = PlayerArtworkSize.capDp(870f)
        assertEquals(517.6f, cap, .1f)
        // An upright 9:16 Canvas would be 732 dp tall at full width: it stops at the controls (it was 478 dp, centred).
        assertEquals(cap, PlayerArtworkSize.heightDp(412f, 9f / 16f, cap), .01f)
        // A square cover is shown whole: as tall as it is wide (it was 478 dp, its sides cut).
        assertEquals(412f, PlayerArtworkSize.heightDp(412f, 1f, cap), .01f)
        // A wide cover is shown whole too, shorter; a very wide one keeps a sensible minimum (its sides cut).
        assertEquals(231.75f, PlayerArtworkSize.heightDp(412f, 16f / 9f, cap), .01f)
        assertEquals(PlayerArtworkSize.MIN_DP, PlayerArtworkSize.heightDp(412f, 3f, cap), .01f)
        // Unknown yet: a square.
        assertEquals(412f, PlayerArtworkSize.heightDp(412f, null, cap), .01f)
        // A short screen (360 × 640): the controls keep their 300 dp, the square cover stops at 340 dp.
        assertEquals(340f, PlayerArtworkSize.heightDp(360f, 1f, PlayerArtworkSize.capDp(640f)), .01f)
        // Capped, the media keeps its shape from the top (only its foot is cut); a wider one is cut evenly at its sides.
        assertEquals(800 to 1422, PlayerArtworkSize.cover(800, 1035, 9f / 16f))
        assertEquals(1840 to 1035, PlayerArtworkSize.cover(800, 1035, 16f / 9f))
    }

    @Test fun squareCoverIsAsTallAsWide() = cover(400 to 400, expectedDp = 400f)

    @Test fun wideCoverIsShorter() = cover(640 to 360, expectedDp = 225f)

    @Test @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun shortScreenStopsAtTheControls() = cover(400 to 400, expectedDp = PlayerArtworkSize.capDp(safeHeightDp()))

    @Test fun uprightCanvasStopsAtTheControlsEdgeToEdge() {
        val video = kept()
        canvasPaths.value = mapOf(track.id to video.absolutePath)
        show()
        assertEquals(PlayerArtworkSize.capDp(safeHeightDp()), artworkDp(), 1f)
        // Edge to edge as in 8.1: never the whole Canvas in a smaller box.
        compose.onAllNodesWithTag("canvas-whole", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("fitted-canvas", useUnmergedTree = true).assertCountEquals(0)
        image("canvas-upright")
    }

    @Test fun reducedMotionJumpsToTheHeight() {
        AppSettings.reduceAnimations.value = true
        try { cover(640 to 360, expectedDp = 225f) } finally { AppSettings.reduceAnimations.value = false }
    }

    private fun cover(size: Pair<Int, Int>, expectedDp: Float) {
        coverSize = size
        show()
        assertEquals(expectedDp, artworkDp(), 1f)
        compose.onAllNodesWithTag("canvas-whole", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("fitted-canvas", useUnmergedTree = true).assertCountEquals(0)
        image("cover-${size.first}x${size.second}-${compose.activity.resources.configuration.screenHeightDp}")
    }

    private fun show() {
        // The Quest player is landscape, with its own artwork beside the lyrics (unchanged).
        org.junit.Assume.assumeFalse(com.ketvoice.app.BuildConfig.IS_QUEST)
        compose.mainClock.autoAdvance = false
        compose.setContent { KetvoiceTheme(useDark = true) { FullPlayerScreen(onDismiss = {}) } }
        repeat(8) { compose.mainClock.advanceTimeBy(400) }
    }

    private fun artworkDp(): Float {
        val node = compose.onNodeWithTag("player-artwork", useUnmergedTree = true).fetchSemanticsNode()
        return node.size.height / compose.activity.resources.displayMetrics.density
    }

    /** The window's height without the navigation bar, as the player reads it. */
    private fun safeHeightDp(): Float {
        val root = compose.activity.window.decorView
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(root)
        val bottom = insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val density = compose.activity.resources.displayMetrics.density
        return (root.height - bottom) / density
    }

    /** A Canvas kept on the device with its upright 9:16 still, as CanvasRepo leaves it. */
    private fun kept(): File {
        val dir = File(context.filesDir, "canvas").apply { mkdirs() }
        val video = File(dir, track.spotifyId + ".mp4")
        video.writeBytes(ByteArray(40_000).also { b -> "\u0000\u0000\u0000\u0018ftypmp42".toByteArray(Charsets.ISO_8859_1).copyInto(b) })
        CanvasRepo.extractFrame = { picture(540, 960) }
        assertNotNull(CanvasRepo.ensurePoster(video))
        runCatching {
            val posters = Class.forName("com.ketvoice.app.ui.sub.CanvasPosters")
            (ReflectionHelpers.getField<android.util.LruCache<*, *>>(posters.getField("INSTANCE").get(null), "cache")).evictAll()
        }
        return video
    }

    /** A drawn picture with a white band at its very top and very bottom (what must not be cut). */
    private fun picture(w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xFF1D2B64.toInt(), 0xFF8E4E7A.toInt(), 0xFFF2B880.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null; paint.color = 0xFFFFFFFF.toInt()
        canvas.drawRect(w * .3f, 0f, w * .7f, h * .03f, paint)
        canvas.drawRect(w * .3f, h * .97f, w * .7f, h.toFloat(), paint)
        return bitmap
    }

    private fun pictures() {
        val resources = compose.activity.resources
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(compose.activity).components {
            add(coil.intercept.Interceptor { chain ->
                coil.request.SuccessResult(android.graphics.drawable.BitmapDrawable(resources, picture(coverSize.first, coverSize.second)),
                    chain.request, coil.decode.DataSource.MEMORY)
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
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/player83/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
