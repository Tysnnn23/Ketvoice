@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.player

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player as MediaPlayer
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.ListenableFuture
import com.ketvoice.app.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * The real playback service as a car sees it (8.4): the app's queue as the session's timeline, the car's
 * Now Playing buttons, and the app's own controller left with the single title it drives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoCarSessionTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var service: ServiceController<PlaybackService>
    private val component = ComponentName(app, PlaybackService::class.java)
    private val state get() = Player.state as MutableStateFlow<PlayerUiState>
    private lateinit var original: PlayerUiState
    private val queue = listOf("q0", "q1", "q2", "q3").map { Track(it, "Title $it", "Artist", "Album", durationMs = 120_000) }

    @Before fun start() {
        original = state.value
        AutoLibraryCallback.testSource = FakeAutoSource()
        AutoLibraryCallback.testPlayback = FakeAutoPlayback()
        service = Robolectric.buildService(PlaybackService::class.java).create()
    }

    @After fun stop() {
        runCatching { service.destroy() }
        state.value = original
        AutoLibraryCallback.testSource = null
        AutoLibraryCallback.testPlayback = null
    }

    private fun <T> await(future: ListenableFuture<T>): T {
        val until = System.currentTimeMillis() + 15_000
        while (!future.isDone && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue("timed out", future.isDone)
        return future.get()
    }

    private fun idle(ms: Long = 300) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
    }

    private fun connect(): MediaBrowser {
        val binder = service.get().onBind(Intent(MediaLibraryService.SERVICE_INTERFACE).setComponent(component))
        shadowOf(app).setComponentNameAndServiceForBindService(component, binder)
        return await(MediaBrowser.Builder(app, SessionToken(app, component)).buildAsync())
    }

    private fun car() = MediaSession.ControllerInfo.createTestOnlyControllerInfo("com.google.android.projection.gearhead",
        4242, 10_042, 0, 0, false, Bundle.EMPTY)

    @Test fun aCarSeesTheQueueAndTheButtonsTheAppKeepsItsSingleTitle() {
        val browser = connect()
        try {
            val session = service.get().sessions.single()
            // The car's buttons are the session's: like, shuffle, repeat, radio, Ketmix.
            assertEquals(CarControls.ACTIONS, session.mediaButtonPreferences.map { it.sessionCommand?.customAction })
            val car = service.get().connection(session, car())
            assertTrue(car.isAccepted)
            CarControls.ACTIONS.forEach { assertTrue(it, car.availableSessionCommands.contains(SessionCommand(it, Bundle.EMPTY))) }
            assertTrue(car.availablePlayerCommands.contains(MediaPlayer.COMMAND_GET_TIMELINE))
            assertEquals(CarControls.ACTIONS, car.mediaButtonPreferences!!.map { it.sessionCommand?.customAction })

            // The app's own controller keeps its commands and drives one title; the car's buttons are not its own.
            assertTrue(browser.isSessionCommandAvailable(SessionCommand(PlaybackService.CMD_UPGRADE, Bundle.EMPTY)))
            assertFalse(browser.isSessionCommandAvailable(SessionCommand(CarControls.LIKE, Bundle.EMPTY)))
            assertFalse(browser.isCommandAvailable(MediaPlayer.COMMAND_GET_TIMELINE))

            state.value = PlayerUiState(queue = queue, queueEntryIds = listOf("e0", "e1", "e2", "e3"), index = 2)
            val file = java.io.File.createTempFile("car", ".wav").apply { deleteOnExit() }
            browser.setMediaItem(MediaItem.Builder().setMediaId("q2").setUri(android.net.Uri.fromFile(file)).build())
            idle()
            val player = service.get().sessions.single().player
            assertEquals(4, player.mediaItemCount)
            assertEquals(2, player.currentMediaItemIndex)
            assertEquals(listOf("q0", "q1", "q2", "q3"), (0 until 4).map { player.getMediaItemAt(it).mediaId })
            assertTrue(player.isCommandAvailable(MediaPlayer.COMMAND_SEEK_TO_MEDIA_ITEM))
            // The app's controller still sees the title it set, alone.
            assertEquals(1, browser.mediaItemCount)
            assertEquals("q2", browser.currentMediaItem?.mediaId)

            // A queue change reaches the session without a new title on the engine.
            state.value = state.value.copy(queue = queue + Track("q4", "Title q4", "Artist", "Album"),
                queueEntryIds = listOf("e0", "e1", "e2", "e3", "e4"))
            idle()
            assertEquals(5, player.mediaItemCount)
            // And the buttons follow the app's modes.
            state.value = state.value.copy(shuffle = true)
            idle()
            assertEquals(androidx.media3.session.CommandButton.ICON_SHUFFLE_ON,
                session.mediaButtonPreferences.first { it.sessionCommand?.customAction == CarControls.SHUFFLE }.icon)
            assertTrue(player.shuffleModeEnabled)
        } finally { browser.release() }
    }
}
