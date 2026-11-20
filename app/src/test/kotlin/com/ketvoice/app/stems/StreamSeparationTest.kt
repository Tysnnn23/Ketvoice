package com.ketvoice.app.stems

import com.ketvoice.app.automix.AnalysisDepth
import org.junit.Assert.*
import org.junit.Test

/** 7.6 (voice76): a streamed title is separated from the playhead on, piece by piece, with a fake source. */
class StreamSeparationTest {
    @Test fun piecesStartUnderThePlayhead() {
        assertEquals(listOf(2, 3, 4, 0, 1), StreamSeparation.order(5, 2))
        assertEquals(listOf(0, 1, 2), StreamSeparation.order(3, 0))
        assertEquals(emptyList<Int>(), StreamSeparation.order(0, 0))
        // A little before the playhead: 61 s into pieces of 60 s is still the first piece.
        assertEquals(0, StreamSeparation.pieceAt(61_000, 60_000, 4))
        assertEquals(1, StreamSeparation.pieceAt(70_000, 60_000, 4))
        assertEquals(3, StreamSeparation.pieceAt(900_000, 60_000, 4))
    }

    @Test fun batteryRestFollowsTheAnalysisMode() {
        assertEquals(0L, StreamSeparation.restMs(4_000, AnalysisDepth.Light, charging = true, first = false))
        assertEquals(0L, StreamSeparation.restMs(4_000, AnalysisDepth.Light, charging = false, first = true))
        assertEquals(4_000L, StreamSeparation.restMs(4_000, AnalysisDepth.Light, charging = false, first = false))
        assertEquals(2_000L, StreamSeparation.restMs(4_000, AnalysisDepth.Balanced, charging = false, first = false))
        assertEquals(1_000L, StreamSeparation.restMs(4_000, AnalysisDepth.Maximum, charging = false, first = false))
    }

    @Test fun eachPieceIsPublishedWithoutAGapFromThePlayhead() {
        val worked = ArrayList<Pair<Int, Int>>()
        val published = ArrayList<Pair<Int, Int>>()
        val rests = ArrayList<Pair<Long, Boolean>>()
        var clock = 0L
        val fractions = ArrayList<Float>()
        val ok = StreamSeparation.run(total = 1_000, per = 300, startPiece = 1, goOn = { true }, now = { clock },
            rest = { ms, first -> rests += ms to first }, work = { a, b -> clock += 50; worked += a to b; true },
            publish = { a, b -> published += a to b }, progress = { fractions += it })
        assertTrue(ok)
        // From the playhead's piece to the end, then the start.
        assertEquals(listOf(300 to 600, 600 to 900, 900 to 1_000, 0 to 300), worked)
        // What the voice stage takes up is always one stretch from the playhead's piece on; the last piece completes the whole.
        assertEquals(listOf(300 to 600, 300 to 900, 300 to 1_000), published)
        assertEquals(1f, fractions.last(), 1e-6f)
        assertEquals(listOf(50L to true, 50L to false, 50L to false), rests)
    }

    @Test fun aStoppedSeparationStoresNothing() {
        var pieces = 0
        val ok = StreamSeparation.run(1_000, 300, 0, goOn = { pieces < 2 }, now = { 0L }, rest = { _, _ -> },
            work = { _, _ -> pieces++; true }, publish = { _, _ -> }, progress = {})
        assertFalse(ok)
        assertEquals(2, pieces)
        // A piece that cannot be read stops it too.
        assertFalse(StreamSeparation.run(1_000, 300, 0, { true }, { 0L }, { _, _ -> }, { a, _ -> a < 300 }, { _, _ -> }, {}))
    }
}
