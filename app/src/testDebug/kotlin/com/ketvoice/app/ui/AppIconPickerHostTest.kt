package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.settings.AppIconPicker
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** The launcher icon chooser shows each icon over a wallpaper (6.0.0 Beta 4), rendered on the host, not a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppIconPickerHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun eachIconIsShownAndPicked() {
        var picked = -1
        compose.setContent { KetvoiceTheme(useDark = true) { AppIconPicker(1, onPick = { picked = it }, onClose = {}) } }
        for (id in 0..3) compose.onNodeWithTag("icon-option-$id").assertExists()
        compose.onNodeWithTag("icon-option-2").performClick()
        compose.runOnIdle { assertEquals(2, picked) }
        // The dialog has its own window: draw that one.
        val windows = ReflectionHelpers.callStaticMethod<Any>(Class.forName("android.view.WindowManagerGlobal"), "getInstance")
        @Suppress("UNCHECKED_CAST")
        val views = ReflectionHelpers.getField<java.util.ArrayList<android.view.View>>(windows, "mViews")
        compose.runOnIdle {
            val view = views.last()
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/app-icon-picker.png")
            file.parentFile?.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
