package com.ketvoice.app.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.player.FullPlayerScreen
import com.ketvoice.app.ui.player.LyricsPane
import com.ketvoice.app.ui.player.PlayerMorph
import com.ketvoice.app.ui.player.PlayerPage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
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
 * 8.0 player80: the player that morphs (the artwork slides into the header for the lyrics and the queue) at
 * progress 0, ½ and 1, and duet lyrics on the other side. Drawn on the host with made-up pictures, not a device;
 * glass draws flat here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Player80HostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val track = Track("qa:player80", "Les heures bleues (feat. Arlo)", "Nova Lune", "Horizons", durationMs = 200_000,
        coverUrl = "fixture:cover")
    private val state get() = Player.state as MutableStateFlow<PlayerUiState>
    private val lyrics get() = LyricsRepo.state as MutableStateFlow<LyricsRepo.State>
    private val position get() = Player.positionMs as MutableStateFlow<Long>

    // An enhanced LRC duet: v1 is Nova Lune, v2 the featured singer, with a backing vocal on one of v2's lines.
    private val duet = MusicApi.parseLrc("""
        [00:58.00]v1: On marchait sous les heures bleues
        [01:01.00]v1: La ville dormait encore
        [01:04.00]v2: Et moi je comptais les néons (oh oh)
        [01:07.00]v2: Jusqu'à ton nom
        [01:10.00]v1: Reste encore un peu
        [01:13.00]v2: Je reste
    """.trimIndent())

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = false
        AppSettings.systemReduceMotion.value = false
        AppSettings.livingArtwork.value = false
        pictures()
        lyrics.value = LyricsRepo.State(source = "LRCLIB", lines = duet, isTimed = true, trackId = track.id)
        // The player asks for this title's lyrics: they are already there, so nothing reaches the network.
        ReflectionHelpers.setStaticField(LyricsRepo::class.java, "currentTrackId", track.id)
        val next = Track("qa:player80:2", "Nuit d'encre", "Nova Lune", "Horizons", durationMs = 180_000, coverUrl = "fixture:cover")
        val later = Track("qa:player80:3", "Septembre", "Arlo", "Saisons", durationMs = 171_000, coverUrl = "fixture:cover")
        state.value = PlayerUiState(queue = listOf(track, next, later), queueEntryIds = listOf("a", "b", "c"), index = 0,
            durationMs = 200_000, positionMs = 64_500)
        position.value = 64_500
    }

    @After fun reset() {
        PlayerMorph.pinned.value = null
        AppUi.showLyrics.value = false
        AppSettings.livingArtwork.value = true
        lyrics.value = LyricsRepo.State()
        ReflectionHelpers.setStaticField(LyricsRepo::class.java, "currentTrackId", null)
        state.value = PlayerUiState()
    }

    @Test fun duetLinesOfTheFeaturedSingerGoRight() {
        assertEquals(listOf(false, false, true, true, false, true),
            com.ketvoice.app.data.LyricsDuet.sides(duet, track.title, track.artist).toList())
    }

    @Test fun lyricsMorph() = morph(PlayerPage.LYRICS, dark = true, "lyrics")
    @Test fun queueMorph() = morph(PlayerPage.QUEUE, dark = true, "queue")
    @Test fun lyricsMorphLight() = morph(PlayerPage.LYRICS, dark = false, "lyrics-light", listOf(1f))
    @Test fun queueMorphLight() = morph(PlayerPage.QUEUE, dark = false, "queue-light", listOf(1f))

    /** 8.1: a tap on the header's small cover over the lyrics or the queue brings the player back to its main screen. */
    @Test fun headerCoverLeavesTheLyrics() = headerCoverReturns(PlayerPage.LYRICS)
    @Test fun headerCoverLeavesTheQueue() = headerCoverReturns(PlayerPage.QUEUE)

    private fun headerCoverReturns(page: PlayerPage) {
        // The Quest's landscape player has no header cover: lyrics and queue sit beside the artwork.
        org.junit.Assume.assumeFalse(com.ketvoice.app.BuildConfig.IS_QUEST)
        AppUi.showLyrics.value = page == PlayerPage.LYRICS
        PlayerMorph.pinned.value = null
        compose.mainClock.autoAdvance = true
        compose.setContent { KetvoiceTheme(useDark = true) { FullPlayerScreen(initialPage = page, onDismiss = {}) } }
        compose.waitForIdle()
        compose.onNodeWithTag("header-cover-back", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag("header-cover-back", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(!AppUi.showLyrics.value)
    }

    private fun morph(page: PlayerPage, dark: Boolean, name: String, steps: List<Float> = listOf(0f, .5f, 1f)) {
        AppUi.showLyrics.value = page == PlayerPage.LYRICS
        PlayerMorph.pinned.value = steps.first()
        compose.mainClock.autoAdvance = false
        compose.setContent { KetvoiceTheme(useDark = dark) { FullPlayerScreen(initialPage = page, onDismiss = {}) } }
        steps.forEach { p ->
            compose.runOnIdle { PlayerMorph.pinned.value = p }
            repeat(4) { compose.mainClock.advanceTimeBy(400) }
            image("$name-${(p * 100).toInt()}")
        }
        // At rest in the header the title sits beside the small cover.
        assertTrue(compose.onAllNodesWithText("Les heures bleues", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun duetInCascadeWithSectionHeaders() {
        // No singer data: the headers name the featured artist of the title, so his lines go across.
        val lines = listOf("[Couplet 1 : Nova Lune]", "On marchait sous les heures bleues", "La ville dormait encore",
            "[Couplet 2 : Arlo]", "Et moi je comptais les néons", "Jusqu'à ton nom", "[Refrain]", "Reste encore un peu")
            .map { MusicApi.LyricLine(-1L, it) }
        lyrics.value = LyricsRepo.State(source = "LRCLIB (texte)", lines = lines, isTimed = false, trackId = track.id)
        assertEquals(listOf(false, false, false, true, true, true, false, false),
            com.ketvoice.app.data.LyricsDuet.sides(lines, track.title, track.artist).toList())
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF1B1820)).padding(24.dp)) { LyricsPane(track, true, {}, Modifier.fillMaxSize()) }
        } }
        compose.mainClock.advanceTimeBy(2_000)
        val left = compose.onNodeWithText("La ville dormait encore", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        image("duet-headers")
        assertTrue("a main-artist line starts at the left", left.left < 60f)
    }

    private fun pictures() {
        val resources = compose.activity.resources
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(compose.activity).components {
            add(coil.intercept.Interceptor { chain ->
                val size = 400
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(),
                    intArrayOf(0xFF1D2B64.toInt(), 0xFF8E4E7A.toInt(), 0xFFF2B880.toInt()), null, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
                paint.shader = null
                paint.color = 0x66FFFFFF
                canvas.drawCircle(size * .5f, size * .5f, size * .24f, paint)
                coil.request.SuccessResult(android.graphics.drawable.BitmapDrawable(resources, bitmap), chain.request,
                    coil.decode.DataSource.MEMORY)
            })
        }.build())
    }

    private fun image(name: String) {
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(2))
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/player80/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
