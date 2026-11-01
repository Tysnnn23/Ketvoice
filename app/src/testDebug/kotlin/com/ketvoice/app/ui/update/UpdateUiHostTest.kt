package com.ketvoice.app.ui.update

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.theme.KetvoiceTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import coil.imageLoader
import android.graphics.Bitmap
import android.graphics.Canvas
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import org.robolectric.Shadows.shadowOf

/** Mocked release/check/installer UI in a host Android model; no actual installation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdateUiHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val candidate = UpdateInfo("v99.0.0", "Ketvoice QA update", listOf("![Ketvoice](${UpdateChecker.CANONICAL_BANNER})", "## Automatic updates", "A host fixture."),
        UpdateFeed.DOWNLOAD_PREFIX + "v99.0.0/Ketvoice-99.0.0.apk", "https://github.com/Tysnnn23/Ketvoice/releases", "2026-11-01", 123)

    @Before @After fun reset() {
        UpdateBadge.pendingRequest = 0; UpdateBadge.handledRequest = 0; UpdateBadge.forceRefresh = false
        UpdateBadge.checking = false; UpdateBadge.lastInfo = null; UpdateBadge.available = null; UpdateBadge.postponedTag = null
    }

    private fun bannerReady() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("release-banner").fetchSemanticsNodes().any { it.config[ReleaseBannerLoaded] }
        }
        compose.mainClock.advanceTimeBy(500)
    }

    private fun screenshot() {
        compose.runOnIdle {
            val view = ShadowDialog.getLatestDialog().window!!.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val banner = compose.onNodeWithTag("release-banner").fetchSemanticsNode().boundsInRoot
            var pictured = 0
            for (y in (banner.top.toInt() + 10) until (banner.bottom.toInt() - 10) step 3)
                for (x in (banner.left.toInt() + 25) until (banner.right.toInt() - 25) step 3) {
                    val pixel = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1))
                    if (pixel != android.graphics.Color.rgb(20, 21, 26)) pictured++
                }
            assertTrue("Banner must paint actual image pixels", pictured > 200)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/update-dialog-host.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun foregroundAutoCheckShowsNotesAndReleaseBannerWithoutAManualRequest() {
        var checks = 0
        compose.setContent { KetvoiceTheme(useDark = true) {
            UpdateHost(check = { manual -> assertFalse(manual); checks++; candidate }, cached = { null })
        } }
        compose.onNodeWithText(candidate.name).assertIsDisplayed()
        compose.onNodeWithText("Automatic updates").assertIsDisplayed()
        compose.onNodeWithTag("release-banner").assertIsDisplayed()
        bannerReady()
        compose.runOnIdle { assertTrue(checks > 0) }
    }

    @Test fun releaseWithoutAnImageDoesNotInventABanner() {
        compose.setContent { KetvoiceTheme(useDark = true) {
            UpdateDialog(candidate.copy(notes = listOf("## Changes", "A release without a banner.")), {}, {})
        } }
        compose.onNodeWithText("Changes").assertIsDisplayed()
        compose.onNodeWithTag("release-banner").assertDoesNotExist()
    }

    @Test fun manualRequestIsNotCancelledWhenItsRequestIsConsumed() {
        val response = CompletableDeferred<UpdateInfo?>()
        compose.setContent { KetvoiceTheme(useDark = true) {
            UpdateHost(check = { force -> if (force) response.await() else null }, cached = { null })
        } }
        compose.runOnIdle { UpdateBadge.requestCheck() }
        compose.runOnIdle { response.complete(candidate) }
        compose.onNodeWithText(candidate.name).assertIsDisplayed()
        bannerReady()
        compose.onNodeWithText("Télécharger").assertIsEnabled()
        compose.onNodeWithText("Plus tard").performClick()
        compose.onNodeWithText(candidate.name).assertDoesNotExist()
        compose.runOnIdle { UpdateBadge.requestShow() }
        compose.onNodeWithText(candidate.name).assertIsDisplayed()
        bannerReady()
    }

    @Test fun installerLaunchReEnablesActionsAndRetryReusesTheDownloadedFile() {
        var downloads = 0
        var launches = 0
        val fixture = File(compose.activity.cacheDir, "mock-verified-apk.bin").apply { writeText("mock") }
        compose.setContent { KetvoiceTheme(useDark = true) {
            UpdateDialog(candidate, {}, {}, download = { _, _, progress -> downloads++; progress(1f); fixture },
                install = { _, file, _ -> assertEquals(fixture, file); launches++; true })
        } }
        bannerReady()
        screenshot()
        compose.onNodeWithText("Télécharger").performClick()
        compose.onNodeWithText("Installer").assertIsEnabled().performClick()
        compose.onNodeWithText("Plus tard").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, downloads); assertEquals(2, launches) }
        fixture.delete()
    }

    @Test fun deniedInstallPermissionKeepsTheVerifiedFileAndAnEnabledRetry() {
        val fixture = File(compose.activity.cacheDir, "mock-permission-apk.bin").apply { writeText("mock") }
        var downloads = 0
        compose.setContent { KetvoiceTheme(useDark = true) {
            UpdateDialog(candidate, {}, {}, download = { _, _, _ -> downloads++; fixture }, install = { _, _, _ -> false })
        } }
        bannerReady()
        compose.onNodeWithText("Télécharger").performClick()
        compose.onNodeWithText("Installer").assertIsEnabled()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, request.intent.action)
        compose.runOnIdle {
            shadowOf(compose.activity).receiveResult(request.intent, android.app.Activity.RESULT_CANCELED, null)
        }
        compose.onNodeWithText("Autorisez Ketvoice à installer des applications, puis relancez").assertExists()
        compose.onNodeWithText("Installer").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, downloads) }
        fixture.delete()
    }

    @Test fun installerFailureIsVisibleInsteadOfReportingSuccessOrStayingBusy() {
        val fixture = File(compose.activity.cacheDir, "mock-failed-apk.bin").apply { writeText("mock") }
        compose.setContent { KetvoiceTheme(useDark = true) {
            UpdateDialog(candidate, {}, {}, download = { _, _, _ -> fixture },
                install = { _, _, _ -> throw android.content.ActivityNotFoundException("no installer") })
        } }
        bannerReady()
        compose.onNodeWithText("Télécharger").performClick()
        compose.onNodeWithText("Installation impossible").assertExists()
        compose.onNodeWithText("Installer").assertIsEnabled()
        fixture.delete()
    }
}
