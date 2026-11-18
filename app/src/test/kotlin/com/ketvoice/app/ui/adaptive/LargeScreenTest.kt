package com.ketvoice.app.ui.adaptive

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class LargeScreenTest {
    // ---- Navigation ----

    @Test fun aPhoneKeepsItsTabBarWhateverItsOrientation() {
        assertEquals(NavigationMode.TabBar, LargeScreen.navigation(400f, 900f, quest = false, collapsed = null))
        assertEquals(NavigationMode.TabBar, LargeScreen.navigation(599f, 900f, quest = false, collapsed = false))
        // A phone on its side: wide but short.
        assertEquals(NavigationMode.TabBar, LargeScreen.navigation(900f, 400f, quest = false, collapsed = null))
        assertNull(LargeScreen.info(400f, 900f, quest = false, collapsed = null))
    }

    @Test fun questKeepsItsOwnWindow() {
        assertEquals(NavigationMode.TabBar, LargeScreen.navigation(1440f, 900f, quest = true, collapsed = false))
        assertNull(LargeScreen.info(1440f, 900f, quest = true, collapsed = null))
    }

    @Test fun mediumWindowsGetTheRailAndCannotOpenTheSidebar() {
        assertEquals(NavigationMode.Rail, LargeScreen.navigation(600f, 900f, false, null))
        assertEquals(NavigationMode.Rail, LargeScreen.navigation(840f, 1200f, false, false))
        assertFalse(LargeScreen.info(840f, 1200f, false, null)!!.sidebarAllowed)
    }

    @Test fun wideWindowsOpenOnTheSidebarUnlessTheListenerClosedIt() {
        assertEquals(NavigationMode.Sidebar, LargeScreen.navigation(1366f, 1024f, false, null))
        assertEquals(NavigationMode.Rail, LargeScreen.navigation(1366f, 1024f, false, true))
        // Portrait tablet: the rail by default (room for the page), the sidebar when asked for.
        assertEquals(NavigationMode.Rail, LargeScreen.navigation(1024f, 1366f, false, null))
        assertEquals(NavigationMode.Sidebar, LargeScreen.navigation(1024f, 1366f, false, false))
        assertTrue(LargeScreen.info(1024f, 1366f, false, null)!!.sidebarAllowed)
    }

    @Test fun thePageGetsWhatTheNavigationLeavesWithinItsMaximum() {
        val info = LargeScreen.info(1366f, 1024f, false, null)!!
        assertEquals(LargeScreen.SIDEBAR_DP, info.navigationWidthDp, 0f)
        assertEquals(1366f - 272f - 32f, info.contentWidthDp, .01f)
        assertEquals(LargeScreen.PAGE_MAX_DP - 32f, LargeScreen.pageWidth(2560f, 88f), .01f)
        assertEquals(0f, LargeScreen.pageWidth(10f, 88f), 0f)
    }

    // ---- Grids and shelves ----

    @Test fun columnsFollowTheWidthOfThePaneNotTheDevice() {
        assertEquals(2, LargeScreen.columns(400f, 170f, 14f, min = 2))
        assertEquals(5, LargeScreen.columns(1000f, 170f, 14f))
        assertEquals(8, LargeScreen.columns(5000f, 170f, 14f, max = 8))
        assertEquals(1, LargeScreen.columns(Float.NaN, 170f, 14f))
        val cell = LargeScreen.cellWidth(1000f, 5, 14f)
        assertEquals(1000f, cell * 5 + 14f * 4, .01f)
    }

    @Test fun aWideShelfHoldsMoreWholeCardsAndShowsTheNextOne() {
        val page = 1030f; val edge = 18f; val gap = 14f
        val cell = LargeScreen.shelfCell(page, 156f, edge, gap)
        val whole = ((page - 2 * edge + gap) / (cell + gap)).toInt()
        assertTrue("more than a phone's two and a half cards ($whole)", whole >= 6)
        val used = whole * cell + (whole - 1) * gap + 2 * edge
        assertTrue("a sliver of the next card stays visible", page - used in 10f..cell)
        assertTrue("cards keep about the phone's size", cell in 140f..211f)
        assertEquals(156f, LargeScreen.shelfCell(Float.NaN, 156f, edge, gap), 0f)
    }

    // ---- Panes ----

    @Test fun artworkGridsKeepAboutTheSameCoverOnEveryWindow() {
        // A phone: two covers; a tablet standing up: four; lying down beside the sidebar: six.
        assertEquals(2, LargeScreen.gridColumns(358f))
        assertEquals(2, LargeScreen.gridColumns(Float.NaN))
        assertEquals(4, LargeScreen.gridColumns(682f))
        assertEquals(6, LargeScreen.gridColumns(1030f))
        listOf(358f, 682f, 1030f).forEach { pane ->
            val cell = LargeScreen.cellWidth(pane, LargeScreen.gridColumns(pane), LargeScreen.GRID_GAP_DP)
            assertTrue("covers stay between 150 and 175 dp ($pane → $cell)", cell in 150f..175f)
        }
        // Rows stand two by two only where both have room.
        assertEquals(1, LargeScreen.listColumns(682f))
        assertEquals(2, LargeScreen.listColumns(1030f))
        assertEquals(200f, LargeScreen.headerArtwork(500f), 0f)
        assertEquals(300f, LargeScreen.headerArtwork(1200f), 0f)
    }

    @Test fun searchKeepsTheQueryBesideTheResultsOnWidePanes() {
        assertNull(LargeScreen.searchPane(600f, 900f))
        assertEquals(380f, LargeScreen.searchPane(1300f, 1000f)!!, 0f)
        assertEquals(300f, LargeScreen.searchPane(840f, 1000f)!!, 0f)
    }

    // ---- Player ----

    @Test fun lyingTheLyricsStandBesideAndTheQueueJoinsThemWhenThereIsRoom() {
        val wide = LargeScreen.playerPlan(1366f, 1024f, lyricsWanted = true, queueWanted = true)
        assertTrue(wide.landscape); assertTrue(wide.lyrics); assertTrue(wide.queue); assertTrue(wide.queueBesideLyrics)
        val narrow = LargeScreen.playerPlan(1180f, 820f, lyricsWanted = true, queueWanted = true)
        assertFalse("the queue takes the lyrics' place", narrow.lyrics); assertTrue(narrow.queue)
        val plain = LargeScreen.playerPlan(1366f, 1024f, lyricsWanted = true, queueWanted = false)
        assertTrue(plain.lyrics); assertFalse(plain.queue)
    }

    @Test fun standingTheLyricsRunBelow() {
        val portrait = LargeScreen.playerPlan(1024f, 1366f, lyricsWanted = true, queueWanted = false)
        assertFalse(portrait.landscape); assertTrue(portrait.lyrics)
        assertTrue(LargeScreen.playerPlan(1024f, 1366f, true, true).queueBesideLyrics)
        assertFalse(LargeScreen.playerPlan(834f, 1194f, true, true).lyrics)
        val hidden = LargeScreen.playerPlan(1024f, 1366f, lyricsWanted = false, queueWanted = false)
        assertFalse(hidden.lyrics || hidden.queue)
    }

    @Test fun theArtworkLeavesRoomForTheControls() {
        assertEquals(520f, LargeScreen.playerArtwork(520f, 1000f, 290f), 0f)
        assertEquals(400f, LargeScreen.playerArtwork(900f, 690f, 290f), 0f)
        assertEquals(620f, LargeScreen.playerArtwork(2000f, 2000f, 290f), 0f)
        assertEquals(120f, LargeScreen.playerArtwork(50f, 50f, 290f), 0f)
    }

    // ---- Sheets ----

    @Test fun sheetsFloatOnLargeWindowsOnly() {
        assertEquals(SheetStyle.Bottom, LargeScreen.sheetStyle(400f, 900f, false, wholeForm = false, anchored = true))
        assertEquals(SheetStyle.Bottom, LargeScreen.sheetStyle(900f, 400f, false, wholeForm = false, anchored = true))
        assertEquals(SheetStyle.Bottom, LargeScreen.sheetStyle(1280f, 800f, quest = true, wholeForm = false, anchored = true))
        assertEquals(SheetStyle.Anchored, LargeScreen.sheetStyle(1366f, 1024f, false, wholeForm = false, anchored = true))
        assertEquals(SheetStyle.Centered, LargeScreen.sheetStyle(1366f, 1024f, false, wholeForm = true, anchored = true))
        assertEquals(SheetStyle.Centered, LargeScreen.sheetStyle(1366f, 1024f, false, wholeForm = false, anchored = false))
        assertEquals(540f, LargeScreen.sheetWidth(1366f), 0f)
        assertEquals(512f, LargeScreen.sheetWidth(560f), 0f)
    }

    @Test fun aPopoverHangsUnderThePointerAndStaysInTheWindow() {
        val (x, y) = LargeScreen.floatingPlacement(700f, 200f, 400f, 300f, 1366f, 1024f, 24f, 0f, 0f)
        assertEquals(500f, x, 0f); assertEquals(224f, y, 0f)
        // Too low for the panel: it goes above the pointer.
        val (_, above) = LargeScreen.floatingPlacement(700f, 950f, 400f, 300f, 1366f, 1024f, 24f, 0f, 0f)
        assertEquals(950f - 24f - 300f, above, 0f)
        // At the edge: kept inside.
        val (edgeX, _) = LargeScreen.floatingPlacement(1360f, 100f, 400f, 300f, 1366f, 1024f, 24f, 0f, 0f)
        assertEquals(1366f - 24f - 400f, edgeX, 0f)
        // No anchor: centred.
        val (cx, cy) = LargeScreen.floatingPlacement(null, null, 400f, 300f, 1366f, 1024f, 24f, 0f, 0f)
        assertEquals(483f, cx, 0f); assertEquals(362f, cy, 0f)
    }

    // ---- Folds ----

    @Test fun aBookFoldGivesTheLeftPageWhenBothPagesAreUsable() {
        assertEquals(880f, bookPane(920f, 940f, 1840f, 40f)!!, 0f)
        assertNull(bookPane(300f, 320f, 1840f, 40f))
        assertNull(bookPane(1600f, 1620f, 1840f, 40f))
        assertNull(bookPane(Float.NaN, 940f, 1840f, 40f))
    }

    // ---- Keyboard ----

    private fun key(code: Int, ctrl: Boolean = false, meta: Boolean = false, shift: Boolean = false, alt: Boolean = false, free: Boolean = true) =
        KeyboardShortcuts.of(code, ctrl, meta, shift, alt, free)

    @Test fun spacePlaysAndPauses() {
        assertEquals(Shortcut.PlayPause, key(KeyEvent.KEYCODE_SPACE))
        assertEquals(Shortcut.PlayPause, key(KeyEvent.KEYCODE_SPACE, free = false))
        assertEquals(Shortcut.None, key(KeyEvent.KEYCODE_SPACE, ctrl = true))
    }

    @Test fun arrowsSeekAndChangeTheVolumeOnlyWhileNoControlHoldsTheFocus() {
        assertEquals(Shortcut.SeekBack, key(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(Shortcut.SeekForward, key(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(Shortcut.SeekForwardLong, key(KeyEvent.KEYCODE_DPAD_RIGHT, shift = true))
        assertEquals(Shortcut.VolumeUp, key(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(Shortcut.VolumeDown, key(KeyEvent.KEYCODE_DPAD_DOWN))
        // A focused control keeps the arrows to move the focus.
        assertEquals(Shortcut.None, key(KeyEvent.KEYCODE_DPAD_LEFT, free = false))
        assertEquals(Shortcut.None, key(KeyEvent.KEYCODE_DPAD_UP, free = false))
        assertEquals(-10_000L, KeyboardShortcuts.seekDelta(Shortcut.SeekBack))
        assertEquals(30_000L, KeyboardShortcuts.seekDelta(Shortcut.SeekForwardLong))
        assertEquals(0L, KeyboardShortcuts.seekDelta(Shortcut.PlayPause))
    }

    @Test fun ctrlOrCommandWorksFromAnywhere() {
        assertEquals(Shortcut.PreviousTrack, key(KeyEvent.KEYCODE_DPAD_LEFT, ctrl = true, free = false))
        assertEquals(Shortcut.NextTrack, key(KeyEvent.KEYCODE_DPAD_RIGHT, meta = true, free = false))
        assertEquals(Shortcut.VolumeUp, key(KeyEvent.KEYCODE_DPAD_UP, ctrl = true, free = false))
        assertEquals(Shortcut.Search, key(KeyEvent.KEYCODE_F, ctrl = true))
        assertEquals(Shortcut.Search, key(KeyEvent.KEYCODE_F, meta = true))
        assertEquals(Shortcut.None, key(KeyEvent.KEYCODE_F))
        assertEquals(Shortcut.Lyrics, key(KeyEvent.KEYCODE_L, ctrl = true))
        assertEquals(Shortcut.Queue, key(KeyEvent.KEYCODE_U, ctrl = true))
        assertEquals(Shortcut.ToggleSidebar, key(KeyEvent.KEYCODE_S, ctrl = true, shift = true))
        assertEquals(Shortcut.None, key(KeyEvent.KEYCODE_S, ctrl = true))
    }

    @Test fun escapeGoesBackAndAltIsLeftToTheSystem() {
        assertEquals(Shortcut.Back, key(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(Shortcut.Back, key(KeyEvent.KEYCODE_ESCAPE, free = false))
        assertEquals(Shortcut.None, key(KeyEvent.KEYCODE_TAB))
        assertEquals(Shortcut.None, key(KeyEvent.KEYCODE_DPAD_LEFT, alt = true))
    }

    // ---- Drag and drop ----

    @Test fun onlyAKetvoiceTitleCanBeDroppedOnAPlaylist() {
        assertTrue(PointerExtras.acceptsDrag(setOf("text/plain"), PointerExtras.TRACK_CLIP_LABEL))
        assertFalse(PointerExtras.acceptsDrag(setOf("text/plain"), "some text"))
        assertFalse(PointerExtras.acceptsDrag(setOf("image/png"), PointerExtras.TRACK_CLIP_LABEL))
        assertFalse(PointerExtras.acceptsDrag(setOf("text/plain"), null))
    }
}
