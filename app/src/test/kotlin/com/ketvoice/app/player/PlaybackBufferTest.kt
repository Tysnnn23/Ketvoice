package com.ketvoice.app.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackBufferTest {
    @Test fun theBufferHoldsMinutesOfSoundWithinASensibleSize() {
        assertTrue(PlaybackBuffer.MAX_MS >= 180_000)
        assertTrue(PlaybackBuffer.MIN_MS in 15_000..PlaybackBuffer.MAX_MS)
        // 320 kbit/s for the full three minutes fits under the byte cap; hi-res stops at the cap.
        assertTrue(320_000L / 8 * PlaybackBuffer.MAX_MS / 1000 < PlaybackBuffer.TARGET_BYTES)
        assertTrue(PlaybackBuffer.TARGET_BYTES <= 32 * 1024 * 1024)
    }

    @Test fun onlyAudibleWaitsReachTheJournal() {
        assertNull(PlaybackBuffer.stallNote(1_500, inFront = false, restrictedData = true))
        assertEquals("waited 4 s for data", PlaybackBuffer.stallNote(4_200, inFront = true, restrictedData = false))
        assertEquals("waited 12 s for data · app in the background · data saver limits Ketvoice in the background",
            PlaybackBuffer.stallNote(12_000, inFront = false, restrictedData = true))
    }
}

class MemoryReliefTest {
    @Suppress("DEPRECATION")
    @Test fun modelsGoWhenTheAppLeavesTheScreenOnBattery() {
        val c = android.content.ComponentCallbacks2::class.java
        val hidden = c.getField("TRIM_MEMORY_UI_HIDDEN").getInt(null)
        val critical = c.getField("TRIM_MEMORY_RUNNING_CRITICAL").getInt(null)
        val moderate = c.getField("TRIM_MEMORY_RUNNING_MODERATE").getInt(null)
        assertTrue(com.ketvoice.app.MemoryRelief.releaseModels(hidden, charging = false))
        assertFalse("charging keeps them for the library work", com.ketvoice.app.MemoryRelief.releaseModels(hidden, charging = true))
        assertTrue(com.ketvoice.app.MemoryRelief.releaseModels(critical, charging = true))
        assertFalse(com.ketvoice.app.MemoryRelief.releaseModels(moderate, charging = false))
        assertNull(com.ketvoice.app.MemoryRelief.note(moderate))
        assertNotNull(com.ketvoice.app.MemoryRelief.note(critical))
    }
}
