package com.ketvoice.app.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.LiveStats
import com.ketvoice.app.data.MonthStat
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.Wrapped
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
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
 * Ketvoice TV 1.1.0's system screens rendered at 1920 × 1080 and driven with the remote: the art frame, the
 * screensaver (playing and gallery), the Replay slides, the entries on Home and in Settings. Pictures under
 * app/build/host-ui-preview/tv/ (« 2x-… »).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvSystemHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Suppress("UNCHECKED_CAST") private val recent get() = ListeningHistory.tracks as MutableStateFlow<List<Track>>
    @Suppress("UNCHECKED_CAST") private val known get() = ListeningHistory.knownTracks as MutableStateFlow<List<Track>>
    @Suppress("UNCHECKED_CAST") private val counts get() = ListeningHistory.playCounts as MutableStateFlow<Map<String, Int>>
    @Suppress("UNCHECKED_CAST") private val months get() = ListeningHistory.months as MutableStateFlow<Map<String, MonthStat>>
    @Suppress("UNCHECKED_CAST") private val player get() = Player.state as MutableStateFlow<PlayerUiState>

    private lateinit var tracks: List<Track>
    private lateinit var actions: TvActions
    private val month = java.time.YearMonth.now().toString()

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = false
        AppSettings.animatedArtwork.value = false
        AppSettings.wrappedSound.value = false
        val dir = File(compose.activity.cacheDir, "tv-covers").apply { mkdirs() }
        val names = listOf("Lumières d'été" to "Nova Lane", "Minuit" to "Kael", "Océan" to "Sora", "Néons" to "Nova Lane",
            "Après la pluie" to "Iris", "Vertige" to "Kael", "Sable" to "Mira", "Polaroid" to "Sora", "Encore" to "Iris")
        tracks = names.mapIndexed { i, (title, artist) ->
            Track("qa:tv$i", title, artist, "Album $title", durationMs = 200_000L + i * 7_000L, coverUrl = cover(dir, i))
        }
        recent.value = tracks
        known.value = tracks
        counts.value = tracks.withIndex().associate { (i, t) -> t.id to 12 - i }
        AppSettings.favoriteIds.value = tracks.take(5).map { it.id }.toSet()
        months.value = mapOf(month to MonthStat(ms = 1_234L * 60_000L, plays = counts.value,
            hours = List(24) { h -> if (h in 18..23) (h - 16) * 600_000L else if (h in 7..9) 300_000L else 0L },
            tracks = tracks.associateBy { it.id }))
        actions = TvActions(TvNavigator())
    }

    @After fun reset() {
        recent.value = emptyList(); known.value = emptyList(); counts.value = emptyMap(); months.value = emptyMap()
        player.value = PlayerUiState()
        AppSettings.favoriteIds.value = emptySet()
        TvReplayPlan.dataForTest = null
    }

    private fun cover(dir: File, i: Int): String {
        val file = File(dir, "cover$i.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val hues = floatArrayOf(i * 41f % 360f, (i * 41f + 70f) % 360f)
            canvas.drawRect(0f, 0f, 300f, 300f, Paint().apply {
                shader = LinearGradient(0f, 0f, 300f, 300f, android.graphics.Color.HSVToColor(floatArrayOf(hues[0], .7f, .85f)),
                    android.graphics.Color.HSVToColor(floatArrayOf(hues[1], .8f, .35f)), Shader.TileMode.CLAMP)
            })
            canvas.drawCircle(150f, 150f, 90f, Paint().apply { color = android.graphics.Color.argb(90, 255, 255, 255) })
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return "file://" + file.absolutePath
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun RemoteMode() {
        val modes = LocalInputModeManager.current
        LaunchedEffect(Unit) { modes.requestInputMode(InputMode.Keyboard) }
    }

    private fun show(tier: TvTier = TvTier.Balanced) {
        compose.setContent { RemoteMode(); TvApp(actions, tier, onExit = {}) }
        settle()
    }

    private fun settle(ms: Long = 1_200) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
        Thread.sleep(200)
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun press(key: Key, times: Int = 1) {
        repeat(times) {
            compose.onRoot().performKeyInput { pressKey(key) }
            compose.mainClock.advanceTimeBy(250)
            compose.waitForIdle()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun waitFor(tag: String) {
        compose.waitUntil(8_000) {
            compose.mainClock.advanceTimeBy(100)
            runCatching { compose.onNodeWithTag(tag).fetchSemanticsNode() }.isSuccess
        }
    }

    private fun focusedTag(): String? = runCatching {
        compose.onNode(isFocused()).fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)
    }.getOrNull()

    private fun image(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            assertEquals("rendered at 1080p", 1920, view.width)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/tv/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun keepsScreenOn(view: android.view.View): Boolean = view.keepScreenOn ||
        (view as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).any { keepsScreenOn(g.getChildAt(it)) } } == true

    @Test fun artFrameShowsTheCoversAndTheRemoteStepsAndPlays() {
        actions.nav.open(TvRoute.ArtFrame)
        show()
        settle(3_000)
        compose.onNodeWithTag("tv-art-frame").assertExists()
        compose.onNodeWithTag("art-frame-picture").assertExists()
        assertTrue("the frame keeps the screen on", keepsScreenOn(compose.activity.window.decorView))
        image("20-art-frame")
        press(Key.DirectionRight)
        settle(3_000)
        image("21-art-frame-next")
        // OK with nothing loaded: the frame's own albums play, without leaving the frame.
        press(Key.DirectionCenter)
        settle(300)
        assertEquals(TvRoute.ArtFrame, actions.nav.current)
        // The hint fades away; the gallery moves on by itself after 20 s.
        compose.mainClock.advanceTimeBy(21_000)
        settle()
        image("22-art-frame-later")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle(600)
        assertEquals(TvRoute.Home, actions.nav.current)
    }

    @Test fun lightTvsShowTheArtFrameToo() {
        actions.nav.open(TvRoute.ArtFrame)
        show(TvTier.Light)
        settle(2_000)
        compose.onNodeWithTag("art-frame-picture").assertExists()
        image("23-art-frame-light")
    }

    private var clock = 1_764_270_000_000L

    private fun dream(tier: TvTier = TvTier.Balanced) {
        compose.setContent {
            KetvoiceTheme(useDark = true) { CompositionLocalProvider(LocalTvTier provides tier) { TvDreamScreen(now = { clock }) } }
        }
        settle(3_000)
    }

    private fun blockLeft(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.left

    @Test fun theScreensaverShowsTheTitlePlayingWithTheTime() {
        player.value = PlayerUiState(queue = tracks, index = 0, isPlaying = true, playWhenReady = true, durationMs = tracks[0].durationMs)
        dream()
        compose.onNodeWithTag("dream-playing").assertExists()
        image("24-screensaver-playing")
        val before = blockLeft("dream-playing")
        // The minute turns: the block glides elsewhere on the screen, nothing else wakes it.
        clock += 60_000L
        compose.mainClock.advanceTimeBy(61_000)
        settle(5_000)
        assertTrue("the block drifted", kotlin.math.abs(blockLeft("dream-playing") - before) > 1f)
        image("25-screensaver-playing-next-minute")
        // Playback paused: the gallery comes back.
        player.value = player.value.copy(playWhenReady = false, isPlaying = false)
        settle(3_000)
        compose.onNodeWithTag("dream-clock").assertExists()
    }

    @Test fun theScreensaverShowsTheGalleryWhenNothingPlays() {
        dream()
        compose.onNodeWithTag("dream-clock").assertExists()
        compose.onNodeWithTag("art-frame-picture").assertExists()
        image("26-screensaver-gallery")
    }

    private fun replayData(): TvReplayData {
        val recap = Wrapped.month(month, months.value, tracks)
        return TvReplayData(recap, genres = listOf(LiveStats.GenreRow("Pop", 30, 46), LiveStats.GenreRow("R&B", 20, 31),
            LiveStats.GenreRow("Électro", 10, 15), LiveStats.GenreRow("Rap", 5, 8)))
    }

    @Test fun replaySlidesWalkWithTheRemote() {
        TvReplayPlan.dataForTest = { replayData() }
        // The slides' own clock drives them here: the test's clock moves only when told to.
        compose.mainClock.autoAdvance = false
        actions.nav.open(TvRoute.Replay(month))
        show()
        compose.onNodeWithTag("tv-replay").assertExists()
        waitFor("replay-title")
        settle(2_000)
        image("27-replay-intro")
        val names = listOf("28-replay-minutes", "29-replay-artists", "30-replay-titles", "31-replay-genres", "32-replay-hours", "33-replay-summary")
        names.forEach { name ->
            press(Key.DirectionRight)
            settle(900)
            image(name)
        }
        compose.onNodeWithTag("replay-hint").assertExists()
        // Back a slide, then OK holds it: it no longer moves on by itself.
        press(Key.DirectionLeft)
        press(Key.DirectionCenter)
        compose.onNodeWithTag("replay-held").assertExists()
        compose.mainClock.advanceTimeBy(TvReplayPlan.SLIDE_MS + 1_000)
        compose.waitForIdle()
        compose.onNodeWithTag("replay-held").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle(600)
        assertEquals(TvRoute.Home, actions.nav.current)
    }

    @Test fun replaySlidesMoveOnByThemselves() {
        TvReplayPlan.dataForTest = { replayData() }
        compose.mainClock.autoAdvance = false
        actions.nav.open(TvRoute.Replay(month))
        show(TvTier.Light)
        waitFor("replay-title")
        compose.mainClock.advanceTimeBy(TvReplayPlan.SLIDE_MS + 500)
        settle()
        compose.onNodeWithTag("replay-minutes").assertExists()
        image("34-replay-minutes-light")
    }

    @Test fun homeLeadsToReplayWrappedAndTheArtFrame() {
        show()
        // Down from the poster's button (or the first row) to the third row, where the extras sit.
        repeat(5) { if (!focusedTag().orEmpty().startsWith("card-replay:")) { press(Key.DirectionDown); settle(500) } }
        settle(2_000)
        compose.onNodeWithTag("row-${TvExtras.SHELF}").assertExists()
        assertTrue(focusedTag().orEmpty(), focusedTag().orEmpty().startsWith("card-replay:"))
        image("35-home-extras-row")
        press(Key.DirectionCenter)
        settle()
        assertEquals(TvRoute.Replay(month), actions.nav.current)
    }

    @Test fun settingsShowTheScreensaverAndHomeScreenSection() {
        actions.nav.section(TvRoute.Settings)
        show()
        // Down the settings to the new section: its buttons, then its two switches.
        val buttons = setOf("setting-art-frame-open", "setting-dream-open")
        repeat(20) { if (focusedTag() !in buttons) press(Key.DirectionDown) }
        if (focusedTag() == "setting-dream-open") press(Key.DirectionLeft)
        assertEquals("setting-art-frame-open", focusedTag())
        press(Key.DirectionRight)
        assertEquals("setting-dream-open", focusedTag())
        press(Key.DirectionDown)
        assertEquals("setting-art-frame-artists", focusedTag())
        settle()
        image("36-settings-system")
        compose.onNodeWithTag("setting-home-channels").assertExists()
        press(Key.DirectionUp)
        press(Key.DirectionLeft)
        press(Key.DirectionCenter)
        settle()
        assertEquals(TvRoute.ArtFrame, actions.nav.current)
    }
}
