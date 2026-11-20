package com.ketvoice.app.perf

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.ketvoice.app.data.AppLanguage
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 7.6 (owner: « Optimise à fond l'app tout en gardant les mêmes choses »): the full player's parts that follow the
 * position, counted with the Compose tracer as in SmoothnessHostTest, with animations on (the glide is what ran
 * composition again at every frame). The counts go to build/perf76/recompositions.txt for the report.
 */
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PerfRecompositionHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST") private val player get() = Player.state as MutableStateFlow<PlayerUiState>
    @Suppress("UNCHECKED_CAST") private val position get() = Player.positionMs as MutableStateFlow<Long>
    @Suppress("UNCHECKED_CAST") private val lyrics get() = LyricsRepo.state as MutableStateFlow<LyricsRepo.State>

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
        AppSettings.reduceAnimations.value = false
        AppSettings.systemReduceMotion.value = false
        Composer.setTracer(Runs)
        Runs.on = true
    }

    @After fun reset() {
        Runs.on = false; Runs.counts.clear()
        player.value = PlayerUiState(); position.value = 0L; lyrics.value = LyricsRepo.State()
    }

    private val song = Track("perf:1", "Titre", "Artiste", "Album", durationMs = 200_000)

    /** [ticks] of the player's 200 ms position steps, the clock running frame by frame between them. */
    private fun play(ticks: Int) {
        repeat(ticks) {
            position.value = position.value + 200
            compose.mainClock.advanceTimeBy(200)
        }
        compose.waitForIdle()
    }

    private fun note(name: String, runs: Int) {
        File("build/perf76").apply { mkdirs() }.resolve("recompositions.txt").appendText("$name: $runs\n")
        println("perf76 $name: $runs")
    }

    @Test fun scrubBarGlidesWithoutComposingAtEveryFrame() {
        player.value = PlayerUiState(queue = listOf(song), index = 0, isPlaying = true, playWhenReady = true, durationMs = 200_000)
        position.value = 30_000
        compose.mainClock.autoAdvance = false
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.player.ScrubBar(200_000, onSeek = {}) } }
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        Runs.counts.clear()
        play(25) // five seconds
        val bar = Runs.of("ScrubBar"); val times = Runs.of("ScrubTimes")
        note("ScrubBar runs in 5 s of playback", bar); note("ScrubTimes runs in 5 s of playback", times)
        assertTrue("the bar follows the position in the draw phase ($bar)", bar <= 2)
        assertTrue("the times change once a second ($times)", times in 1..12)
        compose.onNodeWithTag("scrub-elapsed").assertExists()
    }

    /** The whole full player for five seconds of playback, artwork page then lyrics page: what composes again. */
    private fun fullPlayerRuns(page: com.ketvoice.app.ui.player.PlayerPage): Map<String, Int> {
        player.value = PlayerUiState(queue = listOf(song), index = 0, isPlaying = true, playWhenReady = true, durationMs = 200_000)
        lyrics.value = LyricsRepo.State(lines = (0 until 40).map { MusicApi.LyricLine(it * 10_000L, "Ligne $it") }, trackId = song.id)
        position.value = 1_000
        compose.mainClock.autoAdvance = false
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.player.FullPlayerScreen(initialPage = page) } }
        compose.mainClock.advanceTimeBy(2_000); compose.waitForIdle()
        Runs.counts.clear()
        play(25)
        val runs = Runs.counts.toMap()
        note("full player ($page) composable runs in 5 s: total", runs.values.sum())
        runs.entries.sortedByDescending { it.value }.take(12).forEach { note("  $page ${it.key}", it.value) }
        return runs
    }

    @Test fun fullPlayerArtworkPageHoldsStillWhileATitlePlays() {
        val runs = fullPlayerRuns(com.ketvoice.app.ui.player.PlayerPage.ARTWORK)
        assertTrue("the full player is not composed again at every frame (${runs.values.sum()})", runs.values.sum() < LIMIT_5S)
    }

    @Test fun fullPlayerLyricsPageHoldsStillWhileATitlePlays() {
        val runs = fullPlayerRuns(com.ketvoice.app.ui.player.PlayerPage.LYRICS)
        assertTrue("the lyrics page is not composed again at every frame (${runs.values.sum()})", runs.values.sum() < LIMIT_5S)
    }

    /** The whole app (tab bar and mini-player included) on one tab for five seconds of playback, animations on. */
    private fun appRuns(tab: Int, name: String): Map<String, Int> {
        val tracks = (1..12).map { Track("perf:$it", "Titre $it", "Artiste ${it % 4}", "Album ${it % 5}", durationMs = 200_000) }
        com.ketvoice.app.data.ListeningHistory.tracks.let { (it as MutableStateFlow<List<Track>>).value = tracks }
        player.value = PlayerUiState(queue = tracks, index = 0, isPlaying = true, playWhenReady = true, durationMs = 200_000)
        position.value = 1_000
        com.ketvoice.app.ui.AppUi.selectTab(tab)
        compose.mainClock.autoAdvance = false
        compose.setContent { com.ketvoice.app.ui.AppRoot() }
        compose.mainClock.advanceTimeBy(3_000); compose.waitForIdle()
        Runs.counts.clear()
        play(25)
        val runs = Runs.counts.toMap()
        note("app on $name, composable runs in 5 s: total", runs.values.sum())
        runs.entries.sortedByDescending { it.value }.take(10).forEach { note("  $name ${it.key}", it.value) }
        com.ketvoice.app.ui.AppUi.closeOverlays(); com.ketvoice.app.ui.AppUi.selectTab(com.ketvoice.app.ui.AppUi.HOME)
        (com.ketvoice.app.data.ListeningHistory.tracks as MutableStateFlow<List<Track>>).value = emptyList()
        return runs
    }

    @Test fun appHoldsStillOnEachTabWhileATitlePlays() {
        val ui = com.ketvoice.app.ui.AppUi
        val runs = appRuns(ui.HOME, "home")
        val total = runs.values.sum()
        assertTrue("Home with the mini-player and the tab bar ($total)", total < LIMIT_5S)
        // Until 7.5 the mini-player's progress line was composed again at each 200 ms tick, on every page.
        assertTrue("the mini-player's line follows the position while drawing ($runs)", (runs["MiniProgressLine"] ?: 0) == 0)
    }

    @Test fun appHoldsStillOnLibraryWhileATitlePlays() {
        val total = appRuns(com.ketvoice.app.ui.AppUi.LIBRARY, "library").values.sum()
        assertTrue("Library with the mini-player ($total)", total < LIMIT_5S)
    }

    @Test fun appHoldsStillOnSearchWhileATitlePlays() {
        val total = appRuns(com.ketvoice.app.ui.AppUi.SEARCH, "search").values.sum()
        assertTrue("Search with the mini-player ($total)", total < LIMIT_5S)
    }

    @Test fun appHoldsStillOnDownloadsWhileATitlePlays() {
        val total = appRuns(com.ketvoice.app.ui.AppUi.DOWNLOADS, "downloads").values.sum()
        assertTrue("Downloads with the mini-player ($total)", total < LIMIT_5S)
    }

    private companion object { const val LIMIT_5S = 100_000 }

    @Test fun lyricsPreviewLineComposesOnlyWhenTheLineChanges() {
        player.value = PlayerUiState(queue = listOf(song), index = 0, isPlaying = true, playWhenReady = true, durationMs = 200_000)
        lyrics.value = LyricsRepo.State(lines = listOf(MusicApi.LyricLine(0, "Première ligne"), MusicApi.LyricLine(60_000, "Deuxième ligne")),
            trackId = song.id)
        position.value = 1_000
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.player.LyricsPreviewLine(onOpen = {}) } }
        compose.waitForIdle()
        Runs.counts.clear()
        play(25)
        val runs = Runs.of("LyricsPreviewLine")
        note("LyricsPreviewLine runs in 5 s inside one line", runs)
        assertTrue("one line held for five seconds composes nothing again ($runs)", runs == 0)
    }
}
