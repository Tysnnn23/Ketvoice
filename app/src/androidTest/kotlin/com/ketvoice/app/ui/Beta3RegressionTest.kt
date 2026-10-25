package com.ketvoice.app.ui

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ketvoice.app.data.*
import com.ketvoice.app.glass.*
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.settings.SettingsScreen
import com.ketvoice.app.ui.sub.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class Beta3RegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var oldLanguage = ""
    private var oldBlur = false
    private var oldPerformance = false
    private var oldGlass = false

    @Before fun prepare() {
        oldLanguage = AppSettings.language.value
        oldBlur = AppSettings.reduceDynamicBlur.value
        oldPerformance = AppSettings.highPerformanceMode.value
        oldGlass = AppSettings.liquidGlass.value
        AppSettings.language.value = "Français"
        AppSettings.reduceDynamicBlur.value = false
        AppSettings.highPerformanceMode.value = false
        AppSettings.liquidGlass.value = true
        AppUi.catalogRequest.value = CatalogRequest("favorites", "qa:empty", "QA collection")
        AppUi.artistRequest.value = AppUi.ArtistRequest("invalid", "QA artist")
        AppUi.selectTab(AppUi.LIBRARY)
        compose.runOnUiThread {
            compose.activity.requestedOrientation = if (com.ketvoice.app.BuildConfig.IS_QUEST)
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    @After fun restore() {
        AppSettings.language.value = oldLanguage
        AppSettings.reduceDynamicBlur.value = oldBlur
        AppSettings.highPerformanceMode.value = oldPerformance
        AppSettings.liquidGlass.value = oldGlass
        AppUi.selectTab(AppUi.LIBRARY)
        AppUi.catalogRequest.value = null
        AppUi.artistRequest.value = null
    }

    @Test fun capturedContentCannotSampleItselfButSiblingChromeStillCan() {
        var inside = true
        var sibling = false
        compose.setContent {
            val backdrop = rememberGlassBackdrop(Color.Black)
            CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
                Box(Modifier.fillMaxSize()) {
                    BackdropCapture(backdrop, Modifier.fillMaxSize()) {
                        inside = LocalBackdropLayer.current != null

                        GlassPanel(Modifier.size(80.dp), CircleShape, GlassParams.circle()) { }
                    }
                    sibling = LocalBackdropLayer.current === backdrop
                    GlassPanel(Modifier.size(40.dp), CircleShape, GlassParams.circle()) { }
                }
            }
        }
        repeat(4) { compose.onRoot().captureToImage() }
        compose.runOnIdle { assertFalse(inside); assertTrue(sibling) }
    }

    @Test fun overlayPagesDrawRepeatedlyWithLiquidGlassEnabled() {
        var page by mutableIntStateOf(0)
        compose.setContent {
            KetvoiceTheme(useDark = true) {
                val backdrop = rememberGlassBackdrop(Color.Black)
                CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
                    BackdropCapture(backdrop, Modifier.fillMaxSize().testTag("captured-page")) {
                        when (page) {
                            0 -> SettingsScreen()
                            1 -> CatalogScreen()
                            2 -> LocalMusicScreen()
                            else -> ArtistScreen()
                        }
                    }
                }
            }
        }
        repeat(2) {
            for (target in 0..3) {
                compose.runOnIdle { page = target }
                compose.onNodeWithContentDescription("Retour").assertExists()
                repeat(2) { compose.onRoot().captureToImage() }
            }
        }
        compose.onNodeWithText("Cette page d'artiste n'a pas pu être chargée.").assertExists()
    }

    @Test fun missingAudioPermissionIsNotReportedAsAnEmptyMusicLibrary() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm revoke ${context.packageName} ${LocalMusic.permission}").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        assertFalse(LocalMusic.hasPermission(context))
        LocalMusic.scan(context)
        assertFalse(LocalMusic.scanned.value)
        compose.setContent { KetvoiceTheme { LocalMusicScreen() } }
        compose.onNodeWithText("Autoriser l'accès").assertExists()
        compose.onNodeWithText("Aucun fichier audio trouvé sur cet appareil.").assertDoesNotExist()
        compose.onRoot().captureToImage()
    }

    @Test fun audioPermissionIsDeclaredForThisAndroidVersion() {
        val info = context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions.orEmpty().contains(LocalMusic.permission))
        assertTrue(info.requestedPermissions.orEmpty().contains(Manifest.permission.INTERNET))
    }
}
