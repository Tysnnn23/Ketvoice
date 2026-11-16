package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.player.FullPlayerScreen
import com.ketvoice.app.ui.player.PlayerHero
import com.ketvoice.app.ui.player.PlayerPage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * The screens redone for Ket 27 (2026-11-16): the full-bleed player, Home in the manner of Apple Music,
 * Library and Downloads. Drawn in software Skia on the host with painted fixture covers: layout,
 * semantics and pixels, not a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensRedoHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST")
    private val player get() = Player.state as MutableStateFlow<PlayerUiState>

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
        AppSettings.animatedArtwork.value = false
        paintedCovers()
    }

    @After fun reset() {
        coil.Coil.reset()
        player.value = PlayerUiState()
        AppSettings.reduceAnimations.value = false
        AppSettings.animatedArtwork.value = true
        AppUi.closeOverlays(); AppUi.selectTab(AppUi.HOME)
    }

    /** Every image request answers with a painted cover whose colours follow its address. */
    private fun paintedCovers() {
        val palettes = listOf(intArrayOf(0xFFE8505B.toInt(), 0xFFF9D56E.toInt()), intArrayOf(0xFF3A7BD5.toInt(), 0xFF00D2FF.toInt()),
            intArrayOf(0xFF7F00FF.toInt(), 0xFFE100FF.toInt()), intArrayOf(0xFF11998E.toInt(), 0xFF38EF7D.toInt()),
            intArrayOf(0xFFF7971E.toInt(), 0xFFFFD200.toInt()), intArrayOf(0xFF232526.toInt(), 0xFF8E9EAB.toInt()))
        val cache = HashMap<Int, BitmapDrawable>()
        fun cover(index: Int) = cache.getOrPut(index) {
            val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val (a, b) = palettes[index].let { it[0] to it[1] }
            canvas.drawRect(0f, 0f, 300f, 300f, Paint().apply { shader = LinearGradient(0f, 0f, 300f, 300f, a, b, Shader.TileMode.CLAMP) })
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
            canvas.drawCircle(210f, 96f, 58f, ink)
            canvas.drawRect(36f, 190f, 160f, 206f, ink)
            canvas.drawRect(36f, 220f, 240f, 236f, ink)
            BitmapDrawable(compose.activity.resources, bitmap)
        }
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(compose.activity).components {
            add(coil.intercept.Interceptor { chain ->
                val key = chain.request.data.toString()
                coil.request.SuccessResult(cover(Math.floorMod(key.hashCode(), palettes.size)), chain.request, coil.decode.DataSource.MEMORY)
            })
        }.build())
    }

    private fun image(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png").apply {
                parentFile?.mkdirs(); outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
    }

    private fun track(id: String, title: String = "Titre $id", artist: String = "Artiste $id") =
        Track("fixture:$id", title, artist, "Album $artist", coverUrl = "fixture://cover/$id", durationMs = 200_000)

    // ---------------------------------------------------------------- the player

    private fun playing(vararg tracks: Track) {
        player.value = PlayerUiState(queue = tracks.toList(), queueEntryIds = tracks.map { it.id }, index = 0,
            durationMs = 200_000, isPlaying = true, playWhenReady = true)
    }

    @Test fun thePhonePlayerLaysItsArtworkFullBleedBehindAGlassPlatter() {
        if (com.ketvoice.app.BuildConfig.IS_QUEST) return // Quest keeps its landscape two-pane player.
        playing(track("hero", "Lumière du nord", "Nova"), track("next", "Après"))
        compose.setContent { KetvoiceTheme(useDark = true) { FullPlayerScreen(onDismiss = {}) } }
        val art = compose.onNodeWithTag("player-hero-artwork").fetchSemanticsNode().boundsInRoot
        assertEquals("the artwork starts at the top edge", 0f, art.top, .5f)
        assertEquals("and runs from side to side", 400f, art.width, .5f)
        assertEquals(PlayerHero.coverHeightDp(400f, 900f), art.height, 1f)
        val platter = compose.onNodeWithTag("player-glass-platter").fetchSemanticsNode().boundsInRoot
        assertTrue("the controls' glass runs over the artwork (${platter.top} < ${art.bottom})", platter.top < art.bottom)
        val title = compose.onNodeWithText("Lumière du nord", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the title rests on the glass", title.top > platter.top && title.bottom < platter.bottom)
        compose.onNodeWithTag("player-legibility-gradient").assertExists()
        // Every control stays: heart, the menu, lyrics, output, account, queue, transport.
        compose.onNodeWithContentDescription("Ajouter aux favoris", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Options", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Paroles", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Sortie audio", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("File d'attente", useUnmergedTree = true).assertExists()
        image("redo-player-hero-dark")
        // Host Skia cannot sample a backdrop layer: the static glass shows what the platter is made of.
        compose.runOnIdle { AppSettings.liquidGlass.value = false }
        image("redo-player-hero-static-glass")
        compose.runOnIdle { AppSettings.liquidGlass.value = true }
    }

    @Test fun pausedTheFullBleedArtworkStepsBackAsACard() {
        if (com.ketvoice.app.BuildConfig.IS_QUEST) return
        AppSettings.reduceAnimations.value = false
        playing(track("pause", "En pause", "Nova"))
        player.value = player.value.copy(isPlaying = false, playWhenReady = false)
        compose.setContent { KetvoiceTheme(useDark = true) { FullPlayerScreen(onDismiss = {}) } }
        compose.mainClock.advanceTimeBy(2000)
        val art = compose.onNodeWithTag("player-hero-artwork").fetchSemanticsNode().boundsInRoot
        assertEquals("it steps back by twelve percent", 400f * .88f, art.width, 1f)
        assertTrue("around its centre", art.top > 0f && art.left > 0f)
        compose.runOnIdle { AppSettings.liquidGlass.value = false }
        image("redo-player-hero-paused")
        compose.runOnIdle { AppSettings.liquidGlass.value = true }
    }

    @Test fun lyricsAndQueueKeepTheirPagesOnTheSameGlass() {
        if (com.ketvoice.app.BuildConfig.IS_QUEST) return
        playing(track("lyr", "Les mots", "Nova"), track("q2", "Deuxième"), track("q3", "Troisième"))
        compose.setContent { KetvoiceTheme(useDark = true) { FullPlayerScreen(initialPage = PlayerPage.QUEUE, onDismiss = {}) } }
        compose.onNodeWithTag("player-hero-artwork").assertDoesNotExist()
        val platter = compose.onNodeWithTag("player-glass-platter").fetchSemanticsNode().boundsInRoot
        assertTrue("on the queue the glass carries only the controls (${platter.top})", platter.top > 450f)
        compose.onNodeWithText("Deuxième").assertIsDisplayed()
        image("redo-player-queue-dark")
    }

    @Test @Config(qualifiers = "w900dp-h420dp-mdpi")
    fun aLandscapeWindowKeepsTheArtworkBesideTheControls() {
        if (com.ketvoice.app.BuildConfig.IS_QUEST) return
        playing(track("wide", "Paysage"))
        compose.setContent { KetvoiceTheme(useDark = true) { FullPlayerScreen(onDismiss = {}) } }
        compose.onNodeWithTag("player-glass-platter").assertDoesNotExist()
        compose.onNodeWithTag("player-hero-artwork").assertDoesNotExist()
        compose.onNodeWithText("Paysage", useUnmergedTree = true).assertExists()
    }

    @Test fun coverHeightKeepsTheSidesOfASquareCover() {
        assertEquals(540f, PlayerHero.coverHeightDp(400f, 900f), .01f)
        assertEquals("never shorter than a square", 400f, PlayerHero.coverHeightDp(400f, 500f), .01f)
        assertEquals("never more than 1.4 times the width", 560f, PlayerHero.coverHeightDp(400f, 2000f), .01f)
        assertEquals(0f, PlayerHero.coverHeightDp(Float.NaN, 900f), 0f)
    }
}
