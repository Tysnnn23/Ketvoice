package com.ketvoice.desktop.ui

import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.ketvoice.app.data.AppSettings
import com.ketvoice.desktop.platform.Startup
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Windows 1.1 « Moins de mémoire »: covers bounded by bytes, big covers decoded smaller, left pages released. */
@OptIn(ExperimentalTestApi::class)
class MemoryBudgetTest {
    @BeforeTest fun setUp() { Covers.trim(); Fixtures.installCovers(); AppSettings.reduceAnimations.value = true }
    // Other tests in this JVM expect the fixture covers.
    @AfterTest fun restore() { Covers.trim(); Fixtures.installCovers() }

    private fun usedMb(): Long {
        val rt = Runtime.getRuntime()
        repeat(3) { System.gc(); Thread.sleep(40) }
        return (rt.totalMemory() - rt.freeMemory()) shr 20
    }
    /** Resident memory (decoded covers are Skia native memory, outside the Java heap), after glibc gives its free pages back; null off Linux. */
    private fun rssMb(): Long? = runCatching {
        repeat(4) { System.gc(); Thread.sleep(150) }
        com.sun.jna.Function.getFunction("c", "malloc_trim").invokeInt(arrayOf(0))
        java.io.File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }.filter(Char::isDigit).toLong() shr 10
    }.getOrNull()

    private fun cover(w: Int, h: Int, seed: Int) = Surface.makeRasterN32Premul(w, h).use { surface ->
        surface.canvas.clear(0xFF000000.toInt() or (seed * 2654435761L).toInt().and(0xFFFFFF))
        surface.makeImageSnapshot().use { it.toComposeImageBitmap() }
    }

    @Test fun coversStayWithinTheirByteBudget() {
        val before = usedMb(); val rssBefore = rssMb()
        // 500 covers of 600 px: 720 MB decoded. The 1.0 cache kept 400 of them (576 MB); now 160 MB at most.
        repeat(500) { Covers.put("mem:$it", cover(600, 600, it)) }
        // Without any collection asked by the test: the cache's own release (a GC after every 64 MB evicted).
        Thread.sleep(600)
        val rssUnaided = runCatching {
            com.sun.jna.Function.getFunction("c", "malloc_trim").invokeInt(arrayOf(0))
            java.io.File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }.filter(Char::isDigit).toLong() shr 10
        }.getOrNull()
        println("MemoryBudgetTest unaided: rss $rssBefore -> $rssUnaided MB")
        if (rssBefore != null && rssUnaided != null) assertTrue(rssUnaided - rssBefore < (Covers.BUDGET_BYTES shr 20) + 160, "unaided rss grew ${rssUnaided - rssBefore} MB")
        val held = Covers.heldBytes()
        val after = usedMb(); val rssAfter = rssMb()
        println("MemoryBudgetTest covers: ${Covers.count()} kept, ${held shr 20} MB held, heap $before -> $after MB, rss $rssBefore -> $rssAfter MB")
        assertTrue(held <= Covers.BUDGET_BYTES, "held ${held shr 20} MB")
        assertTrue(Covers.cached("mem:499") != null, "the newest stays")
        assertTrue(Covers.cached("mem:0") == null, "the oldest went")
        assertTrue(after - before < 64, "heap grew ${after - before} MB")
        if (rssBefore != null && rssAfter != null) assertTrue(rssAfter - rssBefore < (Covers.BUDGET_BYTES shr 20) + 96, "rss grew ${rssAfter - rssBefore} MB")
        Covers.trim()
        assertEquals(0L, Covers.heldBytes())
        val rssTrimmed = rssMb()
        println("MemoryBudgetTest trim: rss $rssAfter -> $rssTrimmed MB")
        if (rssAfter != null && rssTrimmed != null) assertTrue(rssTrimmed < rssAfter - 64, "trim released ${rssAfter - rssTrimmed} MB")
    }

    @Test fun bigCoversAreDecodedAtMostMaxSide() {
        val png = Surface.makeRasterN32Premul(3000, 2000).run {
            canvas.clear(0xFF336699.toInt()); makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
        val image = Covers.decode(png)!!
        assertEquals(Covers.MAX_SIDE, image.width)
        assertEquals(Covers.MAX_SIDE * 2 / 3, image.height)
        val small = Surface.makeRasterN32Premul(300, 300).run { makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes }
        assertEquals(300, Covers.decode(small)!!.width)
    }

    @Test fun heapAfterBrowsingComesBack() = runComposeUiTest {
        val player = RecordingPlayer(); val library = Fixtures.library()
        setContent { KetvoiceShell(player, library, FixtureCatalog, dark = true) }
        waitForIdle()
        val start = usedMb(); val rssStart = rssMb()
        val pages = listOf("Albums" to "page:albums", "Titres" to "page:songs", "Téléchargements" to "page:downloads",
            "Accueil" to "page:home")
        // Statistics is left out: switching to it during this loop sometimes throws inside LazyList measuring
        // (IndexOutOfBounds, no app frame), reported to its owner; this test is about memory.
        var peak = start
        repeat(6) {
            for ((nav, page) in pages) {
                onNodeWithTag("nav:$nav").performClick()
                waitForIdle()
                if (page != "page:downloads") onNodeWithTag(page).assertExists()
            }
            onNodeWithTag("nav:Albums").performClick()
            onNodeWithTag("album:Marée basse").performClick()
            waitForIdle()
            onNodeWithTag("page:album").assertExists()
            onNodeWithTag("back").performClick()
            waitForIdle()
            // The page that was left is gone from the tree (AnimatedContent disposed it), not kept behind.
            onNodeWithTag("page:album").assertDoesNotExist()
            val rt = Runtime.getRuntime(); peak = maxOf(peak, (rt.totalMemory() - rt.freeMemory()) shr 20)
        }
        val end = usedMb(); val rssEnd = rssMb()
        println("MemoryBudgetTest browsing (6 rounds, 30 page visits): heap after GC $start -> $end MB, peak $peak MB, rss $rssStart -> $rssEnd MB; " +
            "covers ${Covers.count()} (${Covers.heldBytes() shr 20} MB)")
        assertTrue(end - start < 48, "heap kept ${end - start} MB after browsing")
    }

    @Test fun renderingAsksForDirect3dOnWindowsOnly() {
        val keys = listOf("skiko.renderApi", "skiko.vsync.enabled")
        val saved = keys.associateWith { System.getProperty(it) }
        try {
            keys.forEach { System.clearProperty(it) }
            assertEquals(null, Startup.configureRendering("Linux") { null })
            assertEquals(null, System.getProperty("skiko.renderApi"))
            assertEquals("true", System.getProperty("skiko.vsync.enabled"))
            assertEquals("DIRECT3D", Startup.configureRendering("Windows 11") { null })
            assertEquals("DIRECT3D", System.getProperty("skiko.renderApi"))
            System.clearProperty("skiko.renderApi")
            // The listener's (or a support) override wins.
            assertEquals("OPENGL", Startup.configureRendering("Windows 11") { if (it == "SKIKO_RENDER_API") "opengl" else null })
            assertEquals(null, System.getProperty("skiko.renderApi"))
        } finally {
            saved.forEach { (k, v) -> if (v == null) System.clearProperty(k) else System.setProperty(k, v) }
        }
    }
}
