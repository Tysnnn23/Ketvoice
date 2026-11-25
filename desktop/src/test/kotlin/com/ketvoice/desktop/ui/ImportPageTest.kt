package com.ketvoice.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.Track
import com.ketvoice.app.smartlib.ImportMatch
import com.ketvoice.app.smartlib.ImportPreview
import com.ketvoice.app.smartlib.ImportService
import com.ketvoice.app.smartlib.ImportSong
import com.ketvoice.desktop.ui.theme.KetDesktopTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** « Importer en un clic »: one click reads and matches, the report counts, a title fixed by hand, the playlist created. */
@OptIn(ExperimentalTestApi::class)
class ImportPageTest {
    private val songs = (0 until 4).map { ImportSong("s$it", "Song $it", "Artist $it", 180_000) }
    private val found = Track("k9", "Song 3 (live)", "Artist 3", "")

    private inner class FakeImporter : PcPlaylistImporter {
        var searches = 0
        override suspend fun preview(link: String) = ImportPreview(ImportService.Spotify, "Summer", link, null, songs, complete = false)
        override suspend fun resolve(preview: ImportPreview, progress: (Int, Int) -> Unit): List<ImportMatch> = listOf(
            ImportMatch(songs[0], Track("k0", "Song 0", "Artist 0", ""), 1f), ImportMatch(songs[1], Track("k1", "Song 1", "Artist 1", ""), .95f),
            ImportMatch(songs[2], Track("k2", "Song 2 (remix)", "Artist 2", ""), .8f), ImportMatch(songs[3], null, .2f))
        override suspend fun search(query: String): List<Track> { searches++; return listOf(found) }
    }

    @Test fun oneClickThenTheReportThenAFixThenThePlaylist() = runComposeUiTest {
        val library = Fixtures.library()
        val nav = Navigator()
        val flow = ImportFlow("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        val importer = FakeImporter()
        setContent {
            KetDesktopTheme(true) {
                CompositionLocalProvider(LocalLibrary provides library, LocalNavigator provides nav) {
                    Box(Modifier.size(1100.dp, 1400.dp)) { ImportPageContent(flow, autoStart = true, importer = importer) }
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { flow.matches.isNotEmpty() }
        onNodeWithTag("import-rate", useUnmergedTree = true).assertTextContains("75 %")
        onNodeWithTag("import-count-missing").performClick()
        waitForIdle()
        assertEquals(listOf(3), flow.visible().map { it.first })
        onNodeWithTag("import-fix:3", useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) { flow.results.isNotEmpty() }
        onNodeWithTag("import-fix-result:0").performClick()
        waitForIdle()
        assertEquals("k9", flow.final[3].track?.id)
        onNodeWithTag("import-rate", useUnmergedTree = true).assertTextContains("100 %")
        onNodeWithTag("import-filter-All").performClick()
        onNodeWithTag("import-page-create").performClick()
        waitUntil(timeoutMillis = 5_000) { library.playlists.value.any { it.name == "Summer" } }
        assertTrue(importer.searches >= 1)
    }
}
