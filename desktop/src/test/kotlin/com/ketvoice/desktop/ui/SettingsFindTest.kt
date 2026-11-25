package com.ketvoice.desktop.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.LayoutSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** « Rechercher un réglage » on Windows: the phone's matching over the window's rows, and a result that scrolls to its row. */
@OptIn(ExperimentalTestApi::class)
class SettingsFindTest {
    private val language = AppSettings.language.value
    @BeforeTest fun setUp() {
        Fixtures.installCovers(); AppSettings.reduceAnimations.value = true; SettingsFind.query = ""; SettingsFind.target = null
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
    }
    @AfterTest fun tearDown() { SettingsFind.query = ""; SettingsFind.target = null; LayoutSettings.resetAll(); AppSettings.language.value = language }

    @Test fun rowsAreFoundByNameOrSectionWithoutAccents() {
        val same: (String) -> String = { it }
        assertEquals("Égaliseur", SettingsFind.search("egaliseur", translate = same).first().title)
        assertEquals("Verre Windows 11", SettingsFind.search("verre", translate = same).first().title)
        // A section's name finds its rows; a name that starts with the query comes first.
        val ketmix = SettingsFind.search("ketmix", translate = same)
        assertEquals("Ketmix", ketmix.first().title)
        assertTrue(ketmix.any { it.title == "Intensité du mix" })
        assertTrue(SettingsFind.search("disposition", translate = same).any { it.title == "Barre latérale" })
        assertEquals("Lecture sans blanc", SettingsFind.search("sans blanc", translate = same).first().title)
        assertTrue(SettingsFind.search("apprend", translate = same).any { it.title == "Ketmix apprend de vos avis" })
        assertTrue(SettingsFind.search("zzz", translate = same).isEmpty())
        assertTrue(SettingsFind.search("  ", translate = same).isEmpty())
        // Read in the listener's language.
        val english = mapOf("Égaliseur" to "Equalizer", "Lecteur" to "Player")
        assertEquals("Égaliseur", SettingsFind.search("equal", translate = { english[it] ?: it }).first().title)
    }

    @Test fun everySectionKeyIsOnThePage() {
        val page = setOf("account-header", "h:Général", "h:Ketmix", "h:Séparation des voix", "h:Lecteur", "h:lyrics", "h:downloads", "h:layout",
            "h:Windows", "h:Raccourcis clavier", "local-header", "h:À propos",
            // Windows 2.0: « Puissance de Ketmix » (Pages.kt) and « Sources » / « Lecture » (SourcesPage.kt).
            "h:Puissance de Ketmix", "h:sources", "h:catalogue", "h:playback")
        assertTrue(SettingsFind.entries.all { it.sectionKey in page }, SettingsFind.entries.filter { it.sectionKey !in page }.toString())
    }

    @Test fun aResultScrollsToItsRowAndLightsIt() = runComposeUiTest {
        setContent { KetvoiceShell(RecordingPlayer(), Fixtures.library(), FixtureCatalog, dark = true, navigator = remember { Navigator(Route.Settings) }) }
        onNodeWithTag("settings-search").performTextReplacement("verre windows")
        waitForIdle()
        onAllNodesWithTag("settings-result")[0].performClick()
        waitForIdle()
        assertEquals("Verre Windows 11", SettingsFind.target?.title)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("toggle:window-glass").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("toggle:window-glass").assertExists()
        assertEquals("", SettingsFind.query)
    }

    @Test fun newTitlesOfADownloadedPlaylistAreOffered() = runComposeUiTest {
        val update = com.ketvoice.app.data.PlaylistFollow.Update("p1", "Road trip", listOf("yt:a", "yt:b"))
        val picked = mutableListOf<String>()
        setContent {
            com.ketvoice.desktop.ui.theme.KetDesktopTheme(dark = true, glass = false) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(800.dp, 600.dp)) {
                    PlaylistUpdateCard(listOf(update), onDownload = { picked += "download:" + it.playlistId }, onLater = { picked += "later" })
                }
            }
        }
        onNodeWithTag("playlist-update").assertExists()
        assertEquals("2 nouveaux titres", newTitles(2))
        assertEquals("1 nouveau titre", newTitles(1))
        onNodeWithTag("playlist-update-download").performClick()
        onNodeWithTag("playlist-update-later").performClick()
        assertEquals(listOf("download:p1", "later"), picked)
    }

    @Test fun theLayoutSectionEditsThePhonesKeys() = runComposeUiTest {
        setContent { KetvoiceShell(RecordingPlayer(), Fixtures.library(), FixtureCatalog, dark = true, navigator = remember { Navigator(Route.Settings) }) }
        onNodeWithTag("settings-search").performTextReplacement("boutons du lecteur")
        waitForIdle()
        onAllNodesWithTag("settings-result")[0].performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("layout-group:player").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("layout-group:player").performClick()
        onNodeWithTag("layout-toggle:player:like").performClick()
        onNodeWithTag("layout-up:player:output").performClick()
        assertEquals(listOf("lyrics", "queue", "output", "account", "like"), DeskLayout.barButtons())
        assertTrue(LayoutSettings.playerList.valid(LayoutSettings.player.value))
        onNodeWithTag("bar-like").assertExists()
        // The queue cannot be hidden.
        onNodeWithTag("layout-toggle:player:queue").performClick()
        assertTrue("queue" in DeskLayout.barButtons())
    }
}
