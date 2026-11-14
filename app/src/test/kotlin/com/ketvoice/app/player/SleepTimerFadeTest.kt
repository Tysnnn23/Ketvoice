package com.ketvoice.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerFadeTest {
    @Test fun gainStaysFullUntilTheFadeThenFallsToSilence() {
        assertEquals(1f, SleepCountdown.fadeGain(60_000), 0f)
        assertEquals(1f, SleepCountdown.fadeGain(SleepCountdown.FADE_MS), 0f)
        assertEquals(.5f, SleepCountdown.fadeGain(SleepCountdown.FADE_MS / 2), 1e-4f)
        assertEquals(0f, SleepCountdown.fadeGain(0), 0f)
        assertEquals(0f, SleepCountdown.fadeGain(-500), 0f)
    }

    @Test fun aZeroLengthFadeNeverMutes() {
        assertEquals(1f, SleepCountdown.fadeGain(0, fadeMs = 0), 0f)
    }

    @Test fun theMenuOffersFourDelays() {
        assertEquals(listOf(15, 30, 45, 60), SleepCountdown.CHOICES)
    }
}
