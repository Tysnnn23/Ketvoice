package com.ketvoice.app.smartlib

import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.PlaylistEntry
import com.ketvoice.app.data.Track
import org.junit.Assert.*
import org.junit.Test

class MoodAndDuplicatesTest {
    private fun facts(bpm: Float? = null, db: Float? = null, minor: Boolean? = null, genres: Set<Genre> = emptySet(), words: String = "") =
        TrackFacts(genres, null, null, if (bpm == null && db == null && minor == null) null else SoundSignals(bpm, db, minor), words, "a")

    @Test fun ketmixMeasurementsDecideTheMood() {
        val slowQuiet = facts(bpm = 72f, db = -16f, minor = true)
        val fastLoud = facts(bpm = 150f, db = -5f, minor = false)
        assertTrue(MoodScore.score(Mood.Calm, slowQuiet)!! > .8f)
        assertTrue(MoodScore.score(Mood.Calm, fastLoud)!! < .3f)
        assertTrue(MoodScore.score(Mood.Energy, fastLoud)!! > .8f)
        assertTrue(MoodScore.score(Mood.Melancholy, slowQuiet)!! > .8f)
        assertTrue(MoodScore.score(Mood.Happy, slowQuiet)!! < .4f)
    }

    @Test fun halfAndDoubleTemposStillCount() {
        // A 65 bpm reading of a 130 bpm dance title still suits a party a little.
        assertTrue(MoodScore.score(Mood.Party, facts(bpm = 62f, db = -6f))!! > .55f)
    }

    @Test fun styleAndWordsDecideWithoutMeasurements() {
        assertTrue(MoodScore.score(Mood.Focus, facts(genres = setOf(Genre.LoFi)))!! >= SmartFilter.MOOD_YES)
        assertTrue(MoodScore.score(Mood.Party, facts(genres = setOf(Genre.Classical)))!! < SmartFilter.MOOD_NO)
        assertTrue(MoodScore.score(Mood.Melancholy, facts(words = "tears in the rain"))!! >= SmartFilter.MOOD_YES)
        assertNull("Nothing known, nothing said", MoodScore.score(Mood.Calm, facts(words = "untitled")))
    }

    @Test fun moodPlaylistsNeedEnoughTitlesAndRefreshReorders() {
        val pool = (1..24).map { Track("t$it", "Song $it", "Artist ${it % 5}", "") }
        val facts = pool.associate { val slow = it.id.removePrefix("t").toInt() <= 20
            it.id to if (slow) facts(bpm = 75f, db = -15f) else facts(bpm = 150f, db = -6f) }
        val mixes = MoodMixes.build(pool, facts, emptyMap(), seed = 1L)
        val calm = mixes.first { it.mood == Mood.Calm }
        assertEquals(20, calm.tracks.size)
        assertTrue(calm.tracks.all { it.id.removePrefix("t").toInt() <= 20 })
        assertNull("Too few energetic titles for a mix", mixes.firstOrNull { it.mood == Mood.Energy })
        val again = MoodMixes.mix(Mood.Calm, pool, facts, emptyMap(), seed = 99L)!!
        assertEquals(calm.tracks.toSet(), again.tracks.toSet())
        assertNotEquals(calm.tracks, again.tracks)
        assertTrue("No artist twice in a row", calm.tracks.zipWithNext().none { (a, b) -> a.artist == b.artist })
    }

    private fun track(id: String, title: String, artist: String, ms: Long) = Track(id, title, artist, "", durationMs = ms)

    @Test fun titlesAreNormalisedAndVersionsKeptApart() {
        assertEquals("song", Duplicates.baseTitle("Song (feat. Someone) [Official Video]"))
        assertEquals("song", Duplicates.baseTitle("Song - 2011 Remaster"))
        assertEquals("artist song", Duplicates.baseTitle("Artist - Song"))
        assertEquals(listOf("live"), Duplicates.versionOf("Song (Live at Bercy)"))
        assertEquals(listOf("remaster"), Duplicates.versionOf("Song - Remastered 2011"))
        assertEquals("artist", Duplicates.baseArtist("Artist - Topic"))
        assertEquals("artist", Duplicates.baseArtist("Artist, Guest"))
    }

    @Test fun twoDownloadsOfOneRecordingAreDuplicates() {
        val a = SavedCopy(track("yt:1", "Bené", "Werenoi", 201_000), downloadBytes = 8_000_000, format = "Opus")
        val b = SavedCopy(track("um:2", "Bené (Clip officiel)", "Werenoi - Topic", 202_500), downloadBytes = 30_000_000, format = "FLAC")
        val other = SavedCopy(track("um:3", "Bené", "Werenoi", 260_000), downloadBytes = 1)
        val groups = Duplicates.find(listOf(a, b, other))
        val group = groups.single { it.sameVersion }
        assertEquals(setOf("yt:1", "um:2"), group.copies.map { it.track.id }.toSet())
        assertEquals("The larger file is kept by default", "um:2", Duplicates.preferred(group).id)
        val plan = Duplicates.plan(group, Duplicates.preferred(group))
        assertEquals(listOf("yt:1"), plan.extraDownloads.map { it.track.id })
    }

    @Test fun liveAndStudioAreOfferedApartNeverAsExactDuplicates() {
        val studio = SavedCopy(track("1", "Nuit blanche", "Lunis", 215_000))
        val live = SavedCopy(track("2", "Nuit blanche (Live)", "Lunis", 216_000))
        val groups = Duplicates.find(listOf(studio, live))
        assertTrue(groups.none { it.sameVersion })
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().copies.size)
    }

    @Test fun aPlaylistHoldingATitleTwiceIsMergedWithoutTouchingDownloads() {
        val song = track("1", "Bené", "Werenoi", 201_000)
        val list = LocalPlaylist("p", "Ma playlist", listOf(PlaylistEntry("e1", "Bené", "Werenoi", track = song),
            PlaylistEntry("e2", "Bené", "Werenoi", track = song.copy(id = "2")), PlaylistEntry("e3", "Autre", "X", track = track("3", "Autre", "X", 100_000))))
        val download = SavedCopy(song, downloadBytes = 5)
        val group = Duplicates.find(Duplicates.copies(listOf(download), listOf(list))).single()
        val plan = Duplicates.plan(group, Duplicates.preferred(group))
        assertEquals("1", plan.keep.id)
        assertEquals(listOf("p" to "e2"), plan.removeEntries)
        assertTrue(plan.repoint.isEmpty())
        assertTrue(plan.extraDownloads.isEmpty())
    }

    @Test fun reviewedGroupsStayKeptAndDistantLengthsAreDifferentRecordings() {
        val a = SavedCopy(track("1", "Intro", "X", 60_000), downloadBytes = 1)
        val b = SavedCopy(track("2", "Intro", "X", 180_000), downloadBytes = 1)
        assertTrue(Duplicates.find(listOf(a, b)).isEmpty())
        val c = SavedCopy(track("3", "Intro", "X", 61_000), downloadBytes = 1)
        val key = Duplicates.find(listOf(a, c)).single().key
        assertTrue(Duplicates.find(listOf(a, c), kept = setOf(key)).isEmpty())
    }
}
