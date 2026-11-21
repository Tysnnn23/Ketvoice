package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.ketvoice.app.data.Track
import com.ketvoice.app.remote.RemoteDevice
import com.ketvoice.app.remote.RemoteNow
import com.ketvoice.app.remote.RemoteOp
import com.ketvoice.app.remote.RemoteTitle
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.player.RemoteDevicesList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** « Vos appareils » in « Sortie audio »: the PC playing, opened with its controls; rendered on the host, not a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemoteDevicesHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun thePcThatPlaysIsOpenWithItsControls() {
        val now = 1_000_000L
        val pc = RemoteDevice("pc-device-0001", "DESKTOP-4F2K", "pc", RemoteNow(
            Track("tidal:1", "Legends Never Die", "Juice WRLD", "Legends Never Die", durationMs = 200_000), true, 61_000, 200_000, now, .6f, 0,
            listOf(RemoteTitle(1, "Righteous", "Juice WRLD"), RemoteTitle(2, "Wishing Well", "Juice WRLD"))))
        val tablet = RemoteDevice("tablet-device1", "Galaxy Tab S9", "tablet", null)
        val ops = mutableListOf<Pair<String, RemoteOp>>()
        val sent = mutableListOf<String>()
        compose.setContent { KetvoiceTheme(useDark = true) {
            Column(Modifier.fillMaxSize().background(Color(0xFF1C1C1E))) {
                RemoteDevicesList(listOf(pc, tablet), { now + 4_000 }, { id, op -> ops += id to op }, {}, { sent += it })
            }
        } }
        compose.onAllNodesWithTag("remote-device").fetchSemanticsNodes().also { assertEquals(2, it.size) }
        compose.onAllNodesWithTag("remote-next")[1].performClick()
        assertEquals("pc-device-0001" to RemoteOp.Jump(2), ops.single())
        compose.onNodeWithTag("remote-send").assertIsDisplayed().performClick()
        assertEquals(listOf("pc-device-0001"), sent)
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/remote-devices.png")
            file.parentFile?.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
