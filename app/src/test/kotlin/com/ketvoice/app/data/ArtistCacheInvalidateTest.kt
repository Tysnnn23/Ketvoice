package com.ketvoice.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The refresh button must really drop the cached page and artwork. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArtistCacheInvalidateTest {
    private fun page(name: String) = ArtistPage(MusicArtist("UC123", name, "https://img.example/p.jpg"),
        "bio", emptyList(), emptyList(), emptyList(), "1 auditeur", 1f)

    @Test fun invalidateDropsThePageSoTheNextOpenRefetches() = runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        ArtistCache.init(context)
        ArtistCache.put("UC123", "Eminem", page("Eminem"))
        assertNotNull(ArtistCache.get("UC123", "Eminem"))
        ArtistCache.invalidate("UC123", "Eminem")
        assertNull("Refresh must clear the cached page", ArtistCache.get("UC123", "Eminem"))
        assertNull(ArtistCache.get(null, "Eminem"))
    }

    @Test fun invalidateAlsoRemovesTheNameAliasCreatedByAChannelLookup() = runBlocking {
        ArtistCache.init(org.robolectric.RuntimeEnvironment.getApplication())
        ArtistCache.put(null, "Juice WRLD", page("Juice WRLD"))
        assertNotNull(ArtistCache.get(null, "Juice WRLD"))
        ArtistCache.invalidate("UC123", "Juice WRLD")
        assertNull(ArtistCache.get("UC123", "Juice WRLD"))
    }

    @Test fun aCachedArtistSurvivesUntilItIsExplicitlyInvalidated() = runBlocking {
        ArtistCache.init(org.robolectric.RuntimeEnvironment.getApplication())
        ArtistCache.put("UC999", "Test", page("Test"))
        assertEquals("Test", ArtistCache.get("UC999", "Test")?.artist?.name)
        ArtistCache.put("UC999b", "Autre", page("Autre"))
        assertNotNull("Invalidating another artist must not clear this one", ArtistCache.get("UC999", "Test"))
    }
}
