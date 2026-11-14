package com.ketvoice.app.ui

import com.ketvoice.app.ui.chrome.ChromeCollapse
import org.junit.Assert.*
import org.junit.Test

class ChromeCollapseTest {
    @Test fun scrollingDownFoldsAndALittleUpUnfoldsAnywhere() {
        val fold = ChromeCollapse()
        assertFalse(fold.update(0, 0))
        assertFalse("Below the threshold nothing moves", fold.update(0, 20))
        assertTrue(fold.update(0, 80))
        assertTrue(fold.update(3, 10))
        assertTrue(fold.update(8, 200))
        assertTrue("A small move up is not enough", fold.update(8, 170))
        assertFalse("Back up without reaching the top", fold.update(8, 120))
        assertFalse(fold.update(8, 110))
        assertTrue("Down again folds again", fold.update(9, 30))
    }

    @Test fun theTopAlwaysUnfoldsAndJitterIsIgnored() {
        val fold = ChromeCollapse()
        fold.update(0, 0); fold.update(2, 0)
        assertTrue(fold.update(4, 0))
        assertFalse(fold.update(0, 10))
        repeat(10) { assertFalse(fold.update(0, 30 + it % 2 * 3)) }
        assertFalse("Scrolling up across items counts too", ChromeCollapse().run { update(0, 0); update(5, 0); update(5, 40); update(4, 0) })
    }
}
