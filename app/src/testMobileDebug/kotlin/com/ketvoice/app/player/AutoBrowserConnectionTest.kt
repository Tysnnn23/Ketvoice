@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.player

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.ListenableFuture
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
 * A media browser — what Android Auto is to Ketvoice — connects to the real playback service,
 * browses its library, searches it and asks it to play, under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoBrowserConnectionTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val source = FakeAutoSource()
    private val playback = FakeAutoPlayback()
    private lateinit var service: ServiceController<PlaybackService>
    private val component = ComponentName(app, PlaybackService::class.java)

    @Before fun start() {
        AutoLibraryCallback.testSource = source
        AutoLibraryCallback.testPlayback = playback
        service = Robolectric.buildService(PlaybackService::class.java).create()
    }

    @After fun stop() {
        runCatching { service.destroy() }
        AutoLibraryCallback.testSource = null
        AutoLibraryCallback.testPlayback = null
    }

    private fun <T> await(future: ListenableFuture<T>): T {
        val until = System.currentTimeMillis() + 15_000
        while (!future.isDone && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
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
        val token = SessionToken(app, component)
        // The manifest declares the library: browsers see a library service, not a plain session.
        assertEquals(SessionToken.TYPE_LIBRARY_SERVICE, token.type)
        return await(MediaBrowser.Builder(app, token).buildAsync())
    }

    @Test fun aBrowserBrowsesSearchesAndPlaysThroughThePhonesQueue() {
        source.favorites = listOf(track("f1", "Hello", "Adele"), track("f2", "Skyfall", "Adele"))
        source.catalogue = { q -> listOf(track("c1", "Hello", "Lionel Richie"), track("c2", "Skyfall", "Adele")).filter { it.title.contains(q, true) } }
        val browser = connect()
        try {
            // The app's own controllers keep the service's commands (quality upgrade, equalizer…).
            assertTrue(browser.isSessionCommandAvailable(SessionCommand(PlaybackService.CMD_UPGRADE, Bundle.EMPTY)))
            assertTrue(browser.isSessionCommandAvailable(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH))

            val root = await(browser.getLibraryRoot(null))
            assertEquals(LibraryResult.RESULT_SUCCESS, root.resultCode)
            assertEquals(AutoIds.ROOT, root.value!!.mediaId)
            assertEquals(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
                root.params!!.extras.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))

            val tabs = await(browser.getChildren(AutoIds.ROOT, 0, 10, null)).value!!
            assertEquals(listOf(AutoIds.HOME, AutoIds.FAVORITES, AutoIds.PLAYLISTS, AutoIds.DOWNLOADS), tabs.map { it.mediaId })

            val favorites = await(browser.getChildren(AutoIds.FAVORITES, 0, 1, null)).value!!
            assertEquals(listOf(AutoIds.track(AutoIds.FAVORITES, "f1")), favorites.map { it.mediaId })
            assertEquals("Hello", favorites.single().mediaMetadata.title.toString())
            assertEquals(1, await(browser.getChildren(AutoIds.FAVORITES, 1, 1, null)).value!!.size)
            assertEquals(LibraryResult.RESULT_SUCCESS, await(browser.getChildren(AutoIds.PLAYLISTS, 0, 10, null)).resultCode)
            assertTrue(await(browser.getChildren(AutoIds.PLAYLISTS, 0, 10, null)).value!!.isEmpty())
            assertNotEquals(LibraryResult.RESULT_SUCCESS, await(browser.getChildren("ketvoice.auto.nothing", 0, 10, null)).resultCode)
            assertEquals("Skyfall", await(browser.getItem(AutoIds.track(AutoIds.FAVORITES, "f2"))).value!!.mediaMetadata.title.toString())

            assertEquals(LibraryResult.RESULT_SUCCESS, await(browser.search("Hello", null)).resultCode)
            idle()
            val found = await(browser.getSearchResult("Hello", 0, 10, null)).value!!
            assertEquals(listOf(AutoIds.track(AutoIds.search("Hello"), "c1")), found.map { it.mediaId })

            // The car sends only an id: the phone's queue plays the whole list, the session sets nothing on the engine.
            browser.setMediaItem(MediaItem.Builder().setMediaId(AutoIds.track(AutoIds.FAVORITES, "f2")).build())
            browser.prepare(); browser.play()
            idle()
            assertEquals(listOf("f1", "f2") to 1, playback.played.single())
            // (The browser masks its own copy; the service's player is what counts.)
            assertEquals(0, service.get().sessions.single().player.mediaItemCount)

            // « Hey Google, joue Skyfall sur Ketvoice »: an item carrying only the spoken query.
            browser.setMediaItem(MediaItem.Builder().setRequestMetadata(
                MediaItem.RequestMetadata.Builder().setSearchQuery("Skyfall").build()).build())
            idle()
            assertEquals(listOf("c2") to 0, playback.played.last())
        } finally { browser.release() }
    }

    @Test fun theAppsOwnItemsStillGoStraightToThePlayer() {
        val browser = connect()
        try {
            val file = java.io.File.createTempFile("auto", ".wav").apply { deleteOnExit() }
            browser.setMediaItem(MediaItem.Builder().setMediaId("own").setUri(android.net.Uri.fromFile(file)).build())
            idle()
            assertEquals(1, service.get().sessions.single().player.mediaItemCount)
            assertEquals("own", service.get().sessions.single().player.currentMediaItem?.mediaId)
            // A bare address from another app is not a car request: it stays unsupported, as before.
            browser.setMediaItem(MediaItem.Builder().setRequestMetadata(MediaItem.RequestMetadata.Builder()
                .setMediaUri(android.net.Uri.parse("https://example.com/a.mp3")).build()).build())
            idle()
            assertTrue(playback.played.isEmpty() && playback.shuffled.isEmpty())
            assertEquals(0, playback.resumed)
            assertEquals("own", service.get().sessions.single().player.currentMediaItem?.mediaId)
        } finally { browser.release() }
    }
}
