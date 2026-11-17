package com.ketvoice.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReleaseWatchHostTest {
    private fun page(vararg ids: String) = ArtistPage(MusicArtist("UCx", "Artiste", null), "", emptyList(),
        ids.map { Album(it, "Titre $it", "Artiste", "Single · 2026") }, emptyList())

    @Test fun theFirstPassOnlyRemembersAndTheNextOneAnnouncesTheNewRelease() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("release-watch", Context.MODE_PRIVATE).edit().clear().commit()
        val artists = listOf(ArtistPrefs.Entry("Artiste", "UCx"), ArtistPrefs.Entry("Sans identifiant"))
        var current = page("a", "b")
        val first = ReleaseWatch.check(context, artists, { current }, 2026, pause = 0)
        assertTrue(first.isEmpty())
        current = page("c", "a", "b")
        val second = ReleaseWatch.check(context, artists, { current }, 2026, pause = 0)
        assertEquals(listOf("c"), second.map { it.album.id })
        assertEquals("UCx", second.single().artistId)
        assertTrue(ReleaseWatch.check(context, artists, { current }, 2026, pause = 0).isEmpty())
    }

    @Test fun anArtistWhosePageFailsIsSkippedWithoutLosingWhatWasKnown() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("release-watch", Context.MODE_PRIVATE).edit().clear().commit()
        val artists = listOf(ArtistPrefs.Entry("Artiste", "UCx"))
        ReleaseWatch.check(context, artists, { page("a") }, 2026, pause = 0)
        assertTrue(ReleaseWatch.check(context, artists, { null }, 2026, pause = 0).isEmpty())
        val after = ReleaseWatch.check(context, artists, { page("b", "a") }, 2026, pause = 0)
        assertEquals(listOf("b"), after.map { it.album.id })
    }

    @Test fun atMostThreeAreAnnouncedPerPass() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("release-watch", Context.MODE_PRIVATE).edit().clear().commit()
        val artists = listOf(ArtistPrefs.Entry("Artiste", "UCx"))
        ReleaseWatch.check(context, artists, { page("a") }, 2026, pause = 0)
        val many = ReleaseWatch.check(context, artists, { page("n1", "n2", "n3", "n4", "a") }, 2026, pause = 0)
        assertEquals(3, many.size)
    }
}
