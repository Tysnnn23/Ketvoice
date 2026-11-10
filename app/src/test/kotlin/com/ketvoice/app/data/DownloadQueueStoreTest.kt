package com.ketvoice.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** Persistence rules of the download queue, exercised on the host JVM. */
class DownloadQueueStoreTest {

    private lateinit var dir: File
    private lateinit var file: File

    private fun track(id: String) = Track(id = id, title = "Titre $id", artist = "Artiste", album = "Album")

    @Before fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "kv-queue-" + UUID.randomUUID())
        dir.mkdirs()
        file = File(dir, "download-queue.json")
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test fun stateSurvivesANewStoreOverTheSameFile() {
        val first = DownloadQueueStore(file)
        first.mutate { DownloadQueue.withEntry(it, track("a"), QueueState.Paused) }
        first.mutate { DownloadQueue.withEntry(it, track("b"), QueueState.Failed) }

        val restored = DownloadQueueStore(file).load()
        assertEquals(listOf("a", "b"), restored.map { it.track.id })
        assertEquals(listOf(QueueState.Paused, QueueState.Failed), restored.map { it.state })
    }

    @Test fun loadDropsCompletedDownloadsAndRewritesTheFile() {
        val store = DownloadQueueStore(file)
        store.mutate {
            DownloadQueue.withEntry(DownloadQueue.withEntry(it, track("done")), track("todo"))
        }

        val surviving = store.load(setOf("done"))
        assertEquals(listOf("todo"), surviving.map { it.track.id })

        // The pruned row must not come back on the next launch.
        assertEquals(listOf("todo"), DownloadQueueStore(file).load().map { it.track.id })
        assertTrue(file.readText(Charsets.UTF_8).contains("todo"))
        assertTrue(!file.readText(Charsets.UTF_8).contains("done"))
    }

    @Test fun corruptAndMissingFilesYieldAnEmptyQueue() {
        assertTrue(DownloadQueueStore(file).load().isEmpty())

        file.writeText("{not json at all", Charsets.UTF_8)
        assertTrue(DownloadQueueStore(file).load().isEmpty())

        file.writeText("", Charsets.UTF_8)
        assertTrue(DownloadQueueStore(file).load().isEmpty())
    }

    @Test fun unchangedMutationsDoNotTouchTheDisk() {
        var writes = 0
        val store = DownloadQueueStore(file) { target, bytes ->
            writes++
            target.writeBytes(bytes)
        }
        store.mutate { DownloadQueue.withEntry(it, track("a")) }
        assertEquals(1, writes)

        // Pausing an id that is not queued, or removing a missing one, is a no-op.
        store.mutate { DownloadQueue.withState(it, "missing", QueueState.Paused) }
        store.mutate { DownloadQueue.remove(it, "missing") }
        assertEquals(1, writes)
        assertEquals(listOf("a"), store.entries.map { it.track.id })
    }

    @Test fun aFailedWriteKeepsTheQueueUsable() {
        val store = DownloadQueueStore(file) { _, _ -> throw java.io.IOException("disk full") }
        val next = store.mutate { DownloadQueue.withEntry(it, track("a")) }
        assertEquals(listOf("a"), next.map { it.track.id })
        assertEquals(listOf("a"), store.entries.map { it.track.id })
        assertTrue(!file.exists())
    }
}
