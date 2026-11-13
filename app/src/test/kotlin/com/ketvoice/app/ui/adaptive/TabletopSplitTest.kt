package com.ketvoice.app.ui.adaptive

import org.junit.Assert.*
import org.junit.Test

class TabletopSplitTest {
    @Test fun aCentredHingeSplitsSceneAndControls() {
        val split = tabletopSplit(hingeTopDp = 400f, hingeBottomDp = 420f, windowHeightDp = 840f, bottomInsetDp = 24f)
        assertEquals(TabletopSplit(400f, 396f), split)
    }

    @Test fun aHingeTooCloseToAnEdgeKeepsTheNormalLayout() {
        assertNull(tabletopSplit(120f, 140f, 840f, 24f))
        assertNull(tabletopSplit(640f, 700f, 840f, 24f))
    }

    @Test fun invalidGeometryIsIgnored() {
        assertNull(tabletopSplit(420f, 400f, 840f, 24f))
        assertNull(tabletopSplit(Float.NaN, 420f, 840f, 24f))
        assertNull(tabletopSplit(0f, 20f, 840f, 24f))
    }
}
