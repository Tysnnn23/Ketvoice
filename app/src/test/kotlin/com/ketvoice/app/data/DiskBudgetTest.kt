package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiskBudgetTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(dir: File, name: String, bytes: Int, age: Long): File =
        File(dir, name).apply { writeBytes(ByteArray(bytes)); setLastModified(1_000_000_000L + age) }

    @Test fun oldestFilesGoFirstUntilTheBudgetHasHeadroom() {
        val dir = tmp.newFolder("cache")
        val old = file(dir, "old", 400, 0)
        val mid = file(dir, "mid", 400, 1000)
        val new = file(dir, "new", 400, 2000)
        DiskBudget.trim(dir, 1000)
        assertFalse(old.exists())
        assertTrue(mid.exists() && new.exists())
    }

    @Test fun underBudgetNothingIsDeleted() {
        val dir = tmp.newFolder("cache")
        val a = file(dir, "a", 300, 0)
        assertEquals(0L, DiskBudget.trim(dir, 1000))
        assertTrue(a.exists())
    }

    @Test fun protectedFilesSurviveEvenWhenOldest() {
        val dir = tmp.newFolder("cache")
        val kept = file(dir, "download-cover", 600, 0)
        val gone = file(dir, "other", 600, 1000)
        DiskBudget.trim(dir, 1000) { it.name == "download-cover" }
        assertTrue(kept.exists())
        assertFalse(gone.exists())
    }

    @Test fun onlyAbandonedPartialWritesAreRemoved() {
        val dir = tmp.newFolder("cache")
        val now = 1_000_000_000L + 10 * 3_600_000L
        val stale = file(dir, "x.part", 10, 0)
        val fresh = File(dir, "y.part").apply { writeBytes(ByteArray(10)); setLastModified(now - 1000) }
        DiskBudget.trim(dir, 1_000_000, now)
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
    }

    @Test fun downloadsProtectTheirRemoteCoverAndCanvas() {
        val downloads = tmp.newFolder("downloads")
        File(downloads, "t1.json").writeText("""{"coverUrl":"https://img.example/a.jpg","canvasFile":"/data/canvas/abc.mp4"}""")
        File(downloads, "t2.json").writeText("""{"coverUrl":"file:///local.jpg","canvasSpotifyId":"xyz"}""")
        File(downloads, "t1.lyrics.json").writeText("""{"coverUrl":"https://ignored.example"}""")
        File(downloads, "broken.json").writeText("{")
        val refs = DiskBudget.downloadRefs(downloads)
        assertEquals(setOf(DiskBudget.sha256("https://img.example/a.jpg")), refs.artworkKeys)
        assertEquals(setOf(File("/data/canvas/abc.mp4").absolutePath, "spotify:xyz"), refs.canvasPaths)
    }

    @Test fun touchIsRateLimited() {
        val f = file(tmp.newFolder("c"), "a", 1, 0)
        val before = f.lastModified()
        DiskBudget.touch(f, before + 1000)
        assertEquals(before, f.lastModified())
        DiskBudget.touch(f, before + 48L * 3_600_000L)
        assertTrue(f.lastModified() > before)
    }
}
