package com.ketvoice.app.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.MusicApi
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

/**
 * Ketvoice TV's screens rendered on the host at 1920 × 1080 (960 × 540 dp, xhdpi, a TV in landscape), driven with the
 * remote's keys only. Each step saves a picture under app/build/host-ui-preview/tv/ to look at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvScreensHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Suppress("UNCHECKED_CAST") private val recent get() = ListeningHistory.tracks as MutableStateFlow<List<Track>>
    @Suppress("UNCHECKED_CAST") private val known get() = ListeningHistory.knownTracks as MutableStateFlow<List<Track>>
    @Suppress("UNCHECKED_CAST") private val counts get() = ListeningHistory.playCounts as MutableStateFlow<Map<String, Int>>
    @Suppress("UNCHECKED_CAST") private val player get() = Player.state as MutableStateFlow<PlayerUiState>
    @Suppress("UNCHECKED_CAST") private val position get() = Player.positionMs as MutableStateFlow<Long>
    @Suppress("UNCHECKED_CAST") private val lyrics get() = LyricsRepo.state as MutableStateFlow<LyricsRepo.State>

    private lateinit var tracks: List<Track>
    private lateinit var actions: TvActions

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = false
        AppSettings.animatedArtwork.value = false
        val dir = File(compose.activity.cacheDir, "tv-covers").apply { mkdirs() }
        val names = listOf("Lumières d'été" to "Nova Lane", "Minuit" to "Kael", "Océan" to "Sora", "Néons" to "Nova Lane",
            "Après la pluie" to "Iris", "Vertige" to "Kael", "Sable" to "Mira", "Polaroid" to "Sora", "Encore" to "Iris")
        tracks = names.mapIndexed { i, (title, artist) ->
            Track("qa:tv$i", title, artist, "Album $artist", durationMs = 200_000L + i * 7_000L, coverUrl = cover(dir, i))
        }
        recent.value = tracks
        known.value = tracks
        counts.value = tracks.associate { it.id to 4 }
        AppSettings.favoriteIds.value = tracks.take(5).map { it.id }.toSet()
        actions = TvActions(TvNavigator())
    }

    @After fun reset() {
        recent.value = emptyList(); known.value = emptyList(); counts.value = emptyMap()
        player.value = PlayerUiState(); position.value = 0L; lyrics.value = LyricsRepo.State()
        AppSettings.favoriteIds.value = emptySet()
        TvSignIn.cancel()
    }

    /** A cover drawn on the device: two colours and a disc, saved as a file the image loader reads. */
    private fun cover(dir: File, i: Int): String {
        val file = File(dir, "cover$i.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val hues = floatArrayOf(i * 41f % 360f, (i * 41f + 70f) % 360f)
            val paint = Paint().apply {
                shader = LinearGradient(0f, 0f, 300f, 300f, android.graphics.Color.HSVToColor(floatArrayOf(hues[0], .7f, .85f)),
                    android.graphics.Color.HSVToColor(floatArrayOf(hues[1], .8f, .35f)), Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, 300f, 300f, paint)
            canvas.drawCircle(150f, 150f, 90f, Paint().apply { color = android.graphics.Color.argb(90, 255, 255, 255) })
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return "file://" + file.absolutePath
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun RemoteMode() {
        // A TV has no touch screen: the interface is in keyboard (D-pad) mode from the start.
        val modes = LocalInputModeManager.current
        LaunchedEffect(Unit) { modes.requestInputMode(InputMode.Keyboard) }
    }

    private fun show(tier: TvTier = TvTier.Balanced) {
        compose.setContent {
            RemoteMode()
            TvApp(actions, tier, onExit = {})
        }
        settle()
    }

    private fun settle(ms: Long = 1_200) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
        // Covers decode on Coil's threads.
        Thread.sleep(150)
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

    private fun focused(): SemanticsNodeInteraction = compose.onNode(isFocused())

    private fun focusedTag(): String? = runCatching {
        focused().fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)
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

    @Test fun homeOpensOnResumeAndTheRemoteWalksTheRows() {
        show()
        compose.onNodeWithTag("tv-home").assertExists()
        compose.onNodeWithTag("hero-play").assertExists()
        assertEquals("Home's first focus is the resume button", "hero-play", focusedTag())
        image("01-home")
        press(Key.DirectionDown)
        assertEquals("card-t:qa:tv0:0", focusedTag())
        press(Key.DirectionRight, 2)
        assertEquals("card-t:qa:tv2:2", focusedTag())
        image("02-home-row-focus")
        // Down to the library row, then back up: the first row gives the focus back to its last card.
        press(Key.DirectionDown)
        assertTrue(focusedTag().orEmpty().startsWith("card-"))
        press(Key.DirectionUp)
        assertEquals("card-t:qa:tv2:2", focusedTag())
    }

    @Test fun theSideMenuOpensOnTheLeftAndChangesSection() {
        show()
        press(Key.DirectionDown)
        press(Key.DirectionLeft)
        assertEquals("rail-home", focusedTag())
        image("03-rail-open")
        press(Key.DirectionDown)
        assertEquals("rail-library", focusedTag())
        press(Key.DirectionCenter)
        settle()
        compose.onNodeWithTag("tv-library").assertExists()
        assertEquals(TvRoute.Library, actions.nav.current)
        // Choosing a section puts the focus on its page's first card; the arrows walk on from there.
        assertEquals("card-favorites", focusedTag())
        press(Key.DirectionRight)
        assertEquals("card-history", focusedTag())
        image("04-library")
        // Back: from a section to Home.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
        assertEquals(TvRoute.Home, actions.nav.current)
    }

    @Test fun searchTypesWithTheOnScreenKeyboard() {
        actions.nav.section(TvRoute.Search)
        show()
        assertEquals("key-a", focusedTag())
        press(Key.DirectionCenter)
        press(Key.DirectionRight, 2)
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        compose.onNodeWithTag("search-query").assertExists()
        val shown = compose.onNodeWithTag("search-query").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
        assertEquals("ack▏", shown)
        image("05-search")
        // The arrows walk the grid exactly, then leave it for the action keys under it.
        press(Key.DirectionDown, 3)
        assertEquals("key-8", focusedTag())
        press(Key.DirectionDown)
        assertTrue(focusedTag() in setOf("key-Espace", "key-Effacer"))
    }

    @Test fun nowPlayingShowsLyricsAndTheTransportTakesTheFocus() {
        val track = tracks[0]
        player.value = PlayerUiState(queue = tracks, index = 0, isPlaying = true, playWhenReady = true, durationMs = track.durationMs)
        position.value = 61_000L
        actions.nav.open(TvRoute.NowPlaying)
        show()
        // The page asked LyricsRepo for this title's lyrics (no network here): they arrive as a provider would give them.
        lyrics.value = LyricsRepo.State(trackId = track.id, isTimed = true, lines = listOf(
            MusicApi.LyricLine(40_000, "Les lumières de la ville"),
            MusicApi.LyricLine(55_000, "Dansent encore sous nos pas"),
            MusicApi.LyricLine(60_000, "Et l'été ne finit pas"),
            MusicApi.LyricLine(74_000, "On reste là jusqu'au matin"),
            MusicApi.LyricLine(82_000, "Sous les néons de la plage")))
        settle()
        compose.onNodeWithTag("tv-now-playing").assertExists()
        compose.onNodeWithTag("np-lyrics").assertExists()
        assertEquals("np-play", focusedTag())
        image("06-now-playing")
        press(Key.DirectionRight)
        assertEquals("np-next", focusedTag())
        // Down to the second row, along it to « File d'attente »: the queue slides in from the right.
        press(Key.DirectionDown)
        repeat(4) { if (focusedTag() != "np-queue-open") press(Key.DirectionRight) }
        assertEquals("np-queue-open", focusedTag())
        press(Key.DirectionCenter)
        settle(600)
        compose.onNodeWithTag("np-queue").assertExists()
        assertTrue("the queue takes the focus", focusedTag().orEmpty().startsWith("track-"))
        image("07-now-playing-queue")
        // Back closes it and gives the focus back to play; then asleep after a while without a key: the first key only wakes.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle(600)
        compose.onNodeWithTag("np-queue").assertDoesNotExist()
        assertEquals("np-play", focusedTag())
        compose.onNodeWithTag("np-lyrics").assertExists()
        compose.mainClock.advanceTimeBy(IDLE_MS + 1_500)
        compose.waitForIdle()
        image("07b-now-playing-asleep")
        press(Key.DirectionLeft)
        assertEquals("the waking key did not move the focus", "np-play", focusedTag())
        press(Key.DirectionLeft)
        assertEquals("np-previous", focusedTag())
    }

    @Test fun collectionPageListsTheFavouritesAndPlaysFromATitle() {
        val played = mutableListOf<Pair<Int, Int>>()
        actions = TvActions(TvNavigator(), object : TvActions.TvPlayback {
            override fun queue(tracks: List<Track>, index: Int) { played += tracks.size to index }
            override fun shuffle(tracks: List<Track>) { played += tracks.size to -1 }
        })
        actions.nav.open(TvRoute.Collection("favorites", "favorites", "Favoris"))
        show()
        compose.onNodeWithTag("collection-title").assertExists()
        assertEquals("collection-play", focusedTag())
        image("08-favorites")
        press(Key.DirectionRight, 2)
        assertEquals("track-0", focusedTag())
        press(Key.DirectionDown)
        assertEquals("track-1", focusedTag())
        press(Key.DirectionCenter)
        assertEquals(listOf(5 to 1), played)
        assertEquals(TvRoute.NowPlaying, actions.nav.current)
    }

    @Test fun settingsAndSignInWithTheQrCode() {
        actions.nav.section(TvRoute.Settings)
        show()
        compose.onNodeWithTag("tv-settings").assertExists()
        assertEquals("account-sign-in", focusedTag())
        image("09-settings")
        press(Key.DirectionDown)
        assertEquals("setting-ketmix", focusedTag())
        // The sign-in page, as the TV shows it once the server gave a code (no network here).
        TvSignIn.showForTest(TvSignIn.State.Showing("K7QX4M2P", System.currentTimeMillis() + 245_000))
        compose.runOnIdle { actions.nav.open(TvRoute.SignIn) }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        TvSignIn.showForTest(TvSignIn.State.Showing("K7QX4M2P", System.currentTimeMillis() + 245_000))
        settle(600)
        compose.onNodeWithTag("qr").assertExists()
        compose.onNodeWithTag("sign-in-code").assertExists()
        image("10-sign-in")
    }

    @Test fun artistPageAndATitlesOptionsFromTheMenuKey() {
        actions.nav.open(TvRoute.Artist("", "Nova Lane"))
        show()
        compose.onNodeWithTag("artist-name").assertExists()
        assertEquals("artist-play", focusedTag())
        press(Key.DirectionDown)
        assertTrue(focusedTag().orEmpty().startsWith("card-t:qa:tv0"))
        image("12-artist")
        // The menu key opens the title's options over the page; Back closes them.
        press(Key.Menu)
        compose.onNodeWithTag("track-menu").assertExists()
        settle(600)
        assertEquals("menu-Lire ensuite", focusedTag())
        image("13-track-menu")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle(600)
        compose.onNodeWithTag("track-menu").assertDoesNotExist()
        assertEquals(TvRoute.Artist("", "Nova Lane"), actions.nav.current)
    }

    @Test fun lightTvsDrawTheSameScreens() {
        show(TvTier.Light)
        compose.onNodeWithTag("tv-home").assertExists()
        assertEquals("hero-play", focusedTag())
        press(Key.DirectionDown)
        image("11-home-light-tier")
    }
}
