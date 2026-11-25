package com.ketvoice.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.account.AccountState
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.sync.DeviceState
import com.ketvoice.app.sync.RemoteSession
import com.ketvoice.desktop.connect.DeviceLink
import com.ketvoice.desktop.connect.LiveSync
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** « Connexions » and « Mes données » in the shell: registered pages, the QR code, devices, the resume card, renders. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class ConnectPagesTest {
    private val song = Track("t:1", "Midnight City", "M83", "Hurry Up", durationMs = 240_000)

    @BeforeTest fun setUp() { Fixtures.installCovers(); AppSettings.reduceAnimations.value = true; ConnectUi.register() }
    @AfterTest fun tearDown() {
        AccountSession.showForTest(AccountState())
        DeviceLink.showForTest(DeviceLink.State.Idle)
        LiveSync.showForTest(emptyList(), null)
        ConnectUi.unregister()
    }

    private fun devices(now: Long) = listOf(
        DeviceState("phone-device-01", "Pixel 9", "phone", now - 120_000, RemoteSession("phone-device-01", "Pixel 9", listOf(song), 0, 61_000, true, now - 120_000)),
        DeviceState("pc-device-0002", "BUREAU", "pc", now - 3 * 3600_000L))

    @Test fun signedOutTheConnectionsPageOffersTheQrCode() = runComposeUiTest {
        DeviceLink.showForTest(DeviceLink.State.Showing("ABCDEFGH", System.currentTimeMillis() + 300_000))
        setContent { KetvoiceShell(RecordingPlayer(), Fixtures.library(), FixtureCatalog, dark = true, navigator = remember { Navigator(Route.Extra(ConnectUi.PAGE)) }) }
        waitForIdle()
        onNodeWithTag("page:connections").assertExists()
        onNodeWithTag("qr").assertExists()
        onNodeWithTag("qr-code-text").assertTextEquals("ABCD-EFGH")
        onNodeWithTag("service:discord").assertExists()
        onNodeWithTag("service:lastfm").assertExists()
        scrollTo("service:listenbrainz")
        // No import page in this build: the row says so instead of linking nowhere.
        scrollTo("service:imports")
        onNodeWithTag("imports-open").assertDoesNotExist()
        onNodeWithTag("nav:Connexions").assertExists()
    }

    @Test fun signedInTheDevicesAreListedWithTheirActions() = runComposeUiTest {
        AccountSession.showForTest(AccountState("00000000-0000-4000-8000-000000000001", "kev@example.com"))
        val now = com.ketvoice.app.account.ServerClock.now()
        LiveSync.showForTest(devices(now), null)
        setContent { KetvoiceShell(RecordingPlayer(), Fixtures.library(), FixtureCatalog, dark = false, navigator = remember { Navigator(Route.Extra(ConnectUi.PAGE)) }) }
        waitForIdle()
        onNodeWithTag("qr-sign-in").assertDoesNotExist()
        scrollTo("device-out:pc-device-0002")
        onNodeWithTag("device:phone-device-01").assertExists()
        onNodeWithTag("device-take:phone-device-01").assertExists()
        // Not open right now: it cannot take playback, and a device with nothing written has nothing to transfer.
        onNodeWithTag("device-give:phone-device-01").assertDoesNotExist()
        onNodeWithTag("device-take:pc-device-0002").assertDoesNotExist()
        onNodeWithTag("device-out:pc-device-0002").performClick()
        onNodeWithTag("confirm-dialog").assertExists()
        onNodeWithTag("confirm-cancel").performClick()
    }

    @Test fun homeOffersToResumeWhatAnotherDevicePlayed() = runComposeUiTest {
        val now = com.ketvoice.app.account.ServerClock.now()
        LiveSync.showForTest(emptyList(), RemoteSession("phone-device-01", "Pixel 9", listOf(song), 0, 61_000, false, now - 60_000))
        setContent { KetvoiceShell(RecordingPlayer(), Fixtures.library(), FixtureCatalog, dark = true) }
        waitForIdle()
        onNodeWithTag("resume-elsewhere").assertExists()
        onNodeWithTag("resume-elsewhere-dismiss").performClick()
        waitForIdle()
        onNodeWithTag("resume-elsewhere").assertDoesNotExist()
    }

    @Test fun myDataDeletesOnlyAfterTheTypedWord() = runComposeUiTest {
        AccountSession.showForTest(AccountState("00000000-0000-4000-8000-000000000001", "kev@example.com"))
        setContent { KetvoiceShell(RecordingPlayer(), Fixtures.library(), FixtureCatalog, dark = true, navigator = remember { Navigator(Route.Extra(ConnectUi.MY_DATA_PAGE)) }) }
        waitForIdle()
        onNodeWithTag("page:my-data").assertExists()
        onNodeWithTag("my-data-export").assertExists()
        onNodeWithTag("delete-confirm").performTextInput("supprimer")
        waitForIdle()
        onNodeWithTag("delete-account").assertExists()
    }

    @Test fun settingsHaveTheConnectionsAndProfilesSections() {
        val found = SettingsFind.search("listenbrainz", translate = { it })
        assertTrue(found.any { it.sectionKey == "h:connections" })
        assertTrue(SettingsFind.search("profils sur ce pc", translate = { it }).any { it.sectionKey == "h:profiles" })
        assertEquals("connections", PageRegistry.find(ConnectUi.PAGE)?.id)
    }

    @Test fun rendersOfTheConnectionsPage() {
        val now = com.ketvoice.app.account.ServerClock.now()
        for (dark in listOf(true, false)) {
            AccountSession.showForTest(AccountState())
            DeviceLink.showForTest(DeviceLink.State.Showing("K7PX3MQA", System.currentTimeMillis() + 240_000))
            shoot("win2-connections-signed-out-${if (dark) "dark" else "light"}") {
                KetvoiceShell(RecordingPlayer(), Fixtures.library(), FixtureCatalog, dark = dark, navigator = remember { Navigator(Route.Extra(ConnectUi.PAGE)) })
            }
            AccountSession.showForTest(AccountState("00000000-0000-4000-8000-000000000001", "kev@example.com"))
            LiveSync.showForTest(devices(now), null)
            shoot("win2-connections-${if (dark) "dark" else "light"}") {
                KetvoiceShell(RecordingPlayer(), Fixtures.library(), FixtureCatalog, dark = dark, navigator = remember { Navigator(Route.Extra(ConnectUi.PAGE)) })
            }
        }
        LiveSync.showForTest(emptyList(), RemoteSession("phone-device-01", "Pixel 9", listOf(song), 0, 61_000, false, now - 60_000))
        shoot("win2-home-resume-dark") { KetvoiceShell(RecordingPlayer(), Fixtures.library(), FixtureCatalog, dark = true) }
        shoot("win2-my-data-light") {
            KetvoiceShell(RecordingPlayer(), Fixtures.library(), FixtureCatalog, dark = false, navigator = remember { Navigator(Route.Extra(ConnectUi.MY_DATA_PAGE)) })
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.scrollTo(tag: String) {
        onNodeWithTag("page:connections").performScrollToNode(hasTestTag(tag))
        waitForIdle()
        onNodeWithTag(tag).assertExists()
    }

    private val out = File("build/desktop-ui-preview").apply { mkdirs() }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(1440, 900, Density(1f), content = content)
        try {
            var t = 0L
            repeat(30) { scene.render(t); t += 50_000_000L }
            File(out, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally { scene.close() }
    }
}
