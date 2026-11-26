package com.ketvoice.app.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ketvoice TV's pure rules: capability tier, back stack, focus order. */
class TvLogicTest {
    private val gb = 1_000_000_000L

    @Test fun tierFollowsMemoryCoresAndSystem() {
        // A 2 GB box (Chromecast with Google TV, Mi Box S): light.
        assertEquals(TvTier.Light, TvCapability.tier(TvDevice(4, (1.9 * gb).toLong(), false, 30, false)))
        // Android's own low-memory flag wins over everything.
        assertEquals(TvTier.Light, TvCapability.tier(TvDevice(8, 8 * gb, true, 33, true)))
        // Two cores, whatever the memory.
        assertEquals(TvTier.Light, TvCapability.tier(TvDevice(2, 4 * gb, false, 30, true)))
        // 3 GB, 64-bit (Shield): balanced. 4 GB on a 32-bit system: balanced too.
        assertEquals(TvTier.Balanced, TvCapability.tier(TvDevice(4, (2.9 * gb).toLong(), false, 30, true)))
        assertEquals(TvTier.Balanced, TvCapability.tier(TvDevice(4, (3.8 * gb).toLong(), false, 28, false)))
        // 4 GB, 64-bit, four cores or more: full.
        assertEquals(TvTier.Full, TvCapability.tier(TvDevice(4, (3.7 * gb).toLong(), false, 34, true)))
        // Memory unknown (0) is not taken for a small box.
        assertEquals(TvTier.Full, TvCapability.tier(TvDevice(8, 0, false, 34, true)))
    }

    @Test fun tierChoosesKetmixAnalysisAndPerformanceDefaults() {
        assertEquals(0, TvTier.Light.ketmixAnalysis)
        assertEquals(1, TvTier.Balanced.ketmixAnalysis)
        assertEquals(1, TvTier.Full.ketmixAnalysis)
        assertTrue(TvTier.Light.performanceMode)
        assertFalse(TvTier.Balanced.performanceMode)
        assertFalse(TvTier.Light.focusShadow)
        assertTrue(TvTier.Full.livingBackground)
        assertFalse(TvTier.Balanced.livingBackground)
    }

    @Test fun backGoesUpTheStackThenHomeThenLeaves() {
        val nav = TvNavigator()
        nav.section(TvRoute.Library)
        val album = TvRoute.Collection("album", "42", "Album")
        val artist = TvRoute.Artist("UC1", "Artiste")
        nav.open(album)
        nav.open(artist)
        assertEquals(listOf(TvRoute.Library, album, artist), nav.stack)
        assertEquals(TvRoute.Library, nav.section)
        assertTrue(nav.back()); assertEquals(album, nav.current)
        assertTrue(nav.back()); assertEquals(TvRoute.Library, nav.current)
        // From a section's root, Back goes Home first, then leaves the app.
        assertTrue(nav.back()); assertEquals(TvRoute.Home, nav.current)
        assertFalse(nav.back())
    }

    @Test fun openingTheSamePageDoesNotStackItAndReopeningMovesItUp() {
        val nav = TvNavigator()
        val a = TvRoute.Collection("album", "1", "A")
        val b = TvRoute.Artist("2", "B")
        nav.open(a); nav.open(a)
        assertEquals(2, nav.stack.size)
        nav.open(b); nav.open(a)
        assertEquals(listOf(TvRoute.Home, b, a), nav.stack)
        // Now Playing over a page, then a section: the section starts afresh.
        nav.open(TvRoute.NowPlaying)
        nav.section(TvRoute.Search)
        assertEquals(listOf(TvRoute.Search), nav.stack)
        assertTrue(nav.takeDropped().containsAll(listOf(a, b, TvRoute.NowPlaying)))
        assertTrue(nav.takeDropped().isEmpty())
    }

    @Test fun theStackStaysBounded() {
        val nav = TvNavigator()
        repeat(20) { nav.open(TvRoute.Collection("album", "$it", "A$it")) }
        assertEquals(TvNavigator.MAX_DEPTH, nav.stack.size)
        assertEquals(TvRoute.Home, nav.stack.first())
        assertEquals(TvRoute.Collection("album", "19", "A19"), nav.current)
        // The oldest pages above the root were let go.
        assertTrue(TvRoute.Collection("album", "0", "A0") in nav.takeDropped())
    }

    @Test fun keyboardGridMovesLikeATvKeyboard() {
        // 10 keys, 4 wide: rows 0-3, 4-7, 8-9.
        assertEquals(1, TvFocusOrder.grid(0, 10, 4, TvFocusOrder.Move.Right))
        assertNull(TvFocusOrder.grid(3, 10, 4, TvFocusOrder.Move.Right))
        assertNull(TvFocusOrder.grid(4, 10, 4, TvFocusOrder.Move.Left))
        assertNull(TvFocusOrder.grid(2, 10, 4, TvFocusOrder.Move.Up))
        assertEquals(2, TvFocusOrder.grid(6, 10, 4, TvFocusOrder.Move.Up))
        // Down onto the shorter last row lands on its last key.
        assertEquals(9, TvFocusOrder.grid(7, 10, 4, TvFocusOrder.Move.Down))
        assertEquals(8, TvFocusOrder.grid(4, 10, 4, TvFocusOrder.Move.Down))
        assertNull(TvFocusOrder.grid(9, 10, 4, TvFocusOrder.Move.Right))
        assertNull(TvFocusOrder.grid(9, 10, 4, TvFocusOrder.Move.Down))
        assertNull(TvFocusOrder.grid(12, 10, 4, TvFocusOrder.Move.Down))
    }

    @Test fun rowsComeBackToTheirLastFocusedItem() {
        val keys = listOf("a", "b", "c")
        assertEquals(2, TvFocusOrder.entry(keys, "c"))
        assertEquals(1, TvFocusOrder.entry(keys, "gone", fallbackIndex = 1))
        assertEquals(2, TvFocusOrder.entry(keys, null, fallbackIndex = 9))
        assertEquals(-1, TvFocusOrder.entry(emptyList(), "a"))
        val memory = FocusMemory()
        assertNull(memory.restore())
        memory.focused("recent", "t1")
        memory.focused("mixes", "m2")
        memory.focused("recent", "t3")
        assertEquals("recent" to "t3", memory.restore())
        assertEquals("m2", memory.item("mixes"))
        repeat(FocusMemory.MAX_ROWS + 5) { memory.focused("row$it", "x") }
        assertNull("the oldest rows are forgotten", memory.item("mixes"))
    }

    @Test fun pivotKeepsTheFocusedCardNearTheRowStart() {
        // A 1600 px row, cards 300 px: a card at 900 px scrolls so that it sits at 8 % (128 px).
        assertEquals(900f - 128f, TvFocusOrder.pivotScroll(900f, 300f, 1600f), .5f)
        // A card wider than what is left after the pivot is placed whole, flush to the end.
        assertEquals(500f - 100f, TvFocusOrder.pivotScroll(500f, 1500f, 1600f), .5f)
        // A leading margin larger than the pivot wins (the overscan-safe margin).
        assertEquals(400f - 160f, TvFocusOrder.pivotScroll(400f, 300f, 1600f, leading = 160f), .5f)
        assertEquals(0f, TvFocusOrder.pivotScroll(400f, 300f, 0f), 0f)
    }
}
