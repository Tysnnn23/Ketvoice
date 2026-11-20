package com.ketvoice.app.player

import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.Recordings
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** The radio's rules (6.1): no title twice in a session, artists spaced, fresh titles and favourites in a set ratio. */
class RadioMixTest {
    private fun t(id: String, title: String, artist: String) = Track(id, title, artist, "")
    private fun artist(t: Track) = MediaOverrides.normalize(MediaOverrides.mainArtist(t.artist))

    /** Twelve artists like the seedTrack, three titles each. */
    private val fresh = (1..12).flatMap { a -> (1..3).map { n -> t("f$a-$n", "Fresh $a.$n", "Other $a") } }
    private val own = (1..8).map { t("o$it", "Own $it", "Werenoi") }
    private val familiar = (1..6).map { t("k$it", "Loved $it", "Friend ${it % 3}") }
    private val seedTrack = t("seedTrack", "Own 1", "Werenoi")

    @Test fun nothingComesBackDuringASession() {
        val memory = RadioMemory(RadioSeed.Title(seedTrack)).apply { remember(seedTrack) }
        val random = Random(4)
        val played = mutableListOf(seedTrack)
        repeat(6) { played += RadioMix.pick(fresh, own, familiar, memory, { true }, random) }
        assertEquals("no id twice", played.size, played.map { it.id }.toSet().size)
        assertEquals("no recording twice", played.size, played.map(Recordings::of).toSet().size)
        assertFalse("the seedTrack's own title never comes back", played.drop(1).any { it.title == "Own 1" })
        // Once every title was played, the radio has nothing left rather than repeating.
        assertTrue(RadioMix.pick(fresh, own, familiar, memory, { true }, random).isEmpty())
    }

    @Test fun anotherVersionOfAPlayedRecordingIsNotNew() {
        val memory = RadioMemory(RadioSeed.Title(seedTrack)).apply { remember(t("a", "Skyline", "Echo")) }
        val versions = listOf(t("b", "Skyline (Remastered)", "Echo"), t("c", "Skyline - Live", "Echo feat. Guest"), t("d", "Other", "Nova"))
        assertEquals(listOf("d"), RadioMix.pick(versions, emptyList(), emptyList(), memory, { true }, Random(1)).map { it.id })
    }

    @Test fun anArtistComesBackOnlyAfterOthers() {
        val memory = RadioMemory(RadioSeed.Artist("Werenoi", null)).apply { remember(seedTrack) }
        val batch = RadioMix.pick(fresh, own, familiar, memory, { true }, Random(9), batch = 30)
        val all = listOf(seedTrack) + batch
        all.forEachIndexed { i, track ->
            val before = all.subList(maxOf(0, i - RadioMix.ARTIST_GAP), i).map(::artist)
            assertFalse("${track.title} at $i after $before", artist(track) in before)
        }
    }

    @Test fun freshTitlesTheSeedArtistAndFavouritesInARatio() {
        val memory = RadioMemory(RadioSeed.Artist("Werenoi", null)).apply { remember(seedTrack) }
        val batch = RadioMix.pick(fresh, own, familiar, memory, { true }, Random(2), batch = 24)
        val loved = batch.count { it.id.startsWith("k") }
        val mine = batch.count { it.artist == "Werenoi" }
        val other = batch.size - loved - mine
        assertEquals(24, batch.size)
        assertTrue("about one favourite in four: $loved", loved in 4..7)
        assertTrue("some of the seedTrack's artist: $mine", mine in 3..7)
        assertTrue("mostly fresh titles: $other", other > loved + mine)
    }

    @Test fun neverABlockedArtistAndNoFavouritesOutsideTheCircle() {
        val memory = RadioMemory(RadioSeed.Title(seedTrack)).apply { remember(seedTrack) }
        val batch = RadioMix.pick(fresh, own, emptyList(), memory, { it.artist != "Other 3" }, Random(5))
        assertEquals(RadioMix.BATCH, batch.size)
        assertTrue(batch.none { it.artist == "Other 3" })
        assertTrue(batch.none { it.id.startsWith("k") })
    }

    @Test fun aRadioOfOneArtistStillNeverRepeatsTitles() {
        // Only the seedTrack's artist is known: its titles follow each other, since nothing else can space them, but never twice.
        val memory = RadioMemory(RadioSeed.Artist("Werenoi", null)).apply { remember(seedTrack) }
        val batch = RadioMix.pick(emptyList(), own, emptyList(), memory, { true }, Random(3))
        assertEquals(own.size - 1, batch.size)
        assertTrue(batch.none { it.title == "Own 1" })
        assertEquals(batch.size, batch.map { it.id }.toSet().size)
    }
}
