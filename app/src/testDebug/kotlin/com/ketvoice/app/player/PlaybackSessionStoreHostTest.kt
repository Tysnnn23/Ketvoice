package com.ketvoice.app.player

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.ketvoice.app.data.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackSessionStoreHostTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(base.filesDir, "session-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(base) { override fun getFilesDir(): File = directory }

    private class HostAtomicFile(file: File) : android.util.AtomicFile(file) {
        private val pending = File(file.path + ".host-pending")
        override fun startWrite(): java.io.FileOutputStream = java.io.FileOutputStream(pending)
        override fun finishWrite(stream: java.io.FileOutputStream?) {
            stream?.fd?.sync(); stream?.close()
            java.nio.file.Files.move(pending.toPath(), baseFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
        override fun failWrite(stream: java.io.FileOutputStream?) { stream?.close(); pending.delete() }
    }

    @Test fun savedSessionReloadsWithItsPosition() = runBlocking {
        val store = PlaybackSessionStore(context, HostAtomicFile(File(directory, "playback-session-v1.json")))
        assertNull(store.load())
        val track = Track("fixture", "Song", "Artist", "Album")
        store.save(PlayerUiState(queue = listOf(track), queueEntryIds = listOf("entry"), index = 0), 1234)
        store.save(PlayerUiState(queue = listOf(track), queueEntryIds = listOf("entry"), index = 0), 5678)
        val restored = PlaybackSessionStore(context).load()!!
        assertEquals(track, restored.queue.single())
        assertEquals(5678L, restored.positionMs)
    }

    @Test fun corruptSessionIsNotOverwrittenByAnEmptyState() = runBlocking {
        val file = File(directory, "playback-session-v1.json")
        file.writeText("{broken")
        val store = PlaybackSessionStore(context)
        assertNull(store.load())
        store.save(PlayerUiState(), 0)
        assertEquals("{broken", file.readText())
    }
}
