package com.ketvoice.app.ui.motion

import android.view.HapticFeedbackConstants
import org.junit.Assert.*
import org.junit.Test

class HapticsAndMotionTest {
    @Test fun eachIntentMapsToAPlatformConstantForItsApiLevel() {
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, Haptics.constantFor(Haptics.Kind.Press, 31))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, Haptics.constantFor(Haptics.Kind.Tick, 33))
        assertEquals(HapticFeedbackConstants.SEGMENT_TICK, Haptics.constantFor(Haptics.Kind.Tick, 34))
        assertEquals(HapticFeedbackConstants.CONFIRM, Haptics.constantFor(Haptics.Kind.Confirm, 31))
    }

    @Test fun hapticsFollowTheSettingAndNeverRunOnQuest() {
        assertTrue(Haptics.enabled(setting = true, quest = false))
        assertFalse(Haptics.enabled(setting = false, quest = false))
        assertFalse(Haptics.enabled(setting = true, quest = true))
    }

    @Test fun eitherSystemScaleAtZeroReducesMotion() {
        assertFalse(systemMotionReduced(1f, 1f))
        assertFalse(systemMotionReduced(.5f, .5f))
        assertTrue(systemMotionReduced(0f, 1f))
        assertTrue(systemMotionReduced(1f, 0f))
    }
}
