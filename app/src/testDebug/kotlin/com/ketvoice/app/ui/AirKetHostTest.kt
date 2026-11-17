package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.airket.*
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.airket.AirKetMenu
import com.ketvoice.app.ui.airket.AirKetReceiveContent
import com.ketvoice.app.ui.airket.AirKetSendContent
import com.ketvoice.app.ui.airket.AirKetSummaryContent
import com.ketvoice.app.ui.airket.ReplaceOrMerge
import com.ketvoice.app.ui.common.LocalSheetMaxHeight
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.net.InetAddress

/** Air Ket's screens (7.0) rendered on the host, not a phone. Glass draws flat here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AirKetHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var language = ""
    private var chosen = false

    @org.junit.Before fun french() {
        language = com.ketvoice.app.data.AppSettings.language.value; chosen = com.ketvoice.app.data.AppSettings.languageChosen.value
        com.ketvoice.app.data.AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        com.ketvoice.app.data.AppSettings.languageChosen.value = true
    }

    @org.junit.After fun restore() {
        com.ketvoice.app.data.AppSettings.language.value = language; com.ketvoice.app.data.AppSettings.languageChosen.value = chosen
    }

    private fun save(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
        view.draw(canvas)
        val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png")
        file.parentFile?.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Sheet(dark: Boolean, content: @Composable ColumnScope.() -> Unit) = KetvoiceTheme(useDark = dark) {
        Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF2A1F35) else Color(0xFFD9D2C8)), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.padding(12.dp).widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(34.dp))
                .background(LocalTheme.current.card.copy(alpha = .94f))) {
                CompositionLocalProvider(LocalSheetMaxHeight provides 1_000.dp) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                }
            }
        }
    }

    private val sending = AirKetSendUi(AirKetSendUi.Phase.Waiting, "246810", AirKetCode.link("246810", "192.168.1.20", 41234),
        secondsLeft = 272, wrongTries = 1, titles = 312, downloadBytes = 4_800_000_000L)

    @Test fun theSendScreenShowsTheCodeAndTheQrCode() {
        compose.setContent { Sheet(dark = true) { AirKetSendContent(sending, onClose = {}, onStop = {}, onRestart = {}) } }
        compose.onNodeWithTag("airket-code").assertIsDisplayed()
        compose.onNodeWithText("246 810").assertIsDisplayed()
        compose.onNodeWithTag("airket-qr").assertIsDisplayed()
        compose.onNodeWithText("Expire dans 4:32").assertIsDisplayed()
        save("airket-send")
    }

    @Test fun theSendScreenInLightWhileSending() {
        compose.setContent { Sheet(dark = false) {
            AirKetSendContent(sending.copy(phase = AirKetSendUi.Phase.Sending, sentBytes = 1_300_000_000L, bytesPerSecond = 38_000_000L),
                onClose = {}, onStop = {}, onRestart = {})
        } }
        compose.onNodeWithTag("airket-stop").assertIsDisplayed()
        save("airket-send-light")
    }

    @Test fun theReceiveScreenFindsTheOldPhoneAndTakesTheCode() {
        val found = AirKetFound("AirKet-1", "Pixel 8", InetAddress.getByName("192.168.1.20"), 41234)
        compose.setContent { Sheet(dark = true) {
            AirKetReceiveContent(AirKetReceiveUi(AirKetReceiveUi.Phase.Searching, found = listOf(found), prefilledCode = "246810"), onClose = {})
        } }
        compose.onNodeWithTag("airket-code-field").assertIsDisplayed()
        compose.onNodeWithText("Pixel 8").assertIsDisplayed()
        save("airket-receive")
    }

    @Test fun theOfferAsksBeforeDownloadsAndBeforeOverwriting() {
        val offer = AirKetOffer("Pixel 8", 380_000, 312, 1_900, 4_800_000_000L, 4, localHasData = true, heldBytes = 600_000_000L)
        compose.setContent { Sheet(dark = true) { AirKetReceiveContent(AirKetReceiveUi(AirKetReceiveUi.Phase.Offer, offer = offer), onClose = {}) } }
        compose.onNodeWithTag("airket-accept").assertIsDisplayed()
        compose.onNodeWithTag("airket-downloads").assertIsDisplayed()
        save("airket-offer")
    }

    @Test fun theReplaceOrMergeQuestionHasBothAnswers() {
        var answer: Boolean? = null
        compose.setContent { KetvoiceTheme(useDark = true) { ReplaceOrMerge(onChoose = { answer = it }, onCancel = {}) } }
        compose.onNodeWithText("Remplacer").assertIsDisplayed()
        compose.onNodeWithText("Fusionner").performClick()
        assertTrue(answer == false)
    }

    @Test fun theProgressScreenHasACancel() {
        compose.setContent { Sheet(dark = true) {
            AirKetReceiveContent(AirKetReceiveUi(AirKetReceiveUi.Phase.Receiving, label = "3f9a7c01.flac", doneBytes = 1_500_000_000L,
                totalBytes = 4_800_000_000L, bytesPerSecond = 41_000_000L), onClose = {})
        } }
        compose.onNodeWithTag("airket-cancel").assertIsDisplayed()
        compose.onNodeWithTag("airket-progress").assertIsDisplayed()
        save("airket-progress")
    }

    @Test fun theSummaryListsWhatArrivedAndWhatWasLeftOut() {
        val summary = AirKetSummary(playlists = 14, favourites = 480, titles = 309, files = 1_870, failed = 2, bytes = 4_790_000_000L,
            seconds = 372, leftOutDash = 3, withDownloads = true, replaced = false)
        compose.setContent { Sheet(dark = true) { AirKetSummaryContent(summary) {} } }
        compose.onNodeWithTag("airket-summary").assertIsDisplayed()
        save("airket-summary")
    }

    @Test fun theMenuAndTheErrors() {
        compose.setContent { Sheet(dark = false) { AirKetMenu({}, {}, {}) } }
        compose.onNodeWithTag("airket-send").assertIsDisplayed()
        compose.onNodeWithTag("airket-receive").assertIsDisplayed()
        save("airket-menu")
    }

    @Test fun aWrongCodeShowsItsMessage() {
        compose.setContent { Sheet(dark = true) {
            AirKetReceiveContent(AirKetReceiveUi(AirKetReceiveUi.Phase.Failed, error = AirKetReceiveUi.Error.WrongCode), onClose = {})
        } }
        compose.onNodeWithText("Code incorrect.").assertIsDisplayed()
    }
}
