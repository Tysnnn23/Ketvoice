package com.ketvoice.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.KetvoiceApi
import com.ketvoice.desktop.canvas.CanvasFixtures
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders Cascade (right panel, full player) and Canvas (full player, album motion header) at 1440 × 900 to
 * `build/desktop-ui-preview/`, dark and light. Canvas frames come from a real FFmpeg decode of a generated video.
 */
@OptIn(ExperimentalComposeUiApi::class)
class CascadeScreenshots {
    private val out = File("build/desktop-ui-preview").apply { mkdirs() }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        Fixtures.installCovers()
        AppSettings.reduceAnimations.value = false
        // The scene's own thread, as the window's: work that comes back from other threads (romanization, Canvas
        // lookups) waits here and runs between frames, on the thread that renders.
        val queue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val ui = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }
        }
        val scene = ImageComposeScene(1440, 900, Density(1f), coroutineContext = ui, content = content)
        try {
            var t = 0L
            // Real time between frames: the video frames are decoded on their own thread.
            repeat(60) { while (true) (queue.poll() ?: break).run(); scene.render(t); t += 50_000_000L; Thread.sleep(15) }
            while (true) (queue.poll() ?: break).run()
            File(out, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally { scene.close() }
    }

    private fun both(name: String, route: Route, panel: Panel = Panel.NONE, full: Boolean = false) {
        for (dark in listOf(true, false)) shoot("$name-${if (dark) "dark" else "light"}") {
            CompositionLocalProvider(LocalLyricsFeed provides remember { FixedLyricsFeed() }) {
                KetvoiceShell(RecordingPlayer().playing(), Fixtures.library(), FixtureCatalog, dark = dark,
                    navigator = remember { Navigator(route) }, initialPanel = panel, initialFullPlayer = full)
            }
        }
    }

    private fun withCanvas(block: () -> Unit) {
        CanvasRepo.reset(android.content.Context(File("build/test-data/canvas-shots-${System.nanoTime()}")))
        CanvasRepo.lookup = { KetvoiceApi.CanvasResult("https://canvas.example/${it.id}.mp4", "shot-${it.albumId}") }
        CanvasRepo.fetchFile = { _, dest, _ -> CanvasFixtures.video().copyTo(dest, overwrite = true) }
        runBlocking {
            listOf(Fixtures.albumTracks(0)[3], Fixtures.albumTracks(1)[0]).forEach { CanvasRepo.resolve(it); CanvasRepo.ensurePoster(CanvasRepo.localPath(it.id)!!) }
        }
        try { block() } finally {
            // The player's look-ahead lookups end before the next test sees the repository.
            val until = System.currentTimeMillis() + 5_000
            while (CanvasRepo.loading.value.isNotEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
            CanvasRepo.reset(null)
        }
    }

    @Test fun renders() {
        AppSettings.lyricsRomanization.value = true
        try {
            both("lyrics-panel", Route.Home, panel = Panel.LYRICS)
            FullPlayerLyrics.shown.value = true
            both("player-lyrics", Route.Home, full = true)
            FullPlayerLyrics.shown.value = false
            withCanvas {
                both("player-canvas", Route.Home, full = true)
                both("album-motion", Route.Album(Fixtures.refs[1]))
            }
        } finally { AppSettings.lyricsRomanization.value = false; FullPlayerLyrics.shown.value = false }
        assertTrue(File(out, "player-canvas-dark.png").length() > 10_000)
    }
}
