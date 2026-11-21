package com.ketvoice.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.Density
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.KetvoiceApi
import com.ketvoice.desktop.canvas.CanvasFixtures
import com.ketvoice.desktop.platform.MediaCommand
import com.ketvoice.desktop.platform.MediaCommands
import com.ketvoice.desktop.platform.ThumbBar
import com.ketvoice.desktop.platform.ThumbIcons
import com.ketvoice.desktop.platform.WindowGlass
import com.ketvoice.desktop.platform.WindowGlass.Backdrop
import com.ketvoice.desktop.platform.WindowGlass.SystemLook
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Windows 1.1.0 « winlook11 »: Windows 11 glass, the full-screen player, the taskbar thumbnail buttons. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
class WinLookTest {
    private val out = File("build/desktop-ui-preview").apply { mkdirs() }

    @AfterTest fun tearDown() { PlayerFullScreen.on.value = false; FullPlayerLyrics.shown.value = false }

    // ── Verre Windows 11 ──

    @Test fun micaOnlyWhereWindowsCanShowIt() {
        val win11 = SystemLook(windows = true, build = 26100, transparencyEffects = true, highContrast = false)
        assertEquals(Backdrop.MICA, WindowGlass.choose(win11, enabled = true))
        assertEquals(Backdrop.MICA, WindowGlass.choose(win11.copy(build = 22621), true))
        assertEquals(Backdrop.MICA_LEGACY, WindowGlass.choose(win11.copy(build = 22000), true))
        // Windows 10, transparency effects off, high contrast, the setting off, not Windows: the solid colours.
        assertEquals(Backdrop.NONE, WindowGlass.choose(win11.copy(build = 19045), true))
        assertEquals(Backdrop.NONE, WindowGlass.choose(win11.copy(transparencyEffects = false), true))
        assertEquals(Backdrop.NONE, WindowGlass.choose(win11.copy(highContrast = true), true))
        assertEquals(Backdrop.NONE, WindowGlass.choose(win11, enabled = false))
        assertEquals(Backdrop.NONE, WindowGlass.choose(win11.copy(windows = false), true))
        // Off Windows nothing is attempted and the window stays solid.
        assertFalse(WindowGlass.readSystem().windows && !System.getProperty("os.name").startsWith("Windows"))
    }

    @Test fun glassColoursLeaveTheWindowTransparentAndKeepCardsSolid() {
        for (dark in listOf(true, false)) {
            val solid = com.ketvoice.desktop.ui.theme.KetColors.of(dark)
            val glass = com.ketvoice.desktop.ui.theme.KetColors.glass(dark)
            assertEquals(solid.ground, solid.window)
            assertFalse(solid.acrylic)
            assertEquals(Color.Transparent, glass.window)
            assertTrue(glass.acrylic && glass.material.alpha < 1f && glass.ground.alpha < 1f)
            assertEquals(solid.card, glass.card)
            assertEquals(solid.label, glass.label)
        }
    }

    // ── Lecteur plein écran ──

