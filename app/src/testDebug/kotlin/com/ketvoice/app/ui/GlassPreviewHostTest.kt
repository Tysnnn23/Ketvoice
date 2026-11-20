package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppLanguage
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.glass.GlassClarity
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.GlassSlider
import com.ketvoice.app.ui.settings.GlassTransparencyCard
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** The stepped transparency of the glass in Settings: live, saved on release, accessible, rendered. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassPreviewHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun setup() {
        SettingsStore.load(compose.activity)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.glassIntensity.value = GlassClarity.DEFAULT
        SettingsStore.save()
    }

    @After fun reset() {
        AppSettings.glassIntensity.value = GlassClarity.DEFAULT
        SettingsStore.save()
    }

    @Composable private fun Card(dark: Boolean) = KetvoiceTheme(useDark = dark) {
        Column(Modifier.fillMaxSize().background(LocalTheme.current.background).verticalScroll(rememberScrollState())
            .padding(16.dp)) { GlassTransparencyCard() }
    }

    private fun reload(): Float {
        compose.runOnIdle { AppSettings.glassIntensity.value = -1f; SettingsStore.load(compose.activity) }
        return AppSettings.glassIntensity.value
    }

    @Test fun theDefaultIsTodaysLookOnAContinuousSlider() {
        compose.setContent { Card(false) }
        compose.onNodeWithTag("glass-tint-level").assertTextEquals("Standard")
        val info = compose.onNodeWithTag("glass-tint-slider").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(GlassClarity.DEFAULT, info.current, 1e-4f)
        assertEquals("continuous (7.5)", 0, info.steps)
        // The Clair / Teinté tabs of 7.1 are gone: the slider is the one control.
        compose.onNode(hasText("Clair") and hasClickAction()).assertDoesNotExist()
    }

    @Test fun theSliderSetsAnyIntensityAndSavesIt() {
        compose.setContent { Card(false) }
        compose.onNodeWithTag("glass-tint-slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(.47f)) }
        compose.runOnIdle { assertEquals("not snapped", .47f, AppSettings.glassIntensity.value, 1e-4f) }
        compose.onNodeWithTag("glass-tint-level").assertTextEquals("Teinté")
        assertEquals("saved when released", .47f, reload(), 1e-4f)
        compose.onNodeWithTag("glass-tint-slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(0f)) }
        compose.runOnIdle { assertEquals(GlassClarity.MIN, AppSettings.glassIntensity.value, 0f) }
        compose.onNodeWithTag("glass-tint-level").assertTextEquals("Très transparent")
        assertEquals(GlassClarity.MIN, reload(), 0f)
    }

    @Test fun aTapAtTheEndIsTheMostOpaque() {
        compose.setContent { Card(true) }
        compose.onNodeWithTag("glass-tint-slider").performScrollTo().performTouchInput { click(centerRight.copy(x = width - 2f)) }
        compose.runOnIdle { assertEquals(GlassClarity.MAX, AppSettings.glassIntensity.value, .01f) }
        compose.onNodeWithTag("glass-tint-level").assertTextEquals("Le plus lisible")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun keyboardArrowsMoveAndPersist() {
        compose.setContent { Card(false) }
        compose.onNodeWithTag("glass-tint-slider").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(GlassClarity.DEFAULT + .05f, AppSettings.glassIntensity.value, 1e-3f) }
        compose.onNodeWithTag("glass-tint-slider").performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.DirectionLeft) }
        compose.runOnIdle { assertEquals(GlassClarity.DEFAULT - .05f, AppSettings.glassIntensity.value, 1e-3f) }
        assertEquals(GlassClarity.DEFAULT - .05f, reload(), 1e-3f)
    }

    @Test fun theSliderIsNamedAndSaysItsLevel() {
        compose.setContent { Card(true) }
        compose.onNodeWithContentDescription("Intensité du verre").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Standard"))
        compose.onNodeWithText("Transparent").assertExists()
        compose.onNodeWithText("Teinté").assertExists()
    }

    @Test @Config(qualifiers = "w320dp-h480dp-mdpi")
    fun shortWindowAndLargeFontCanScrollToTheSlider() {
        compose.setContent { KetvoiceTheme(useDark = true) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { GlassTransparencyCard() }
            }
        } }
        compose.onNodeWithTag("glass-tint-slider").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Teinté").performScrollTo().assertIsDisplayed()
    }

    @Test fun rtlSliderMapsPhysicalEdgesToLogicalValues() {
        var value by mutableFloatStateOf(.5f)
        compose.setContent { KetvoiceTheme(useDark = false) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                GlassSlider(value, { value = it }, Modifier.width(300.dp).testTag("rtl-slider"))
            }
        } }
        compose.onNodeWithTag("rtl-slider").performTouchInput { click(androidx.compose.ui.geometry.Offset(1f, center.y)) }
        compose.runOnIdle { assertEquals(1f, value, .01f) }
        compose.onNodeWithTag("rtl-slider").performTouchInput { click(androidx.compose.ui.geometry.Offset(width - 1f, center.y)) }
        compose.runOnIdle { assertEquals(0f, value, .01f) }
    }

    /** Software renders of the card (host Skia, not a device GPU) at the clearest, default and most tinted levels. */
    @Test fun rendersTheCardAtEachEndOfTheSlider() {
        var dark by mutableStateOf(false)
        compose.setContent { Card(dark) }
        for (theme in listOf(false, true)) for (level in listOf(GlassClarity.MIN, GlassClarity.DEFAULT, GlassClarity.MAX)) {
            compose.runOnIdle { dark = theme; AppSettings.glassIntensity.value = level }
            compose.waitForIdle()
            compose.onNodeWithTag("glass-tint-level").assertTextEquals(GlassClarity.label(level))
            image("glass-transparency-${if (theme) "dark" else "light"}-$level")
        }
    }

    private fun image(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/look/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
