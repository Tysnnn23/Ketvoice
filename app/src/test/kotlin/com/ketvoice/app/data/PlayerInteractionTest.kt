package com.ketvoice.app.data

import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.chrome.lensPosition
import com.ketvoice.app.ui.player.segmentProgress
import org.junit.Assert.*
import org.junit.Test

class PlayerInteractionTest {
    @Test fun selectingATabClosesSearchInsteadOfLeavingItsOverlayOnTop() {
        AppUi.overlay.intValue = AppUi.SEARCH
        AppUi.showPlayer.value = true
        AppUi.selectTab(AppUi.EXPLORER)
        assertEquals(AppUi.EXPLORER, AppUi.tab.intValue)
        assertEquals(AppUi.NONE, AppUi.overlay.intValue)
        assertFalse(AppUi.showPlayer.value)
    }
    @Test fun longPressLensFollowsTheFingerAndStaysWithinThreeTabs() {
        assertEquals(0f, lensPosition(-100f, 300f), .001f)
        assertEquals(1f, lensPosition(150f, 300f), .001f)
        assertEquals(2f, lensPosition(450f, 300f), .001f)
    }
    @Test fun highlightSweepsWithinTheProvidersActualDuration() {
        assertEquals(0f, segmentProgress(999, 1000, 2000), .001f)
        assertEquals(.5f, segmentProgress(1500, 1000, 2000), .001f)
        assertEquals(1f, segmentProgress(2500, 1000, 2000), .001f)
    }
    @Test fun missingDurationNeverCreatesArtificialSyllables() {
        assertEquals(0f, segmentProgress(999, 1000, -1), .001f)
        assertEquals(1f, segmentProgress(1000, 1000, -1), .001f)
    }
}
