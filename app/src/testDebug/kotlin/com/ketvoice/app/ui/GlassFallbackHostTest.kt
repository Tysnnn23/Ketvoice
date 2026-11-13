package com.ketvoice.app.ui

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.glass.*
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.common.Text
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Android API models on the host, not device GPU or hardware blur validation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 35], qualifiers = "w400dp-h900dp-mdpi")
class GlassFallbackHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun reset() {
        AppSettings.liquidGlass.value = true
        AppSettings.reduceAnimations.value = false
        AppSettings.reduceDynamicBlur.value = false
        AppSettings.highPerformanceMode.value = false
        AppSettings.glassTint.value = 0f
        AppSettings.glassStyle.value = 0
    }

    @Test fun backdropControlStaysUsableAcrossApiFallbackAndReducedMotion() {
        var pressed by mutableIntStateOf(0)
        AppSettings.liquidGlass.value = true
        AppSettings.reduceAnimations.value = false
        AppSettings.reduceDynamicBlur.value = false
        AppSettings.highPerformanceMode.value = false
        compose.setContent { KetvoiceTheme(useDark = false) {
            val backdrop = rememberGlassBackdrop(Color.Blue)
            Box(Modifier.fillMaxSize()) {
                BackdropCapture(backdrop, Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().background(Color.Blue)) }
                CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
                    GlassPanel(Modifier.width(200.dp), RoundedCornerShape(24.dp), GlassParams.pill()) {
                        Text("Control fixture", modifier = Modifier.clickable { pressed++ }.padding(16.dp))
                    }
                }
            }
        } }
        if (Build.VERSION.SDK_INT < 33) {
            assertEquals(GlassRenderMode.Blur, glassRenderMode(Build.VERSION.SDK_INT, true, true, false, false, false, false, false, false))
        }
        compose.onNodeWithText("Control fixture").assertIsDisplayed().performClick()
        compose.runOnIdle { AppSettings.reduceAnimations.value = true }
        compose.onNodeWithText("Control fixture").assertIsDisplayed().performClick()
        compose.runOnIdle { AppSettings.highPerformanceMode.value = true }
        compose.onNodeWithText("Control fixture").assertIsDisplayed().performClick()
        assertEquals(3, pressed)
    }

    /** The Liquid 27 touch light observes pointers; it must never steal the control's own click. */
    @Test fun liquid27ControlKeepsItsClicksAcrossFallbacks() {
        var pressed by mutableIntStateOf(0)
        AppSettings.glassStyle.value = GlassStyles.LIQUID_27
        AppSettings.glassTint.value = .5f
        compose.setContent { KetvoiceTheme(useDark = true) {
            val backdrop = rememberGlassBackdrop(Color.Magenta)
            Box(Modifier.fillMaxSize()) {
                BackdropCapture(backdrop, Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().background(Color.Magenta)) }
                CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
                    GlassPanel(Modifier.width(200.dp), RoundedCornerShape(24.dp), GlassParams.pill(dark = true)) {
                        Text("Liquid fixture", modifier = Modifier.clickable { pressed++ }.padding(16.dp))
                    }
                }
            }
        } }
        compose.onNodeWithText("Liquid fixture").assertIsDisplayed().performClick()
        compose.runOnIdle { AppSettings.reduceAnimations.value = true }
        compose.onNodeWithText("Liquid fixture").assertIsDisplayed().performClick()
        compose.runOnIdle { AppSettings.reduceAnimations.value = false; AppSettings.liquidGlass.value = false }
        compose.onNodeWithText("Liquid fixture").assertIsDisplayed().performClick()
        assertEquals(3, pressed)
    }
}
