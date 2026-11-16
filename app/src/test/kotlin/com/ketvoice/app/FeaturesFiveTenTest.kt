package com.ketvoice.app

import com.ketvoice.app.data.BackgroundPlayback
import com.ketvoice.app.data.PlaybackJournal
import com.ketvoice.app.player.NetworkRecovery
import com.ketvoice.app.util.LyricAudio
import androidx.media3.common.PlaybackException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FeaturesFiveTenTest {
    /** A lost network waits for the network to come back instead of trying other sources until playback stops. */
    @Test fun networkLossesAreRecoveredNotFailedOver() {
        assertTrue(NetworkRecovery.isNetworkError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertTrue(NetworkRecovery.isNetworkError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertFalse(NetworkRecovery.isNetworkError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertFalse(NetworkRecovery.isNetworkError(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertEquals(400L, NetworkRecovery.backoffMs(1))
        assertTrue(NetworkRecovery.backoffMs(2) > NetworkRecovery.backoffMs(1))
        assertEquals(6_400L, NetworkRecovery.backoffMs(NetworkRecovery.MAX_RETRIES))
    }

    @Test fun manufacturerBatteryScreensAreKnown() {
        for (brand in listOf("Xiaomi", "HUAWEI", "OnePlus", "realme", "vivo", "samsung", "OPPO", "HONOR", "POCO"))
            assertTrue(brand, BackgroundPlayback.vendorIntents(brand).isNotEmpty())
        assertTrue(BackgroundPlayback.vendorIntents("Google").isEmpty())
    }

    @Test fun journalKeepsEventsAndNamesExits() {
        PlaybackJournal.clear()
        PlaybackJournal.add("playback service started")
        assertTrue(PlaybackJournal.snapshot().last().endsWith("playback service started"))
        assertEquals("low memory", PlaybackJournal.reason(android.app.ApplicationExitInfo.REASON_LOW_MEMORY))
        assertTrue(PlaybackJournal.reason(android.app.ApplicationExitInfo.REASON_SIGNALED).contains("battery"))
    }

    @Test fun videoSoundFadesInAndOut() {
        val rate = 1_000
        val data = ShortArray(2 * rate) { 10_000 }
        LyricAudio.fade(data, rate, 2)
        assertEquals(0, data[0].toInt())
        assertEquals(0, data[data.size - 1].toInt())
        assertEquals(10_000, data[rate].toInt())
    }
}
