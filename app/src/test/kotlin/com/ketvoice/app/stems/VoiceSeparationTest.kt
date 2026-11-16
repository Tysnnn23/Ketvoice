package com.ketvoice.app.stems

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the voice slider asks for when a title was never separated (6.0.0 Beta 3). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceSeparationTest {
    @Test fun aTitleIsReadFromWhereThePlayerLoadedIt() {
        VoiceSeparation.heard("qa:one", Uri.parse("https://example.org/one.m4a"))
        assertEquals("https://example.org/one.m4a", VoiceSeparation.source("qa:one").toString())
        assertNull("a title the players never loaded has no address", VoiceSeparation.source("qa:never"))
        // Only the last twelve titles are remembered.
        repeat(12) { VoiceSeparation.heard("qa:later$it", Uri.parse("https://example.org/$it.m4a")) }
        assertNull(VoiceSeparation.source("qa:one"))
        assertNotNull(VoiceSeparation.source("qa:later11"))
    }

    @Test fun withoutAModelNothingStarts() {
        if (VoiceSeparation.modelReady) return
        VoiceSeparation.request("qa:no-model")
        val state = VoiceSeparation.state.value
        assertFalse("nothing is separated without a model: $state",
            state is VoiceSeparation.State.Working && state.trackId == "qa:no-model")
        assertFalse(VoiceSeparation.busy)
    }
}
