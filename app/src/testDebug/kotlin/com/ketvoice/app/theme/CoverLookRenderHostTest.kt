package com.ketvoice.app.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppLanguage
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.LiquidGlassSwitch
import com.ketvoice.app.ui.common.Text
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Software renders (host Skia) of a Ket 27 page coloured by covers that are hard to read on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h420dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverLookRenderHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pagesButtonsAndTextTakeTheCoverAndStayReadable() {
        AppSettings.languageChosen.value = true
        AppSettings.language.value = AppLanguage.FRENCH.label
        val covers = mapOf("yellow" to Color(0xFFFFD60A), "navy" to Color(0xFF101A40), "red" to Color(0xFFE53935), "grey" to Color(0xFF7F7F7F))
        var dark by mutableStateOf(false)
        var cover by mutableStateOf(Color.Unspecified)
        var shown: KetTheme? = null
        compose.setContent {
            KetvoiceTheme(useDark = dark, cover = CoverLook(cover.takeIf { it.isSpecified }, 1f, cover.takeIf { it.isSpecified }, 1f)) {
                val th = LocalTheme.current
                shown = th
                Column(Modifier.fillMaxSize().background(th.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Bibliothèque", color = th.onBackground, style = Ket27.Type.largeTitle)
                    Text("12 titres · 48 min", color = th.secondary, style = Ket27.Type.subheadline)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Ket27.Metrics.groupRadius)).background(th.card).padding(16.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Fond selon la pochette", color = th.onBackground, style = Ket27.Type.body)
                                Text("Le texte reste toujours lisible", color = th.secondary, style = Ket27.Type.footnote)
                            }
                            LiquidGlassSwitch(true, onCheckedChange = null)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KetTextButton("Tout lire", {}, primary = true)
                        Box(Modifier.clip(RoundedCornerShape(999.dp)).background(th.accent.copy(alpha = CoverLegibility.CAPSULE_TINT))) {
                            KetTextButton("Aléatoire", {})
                        }
                        Box(Modifier.clip(RoundedCornerShape(999.dp)).background(th.accent).padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text("Lecture", color = CoverLegibility.onFill(th.accent), style = Ket27.Type.headline)
                        }
                    }
                }
            }
        }
        for ((name, color) in covers) for (theme in listOf(false, true)) {
            compose.runOnIdle { cover = color; dark = theme }
            compose.waitForIdle()
            val th = shown!!
            assertTrue(CoverLegibility.contrast(th.accent, th.background) >= CoverLegibility.TEXT)
            assertTrue(CoverLegibility.contrast(th.secondary, th.card) >= CoverLegibility.TEXT)
            image("cover-page-$name-${if (theme) "dark" else "light"}")
        }
    }

    private fun image(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/look/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
