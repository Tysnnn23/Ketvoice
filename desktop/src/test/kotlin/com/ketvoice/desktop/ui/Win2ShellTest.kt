package com.ketvoice.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.desktop.ui.glass.GlassMode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Windows 2.0's shell in a window: the island, the panel's tabs, the narrow sidebar, the extension points, the glass modes. */
@OptIn(ExperimentalTestApi::class)
class Win2ShellTest {
    @BeforeTest fun setUp() { Fixtures.installCovers(); AppSettings.reduceAnimations.value = true }
    @AfterTest fun tearDown() {
        PageRegistry.unregister("test-sources")
        PanelTabs.unregister("test-room")
        SettingsSections.unregister("test-connexions")
        MiniPlayer.open.value = false
    }

    @Test fun theIslandOpensUnderThePointerAndUnfoldsIntoTheFullPlayer() = runComposeUiTest {
        val shell = ShellState()
        // Paused: a playing title moves the lyrics clock every frame and the test would never be idle.
        val player = RecordingPlayer().playing().apply { toggle() }
        setContent { KetvoiceShell(player, Fixtures.library(), FixtureCatalog, dark = true, shell = shell) }
        waitForIdle()
        onNodeWithTag("bar").assertExists()
        onNodeWithTag("island-progress").assertExists()
        onNodeWithTag("bar").performMouseInput { moveTo(center) }
        waitForIdle()
        assertTrue(shell.islandOpen)
        onNodeWithTag("seek").assertExists()
        onNodeWithTag("shuffle").performClick()
        assertTrue("shuffle:true" in player.calls)
        onNodeWithTag("next").performClick()
        assertTrue("next" in player.calls)
        onNodeWithTag("lcd-artwork").performClick()
        waitForIdle()
        assertTrue(shell.fullPlayer)
        onNodeWithTag("full-player").assertExists()
        onNodeWithTag("bar").assertDoesNotExist()
        onNodeWithTag("close-player").performClick()
        waitForIdle()
        assertFalse(shell.fullPlayer)
        onNodeWithTag("bar").assertExists()
    }

    @Test fun theIslandsButtonsOpenThePanelsTabsAndTheTabsSwitch() = runComposeUiTest {
        val shell = ShellState()
        setContent {
            CompositionLocalProvider(LocalLyricsFeed provides remember { FixedLyricsFeed() }) {
                KetvoiceShell(RecordingPlayer().playing().apply { toggle() }, Fixtures.library(), FixtureCatalog, dark = true, shell = shell)
            }
        }
        waitForIdle()
        onNodeWithTag("side-panel").assertDoesNotExist()
        onNodeWithTag("toggle-lyrics").performClick()
        waitForIdle()
        assertEquals("lyrics", shell.panel)
        onNodeWithTag("panel:lyrics").assertExists()
        onNodeWithTag("panel-tab:queue").performClick()
        waitForIdle()
        onNodeWithTag("panel:queue").assertExists()
        onNodeWithTag("panel-tab:artist").performClick()
        waitForIdle()
        onNodeWithTag("panel:artist").assertExists()
        onNodeWithTag("artist-open").performClick()
        waitForIdle()
        onNodeWithTag("page:artist").assertExists()
        onNodeWithTag("panel-tab:ketmix").performClick()
        waitForIdle()
        onNodeWithTag("panel:ketmix").assertExists()
        onNodeWithTag("panel-close").performClick()
        waitForIdle()
        assertNull(shell.panel)
        onNodeWithTag("side-panel").assertDoesNotExist()
    }

    @Test fun aNarrowWindowKeepsTheSidebarsIcons() = runComposeUiTest {
        setContent {
            Box(Modifier.size(940.dp, 700.dp)) { KetvoiceShell(RecordingPlayer(), Fixtures.library(), FixtureCatalog, dark = true) }
        }
        waitForIdle()
        onNodeWithTag("search-button").assertExists()
        onNodeWithTag("search-field").assertDoesNotExist()
        onNodeWithTag("nav:Albums").performClick()
        onNodeWithTag("page:albums").assertExists()
        onNodeWithTag("search-button").performClick()
        onNodeWithTag("page:search-start").assertExists()
    }

