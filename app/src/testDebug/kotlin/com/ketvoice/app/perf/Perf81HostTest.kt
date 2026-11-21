package com.ketvoice.app.perf

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import com.ketvoice.app.data.AppLanguage
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.AppUi
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
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 8.1 (owner: « optimise les performances … c'est catastrophique »): what composes again, and how long a software draw
 * of the window takes, while Home scrolls (the tab bar folds), while the title changes, and while the full player
 * morphs into its lyrics header — the 8.0 effects (glass, morphs, springs, animated symbols) all on. Numbers go to
 * build/perf81/ui.txt for the report; the bounds only catch a return to composing at every frame.
 */
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Perf81HostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST") private val player get() = Player.state as MutableStateFlow<PlayerUiState>
    @Suppress("UNCHECKED_CAST") private val position get() = Player.positionMs as MutableStateFlow<Long>
    @Suppress("UNCHECKED_CAST") private val lyrics get() = LyricsRepo.state as MutableStateFlow<LyricsRepo.State>
    @Suppress("UNCHECKED_CAST") private val recent get() = ListeningHistory.tracks as MutableStateFlow<List<Track>>
    @Suppress("UNCHECKED_CAST") private val known get() = ListeningHistory.knownTracks as MutableStateFlow<List<Track>>

    private object Runs : CompositionTracer {
        @Volatile var on = false
        val counts = ConcurrentHashMap<String, Int>()
        /** The app's own composables, with their file and line (lambdas apart). */
        val ours = ConcurrentHashMap<String, Int>()
        override fun isTraceInProgress() = on
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            counts.merge(info.substringBefore(' ').substringAfterLast('.'), 1, Int::plus)
            if (info.startsWith("com.ketvoice")) ours.merge(info.substringBefore(' ').substringAfterLast('.') + " " +
                info.substringAfter('(', "").substringBefore(')'), 1, Int::plus)
        }
        fun clear() { counts.clear(); ours.clear() }
        override fun traceEventEnd() {}
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
        Runs.on = false; Runs.clear()
        AppUi.closeOverlays(); AppUi.showPlayer.value = false; AppUi.showLyrics.value = false; AppUi.selectTab(AppUi.HOME)
        player.value = PlayerUiState(); position.value = 0L; lyrics.value = LyricsRepo.State()
        recent.value = emptyList(); known.value = emptyList()
    }

    private val tracks = (1..40).map { Track("perf81:$it", "Titre $it", "Artiste ${it % 6}", "Album ${it % 9}", durationMs = 200_000) }

    private fun note(line: String) {
        File("build/perf81").apply { mkdirs() }.resolve("ui.txt").appendText(line + "\n")
        println("perf81 $line")
    }

    private fun report(name: String, frames: Int): Int {
        val runs = Runs.counts.toMap()
        val total = runs.values.sum()
        note("$name: $total composable runs over $frames frames (${"%.1f".format(total.toFloat() / frames.coerceAtLeast(1))}/frame), " +
            "$composedFrames frames composed")
        lastComposedFrames = composedFrames; composedFrames = 0
        val ours = Runs.ours.toMap()
        note("  app composables: ${ours.values.sum()}")
        ours.entries.sortedByDescending { it.value }.take(30).forEach { note("    ${it.key} ${it.value}") }
        Runs.clear()
        return total
    }

    /** Frames in which anything composed, since the last report. */
    private var composedFrames = 0
    private var lastComposedFrames = 0

    /** [frames] frames of 16 ms, the position ticking every 200 ms as the player does. */
    private fun frames(frames: Int) {
        repeat(frames) { i ->
            if (i % 12 == 0) position.value = position.value + 200
            val before = Runs.counts.values.sum()
            compose.mainClock.advanceTimeBy(16)
            if (Runs.counts.values.sum() != before) composedFrames++
        }
        compose.waitForIdle()
    }

    /** Mean wall time of a software draw of the whole window (glass draws flat on the host). */
    private fun drawMs(name: String, times: Int = 20): Double {
        var ms = 0.0
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            repeat(3) { view.draw(canvas) }
            val start = System.nanoTime()
            repeat(times) { view.draw(canvas) }
            ms = (System.nanoTime() - start) / 1e6 / times
            File("build/perf81/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        note("$name: software draw ${"%.2f".format(ms)} ms")
        return ms
    }

    private fun app() {
        recent.value = tracks; known.value = tracks
        player.value = PlayerUiState(queue = tracks, index = 0, isPlaying = true, playWhenReady = true, durationMs = 200_000)
        position.value = 1_000
        AppUi.selectTab(AppUi.HOME)
        compose.mainClock.autoAdvance = false
        compose.setContent { com.ketvoice.app.ui.AppRoot() }
        compose.mainClock.advanceTimeBy(3_000); compose.waitForIdle()
    }

    @Test fun homeScrollFoldsTheBarWithoutComposingAtEveryFrame() {
        app()
        drawMs("home-rest")
        Runs.clear()
        compose.onNodeWithTag("home-list").performTouchInput { swipeUp(durationMillis = 400) }
        frames(90)
        val folded = Runs.counts.toMap()
        val foldedFrames = composedFrames
        // The bar folded: what it looks like (the draw does not compose, so the counts stay as they are).
        drawMs("home-folded", times = 1)
        compose.onNodeWithTag("home-list").performTouchInput { swipeDown(durationMillis = 400) }
        frames(90)
        note("home scroll down (fold): ${folded.values.sum()} runs, $foldedFrames frames composed")
        val total = report("home scroll down and up, bar folding and unfolding (180 frames + 2 swipes)", 180 + 50)
        drawMs("home-scrolled")
        // 8.0 composed the whole chrome, and AppRoot, at each frame of the fold: 15 934 runs here (8.1: about 1 100).
        assertTrue("scroll and fold ($total)", total < 5_000)
    }

    @Test fun homeTrackChange() {
        app()
        Runs.clear()
        player.value = player.value.copy(index = 1)
        frames(60)
        val total = report("home, title change (60 frames)", 60)
        assertTrue(total < 200_000)
    }

    @Test fun playerOpensAndClosesFromTheMiniPlayer() {
        app()
        Runs.clear()
        AppUi.showPlayer.value = true
        frames(60)
        report("app, full player opening from the mini-player (60 frames)", 60)
        // 8.0: 28 of 60 frames composed while the player grew out of the mini-player (8.1: 5).
        assertTrue("opening ($lastComposedFrames frames composed)", lastComposedFrames <= 12)
        AppUi.showPlayer.value = false
        frames(60)
        report("app, full player closing into the mini-player (60 frames)", 60)
        assertTrue("closing ($lastComposedFrames frames composed)", lastComposedFrames <= 12)
    }

    @Test fun homeIdlePlayback() {
        app()
        Runs.clear()
        frames(300)
        report("home, 5 s of playback (300 frames)", 300)
    }

    private fun fullPlayer() {
        player.value = PlayerUiState(queue = tracks, index = 0, isPlaying = true, playWhenReady = true, durationMs = 200_000)
        lyrics.value = LyricsRepo.State(lines = (0 until 40).map { MusicApi.LyricLine(it * 4_000L, "Ligne $it") }, trackId = tracks[0].id)
        position.value = 1_000
        compose.mainClock.autoAdvance = false
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.player.FullPlayerScreen() } }
        compose.mainClock.advanceTimeBy(2_000); compose.waitForIdle()
    }

    @Test fun fullPlayerTrackChangeAndMorph() {
        fullPlayer()
        drawMs("player-artwork")
        Runs.clear()
        frames(300)
        report("full player artwork, 5 s of playback (300 frames)", 300)
        Runs.clear()
        player.value = player.value.copy(index = 1)
        lyrics.value = lyrics.value.copy(trackId = tracks[1].id)
        frames(60)
        report("full player, title change (60 frames)", 60)
        Runs.clear()
        compose.onAllNodesWithContentDescription("Paroles")[0].performClick()
        frames(60)
        report("full player, morph to lyrics (60 frames)", 60)
        drawMs("player-lyrics")
        Runs.clear()
        frames(300)
        report("full player lyrics, 5 s of playback (300 frames)", 300)
    }
}
