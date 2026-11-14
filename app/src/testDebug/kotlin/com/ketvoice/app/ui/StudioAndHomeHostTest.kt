package com.ketvoice.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.home.HomeForYou
import com.ketvoice.app.ui.home.HomeWeekCard
import com.ketvoice.app.ui.studio.CanvasEditor
import com.ketvoice.app.ui.studio.PublisherScreen
import com.ketvoice.app.ui.studio.StudioFiles
import com.ketvoice.app.ui.studio.StudioState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Host fixtures in software Skia: layout, semantics and state, not a physical device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudioAndHomeHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun <T> flow(owner: Any, name: String): MutableStateFlow<T> =
        owner::class.java.getDeclaredField(name).apply { isAccessible = true }.get(owner) as MutableStateFlow<T>
    private val player get() = Player.state as MutableStateFlow<PlayerUiState>
    private val position get() = Player.positionMs as MutableStateFlow<Long>
    private fun playerField(name: String) = Player::class.java.getDeclaredField(name).apply { isAccessible = true }
    private var oldController: Any? = null

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.languageChosen.value = true
        AppSettings.reduceAnimations.value = true
        oldController = playerField("controller").get(Player)
        playerField("controller").set(Player, null)
    }

    @After fun clear() {
        coil.Coil.reset()
        flow<Boolean>(PublisherSession, "_allowed").value = false
        flow<Boolean>(PublisherSession, "_restoring").value = false
        player.value = PlayerUiState(); position.value = 0L
        flow<Map<String, DayStat>>(ListeningHistory, "_days").value = emptyMap()
        (ListeningHistory.knownTracks as MutableStateFlow<List<Track>>).value = emptyList()
        AppUi.editImportedSong.value = false
        playerField("controller").set(Player, oldController)
    }

    private fun image(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            org.robolectric.util.ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png").apply {
                parentFile?.mkdirs(); outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
    }

    @Test fun studioAsksToSignInAndNeverShowsEditingFirst() {
        AppUi.editImportedSong.value = true
        compose.setContent { KetvoiceTheme(useDark = true) {
            PublisherScreen(Track("kv:upload:11111111-1111-4111-8111-111111111111", "Mine", "Me", "")) {}
        } }
        image("studio-sign-in")
        compose.onNodeWithText("Connexion").assertIsDisplayed()
        compose.onNodeWithTag("studio-send-link").assertIsDisplayed()
        compose.onNodeWithTag("studio-save").assertDoesNotExist()
    }

    @Test fun aRememberedSessionWaitsInsteadOfAskingAgain() {
        flow<Boolean>(PublisherSession, "_restoring").value = true
        compose.setContent { KetvoiceTheme(useDark = true) { PublisherScreen(null) {} } }
        compose.onNodeWithText("Reconnexion au Studio…").assertIsDisplayed()
        compose.onNodeWithTag("studio-send-link").assertDoesNotExist()
    }

    @Test fun aChosenCanvasIsCheckedBeforeItCanBeSent() {
        val video = File(compose.activity.cacheDir, "canvas-fixture.mp4").apply {
            writeBytes(byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()) + ByteArray(2048))
        }
        var chosen: File? = video
        compose.setContent { KetvoiceTheme(useDark = true) {
            val scope = rememberCoroutineScope()
            val state = androidx.compose.runtime.remember { StudioState(StudioFiles(compose.activity), scope) }
            CanvasEditor(state, chosen, onChosen = { chosen = it })
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("studio-canvas-checks").fetchSemanticsNodes().isNotEmpty() }
        image("studio-canvas-editor")
        compose.onNodeWithText("Format MP4").assertIsDisplayed()
        compose.onNodeWithText("Durée inconnue").assertIsDisplayed()
        compose.onNodeWithTag("studio-canvas-source").onChildren().filterToOne(hasText("API")).performClick()
        compose.onNodeWithTag("studio-canvas-link").assertIsDisplayed()
        assertNotNull("A valid MP4 is kept", chosen)
    }

    @Test fun aFileThatIsNotAVideoIsDropped() {
        val page = File(compose.activity.cacheDir, "not-a-video.mp4").apply { writeText("<html>not a video</html>") }
        var chosen: File? = page
        compose.setContent { KetvoiceTheme(useDark = true) {
            val scope = rememberCoroutineScope()
            val state = androidx.compose.runtime.remember { StudioState(StudioFiles(compose.activity), scope) }
            CanvasEditor(state, chosen, onChosen = { chosen = it })
        } }
        compose.waitUntil(5_000) { chosen == null }
    }

    @Test fun forYouOffersToResumeAPausedTitleWhereItStopped() {
        val track = Track("fixture:resume", "Titre en pause", "Nova", "Album", durationMs = 200_000)
        player.value = PlayerUiState(queue = listOf(track), queueEntryIds = listOf("k"), index = 0, playWhenReady = false)
        position.value = 83_000L
        compose.setContent { KetvoiceTheme(useDark = true) { HomeForYou(20, emptyList()) {} } }
        image("home-for-you-resume")
        compose.onNodeWithTag("for-you-resume").assertIsDisplayed()
        compose.onNodeWithText("REPRENDRE LÀ OÙ VOUS ÉTIEZ").assertIsDisplayed()
        compose.onNodeWithText("Nova · à 1:23").assertIsDisplayed()
    }

    @Test fun forYouShowsAFreshReleaseWhenNothingIsPaused() {
        val today = java.time.LocalDate.now().toString()
        compose.setContent { KetvoiceTheme(useDark = true) {
            HomeForYou(10, listOf(CatalogAlbum("77", "Nouvel album", "Nova", null, today))) {}
        } }
        compose.onNodeWithTag("for-you-release").assertIsDisplayed()
        compose.onNodeWithText("Nouvel album").performClick()
        compose.waitForIdle()
        assertEquals(CatalogRequest("album", "77", "Nouvel album"), AppUi.catalogRequest.value)
    }

    @Test fun theWeekCardAppearsOnceThereIsAWeekToTell() {
        val a = Track("fixture:week-a", "Favori", "Nova", "Album")
        (ListeningHistory.knownTracks as MutableStateFlow<List<Track>>).value = listOf(a)
        val today = java.time.LocalDate.now()
        var days = ListeningWeek.addTime(emptyMap(), today, 2 * 3_600_000L)
        repeat(4) { days = ListeningWeek.addPlay(days, today, a.id) }
        flow<Map<String, DayStat>>(ListeningHistory, "_days").value = days
        compose.setContent { KetvoiceTheme(useDark = true) { HomeWeekCard() } }
        image("home-week")
        compose.onNodeWithTag("home-week").assertIsDisplayed()
        compose.onNodeWithText("2 h 00 min").assertIsDisplayed()
        compose.onNodeWithText("Nova").assertIsDisplayed()
    }
}
