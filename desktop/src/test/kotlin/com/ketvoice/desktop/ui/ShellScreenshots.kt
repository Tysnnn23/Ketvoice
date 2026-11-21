package com.ketvoice.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.ketvoice.app.data.AppSettings
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the window at 1440 × 900 with fixture data (no network) to `build/desktop-ui-preview/`,
 * in dark and light, for a look at the layout.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ShellScreenshots {
    private val out = File("build/desktop-ui-preview").apply { mkdirs() }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        Fixtures.installCovers()
        AppSettings.reduceAnimations.value = false
        val scene = ImageComposeScene(1440, 900, Density(1f), content = content)
        try {
            var t = 0L
            repeat(60) { scene.render(t); t += 50_000_000L }
            val png = scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(out, "$name.png").writeBytes(png)
        } finally { scene.close() }
    }

    private fun both(name: String, route: Route, panel: Panel = Panel.NONE, query: String = "") {
        for (dark in listOf(true, false)) shoot("$name-${if (dark) "dark" else "light"}") {
            KetvoiceShell(RecordingPlayer().playing(), Fixtures.library(), FixtureCatalog, dark = dark,
                navigator = remember { Navigator(route) }, initialPanel = panel, initialQuery = query)
        }
    }

    @Test fun renders() {
        both("home", Route.Home)
        both("search", Route.Search, query = "no")
        both("album", Route.Album(Fixtures.refs[0]))
        both("artist", Route.Artist(Fixtures.artists[0]))
        both("songs", Route.Songs)
        both("queue", Route.Home, panel = Panel.QUEUE)
        assertTrue(File(out, "album-dark.png").length() > 10_000)
    }
}
