package com.ketvoice.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
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
import com.ketvoice.app.ui.studio.StudioVisuals
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
        MediaOverrides.replaceForTest(emptyList())
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

    @Test fun visualsOfACatalogueTitleCanBeChangedAndRestored() {
        val track = Track("yt:abc", "One More Time", "Daft Punk", "Discovery")
        val targets = listOfNotNull(VisualTarget.track(track), VisualTarget.album("Discovery", "Daft Punk", null, track),
            VisualTarget.artist("Daft Punk", null))
        MediaOverrides.replaceForTest(listOf(MediaOverride("album", "discovery|daft punk", "Discovery — Daft Punk", cover = "https://cdn.example/a.jpg")))
        compose.setContent { KetvoiceTheme(useDark = true) {
            val scope = rememberCoroutineScope()
            val state = androidx.compose.runtime.remember { StudioState(StudioFiles(compose.activity), scope) }
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(com.ketvoice.app.theme.LocalTheme.current.background)) {
                StudioVisuals(state, targets)
            }
        } }
        image("studio-visuals-title")
        compose.onNodeWithText("Original du catalogue").assertExists()
        compose.onNodeWithTag("studio-visual-save").assertIsNotEnabled()
        compose.onNodeWithTag("studio-visuals-kind").onChildren().filterToOne(hasText("Album")).performClick()
        compose.onNodeWithText("Personnalisé").assertExists()
        compose.onNodeWithTag("studio-visual-cover-restore").performScrollTo().performClick()
        compose.onNodeWithText("Original rétabli à l'envoi").assertExists()
        compose.onNodeWithTag("studio-visual-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("studio-rights").performScrollTo().performClick()
        compose.onNodeWithTag("studio-visual-save").assertIsEnabled()
        compose.onNodeWithTag("studio-visual-restore-all").assertExists()
        compose.onNodeWithTag("studio-visuals-kind").performScrollTo()
        compose.onNodeWithTag("studio-visuals-kind").onChildren().filterToOne(hasText("Artiste")).performClick()
        image("studio-visuals-artist")
        compose.onNodeWithText("Photo de l'artiste").assertExists()
        compose.onNodeWithText("Portrait").assertExists()
        compose.onNodeWithTag("studio-artist-preview").assertExists()
    }

    @Test fun aLongPressOnLyricsOffersShareBesideCancel() {
        val repo = flow<LyricsRepo.State>(LyricsRepo, "_state")
        val before = repo.value
        repo.value = LyricsRepo.State(lines = listOf(MusicApi.LyricLine(0, "First line of the song"),
            MusicApi.LyricLine(4000, "Second line, sung now"), MusicApi.LyricLine(8000, "Third line")), source = "Test")
        try {
            compose.setContent { KetvoiceTheme(useDark = false) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Color(0xFF202030))) {
                    com.ketvoice.app.ui.player.LyricsPane(Track("yt:l", "Song", "Artist", "Album"), expanded = false, onSource = {})
                }
            } }
            compose.onNodeWithText("Second line, sung now").performTouchInput { longClick() }
            compose.waitForIdle()
            image("lyrics-moment-bar")
            compose.onNodeWithTag("moment-share").assertIsDisplayed()
            compose.onNodeWithText("Annuler").assertIsDisplayed()
        } finally { repo.value = before }
    }

    @Test fun anAlbumsTitlesCanBeTickedToSwitchTheirCanvasOffOrChangeIt() {
        val tracks = listOf(Track("yt:1", "One More Time", "Daft Punk", "Discovery"), Track("yt:2", "Aerodynamic", "Daft Punk", "Discovery"),
            Track("yt:3", "Digital Love", "Daft Punk", "Discovery"))
        val target = VisualTarget.album("Discovery", "Daft Punk", null, tracks.first(), tracks)!!
        MediaOverrides.replaceForTest(listOf(MediaOverride("track", "aerodynamic|daft punk", canvasOff = true),
            MediaOverride("track", "digital love|daft punk", canvas = "https://cdn.example/own.mp4")))
        compose.setContent { KetvoiceTheme(useDark = true) {
            val scope = rememberCoroutineScope()
            val state = androidx.compose.runtime.remember { StudioState(StudioFiles(compose.activity), scope) }
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(com.ketvoice.app.theme.LocalTheme.current.background)) {
                StudioVisuals(state, listOf(target))
            }
        } }
        compose.onNodeWithTag("studio-title-canvas").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("studio-title-row").assertCountEquals(3)
        compose.onNodeWithText("Canvas désactivé").assertExists(); compose.onNodeWithText("Canvas personnalisé").assertExists()
        compose.onNodeWithText("Aucun titre choisi : l'action vise tout l'album.").assertExists()
        image("studio-title-canvas")
        // Nothing can be sent before the rights are confirmed.
        compose.onNodeWithTag("studio-title-off").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("studio-rights").performScrollTo().performClick()
        compose.onNodeWithTag("studio-title-off").assertIsEnabled(); compose.onNodeWithTag("studio-title-restore").assertIsEnabled()
        // Applying a Canvas needs one to be chosen first.
        compose.onNodeWithTag("studio-title-apply").assertIsNotEnabled()
        compose.onAllNodesWithTag("studio-title-row")[0].performScrollTo().performClick()
        compose.onAllNodesWithTag("studio-title-row")[2].performClick()
        compose.onNodeWithText("2 titres choisis").assertExists()
        compose.onNodeWithTag("studio-title-none").performScrollTo().performClick()
        compose.onNodeWithText("Aucun titre choisi : l'action vise tout l'album.").assertExists()
        compose.onNodeWithTag("studio-title-all").performScrollTo().performClick()
        compose.onNodeWithText("3 titres choisis").assertExists()
        // Each title also has its own switch.
        compose.onNodeWithTag("studio-canvas-off").performScrollTo().assertIsEnabled()
    }

    @Test fun theCanvasActionsHaveAModeForTheMusicPlayerAlone() {
        val tracks = listOf(Track("yt:1", "One More Time", "Daft Punk", "Discovery"), Track("yt:2", "Aerodynamic", "Daft Punk", "Discovery"))
        val target = VisualTarget.album("Discovery", "Daft Punk", null, tracks.first(), tracks)!!
        MediaOverrides.replaceForTest(listOf(MediaOverride("track", "aerodynamic|daft punk", playerCanvas = false),
            MediaOverride("album", "discovery|daft punk", canvasOff = true)))
        compose.setContent { KetvoiceTheme(useDark = true) {
            val scope = rememberCoroutineScope()
            val state = androidx.compose.runtime.remember { StudioState(StudioFiles(compose.activity), scope) }
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(com.ketvoice.app.theme.LocalTheme.current.background)) {
                StudioVisuals(state, listOf(target))
            }
        } }
        try {
            // The page's mode first: what was there before, and the page's own marks.
            compose.onNodeWithTag("studio-canvas-scope").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("studio-title-off").assertExists()
            compose.onAllNodesWithText("Canvas désactivé").assertCountEquals(2)
            compose.onAllNodesWithTag("studio-player-off").assertCountEquals(0)
            compose.onNodeWithTag("studio-canvas-scope-player").performClick()
            compose.waitForIdle()
            // The player's mode: its three actions, its own marks, and nothing that would change the page.
            for (tag in listOf("studio-player-off", "studio-player-on", "studio-player-follow")) compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithTag("studio-title-off").assertCountEquals(0)
            compose.onAllNodesWithTag("studio-title-apply").assertCountEquals(0)
            compose.onAllNodesWithText("Masqué dans le lecteur").assertCountEquals(1)
            compose.onAllNodesWithText("Canvas désactivé").assertCountEquals(0)
            image("studio-title-canvas-player")
            compose.onNodeWithTag("studio-player-off").assertIsNotEnabled()
            compose.onNodeWithTag("studio-rights").performScrollTo().performClick()
            compose.onNodeWithTag("studio-player-off").performScrollTo().assertIsEnabled()
            compose.onNodeWithTag("studio-canvas-scope-page").performScrollTo().performClick()
            compose.onNodeWithTag("studio-title-off").assertExists()
        } finally { MediaOverrides.replaceForTest(emptyList()) }
    }

    @Test fun theTitlesOfAnAlbumAreOnlyOfferedForAnAlbumWithTitles() {
        val track = Track("yt:1", "One More Time", "Daft Punk", "Discovery")
        compose.setContent { KetvoiceTheme(useDark = true) {
            val scope = rememberCoroutineScope()
            val state = androidx.compose.runtime.remember { StudioState(StudioFiles(compose.activity), scope) }
            StudioVisuals(state, listOf(VisualTarget.track(track)!!, VisualTarget.album("Discovery", "Daft Punk", null, track)!!))
        } }
        compose.onAllNodesWithTag("studio-title-canvas").assertCountEquals(0)
        compose.onNodeWithTag("studio-canvas-off").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("studio-visuals-kind").onChildren().filterToOne(hasText("Album")).performClick()
        compose.onAllNodesWithTag("studio-title-canvas").assertCountEquals(0)
    }
}
