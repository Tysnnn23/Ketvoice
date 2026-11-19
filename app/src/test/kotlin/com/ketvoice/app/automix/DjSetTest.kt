package com.ketvoice.app.automix

import com.ketvoice.app.automix.DjSet.Title
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DjSetTest {
    @Test fun `the energy the set aims for rises, peaks and comes down`() {
        assertEquals(DjSet.START, DjSet.target(0f), 1e-4f)
        assertEquals(1f, DjSet.target(DjSet.PEAK_AT), 1e-4f)
        assertEquals(DjSet.END, DjSet.target(1f), 1e-4f)
        val curve = (0..20).map { DjSet.target(it / 20f) }
        val peak = curve.indexOf(curve.max())
        assertTrue((1..peak).all { curve[it] >= curve[it - 1] })
        assertTrue((peak + 1 until curve.size).all { curve[it] <= curve[it - 1] })
    }

    @Test fun `ranks put the measured energies from 0 to 1 and leave the others unknown`() {
        val ranks = DjSet.ranks(listOf(.8f, Float.NaN, .2f, .5f))
        assertEquals(1f, ranks[0], 0f); assertTrue(ranks[1].isNaN()); assertEquals(0f, ranks[2], 0f); assertEquals(.5f, ranks[3], 0f)
    }

    @Test fun `the order follows the energy curve`() {
        val energies = listOf(.9f, .1f, .5f, .3f, .7f, .2f, .8f, .4f, .6f)
        val titles = energies.map { Title(bpm = 124f, key = 0, energy = it) }
        val order = DjSet.order(titles)
        assertEquals(titles.indices.toList(), order.sorted())
        val played = order.map { energies[it] }
        val peak = played.indexOf(played.max())
        assertTrue("peak at $peak in $played", peak in 4..7)
        assertTrue("$played", played.first() < .5f && played.last() < .9f)
        assertTrue(DjSet.cost(order, titles) <= DjSet.cost(titles.indices.toList().toIntArray(), titles))
    }

    @Test fun `pinned titles keep their places`() {
        val titles = List(8) { i -> Title(bpm = 100f + 7 * ((i * 5) % 8), key = (i * 7) % 12, energy = ((i * 3) % 8) / 8f, pinned = i == 0 || i == 4) }
        val order = DjSet.order(titles)
        assertEquals(0, order[0]); assertEquals(4, order[4])
        assertEquals(titles.indices.toList(), order.sorted())
    }

    @Test fun `the tempo path is smooth`() {
        val bpms = listOf(128f, 120f, 126f, 122f, 124f, 121f, 127f)
        val order = DjSet.order(bpms.map { Title(bpm = it, key = 0) })
        val path = order.map { bpms[it] }
        assertTrue("path $path", path.zipWithNext().all { (a, b) -> abs(a - b) <= 2.01f })
    }

    @Test fun `keys move to their Camelot neighbours`() {
        // C, then G and D (one step each on the wheel), and F sharp which clashes with all three.
        val keys = listOf(0, 6, 2, 7)
        val order = DjSet.order(keys.mapIndexed { i, k -> Title(bpm = 124f, key = k, pinned = i == 0) })
        val path = order.map { keys[it] }
        assertEquals(listOf(0, 7, 2, 6), path)
        assertEquals(1, path.zipWithNext().count { (a, b) -> MusicalKey.relation(a, b) == MusicalKey.Relation.Clash })
    }

    @Test fun `energy comes from level, tempo and beat`() {
        val calm = SegmentAnalysis(0, 30_000, bpm = 84f, confidence = .5f, loudnessDb = -20f)
        val loud = SegmentAnalysis(0, 30_000, bpm = 128f, confidence = .9f, loudnessDb = -8f)
        assertTrue(DjSet.energyOf(loud) > DjSet.energyOf(calm))
        assertTrue(DjSet.energyOf(null, null).isNaN())
    }
}
