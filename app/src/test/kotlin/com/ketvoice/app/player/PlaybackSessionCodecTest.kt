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
}
