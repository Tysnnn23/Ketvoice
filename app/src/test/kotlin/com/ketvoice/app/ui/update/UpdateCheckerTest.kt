package com.ketvoice.app.ui.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test fun newerPatchMinorAndMajorAreDetected() {
        assertTrue(UpdateChecker.isNewer("v3.5.0", "3.4.3"))
        assertTrue(UpdateChecker.isNewer("3.4.4", "3.4.3"))
        assertTrue(UpdateChecker.isNewer("4.0.0", "3.9.9"))
        assertTrue(UpdateChecker.isNewer("3.10.0", "3.9.0"))
    }

    @Test fun sameOrOlderIsNotOffered() {
        assertFalse(UpdateChecker.isNewer("3.4.3", "3.4.3"))
        assertFalse(UpdateChecker.isNewer("v3.4.3", "3.4.3"))
        assertFalse(UpdateChecker.isNewer("3.4.2", "3.4.3"))
        assertFalse(UpdateChecker.isNewer("2.9.9", "3.0.0"))
    }

    @Test fun stableReleaseReplacesItsOwnBeta() {
        assertTrue(UpdateChecker.isNewer("v3.5.0", "3.5.0-BETA"))
        assertFalse(UpdateChecker.isNewer("v3.5.0-BETA", "3.5.0"))
    }

    @Test fun betaOfNextVersionBeatsCurrentStable() {
        assertTrue(UpdateChecker.isNewer("v3.5.0-BETA", "3.4.3"))
        assertFalse(UpdateChecker.isNewer("v3.4.3-BETA", "3.4.3"))
    }

    @Test fun missingTrailingZerosAreEqual() {
        assertFalse(UpdateChecker.isNewer("3.5", "3.5.0"))
        assertFalse(UpdateChecker.isNewer("3.5.0", "3.5"))
    }

    @Test fun buildMetadataIsIgnored() {
        assertFalse(UpdateChecker.isNewer("3.5.0+42", "3.5.0"))
        assertTrue(UpdateChecker.isNewer("3.5.1+1", "3.5.0+99"))
    }

    @Test fun emptyOrGarbageTagIsNeverNewer() {
        assertFalse(UpdateChecker.isNewer("", "3.4.3"))
        assertFalse(UpdateChecker.isNewer("latest", "3.4.3"))
    }
}
