package com.ketvoice.app.data

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.ketvoice.app.automix.Automix
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 7.6 (owner: « après avoir écouté un album entier, le Canvas continue de se charger au lieu d'utiliser le cache »):
 * an album is played title after title through the player's own Canvas calls (the title that starts, then the next
 * two of the queue), then played again. The second pass asks nothing and every title shows its own Canvas and its
 * still at once. The fake server answers as the real catalogue does for « Legends Never Dies » (Juice WRLD): one
 * Canvas per title (ids and sizes read from the catalogue on 2026-11-20), catalogue titles carrying no Spotify id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CanvasPlayerReplayHostTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val lookups = AtomicInteger()
    private val downloads = AtomicInteger()
    private val canvasOf = mapOf(
        "Conversations" to "4K06PO78fW4mnBVenxGNob",
        "Righteous" to "5dOxHHMOFAbG3VH23t0xNm",
        "Wishing Well" to "2U5WueTLIK5WJLD7mvDODv",
        "Come & Go" to "2Y0wPrPQBrGhoLn14xRYCG")
    private val album = canvasOf.keys.mapIndexed { i, title -> Track("lnd-$i", title, "Juice WRLD", "Legends Never Dies") }

    private fun restart() {
        CanvasRepo::class.java.getDeclaredField("appContext").apply { isAccessible = true }.set(CanvasRepo, null)
        @Suppress("UNCHECKED_CAST")
        (CanvasRepo::class.java.getDeclaredField("_paths").apply { isAccessible = true }.get(CanvasRepo)
            as kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>).value = emptyMap()
        (CanvasRepo::class.java.getDeclaredField("shared").apply { isAccessible = true }.get(CanvasRepo) as MutableSet<*>).clear()
        CanvasRepo.resetAuth()
        CanvasRepo.init(context)
        lookups.set(0); downloads.set(0)
    }

    @Before fun fakeServer() {
        File(context.filesDir, "canvas").deleteRecursively()
        context.getSharedPreferences("ketvoice_canvas", Context.MODE_PRIVATE).edit().clear().commit()
        // The real rule decides; only the network it reads is faked: unmetered Wi-Fi.
        CanvasRepo.mayFetch = { c, ahead -> CanvasRepo.mayDownload(c, ahead) }
        CanvasRepo.unmeteredNow = { true }
        CanvasRepo.allowedNow = { true }
        CanvasRepo.extractFrame = { Bitmap.createBitmap(90, 160, Bitmap.Config.ARGB_8888) }
        CanvasRepo.lookup = { track ->
            lookups.incrementAndGet(); delay(20)
            canvasOf[track.title]?.let { KetvoiceApi.CanvasResult("https://canvaz.scdn.co/upload/artist/x/video/$it.cnvs.mp4", it) }
        }
        CanvasRepo.fetchFile = { _, dest, _ ->
            downloads.incrementAndGet()
            val body = ByteArray(828_241).also { b -> "\u0000\u0000\u0000\u0018ftypmp42".toByteArray(Charsets.ISO_8859_1).copyInto(b) }
            dest.parentFile?.mkdirs(); dest.writeBytes(body); dest
        }
        restart()
    }

    @After fun realNetwork() {
        CanvasRepo.lookup = { KetvoiceApi.canvas(it) }
        CanvasRepo.unmeteredNow = { CanvasRepo.unmetered(it) }
        Automix.appInFront = true
    }

    /** What the player does when a title starts: its own Canvas, then the next two of the queue. */
    private suspend fun play(index: Int) {
        CanvasRepo.resolve(album[index])
        CanvasRepo.aheadOf(album, index)
    }

    /** Stills are written off the caller's thread; a slow machine (CI) needs more than a moment. */
    private suspend fun postersWritten() {
        repeat(500) {
            if (CanvasRepo.loading.value.isEmpty() && album.all { CanvasRepo.posterOf(CanvasRepo.paths.value[it.id]) != null }) return
            delay(20)
        }
    }

    private fun secondPass(label: String, firstLookups: Int, firstDownloads: Int) = runBlocking {
        postersWritten()
        restart()
        Automix.appInFront = true
        val shownAtOnce = album.indices.map { i ->
            play(i)
            val path = CanvasRepo.paths.value[album[i].id]
            path != null && CanvasRepo.posterOf(path) != null
        }
        println("canvas-replay[$label]: first pass $firstLookups lookups $firstDownloads downloads; " +
            "second pass ${lookups.get()} lookups ${downloads.get()} downloads; poster at once ${shownAtOnce.count { it }}/${album.size}")
        assertEquals("no lookup on the second pass", 0, lookups.get())
        assertEquals("no download on the second pass", 0, downloads.get())
        assertTrue("every title shows its Canvas and its still at once", shownAtOnce.all { it })
        album.forEach { t ->
            assertEquals("${t.title} shows its own Canvas", canvasOf.getValue(t.title) + ".mp4",
                CanvasRepo.paths.value[t.id]?.let { File(it).name })
        }
    }

    @Test fun titlesGivenTheirAlbumsCanvasIn75GetTheirOwnAfterTheUpdate() = runBlocking {
        // The state 7.5 left: the first title's Canvas downloaded, and every later title mapped to it for good.
        val first = canvasOf.getValue(album[0].title)
        val dir = File(context.filesDir, "canvas").apply { mkdirs() }
        File(dir, "$first.mp4").writeBytes(ByteArray(828_241).also { b -> "\u0000\u0000\u0000\u0018ftypmp42".toByteArray(Charsets.ISO_8859_1).copyInto(b) })
        val prefs = context.getSharedPreferences("ketvoice_canvas", Context.MODE_PRIVATE)
        prefs.edit().clear().apply { album.forEach { putString(it.id, first) } }
            .putString("album:juice wrld:legends never dies", first).putString("page:MPREqaLegends", first).commit()
        restart() // the first start of 7.6
        assertTrue("the 7.5 title-to-album mappings are forgotten", album.all { CanvasRepo.paths.value[it.id] == null })
        album.forEach { CanvasRepo.resolve(it) }
        println("canvas-replay[7.5 state]: after the update ${lookups.get()} lookups ${downloads.get()} downloads")
        album.forEach { t ->
            assertEquals("${t.title} shows its own Canvas", canvasOf.getValue(t.title) + ".mp4", CanvasRepo.paths.value[t.id]?.let { File(it).name })
        }
        assertEquals("the first title's file was kept, not fetched again", album.size - 1, downloads.get())
        assertEquals("the page still remembers its Canvas", first, prefs.getString("page:MPREqaLegends", null))
        // Once only: a later start changes nothing.
        restart()
        assertNotNull(prefs.getString(album[1].id, null).also { assertEquals(canvasOf.getValue(album[1].title), it) })
        album.forEach { CanvasRepo.resolve(it) }
        assertEquals(0, lookups.get())
    }

    @Test fun anAlbumHeardScreenOffOnWifiReplaysFromTheDevice() {
        runBlocking { Automix.appInFront = false; album.indices.forEach { play(it) } }
        secondPass("screen off", lookups.get(), downloads.get())
    }

    @Test fun anAlbumHeardOnScreenReplaysWithEachTitlesOwnCanvas() {
        runBlocking { Automix.appInFront = true; album.indices.forEach { play(it) } }
        secondPass("on screen", lookups.get(), downloads.get())
    }
}
