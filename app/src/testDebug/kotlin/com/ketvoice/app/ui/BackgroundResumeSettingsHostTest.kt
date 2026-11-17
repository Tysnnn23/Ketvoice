package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.theme.KetvoiceTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Settings → Playback (6.1): the automatic resume beside the background-playback rows, drawn on the host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackgroundResumeSettingsHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
    }

    @After fun reset() {
        AppSettings.reduceAnimations.value = false
        AppSettings.autoResume.value = true
        AppUi.closeOverlays(); AppUi.selectTab(AppUi.HOME)
    }

    private fun image(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun theResumeSwitchSitsWithTheBackgroundRows() {
        if (com.ketvoice.app.BuildConfig.IS_QUEST) return // the background rows are phone and tablet only.
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.settings.SettingsScreen() } }
        compose.waitForIdle()
        compose.onNodeWithTag("settings-category-Playback").performClick()
        compose.waitForIdle()
        val title = "Reprendre automatiquement la lecture après un arrêt par le système"
        compose.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        // Exact alarms are not granted on the host: the row that asks for them shows under the switch.
        compose.onNodeWithText("Reprise en moins d'une minute").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(title).performScrollTo()
        image("settings-background-resume")
        val above = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        val below = compose.onNodeWithText("Reprise en moins d'une minute").fetchSemanticsNode().boundsInRoot
        assertTrue("the alarms row follows the switch", below.top > above.bottom)
        // Turned off, the switch keeps its place and the alarms row goes.
        compose.onNodeWithText(title).performClick()
        compose.waitForIdle()
        assertFalse(AppSettings.autoResume.value)
        compose.onNodeWithText("Reprise en moins d'une minute").assertDoesNotExist()
    }
}
