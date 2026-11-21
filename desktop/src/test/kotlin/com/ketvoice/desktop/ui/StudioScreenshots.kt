package com.ketvoice.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.ketvoice.app.automix.AnalysisStore
import com.ketvoice.app.automix.Levels
import com.ketvoice.app.automix.MapPointsBackend
import com.ketvoice.app.automix.MixPointStore
import com.ketvoice.app.automix.MixPresetStore
import com.ketvoice.app.automix.WaveFiles
import com.ketvoice.app.automix.WaveStore
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.desktop.audio.AudioSource
import com.ketvoice.desktop.ketmix.KetmixStudioDesk
import com.ketvoice.desktop.ketmix.KetmixStudioTest
import com.ketvoice.desktop.ketmix.StudioState
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Ketmix Studio over the window (light and dark, with and without a keyframe open) and « Voix prêtes à l'avance ». */
@OptIn(ExperimentalComposeUiApi::class)
class StudioScreenshots {
    private val out = File("build/desktop-ui-preview").apply { mkdirs() }

    private fun shoot(name: String, height: Int = 900, frames: Int = 40, content: @Composable () -> Unit) {
        Fixtures.installCovers()
        AppSettings.reduceAnimations.value = true
        val queue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val ui = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }
        }
        val scene = ImageComposeScene(1440, height, Density(1f), coroutineContext = ui, content = content)
        try {
            var t = 0L
            repeat(frames) { while (true) (queue.poll() ?: break).run(); scene.render(t); t += 50_000_000L; Thread.sleep(15) }
            while (true) (queue.poll() ?: break).run()
            File(out, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally { scene.close() }
    }

    @Test fun renders() {
        System.setProperty("ketvoice.dataDir", File("build/studio-data").absolutePath)
        val a = Track("studio-a", "Premier", "Artiste A", "", durationMs = 40_000)
        val b = Track("studio-b", "Second", "Artiste B", "", durationMs = 40_000)
        val files = mapOf(a.id to KetmixStudioTest.groove("studio-a", 440.0), b.id to KetmixStudioTest.groove("studio-b", 660.0))
        MixPointStore.use(MapPointsBackend()); MixPresetStore.use(MapPointsBackend())
        WaveStore.use(WaveFiles(java.nio.file.Files.createTempDirectory("studio-waves").toFile()))
        AnalysisStore.init(android.content.Context.app)
        AppSettings.ketmixAnalysis.value = 0
        val resolve: suspend (Track) -> AudioSource? = { t -> files[t.id]?.let { AudioSource.LocalFile(it.path) } }
        // A first reading keeps the waves and grids, so the window draws them at once.
        runBlocking { StudioState(a, b).load(resolve) }
        val keep = KetmixStudioDesk.resolve
        KetmixStudioDesk.resolve = resolve
        try {
            for (dark in listOf(true, false)) {
                val mode = if (dark) "dark" else "light"
                KetmixStudioDesk.request.value = KetmixStudioDesk.Request(a, b)
                shoot("studio-$mode") { KetvoiceShell(RecordingPlayer().playing(), Fixtures.library(), FixtureCatalog, dark = dark, navigator = remember { Navigator(Route.Home) }) }
                // A transition set by hand: an echo of 8 s with two keyframes, the second one open.
                val s = StudioState(a, b)
                runBlocking { s.load(resolve) }
                s.moveExit(26_000, 20f); s.style(com.ketvoice.app.automix.MixStyle.Fade); s.resize(8_000)
                s.addKey(1_500); s.setLevels(s.selectedKey, outgoing = Levels(voice = 30, music = 85), incoming = Levels(music = 40))
                s.addKey(6_000); s.setLevels(s.selectedKey, incoming = Levels(music = 100), outgoing = Levels(music = 20))
                s.save()
                KetmixStudioDesk.prepared = s
                KetmixStudioDesk.request.value = null
                KetmixStudioDesk.request.value = KetmixStudioDesk.Request(a, b)
                shoot("studio-keys-$mode") {
                    KetvoiceShell(RecordingPlayer().playing(), Fixtures.library(), FixtureCatalog, dark = dark, navigator = remember { Navigator(Route.Home) })
                }
                KetmixStudioDesk.request.value = null; KetmixStudioDesk.prepared = null
                MixPointStore.use(MapPointsBackend())
                shoot("settings-library-voices-$mode", height = 2200, frames = 20) {
                    KetvoiceShell(RecordingPlayer().playing(), Fixtures.library(), FixtureCatalog, dark = dark, navigator = remember { Navigator(Route.Settings) })
                }
            }
        } finally {
            KetmixStudioDesk.request.value = null; KetmixStudioDesk.resolve = keep
            MixPointStore.use(null); MixPresetStore.use(null); WaveStore.use(null)
        }
        assertTrue(File(out, "studio-dark.png").length() > 20_000)
    }
}
