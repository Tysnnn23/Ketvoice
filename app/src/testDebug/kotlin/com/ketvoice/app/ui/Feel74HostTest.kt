package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.Album
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ArtistPage
import com.ketvoice.app.data.ArtistPrefs
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.MusicArtist
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
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
import java.io.File

/**
 * 7.4 « feel »: the immersive artist page (with and without a photo, at the top and scrolled, light and dark) and
 * the full player's background on a light and a dark cover, with Canvas on and off. Drawn on the host, not a
 * device, with made-up pictures; glass draws flat and a Canvas cannot decode here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Feel74HostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val tracks = listOf("Lumière froide", "Nuit d'encre", "Les heures bleues", "Septembre", "Métropole", "Écho").mapIndexed { i, t ->
        Track("qa:feel:$i", t, "Nova Lune", "Horizons", durationMs = 151_000L + i * 13_400L,
            coverUrl = "fixture:cover:${i % 3}", artistId = "UCqaNova", albumId = "MPREqaHorizons")
    }
    private val albums = listOf(Album("MPREqaHorizons", "Horizons", "Nova Lune", "Album · 2025", "fixture:cover:0"),
        Album("MPREqaVille", "Ville lumière", "Nova Lune", "Single · 2023", "fixture:cover:1"))

    private fun page(photo: String?) = ArtistPage(MusicArtist("UCqaNova", "Nova Lune", photo),
        "Nova Lune est une autrice-compositrice française née à Lyon.", tracks, albums, emptyList(), "1,2 M auditeurs mensuels", 1f)

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Class<*>, name: String) = ReflectionHelpers.getStaticField<MutableStateFlow<T>>(owner, name)
    private val state get() = Player.state as MutableStateFlow<PlayerUiState>

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = false
        AppSettings.systemReduceMotion.value = false
        // No endless Ken Burns drift: the host waits for the page to be idle.
        AppSettings.livingArtwork.value = false
        AppSettings.artistPhotos.value = false
        AppSettings.artistSignatures.value = false
        ArtistPrefs.replaceForTest()
        pictures()
    }

    @After fun reset() {
        AppSettings.livingArtwork.value = true
        AppSettings.artistPhotos.value = true
        AppSettings.meshBackground.value = true
        AppSettings.animatedArtwork.value = true
        AppSettings.panoramicCanvas.value = false
        field<Map<String, String>>(CanvasRepo::class.java, "_paths").value = emptyMap()
        state.value = PlayerUiState()
        ArtistPrefs.replaceForTest()
    }

    @Test fun artistWithPhotoDark() = artist("fixture:portrait", dark = true, "feel74-artist-photo-dark")

    @Test fun artistWithPhotoLight() = artist("fixture:portrait", dark = false, "feel74-artist-photo-light")

    @Test fun artistWithoutPhotoUsesTheCover() {
        artist(null, dark = false, "feel74-artist-nophoto-light")
        compose.onNodeWithTag("artist-hero-cover").assertExists()
    }

    @Test fun artistWithoutPhotoDark() = artist(null, dark = true, "feel74-artist-nophoto-dark")

    private fun artist(photo: String?, dark: Boolean, name: String) {
        // Coil's cross-fade does not finish on the host: pictures are drawn without it, then motion comes back
        // for the scroll so the parallax shows.
        AppSettings.reduceAnimations.value = true
        frame(dark) { com.ketvoice.app.ui.sub.ArtistPageFrame(AppUi.ArtistRequest("UCqaNova", "Nova Lune"), page(photo), false, false, 0, {}, {}) }
        compose.onNodeWithTag("artist-portrait").assertIsDisplayed()
        // The actions sit under the picture, still on the first screen.
        compose.onNodeWithTag("artist-follow").assertIsDisplayed()
        val hero = compose.onNodeWithTag("artist-portrait").fetchSemanticsNode().boundsInRoot
        val window = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals("the picture starts under the status bar", 0f, hero.top, 1f)
        assertTrue("about 58 % of the height", hero.height / window.height in .50f..0.62f)
        image("$name-1-top")
        compose.runOnIdle { AppSettings.reduceAnimations.value = false }
        compose.onNodeWithTag("artist-list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 520f) }
        compose.waitForIdle()
        image("$name-2-scrolled")
    }

    @Test fun playerLightCoverFlowing() = player("fixture:light", "feel74-player-light-flowing")

    @Test fun playerDarkCoverFlowing() = player("fixture:dark", "feel74-player-dark-flowing")

    @Test fun playerLightCoverStill() {
        AppSettings.meshBackground.value = false
        player("fixture:light", "feel74-player-light-still")
    }

    @Test fun playerCanvasPanoramicLoading() {
        // A Canvas that cannot decode here: the cover's colours stay, nothing goes black.
        AppSettings.panoramicCanvas.value = true
        val file = File.createTempFile("canvas", ".mp4").apply { deleteOnExit() }
        player("fixture:dark", "feel74-player-canvas-panoramic", canvas = file.absolutePath)
    }

    @Test fun playerCanvasInline() {
        val file = File.createTempFile("canvas", ".mp4").apply { deleteOnExit() }
        player("fixture:light", "feel74-player-canvas-inline", canvas = file.absolutePath)
    }

    private fun player(cover: String, name: String, canvas: String? = null) {
        val track = Track("qa:feel:player:$cover", "Les heures bleues", "Nova Lune", "Horizons", durationMs = 200_000, coverUrl = cover)
        field<Map<String, String>>(CanvasRepo::class.java, "_paths").value = if (canvas != null) mapOf(track.id to canvas) else emptyMap()
        state.value = PlayerUiState(queue = listOf(track), queueEntryIds = listOf("fixture"), index = 0, durationMs = 200_000, positionMs = 61_000)
        compose.mainClock.autoAdvance = false
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.player.FullPlayerScreen(onDismiss = {}) } }
        repeat(6) { compose.mainClock.advanceTimeBy(400) }
        image(name)
    }

    private fun frame(dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            KetvoiceTheme(useDark = dark) {
                val config = androidx.compose.ui.platform.LocalConfiguration.current
                CompositionLocalProvider(com.ketvoice.app.ui.adaptive.LocalAdaptiveLayout provides
                    com.ketvoice.app.ui.adaptive.AdaptiveLayout(config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat()),
                    androidx.compose.foundation.LocalOverscrollFactory provides null) { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun pictures() {
        val resources = compose.activity.resources
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(compose.activity).components {
            add(coil.intercept.Interceptor { chain ->
                val key = chain.request.data.toString()
                val bitmap = when {
                    key == "fixture:portrait" -> portrait()
                    key == "fixture:light" -> gradient(intArrayOf(0xFFF7F2E8.toInt(), 0xFFF3DCD2.toInt(), 0xFFEFE7F6.toInt()), 0x33402A20)
                    key == "fixture:dark" -> gradient(intArrayOf(0xFF070A16.toInt(), 0xFF101B36.toInt(), 0xFF1C2D52.toInt()), 0xCCC9A227.toInt())
                    else -> gradient(listOf(intArrayOf(0xFF41295A.toInt(), 0xFF2F0743.toInt(), 0xFFE96443.toInt()),
                        intArrayOf(0xFF134E5E.toInt(), 0xFF71B280.toInt(), 0xFFF5F7A1.toInt()),
                        intArrayOf(0xFF1D2B64.toInt(), 0xFFF8CDDA.toInt(), 0xFF8E9EAB.toInt()))[(key.substringAfterLast(':').toIntOrNull() ?: 0) % 3],
                        0x55FFFFFF)
                }
                coil.request.SuccessResult(android.graphics.drawable.BitmapDrawable(resources, bitmap), chain.request,
                    coil.decode.DataSource.MEMORY)
            })
        }.build())
    }

    private fun portrait(): Bitmap {
        val size = 600
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), intArrayOf(0xFF2B1B4A.toInt(), 0xFF8C3A5E.toInt(), 0xFFE58B5A.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = RadialGradient(size * .62f, size * .30f, size * .45f, 0x99FFD3A0.toInt(), 0x00FFD3A0, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = null
        paint.color = 0xFF1A1020.toInt()
        canvas.drawCircle(size * .5f, size * .40f, size * .14f, paint)
        canvas.drawOval(size * .22f, size * .58f, size * .78f, size * 1.15f, paint)
        return bitmap
    }

    private fun gradient(colors: IntArray, mark: Int): Bitmap {
        val size = 400
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), colors, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = null
        paint.color = mark
        canvas.drawCircle(size * .5f, size * .5f, size * .24f, paint)
        return bitmap
    }

    private fun image(name: String) {
        // Coil's cross-fades run on the system clock, not the test's: let them finish before the picture is taken.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(2))
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/feel74/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
