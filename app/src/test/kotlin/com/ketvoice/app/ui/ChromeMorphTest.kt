package com.ketvoice.app.ui

import com.ketvoice.app.ui.chrome.ChromeCollapse
import com.ketvoice.app.ui.chrome.TabMelt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 8.0 « Barre d'onglets qui se transforme »: the fold follows the scroll's speed, and the tabs melt in order. */
class ChromeMorphTest {
    @Test fun theFoldStartsWithTheScrollsSpeedOnlyWhenItGoesTheSameWay() {
        val travel = 132f
        // Scrolling down at 660 px/s folds at 5 folds per second.
        assertEquals(5f, ChromeCollapse.flingVelocity(1f, 660f, travel), 1e-4f)
        // Scrolling back up unfolds with a negative speed.
        assertEquals(-2f, ChromeCollapse.flingVelocity(0f, -264f, travel), 1e-4f)
        // The other way, or nothing to read, adds nothing.
        assertEquals(0f, ChromeCollapse.flingVelocity(1f, -500f, travel), 0f)
        assertEquals(0f, ChromeCollapse.flingVelocity(0f, 500f, travel), 0f)
        assertEquals(0f, ChromeCollapse.flingVelocity(1f, Float.NaN, travel), 0f)
        assertEquals(0f, ChromeCollapse.flingVelocity(1f, 500f, 0f), 0f)
        // A hard fling is capped, so the spring never jumps.
        assertEquals(ChromeCollapse.MAX_FLING, ChromeCollapse.flingVelocity(1f, 1e6f, travel), 0f)
    }

    @Test fun tabsMeltIntoOneCapsule() {
        assertEquals(0f, TabMelt.fold(296f, 296f, 52f), 0f)
        assertEquals(1f, TabMelt.fold(52f, 296f, 52f), 0f)
        assertEquals(.5f, TabMelt.fold(174f, 296f, 52f), 1e-4f)
        assertEquals(0f, TabMelt.fold(100f, 52f, 52f), 0f)
        // The middle tab stays; the outer ones flow in by one slot.
        assertEquals(0f, TabMelt.towardsMiddle(1f, 90f, 1f), 0f)
        assertEquals(90f, TabMelt.towardsMiddle(0f, 90f, 1f), 0f)
        assertEquals(-45f, TabMelt.towardsMiddle(2f, 90f, .5f), 0f)
        // Labels leave first, then the other tabs, then the selection lens; all are gone before the pill shows.
        val m = .3f
        assertTrue(TabMelt.labelAlpha(m) < TabMelt.otherAlpha(m) && TabMelt.otherAlpha(m) < TabMelt.lensAlpha(m))
        for (alpha in listOf(TabMelt.labelAlpha(TabMelt.PILL_FROM), TabMelt.otherAlpha(TabMelt.PILL_FROM),
                TabMelt.lensAlpha(TabMelt.PILL_FROM))) assertEquals(0f, alpha, 0f)
        assertEquals(1f, TabMelt.labelAlpha(0f), 0f)
    }
}
