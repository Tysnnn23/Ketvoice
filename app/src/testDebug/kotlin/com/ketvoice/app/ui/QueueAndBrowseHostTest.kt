package com.ketvoice.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.*
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.player.SleepTimer
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.explorer.AlbumShelf
import com.ketvoice.app.ui.explorer.ArtistShelf
import com.ketvoice.app.ui.explorer.ChartTracksShelf
import com.ketvoice.app.ui.player.QueuePane
import com.ketvoice.app.ui.player.TrackMenuSheet
import com.ketvoice.app.ui.sub.LocalMusicScreen
import com.ketvoice.app.ui.sub.ReplayScreen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Host fixtures in software Skia: layout, semantics and state, not a physical device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QueueAndBrowseHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val player get() = Player.state as MutableStateFlow<PlayerUiState>
    private val known get() = ListeningHistory.knownTracks as MutableStateFlow<List<Track>>
    private val recent get() = ListeningHistory.tracks as MutableStateFlow<List<Track>>
    private val counts get() = ListeningHistory.playCounts as MutableStateFlow<Map<String, Int>>
    private val listened get() = ListeningHistory.listenedMs as MutableStateFlow<Long>
    private fun <T> local(name: String): MutableStateFlow<T> = LocalMusic::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(LocalMusic) as MutableStateFlow<T>
    private fun playerField(name: String) = Player::class.java.getDeclaredField(name).apply { isAccessible = true }
    private var oldController: Any? = null

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.languageChosen.value = true
        AppSettings.reduceAnimations.value = true
        oldController = playerField("controller").get(Player)
        playerField("controller").set(Player, null)
        clear()
    }

    @After fun clear() {
        coil.Coil.reset()
        SleepTimer.cancel()
        player.value = PlayerUiState()
        known.value = emptyList(); recent.value = emptyList(); counts.value = emptyMap(); listened.value = 0L
        local<List<Track>>("_tracks").value = emptyList()
        AppSettings.localMusicFolder.value = null
        PlaylistStore.resetForTest()
        AppUi.closeOverlays(); AppUi.selectTab(AppUi.HOME)
        AppUi.catalogRequest.value = null
        AppUi.trackMenuTrack.value = null
    }

    @After fun restoreController() { playerField("controller").set(Player, oldController) }

    private fun track(id: String, title: String, artist: String = "Artiste", album: String = "Album", durationMs: Long = 200_000) =
        Track("fixture:$id", title, artist, album, durationMs = durationMs)

    private fun image(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            org.robolectric.util.ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            java.io.File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png").apply {
                parentFile?.mkdirs()
                outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
    }

    @androidx.compose.runtime.Composable
    private fun PlayerSurface(content: @androidx.compose.runtime.Composable () -> Unit) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xFF14141A)).padding(18.dp)) { content() }
    }

    private fun queue() = listOf(track("a", "Premier"), track("b", "Deuxième"), track("c", "Troisième"))

    @Test fun queueCanBeClearedAndSavedFromItsHeader() {
        player.value = PlayerUiState(queue = queue(), queueEntryIds = listOf("k0", "k1", "k2"), index = 0)
        compose.setContent { KetvoiceTheme(useDark = true) { PlayerSurface { val state = Player.state.collectAsStateForTest(); QueuePane(state) } } }
        image("queue-pane")
        compose.onNodeWithTag("queue-save").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Deuxième").assertIsDisplayed()
        compose.onNodeWithTag("queue-modes").assertDoesNotExist()
        compose.onNodeWithTag("queue-clear").performClick()
        compose.waitForIdle()
        assertEquals(listOf("fixture:a"), player.value.queue.map { it.id })
        compose.onNodeWithText("Deuxième").assertDoesNotExist()
        compose.onNodeWithTag("queue-clear").assertDoesNotExist()
    }

    @Test fun wideLayoutsGetShuffleRepeatAndAutoplayInTheQueue() {
        player.value = PlayerUiState(queue = queue(), queueEntryIds = listOf("k0", "k1", "k2"), index = 0)
        compose.setContent { KetvoiceTheme(useDark = true) { PlayerSurface { val state = Player.state.collectAsStateForTest(); QueuePane(state, showModes = true) } } }
        image("queue-pane-modes")
        compose.onNodeWithTag("queue-modes").assertIsDisplayed()
        compose.onNodeWithContentDescription("Répéter").performClick()
        compose.waitForIdle()
        assertTrue(player.value.repeatAll)
        compose.onNodeWithContentDescription("Répéter la file").performClick()
        compose.waitForIdle()
        assertTrue(player.value.repeatOne)
        compose.onNodeWithContentDescription("Aléatoire").performClick()
        compose.waitForIdle()
        assertTrue(player.value.shuffle)
        assertEquals("fixture:a", player.value.current?.id)
        compose.onNodeWithContentDescription("Lecture automatique").performClick()
        compose.waitForIdle()
        assertFalse(player.value.autoplay)
    }

    @Test fun removingAnUpcomingTitleFromTheQueueKeepsTheOthers() {
        player.value = PlayerUiState(queue = queue(), queueEntryIds = listOf("k0", "k1", "k2"), index = 0)
        compose.setContent { KetvoiceTheme(useDark = true) { val state = Player.state.collectAsStateForTest(); QueuePane(state) } }
        compose.onNodeWithContentDescription("Retirer Deuxième").performClick()
        compose.waitForIdle()
        assertEquals(listOf("fixture:a", "fixture:c"), player.value.queue.map { it.id })
        assertEquals(listOf("k0", "k2"), player.value.queueEntryIds)
    }

    @Test fun sleepTimerIsInTheTrackMenuWithAnEndOfTitleChoice() {
        compose.setContent { KetvoiceTheme(useDark = true) { TrackMenuSheet(track("a", "Premier")) { } } }
        compose.onNodeWithText("Minuterie").performScrollTo().performClick()
        compose.onNodeWithText("Pause dans 45 min").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Annuler la minuterie").assertDoesNotExist()
        compose.onNodeWithText("À la fin du titre").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(SleepTimer.endOfTrack.value)
        SleepTimer.set(15)
        assertFalse("A delay replaces the end-of-title mode", SleepTimer.endOfTrack.value)
        assertEquals(15 * 60L, SleepTimer.remainingSeconds.value)
        SleepTimer.cancel()
        assertEquals(0L, SleepTimer.remainingSeconds.value)
    }

    @Test fun localMusicAsksForTheAudioPermissionInsteadOfLookingEmpty() {
        compose.setContent { KetvoiceTheme(useDark = true) { LocalMusicScreen() } }
        compose.waitForIdle()
        assertTrue(LocalMusic.needsPermission.value)
        image("local-permission")
        compose.onNodeWithTag("local-permission").assertIsDisplayed()
        compose.onNodeWithText("Autoriser l'accès").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Accès à la musique requis").assertIsDisplayed()
        compose.onNodeWithTag("local-search").assertDoesNotExist()
    }

    @Test fun localMusicCanBeSearchedSortedAndGrouped() {
        shadowOf(compose.activity.application).grantPermissions(LocalMusic.permission)
        compose.setContent { KetvoiceTheme(useDark = true) { LocalMusicScreen() } }
        compose.waitUntil(5_000) { !LocalMusic.scanning.value && LocalMusic.scanned.value }
        local<List<Track>>("_tracks").value = listOf(
            Track("local:1", "Zeta", "Beta", "Second", durationMs = 300_000),
            Track("local:2", "Alpha", "Alpha Artist", "First", durationMs = 100_000),
            Track("local:3", "Mid", "Beta", "Second", durationMs = 200_000))
        compose.waitForIdle()
        assertFalse(LocalMusic.needsPermission.value)
        image("local-music")
        compose.onNodeWithTag("local-search").assertIsDisplayed()
        compose.onNodeWithTag("local-view").assertIsDisplayed()
        fun order() = listOf("Zeta", "Alpha", "Mid").sortedBy { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(listOf("Alpha", "Mid", "Zeta"), order())
        compose.onNodeWithTag("local-sort").performScrollToNode(hasText("Durée"))
        compose.onNodeWithText("Durée").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Zeta", "Mid", "Alpha"), order())
        compose.onNodeWithTag("local-view").onChildren().filterToOne(hasText("Artistes")).performClick()
        compose.waitForIdle()
        image("local-artists")
        compose.onNodeWithText("2 titres", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Alpha Artist").assertIsDisplayed()
        compose.onAllNodesWithTag("local-track").assertCountEquals(0)
    }

    @Test fun replayShowsRealListeningTimeAndOpensATitleMenuOnLongPress() {
        val a = track("a", "Favori", artist = "Nova")
        val b = track("b", "Second", artist = "Orion")
        known.value = listOf(a, b)
        counts.value = mapOf(a.id to 5, b.id to 1)
        listened.value = 3 * 3_600_000L + 12 * 60_000L
        compose.setContent { KetvoiceTheme(useDark = true) { ReplayScreen() } }
        image("replay")
        compose.onNodeWithTag("replay-time").assertIsDisplayed()
        compose.onNodeWithText("3 h 12 min").assertIsDisplayed()
        compose.onNodeWithText("Temps d'écoute").assertIsDisplayed()
        compose.onAllNodesWithTag("replay-artist").assertCountEquals(2)
        compose.onNodeWithTag("replay-list").performScrollToNode(hasText("Favori"))
        compose.onNodeWithText("Favori").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(a.id, AppUi.trackMenuTrack.value?.id)
    }

    @Test fun replayArtistWithoutACataloguePageOpensASearchForThatName() {
        val a = track("a", "Favori", artist = "Nova")
        known.value = listOf(a)
        counts.value = mapOf(a.id to 2)
        compose.setContent { KetvoiceTheme(useDark = true) { ReplayScreen() } }
        compose.onNodeWithTag("replay-artist").performClick()
        compose.waitForIdle()
        assertEquals(AppUi.SEARCH, AppUi.overlay.intValue)
        assertEquals("Nova", AppUi.searchSeed.value)
    }

    @Test fun discoveryShelvesOpenAlbumsAndArtists() {
        val tracks = listOf(track("a", "Numéro un"), track("b", "Numéro deux"))
        compose.setContent {
            KetvoiceTheme(useDark = true) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize()
                    .background(com.ketvoice.app.theme.LocalTheme.current.background).padding(16.dp)) {
                    ChartTracksShelf(tracks)
                    AlbumShelf(listOf(CatalogAlbum("42", "Grand album", "Nova", null)))
                    ArtistShelf(listOf(CatalogArtist("7", "Orion", null)))
                }
            }
        }
        image("explorer-shelves")
        compose.onNodeWithTag("chart-tracks").assertIsDisplayed()
        compose.onNodeWithText("Numéro un").assertIsDisplayed()
        compose.onNodeWithText("Numéro deux").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals("fixture:b", AppUi.trackMenuTrack.value?.id)
        compose.onNodeWithText("Grand album").performClick()
        compose.waitForIdle()
        assertEquals(CatalogRequest("album", "42", "Grand album"), AppUi.catalogRequest.value)
        compose.onNodeWithText("Orion").performClick()
        compose.waitForIdle()
        assertEquals("7", AppUi.artistRequest.value?.id)
    }
}

@androidx.compose.runtime.Composable
private fun kotlinx.coroutines.flow.StateFlow<PlayerUiState>.collectAsStateForTest(): PlayerUiState {
    val value = this.collectAsState()
    return value.value
}
