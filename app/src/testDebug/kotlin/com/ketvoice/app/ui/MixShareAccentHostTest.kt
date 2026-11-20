package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import com.ketvoice.app.automix.Automix
import com.ketvoice.app.data.AppLanguage
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.CatalogPage
import com.ketvoice.app.data.CatalogRequest
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.CoverLegibility
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.player.MiniMix
import com.ketvoice.app.util.ShareCard
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * 7.3, drawn on the host (not a device): the docked mini-player during a Ketmix mix (phone and tablet),
 * the share card, and album pages taking a light and a dark cover's accent. Covers are drawn by the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MixShareAccentHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val player get() = Player.state as MutableStateFlow<PlayerUiState>
    @Suppress("UNCHECKED_CAST")
    private val mixUi get() = Automix::class.java.getDeclaredField("_ui").apply { isAccessible = true }.get(Automix) as MutableStateFlow<Automix.Ui>
    @Suppress("UNCHECKED_CAST")
    private val lyrics get() = LyricsRepo::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(LyricsRepo) as MutableStateFlow<LyricsRepo.State>
    private var oldController: Any? = null
    private val outgoing = Track("fixture:a", "Lumière froide", "Nova Lune", "Horizons", durationMs = 200_000, coverUrl = "fixture:cover:dark")
    private val incoming = Track("fixture:b", "Septembre", "Iris Vale", "Ville", durationMs = 210_000, coverUrl = "fixture:cover:warm")

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.languageChosen.value = true
        AppSettings.reduceAnimations.value = false
        AppSettings.reduceDynamicBlur.value = true
        oldController = Player::class.java.getDeclaredField("controller").apply { isAccessible = true }.get(Player)
        Player::class.java.getDeclaredField("controller").apply { isAccessible = true }.set(Player, null)
        com.ketvoice.app.ui.player.ShareCardDraw.dispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        pictures()
    }

    @After fun reset() {
        coil.Coil.reset()
        mixUi.value = Automix.Ui()
        lyrics.value = LyricsRepo.State()
        player.value = PlayerUiState()
        AppSettings.reduceAnimations.value = false
        AppSettings.reduceDynamicBlur.value = false
        com.ketvoice.app.ui.player.ShareCardDraw.dispatcher = kotlinx.coroutines.Dispatchers.Default
        Player::class.java.getDeclaredField("controller").apply { isAccessible = true }.set(Player, oldController)
    }

    @Test fun theMiniPlayerHoldsBothCoversDuringAMix() {
        player.value = PlayerUiState(queue = listOf(outgoing), index = 0, isPlaying = true, playWhenReady = true)
        mixUi.value = Automix.Ui(Automix.Phase.Mixing, outgoing, incoming, startedAt = SystemClock.elapsedRealtime() - 4_000L,
            durationMs = 9_000L, bpm = 120f)
        compose.mainClock.autoAdvance = false
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF101014)), contentAlignment = Alignment.Center) {
                Column(Modifier.fillMaxWidth()) {
                    com.ketvoice.app.ui.chrome.MiniPlayerPill(onOpen = {})
                    Box(Modifier.padding(top = 40.dp).width(380.dp)) { com.ketvoice.app.ui.tablet.TabletMiniPlayer(onOpen = {}) }
                }
            }
        } }
        compose.mainClock.advanceTimeBy(1_200)
        image("mini-player-mixing")
        compose.onAllNodesWithTag("mini-mix-covers", useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(2, it.size) }
        // The mix ends: the slot closes on the incoming cover alone.
        player.value = PlayerUiState(queue = listOf(incoming), index = 0, isPlaying = true, playWhenReady = true)
        mixUi.value = Automix.Ui()
        compose.mainClock.advanceTimeBy(1_500)
        compose.onAllNodesWithTag("mini-mix-covers", useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(0, it.size) }
        image("mini-player-after-mix")
    }

    @Test fun reducedMotionOnlyCrossfadesTheCovers() {
        AppSettings.reduceAnimations.value = true
        player.value = PlayerUiState(queue = listOf(outgoing), index = 0, isPlaying = true, playWhenReady = true)
        mixUi.value = Automix.Ui(Automix.Phase.Mixing, outgoing, incoming, startedAt = SystemClock.elapsedRealtime() - 4_000L, durationMs = 9_000L)
        compose.mainClock.autoAdvance = false
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.chrome.MiniPlayerPill(onOpen = {}) } }
        compose.mainClock.advanceTimeBy(600)
        compose.onAllNodesWithTag("mini-mix-covers", useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(0, it.size) }
        compose.onNodeWithTag("mini-mix-fade", useUnmergedTree = true).assertExists()
        assertEquals(.25f, MiniMix.crossfade(.75f, promoted = true), 1e-4f)
    }

    @Test fun theShareCardIsAStoryPicture() {
        val cover = cover(0xFF0F2027.toInt(), 0xFF2C5364.toInt(), 0xFF6DD5ED.toInt())
        val plain = ShareCard.draw(cover, "Lumière froide", "Nova Lune", "", 0xFF2C5364.toInt())
        assertEquals(1080, plain.width); assertEquals(1920, plain.height)
        save(plain, "share-card-title")
        save(ShareCard.draw(cover(0xFFFFF4D6.toInt(), 0xFFF5C26B.toInt(), 0xFFE07A5F.toInt()), "Septembre", "Iris Vale",
            "Et la ville s'allume quand tu passes", 0xFFE07A5F.toInt()), "share-card-line")
        // The sheet, with the lines of the title playing to choose from.
        player.value = PlayerUiState(queue = listOf(outgoing), index = 0, isPlaying = true, playWhenReady = true)
        lyrics.value = LyricsRepo.State(lines = listOf(MusicApi.LyricLine(0, "Sous la lumière froide"),
            MusicApi.LyricLine(4000, "On attend le dernier train"), MusicApi.LyricLine(8000, "Et la nuit nous garde")), source = "Test")
        compose.setContent { KetvoiceTheme(useDark = true) { Box(Modifier.fillMaxSize().background(Color(0xFF15151A))) {
            com.ketvoice.app.ui.player.ShareCardSheet(outgoing) {}
        } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("share-card-preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("share-card-image").assertExists()
        compose.mainClock.advanceTimeBy(1_000)
        image("share-card-sheet")
    }

    @Test fun albumPagesTakeAReadableCoverAccent() {
        // Readable on the page whatever the cover: a pale yellow on a dark page is kept, darkened on a light one.
        val pale = Color(0xFFFFE9A8)
        val onLight = CoverLegibility.pageAccent(pale, Color.Red, Color.White, Color(0xFFF2F2F2))
        assertTrue(CoverLegibility.contrast(onLight, Color.White) >= CoverLegibility.TEXT)
        assertEquals(Color.Red, CoverLegibility.pageAccent(null, Color.Red, Color.White, Color.White))
    }

    @Test fun anAlbumWithALightCoverInALightTheme() = renderAlbum("fixture:cover:light", "light")

    @Test fun anAlbumWithADarkCoverInADarkTheme() = renderAlbum("fixture:cover:dark", "dark")

    private fun renderAlbum(key: String, name: String) {
        AppSettings.reduceAnimations.value = true
        val album = (0 until 8).map { Track("qa:$name:$it", "Titre ${it + 1}", "Nova Lune", "Horizons", durationMs = 180_000L, coverUrl = key) }
        player.value = PlayerUiState(queue = listOf(album[2]), index = 0, isPlaying = true, playWhenReady = true)
        compose.setContent { KetvoiceTheme(useDark = name == "dark") {
            CompositionLocalProvider(androidx.compose.foundation.LocalOverscrollFactory provides null) {
                com.ketvoice.app.ui.sub.AlbumPage(CatalogRequest("album", "MPREqa$name", "Horizons"),
                    CatalogPage("Horizons", "Nova Lune", key, album, "2025-03-14"), album, false, {})
            }
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("album-artwork-loaded").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(1_500)
        image("album-accent-$name")
    }

    private fun pictures() {
        val resources = compose.activity.resources
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(compose.activity).components {
            add(coil.intercept.Interceptor { chain ->
                val bitmap = when (chain.request.data.toString()) {
                    "fixture:cover:light" -> cover(0xFFFFF4D6.toInt(), 0xFFF5C26B.toInt(), 0xFFE07A5F.toInt())
                    "fixture:cover:warm" -> cover(0xFFFF512F.toInt(), 0xFFDD2476.toInt(), 0xFF3A1C71.toInt())
                    else -> cover(0xFF0F2027.toInt(), 0xFF2C5364.toInt(), 0xFF6DD5ED.toInt())
                }
                coil.request.SuccessResult(android.graphics.drawable.BitmapDrawable(resources, bitmap), chain.request, coil.decode.DataSource.MEMORY)
            })
        }.build())
    }

    private fun cover(vararg colors: Int): Bitmap {
        val size = 400
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, size.toFloat(), colors, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = null; paint.color = 0x55FFFFFF
        canvas.drawCircle(size * .5f, size * .5f, size * .26f, paint)
        return bitmap
    }

    private fun save(bitmap: Bitmap, name: String) {
        val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/extra73/$name.png")
        file.parentFile?.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun image(name: String) {
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            save(bitmap, name)
            bitmap.recycle()
        }
    }
}
