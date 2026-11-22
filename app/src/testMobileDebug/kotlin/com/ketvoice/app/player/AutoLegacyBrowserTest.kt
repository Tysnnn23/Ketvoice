@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ketvoice.app.player

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import androidx.test.core.app.ApplicationProvider
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
 * Android Auto speaks the older MediaBrowserService protocol: the same library through
 * MediaBrowserCompat, with Auto's root hints, subscriptions and a search.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoLegacyBrowserTest {
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

    private fun idleUntil(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 15_000
        while (!done() && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue("timed out: $what", done())
    }

    @Test fun aLegacyBrowserConnectsBrowsesAndSearches() {
        source.favorites = listOf(track("f1", "Hello", "Adele"), track("f2", "Skyfall", "Adele"))
        source.catalogue = { q -> listOf(track("c1", "Hello", "Lionel Richie"), track("c2", "Rolling in the deep", "Adele")).filter { it.title.contains(q, true) } }
        val binder = service.get().onBind(Intent("android.media.browse.MediaBrowserService").setComponent(component))
        shadowOf(app).setComponentNameAndServiceForBindService(component, binder)

        var connected = false
        var failed = false
        lateinit var browser: MediaBrowserCompat
        val hints = Bundle().apply { putInt("androidx.media.MediaBrowserCompat.Extras.KEY_ROOT_CHILDREN_LIMIT", 4) }
        browser = MediaBrowserCompat(app, component, object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() { connected = true }
            override fun onConnectionFailed() { failed = true }
        }, hints)
        browser.connect()
        idleUntil("connection") { connected || failed }
        assertTrue("connection failed", connected)
        try {
            assertEquals(AutoIds.ROOT, browser.root)
            // Search is announced to the car, and the default looks (grid folders, list titles) too.
            assertTrue(browser.extras!!.getBoolean("android.media.browse.SEARCH_SUPPORTED"))
            assertEquals(2 /* grid */, browser.extras!!.getInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"))

            var tabs: List<MediaBrowserCompat.MediaItem>? = null
            browser.subscribe(browser.root, object : MediaBrowserCompat.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowserCompat.MediaItem>) { tabs = children }
            })
            idleUntil("tabs") { tabs != null }
            assertEquals(listOf("Accueil", "Bibliothèque", "Radio", "Téléchargements"), tabs!!.map { it.description.title.toString() })
            assertTrue(tabs!!.all { it.isBrowsable && !it.isPlayable })
            // Tab icons reach the car as resource addresses of the app.
            assertTrue(tabs!!.all { it.description.iconUri.toString().startsWith("android.resource://${app.packageName}/drawable/") })

            var favorites: List<MediaBrowserCompat.MediaItem>? = null
            browser.subscribe(AutoIds.FAVORITES, object : MediaBrowserCompat.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowserCompat.MediaItem>) { favorites = children }
            })
            idleUntil("favorites") { favorites != null }
            assertEquals(listOf("Tout lire", "Lecture aléatoire", "Hello", "Skyfall"), favorites!!.map { it.description.title.toString() })
            assertTrue(favorites!!.all { it.isPlayable })

            var results: List<MediaBrowserCompat.MediaItem>? = null
            browser.search("Hello", null, object : MediaBrowserCompat.SearchCallback() {
                override fun onSearchResult(query: String, extras: Bundle?, items: MutableList<MediaBrowserCompat.MediaItem>) { results = items }
                override fun onError(query: String, extras: Bundle?) { results = emptyList() }
            })
            idleUntil("search") { results != null }
            assertEquals(listOf(AutoIds.track(AutoIds.search("Hello"), "c1")), results!!.map { it.mediaId })

            // The car's transport controls (play from media id / from search) travel through the system's media
            // session service, which Robolectric does not run: AutoBrowserConnectionTest covers the same session path.
            assertNotNull(browser.sessionToken)
        } finally { browser.disconnect() }
    }
}
