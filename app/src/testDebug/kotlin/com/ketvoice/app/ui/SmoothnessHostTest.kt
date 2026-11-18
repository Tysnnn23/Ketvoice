package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppLanguage
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.library.coverGridRows
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

@androidx.compose.runtime.Composable
private fun FollowsEveryFrame() {
    val t = rememberInfiniteTransition(label = "yardstick").animateFloat(0f, 1f,
        infiniteRepeatable(tween(500)), label = "t").value
    Box(Modifier.size((10 + t * 10).dp))
}

/**
 * 6.6 (owner: « elle continue de bugger cruellement sur la page d'accueil, les chargements, Wrapped, bibliothèque »):
 * what the pages do while nothing on them changes. Counted on the host with the Compose tracer — how often a
 * composable runs again and how often a layer is drawn — never frame times, which only a phone can measure.
 */
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmoothnessHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST") private val player get() = Player.state as MutableStateFlow<PlayerUiState>
    @Suppress("UNCHECKED_CAST") private val position get() = Player.positionMs as MutableStateFlow<Long>
    private val recent get() = ListeningHistory.tracks as MutableStateFlow<List<Track>>
    private val known get() = ListeningHistory.knownTracks as MutableStateFlow<List<Track>>

    /** How many times each composable ran (its body, not a skip), by its simple name. */
    private object Runs : CompositionTracer {
        @Volatile var on = false
        val counts = ConcurrentHashMap<String, Int>()
        override fun isTraceInProgress() = on
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            counts.merge(info.substringBefore(' ').substringAfterLast('.'), 1, Int::plus)
        }
        override fun traceEventEnd() {}
        fun of(name: String) = counts[name] ?: 0
    }

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
        Composer.setTracer(Runs)
        Runs.on = true
    }

    @After fun reset() {
        Runs.on = false; Runs.counts.clear()
        AppSettings.reduceAnimations.value = false
        player.value = PlayerUiState(); position.value = 0L
        recent.value = emptyList(); known.value = emptyList()
        ListeningHistory.resetListenedMs()
        AppUi.closeOverlays(); AppUi.selectTab(AppUi.HOME)
    }

    private fun track(i: Int) = Track("smooth:$i", "Titre $i", "Artiste ${i % 4}", "Album ${i % 7}", durationMs = 200_000)

    private fun image(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png").apply {
                parentFile?.mkdirs()
                outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
    }

    /** Five seconds of the player's 200 ms ticks: the position moves and listening time is counted. */
    private fun play(ticks: Int) {
        repeat(ticks) {
            position.value = position.value + 200
            ListeningHistory.addListenedMs(200)
            compose.mainClock.advanceTimeBy(200)
        }
        compose.waitForIdle()
    }

    @Test fun homeHoldsStillWhileATitlePlays() {
        val tracks = (1..12).map(::track)
        recent.value = tracks; known.value = tracks
        player.value = PlayerUiState(queue = tracks, index = 0, isPlaying = true, playWhenReady = true, durationMs = 200_000)
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.home.HomeScreen() } }
        compose.waitForIdle()
        compose.onNodeWithTag("home-recent-carousel").assertExists()
        assertTrue("the tracer sees Home compose (${Runs.counts.keys.take(20)})", Runs.of("HomeScreen") >= 1 && Runs.of("RecentCover") >= 1)
        Runs.counts.clear()
        // 4.8 s of playback: until 6.5, the week and Replay cards were worked out again at each tick.
        play(24)
        assertEquals("Home itself never follows the position (${Runs.counts})", 0, Runs.of("HomeScreen"))
        assertEquals(0, Runs.of("HomeWeekCard"))
        assertEquals(0, Runs.of("HomeReplayLink"))
        assertEquals(0, Runs.of("RecentCover"))
        // A state change Home does not show (buffering, quality) leaves it alone too.
        player.value = player.value.copy(buffering = true, quality = "FLAC")
        compose.waitForIdle()
        assertEquals(0, Runs.of("HomeScreen"))
        image("smooth-home-playing")
    }

    /** The yardstick: an animation read in composition runs its composable again at every frame, and this counts it. */
    @Test fun theCountSeesAComposableThatFollowsAnAnimation() {
        compose.mainClock.autoAdvance = false
        compose.setContent { FollowsEveryFrame() }
        compose.mainClock.advanceTimeByFrame()
        Runs.counts.clear()
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue("about 60 frames in a second (${Runs.of("FollowsEveryFrame")})", Runs.of("FollowsEveryFrame") > 30)
    }

    @Test fun nowPlayingBarsMoveWithoutComposingAgain() {
        AppSettings.reduceAnimations.value = false
        compose.mainClock.autoAdvance = false
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.player.NowPlayingBars(true) } }
        compose.mainClock.advanceTimeByFrame()
        Runs.counts.clear()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("the bars are drawn from the animation, not composed from it", 0, Runs.of("NowPlayingBars"))
        assertEquals(0, Runs.of("NowPlayingBarsCanvas"))
    }

    @Test fun indeterminateProgressGlidesWithoutComposingAgain() {
        AppSettings.reduceAnimations.value = false
        compose.mainClock.autoAdvance = false
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.common.KetProgressBar(null) } }
        compose.mainClock.advanceTimeByFrame()
        Runs.counts.clear()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(0, Runs.of("KetProgressBar"))
    }

    @Test fun wrappedStoryDoesNotComposeAgainWhilePagesRunOut() {
        AppSettings.reduceAnimations.value = false
        val tracks = (1..6).map(::track)
        known.value = tracks
        compose.mainClock.autoAdvance = false
        val recap = com.ketvoice.app.ui.sub.wrappedRecapNow()
        compose.setContent { KetvoiceTheme(useDark = true) {
            com.ketvoice.app.ui.sub.WrappedStoryContent(recap, onClose = {}, startPage = 0, autoAdvance = true)
        } }
        compose.mainClock.advanceTimeBy(1_000)
        Runs.counts.clear()
        // A second of the running story bar: until 6.5 the whole story ran again at every frame of it.
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(0, Runs.of("WrappedStoryContent"))
        assertEquals(0, Runs.of("WrappedPageBody"))
    }

    @Test fun libraryAlbumGridComposesOnlyTheLinesOnScreen() {
        // 80 albums: one item per line of the grid, the far ones composed only once scrolled to.
        val tracks = (1..80).map { Track("grid:$it", "Titre $it", "Artiste $it", "Album $it", durationMs = 200_000) }
        recent.value = tracks
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.library.LibraryScreen() } }
        compose.waitForIdle()
        compose.onNode(hasText("Albums") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("library-albums").assertExists()
        val cells = (1..80).count { n ->
            compose.onAllNodes(hasText("Album $n") and hasAnyAncestor(hasTestTag("library-list")))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("only the lines on screen are composed ($cells of 80)", cells in 2..20)
        image("smooth-library-albums")
    }

    @Test fun coverGridLinesHaveStableKeysAndTypes() {
        val rows = (1..9).map { "album-$it" }
        val state = androidx.compose.foundation.lazy.LazyListState()
        compose.setContent { KetvoiceTheme(useDark = true) {
            LazyColumn(Modifier.fillMaxSize(), state = state) {
                coverGridRows("albums", rows, columns = 2, side = 120.dp, tag = "grid", key = { it }) { item, _ ->
                    com.ketvoice.app.ui.common.Text(item, localize = false)
                }
            }
        } }
        compose.waitForIdle()
        compose.runOnIdle {
            val keys = state.layoutInfo.visibleItemsInfo.map { it.key }
            assertEquals(listOf("albums:album-1", "albums:album-3", "albums:album-5", "albums:album-7", "albums:album-9"), keys)
            assertTrue(state.layoutInfo.visibleItemsInfo.all { it.contentType == "albums" })
        }
    }
}
