package com.ketvoice.desktop.canvas

import android.content.Context
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.KetvoiceApi
import com.ketvoice.app.data.Track
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Canvas on the desktop: FFmpeg decoding at display size, the loop, the playback thread, the disk cache and its stills. */
class CanvasVideoTest {
    @Test fun decodesAtTheShownSizeAndLoops() {
        val video = CanvasFixtures.video()
        VideoFrames(video, 180, 320).use { v ->
            assertEquals(360, v.sourceWidth); assertEquals(640, v.sourceHeight)
            assertEquals(9f / 16f, v.aspect, .01f)
            assertEquals(180 to 320, v.width to v.height)
            val px = ByteArray(v.width * v.height * 4)
            var last = -1L
            // 1 s at 12 fps: 30 frames go round the loop more than twice, always forward in time.
            repeat(30) { val t = assertNotNull(v.next(px)); assertTrue(t > last, "frame $it at $t after $last"); last = t }
            assertTrue(last in 2_200L..2_700L, "after 30 frames: $last ms")
            // testsrc2 is colourful: the frame is not empty.
            assertTrue(px.count { it != 0.toByte() } > px.size / 4)
        }
    }

    @Test fun neverDecodesLargerThanTheSource() {
        assertEquals(360 to 640, VideoFrames.fitSize(9f / 16f, 360, 640, 2000, 2000))
        // A wide place: the width limits, the height follows the shape.
        assertEquals(400 to 710, VideoFrames.fitSize(9f / 16f, 1080, 1920, 400, 300))
    }

    @Test fun playsOnItsOwnThreadAndHoldsWhilePaused() {
        CanvasPlayback(CanvasFixtures.video(), 90, 160).start().use { p ->
            val first = waitFor { p.take() }
            assertEquals(90, first.width); p.give(first)
            Thread.sleep(300)
            assertEquals(1L, p.decoded, "paused: only the first frame")
            p.playing = true
            Thread.sleep(600)
            assertTrue(p.decoded in 4L..12L, "about 12 fps: ${p.decoded}")
            assertTrue(p.aspect > .5f && p.aspect < .6f)
        }
    }

    @Test fun keepsEachCanvasOnDiskWithItsStillAndLetsTheAlbumStandIn() = runBlocking {
        val dir = File("build/test-data/canvas-repo-${System.nanoTime()}")
        CanvasRepo.reset(Context(dir))
        val source = CanvasFixtures.video()
        var lookups = 0
        var downloads = 0
        CanvasRepo.lookup = { t -> lookups++; if (t.id == "t1") KetvoiceApi.CanvasResult("https://canvas.example/a.mp4", "sp1") else null }
        CanvasRepo.fetchFile = { _, dest, _ -> downloads++; source.copyTo(dest, overwrite = true) }
        AppSettings.animatedArtwork.value = true
        val first = Track(id = "t1", title = "Un", artist = "Nora Vale", album = "Marée basse", durationMs = 1)
        val second = first.copy(id = "t2", title = "Deux")
        CanvasRepo.resolve(first)
        val path = assertNotNull(CanvasRepo.pathFor(first))
        assertEquals("sp1.mp4", File(path).name)
        val poster = assertNotNull(CanvasRepo.ensurePoster(File(path)))
        assertTrue(poster.name.endsWith(".poster.jpg") && poster.length() > 1_000)
        org.jetbrains.skia.Image.makeFromEncoded(poster.readBytes()).use { assertEquals(360, it.width); assertEquals(640, it.height) }
        // The second title of the album: its album's Canvas stands in at once, its own is asked for once (none).
        CanvasRepo.resolve(second)
        assertEquals(path, CanvasRepo.pathFor(second))
        assertEquals(2, lookups)
        assertNotNull(CanvasRepo.albumFile("Marée basse", "Nora Vale"))
        // A new start finds the title's own Canvas on disk without the network, never the stand-in as its own.
        CanvasRepo.reset(Context(dir))
        assertEquals(path, CanvasRepo.paths.value["t1"])
        assertNull(CanvasRepo.paths.value["t2"])
        CanvasRepo.resolve(first)
        assertEquals(1, downloads)
        assertEquals(2, lookups)
        CanvasRepo.reset(null)
    }

    private fun <T : Any> waitFor(block: () -> T?): T {
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until) { block()?.let { return it }; Thread.sleep(10) }
        error("timed out")
    }
}

/** A short test video made by the FFmpeg program of the same (LGPL) build: 360 × 640, 12 fps, 1 s, MPEG-4 Part 2. */
object CanvasFixtures {
    private val file by lazy {
        val out = File("build/test-data/canvas-fixture.mp4")
        if (!out.isFile || out.length() < 10_000) {
            out.parentFile.mkdirs()
            val ffmpeg = org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.ffmpeg::class.java)
            val p = ProcessBuilder(ffmpeg, "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=360x640:rate=12:duration=1",
                "-c:v", "mpeg4", "-q:v", "3", "-pix_fmt", "yuv420p", out.absolutePath).redirectErrorStream(true).start()
            val log = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0 && out.length() > 10_000) { "ffmpeg: $log" }
        }
        out
    }
    fun video(): File = file
}
