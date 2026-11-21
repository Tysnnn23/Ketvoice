package com.ketvoice.desktop.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.desktop.ui.theme.KetDesktopTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CascadeLyricsTest {
    private fun ui(test: androidx.compose.ui.test.ComposeUiTest.(RecordingPlayer, FixedLyricsFeed, MenuState) -> Unit) = runComposeUiTest {
        val player = RecordingPlayer().playing().also { it.toggle(); it.calls.clear() } // paused: the clock holds still
        val feed = FixedLyricsFeed()
        val menu = MenuState()
        setContent {
            KetDesktopTheme(true) {
                CompositionLocalProvider(LocalPlayer provides player, LocalLyricsFeed provides feed, LocalMenu provides menu) {
                    CascadeLyrics(player.state.value.current!!, LyricsLook.Panel, Modifier.size(330.dp, 760.dp))
                }
            }
        }
        test(player, feed, menu)
    }

    @Test fun aClickOnALinePlaysFromIt() = ui { player, feed, _ ->
        assertEquals(listOf("fx-0-3"), feed.loads)
        onNodeWithTag("lyric:4").performClick()
        assertTrue("seekTo" in player.calls)
        assertEquals(64_500L, player.state.value.positionMs)
    }

    @Test fun theOtherSingerSitsOnTheOtherSide() = ui { _, _, _ ->
        val lead = onNodeWithTag("lyric:3").fetchSemanticsNode().boundsInRoot
        val other = onNodeWithTag("lyric:4").fetchSemanticsNode().boundsInRoot
        assertTrue(other.left > lead.left + 20f, "duet line starts further in: ${other.left} vs ${lead.left}")
        assertTrue(other.right > lead.right, "and reaches the right edge")
    }

    @Test fun translationAndRomanizationGoUnderTheLines() {
        AppSettings.translateLanguage.value = "Anglais"
        AppSettings.lyricsRomanization.value = true
        try {
            ui { _, _, _ ->
                waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("romanized:5", useUnmergedTree = true).fetchSemanticsNode() }.isSuccess }
                onNodeWithTag("lyrics-translate").performClick()
                waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("translation:3", useUnmergedTree = true).fetchSemanticsNode() }.isSuccess }
                onNodeWithTag("translation:3", useUnmergedTree = true).assertIsDisplayed()
            }
        } finally { AppSettings.lyricsRomanization.value = false; AppSettings.translateLanguage.value = "Français" }
    }

    @Test fun theSourceMenuStartsWithTheRomanizationSwitch() = ui { _, feed, menu ->
        onNodeWithTag("lyrics-source").performClick()
        assertEquals("lyrics-romanization", menu.items.first().tag)
        val lrclib = menu.items.first { it.tag == "lyrics-source:lrclib" }
        val before = AppSettings.lyricsSource.value
        try {
            lrclib.action()
            assertEquals("lrclib", AppSettings.lyricsSource.value)
            assertEquals("fx-0-3:refresh", feed.loads.last())
        } finally { AppSettings.lyricsSource.value = before }
    }

    @Test fun scrollingPausesTheFollowingUntilAsked() = ui { _, _, _ ->
        onNodeWithTag("lyrics-list").performMouseInput { moveTo(Offset(100f, 300f)); scroll(400f) }
        waitForIdle()
        onNodeWithTag("lyrics-follow").assertIsDisplayed().performClick()
        waitForIdle()
        assertTrue(runCatching { onNodeWithTag("lyrics-follow").fetchSemanticsNode() }.isFailure)
    }

    @Test fun onlyAHeldNoteGlows() {
        assertEquals(0f, DeskLyricsWords.glow(.5f, 600))
        assertTrue(DeskLyricsWords.glow(.5f, 2_400) > .9f)
        assertEquals(0f, DeskLyricsWords.glow(1f, 2_400))
        assertEquals(1f, DeskLyricsWords.lift(1f))
    }
}
