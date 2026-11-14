package com.ketvoice.app.player

import com.ketvoice.app.data.Track
import org.junit.Assert.*
import org.junit.Test

class PlaybackSessionCodecTest {
    @Test fun queueWithDuplicatesAndLocalUrisRestoresPausedWithoutSignedUrls() {
        val online = Track("tidal:1", "Song", "Artist", "Album", durationMs = 180_000,
            streamUrl = "https://audio.example/file?token=secret", downloadUrl = "https://audio.example/download?sig=secret",
            coverUrl = "https://images.example/art?token=secret", canvasUrl = "https://video.example/?token=secret",
            sourceOverride = "cs:lossless")
        val local = Track("local:42", "Local", "Artist", "Album", streamUrl = "content://media/external/audio/42")
        val state = PlayerUiState(queue = listOf(online, local, online), queueEntryIds = listOf("first", "second", "third"),
            index = 2, shuffle = true, repeatOne = true, autoplay = false, playWhenReady = true)
        val bytes = PlaybackSessionCodec.encode(state, 25_000)
        assertFalse(String(bytes).contains("secret"))
        val snapshot = PlaybackSessionCodec.decode(bytes)!!
        assertEquals(listOf("first", "second", "third"), snapshot.entryIds)
        assertEquals(listOf("tidal:1", "local:42", "tidal:1"), snapshot.queue.map { it.id })
        assertNull(snapshot.queue.first().streamUrl)
        assertEquals("cs:lossless", snapshot.queue.first().sourceOverride)
        assertEquals("content://media/external/audio/42", snapshot.queue[1].streamUrl)
        assertEquals(2, snapshot.index)
        assertEquals(25_000L, snapshot.positionMs)
        val restored = snapshot.toPlayerState()
        assertFalse(restored.playWhenReady)
        assertFalse(restored.isPlaying)
        assertTrue(restored.shuffle)
        assertTrue(restored.repeatOne)
        assertFalse(restored.autoplay)
    }

    @Test fun emptyQueueDoesNotResurrectAnOldTrack() {
        assertNull(PlaybackSessionCodec.decode(PlaybackSessionCodec.encode(PlayerUiState(), 0)))
    }

    @Test fun foreignVersionAndOutOfRangeIndexAreRejected() {
        assertThrows(Exception::class.java) { PlaybackSessionCodec.decode("""{"version":99,"queue":[]}""".toByteArray()) }
        val track = Track("test", "Song", "Artist", "Album")
        val bytes = PlaybackSessionCodec.encode(PlayerUiState(queue = listOf(track), index = 5), 0)
        assertThrows(Exception::class.java) { PlaybackSessionCodec.decode(bytes) }
    }

    @Test fun repeatAllAndTheOrderBeforeShuffleSurviveARestart() {
        val tracks = listOf("a", "b", "c").map { Track(it, "Song $it", "Artist", "Album") }
        val state = PlayerUiState(queue = tracks, queueEntryIds = listOf("k2", "k0", "k1"), index = 0, shuffle = true,
            repeatAll = true, linearOrder = listOf("k0", "k1", "k2"))
        val snapshot = PlaybackSessionCodec.decode(PlaybackSessionCodec.encode(state, 0))!!
        assertTrue(snapshot.repeatAll)
        assertEquals(listOf("k0", "k1", "k2"), snapshot.linearOrder)
        val restored = snapshot.toPlayerState()
        assertTrue(restored.repeatAll)
        assertEquals(listOf("k0", "k1", "k2"), restored.linearOrder)
    }

    @Test fun anOrderThatDoesNotNameTheSavedEntriesIsDropped() {
        val tracks = listOf("a", "b").map { Track(it, "Song $it", "Artist", "Album") }
        val stale = PlayerUiState(queue = tracks, queueEntryIds = listOf("k0", "k1"), index = 0, shuffle = true,
            linearOrder = listOf("k0", "other"))
        assertTrue(PlaybackSessionCodec.decode(PlaybackSessionCodec.encode(stale, 0))!!.linearOrder.isEmpty())
        // Without shuffle there is nothing to restore, so nothing is written.
        val linear = PlayerUiState(queue = tracks, queueEntryIds = listOf("k0", "k1"), index = 0, shuffle = false,
            linearOrder = listOf("k0", "k1"))
        assertFalse(String(PlaybackSessionCodec.encode(linear, 0)).contains("linearOrder"))
    }

    @Test fun sessionsSavedBeforeRepeatAllStillLoad() {
        val legacy = """{"version":1,"queue":[{"track":{"id":"a","title":"Song","artist":"Artist","album":"Album"},"entryId":"k0"}],
            "index":0,"positionMs":10,"shuffle":true,"repeatOne":true,"autoplay":false}""".toByteArray()
        val snapshot = PlaybackSessionCodec.decode(legacy)!!
        assertFalse(snapshot.repeatAll)
        assertTrue(snapshot.repeatOne)
        assertTrue(snapshot.linearOrder.isEmpty())
    }

    @Test fun repeatOneWinsOverRepeatAll() {
        val track = Track("a", "Song", "Artist", "Album")
        val both = PlayerUiState(queue = listOf(track), queueEntryIds = listOf("k0"), index = 0, repeatOne = true, repeatAll = true)
        val snapshot = PlaybackSessionCodec.decode(PlaybackSessionCodec.encode(both, 0))!!
        assertTrue(snapshot.repeatOne)
        assertFalse(snapshot.repeatAll)
    }
}
