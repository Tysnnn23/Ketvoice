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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Settings → Lyrics (6.1): « Paroles sur l'écran verrouillé » and what Android needs for it, drawn on the host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LockscreenLyricsSettingsHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
        AppSettings.lockscreenLyrics.value = true
    }

    @After fun reset() {
        AppSettings.reduceAnimations.value = false
        AppSettings.lockscreenLyrics.value = true
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

    @Test fun theSwitchAndTheNotificationRow() {
        if (com.ketvoice.app.BuildConfig.IS_QUEST) return // no lock screen on Quest: the rows are not there.
        shadowOf(compose.activity.application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.settings.SettingsScreen() } }
        compose.waitForIdle()
        compose.onNodeWithTag("settings-category-Lyrics").performClick()
        compose.waitForIdle()
        val title = "Paroles sur l'écran verrouillé"
        compose.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Notification des paroles").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(title).performScrollTo()
        image("settings-lockscreen-lyrics")
        compose.onNodeWithText(title).performClick()
        compose.waitForIdle()
        assertFalse(AppSettings.lockscreenLyrics.value)
        compose.onNodeWithText("Notification des paroles").assertDoesNotExist()
    }

    @Test fun refusedNotificationsAreSaid() {
        if (com.ketvoice.app.BuildConfig.IS_QUEST) return
        shadowOf(compose.activity.application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        compose.setContent { KetvoiceTheme(useDark = true) { com.ketvoice.app.ui.settings.SettingsScreen() } }
        compose.waitForIdle()
        compose.onNodeWithTag("settings-category-Lyrics").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Notifications désactivées").performScrollTo().assertIsDisplayed()
    }
}
