package com.ketvoice.desktop.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.remote.RemoteDevice
import com.ketvoice.app.remote.RemoteHandoff
import com.ketvoice.app.remote.RemoteKey
import com.ketvoice.app.remote.RemoteKeyStore
import com.ketvoice.app.remote.RemoteNow
import com.ketvoice.app.remote.RemoteOp
import com.ketvoice.app.remote.RemoteTitle
import com.ketvoice.desktop.audio.AudioSource
import com.ketvoice.desktop.audio.FakeAudioEngine
import com.ketvoice.desktop.ui.DevicesPanel
import com.ketvoice.desktop.ui.DevicesView
import com.ketvoice.desktop.ui.Fixtures
import com.ketvoice.desktop.ui.theme.KetDesktopTheme
import com.ketvoice.desktop.ui.theme.LocalKet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phone control on Windows: the secret kept per account, the PC's player driven from the phone, the popover. */
class DesktopRemoteTest {
    private val context = android.content.Context.app
    private val engine = FakeAudioEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun tearDown() { RemoteKeyStore.forget(context); Player.resetForTest(); scope.cancel() }

    @Test fun theSecretFollowsTheAccountItCameFrom() {
        RemoteKeyStore.forget(context)
        val made = RemoteKeyStore.ensure(context, "user-a", null)
        assertTrue(RemoteKey.valid(made))
        assertEquals(made, RemoteKeyStore.ensure(context, "user-a", RemoteKey.generate()), "a device keeps its own once it has one")
        assertNull(RemoteKeyStore.load(context, "user-b"), "another account never reads it")
        val merged = RemoteKey.generate()
        RemoteKeyStore.adopt(context, "user-a", merged)
        assertEquals(merged, RemoteKeyStore.load(context, "user-a"))
        assertEquals(merged, RemoteKeyStore.secret.value)
        RemoteKeyStore.forget(context)
        val fromAccount = RemoteKey.generate()
        assertEquals(fromAccount, RemoteKeyStore.ensure(context, "user-a", fromAccount), "a fresh device takes the account's")
    }

    @Test fun thePhoneDrivesThePlayerOfThePc() {
        Player.resetForTest()
        Player.autoplayCandidates = { emptyList() }
        Player.attach(engine, { AudioSource.Url("https://fixture.test/${it.id}.flac") }, scope)
        val queue = (0 until 4).map { Track("tidal:$it", "Title $it", "Artist", "Album", durationMs = 200_000) }
        DesktopRemote.port.play(RemoteHandoff(queue, 2, 61_000, play = true))
        assertEquals(2, Player.state.value.index)
        val deadline = System.currentTimeMillis() + 5_000
        while (engine.calls.none { it.startsWith("load ") } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(engine.calls.last { it.startsWith("load ") }.contains("tidal:2.flac") &&
            engine.calls.last { it.startsWith("load ") }.endsWith("61000 true"), engine.calls.toString())
        DesktopRemote.port.run(RemoteOp.Jump(3))
        assertEquals(3, Player.state.value.index)
        DesktopRemote.port.run(RemoteOp.Volume(.3f))
        assertEquals(.3f, Player.volume.value)
        DesktopRemote.port.run(RemoteOp.Remove(3))
        assertEquals(4, Player.state.value.queue.size, "the title that plays is never removed")
        DesktopRemote.port.run(RemoteOp.Remove(1))
        assertEquals(3, Player.state.value.queue.size)
        val seen = DesktopRemote.port.playback()
        assertEquals("tidal:3", seen.current?.id)
        DesktopRemote.port.pause()
        assertTrue(!Player.state.value.isPlaying)
        assertNotEquals("", DesktopRemote.computerName())
    }

    @Test fun rendersThePopover() {
        Fixtures.installCovers()
        AppSettings.reduceAnimations.value = true
        val now = 1_000_000L
        val phone = RemoteDevice("phone-device-01", "Pixel 9", "phone", RemoteNow(
            Track("tidal:1", "Legends Never Die", "Juice WRLD", "Legends Never Die", durationMs = 200_000), true, 61_000, 200_000, now, .7f, 4,
            listOf(RemoteTitle(5, "Righteous", "Juice WRLD"), RemoteTitle(6, "Wishing Well", "Juice WRLD"), RemoteTitle(7, "Blood On My Jeans", "Juice WRLD"))))
        val tablet = RemoteDevice("tablet-device1", "Galaxy Tab S9", "tablet", null)
        val out = File("build/desktop-ui-preview").apply { mkdirs() }
        val cases = listOf(
            "devices" to DevicesView(true, true, listOf(phone, tablet), null, false),
            "devices-empty" to DevicesView(true, true, emptyList(), "Lucid Dreams · Juice WRLD", true),
            "devices-signed-out" to DevicesView(false, false, emptyList(), null, false),
        )
        for ((name, view) in cases) for (dark in listOf(true, false)) {
            val ops = mutableListOf<RemoteOp>()
            val scene = ImageComposeScene(600, 1000, Density(1.5f)) {
                KetDesktopTheme(dark) {
                    Box(Modifier.fillMaxSize().background(LocalKet.current.ground).padding(20.dp), contentAlignment = Alignment.TopCenter) {
                        DevicesPanel(view, { now + 5_000 }, { _, op -> ops += op }, {}, {})
                    }
                }
            }
            try {
                var t = 0L
                repeat(20) { scene.render(t); t += 50_000_000L }
                File(out, "$name-${if (dark) "dark" else "light"}.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            } finally { scene.close() }
        }
        assertTrue(File(out, "devices-dark.png").length() > 5_000)
    }
}
