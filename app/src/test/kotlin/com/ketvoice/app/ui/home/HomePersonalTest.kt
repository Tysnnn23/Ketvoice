package com.ketvoice.app.ui.home

import com.ketvoice.app.data.MixCard
import com.ketvoice.app.data.MixKind
import org.junit.Assert.*
import org.junit.Test

class HomePersonalTest {
    @Test fun greetingFollowsTheTimeOfDay() {
        assertEquals("Bonne nuit", homeGreeting(3))
        assertEquals("Bonjour", homeGreeting(5))
        assertEquals("Bonjour", homeGreeting(11))
        assertEquals("Bon après-midi", homeGreeting(12))
        assertEquals("Bonsoir", homeGreeting(18))
        assertEquals("Bonsoir", homeGreeting(22))
        assertEquals("Bonne nuit", homeGreeting(23))
        assertEquals("Bonne nuit", homeGreeting(24))
    }

    @Test fun theFirstShelfAlwaysLeadsAndTheRestTakesTurns() {
        val orders = (1L..40L).map { homeBlockOrder(it, shelfCount = 3, hasAlbums = true) }
        orders.forEach { order ->
            assertEquals(HomeBlock.Shelf0, order.first())
            assertEquals(setOf(HomeBlock.Shelf0, HomeBlock.Shelf1, HomeBlock.Shelf2, HomeBlock.Albums), order.toSet())
            assertEquals(4, order.size)
        }
        assertTrue("Launches differ", orders.toSet().size > 1)
        assertEquals(homeBlockOrder(9, 3, true), homeBlockOrder(9, 3, true))
    }

    @Test fun missingBlocksAreNeverListed() {
        assertEquals(listOf(HomeBlock.Albums), homeBlockOrder(1, 0, true))
        assertEquals(emptyList<HomeBlock>(), homeBlockOrder(1, 0, false))
        assertEquals(listOf(HomeBlock.Shelf0), homeBlockOrder(1, 1, false))
        assertFalse(HomeBlock.Albums in homeBlockOrder(4, 3, false))
    }

    @Test fun mixTitlesNameTheirArtist() {
        assertEquals("Mix Damso", mixTitle(MixCard("artist:Damso", MixKind.Artist, "Damso", emptyList())))
        assertEquals("Dans le style de PNL", mixTitle(MixCard("style:PNL", MixKind.Style, "PNL", emptyList())))
        assertEquals("En boucle", mixTitle(MixCard("repeat", MixKind.OnRepeat, null, emptyList())))
        assertEquals("À redécouvrir", mixTitle(MixCard("rediscover", MixKind.Rediscover, null, emptyList())))
    }

    // 7.0 « Pour toi »
    private fun mood(m: com.ketvoice.app.smartlib.Mood, n: Int = 3) = com.ketvoice.app.smartlib.MoodMix(m,
        (1..n).map { com.ketvoice.app.data.Track("${m.name}$it", "T$it", "A$it", "Al") })

    @Test fun theAmbiancesFollowTheHour() {
        val all = com.ketvoice.app.smartlib.Mood.values().map { mood(it) }
        assertEquals(com.ketvoice.app.smartlib.Mood.Energy, moodsForHour(8, all).first().mood)
        assertEquals(com.ketvoice.app.smartlib.Mood.Focus, moodsForHour(14, all).first().mood)
        assertEquals(com.ketvoice.app.smartlib.Mood.Party, moodsForHour(20, all).first().mood)
        assertEquals(com.ketvoice.app.smartlib.Mood.Calm, moodsForHour(2, all).first().mood)
        assertEquals("every ambiance stays", all.size, moodsForHour(2, all).size)
        assertTrue("an empty ambiance is not shown", moodsForHour(8, listOf(mood(com.ketvoice.app.smartlib.Mood.Energy, 0))).isEmpty())
    }

    @Test fun theNewReleasesWatchTheFollowedArtistsFirst() {
        val followed = listOf(com.ketvoice.app.data.ArtistPrefs.Entry("Nova", "UC1"), com.ketvoice.app.data.ArtistPrefs.Entry("Alpha"))
        val top = listOf(com.ketvoice.app.data.PersonalMixes.ArtistTaste("alpha", 9, emptyList()),
            com.ketvoice.app.data.PersonalMixes.ArtistTaste("Beta", 4, emptyList()),
            com.ketvoice.app.data.PersonalMixes.ArtistTaste("Once", 1, emptyList()))
        val watched = com.ketvoice.app.data.HomeReleases.watched(followed, top)
        assertEquals(listOf("Nova", "Alpha", "Beta"), watched.map { it.name })
        assertEquals("UC1", watched.first().catalogId)
    }
}
