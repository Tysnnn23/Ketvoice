package com.ketvoice.app.player

import org.junit.Assert.*
import org.junit.Test

class EqualizerProfileTest {
    @Test fun corruptOrMissingPreferenceValuesAreBounded() {
        assertEquals(listOf(12, -12, 0, 0, 0, 0, 0, 0, 0, 0), EqualizerProfile.normalize(listOf(500, -500)))
    }
    @Test fun flatIsActuallyFlatAtAnyFrequency() {
        listOf(1f, 31f, 60f, 200f, 910f, 4000f, 14000f, 24000f).forEach {
            assertEquals(0, EqualizerProfile.millibels(it, List(10) { 0 }, -1500, 1500))
        }
    }
    @Test fun nativeRangeIsRespectedRatherThanClaimingUnsupportedGain() {
        assertEquals(600, EqualizerProfile.millibels(31f, listOf(12) + List(9) { 0 }, -600, 600))
        assertEquals(-600, EqualizerProfile.millibels(16000f, List(9) { 0 } + listOf(-12), -600, 600))
    }
    @Test fun bandGainsAreNotChangedByTheInterpolation() {
        val gains = listOf(5, 3, -1, 2, 4, 0, -3, 6, 1, -2)
        EqualizerProfile.frequencies.forEachIndexed { i, hz ->
            assertEquals(gains[i] * 100, EqualizerProfile.millibels(hz.toFloat(), gains, -1500, 1500))
        }
    }
    @Test fun fiveBandSettingsOfEarlierVersionsSoundTheSameOnTenBands() {
        val old = listOf(6, 3, 0, -2, 4)
        val ten = EqualizerProfile.normalize(old)
        assertEquals(10, ten.size)
        assertEquals(6, ten[0]); assertEquals(6, ten[1])  // 31 and 62 Hz: at or below the old 60 Hz band
        assertEquals(4, ten[9])                           // 16 kHz: above the old 14 kHz band
        EqualizerProfile.legacyFrequencies.forEachIndexed { i, hz ->
            assertEquals(old[i].toFloat(), EqualizerProfile.db(hz.toFloat(), ten), 1.1f)
        }
    }
    @Test fun everyPresetHasTenBandsInRangeAndCutoffsRise() {
        EqualizerProfile.presets.values.forEach { gains -> assertEquals(10, gains.size); assertTrue(gains.all { it in -12..12 }) }
        val cutoffs = (0 until 10).map(EqualizerProfile::cutoffHz)
        assertTrue(cutoffs.zipWithNext().all { (a, b) -> b > a })
        assertEquals(20_000f, cutoffs.last(), 0f)
    }
}