    @Test fun f11OpensTheFullScreenPlayerAndEscapeLeavesIt() = runComposeUiTest {
        AppSettings.reduceAnimations.value = true
        Fixtures.installCovers()
        // Paused: while it plays the lyrics and colours move every frame and the test would never be idle.
        val player = RecordingPlayer().playing().apply { toggle() }
        setContent {
            CompositionLocalProvider(LocalLyricsFeed provides remember { FixedLyricsFeed() }) {
                KetvoiceShell(player, Fixtures.library(), FixtureCatalog, dark = true)
            }
        }
        waitForIdle()
        onNodeWithTag("shell").performKeyInput { pressKey(Key.F11) }
        waitForIdle()
        assertTrue(PlayerFullScreen.on.value)
        onNodeWithTag("fullscreen-player").assertExists()
        onNodeWithTag("fullscreen-cover").assertExists()
        onNodeWithTag("lyrics:full").assertExists()
        onNodeWithTag("shell").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertFalse(PlayerFullScreen.on.value)
        onNodeWithTag("fullscreen-player").assertDoesNotExist()
        // From the full player's button, and out through its own.
        onNodeWithTag("shell").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.P) } }
        waitForIdle()
        onNodeWithTag("enter-fullscreen").performClick()
        waitForIdle()
        assertTrue(PlayerFullScreen.on.value)
        onNodeWithTag("exit-fullscreen").performClick()
        waitForIdle()
        assertFalse(PlayerFullScreen.on.value)
        onNodeWithTag("full-player").assertExists()
    }

    @Test fun f11DoesNothingWithoutATitle() = runComposeUiTest {
        setContent { KetvoiceShell(RecordingPlayer(), Fixtures.library(), FixtureCatalog, dark = true) }
        waitForIdle()
        onNodeWithTag("shell").performKeyInput { pressKey(Key.F11) }
        waitForIdle()
        assertFalse(PlayerFullScreen.on.value)
    }

    // ── Boutons dans la miniature ──

    @Test fun thumbnailButtonsSendTheTransport() {
        assertEquals(MediaCommand.Toggle, MediaCommands.fromNative(7, 0))
        assertEquals(MediaCommand.Previous, MediaCommands.fromNative(4, 0))
        assertEquals(MediaCommand.Next, MediaCommands.fromNative(3, 0))
    }

    @Test fun thumbnailGlyphsAreDrawnAndMirrored() {
        for (size in listOf(16, 20, 24, 32)) {
            val px = ThumbIcons.render(size, ThumbIcons.glyphColor(lightTaskbar = false))
            assertEquals(4 * size * size, px.size)
            fun icon(i: Int) = px.copyOfRange(i * size * size, (i + 1) * size * size)
            fun ink(i: Int) = icon(i).count { (it ushr 24) > 128 }
            for (i in 0 until 4) assertTrue(ink(i) > size * size / 8, "glyph $i at $size px has ink")
            // Next is previous seen in a mirror.
            val prev = icon(ThumbIcons.PREVIOUS); val next = icon(ThumbIcons.NEXT)
            var off = 0
            for (y in 0 until size) for (x in 0 until size) {
                if (((prev[y * size + x] ushr 24) > 128) != ((next[y * size + size - 1 - x] ushr 24) > 128)) off++
            }
            assertTrue(off <= size, "mirror differs in $off px at $size")
            // The corners stay clear (no square plate behind the glyph).
            assertEquals(0, icon(ThumbIcons.PLAY)[0] ushr 24)
        }
        // Dark glyphs on a light taskbar.
        val light = ThumbIcons.render(16, ThumbIcons.glyphColor(lightTaskbar = true))
        assertTrue(light.filter { (it ushr 24) == 0xFF }.all { (it and 0xFFFFFF) == 0x1C1C1E })
    }

    @Test fun coverGoesToTheTaskbarReduced() {
        val image = ImageBitmap(1000, 500)
        androidx.compose.ui.graphics.Canvas(image).drawRect(0f, 0f, 1000f, 500f, androidx.compose.ui.graphics.Paint().apply { color = Color(0xFF3366CC) })
        val (px, w, h) = ThumbBar.coverPixels(image)
        assertEquals(ThumbBar.COVER_SIDE, w); assertEquals(ThumbBar.COVER_SIDE / 2, h); assertEquals(w * h, px.size)
        assertEquals(0xFF3366CC.toInt(), px[px.size / 2])
        val small = ThumbBar.coverPixels(ImageBitmap(64, 64))
        assertEquals(64, small.second)
    }

    // ── Renders ──

    private fun shoot(name: String, w: Int = 1440, h: Int = 900, under: Brush? = null, content: @Composable () -> Unit) {
        Fixtures.installCovers()
        AppSettings.reduceAnimations.value = false
        val queue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val ui = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }
        }
        val scene = ImageComposeScene(w, h, Density(1f), coroutineContext = ui) {
            // What DWM puts behind a transparent window: a stand-in for Mica (the wallpaper, blurred and tinted).
            Box(Modifier.fillMaxSize().then(if (under != null) Modifier.background(under) else Modifier)) { content() }
        }
        try {
            var t = 0L
            repeat(60) { while (true) (queue.poll() ?: break).run(); scene.render(t); t += 50_000_000L; Thread.sleep(15) }
            while (true) (queue.poll() ?: break).run()
            File(out, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally { scene.close() }
    }

    private fun mica(dark: Boolean) = if (dark) Brush.linearGradient(listOf(Color(0xFF1F2129), Color(0xFF262022), Color(0xFF1E2224)))
    else Brush.linearGradient(listOf(Color(0xFFEDEFF6), Color(0xFFF4EEEF), Color(0xFFEEF2F1)))

    @Test fun renders() {
        AppSettings.lyricsRomanization.value = true
        for (dark in listOf(true, false)) {
            val mode = if (dark) "dark" else "light"
            for (route in listOf(Route.Home to "home", Route.Settings to "settings")) {
                shoot("w11-glass-${route.second}-$mode", under = mica(dark)) {
                    CompositionLocalProvider(LocalLyricsFeed provides remember { FixedLyricsFeed() }) {
                        KetvoiceShell(RecordingPlayer().playing(), Fixtures.library(), FixtureCatalog, dark = dark,
                            navigator = remember { Navigator(route.first) }, initialPanel = if (route.first == Route.Home) Panel.LYRICS else Panel.NONE, glass = true)
                    }
                }
            }
            PlayerFullScreen.on.value = true
            // 1920: playing with a still pointer (controls faded out); 1366: paused, controls shown.
            for ((w, h) in listOf(1920 to 1080, 1366 to 768)) shoot("w11-fullscreen-${w}-$mode", w, h) {
                CompositionLocalProvider(LocalLyricsFeed provides remember { FixedLyricsFeed() }) {
                    KetvoiceShell(RecordingPlayer().playing().apply { if (w < 1920) toggle() }, Fixtures.library(), FixtureCatalog, dark = dark)
                }
            }
            PlayerFullScreen.on.value = false
        }
        // The Canvas edge to edge on the left.
        CanvasRepo.reset(android.content.Context(File("build/test-data/canvas-w11-${System.nanoTime()}")))
        CanvasRepo.lookup = { KetvoiceApi.CanvasResult("https://canvas.example/${it.id}.mp4", "w11-${it.albumId}") }
        CanvasRepo.fetchFile = { _, dest, _ -> CanvasFixtures.video().copyTo(dest, overwrite = true) }
        try {
            runBlocking {
                val t = Fixtures.albumTracks(0)[3]
                CanvasRepo.resolve(t); CanvasRepo.ensurePoster(CanvasRepo.localPath(t.id)!!)
            }
            PlayerFullScreen.on.value = true
            shoot("w11-fullscreen-canvas-dark", 1920, 1080) {
                CompositionLocalProvider(LocalLyricsFeed provides remember { FixedLyricsFeed() }) {
                    KetvoiceShell(RecordingPlayer().playing(), Fixtures.library(), FixtureCatalog, dark = true)
                }
            }
        } finally {
            PlayerFullScreen.on.value = false
            val until = System.currentTimeMillis() + 5_000
            while (CanvasRepo.loading.value.isNotEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
            CanvasRepo.reset(null)
        }
        // The thumbnail buttons, 8× so they can be looked at, on a dark and a light taskbar.
        for (light in listOf(false, true)) {
            val size = 24; val zoom = 8
            val px = ThumbIcons.render(size, ThumbIcons.glyphColor(light))
            val img = java.awt.image.BufferedImage(4 * size * zoom + 5 * 16, size * zoom + 32, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = if (light) java.awt.Color(0xEE, 0xEE, 0xF0) else java.awt.Color(0x20, 0x20, 0x22); g.fillRect(0, 0, img.width, img.height)
            for (i in 0 until 4) {
                val icon = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                icon.setRGB(0, 0, size, size, px, i * size * size, size)
                g.drawImage(icon, 16 + i * (size * zoom + 16), 16, size * zoom, size * zoom, null)
            }
            g.dispose()
            javax.imageio.ImageIO.write(img, "png", File(out, "w11-thumb-icons-${if (light) "light" else "dark"}.png"))
        }
        assertTrue(File(out, "w11-fullscreen-1920-dark.png").length() > 0)
    }
}
