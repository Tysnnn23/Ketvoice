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
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.pressKey
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.Track
import com.ketvoice.app.remote.PhoneRemote
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
 * Ketvoice TV 1.1's account and Ketmix screens at 1920 × 1080 with the remote's keys: « Qui écoute ? », the collection
 * page's « Mixer cette playlist » and its progress, « Lecture reçue de … », the rhythm model's offer and Settings ›
 * Profils. Pictures under app/build/host-ui-preview/tv/.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvAccountsHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Suppress("UNCHECKED_CAST") private val recent get() = ListeningHistory.tracks as MutableStateFlow<List<Track>>
    @Suppress("UNCHECKED_CAST") private val known get() = ListeningHistory.knownTracks as MutableStateFlow<List<Track>>
    @Suppress("UNCHECKED_CAST") private val received get() = PhoneRemote.received as MutableStateFlow<PhoneRemote.Received?>

    private val lea = "11111111-1111-4111-8111-111111111111"
    private val sam = "22222222-2222-4222-8222-222222222222"
    private val noa = "33333333-3333-4333-8333-333333333333"
    private lateinit var tracks: List<Track>
    private lateinit var actions: TvActions
    private lateinit var root: File

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = false
        AppSettings.animatedArtwork.value = false
        val dir = File(compose.activity.cacheDir, "tv-covers").apply { mkdirs() }
        tracks = listOf("Lumières d'été" to "Nova Lane", "Minuit" to "Kael", "Océan" to "Sora", "Néons" to "Nova Lane", "Après la pluie" to "Iris")
            .mapIndexed { i, (title, artist) -> Track("qa:acc$i", title, artist, "Album $artist", durationMs = 200_000L + i * 7_000L, coverUrl = cover(dir, i)) }
        recent.value = tracks
        known.value = tracks
        AppSettings.favoriteIds.value = tracks.map { it.id }.toSet()
        root = File(compose.activity.cacheDir, "tv-profiles-test").apply { deleteRecursively(); mkdirs() }
        actions = TvActions(TvNavigator())
        // The offer of the rhythm model is not part of these screens unless a test asks for it.
        compose.activity.getSharedPreferences(TvModelOffer.PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean("rhythmModelSuggested", true).commit()
    }

    @After fun reset() {
        recent.value = emptyList(); known.value = emptyList()
        AppSettings.favoriteIds.value = emptySet()
        TvProfiles.picking.value = false
        TvProfiles.showForTest(TvProfileBook())
        TvPlaylistMix.showForTest(TvPlaylistMix.State.Idle)
        received.value = null
    }

    private fun cover(dir: File, i: Int): String {
        val file = File(dir, "acc$i.png")
        if (!file.exists()) picture(file, i * 53f)
        return "file://" + file.absolutePath
    }

    private fun picture(file: File, hue: Float) {
        file.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, 300f, 300f, android.graphics.Color.HSVToColor(floatArrayOf(hue % 360f, .6f, .9f)),
                android.graphics.Color.HSVToColor(floatArrayOf((hue + 60f) % 360f, .8f, .4f)), Shader.TileMode.CLAMP)
        }
        Canvas(bitmap).apply {
            drawRect(0f, 0f, 300f, 300f, paint)
            drawCircle(150f, 120f, 60f, Paint().apply { color = android.graphics.Color.argb(150, 255, 255, 255) })
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
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

    @Test fun whoIsListeningPicksAProfileWithTheRemote() {
        val store = TvProfileStore(root)
        picture(store.avatar(sam), 200f)
        TvProfiles.showForTest(TvProfileBook(listOf(TvProfile(lea, "Léa", "lea@example.com"), TvProfile(sam, "Sam", "sam@example.com"),
            TvProfile(noa, "Noa", "noa@example.com")), mounted = sam), root)
        TvProfiles.launch()
        assertTrue("three listeners: the picker at launch", TvProfiles.picking.value)
        show()
        compose.onNodeWithTag("profile-picker").assertExists()
        assertEquals("the profile in use has the focus", "profile-$sam", focusedTag())
        settle(600)
        image("20-profile-picker")
        press(Key.DirectionRight)
        assertEquals("profile-$noa", focusedTag())
        press(Key.DirectionRight)
        assertEquals("profile-add", focusedTag())
        image("21-profile-picker-add")
        // « Ajouter un profil »: the QR sign-in, over the app.
        TvSignIn.showForTest(TvSignIn.State.Showing("K7QX4M2P", System.currentTimeMillis() + 245_000))
        press(Key.DirectionCenter)
        settle(600)
        assertEquals(TvRoute.SignIn, actions.nav.current)
        compose.onNodeWithTag("profile-picker").assertDoesNotExist()
        TvSignIn.cancel()
    }

    @Test fun backOnThePickerKeepsTheProfileInUse() {
        TvProfiles.showForTest(TvProfileBook(listOf(TvProfile(lea, "Léa"), TvProfile(sam, "Sam")), mounted = lea), root)
        TvProfiles.picking.value = true
        show()
        assertEquals("profile-$lea", focusedTag())
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle(600)
        compose.onNodeWithTag("profile-picker").assertDoesNotExist()
        assertEquals(lea, TvProfiles.book.value.mounted)
        compose.onNodeWithTag("tv-home").assertExists()
    }

    @Test fun mixThisPlaylistShowsItsPreparation() {
        actions.nav.open(TvRoute.Collection("favorites", "favorites", "Favoris"))
        show()
        compose.onNodeWithTag("collection-mix").assertExists()
        assertEquals("collection-play", focusedTag())
        press(Key.DirectionDown)
        assertEquals("collection-mix", focusedTag())
        image("22-collection-mix-button")
        // The button's work, as the TV shows it while the fourth title of ten is read (no player here).
        TvPlaylistMix.showForTest(TvPlaylistMix.State.Preparing(3, 10, "Océan"))
        settle(600)
        compose.onNodeWithTag("mix-progress").assertExists()
        val line = compose.onNodeWithTag("mix-progress-count").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
        assertEquals("Préparation du mix… 4/10", line)
        assertEquals("mix-play-now", focusedTag())
        image("23-mix-progress")
        // Back cancels: nothing plays, the page is as it was.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle(600)
        compose.onNodeWithTag("mix-progress").assertDoesNotExist()
        assertEquals(TvPlaylistMix.State.Idle, TvPlaylistMix.state.value)
        assertTrue(actions.nav.current is TvRoute.Collection)
    }

    @Test fun aHandoffFromThePhoneShowsAToast() {
        show()
        received.value = PhoneRemote.Received("Pixel 9 de Léa", System.currentTimeMillis())
        settle(600)
        compose.onNodeWithTag("handoff-toast").assertExists()
        image("24-handoff-toast")
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        compose.onNodeWithTag("handoff-toast").assertDoesNotExist()
    }

    @Test fun aPowerfulTvIsOfferedTheRhythmModelOnceAndSettingsShowProfiles() {
        compose.activity.getSharedPreferences(TvModelOffer.PREFS, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        TvProfiles.showForTest(TvProfileBook(listOf(TvProfile(lea, "Léa", "lea@example.com"), TvProfile(sam, "Sam", "sam@example.com")),
            mounted = lea), root)
        show(TvTier.Full)
        compose.mainClock.advanceTimeBy(7_000)
        compose.waitForIdle()
        settle(400)
        compose.onNodeWithTag("rhythm-offer").assertExists()
        assertEquals("rhythm-download", focusedTag())
        image("25-rhythm-model-offer")
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        settle(400)
        compose.onNodeWithTag("rhythm-offer").assertDoesNotExist()
        assertTrue("asked once", TvModelOffer.suggested(compose.activity))
        // Settings: the profiles, and the model's line in Ketmix (Full tier only).
        compose.runOnIdle { actions.nav.section(TvRoute.Settings) }
        settle(800)
        compose.onNodeWithTag("tv-profiles").assertExists()
        image("26-settings-profiles")
        compose.onNodeWithTag("tv-settings").performScrollToKey("s:Ketmix")
        settle(600)
        compose.onNodeWithTag("setting-rhythm-model").assertExists()
        image("27-settings-rhythm-model")
    }
}
