package com.ketvoice.desktop.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.desktop.lyrics.LyricsPcSettings
import com.ketvoice.desktop.platform.ClickThrough
import com.ketvoice.desktop.platform.ScreenArea
import com.ketvoice.desktop.ui.theme.KetDesktopTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Windows 2.0 lyrics on PC: romanisation cases, the switches in the lyrics view, the floating band's place. */
@OptIn(ExperimentalTestApi::class)
class LyricsPcTest {
    @Test fun romanisationReadsEachScript() {
        val out = RomanCache.lines(listOf("こんにちは", "사랑해", "Привет мир", "Γεια σου", "שלום", "مرحبا", "hello"))
        assertEquals("kon'nichiha", out[0]) // ICU keeps n and i apart
        assertEquals("saranghae", out[1])
        assertEquals("Privet mir", out[2])
        assertEquals("Geia sou", out[3])
        assertTrue(out[4]!!.isNotBlank() && out[4]!!.none { it.code in 0x0590..0x05FF }, "Hebrew: ${out[4]}")
        assertTrue(out[5]!!.isNotBlank() && out[5]!!.none { it.code in 0x0600..0x06FF }, "Arabic: ${out[5]}")
        assertNull(out[6], "Latin lines get nothing")
        // Chinese alone: pinyin with tones; Japanese with kanji: nothing rather than a Chinese reading.
        assertEquals("nǐ hǎo", RomanCache.lines(listOf("你好"))[0])
        assertNull(RomanCache.lines(listOf("夜に駆ける", "愛"))[1])
        assertTrue(RomanCache.useful(listOf("hello", "사랑")))
        assertFalse(RomanCache.useful(listOf("hello", "ça va")))
    }

    @Test fun theSwitchesSitUnderTheSourceAndTranslateEveryLine() {
        AppSettings.translateLanguage.value = "Anglais"
        try {
            runComposeUiTest {
                val player = RecordingPlayer().playing().also { it.toggle() }
                setContent {
                    KetDesktopTheme(true) {
                        CompositionLocalProvider(LocalPlayer provides player, LocalLyricsFeed provides FixedLyricsFeed(), LocalMenu provides MenuState()) {
                            CascadeLyrics(player.state.value.current!!, LyricsLook.Panel, Modifier.size(420.dp, 760.dp))
                        }
                    }
                }
                onNodeWithTag("lyrics-toggles").assertExists()
                onNodeWithTag("lyrics-chip-floating").assertExists()
                onNodeWithTag("lyrics-chip-translate").performClick()
                onNodeWithTag("lyrics-chip-every").performClick()
                assertTrue(LyricsPcSettings.translateEvery.value)
                // Under every line now, not only the one sung.
                waitUntil(timeoutMillis = 5_000) {
                    listOf(0, 1, 2).count { i -> runCatching { onNodeWithTag("translation:$i", useUnmergedTree = true).fetchSemanticsNode() }.isSuccess } >= 2
                }
            }
        } finally { LyricsPcSettings.translateEvery.value = false; AppSettings.translateLanguage.value = "Français" }
    }

    @Test fun theFloatingBandStaysOnItsMonitor() {
        val screens = listOf(ScreenArea(0f, 0f, 1920f, 1040f), ScreenArea(1920f, 0f, 1280f, 984f))
        val bottom = assertNotNull(FloatingLyrics.bounds(screens, 0, null))
        assertEquals((1920f - 1000f) / 2, bottom[0])
        assertTrue(bottom[1] + bottom[3] < 1040f && bottom[1] > 800f)
        val second = assertNotNull(FloatingLyrics.bounds(screens, 1, 1f to 0f))
        assertEquals(1920f + 1280f - 1000f, second[0])
        assertEquals(0f, second[1])
        // A monitor that left: the first one.
        assertEquals(bottom.toList(), FloatingLyrics.bounds(screens, 5, null)!!.toList())
        assertNull(FloatingLyrics.bounds(emptyList(), 0, null))
        val share = FloatingLyrics.share(screens[1], 1920f + 140f, 426f)
        assertEquals(.5f, share.first, 1e-4f)
        assertEquals(.5f, share.second, 1e-4f)
    }

    @Test fun aLockedBandLetsClicksThrough() {
        val through = ClickThrough.style(0, through = true)
        assertTrue(through and 0x20 != 0 && through and 0x80000 != 0 && through and 0x08000000 != 0)
        val unlocked = ClickThrough.style(through, through = false)
        assertEquals(0, unlocked and 0x20)
        assertTrue(unlocked and 0x08000000 != 0)
    }
}
