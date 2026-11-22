package com.ketvoice.app.player

import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

/** « Choix de la sortie audio » (8.4): which devices are offered, which one plays, and what a disconnection does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioOutputsTest {
    @After fun reset() { AudioOutputs.choose(null) }

    private fun raw(id: Int, type: Int, name: String = "", address: String = "") = AudioOutputs.Raw(id, type, name, address)

    @Test fun onlyMusicOutputsAreOfferedOnePerDevice() {
        val listed = AudioOutputs.list(listOf(
            raw(1, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "Pixel"),
            raw(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Pixel"),
            raw(3, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE, "Pixel"),
            // One pair of earbuds publishes its call profile and its media profile: only the media one is offered.
            raw(4, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds Pro", "AA:BB"),
            raw(5, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds Pro", "AA:BB"),
            raw(6, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "Pixel"),
            raw(7, AudioDeviceInfo.TYPE_USB_HEADSET, "DAC X"),
            raw(8, AudioDeviceInfo.TYPE_HEARING_AID, "Hearing L", "CC:DD"),
            raw(9, AudioDeviceInfo.TYPE_REMOTE_SUBMIX),
            raw(10, AudioDeviceInfo.TYPE_TELEPHONY),
            raw(11, AudioDeviceInfo.TYPE_HDMI, "TV")))
        assertEquals(listOf(OutputKind.Phone, OutputKind.Wired, OutputKind.Usb, OutputKind.Bluetooth, OutputKind.HearingAid, OutputKind.Hdmi),
            listed.map { it.kind })
        assertEquals(5, listed.single { it.kind == OutputKind.Bluetooth }.id)
        assertEquals(2, listed.single { it.kind == OutputKind.Phone }.id)
    }

    @Test fun leAudioCountsAsBluetooth() {
        val listed = AudioOutputs.list(listOf(raw(1, AudioDeviceInfo.TYPE_BLE_HEADSET, "Watch buds", "EE"), raw(2, AudioDeviceInfo.TYPE_BLE_SPEAKER, "Speaker", "FF")))
        assertEquals(listOf(OutputKind.Bluetooth, OutputKind.Bluetooth), listed.map { it.kind })
    }

    @Test fun theChosenOutputPlaysWhileConnectedOtherwiseAndroidsRoute() {
        val outputs = AudioOutputs.list(listOf(raw(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), raw(5, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds", "AA"),
            raw(6, AudioDeviceInfo.TYPE_WIRED_HEADSET)))
        // Nothing chosen: headphones in Bluetooth win, as Android does for media.
        assertEquals(5, AudioOutputs.active(outputs, null)?.id)
        // The speaker chosen while the earbuds are connected: the speaker.
        assertEquals(2, AudioOutputs.active(outputs, 2)?.id)
        // Wired only, then the speaker only.
        assertEquals(6, AudioOutputs.active(outputs.filter { it.id != 5 }, null)?.id)
        assertEquals(2, AudioOutputs.active(outputs.filter { it.id == 2 }, null)?.id)
    }

    @Test fun aChoiceWhoseDeviceLeftIsForgotten() {
        val both = AudioOutputs.list(listOf(raw(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), raw(5, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds", "AA")))
        AudioOutputs.choose(both.single { it.id == 5 })
        AudioOutputs.refresh(both)
        assertEquals(5, AudioOutputs.chosen.value)
        AudioOutputs.refresh(both.filter { it.id == 2 })
        assertNull(AudioOutputs.chosen.value)
        assertNull(AudioOutputs.reconcile(emptyList(), 7))
    }

    @Test fun theDevicesAndroidReportsAreRead() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val audio = shadowOf(context.getSystemService(AudioManager::class.java))
        audio.setOutputDevices(listOf(
            AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build(),
            AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE).build(),
            AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_WIRED_HEADPHONES).build()))
        assertEquals(listOf(OutputKind.Phone, OutputKind.Wired), AudioOutputs.read(context).map { it.kind })
        audio.setOutputDevices(listOf(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()))
        assertEquals(listOf(OutputKind.Phone), AudioOutputs.read(context).map { it.kind })
    }
}
