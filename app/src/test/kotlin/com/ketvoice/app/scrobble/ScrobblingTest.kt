package com.ketvoice.app.scrobble

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Scrobbling: the rules, the real listening time, exclusions and the offline queue. */
class ScrobblingTest {
    private val song = ScrobbleTrack("t:1", "Title", "Artist", "Album", 200_000)

    /** Plays [track] from [from] for [ms] in 250 ms steps; returns every event. */
    private fun ScrobbleTracker.play(track: ScrobbleTrack, from: Long, ms: Long, clock: LongArray, excluded: Boolean = false): List<ScrobbleEvent> {
        val out = ArrayList<ScrobbleEvent>()
        var pos = from
        val end = from + ms
        while (pos <= end) {
            out += update(track, true, pos, clock[0], excluded)
            pos += 250; clock[0] += 250
        }
        return out
    }

    @Test fun thresholdIsHalfTheTitleOrFourMinutes() {
        assertEquals(100_000, ScrobbleRules.threshold(200_000))
        assertEquals(240_000, ScrobbleRules.threshold(600_000))
        assertEquals(240_000, ScrobbleRules.threshold(0))
        assertFalse(ScrobbleRules.eligible(song.copy(durationMs = 29_000)))
        assertTrue(ScrobbleRules.eligible(song.copy(durationMs = 30_000)))
        assertTrue(ScrobbleRules.eligible(song.copy(durationMs = 0)))
        assertFalse(ScrobbleRules.eligible(song.copy(artist = " ")))
    }

    @Test fun nowPlayingAtTheStartThenOneScrobbleAfterHalf() {
        val tracker = ScrobbleTracker()
        val clock = longArrayOf(1_000_000)
        val first = tracker.play(song, 0, 1_000, clock)
        assertEquals(listOf(ScrobbleEvent.NowPlaying(song)), first)
        val rest = tracker.play(song, 1_250, 120_000, clock)
        val listened = rest.filterIsInstance<ScrobbleEvent.Listened>()
        assertEquals(1, listened.size)
        assertEquals(1_000_000, listened[0].scrobble.startedAt)
        assertTrue(rest.none { it is ScrobbleEvent.NowPlaying })
    }

    @Test fun seekingForwardIsNotListening() {
        val tracker = ScrobbleTracker()
        val clock = longArrayOf(0)
        tracker.play(song, 0, 10_000, clock)
        // Jump to 3 min and listen 10 s: about 20 s heard, far from the 100 s needed.
        val events = tracker.play(song, 180_000, 10_000, clock)
        assertTrue(events.none { it is ScrobbleEvent.Listened })
        assertTrue(tracker.heardMs in 18_000..23_000)
    }

    @Test fun pausedTimeDoesNotCount() {
        val tracker = ScrobbleTracker()
        tracker.update(song, true, 0, 0)
        tracker.update(song, false, 50_000, 50_000)
        tracker.update(song, false, 50_000, 500_000)
        assertTrue(tracker.heardMs <= 51_500)
    }

    @Test fun shortTitlesAndExcludedPlaysAreNeverScrobbled() {
        val clock = longArrayOf(0)
        val short = song.copy(id = "t:short", durationMs = 20_000)
        assertTrue(ScrobbleTracker().play(short, 0, 20_000, clock).isEmpty())
        // The lyrics editor or a Ketmix preview at any moment of the play leaves it out, even listened to the end.
        val tracker = ScrobbleTracker()
        tracker.play(song, 0, 5_000, clock, excluded = true)
        assertTrue(tracker.play(song, 5_250, 190_000, clock).none { it is ScrobbleEvent.Listened })
    }

    @Test fun aNewTitleAndARepeatAreNewPlays() {
        val tracker = ScrobbleTracker()
        val clock = longArrayOf(0)
        assertEquals(1, tracker.play(song, 0, 110_000, clock).count { it is ScrobbleEvent.Listened })
        // Repeat one: the same title back to its start.
        assertEquals(1, tracker.play(song, 0, 110_000, clock).count { it is ScrobbleEvent.Listened })
        val next = song.copy(id = "t:2")
        assertTrue(tracker.play(next, 0, 500, clock).first() is ScrobbleEvent.NowPlaying)
    }

    @Test fun theQueueKeepsWhatWasNotSentAcrossRestarts() {
        var disk: String? = null
        val queue = ScrobbleQueue({ disk }, { disk = it }, max = 3)
        val now = 10L * 24 * 3600_000L
        (1..4).forEach { queue.add(Scrobble(song.copy(id = "t:$it"), now - it * 1_000L)) }
        assertEquals(3, queue.size)
        queue.add(Scrobble(song.copy(id = "t:4"), now - 4_000L)) // the same listen twice is kept once
        assertEquals(3, queue.size)
        val reopened = ScrobbleQueue({ disk }, { disk = it }, max = 3)
        val first = reopened.peek(2, now)
        assertEquals(listOf("t:2", "t:3"), first.map { it.track.id })
        reopened.remove(first)
        assertEquals(listOf("t:4"), ScrobbleQueue({ disk }, { disk = it }).peek(10, now).map { it.track.id })
    }

    @Test fun listensTooOldForTheServiceLeaveTheQueue() {
        var disk: String? = null
        val queue = ScrobbleQueue({ disk }, { disk = it }, maxAgeMs = 1_000)
        queue.add(Scrobble(song, 1_000))
        assertTrue(queue.peek(5, 5_000).isEmpty())
        assertEquals(0, queue.size)
    }

    @Test fun retriesBackOff() {
        assertEquals(0, ScrobbleRetry.delayMs(0))
        assertEquals(30_000, ScrobbleRetry.delayMs(1))
        assertEquals(60_000, ScrobbleRetry.delayMs(2))
        assertEquals(30 * 60_000L, ScrobbleRetry.delayMs(20))
    }

    @Test fun listenBrainzBodies() {
        val now = ListenBrainzBody.playingNow(song)
        assertEquals("playing_now", now.getString("listen_type"))
        assertFalse(now.getJSONArray("payload").getJSONObject(0).has("listened_at"))
        val one = ListenBrainzBody.listens(listOf(Scrobble(song, 1_700_000_000_123)))
        assertEquals("single", one.getString("listen_type"))
        val listen = one.getJSONArray("payload").getJSONObject(0)
        assertEquals(1_700_000_000L, listen.getLong("listened_at"))
        val meta = listen.getJSONObject("track_metadata")
        assertEquals("Artist", meta.getString("artist_name"))
        assertEquals("Album", meta.getString("release_name"))
        assertEquals(200_000, meta.getJSONObject("additional_info").getLong("duration_ms"))
        assertEquals("import", ListenBrainzBody.listens(listOf(Scrobble(song, 1), Scrobble(song, 2))).getString("listen_type"))
        assertNull(ScrobbleQueue.parse(JSONObject().put("id", "t:1")))
    }
}
