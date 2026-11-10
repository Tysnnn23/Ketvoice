package com.ketvoice.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueTest {

    private fun track(id: String = "kv:um:a1", title: String = "Pétunias") = Track(
        id = id,
        title = title,
        artist = "Werenoi",
        album = "Pétunias",
        durationMs = 184_000L,
        streamUrl = "https://example.test/a.m3u8",
        downloadUrl = "https://example.test/a.mp3",
        canvasUrl = "https://example.test/a.mp4",
        lyricsProvider = "LRCLIB",
        explicit = true,
        coverUrl = "https://example.test/a.jpg",
        addon = "um",
        addonId = "a1",
        videoId = "vid",
        spotifyId = "spid",
        isrc = "FR1234567890",
        youtubeCatalogVerified = true,
        albumId = "alb",
        artistId = "art"
    )

    @Test fun roundTripKeepsEveryTrackField() {
        val entry = QueuedDownload(track(), QueueState.Paused, addedAt = 1_700_000_000_000L)
        val decoded = DownloadQueue.decode(DownloadQueue.encode(listOf(entry)))
        assertEquals(listOf(entry), decoded)
    }

    @Test fun roundTripKeepsNullableFieldsNull() {
        val bare = Track(id = "x", title = "T", artist = "A", album = "")
        val decoded = DownloadQueue.decode(DownloadQueue.encode(listOf(QueuedDownload(bare))))
        assertEquals(listOf(bare), decoded.map { it.track })
        assertNull(decoded.single().track.streamUrl)
        assertNull(decoded.single().track.addonId)
    }

    @Test fun decodeNeverThrowsOnBadInput() {
        listOf(
            null, "", "   ", "not json", "[]", "{}",
            """{"entries":"oops"}""",
            """{"entries":[]}""",
            """{"entries":[{},{},{"track":null}]}""",
            """{"entries":[{"track":{"id":"","title":"x"}}]}""",
            """{"entries":[{"track":{"id":"only-id"}}]}"""
        ).forEach { raw ->
            assertEquals("input: $raw", emptyList<QueuedDownload>(), DownloadQueue.decode(raw))
        }
    }

    @Test fun decodeKeepsGoodRowsAndSkipsBrokenOnes() {
        val raw = """
            {"version":1,"entries":[
              {"track":{"id":"a","title":"A","artist":"X","album":""},"state":"Pending","addedAt":1},
              {"track":{"id":"","title":"broken","artist":"X","album":""},"state":"Pending"},
              "garbage",
              {"track":{"id":"b","title":"B","artist":"Y","album":"Z"},"state":"Failed","addedAt":2}
            ]}
        """.trimIndent()
        val decoded = DownloadQueue.decode(raw)
        assertEquals(listOf("a", "b"), decoded.map { it.track.id })
        assertEquals(QueueState.Failed, decoded.last().state)
    }

    @Test fun decodeKeepsTheLatestRowForDuplicatedIds() {
        val raw = """
            {"entries":[
              {"track":{"id":"a","title":"Old","artist":"X","album":""},"state":"Pending","addedAt":10},
              {"track":{"id":"a","title":"New","artist":"X","album":""},"state":"Paused","addedAt":20}
            ]}
        """.trimIndent()
        val decoded = DownloadQueue.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("New", decoded.single().track.title)
        assertEquals(QueueState.Paused, decoded.single().state)
    }

    @Test fun withEntryRefreshesInsteadOfDuplicating() {
        val first = DownloadQueue.withEntry(emptyList(), track("a"))
        val failed = DownloadQueue.withState(first, "a", QueueState.Failed)
        val again = DownloadQueue.withEntry(failed, track("a"), QueueState.Pending)
        assertEquals(1, again.size)
        assertEquals(QueueState.Pending, again.single().state)
        assertEquals(listOf("a"), again.map { it.track.id })
    }

    @Test fun stateAndRemoveOnlyTouchTheTarget() {
        val queue = listOf(QueuedDownload(track("a")), QueuedDownload(track("b")))
        assertEquals(listOf(QueueState.Paused, QueueState.Pending),
            DownloadQueue.withState(queue, "a", QueueState.Paused).map { it.state })
        assertEquals(listOf("b"), DownloadQueue.remove(queue, "a").map { it.track.id })
        assertEquals(listOf("a", "b"), DownloadQueue.remove(queue, "missing").map { it.track.id })
        assertEquals("b", DownloadQueue.find(queue, "b")?.track?.id)
        assertNull(DownloadQueue.find(queue, "missing"))
    }

    @Test fun pendingDropsWhatIsAlreadyOnDisk() {
        val queue = listOf(QueuedDownload(track("a")), QueuedDownload(track("b")), QueuedDownload(track("c")))
        assertEquals(listOf("b"),
            DownloadQueue.pending(queue, setOf("a", "c")).map { it.track.id })
    }

    @Test fun unknownStateFallsBackToPending() {
        assertEquals(QueueState.Pending, QueueState.parse("weird"))
        assertEquals(QueueState.Pending, QueueState.parse(null))
        assertEquals(QueueState.Paused, QueueState.parse("paused"))
        assertEquals(QueueState.Failed, QueueState.parse("FAILED"))
        assertTrue(DownloadQueue.decode("""{"entries":[{"track":{"id":"a","title":"A","artist":"","album":""},"state":"weird"}]}""")
            .single().state == QueueState.Pending)
    }

    @Test fun restorePolicyMatchesWhatTheUiExpects() {
        // Paused and pending rows keep their controls; a failed row only offers
        // "Réessayer", so it must not occupy a busy slot.
        assertEquals(DownloadPhase.Resolving, DownloadQueue.phaseFor(QueueState.Pending))
        assertEquals(DownloadPhase.Paused, DownloadQueue.phaseFor(QueueState.Paused))
        assertEquals(DownloadPhase.Failed, DownloadQueue.phaseFor(QueueState.Failed))

        assertTrue(DownloadQueue.startsImmediately(QueueState.Pending))
        assertTrue(!DownloadQueue.startsImmediately(QueueState.Paused))
        assertTrue(!DownloadQueue.startsImmediately(QueueState.Failed))

        assertTrue(DownloadQueue.countsAsBusy(QueueState.Pending))
        assertTrue(DownloadQueue.countsAsBusy(QueueState.Paused))
        assertTrue(!DownloadQueue.countsAsBusy(QueueState.Failed))
    }
}
