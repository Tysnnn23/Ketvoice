package com.ketvoice.app.player

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafeMedia3LoggerHostTest {
    @Test fun mediaErrorsNeverLogTheirMessagesOrSignedUrls() {
        ShadowLog.clear()
        val secret = "https://cdn.example/audio?token=private-fixture"
        SafeMedia3Logger.e("Media3", secret, IllegalStateException(secret))
        val entries = ShadowLog.getLogsForTag("KetvoicePlayback")
        assertEquals(1, entries.size)
        assertFalse(entries.single().msg.contains(secret))
        assertNull(entries.single().throwable)
    }
}
