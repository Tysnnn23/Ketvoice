package com.ketvoice.app.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Dp
import com.ketvoice.app.automix.Automix
import com.ketvoice.app.automix.KetmixLearning
import com.ketvoice.app.automix.MixKind
import com.ketvoice.app.automix.MixPlan
import com.ketvoice.app.data.Album
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ArtistCache
import com.ketvoice.app.data.ArtistPage
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.MusicArtist
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * Ketvoice TV 1.1.0's visuals rendered on the host at 1920 × 1080, driven by the remote's keys: Now Playing with and
 * without lyrics, a Canvas in its frame, the pictures during a Ketmix blend, the remote's 👍/👎, Home's poster, the
 * card → page zoom (first and last frames, both ways) and the immersive artist page. Pictures under
 * app/build/host-ui-preview/tv/ (named 2x-…).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvVisualsHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Suppress("UNCHECKED_CAST") private val recent get() = ListeningHistory.tracks as MutableStateFlow<List<Track>>
    @Suppress("UNCHECKED_CAST") private val known get() = ListeningHistory.knownTracks as MutableStateFlow<List<Track>>
    @Suppress("UNCHECKED_CAST") private val counts get() = ListeningHistory.playCounts as MutableStateFlow<Map<String, Int>>
    @Suppress("UNCHECKED_CAST") private val player get() = Player.state as MutableStateFlow<PlayerUiState>
    @Suppress("UNCHECKED_CAST") private val position get() = Player.positionMs as MutableStateFlow<Long>
    @Suppress("UNCHECKED_CAST") private val lyrics get() = LyricsRepo.state as MutableStateFlow<LyricsRepo.State>
    @Suppress("UNCHECKED_CAST") private val mix get() = Automix.ui as MutableStateFlow<Automix.Ui>
    @Suppress("UNCHECKED_CAST") private val canvases get() = CanvasRepo.paths as MutableStateFlow<Map<String, String>>

    private lateinit var tracks: List<Track>
    private lateinit var actions: TvActions
    private lateinit var dir: File
    private val defaultCanvas = TvCanvasSlot.content

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = false
        AppSettings.animatedArtwork.value = false
        dir = File(compose.activity.cacheDir, "tv-covers").apply { mkdirs() }
        val names = listOf("Lumières d'été" to "Nova Lane", "Minuit" to "Kael", "Océan" to "Sora", "Néons" to "Nova Lane",
            "Après la pluie" to "Iris", "Vertige" to "Kael", "Sable" to "Mira", "Polaroid" to "Sora", "Encore" to "Iris")
        tracks = names.mapIndexed { i, (title, artist) ->
            Track("qa:tv$i", title, artist, "Album $artist", durationMs = 200_000L + i * 7_000L, coverUrl = picture("cover$i", 300, 300, i))
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
        mix.value = Automix.Ui(); canvases.value = emptyMap()
        TvCanvasSlot.content = defaultCanvas
        AppSettings.favoriteIds.value = emptySet()
        AppSettings.automix.value = false; AppSettings.ketmixLearns.value = false; AppSettings.animatedArtwork.value = false
        KetmixLearning.resetForTest()
    }

    /** A picture drawn on the device (two colours and a disc), saved as a file the image loader reads. */
    private fun picture(name: String, w: Int, h: Int, i: Int): String {
        val file = File(dir, "$name.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val hues = floatArrayOf(i * 41f % 360f, (i * 41f + 70f) % 360f)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), android.graphics.Color.HSVToColor(floatArrayOf(hues[0], .7f, .85f)),
                    android.graphics.Color.HSVToColor(floatArrayOf(hues[1], .8f, .35f)), Shader.TileMode.CLAMP)
            })
            canvas.drawCircle(w * .5f, h * .42f, minOf(w, h) * .3f, Paint().apply { color = android.graphics.Color.argb(90, 255, 255, 255) })
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
        compose.setContent {
            RemoteMode()
            TvApp(actions, tier, onExit = {})
        }
        settle()
    }

    private fun settle(ms: Long = 1_200) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
        // Pictures decode on Coil's threads (the poster after its debounce): twice, so that a fade has its picture.
        repeat(2) {
            Thread.sleep(200)
            compose.mainClock.advanceTimeBy(700)
            compose.waitForIdle()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun press(key: Key, times: Int = 1, after: Long = 250) {
        repeat(times) {
            compose.onRoot().performKeyInput { pressKey(key) }
            compose.mainClock.advanceTimeBy(after)
            compose.waitForIdle()
        }
    }

    private fun focusedTag(): String? = runCatching {
        compose.onNode(isFocused()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.TestTag)
    }.getOrNull()

    private fun text(tag: String): String = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        .config[SemanticsProperties.Text].joinToString { it.text }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()

    private fun width(b: androidx.compose.ui.unit.DpRect): Dp = b.right - b.left
    private fun height(b: androidx.compose.ui.unit.DpRect): Dp = b.bottom - b.top

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

    private val sampleLyrics get() = listOf(
        MusicApi.LyricLine(40_000, "Les lumières de la ville"),
        MusicApi.LyricLine(55_000, "Dansent encore sous nos pas"),
        MusicApi.LyricLine(60_000, "Et l'été ne finit pas"),
        MusicApi.LyricLine(74_000, "On reste là jusqu'au matin"),
        MusicApi.LyricLine(82_000, "Sous les néons de la plage"),
        MusicApi.LyricLine(90_000, "Le ciel garde nos secrets"))

    private fun playing(index: Int = 0) {
        val track = tracks[index]
        player.value = PlayerUiState(queue = tracks, index = index, isPlaying = true, playWhenReady = true, durationMs = track.durationMs)
        position.value = 61_000L
        actions.nav.open(TvRoute.NowPlaying)
    }

    private fun withLyrics(index: Int = 0) {
        lyrics.value = LyricsRepo.State(trackId = tracks[index].id, isTimed = true, lines = sampleLyrics)
    }

    @Test fun nowPlayingWithLyricsGlidesToTheCentredLayoutWhenTheyAreHidden() {
        playing()
        show()
        withLyrics()
        settle()
        compose.onNodeWithTag("np-lyrics").assertExists()
        assertEquals("np-play", focusedTag())
        val art = bounds("np-artwork")
        // The column on the left: the frame is square (a cover) and stays left of the middle.
        assertTrue("artwork in the left column ($art)", art.right.value < 480f)
        assertEquals("a cover's frame is square", width(art).value, height(art).value, 2f)
        image("20-np-lyrics")
        // The lyrics button hides them: everything glides to the centred layout.
        press(Key.DirectionDown)
        repeat(4) { if (focusedTag() != "np-lyrics-toggle") press(Key.DirectionRight) }
        assertEquals("np-lyrics-toggle", focusedTag())
        compose.mainClock.autoAdvance = false
        press(Key.DirectionCenter, after = 0)
        frames(180)
        image("20b-np-lyrics-hiding")
        compose.mainClock.autoAdvance = true
        settle()
        compose.onNodeWithTag("np-lyrics").assertDoesNotExist()
        val solo = bounds("np-artwork")
        assertEquals("centred", 480f, (solo.left.value + solo.right.value) / 2f, 2f)
        assertEquals("the focus stayed on the button", "np-lyrics-toggle", focusedTag())
        image("21-np-lyrics-hidden")
    }

    @Test fun nowPlayingWithoutLyricsCentresTheArtworkWithTheControlsAlongTheBottom() {
        playing(2)
        show()
        compose.onNodeWithTag("np-lyrics").assertDoesNotExist()
        val art = bounds("np-artwork")
        assertEquals("centred", 480f, (art.left.value + art.right.value) / 2f, 2f)
        val play = bounds("np-play")
        assertEquals("play in the middle of the bottom bar", 480f, (play.left.value + play.right.value) / 2f, 3f)
        assertTrue("the like button at the bar's left end", bounds("np-like").left.value < 60f)
        assertTrue("the queue button at its right end", bounds("np-queue-open").right.value > 900f)
        assertEquals(tracks[2].title, text("np-title"))
        image("22-np-no-lyrics")
    }

    @Test fun aCanvasPlaysInsideTheFrameAtItsOwnShape() {
        AppSettings.animatedArtwork.value = true
        // Host renders cannot decode video: a drawn stand-in reports a 9:16 Canvas as the player would.
        TvCanvasSlot.content = { _, _, modifier, onAspect, _ ->
            LaunchedEffect(Unit) { onAspect(9f / 16f) }
            Box(modifier.background(Brush.verticalGradient(listOf(Color(0xFF3B6FD8), Color(0xFFE0568A), Color(0xFF1B1B3A)))))
        }
        canvases.value = mapOf(tracks[1].id to File(dir, "canvas.mp4").absolutePath)
        playing(1)
        show()
        compose.onNodeWithTag("np-canvas", useUnmergedTree = true).assertExists()
        val art = bounds("np-artwork")
        assertEquals("the frame takes the Canvas's shape", 9f / 16f, width(art).value / height(art).value, .02f)
        image("23-np-canvas")
        withLyrics(1)
        settle()
        val column = bounds("np-artwork")
        assertTrue("with lyrics the Canvas moves to the column", column.right.value < 480f)
        assertEquals(9f / 16f, width(column).value / height(column).value, .02f)
        image("23b-np-canvas-lyrics")
    }

    @Test fun duringAKetmixBlendThePicturesFollowTheMixAndSwapOnTheBass() {
        AppSettings.automix.value = true
        playing(0)
        val now = android.os.SystemClock.elapsedRealtime()
        // 42 % through a 12 s blend whose bass changes hands at the middle: the incoming cover is coming in.
        mix.value = Automix.Ui(Automix.Phase.Mixing, tracks[0], tracks[4], MixKind.Beatmatch, startedAt = now - 5_040, durationMs = 12_000,
            swapAt = .5f)
        show()
        compose.onNodeWithTag("np-artwork-incoming", useUnmergedTree = true).assertExists()
        assertEquals("the outgoing title is shown until the swap", tracks[0].title, text("np-title"))
        compose.onNodeWithTag("ketmix-badge", useUnmergedTree = true).assertExists()
        image("24-np-ketmix-blend")
        // Past the bass swap: the incoming title is the one shown, its picture whole.
        mix.value = mix.value.copy(startedAt = now - 8_400)
        settle()
        compose.onNodeWithTag("np-artwork-incoming", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(tracks[4].title, text("np-title"))
        image("24b-np-ketmix-after-swap")
    }

    @Test fun theRemoteRatesTheLastTransitionWithUpAndDownWithoutMovingTheFocus() {
        AppSettings.automix.value = true
        AppSettings.ketmixLearns.value = true
        AppSettings.ketmixTaste.value = ""
        KetmixLearning.persist = {}
        KetmixLearning.resetForTest()
        playing(1)
        mix.value = Automix.Ui(Automix.Phase.Mixing, tracks[0], tracks[1], MixKind.Echo, startedAt = android.os.SystemClock.elapsedRealtime(),
            durationMs = 6_000)
        show()
        compose.runOnIdle { KetmixLearning.played(MixPlan(MixKind.Echo, overlapMs = 4_000, reason = "fixture"), "Minuit — Kael", "Océan — Sora") }
        settle(400)
        // The mix ends: « Cette transition vous a plu ? » with the remote's answer under it.
        mix.value = Automix.Ui()
        settle(600)
        compose.onNodeWithTag("np-rate", useUnmergedTree = true).assertExists()
        assertEquals("↑ J'ai aimé · ↓ Je n'ai pas aimé", text("np-rate-hint"))
        assertEquals("np-play", focusedTag())
        image("25-np-rate-prompt")
        press(Key.DirectionUp)
        assertEquals("Up is 👍", true, KetmixLearning.last.value?.rating)
        assertEquals("the focus did not move", "np-play", focusedTag())
        compose.onNodeWithTag("np-rated-up", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("np-rate-hint", useUnmergedTree = true).assertDoesNotExist()
        image("25b-np-rate-thanks")
        // Once rated, Down is a key like any other: the rating stays.
        press(Key.DirectionDown)
        assertEquals(true, KetmixLearning.last.value?.rating)
    }

    @Test fun homeShowsTheFocusedCardsPictureAndTitleAsItsPoster() {
        show()
        assertEquals("hero-play", focusedTag())
        assertEquals("on arrival the poster tells what was playing", tracks[0].title, text("home-spot-title"))
        compose.onNodeWithTag("tv-poster", useUnmergedTree = true).assertExists()
        image("26-home-poster")
        press(Key.DirectionDown)
        press(Key.DirectionRight, 2, after = 120)
        settle()
        assertEquals("card-t:qa:tv2:2", focusedTag())
        assertEquals(tracks[2].title, text("home-spot-title"))
        image("26b-home-poster-focus")
        // Down to « Votre bibliothèque »: a collection's poster.
        press(Key.DirectionDown)
        settle()
        assertEquals("Favoris", text("home-spot-title"))
        image("26c-home-poster-library")
        press(Key.DirectionUp, 2)
        assertEquals("hero-play", focusedTag())
    }

    @Test fun aCardZoomsIntoItsPageAndBack() {
        show()
        press(Key.DirectionDown, 2)
        assertEquals("card-favorites", focusedTag())
        val card = bounds("card-favorites")
        // The frames of the flight are stepped by hand (the test clock would otherwise run it to its end at once).
        compose.mainClock.autoAdvance = false
        compose.onRoot().performKeyInputCenter()
        frames(96)
        compose.onNodeWithTag("tv-zoom").assertExists()
        image("27-zoom-start")
        frames(160)
        image("27b-zoom-mid")
        frames(700)
        compose.onNodeWithTag("tv-zoom").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("collection-title").assertExists()
        val header = bounds("collection-cover")
        assertTrue("the header is larger than the card", width(header) > width(card))
        settle()
        image("27c-zoom-end")
        // Back: the picture shrinks into its card again.
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        frames(64)
        compose.onNodeWithTag("tv-zoom").assertExists()
        image("27d-zoom-back")
        frames(160)
        image("27e-zoom-back-mid")
        frames(800)
        compose.onNodeWithTag("tv-zoom").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        settle()
        assertEquals("card-favorites", focusedTag())
        image("27f-zoom-back-end")
    }

    /** Steps the clock by hand, a frame at a time, for [ms]. */
    private fun frames(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(16L, left)
            compose.mainClock.advanceTimeBy(step)
            compose.waitForIdle()
            left -= step
        }
        compose.waitForIdle()
    }

    @Test fun reducedMotionOpensPagesWithoutTheZoom() {
        AppSettings.reduceAnimations.value = true
        try {
            show()
            press(Key.DirectionDown, 2)
            compose.onRoot().performKeyInputCenter()
            compose.mainClock.advanceTimeBy(48)
            compose.waitForIdle()
            compose.onNodeWithTag("tv-zoom").assertDoesNotExist()
            compose.onNodeWithTag("collection-title").assertExists()
        } finally { AppSettings.reduceAnimations.value = false }
    }

    @Test fun anArtistsCircleOpensIntoTheFullScreenPhoto() {
        val photo = picture("artist", 1600, 900, 5)
        listOf("Nova Lane", "Kael", "Sora", "Iris", "Mira").forEach { name ->
            val page = ArtistPage(MusicArtist("UC$name", name, photo), "", tracks.filter { it.artist == name }, emptyList(), emptyList())
            kotlinx.coroutines.runBlocking { ArtistCache.put("", name, page) }
        }
        show()
        repeat(8) { if (!focusedTag().orEmpty().startsWith("card-ar:")) press(Key.DirectionDown) }
        assertTrue("on an artist's circle (${focusedTag()})", focusedTag().orEmpty().startsWith("card-ar:"))
        settle()
        image("29-zoom-artist-card")
        compose.mainClock.autoAdvance = false
        compose.onRoot().performKeyInputCenter()
        frames(96)
        compose.onNodeWithTag("tv-zoom").assertExists()
        image("29b-zoom-artist-start")
        frames(180)
        image("29c-zoom-artist-mid")
        frames(700)
        compose.mainClock.autoAdvance = true
        settle()
        compose.onNodeWithTag("artist-name").assertExists()
        image("29d-zoom-artist-end")
    }

    @Test fun theArtistPageIsImmersive() {
        val photo = picture("artist", 1600, 900, 5)
        val page = ArtistPage(MusicArtist("UCnova", "Nova Lane", photo),
            "Nova Lane chante la nuit des villes côtières depuis 2019. Son premier album, enregistré dans un garage de Marseille, " +
                "a fait le tour des radios indépendantes. Elle revient avec des titres plus lumineux, entre pop et électro douce.",
            (tracks.filter { it.artist == "Nova Lane" } + tracks.take(3)).distinctBy { it.id },
            listOf(Album("MPRE1", "Marées", "Nova Lane", "Album · 2024", tracks[1].coverUrl), Album("MPRE2", "Rivage", "Nova Lane", "Album · 2021", tracks[2].coverUrl),
                Album("MPRE3", "Sel", "Nova Lane", "Single · 2025", tracks[3].coverUrl)),
            listOf(MusicArtist("UCkael", "Kael", tracks[1].coverUrl), MusicArtist("UCsora", "Sora", tracks[2].coverUrl)),
            listeners = "1,2 M d'auditeurs mensuels")
        kotlinx.coroutines.runBlocking { ArtistCache.put("", "Nova Lane", page) }
        actions.nav.open(TvRoute.Artist("", "Nova Lane"))
        show()
        settle()
        assertEquals("Nova Lane", text("artist-name"))
        assertTrue(text("artist-bio").startsWith("Nova Lane chante"))
        assertEquals("artist-play", focusedTag())
        val backdrop = bounds("artist-backdrop")
        assertEquals("the photo fills the screen, under the side menu too", 960f, width(backdrop).value, 1f)
        image("28-artist-immersive")
        press(Key.DirectionDown)
        settle()
        assertTrue(focusedTag().orEmpty().startsWith("card-t:"))
        image("28b-artist-rows")
        press(Key.DirectionDown)
        assertTrue("the albums row follows", focusedTag() == "card-al:MPRE1")
        press(Key.DirectionDown)
        assertEquals("then the singles", "card-al:MPRE3", focusedTag())
        press(Key.DirectionDown)
        settle()
        image("28c-artist-similar")
        assertNotNull(focusedTag())
    }

    @OptIn(ExperimentalTestApi::class)
    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performKeyInputCenter() = performKeyInput { pressKey(Key.DirectionCenter) }

}
