package com.ketvoice.app.data

import com.ketvoice.app.data.CanvasCachePlan.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The size, the limit and « Vider le cache des Canvas » (6.2.3), as pure functions over file lists. */
class CanvasCachePlanTest {
    @get:Rule val tmp = TemporaryFolder()
    private val mb = 1_000_000L
    private fun item(name: String, mb: Long, age: Long, kept: Boolean = false) = Item(name, mb * 1_000_000L, age, kept)

    @Test fun theLimitsOfferedAreTheOnesTheOwnerChose() {
        assertEquals(listOf(256, 512, 1024, 2048), CanvasCachePlan.LIMITS_MB)
        assertEquals(512, CanvasCachePlan.DEFAULT_MB)
    }

    @Test fun aStoredValueFallsBackToTheNearestLimit() {
        assertEquals(256, CanvasCachePlan.clampMb(0))
        assertEquals(256, CanvasCachePlan.clampMb(-5))
        assertEquals(2048, CanvasCachePlan.clampMb(Int.MAX_VALUE))
        assertEquals(1024, CanvasCachePlan.clampMb(1024))
        assertEquals(1024, CanvasCachePlan.clampMb(900))
    }

    @Test fun usageIsSplitBetweenWhatDownloadsNeedAndTheRest() {
        val usage = CanvasCachePlan.usage(listOf(item("a.mp4", 30, 1, kept = true), item("b.mp4", 50, 2), item("c.mp4.part", 99, 3)))
        assertEquals(30 * mb, usage.keptBytes)
        assertEquals(50 * mb, usage.otherBytes)
        assertEquals(80 * mb, usage.totalBytes)
    }

    @Test fun overTheLimitTheLeastRecentlyUsedGoFirstDownTo90Percent() {
        val items = listOf(item("new", 100, 300), item("old", 100, 100), item("mid", 100, 200))
        // 300 MB against 256 MB: the oldest goes (200 MB <= 230.4 MB).
        assertEquals(listOf("old"), CanvasCachePlan.overLimit(items, 256 * mb).map { it.name })
    }

    @Test fun withinTheLimitNothingGoes() {
        assertTrue(CanvasCachePlan.overLimit(listOf(item("a", 100, 1)), 256 * mb).isEmpty())
    }

    @Test fun whatDownloadsNeedNeverGoEvenWhenOldestAndOverTheLimit() {
        val items = listOf(item("kept", 200, 1, kept = true), item("x", 100, 2), item("y", 100, 3))
        val gone = CanvasCachePlan.overLimit(items, 256 * mb).map { it.name }
        assertFalse("kept" in gone)
        assertEquals(listOf("x", "y"), gone)
    }

    @Test fun clearingKeepsDownloadsAndWritesInFlight() {
        val items = listOf(item("kept.mp4", 40, 1, kept = true), item("a.mp4", 10, 2), item("b.mp4.part", 5, 3))
        assertEquals(listOf("a.mp4"), CanvasCachePlan.toClear(items).map { it.name })
    }

    @Test fun sizesAreShownInMoAndGo() {
        assertEquals("0 Mo", CanvasCachePlan.sizeLabel(0))
        assertEquals("< 1 Mo", CanvasCachePlan.sizeLabel(10_000))
        assertEquals("312 Mo", CanvasCachePlan.sizeLabel(312_000_000))
        assertEquals("1,4 Go", CanvasCachePlan.sizeLabel(1_400_000_000))
        assertEquals("256 Mo", CanvasCachePlan.limitLabel(256))
        assertEquals("2 Go", CanvasCachePlan.limitLabel(2048))
    }

    @Test fun theBudgetTrimmingAppliesTheChosenLimitToRealFiles() {
        val dir = tmp.newFolder("canvas")
        fun file(name: String, bytes: Int, age: Long) = File(dir, name).apply { writeBytes(ByteArray(bytes)); setLastModified(1_000_000_000L + age) }
        val old = file("old.mp4", 400, 0); val kept = file("kept.mp4", 400, 1); val new = file("new.mp4", 400, 2)
        DiskBudget.trim(dir, 1000, protected = { it.name == "kept.mp4" })
        assertFalse(old.exists()); assertTrue(kept.exists() && new.exists())
    }

    @Test fun downloadedTitlesProtectTheirOwnAndTheirAlbumsCanvas() {
        val downloads = tmp.newFolder("downloads")
        File(downloads, "own.json").writeText("""{"canvasFile":"/data/canvas/own.mp4","album":"A","artist":"X"}""")
        File(downloads, "borrowed.json").writeText("""{"album":"B","artist":"Y"}""")
        File(downloads, "none.json").writeText("""{"title":"no album"}""")
        val refs = DiskBudget.downloadRefs(downloads)
        assertEquals(setOf("B" to "Y"), refs.albums)
        val kept = DiskBudget.canvasKept(refs) { album, _ -> if (album == "B") File("/data/canvas/album-b.mp4") else null }
        assertTrue(kept(File("/data/canvas/own.mp4")))
        assertTrue(kept(File("/data/canvas/album-b.mp4")))
        assertFalse(kept(File("/data/canvas/other.mp4")))
    }

    @Test fun theLimitAndThePreloadAreBackedUpAndChecked() {
        assertEquals(1024, PersonalBackup.setting("canvasCacheMb", 1024))
        assertEquals(null, PersonalBackup.setting("canvasCacheMb", 99_999))
        assertEquals(null, PersonalBackup.setting("canvasCacheMb", "big"))
        assertEquals(false, PersonalBackup.setting("canvasPreloadWifi", false))
        assertEquals(null, PersonalBackup.setting("canvasPreloadWifi", 1))
    }
}
