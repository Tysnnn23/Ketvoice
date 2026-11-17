package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.common.CanvasLoadingPill
import com.ketvoice.app.ui.common.CanvasPill
import com.ketvoice.app.ui.settings.CanvasCacheRows
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.io.RandomAccessFile

/** The Canvas cache rows of Settings and the loading pill (6.2.3), rendered on the host, not a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasCacheHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val track = Track(id = "pill-test", title = "Titre", artist = "Artiste", album = "Album")

    @After fun reset() {
        CanvasRepo.setLoading(track.id, false)
        AppSettings.reduceAnimations.value = false
        AppSettings.canvasCacheMb.value = 512
    }

    private fun sparse(file: File, mb: Long) { file.parentFile?.mkdirs(); RandomAccessFile(file, "rw").use { it.setLength(mb * 1_000_000L) } }

    private fun save(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
        view.draw(canvas)
        val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png")
        file.parentFile?.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun theSettingsRowsShowTheSizeSplitTheLimitAndTheClearButton() {
        val files = compose.activity.filesDir
        sparse(File(files, "canvas/a.mp4"), 300); sparse(File(files, "canvas/b.mp4"), 100)
        sparse(File(files, "canvas/kept.mp4"), 40)
        File(files, "downloads").mkdirs()
        File(files, "downloads/t.json").writeText("""{"canvasFile":"${File(files, "canvas/kept.mp4").absolutePath}"}""")
        compose.setContent { KetvoiceTheme(useDark = false) {
            Box(Modifier.fillMaxSize().background(Color(0xFFF2F2F7)).padding(top = 24.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState())) { CanvasCacheRows { } }
            }
        } }
        // The app's own translation turns « Mo » into « MB » on the host: the sizes are matched on their number.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("440 M", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Espace utilisé par les Canvas").assertIsDisplayed()
        compose.onNodeWithText("Limite du cache des Canvas").assertIsDisplayed()
        compose.onNodeWithText("Précharger en Wi-Fi").assertIsDisplayed()
        compose.onNodeWithText("440 M", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Les moins récemment", substring = true).assertIsDisplayed()
        save("canvas-cache-settings")
        // The button asks first, then clears everything but what the download needs.
        compose.onNodeWithText("Vider le cache des Canvas").performClick()
        compose.onNodeWithText("Vider le cache des Canvas ?").assertIsDisplayed()
        compose.onNodeWithText(com.ketvoice.app.data.AppText.t("Vider")).performClick()
        compose.waitUntil(5_000) { !File(files, "canvas/a.mp4").exists() }
        assertTrue(File(files, "canvas/kept.mp4").exists())
        assertEquals(false, File(files, "canvas/b.mp4").exists())
    }

    private fun setPill() = compose.setContent { KetvoiceTheme(useDark = true) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF5A3A2A), Color(0xFF14110F)))).padding(24.dp),
            contentAlignment = Alignment.TopEnd) {
            CanvasLoadingPill(track, wanted = true)
        }
    } }

    @Test fun thePillComesOnlyWhenTheCanvasIsLateAndLeavesWithIt() {
        compose.mainClock.autoAdvance = false
        setPill()
        CanvasRepo.setLoading(track.id, true)
        compose.mainClock.advanceTimeBy(CanvasPill.DELAY_MS - 300)
        compose.onNodeWithTag("canvas-pill").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("canvas-pill").assertIsDisplayed()
        save("canvas-pill")
        // The Canvas arrived (or the lookup ended): the pill fades away.
        CanvasRepo.setLoading(track.id, false)
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("canvas-pill").assertDoesNotExist()
    }

    @Test fun aCanvasThatComesQuicklyNeverShowsThePill() {
        compose.mainClock.autoAdvance = false
        setPill()
        CanvasRepo.setLoading(track.id, true)
        compose.mainClock.advanceTimeBy(600)
        CanvasRepo.setLoading(track.id, false)
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithTag("canvas-pill").assertDoesNotExist()
    }

    @Test fun underReducedMotionThePillDoesNotAnimate() {
        AppSettings.reduceAnimations.value = true
        compose.mainClock.autoAdvance = false
        setPill()
        CanvasRepo.setLoading(track.id, true)
        compose.mainClock.advanceTimeBy(CanvasPill.DELAY_MS + 50)
        compose.onNodeWithTag("canvas-pill").assertIsDisplayed()
        CanvasRepo.setLoading(track.id, false)
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("canvas-pill").assertDoesNotExist()
    }

    @Test fun theRuleNeedsAWantedLoadingTitleWithoutCanvas() {
        assertTrue(CanvasPill.waiting(loading = true, hasCanvas = false, wanted = true))
        assertEquals(false, CanvasPill.waiting(loading = true, hasCanvas = true, wanted = true))
        assertEquals(false, CanvasPill.waiting(loading = false, hasCanvas = false, wanted = true))
        assertEquals(false, CanvasPill.waiting(loading = true, hasCanvas = false, wanted = false))
    }
}
