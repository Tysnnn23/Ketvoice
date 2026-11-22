package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.theme.KetvoiceTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** « Recherche dans les réglages » (8.4): typed at the top of Settings, a result opens its page at its row. Drawn on the host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsSearchHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun french() {
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
    }

    @After fun reset() { AppSettings.reduceAnimations.value = false; AppUi.closeOverlays() }

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

    @Test fun aQueryWithoutAccentsFindsTheRowAndOpensItsPage() {
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.settings.SettingsScreen() } }
        compose.waitForIdle()
        compose.onNodeWithText("Rechercher un réglage").assertIsDisplayed()
        compose.onNodeWithTag("settings-search").performTextInput("egaliseur")
        compose.waitForIdle()
        compose.onNodeWithTag("settings-category-Playback").assertDoesNotExist()
        compose.onNodeWithText("Égaliseur").assertIsDisplayed()
        image("settings-search-results")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Égaliseur").performClick()
        compose.mainClock.advanceTimeBy(600)
        // The Playback page, with the row brought into view and lit.
        compose.onNodeWithTag("settings-focus").assertIsDisplayed()
        compose.onNodeWithText("Égaliseur").assertIsDisplayed()
        image("settings-search-opened")
        compose.mainClock.autoAdvance = true
    }

    @Test fun nothingFoundIsSaid() {
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.settings.SettingsScreen() } }
        compose.waitForIdle()
        compose.onNodeWithTag("settings-search").performTextInput("zzqx")
        compose.waitForIdle()
        compose.onNodeWithText("Aucun réglage ne correspond à « zzqx »").assertIsDisplayed()
        image("settings-search-empty")
    }
}