    @Test fun pagesTabsAndSettingsSectionsFromOtherFeaturesJoinTheShell() = runComposeUiTest {
        PageRegistry.register(PageRegistry.Page("test-sources", "Sources", KetIcons.Globe) { arg ->
            Page("Sources", "test-sources") { item { KText("arg=$arg", modifier = Modifier) } }
        })
        PanelTabs.register(PanelTabs.Tab("test-room", "Salon", KetIcons.Account, 50) { Box(Modifier.size(10.dp).let { it }) { KText("salon") } })
        SettingsSections.register(SettingsSections.Section("test-connexions", "Connexions", after = "Windows", rows = listOf("Last.fm")) {
            SettingsCard { SettingRow("Last.fm", "—") }
        })
        val shell = ShellState()
        val nav = Navigator(Route.Home)
        setContent { KetvoiceShell(RecordingPlayer().playing().apply { toggle() }, Fixtures.library(), FixtureCatalog, dark = true, navigator = nav, shell = shell) }
        waitForIdle()
        onNodeWithTag("nav:Sources").performClick()
        waitForIdle()
        onNodeWithTag("page:test-sources").assertExists()
        assertEquals(Route.Extra("test-sources"), nav.current)
        shell.open("test-room")
        waitForIdle()
        onNodeWithTag("panel-tab:test-room").assertExists()
        // The section's rows are found by the settings search.
        assertTrue(SettingsFind.search("last.fm", translate = { it }).any { it.sectionKey == "h:test-connexions" })
        nav.root(Route.Settings)
        waitForIdle()
        SettingsSections.unregister("test-connexions")
        assertTrue(SettingsFind.search("last.fm", translate = { it }).none { it.sectionKey == "h:test-connexions" })
    }

    @Test fun everyGlassModeDrawsTheShell() {
        for (mode in GlassMode.entries) runComposeUiTest {
            setContent {
                Box(Modifier.size(1200.dp, 800.dp)) {
                    KetvoiceShell(RecordingPlayer().playing().apply { toggle() }, Fixtures.library(), FixtureCatalog, dark = mode != GlassMode.TINTED,
                        glassMode = mode, initialPanel = Panel.QUEUE)
                }
            }
            waitForIdle()
            onNodeWithTag("sidebar").assertIsDisplayed()
            onNodeWithTag("side-panel").assertIsDisplayed()
            onNodeWithTag("bar").assertIsDisplayed()
        }
    }

    @Test fun theCompactPlayerIsToggledFromTheIsland() = runComposeUiTest {
        setContent { KetvoiceShell(RecordingPlayer().playing().apply { toggle() }, Fixtures.library(), FixtureCatalog, dark = true) }
        waitForIdle()
        onNodeWithTag("mini-toggle").performClick()
        assertTrue(MiniPlayer.open.value)
        onNodeWithTag("mini-toggle").performClick()
        assertFalse(MiniPlayer.open.value)
    }

    @Test fun theCompactPlayersCardPlaysAndShowsTheLine() = runComposeUiTest {
        val player = RecordingPlayer().playing().apply { toggle() }
        var expanded = false
        setContent {
            com.ketvoice.desktop.ui.theme.KetDesktopTheme(true) {
                CompositionLocalProvider(LocalPlayer provides player, LocalLyricsFeed provides remember { FixedLyricsFeed() }) {
                    Box(Modifier.size(CompactPlayer.WIDTH.dp, CompactPlayer.HEIGHT.dp)) { MiniPlayerContent(onExpand = { expanded = true }) }
                }
            }
        }
        waitForIdle()
        onNodeWithTag("mini-player").assertExists()
        onNodeWithTag("mini-lyric").assertExists()
        onNodeWithTag("mini-expand").performClick()
        assertTrue(expanded)
        // Last: once it plays the lyrics clock runs every frame and the window is never idle again.
        onNodeWithTag("play-pause").performClick()
        assertTrue("toggle" in player.calls)
    }
}
