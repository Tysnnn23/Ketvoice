package com.ketvoice.app.widget

import androidx.compose.ui.unit.DpSize
import org.junit.Assert.*
import org.junit.Test

/** The widgets' size mapping and their update throttling (6.4), on the JVM. */
class WidgetLayoutTest {
    @Test fun eachBreakpointHasItsLayout() {
        assertEquals(WidgetSize.StripNarrow, WidgetSizing.classify(100f, 40f))
        assertEquals(WidgetSize.Strip, WidgetSizing.classify(180f, 40f))
        assertEquals(WidgetSize.Strip, WidgetSizing.classify(250f, 40f))
        assertEquals(WidgetSize.Small, WidgetSizing.classify(100f, 110f))
        assertEquals(WidgetSize.Medium, WidgetSizing.classify(250f, 110f))
        assertEquals(WidgetSize.Medium, WidgetSizing.classify(250f, 170f))
        assertEquals(WidgetSize.Large, WidgetSizing.classify(250f, 260f))
    }

    /** Android's cell sizes (portrait, then landscape) for a phone, as the documentation lists them. */
    private val cells = mapOf(
        "1x1" to listOf(57f to 102f, 127f to 51f),
        "2x1" to listOf(130f to 102f, 269f to 51f),
        "4x1" to listOf(276f to 102f, 554f to 51f),
        "2x2" to listOf(130f to 220f, 269f to 117f),
        "4x2" to listOf(276f to 220f, 554f to 117f),
        "3x3" to listOf(203f to 337f, 412f to 184f),
        "4x4" to listOf(276f to 441f, 554f to 250f),
    )

    private fun layouts(sizes: Set<DpSize>, cell: String) = cells.getValue(cell).map { (w, h) -> WidgetSizing.layoutFor(sizes, w, h) }

    @Test fun thePlayersFollowTheLauncherCells() {
        val p = WidgetSizing.players
        assertEquals(listOf(WidgetSize.StripNarrow, WidgetSize.StripNarrow), layouts(p, "1x1"))
        assertEquals(listOf(WidgetSize.StripNarrow, WidgetSize.Strip), layouts(p, "2x1"))
        assertEquals(listOf(WidgetSize.Strip, WidgetSize.Strip), layouts(p, "4x1"))
        assertEquals(listOf(WidgetSize.Small, WidgetSize.Medium), layouts(p, "2x2"))
        assertEquals(listOf(WidgetSize.Medium, WidgetSize.Medium), layouts(p, "4x2"))
        assertEquals(listOf(WidgetSize.Small, WidgetSize.Medium), layouts(p, "3x3"))
        assertEquals(listOf(WidgetSize.Large, WidgetSize.Medium), layouts(p, "4x4"))
        // A 4×2 in portrait has room for the roomy card (bigger cover, the sung line).
        assertEquals(WidgetSizing.MEDIUM_ROOMY, WidgetSizing.pick(p, 276f, 220f))
        assertEquals(WidgetSizing.MEDIUM, WidgetSizing.pick(p, 554f, 117f))
        // A one-row player has the full transport only from 250 dp.
        assertEquals(WidgetSizing.STRIP_FULL, WidgetSizing.pick(p, 276f, 102f))
        assertEquals(WidgetSizing.STRIP, WidgetSizing.pick(p, 203f, 102f))
    }

    @Test fun theMiniStaysOneRowAndCardsNeverShrinkBelowTheSquare() {
        cells.keys.forEach { cell ->
            layouts(WidgetSizing.mini, cell).forEach { assertTrue("$cell → $it", it == WidgetSize.Strip || it == WidgetSize.StripNarrow) }
            layouts(WidgetSizing.cards, cell).forEach { assertTrue("$cell → $it", it != WidgetSize.Strip && it != WidgetSize.StripNarrow) }
        }
        // Nothing fits a place smaller than every breakpoint: the smallest one is used.
        assertEquals(WidgetSizing.SMALL, WidgetSizing.pick(WidgetSizing.cards, 80f, 80f))
    }

    @Test fun theLargestFittingBreakpointWins() {
        val sizes = WidgetSizing.players
        sizes.forEach { assertEquals(it, WidgetSizing.pick(sizes, it.width.value, it.height.value)) }
        assertEquals(WidgetSizing.LARGE, WidgetSizing.pick(sizes, 1000f, 1000f))
    }

    // ---------------------------------------------------------------- throttling

    @Test fun nothingPendingNothingWakes() {
        val c = WidgetCoalescer()
        assertNull(c.waitAt(0))
        assertTrue(c.take(0).isEmpty())
    }

