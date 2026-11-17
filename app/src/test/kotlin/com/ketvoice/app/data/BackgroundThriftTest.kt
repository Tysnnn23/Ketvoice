package com.ketvoice.app.data

import com.ketvoice.app.automix.BackgroundThrift
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Background playback on phones whose battery manager stops busy apps (6.0.0 Beta 5, an Honor phone). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundThriftTest {
    @Test fun workBesideTheMusicIsKeptToTheLeastOnlyAwayAndOnBattery() {
        assertTrue(BackgroundThrift.active(inFront = false, charging = false))
        assertFalse("the app on screen", BackgroundThrift.active(inFront = true, charging = false))
        assertFalse("charging", BackgroundThrift.active(inFront = false, charging = true))
    }

    @Test fun honorPhonesOpenTheirAppLaunchScreenFirst() {
        assertTrue(BackgroundPlayback.honorLike("HONOR")); assertTrue(BackgroundPlayback.honorLike("Huawei"))
        assertFalse(BackgroundPlayback.honorLike("samsung"))
        val first = BackgroundPlayback.vendorIntents("HONOR").first().component!!
        assertEquals("com.hihonor.systemmanager", first.packageName)
        assertEquals("com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity", first.className)
    }
}
