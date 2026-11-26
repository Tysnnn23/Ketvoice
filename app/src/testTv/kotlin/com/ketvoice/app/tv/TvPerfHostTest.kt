package com.ketvoice.app.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.ketvoice.app.data.AppLanguage
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
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
import java.util.concurrent.ConcurrentHashMap

/**
 * What composes again on the TV while the remote walks a row and while a title plays (the position ticks), and how
 * long a first composition and a software draw of the 1080p window take on the host. Numbers go to
 * build/tv-perf/ui.txt; the bounds only catch a page that recomposes at every key or every tick.
 */
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvPerfHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST") private val recent get() = ListeningHistory.tracks as MutableStateFlow<List<Track>>
    @Suppress("UNCHECKED_CAST") private val known get() = ListeningHistory.knownTracks as MutableStateFlow<List<Track>>
    @Suppress("UNCHECKED_CAST") private val player get() = Player.state as MutableStateFlow<PlayerUiState>
    @Suppress("UNCHECKED_CAST") private val position get() = Player.positionMs as MutableStateFlow<Long>

    private object Runs : CompositionTracer {
        @Volatile var on = false
        val counts = ConcurrentHashMap<String, Int>()
        override fun isTraceInProgress() = on
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            if (info.startsWith("com.ketvoice")) counts.merge(info.substringBefore(' ').substringAfterLast('.'), 1, Int::plus)
        }
        override fun traceEventEnd() {}
    }

    private val report = StringBuilder()
    private val actions = TvActions(TvNavigator())

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.animatedArtwork.value = false
        val tracks = (0 until 24).map { Track("qa:perf$it", "Titre $it", "Artiste ${it % 5}", "Album ${it % 7}", durationMs = 200_000) }
        recent.value = tracks
        known.value = tracks
        Composer.setTracer(Runs)
        Runs.on = true
    }

    @After fun reset() {
        Runs.on = false
        recent.value = emptyList(); known.value = emptyList()
        player.value = PlayerUiState(); position.value = 0L
        File("build/tv-perf").mkdirs()
        File("build/tv-perf/ui.txt").appendText(report.toString())
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun show(tier: TvTier) {
        compose.setContent {
            val modes = LocalInputModeManager.current
            LaunchedEffect(Unit) { modes.requestInputMode(InputMode.Keyboard) }
            TvApp(actions, tier, onExit = {})
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }

    private fun drawMs(): Double {
        var ms = 0.0
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas) // warm
            val start = System.nanoTime()
            repeat(3) { view.draw(canvas) }
            ms = (System.nanoTime() - start) / 3e6
            bitmap.recycle()
        }
        return ms
    }

    @Test fun walkingARowRecomposesOnlyTheCardsThatGainAndLoseTheFocus() {
        Runs.counts.clear()
        val started = System.nanoTime()
        show(TvTier.Light)
        val firstMs = (System.nanoTime() - started) / 1e6
        val firstHome = Runs.counts["TvHomePage"] ?: 0
        press(Key.DirectionDown)
        Runs.counts.clear()
        repeat(6) { press(Key.DirectionRight) }
        val home = Runs.counts["TvHomePage"] ?: 0
        val rows = Runs.counts["TvShelfRow"] ?: 0
        val cards = Runs.counts["TvItemCard"] ?: 0
        val draw = drawMs()
        report.appendLine("home first composition (host, Light tier, incl. 1.5 s of settled clock): %.0f ms wall, TvHomePage composed %d×".format(firstMs, firstHome))
        report.appendLine("6 × Right along a row: TvHomePage %d, TvShelfRow %d, TvItemCard %d recompositions; software draw of 1920×1080 %.1f ms"
            .format(home, rows, cards, draw))
        assertEquals("Home never recomposes while the focus moves", 0, home)
        assertTrue("a row does not recompose at each key ($rows)", rows <= 6)
        // Cards entering the row as it scrolls compose once; a focus change touches only the two cards concerned.
        assertTrue("cards recompose only around the focus ($cards)", cards <= 6 * 4)
    }

    @Test fun positionTicksRecomposeOnlyTheTimes() {
        val tracks = recent.value
        player.value = PlayerUiState(queue = tracks, index = 0, isPlaying = true, playWhenReady = true, durationMs = 200_000)
        actions.nav.open(TvRoute.NowPlaying)
        show(TvTier.Balanced)
        Runs.counts.clear()
        repeat(20) { i ->
            compose.runOnIdle { position.value = 10_000L + i * 1_000L }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
        }
        val page = Runs.counts["TvNowPlayingPage"] ?: 0
        val times = Runs.counts["TimeText"] ?: 0
        val draw = drawMs()
        report.appendLine("20 position ticks on Now Playing: TvNowPlayingPage %d, TimeText %d recompositions; software draw %.1f ms"
            .format(page, times, draw))
        // It composes again only when its controls step back after 8 s without a key (once), never with the position.
        assertTrue("Now Playing does not follow the position ($page)", page <= 2)
        assertTrue("only the two times follow the ticks ($times)", times <= 2 * 20 + 4)
    }
}
