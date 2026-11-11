package com.ketvoice.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import com.ketvoice.app.ui.common.GlassSlider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.AppLanguage
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.settings.GlassAppearancePreview
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
class GlassPreviewHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun setup() {
        SettingsStore.load(compose.activity)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.glassStyle.value = 0
        AppSettings.glassTint.value = .25f
        SettingsStore.save()
    }

    @After fun reset() {
        AppSettings.glassStyle.value = 0
        AppSettings.glassTint.value = 0f
        SettingsStore.save()
    }

    @Test fun transparencyChangesLivePersistsOnReleaseAndCancelRestoresTheOriginal() {
        var visible by mutableStateOf(true)
        compose.setContent { KetvoiceTheme(useDark = false) { if (visible) GlassAppearancePreview { visible = false } } }
        compose.onNodeWithTag("glass-tint-slider").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(.73f)) }
        compose.runOnIdle { assertEquals(.73f, AppSettings.glassTint.value, .0001f) }
        compose.runOnIdle { AppSettings.glassTint.value = 0f; SettingsStore.load(compose.activity) }
        assertEquals("Slider release must persist the actual continuous value", .73f, AppSettings.glassTint.value, .0001f)
        compose.onNodeWithText("Annuler").performScrollTo().performClick()
        assertFalse(visible)
        compose.runOnIdle { AppSettings.glassTint.value = 0f; SettingsStore.load(compose.activity) }
        assertEquals("Cancel must persist the original value", .25f, AppSettings.glassTint.value, .0001f)
    }

    @Test fun styleAndTintRemainIndependentAndSurviveReloadAfterApply() {
        var visible by mutableStateOf(true)
        compose.setContent { KetvoiceTheme(useDark = true) { if (visible) GlassAppearancePreview { visible = false } } }
        compose.onNodeWithText("Dépoli").performScrollTo().performClick()
        assertEquals("Style preview must not commit prematurely", 0, AppSettings.glassStyle.value)
        compose.onNodeWithTag("glass-tint-slider").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(.61f)) }
        compose.onNodeWithText("Appliquer").performScrollTo().performClick()
        assertFalse(visible)
        compose.runOnIdle { AppSettings.glassStyle.value = 0; AppSettings.glassTint.value = 0f; SettingsStore.load(compose.activity) }
        assertEquals(1, AppSettings.glassStyle.value)
        assertEquals(.61f, AppSettings.glassTint.value, .0001f)
    }

    @Test fun previewSheetExposesItsAccessibleSliderAndButtons() {
        compose.setContent { KetvoiceTheme(useDark = true) { GlassAppearancePreview {} } }
        compose.onNodeWithContentDescription("Transparence du verre").assertIsDisplayed()
        compose.onNodeWithText("Appliquer").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Annuler").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun keyboardArrowAdjustsTheSliderAndPersists() {
        compose.setContent { KetvoiceTheme(useDark = false) { GlassAppearancePreview {} } }
        compose.onNodeWithTag("glass-tint-slider").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(.30f, AppSettings.glassTint.value, .0001f) }
        compose.runOnIdle { AppSettings.glassTint.value = 0f; SettingsStore.load(compose.activity) }
        assertEquals(.30f, AppSettings.glassTint.value, .0001f)
    }

    @Test @Config(qualifiers = "w320dp-h480dp-mdpi")
    fun shortWindowAndLargeFontCanScrollToEveryPreviewAction() {
        compose.setContent { KetvoiceTheme(useDark = true) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) { GlassAppearancePreview {} }
        } }
        compose.onNodeWithTag("glass-tint-slider").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Opaque").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Appliquer").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Annuler").assertIsDisplayed()
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
}
