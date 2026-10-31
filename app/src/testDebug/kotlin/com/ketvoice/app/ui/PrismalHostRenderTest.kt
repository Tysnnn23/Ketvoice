package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.glass.GLASS_AGSL
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.common.CompactSearchField
import com.ketvoice.app.ui.settings.SettingsScreen
import com.ketvoice.app.ui.chrome.FloatingChrome
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Host Skia/Android model, NOT a device, owner session or hardware GPU test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrismalHostRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun reset() {
        AppUi.closeOverlays()
        AppSettings.reduceAnimations.value = false
        AppSettings.reduceDynamicBlur.value = false
        AppSettings.highPerformanceMode.value = false
        @Suppress("UNCHECKED_CAST")
        (Player.state as MutableStateFlow<PlayerUiState>).value = PlayerUiState()
    }

    private fun image(name: String) {
        val directory = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}").apply { mkdirs() }
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            // Host Skia can evaluate the shader in software. This flag only bypasses
            // the Android Java canvas's hardware-only API guard in this test.
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            File(directory, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test fun settingsHomeAndAppearanceNavigateAndRenderOnHost() {
        AppSettings.reduceAnimations.value = true
        compose.setContent { KetvoiceTheme(useDark = false) { SettingsScreen() } }
        compose.onNodeWithTag("settings-home").assertIsDisplayed()
        image("settings-home")
        compose.onNodeWithTag("settings-category-Appearance").performClick()
        compose.onNodeWithText("Thème").performScrollTo().assertIsDisplayed()
        image("settings-appearance")
        compose.onNodeWithContentDescription("Retour").performClick()
        compose.onNodeWithTag("settings-home").assertIsDisplayed()
    }

    @Test fun narrowSearchRemainsOneLineAtLargeFontScaleOnHost() {
        var query by mutableStateOf("")
        compose.setContent { KetvoiceTheme(useDark = false) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.6f)) {
                Column(Modifier.width(280.dp).padding(8.dp)) {
                    CompactSearchField(query, { query = it }, {}, Modifier.testTag("search"))
                }
            }
        } }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Rechercher un titre ou un artiste…", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        compose.onNodeWithTag("search").assertHeightIsEqualTo(52.dp)
        image("compact-search-large-font")
        compose.onNode(hasSetTextAction()).performTextInput("Long artist name and long track title")
        compose.onNodeWithTag("search").assertHeightIsEqualTo(52.dp)
        compose.onNodeWithContentDescription("Effacer").performClick()
        compose.runOnIdle { assertEquals("", query) }
    }

    @Test fun condensedNavigationFitsA320DpViewportWithARealMiniPlayerLayout() {
        AppSettings.reduceAnimations.value = true
        AppUi.selectTab(AppUi.HOME)
        @Suppress("UNCHECKED_CAST")
        (Player.state as MutableStateFlow<PlayerUiState>).value = PlayerUiState(
            queue = listOf(Track("qa:chrome", "A very long title that must not widen the compact player", "QA artist", "QA album")), index = 0)
        compose.setContent { KetvoiceTheme(useDark = false) {
            Box(Modifier.width(320.dp)) { FloatingChrome(true, {}) }
        } }
        val play = compose.onNodeWithContentDescription("Lecture").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithContentDescription("Rechercher").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Mini-player transport must not overlap search", play.right <= search.left)
        compose.onNodeWithContentDescription("Explorer").performClick()
        compose.runOnIdle { assertEquals(AppUi.EXPLORER, AppUi.tab.intValue) }
        image("condensed-navigation-320dp")
    }

    @Test fun prismalShaderCompilesAndRefractsRealSourcePixelsOnHostSkia() {
        val source = Bitmap.createBitmap(360, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        val paint = Paint()
        for (x in 0 until source.width step 8) for (y in 0 until source.height step 8) {
            paint.color = if ((x / 8 + y / 8) % 2 == 0) android.graphics.Color.rgb(50, 110, 180)
                else android.graphics.Color.rgb(225, 130, 100)
            canvas.drawRect(x.toFloat(), y.toFloat(), x + 8f, y + 8f, paint)
        }
        fun render(amount: Float): Bitmap {
        val shader = RuntimeShader(GLASS_AGSL)
        shader.setInputShader("content", BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setFloatUniform("size", 320f, 60f)
        shader.setFloatUniform("offset", -20f, -20f)
        shader.setFloatUniform("contentSize", 360f, 100f)
        shader.setFloatUniform("cornerRadii", 30f, 30f, 30f, 30f)
        shader.setFloatUniform("refractionHeight", 12f)
        shader.setFloatUniform("refractionAmount", amount)
        shader.setFloatUniform("dispersion", .035f)
        shader.setFloatUniform("depthEffect", 0f)
        shader.setFloatUniform("tint", 1f, 1f, 1f, .04f)
        shader.setFloatUniform("saturation", 1.2f)
        shader.setFloatUniform("brightness", 1f)
        shader.setFloatUniform("edgeDark", .055f)
        shader.setFloatUniform("rimLight", .5f)
        shader.setFloatUniform("sheen", .03f)
        shader.setFloatUniform("materialDark", 0f)
        shader.setFloatUniform("lightDir", -.7071f, -.7071f)
            val bitmap = Bitmap.createBitmap(360, 100, Bitmap.Config.ARGB_8888)
            val target = Canvas(bitmap)
            ReflectionHelpers.setField(target, "mAllowHwFeaturesInSwMode", true)
            target.drawRect(0f, 0f, 360f, 100f, Paint().apply { this.shader = shader })
            return bitmap
        }
        val glass = render(24f)
        val flat = render(0f)
        assertEquals(0, android.graphics.Color.alpha(glass.getPixel(20, 20)))
        assertEquals(255, android.graphics.Color.alpha(glass.getPixel(180, 50)))
        var shifted = 0
        // A single checkerboard scanline can land on the same periodic tile after
        // refraction. Compare an edge band instead of assuming one pixel phase.
        for (y in 21..26) for (x in 70..280) if (glass.getPixel(x, y) != flat.getPixel(x, y)) shifted++
        val directory = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}").apply { mkdirs() }
        File(directory, "prismal-optical-grid.png").outputStream().use { glass.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(directory, "prismal-flat-grid.png").outputStream().use { flat.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("Edge refraction must move the sampled source, not just tint it (shifted=$shifted)", shifted > 500)
        source.recycle(); glass.recycle(); flat.recycle()
    }
}
