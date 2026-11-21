package com.ketvoice.desktop.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.desktop.audio.AudioEvent
import com.ketvoice.desktop.audio.AudioSource
import com.ketvoice.desktop.audio.FakeAudioEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shell over the real `Player` facade, with the sound-free engine: what a listener's clicks reach. */
@OptIn(ExperimentalTestApi::class)
class ShellPlayerIntegrationTest {
    private val engine = FakeAudioEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private fun sourceOf(t: Track) = AudioSource.Url("https://fixture.test/${t.id}.flac", mapOf("User-Agent" to "test"))

    @BeforeTest fun setUp() {
        Fixtures.installCovers()
        AppSettings.reduceAnimations.value = true
        Player.resetForTest()
        Player.autoplayCandidates = { emptyList() }
        Player.attach(engine, { sourceOf(it) }, scope)
    }

    @AfterTest fun tearDown() { Player.resetForTest(); scope.cancel() }

    private fun lastLoad() = engine.calls.lastOrNull { it.startsWith("load ") }

    @Test fun doubleClickPlaysThroughThePlayerAndTheBarFollows() = runComposeUiTest {
        val album = Fixtures.albumTracks(0)
        setContent {
            KetvoiceShell(PlayerBridge, Fixtures.library(), FixtureCatalog, dark = true,
                navigator = remember { Navigator(Route.Album(Fixtures.refs[0])) })
        }
        fun barShows(t: Track) = onAllNodesWithText(t.title).filter(hasAnyAncestor(hasTestTag("lcd"))).fetchSemanticsNodes().isNotEmpty()

        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("track:${album[1].id}").assertExists() }.isSuccess }
        onNodeWithTag("track:${album[1].id}").performMouseInput { doubleClick() }
        waitUntil(timeoutMillis = 5_000) { lastLoad() != null }
        assertEquals("load ${sourceOf(album[1])} 0 true", lastLoad())
        waitUntil(timeoutMillis = 5_000) { barShows(album[1]) }

        onNodeWithTag("next").performClick()
        waitUntil(timeoutMillis = 5_000) { lastLoad() == "load ${sourceOf(album[2])} 0 true" }
        waitUntil(timeoutMillis = 5_000) { barShows(album[2]) }

        onNodeWithTag("previous").performClick()
        waitUntil(timeoutMillis = 5_000) { lastLoad() == "load ${sourceOf(album[1])} 0 true" }
        waitUntil(timeoutMillis = 5_000) { barShows(album[1]) }

        // The Lossless badge comes only from the engine's real format, and goes with the next load.
        onNodeWithTag("lossless-badge").assertDoesNotExist()
        engine.emit(AudioEvent.Format("flac", 44_100, 16, 2, 0))
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("lossless-badge").assertExists() }.isSuccess }
        onNodeWithTag("next").performClick()
        waitUntil(timeoutMillis = 5_000) { lastLoad() == "load ${sourceOf(album[2])} 0 true" }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("lossless-badge").assertDoesNotExist() }.isSuccess }
        engine.emit(AudioEvent.Format("aac", 44_100, 0, 2, 256_000))
        waitForIdle()
        onNodeWithTag("lossless-badge").assertDoesNotExist()
    }
}
