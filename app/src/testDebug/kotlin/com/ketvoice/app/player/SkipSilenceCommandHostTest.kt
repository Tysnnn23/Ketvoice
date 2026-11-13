package com.ketvoice.app.player

import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression: the toggle used to travel in the command extras while the service read the arguments. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SkipSilenceCommandHostTest {
    private fun on(value: Boolean) = Bundle().apply { putBoolean("on", value) }

    @Test fun argumentsCarryTheToggle() {
        assertTrue(skipSilenceRequested(on(true), Bundle.EMPTY))
        assertFalse(skipSilenceRequested(on(false), on(true)))
    }

    @Test fun legacyExtrasAreStillHonoured() {
        assertTrue(skipSilenceRequested(Bundle.EMPTY, on(true)))
        assertFalse(skipSilenceRequested(Bundle.EMPTY, Bundle.EMPTY))
    }
}
