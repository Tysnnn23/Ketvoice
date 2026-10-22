package com.ketvoice.app.data

import com.ketvoice.app.ui.adaptive.AdaptiveLayout
import org.junit.Assert.*
import org.junit.Test

class AdaptiveLayoutTest {
    @Test fun phoneKeepsCompactNavigation() {
        val layout = AdaptiveLayout(412f, 915f)
        assertFalse(layout.hasRail)
        assertFalse(layout.adaptivePlayer)
        assertEquals(1, layout.trackColumns)
    }
    @Test fun portraitTabletGetsARailAndTwoPlayerPanes() {
        val layout = AdaptiveLayout(800f, 1280f)
        assertTrue(layout.hasRail)
        assertTrue(layout.playerPanes)
        assertEquals(104f, layout.railWidthDp)
        assertTrue(layout.recentPageWidthDp <= 420f)
    }
    @Test fun landscapeTabletUsesColumnsWithoutStretchingContent() {
        val layout = AdaptiveLayout(1280f, 800f)
        assertTrue(layout.expandedRail)
        assertEquals(2, layout.trackColumns)
        assertTrue(layout.contentWidthDp <= 1280f)
    }
    @Test fun resizingAndPhoneLandscapeAreNotTreatedAsTabletPortrait() {
        assertFalse(AdaptiveLayout(520f, 900f).hasRail)
        val landscape = AdaptiveLayout(850f, 393f)
        assertFalse(landscape.hasRail)
        assertFalse(landscape.playerPanes)
        assertFalse(landscape.adaptivePlayer)
    }
    @Test fun shortLandscapeWindowsKeepThePhonePlayer() {
        val phoneLandscape = AdaptiveLayout(914f, 411f)
        assertFalse(phoneLandscape.adaptivePlayer)
        assertFalse(phoneLandscape.playerPanes)
        val tabletLandscape = AdaptiveLayout(1280f, 800f)
        assertTrue(tabletLandscape.adaptivePlayer)
        assertTrue(tabletLandscape.playerPanes)
    }
}
