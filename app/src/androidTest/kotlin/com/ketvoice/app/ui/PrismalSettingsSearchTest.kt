package com.ketvoice.app.ui

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.glass.*
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.chrome.GlassTabCapsule
import com.ketvoice.app.ui.common.CompactSearchField
import com.ketvoice.app.ui.common.LiquidGlassSwitch
import com.ketvoice.app.ui.settings.SettingsScreen
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Synthetic checks in .qa only. Compile != execution; never treated as phone validation. */
class PrismalSettingsSearchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun reset() {
        AppUi.closeOverlays()
        AppSettings.reduceAnimations.value = false
        AppSettings.reduceDynamicBlur.value = false
        AppSettings.highPerformanceMode.value = false
        AppSettings.liquidGlass.value = true
    }

    @Test fun narrowSearchPlaceholderIsOneLineAtLargeFontScale() {
        var query by mutableStateOf("")
        compose.setContent { KetvoiceTheme(useDark = false) {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.6f)) {
                Box(Modifier.width(280.dp)) {
                    CompactSearchField(query, { query = it }, {}, Modifier.testTag("compact-search"))
                }
            }
        } }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Rechercher un titre ou un artiste…", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        compose.onNodeWithTag("compact-search").assertHeightIsEqualTo(52.dp)
        compose.onNode(hasSetTextAction()).performTextInput("A deliberately long artist and track query")
        compose.onNodeWithTag("compact-search").assertHeightIsEqualTo(52.dp)
    }

    @Test fun searchStillSubmitsAndCanBeCleared() {
        var query by mutableStateOf("")
        var submitted = ""
        compose.setContent { KetvoiceTheme(useDark = false) {
            CompactSearchField(query, { query = it }, { submitted = query })
        } }
        compose.onNode(hasSetTextAction()).performTextInput("Test artist")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.runOnIdle { assertEquals("Test artist", submitted) }
        compose.onNodeWithContentDescription("Effacer").performClick()
        compose.runOnIdle { assertEquals("", query) }
    }

    @Test fun settingsStartWithCategoriesInsteadOfEveryToggle() {
        compose.setContent { KetvoiceTheme(useDark = false) { SettingsScreen() } }
        compose.onNodeWithTag("settings-home").assertIsDisplayed()
        compose.onNodeWithTag("settings-category-Appearance").assertIsDisplayed()
        compose.onNodeWithText("Flou dynamique").assertDoesNotExist()
        compose.onNodeWithText("Qualité en Wi-Fi").assertDoesNotExist()
    }

    @Test fun categoryBackAndNestedDesignKeepSettingsReachable() {
        compose.setContent { KetvoiceTheme(useDark = false) { SettingsScreen() } }
        compose.onNodeWithTag("settings-category-Appearance").performClick()
        compose.onNodeWithText("DESIGN KETVOICE").performScrollTo().performClick()
        compose.onNodeWithText("Chaque finition peut être retirée séparément, sans modifier vos playlists ni votre musique.").assertExists()
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.onNodeWithText("Thème").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.onNodeWithTag("settings-home").assertIsDisplayed()
        compose.onNodeWithTag("settings-category-Lyrics").performClick()
        compose.onNodeWithText("Source des paroles").assertIsDisplayed()
        compose.onNodeWithText("Qualité en Wi-Fi").assertDoesNotExist()
    }

    @Test fun opticalSwitchIsOneAccessibleControlAndTogglesOnlyOnce() {
        var checked by mutableStateOf(false)
        var changes = 0
        compose.setContent { KetvoiceTheme(useDark = false) {
            LiquidGlassSwitch(checked, { checked = it; changes++ }, Modifier.testTag("optical-switch"))
        } }
        compose.onNodeWithTag("optical-switch").assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(1, changes) }
    }

    @Test fun navigationExposesSelectedTabsAndSwitchesWithoutWrappingLabels() {
        AppUi.selectTab(AppUi.HOME)
        AppSettings.reduceAnimations.value = true
        compose.setContent { KetvoiceTheme(useDark = false) {
            Box(Modifier.width(300.dp)) { GlassTabCapsule(false) }
        } }
        compose.onNodeWithContentDescription("Accueil").assertIsSelected()
        compose.onNodeWithContentDescription("Explorer").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(AppUi.EXPLORER, AppUi.tab.intValue) }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Bibliothèque", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
    }

    @Test fun prismalAdaptationCompilesAsARealRuntimeShaderOnSupportedDevices() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val shader = android.graphics.RuntimeShader(GLASS_AGSL)
        shader.setFloatUniform("size", 300f, 60f)
        shader.setFloatUniform("offset", -20f, -20f)
        shader.setFloatUniform("contentSize", 340f, 100f)
        shader.setFloatUniform("cornerRadii", 30f, 30f, 30f, 30f)
        shader.setFloatUniform("refractionHeight", 12f)
        shader.setFloatUniform("refractionAmount", 24f)
    }

    @Test fun mattePanelsAndControlsRemainReadableWithEffectsDisabled() {
        AppSettings.reduceDynamicBlur.value = true
        AppSettings.highPerformanceMode.value = true
        compose.setContent { KetvoiceTheme(useDark = true) {
            Column(Modifier.background(Color(0xFF111118)).padding(24.dp)) {
                GlassPanel(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp), GlassParams.card(20f, true)) {
                    com.ketvoice.app.ui.common.Text("Matte settings group", localize = false, modifier = Modifier.padding(20.dp))
                }
                LiquidGlassSwitch(true, {}, Modifier.testTag("static-switch"))
            }
        } }
        compose.onNodeWithText("Matte settings group").assertIsDisplayed()
        compose.onNodeWithTag("static-switch").assertIsOn()
    }
}
