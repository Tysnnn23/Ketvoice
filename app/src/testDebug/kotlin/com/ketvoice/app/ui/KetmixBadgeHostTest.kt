package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.player.KetmixMixingBadge
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Ketmix's sign between the times under the seek bar (6.0.0 Beta 5), rendered on the host, not a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h200dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KetmixBadgeHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun theBadgeShowsWhileAMixPlaysOnly() {
        val progress = mutableStateOf<Float?>(null)
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF3B2A20)).padding(24.dp), contentAlignment = Alignment.Center) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Text("1:01", color = Color.White.copy(alpha = .6f))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { KetmixMixingBadge(progress.value, Color.White.copy(alpha = .7f)) }
                    androidx.compose.material3.Text("-2:27", color = Color.White.copy(alpha = .6f))
                }
            }
        } }
        compose.onNodeWithTag("ketmix-badge").assertDoesNotExist()
        progress.value = .5f
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("ketmix-badge").assertIsDisplayed()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/ketmix-badge.png")
            file.parentFile?.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        progress.value = null
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("ketmix-badge").assertDoesNotExist()
    }
}
