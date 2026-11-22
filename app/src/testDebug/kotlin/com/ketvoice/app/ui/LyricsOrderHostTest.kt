package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.LyricsOrder
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.settings.LyricsOrderContent
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

/** « Ordre des sources de paroles » (8.4): the list, dragged by a handle or moved by a screen reader. Drawn on the host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsOrderHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun french() {
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
    }

    @After fun reset() { AppSettings.reduceAnimations.value = false; AppSettings.lyricsOrder.value = "" }

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

    @Test fun dragAndScreenReaderMovesReorderTheSources() {
        var order by mutableStateOf(LyricsOrder.default)
        compose.setContent { KetvoiceTheme(useDark = true) {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(com.ketvoice.app.theme.LocalTheme.current.background)) {
                LyricsOrderContent(order, automatic = true, onOrder = { order = it }, onClose = {})
            }
        } }
        compose.waitForIdle()
        image("lyrics-order")
        val first = LyricsOrder.default[0]
        // Dragged down by its handle, the first source goes further down.
        compose.onAllNodesWithTag("lyrics-order-handle")[0].performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, 160f)); up() }
        compose.waitForIdle()
        assertNotEquals(first, order.first())
        assertTrue(order.indexOf(first) > 0)
        assertEquals(LyricsOrder.default.toSet(), order.toSet())
        // « Monter » with a screen reader brings the last one up a place.
        val last = order.last()
        val actions = compose.onNodeWithTag("lyrics-order-" + last).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.first { it.label == "Monter" }.action() }
        compose.waitForIdle()
        assertEquals(last, order[order.lastIndex - 1])
        // Back to the default order.
        compose.onNodeWithText("Rétablir l'ordre par défaut").performClick()
        compose.waitForIdle()
        assertEquals(LyricsOrder.default, order)
    }

    @Test fun aSourcePickedByHandIsSaidToBeUsedAlone() {
        compose.setContent { KetvoiceTheme(useDark = true) { LyricsOrderContent(LyricsOrder.default, automatic = false, onOrder = {}, onClose = {}) } }
        compose.onNodeWithText("Une source choisie dans « Source des paroles » est utilisée seule : cet ordre sert à la source Automatique.").assertIsDisplayed()
    }
}
