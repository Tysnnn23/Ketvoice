package com.ketvoice.app

import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import androidx.media3.session.MediaLibraryService
import com.ketvoice.app.player.PlaybackService
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** The whole app starts with its playback service bound, as on a phone (6.1.0 crashed at launch). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35, 36], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppLaunchWithServiceHostTest {
    @Test fun theAppStartsWithItsService() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        val component = ComponentName(app, PlaybackService::class.java)
        shadowOf(app).setComponentNameAndServiceForBindService(component,
            service.get().onBind(Intent(MediaLibraryService.SERVICE_INTERFACE).setComponent(component)))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        repeat(40) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250)) }
        controller.pause().stop().destroy()
        service.destroy()
    }
}
