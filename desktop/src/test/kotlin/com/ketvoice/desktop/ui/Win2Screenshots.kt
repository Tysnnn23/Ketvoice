package com.ketvoice.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.ketvoice.app.data.AppSettings
import com.ketvoice.desktop.ui.glass.GlassMode
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Windows 2.0's main screens rendered with fixture data to `build/desktop-ui-preview/win2-*.png` (no network):
 * the liquid glass over the pages, the island at rest and open, the panel's tabs, the full player, the narrow window,
 * the compact player and the two fallbacks of the glass.
 */
@OptIn(ExperimentalComposeUiApi::class)
class Win2Screenshots {
    private val out = File("build/desktop-ui-preview").apply { mkdirs() }

    private fun shoot(name: String, w: Int = 1440, h: Int = 900, hover: Offset? = null, steps: List<() -> Unit> = emptyList(),
                      content: @Composable () -> Unit) {
        Fixtures.installCovers()
        AppSettings.reduceAnimations.value = false
        val scene = ImageComposeScene(w, h, Density(1f), content = content)
        try {
            var t = 0L
            repeat(40) { scene.render(t); t += 50_000_000L }
            // State changes between frames (a transition starting, then ending).
            for (step in steps) { step(); repeat(20) { scene.render(t); t += 50_000_000L } }
            if (hover != null) { scene.sendPointerEvent(PointerEventType.Move, hover); repeat(30) { scene.render(t); t += 50_000_000L } }
            val png = scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(out, "win2-$name.png").writeBytes(png)
        } finally { scene.close() }
    }

    @Composable
    private fun Shell(route: Route, dark: Boolean, panel: Panel = Panel.NONE, full: Boolean = false, mode: GlassMode = GlassMode.LIQUID, query: String = "") {
        CompositionLocalProvider(LocalLyricsFeed provides remember { FixedLyricsFeed() }) {
            KetvoiceShell(RecordingPlayer().playing(), Fixtures.library(), FixtureCatalog, dark = dark, navigator = remember { Navigator(route) },
                initialPanel = panel, initialFullPlayer = full, glassMode = mode, initialQuery = query)
        }
    }

    private fun both(name: String, route: Route, panel: Panel = Panel.NONE, full: Boolean = false) {
        for (dark in listOf(true, false)) shoot("$name-${if (dark) "dark" else "light"}") { Shell(route, dark, panel, full) }
    }

    @Test fun renders() {
        both("home", Route.Home)
        both("home-lyrics", Route.Home, Panel.LYRICS)
        both("album", Route.Album(Fixtures.refs[0]))
        both("nowplaying", Route.Home, full = true)
        both("settings", Route.Settings)
        shoot("artist-dark") { Shell(Route.Artist(Fixtures.artists[0]), true) }
        shoot("search-dark") { Shell(Route.Search, true, query = "no") }
        shoot("queue-dark") { Shell(Route.Albums, true, Panel.QUEUE) }
        shoot("ketmix-panel-dark") { Shell(Route.Home, true, Panel.KETMIX) }
        shoot("artist-panel-light") { Shell(Route.Home, false, Panel.ARTIST) }
        // The island open under the pointer, and the lens on a sidebar entry.
        shoot("island-open-dark", hover = Offset(720f, 850f)) { Shell(Route.Albums, true) }
        shoot("lens-dark", hover = Offset(120f, 200f)) { Shell(Route.Albums, true) }
        // A narrow window: the sidebar keeps its icons.
        shoot("narrow-dark", 940, 700) { Shell(Route.Home, true) }
        // The glass's fallbacks.
        shoot("home-frosted-dark") { Shell(Route.Home, true, mode = GlassMode.FROSTED) }
        shoot("home-tinted-light") { Shell(Route.Home, false, mode = GlassMode.TINTED) }
        for (dark in listOf(true, false)) shoot("compact-${if (dark) "dark" else "light"}", CompactPlayer.WIDTH, CompactPlayer.HEIGHT) {
            com.ketvoice.desktop.ui.theme.KetDesktopTheme(dark) {
                CompositionLocalProvider(LocalPlayer provides RecordingPlayer().playing(), LocalLyricsFeed provides remember { FixedLyricsFeed() }) {
                    MiniPlayerContent(onExpand = {}, onClose = {})
                }
            }
        }
        assertTrue(File(out, "win2-home-dark.png").length() > 10_000)
    }

    /** The live mix view (« Ketmix » tab, full player) and « Ketmix apprend »'s toast above the island. */
    @Test fun rendersTheLiveMix() {
        KetmixLive.register()
        com.ketvoice.desktop.ketmix.KetmixPcSettings.learns.value = true
        try {
            com.ketvoice.desktop.ketmix.KetmixFeedback.planned(LiveMixFixtures.plan, LiveMixFixtures.from, LiveMixFixtures.to)
            com.ketvoice.desktop.ketmix.LiveMix.showForTest(LiveMixFixtures.frame())
            for (dark in listOf(true, false)) {
                val look = if (dark) "dark" else "light"
                shoot("ketmix-live-$look") { Shell(Route.Home, dark, Panel.KETMIX) }
                shoot("nowplaying-live-$look") { Shell(Route.Home, dark, full = true) }
            }
            FullPlayerLyrics.shown.value = true
            shoot("nowplaying-live-lyrics-dark") { Shell(Route.Home, true, full = true) }
            FullPlayerLyrics.shown.value = false
            com.ketvoice.desktop.ketmix.LiveMix.showForTest(null)
            for (dark in listOf(true, false)) shoot("ketmix-toast-${if (dark) "dark" else "light"}", steps = listOf(
                { com.ketvoice.desktop.ketmix.KetmixFeedback.playing(true) }, { com.ketvoice.desktop.ketmix.KetmixFeedback.playing(false) })) {
                Shell(Route.Albums, dark)
            }
        } finally {
            com.ketvoice.desktop.ketmix.LiveMix.showForTest(null)
            com.ketvoice.desktop.ketmix.KetmixFeedback.resetForTest()
            com.ketvoice.desktop.ketmix.KetmixPcSettings.learns.value = false
            FullPlayerLyrics.shown.value = false
        }
        assertTrue(File(out, "win2-ketmix-live-dark.png").length() > 10_000)
    }
}
