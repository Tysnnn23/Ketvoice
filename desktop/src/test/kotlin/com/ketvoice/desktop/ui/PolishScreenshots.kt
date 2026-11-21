package com.ketvoice.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.ketvoice.app.data.AppLanguage
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.smartlib.ImportPreview
import com.ketvoice.app.smartlib.ImportService
import com.ketvoice.app.smartlib.ImportSong
import com.ketvoice.desktop.ui.theme.KetDesktopTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

/** Phase 3's pages and states, dark and light, to `build/desktop-ui-preview/` (fixtures, no network). */
@OptIn(ExperimentalComposeUiApi::class)
class PolishScreenshots {
    private val out = File("build/desktop-ui-preview").apply { mkdirs() }

    private fun shoot(name: String, w: Int = 1440, h: Int = 900, frames: Int = 60, content: @Composable () -> Unit) {
        Fixtures.installCovers()
        AppSettings.reduceAnimations.value = false
        val scene = ImageComposeScene(w, h, Density(1f), content = content)
        try {
            var t = 0L
            repeat(frames) { scene.render(t); t += 50_000_000L }
            File(out, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally { scene.close() }
    }

    private fun both(name: String, route: Route, catalog: CatalogSource = FixtureCatalog, query: String = "", full: Boolean = false,
                     overlay: @Composable () -> Unit = {}) {
        for (dark in listOf(true, false)) shoot("$name-${if (dark) "dark" else "light"}") {
            KetvoiceShell(RecordingPlayer().playing(), Fixtures.library(), catalog, dark = dark,
                navigator = remember { Navigator(route) }, initialQuery = query, initialFullPlayer = full)
            KetDesktopTheme(dark) { overlay() }
        }
    }

    private val offline = object : CatalogSource by FixtureCatalog {
        override suspend fun home(): HomeData = error("no network")
        override suspend fun online() = false
    }

    @Test fun renders() {
        com.ketvoice.app.data.AppText.init(android.content.Context.app)
        AppSettings.language.value = AppLanguage.FRENCH.label
        both("p3-artist", Route.Artist(Fixtures.artists[0]))
        both("p3-album", Route.Album(Fixtures.refs[0]))
        both("p3-offline-home", Route.Home, offline)
        SearchScope.library.value = true
        try { both("p3-search-library", Route.Search, query = "a") } finally { SearchScope.library.value = false }
        both("p3-settings", Route.Settings)
        both("p3-player", Route.Home, full = true)
        both("p3-import", Route.Home, overlay = {
            ImportPlaylistDialog({}, remember {
                ImportState("https://music.apple.com/fr/playlist/route/pl.u-123").apply {
                    preview = ImportPreview(ImportService.AppleMusic, "Route du soir", link, "fixture:cover:2",
                        Fixtures.tracks.take(42).map { ImportSong(it.id, it.title, it.artist, it.durationMs) }, complete = false, publishedCount = 120)
                    busy = true; done = 17
                }
            }, object : PlaylistImporter {
                override suspend fun preview(link: String) = error("unused")
                override suspend fun resolve(preview: ImportPreview, progress: (Int, Int) -> Unit) = error("unused")
            })
        })
        for (dark in listOf(true, false)) shoot("p3-mini-${if (dark) "dark" else "light"}", 380, 132) {
            KetDesktopTheme(dark) {
                androidx.compose.runtime.CompositionLocalProvider(LocalPlayer provides RecordingPlayer().playing()) { MiniPlayerContent {} }
            }
        }
        for (dark in listOf(true, false)) shoot("p3-shortcuts-${if (dark) "dark" else "light"}", 640, 500) {
            KetDesktopTheme(dark) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(com.ketvoice.desktop.ui.theme.LocalKet.current.ground)
                    .padding(20.dp)) { ShortcutsCard() }
            }
        }
        // Statistics in Japanese: the week's axis in the app language (was « Mon Tue » in every language).
        AppSettings.language.value = AppLanguage.JAPANESE.label
        try { both("p3-stats-ja", Route.Stats) } finally { AppSettings.language.value = AppLanguage.FRENCH.label }
        assertTrue(File(out, "p3-artist-dark.png").length() > 10_000)
    }
}
