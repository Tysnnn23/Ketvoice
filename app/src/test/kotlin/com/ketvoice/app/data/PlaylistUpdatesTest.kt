package com.ketvoice.app.data

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** « Playlists téléchargées à jour » (8.4): which titles are new, what the notification carries, and what it downloads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistUpdatesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun track(n: Int) = Track("yt:track000000$n", "Title $n", "Artist", "Album", 200_000)
    private fun playlist(id: String, vararg tracks: Track, name: String = "Road trip") =
        LocalPlaylist(id, name, tracks.map { PlaylistRules.entry(it) })

    @Before fun setup() {
        AppSettings.languageChosen.value = true
        AppSettings.language.value = AppLanguage.FRENCH.label
        PlaylistStore.resetForTest()
        context.getSharedPreferences("playlist-updates", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun reset() { PlaylistStore.resetForTest() }

    @Test fun aWhollyDownloadedPlaylistIsFollowedWithoutAnnouncingAnything() {
        val road = playlist("p1", track(1), track(2))
        val first = PlaylistUpdates.diff(listOf(road, playlist("p2", track(3), track(4))), setOf(track(1).id, track(2).id, track(3).id), emptyMap())
        assertTrue(first.updates.isEmpty())
        // The half-downloaded one is not followed.
        assertEquals(setOf("p1"), first.known.keys)
        assertEquals(setOf(track(1).id, track(2).id), first.known["p1"])
    }

    @Test fun onlyTheNewTitlesNotOnTheDeviceAreAnnouncedOnce() {
        val known = mapOf("p1" to setOf(track(1).id, track(2).id))
        // The sync brought two titles; one of them is already downloaded (from an album, say) and a local file never counts.
        val grown = playlist("p1", track(1), track(2), track(3), track(4), Track("local:9", "Mine", "Me", ""))
        val check = PlaylistUpdates.diff(listOf(grown), setOf(track(1).id, track(2).id, track(4).id), known)
        assertEquals(listOf(PlaylistUpdates.Update("p1", "Road trip", listOf(track(3).id))), check.updates)
        // The next check does not announce it again, even while it is still not downloaded.
        val again = PlaylistUpdates.diff(listOf(grown), setOf(track(1).id, track(2).id, track(4).id), check.known)
        assertTrue(again.updates.isEmpty())
    }

    @Test fun aPlaylistWithNoDownloadLeftIsNoLongerFollowedAndADeletedOneIsForgotten() {
        val known = mapOf("p1" to setOf(track(1).id), "gone" to setOf(track(5).id))
        val check = PlaylistUpdates.diff(listOf(playlist("p1", track(1), track(2))), emptySet(), known)
        assertTrue(check.updates.isEmpty())
        assertTrue(check.known.isEmpty())
    }

    @Test fun whatIsRememberedSurvivesItsEncoding() {
        val known = mapOf("p1" to setOf("yt:a", "yt:b"), "shared:xyz" to emptySet())
        assertEquals(known, PlaylistUpdates.decode(PlaylistUpdates.encode(known)))
        assertTrue(PlaylistUpdates.decode("not json").isEmpty())
        assertTrue(PlaylistUpdates.decode(null).isEmpty())
    }

    @Test fun theNotificationCarriesThePlaylistAndOnlyItsNewTitles() {
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val update = PlaylistUpdates.Update("p1", "Road trip", listOf(track(3).id, track(4).id))
        val intent = PlaylistUpdates.intent(context, update)
        assertEquals(PlaylistUpdates.ACTION, intent.action)
        assertEquals("p1", intent.getStringExtra(PlaylistUpdates.EXTRA_PLAYLIST))
        assertArrayEquals(arrayOf(track(3).id, track(4).id), intent.getStringArrayExtra(PlaylistUpdates.EXTRA_TRACKS))
        assertEquals("Nouveaux sons dans Road trip", PlaylistUpdates.title(update))
        assertEquals("2 nouveaux titres : touchez pour les télécharger", PlaylistUpdates.text(update))
        assertEquals("1 nouveau titre : touchez pour le télécharger", PlaylistUpdates.text(update.copy(trackIds = listOf("yt:x"))))
        PlaylistUpdates.announce(context, update)
        val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals("Nouveaux sons dans Road trip", posted.extras.getString(android.app.Notification.EXTRA_TITLE))
        val tap = shadowOf(posted.contentIntent).savedIntent
        assertEquals("p1", tap.getStringExtra(PlaylistUpdates.EXTRA_PLAYLIST))
        assertArrayEquals(arrayOf(track(3).id, track(4).id), tap.getStringArrayExtra(PlaylistUpdates.EXTRA_TRACKS))
    }

    @Test fun theListenersOwnAdditionsAreNeverAnnouncedButTheSyncsAre() = runBlocking {
        PlaylistStore.init(context)
        PlaylistUpdates.init(context)
        val id = PlaylistStore.create("Road trip", listOf(PlaylistRules.entry(track(1))))
        PlaylistUpdates.follow(PlaylistStore.lists.value.single { it.id == id })
        // Added by the listener on this phone.
        PlaylistStore.add(id, track(2))
        val here = setOf(track(1).id, track(2).id)
        assertTrue(PlaylistUpdates.check(context, here).isEmpty())
        // Arrived through the account's sync.
        val synced = PlaylistStore.lists.value.single { it.id == id }.let { it.copy(entries = it.entries + PlaylistRules.entry(track(3))) }
        PlaylistStore.replaceFromSync(listOf(synced))
        assertEquals(listOf(track(3).id), PlaylistUpdates.check(context, here).single().trackIds)
        // The touch downloads only that title (Downloader is not running here, so nothing actually starts).
        assertEquals(1, PlaylistUpdates.downloadNew(id, listOf(track(3).id, "yt:not-in-playlist")))
        // Nothing of the listener's downloads is ever touched by a check.
        assertTrue(Downloader.items.value.isEmpty())
    }
}
