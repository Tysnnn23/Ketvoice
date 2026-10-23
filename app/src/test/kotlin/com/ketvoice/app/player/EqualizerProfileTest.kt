package com.ketvoice.app.player

import org.junit.Assert.*
import org.junit.Test

class EqualizerProfileTest {
    @Test fun corruptOrMissingPreferenceValuesAreBounded() {
        assertEquals(listOf(12,-12,0,0,0),EqualizerProfile.normalize(listOf(500,-500)))
    }
    @Test fun flatIsActuallyFlatAtAnyBandCenter() {
        listOf(1f,60f,200f,910f,4000f,14000f,24000f).forEach {
            assertEquals(0,EqualizerProfile.millibels(it,listOf(0,0,0,0,0),-1500,1500))
        }
    }
    @Test fun nativeRangeIsRespectedRatherThanClaimingUnsupportedGain() {
        assertEquals(600,EqualizerProfile.millibels(60f,listOf(12,0,0,0,0),-600,600))
        assertEquals(-600,EqualizerProfile.millibels(14000f,listOf(0,0,0,0,-12),-600,600))
    }
    @Test fun anchorGainsAreNotChangedByTheInterpolation() {
        val gains=listOf(5,3,-1,2,4)
        EqualizerProfile.frequencies.forEachIndexed { i,hz ->
            assertEquals(gains[i]*100,EqualizerProfile.millibels(hz.toFloat(),gains,-1500,1500))
        }
    }
}
