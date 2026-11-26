package com.ketvoice.app.tv

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The TV's first defaults follow its tier, and never overwrite what the listener saved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "television")
class TvDefaultsHostTest {
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("tv-defaults-test", Context.MODE_PRIVATE)

    @Test fun aLightTvStartsOnLightAnalysisAndThePerformanceMode() {
        prefs.edit().clear().commit()
        TvDefaults.apply(prefs, TvTier.Light)
        assertEquals(0, prefs.getInt("ketmixAnalysis", -1))
        assertTrue(prefs.getBoolean("highPerformanceMode", false))
    }

    @Test fun aBalancedTvKeepsThePhonesDefaults() {
        prefs.edit().clear().commit()
        TvDefaults.apply(prefs, TvTier.Balanced)
        assertEquals(1, prefs.getInt("ketmixAnalysis", -1))
        assertFalse(prefs.getBoolean("highPerformanceMode", true))
    }

    @Test fun savedChoicesAreNeverTouched() {
        prefs.edit().clear().putInt("ketmixAnalysis", 2).putBoolean("highPerformanceMode", false).commit()
        TvDefaults.apply(prefs, TvTier.Light)
        assertEquals(2, prefs.getInt("ketmixAnalysis", -1))
        assertFalse(prefs.getBoolean("highPerformanceMode", true))
    }

    @Test fun theDeviceIsReadFromAndroid() {
        val device = TvCapability.read(RuntimeEnvironment.getApplication())
        assertTrue(device.cores >= 1)
        assertEquals(35, device.sdk)
        // Whatever the host, the tier is one of the three and is kept once read.
        TvCapability.init(RuntimeEnvironment.getApplication())
        assertEquals(TvCapability.tier(device), TvCapability.current)
    }
}