    @Test fun aLineIsSentAtOnceThenSpacedOut() {
        val c = WidgetCoalescer()
        c.request(WidgetReason.Lyric)
        assertEquals(0L, c.waitAt(1_000))
        assertEquals(setOf(WidgetKind.Player, WidgetKind.Lyrics), c.take(1_000))
        c.request(WidgetReason.Lyric)
        assertEquals(WidgetRefresh.LYRIC_SPACING_MS - 100, c.waitAt(1_100))
        assertTrue("too soon", c.take(1_100).isEmpty())
        assertEquals(setOf(WidgetKind.Player, WidgetKind.Lyrics), c.take(1_000 + WidgetRefresh.LYRIC_SPACING_MS))
        assertNull(c.waitAt(2_000))
    }

    /** A quick run of lines (one every 100 ms for 3 s) redraws the lyrics widgets at most a few times a second. */
    @Test fun aQuickRunOfLinesIsCoalesced() {
        val c = WidgetCoalescer()
        var sends = 0
        var now = 0L
        var nextRequest = 0L
        while (now <= 3_000) {
            if (now >= nextRequest) { c.request(WidgetReason.Lyric); nextRequest += 100 }
            if (c.waitAt(now) == 0L) sends += c.take(now).count { it == WidgetKind.Lyrics }
            now += 10
        }
        val most = 3_000 / WidgetRefresh.LYRIC_SPACING_MS + 1
        assertTrue("$sends redraws for 31 lines", sends in 2..most.toInt())
        // The last line is never lost: what is still pending goes once it is due.
        val left = c.waitAt(now)
        if (left != null) assertTrue(c.take(now + left).contains(WidgetKind.Lyrics))
    }

    @Test fun eachWidgetTypeHasItsOwnPace() {
        val c = WidgetCoalescer()
        c.request(WidgetReason.Lyric); c.take(0)
        // A new title 100 ms after a line: the small player goes at once, the large one waits for its spacing.
        c.request(WidgetReason.Track)
        val now = c.take(100)
        assertTrue(WidgetKind.NowPlaying in now && WidgetKind.Mini in now && WidgetKind.Ketmix in now)
        assertFalse(WidgetKind.Player in now)
        assertEquals(WidgetRefresh.LYRIC_SPACING_MS - 100, c.waitAt(100))
        // The listening history never redraws a player.
        val h = WidgetCoalescer().apply { request(WidgetReason.History) }.take(0)
        assertEquals(setOf(WidgetKind.Week, WidgetKind.Mix, WidgetKind.Replay), h)
    }

    @Test fun aLyricNeverRedrawsTheWeek() {
        assertFalse(WidgetKind.Week in WidgetReason.Lyric.kinds)
        assertFalse(WidgetKind.Week in WidgetReason.Progress.kinds)
        assertTrue(WidgetRefresh.LYRIC_SPACING_MS >= 500)
        assertTrue(WidgetRefresh.PROGRESS_MS >= 30_000)
    }

    // ---------------------------------------------------------------- words

    @Test fun emptyAndPausedStatesSayWhy() {
        assertFalse(WidgetsState().hasTrack)
        assertEquals("Rien en lecture", lyricsMessage(WidgetsState()))
        assertEquals("En pause", lyricsMessage(WidgetsState(title = "Song")))
        assertEquals("Pas de paroles synchronisées", lyricsMessage(WidgetsState(title = "Song", playing = true)))
        assertEquals("Désactivé", ketmixStatus(KetmixWidgetState()))
        assertEquals("Activé", ketmixStatus(KetmixWidgetState(on = true)))
        assertEquals("Mix en cours", ketmixStatus(KetmixWidgetState(on = true, mixing = true)))
        assertTrue(ReplayWidgetState().empty)
    }

    @Test fun tilesShowTheTitleAndKetmix() {
        val idle = QuickTiles.playLook(null, false)
        assertEquals(android.service.quicksettings.Tile.STATE_INACTIVE, idle.state)
        assertTrue(idle.subtitleIsText)
        val on = QuickTiles.playLook("Midnight City", true)
        assertEquals(android.service.quicksettings.Tile.STATE_ACTIVE, on.state)
        assertEquals("Midnight City", on.subtitle)
        assertFalse(on.subtitleIsText)
        assertEquals(android.service.quicksettings.Tile.STATE_INACTIVE, QuickTiles.playLook("Midnight City", false).state)
        assertEquals("Désactivé", QuickTiles.ketmixLook(false, "Next").subtitle)
        assertEquals("Next", QuickTiles.ketmixLook(true, "Next").subtitle)
        assertEquals("Activé", QuickTiles.ketmixLook(true, null).subtitle)
    }

    @Test fun minutesAreWrittenAsTheLanguageWritesNumbers() {
        assertEquals("1,204", WidgetLook.minutes(1_204, java.util.Locale.ENGLISH))
        assertEquals("42", WidgetLook.minutes(42, java.util.Locale.ENGLISH))
        assertEquals("0", WidgetLook.minutes(-3, java.util.Locale.ENGLISH))
    }

    @Test fun everyWidgetCanBeAddedFromSettings() {
        assertEquals(WidgetKind.entries.toSet(), WidgetPinning.widgets.map { it.kind }.toSet())
        assertEquals(2, WidgetPinning.tiles.size)
    }
}
