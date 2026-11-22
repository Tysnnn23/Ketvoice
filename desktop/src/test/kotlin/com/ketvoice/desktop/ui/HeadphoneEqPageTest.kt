package com.ketvoice.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.player.HeadphoneProfiles
import com.ketvoice.app.player.HeadphoneStore
import com.ketvoice.desktop.audio.DesktopHeadphoneEq
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** « Égaliseur par casque » on the equalizer page: profiles listed, linked to the device playing, compared, unlinked. */
@OptIn(ExperimentalTestApi::class)
class HeadphoneEqPageTest {
    private fun clear() {
        AppSettings.headphoneEq.value = true; AppSettings.headphoneProfiles.value = ""; AppSettings.headphoneLinks.value = ""
        DesktopHeadphoneEq.resetForTest()
    }

    @BeforeTest fun setUp() = clear()
    @AfterTest fun tearDown() = clear()

    @Test fun theSectionLinksComparesAndUnlinks() = runComposeUiTest {
        HeadphoneStore.add(HeadphoneProfiles.parse("Preamp: -4 dB\nFilter 1: ON LSC Fc 105 Hz Gain 4 dB Q 0.7", "Sony WH-1000XM4"))
        HeadphoneStore.add(HeadphoneProfiles.parse("Filter 1: ON PK Fc 1000 Hz Gain -2 dB Q 1", "Sennheiser HD 600"))
        DesktopHeadphoneEq.follow("Casque (HD 600)")
        setContent {
            CompositionLocalProvider(com.ketvoice.desktop.ui.theme.LocalKet provides com.ketvoice.desktop.ui.theme.KetColors.of(true)) {
                Box(Modifier.size(1000.dp, 2400.dp)) { EqualizerPage() }
            }
        }
        // The page is a lazy list: each part is scrolled to before it is read or clicked.
        fun show(tag: String) { onNodeWithTag("page:equalizer").performScrollToNode(hasTestTag(tag)); waitForIdle() }
        show("headphone-import")
        assertEquals(2, onAllNodesWithTag("headphone-profile").fetchSemanticsNodes().size)
        onNodeWithTag("headphone-curve").assertDoesNotExist()
        // Sorted by name: Sennheiser first. A click links it to the headset playing; the curve shows what it does.
        show("headphone-import")
        onAllNodesWithTag("headphone-link")[0].performClick()
        waitForIdle()
        assertEquals("Sennheiser HD 600", DesktopHeadphoneEq.status.value.profile?.name)
        assertTrue(DesktopHeadphoneEq.status.value.applied)
        show("headphone-curve")
        onNodeWithTag("headphone-curve").assertExists()
        assertEquals("Casque (HD 600)", HeadphoneStore.links().values.single().name)
        onNodeWithTag("toggle:headphone-compare").performClick()
        waitForIdle()
        assertFalse(DesktopHeadphoneEq.status.value.applied, "« Écouter sans correction »")
        onNodeWithTag("toggle:headphone-compare").performClick()
        show("toggle:headphone-eq")
        onNodeWithTag("toggle:headphone-eq").performClick()
        waitForIdle()
        assertFalse(AppSettings.headphoneEq.value)
        assertFalse(DesktopHeadphoneEq.status.value.applied, "switched off")
        show("headphone-unlink")
        onNodeWithTag("headphone-unlink").performClick()
        waitForIdle()
        assertTrue(HeadphoneStore.links().isEmpty())
        assertEquals(null, DesktopHeadphoneEq.status.value.profile)
    }
}
