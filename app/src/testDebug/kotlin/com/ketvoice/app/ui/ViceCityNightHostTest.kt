package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.ViceCityEdition
import com.ketvoice.app.data.ViceFm
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
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The Vice City theme's 80s night (6.3), drawn on the host in software, not on a device: the neon floor behind the
 * tab bar, the chrome titles of Home, Explorer and the Library, the neon KETVOICE sign, Vice FM, the player's neon
 * ring and times, a held note's neon glow, a sheet and a toast with the neon edge, on a phone and a tablet. And
 * everything gone with the theme off or after the edition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViceCityNightHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST")
    private val player get() = Player.state as MutableStateFlow<PlayerUiState>
    @Suppress("UNCHECKED_CAST")
    private val position get() = Player.positionMs as MutableStateFlow<Long>
    private val paris = ZoneId.of("Europe/Paris")
    private val tracks = (0 until 5).map { Track("qa:n$it", "Ocean Drive $it", "Artiste $it", "Album", durationMs = 200_000) }

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
        AppSettings.reduceDynamicBlur.value = true
        ViceCityEdition.zone = { paris }
        ViceCityEdition.clock = { LocalDateTime.parse("2026-11-18T10:00").atZone(paris).toInstant().toEpochMilli() }
    }

    @After fun reset() {
        AppSettings.reduceAnimations.value = false
        AppSettings.reduceDynamicBlur.value = false
        AppSettings.highPerformanceMode.value = false
        AppSettings.viceCityTheme.value = false
        ViceCityEdition.clock = { System.currentTimeMillis() }
        ViceCityEdition.zone = { ZoneId.systemDefault() }
        player.value = PlayerUiState()
        position.value = 0L
        AppUi.closeOverlays(); AppUi.selectTab(AppUi.HOME)
    }

    private fun image(name: String): Bitmap {
        compose.waitForIdle()
        lateinit var out: Bitmap
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/vicecity-night/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out = bitmap
        }
        return out
    }

    @Composable
    private fun NoOverscroll(content: @Composable () -> Unit) =
        CompositionLocalProvider(androidx.compose.foundation.LocalOverscrollFactory provides null, content = content)

    private fun app(tab: Int = AppUi.HOME) {
        AppUi.selectTab(tab)
        compose.setContent { NoOverscroll { AppRoot() } }
        compose.waitForIdle()
    }

    private fun quest() = com.ketvoice.app.BuildConfig.IS_QUEST

    @Test fun homeIsAnEightiesNight() {
        if (quest()) return // Quest keeps its own landscape window, rendered elsewhere.
        AppSettings.viceCityTheme.value = true
        app()
        compose.onNodeWithTag("vicecity-floor").assertExists()
        compose.onNodeWithTag("vicecity-wordmark", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("home-greeting").assertIsDisplayed()
        compose.onNodeWithTag("vicefm-card").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("vicefm-play", useUnmergedTree = true).assertHasClickAction()
        compose.onNodeWithText("Ketvoice n'est pas affilié à Rockstar Games.").assertDoesNotExist()
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("vicefm-card"))
        image("home-phone")
    }

    @Test fun homeWithAPlayingTitleDocksTheMiniPlayerOverTheFloor() {
        if (quest()) return
        AppSettings.viceCityTheme.value = true
        player.value = PlayerUiState(queue = tracks, queueEntryIds = tracks.map { it.id }, index = 0, durationMs = 200_000)
        position.value = 80_000L
        app()
        compose.onNodeWithTag("mini-progress", useUnmergedTree = true).assertExists()
        image("home-playing")
    }

    @Test fun theLibraryAndExplorerWearTheChrome() {
        if (quest()) return
        AppSettings.viceCityTheme.value = true
        app(AppUi.LIBRARY)
        compose.onAllNodesWithText("Bibliothèque").onFirst().assertExists()
        compose.onNodeWithTag("vicecity-floor").assertExists()
        image("library-phone")
        compose.runOnIdle { AppUi.selectTab(AppUi.EXPLORER) }
        compose.waitForIdle()
        compose.onAllNodesWithText("Explorer").onFirst().assertExists()
        image("explorer-phone")
    }

    @Test fun thePerformanceModeKeepsTheFloorStill() {
        if (quest()) return
        AppSettings.viceCityTheme.value = true
        AppSettings.highPerformanceMode.value = true
        app()
        compose.onNodeWithTag("vicecity-floor").assertExists()
        image("home-performance")
    }

    @Test fun withoutTheThemeNothingOfTheNightRemains() {
        if (quest()) return
        AppSettings.viceCityTheme.value = false
        app()
        compose.onNodeWithTag("vicecity-floor").assertDoesNotExist()
        compose.onNodeWithTag("vicecity-wordmark", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("vicefm-card").assertDoesNotExist()
    }

    @Test fun afterTheEditionNothingOfTheNightRemains() {
        if (quest()) return
        AppSettings.viceCityTheme.value = true
        ViceCityEdition.clock = { LocalDateTime.parse("2026-12-11T00:00").atZone(paris).toInstant().toEpochMilli() }
        app()
        compose.onNodeWithTag("vicecity-floor").assertDoesNotExist()
        compose.onNodeWithTag("vicefm-card").assertDoesNotExist()
        compose.onNodeWithTag("vicecity-wordmark", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun thePlayerKeepsItsLayoutAndTakesTheNeon() {
        if (quest()) return
        player.value = PlayerUiState(queue = tracks, queueEntryIds = tracks.map { it.id }, index = 0, durationMs = 200_000)
        position.value = 64_000L
        compose.setContent { NoOverscroll { KetvoiceTheme(viceCity = true) {
            com.ketvoice.app.ui.player.FullPlayerScreen(onDismiss = {})
        } } }
        compose.waitForIdle()
        compose.onNodeWithTag("scrub-elapsed", useUnmergedTree = true).assertExists()
        image("player-phone")
    }

    /** Vice FM with no answer from anywhere: a line says so, and the button stays to try again. */
    @Test fun viceFmSaysWhenNothingAnswers() {
        val silent = object : ViceFm.Sources {
            override suspend fun radio(artist: String) = emptyList<Track>()
            override suspend fun top(artist: String) = emptyList<Track>()
            override suspend fun search(query: String) = emptyList<Track>()
        }
        compose.setContent { KetvoiceTheme(viceCity = true) {
            Box(Modifier.fillMaxSize().background(com.ketvoice.app.theme.ViceCityTheme.Colors.night).padding(top = 40.dp)) {
                com.ketvoice.app.ui.vicecity.ViceFmCard(sources = silent)
            }
        } }
        compose.waitForIdle()
        compose.onNodeWithText("Synthwave et tubes des années 80, pris dans le catalogue.").assertIsDisplayed()
        image("vicefm-card")
        compose.onNodeWithTag("vicefm-play", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("vicefm-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("vicefm-play", useUnmergedTree = true).assertHasClickAction()
        image("vicefm-card-unanswered")
    }

    @Test fun viceFmIsOnlyThereWithTheTheme() {
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.vicecity.ViceFmCard() } }
        compose.waitForIdle()
        compose.onNodeWithTag("vicefm-card").assertDoesNotExist()
    }

    private val held = MusicApi.LyricLine(0, "Néons sur la plage", listOf(
        MusicApi.LyricWord(0, "Néons ", 500), MusicApi.LyricWord(500, "sur ", 800), MusicApi.LyricWord(800, "la ", 1_000),
        MusicApi.LyricWord(1_000, "plage", 3_000)))

    private fun warmth(image: Bitmap): Long {
        var sum = 0L
        for (y in 0 until image.height step 2) for (x in 0 until image.width step 2) {
            val c = image.getPixel(x, y)
            sum += android.graphics.Color.red(c) - android.graphics.Color.green(c)
        }
        return sum
    }

    private val neonLyrics = androidx.compose.runtime.mutableStateOf(true)

    private fun lyric() {
        compose.setContent { KetvoiceTheme(useDark = true, viceCity = neonLyrics.value) {
            Box(Modifier.fillMaxSize().background(Color(0xFF1B1622)).padding(28.dp), contentAlignment = Alignment.Center) {
                androidx.compose.runtime.key(neonLyrics.value) { com.ketvoice.app.ui.player.KaraokeLine(held, Modifier.fillMaxWidth()) }
            }
        } }
    }

    /** The held « plage » glows a little, in neon pink here (white without the theme); the size of the glow is the same. */
    @Test fun aHeldNoteGlowsNeon() {
        AppSettings.reduceAnimations.value = false
        position.value = 1_700L
        lyric()
        compose.mainClock.advanceTimeBy(600)
        val neon = warmth(image("lyrics-held-note"))
        compose.runOnIdle { neonLyrics.value = false }
        compose.mainClock.advanceTimeBy(600)
        val white = warmth(image("lyrics-held-note-without-theme"))
        assertTrue("the glow leans pink: $neon > $white", neon > white)
    }

    @Test fun aSheetAndAToastWearTheNeonEdge() {
        if (quest()) return
        val track = Track("qa:sheet", "Ocean Drive", "Artiste", "Album")
        compose.setContent { NoOverscroll { KetvoiceTheme(viceCity = true) {
            Box(Modifier.fillMaxSize().background(com.ketvoice.app.theme.ViceCityTheme.Colors.night)) {
                com.ketvoice.app.ui.common.KetSnackbar(toast("Ajouté à vos titres", "Annuler"),
                    Modifier.align(Alignment.TopCenter).padding(top = 60.dp))
                com.ketvoice.app.ui.player.PlayerSheetScrim({}) { com.ketvoice.app.ui.player.TrackMenuSheet(track) {} }
            }
        } } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("ket-snackbar").assertIsDisplayed()
        image("sheet-and-toast-phone")
    }

    private fun toast(message: String, action: String) = object : SnackbarData {
        override val visuals = object : SnackbarVisuals {
            override val message = message
            override val actionLabel = action
            override val withDismissAction = false
            override val duration = SnackbarDuration.Short
        }
        override fun performAction() {}
        override fun dismiss() {}
    }

    @Test @Config(qualifiers = "w800dp-h1280dp-mdpi")
    fun onATablet() {
        if (quest()) return
        AppSettings.viceCityTheme.value = true
        player.value = PlayerUiState(queue = tracks, queueEntryIds = tracks.map { it.id }, index = 0, durationMs = 200_000)
        position.value = 80_000L
        app()
        compose.onNodeWithTag("vicefm-card").assertExists()
        image("home-tablet")
        compose.runOnIdle { AppUi.selectTab(AppUi.LIBRARY) }
        image("library-tablet")
        compose.runOnIdle { AppUi.showPlayer.value = true }
        compose.mainClock.advanceTimeBy(1_500)
        image("player-tablet")
        compose.runOnIdle { AppUi.showPlayer.value = false }
    }

    @Test @Config(qualifiers = "w800dp-h1280dp-mdpi")
    fun aSheetOnATablet() {
        if (quest()) return
        val track = Track("qa:sheet", "Ocean Drive", "Artiste", "Album")
        compose.setContent { NoOverscroll { KetvoiceTheme(viceCity = true) {
            Box(Modifier.fillMaxSize().background(com.ketvoice.app.theme.ViceCityTheme.Colors.night)) {
                com.ketvoice.app.ui.player.PlayerSheetScrim({}) { com.ketvoice.app.ui.player.TrackMenuSheet(track) {} }
            }
        } } }
        compose.mainClock.advanceTimeBy(1_000)
        image("sheet-tablet")
    }
}
