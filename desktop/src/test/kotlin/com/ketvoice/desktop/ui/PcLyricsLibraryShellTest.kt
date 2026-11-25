package com.ketvoice.desktop.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.ketvoice.desktop.library.DropKinds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Windows 2.0 lyrics and library pieces in the real shell: the import page, the drop layer and its question, Settings. */
@OptIn(ExperimentalTestApi::class)
class PcLyricsLibraryShellTest {
    @Test fun theImportPageTheDropLayerAndTheSettingsJoinTheShell() = runComposeUiTest {
        PcLyricsLibrary.register()
        try {
            val nav = Navigator(Route.Home)
            setContent { KetvoiceShell(RecordingPlayer().playing().apply { toggle() }, Fixtures.library(), FixtureCatalog, dark = true, navigator = nav) }
            waitForIdle()
            onNodeWithTag("drop-target").assertExists()
            // A playlist link dropped: the import page, with the link.
            DropAnywhere.handle(DropKinds.ofText("https://www.deezer.com/fr/playlist/1234567")) { nav.push(it) }
            waitForIdle()
            assertEquals(Route.Extra(ImportPage.ID, "https://www.deezer.com/fr/playlist/1234567"), nav.current)
            onNodeWithTag("page:import").assertExists()
            // Dragging over the window shows the glass sheet; a manifest asks before anything is added.
            DropAnywhere.hovering.value = true
            waitForIdle()
            onNodeWithTag("drop-overlay").assertExists()
            DropAnywhere.hovering.value = false
            DropAnywhere.handle(DropKinds.ofText("https://example.org/k/manifest.json")) { nav.push(it) }
            waitForIdle()
            onNodeWithTag("drop-ask").assertExists()
            onNodeWithTag("drop-no").performClick()
            waitForIdle()
            assertEquals(null, DropAnywhere.ask.value)
            assertTrue(SettingsFind.search("paroles flottantes", translate = { it }).any { it.sectionKey == "h:lyrics-pc" })
            assertTrue(SettingsFind.search("playlists suivies", translate = { it }).any { it.sectionKey == "h:library-pc" })
            nav.root(Route.Settings)
            waitForIdle()
        } finally {
            PageRegistry.unregister(ImportPage.ID)
            ShellOverlays.unregister("drop"); ShellOverlays.unregister("outside-playlists")
            SettingsSections.unregister("lyrics-pc"); SettingsSections.unregister("library-pc")
        }
    }
}
