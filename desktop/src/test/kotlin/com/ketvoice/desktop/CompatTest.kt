package com.ketvoice.desktop

import android.content.Context
import android.content.FileSharedPreferences
import android.net.Uri
import android.util.AtomicFile
import androidx.core.content.edit
import com.ketvoice.app.data.SecureSessionStore
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.util.Romanizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The desktop shims behave as the shared code expects from Android. */
class CompatTest {
    private fun freshContext() = Context(Files.createTempDirectory("ketvoice-compat").toFile())

    @Test fun preferencesKeepTypesAcrossReopen() {
        val ctx = freshContext()
        ctx.getSharedPreferences("p", Context.MODE_PRIVATE).edit(commit = true) {
            putString("s", "é"); putInt("i", 3); putLong("l", 1L shl 40); putFloat("f", .5f)
            putBoolean("b", true); putStringSet("set", setOf("a", "b"))
        }
        val file = File(ctx.dataDir, "shared_prefs/p.json")
        assertTrue(file.isFile)
        // A second process reads the same file.
        val reread = FileSharedPreferences::class.java.getDeclaredConstructor(File::class.java)
            .apply { isAccessible = true }.newInstance(file)
        assertEquals("é", reread.getString("s", null))
        assertEquals(3, reread.getInt("i", 0))
        assertEquals(1L shl 40, reread.getLong("l", 0))
        assertEquals(.5f, reread.getFloat("f", 0f))
        assertTrue(reread.getBoolean("b", false))
        assertEquals(setOf("a", "b"), reread.getStringSet("set", null))
        reread.edit().remove("s").clear().putInt("i", 4).commit()
        assertFalse("s" in reread)
        assertEquals(mapOf("i" to 4), reread.all)
    }

    @Test fun applyIsWrittenInOrder() {
        val ctx = freshContext()
        val prefs = ctx.getSharedPreferences("q", Context.MODE_PRIVATE)
        repeat(50) { n -> prefs.edit { putInt("n", n) } }
        FileSharedPreferences.flush()
        assertTrue(File(ctx.dataDir, "shared_prefs/q.json").readText().contains("\"v\":49"))
    }

    @Test fun atomicFileReplacesWhole() {
        val f = File(freshContext().filesDir, "a.json")
        val a = AtomicFile(f)
        a.startWrite().also { it.write("one".toByteArray()); a.finishWrite(it) }
        a.startWrite().also { it.write("half".toByteArray()); a.failWrite(it) }
        assertEquals("one", String(a.readFully()))
    }

    @Test fun uriParsesFiles() {
        val uri = Uri.parse("file:///tmp/My%20Song.lrc")
        assertEquals("file", uri.scheme)
        assertEquals("/tmp/My Song.lrc", uri.path)
        assertEquals("My Song.lrc", uri.lastPathSegment)
    }

    @Test fun sessionsAreSealed() {
        val ctx = freshContext()
        val store = SecureSessionStore(ctx)
        store.put("youtube-cookie", "SAPISID=secret")
        FileSharedPreferences.flush()
        assertEquals("SAPISID=secret", store.get("youtube-cookie"))
        assertFalse(File(ctx.dataDir, "shared_prefs/private-sessions.json").readText().contains("secret"))
        store.remove("youtube-cookie")
        assertNull(store.get("youtube-cookie"))
    }

    @Test fun romanizationUsesIcu() {
        assertEquals(listOf("sayounara", null), Romanizer.lines(listOf("さようなら", "東京")))
    }

    @Test fun queueFacadeKeepsPinsAndOrder() {
        val tracks = (1..4).map { Track("t$it", "T$it", "A", "B", 180_000L) }
        Player.setQueue(tracks, 0)
        Player.playNext(Track("x", "X", "A", "B", 180_000L))
        val next = Player.automixUpcoming(3)
        assertEquals(listOf("x", "t2", "t3"), next.map { it.track.id })
        assertTrue(Player.pinnedNext(next.first().entryId))
        Player.moveQueue(4, 1)
        assertEquals(listOf("t1", "t4", "x", "t2", "t3"), Player.state.value.queue.map { it.id })
        assertEquals(0, Player.state.value.index)
    }
}
