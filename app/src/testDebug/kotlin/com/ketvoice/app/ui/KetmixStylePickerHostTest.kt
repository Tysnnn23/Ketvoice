package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.automix.MapPointsBackend
import com.ketvoice.app.automix.MixPointStore
import com.ketvoice.app.automix.MixStyle
import com.ketvoice.app.automix.SegmentAnalysis
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.player.EditorState
import com.ketvoice.app.ui.player.KetmixEditorScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** 7.6 (ketmix76): the style picker of the Ketmix editor shows « Coupe », « Sur le drop » and « Relais des voix » and plays them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KetmixStylePickerHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun points() { MixPointStore.use(MapPointsBackend()) }
    @After fun forget() { MixPointStore.use(null) }

    private fun state(): EditorState {
        val state = EditorState(Track("demo-out", "Nuit blanche", "Lumen", "Lumen", 200_000L), Track("demo-in", "Sous la pluie", "Aster", "Aster", 214_000L), 200_000L, 214_000L)
        state.out.analysis = SegmentAnalysis(155_000, 45_000, 124f, 155_000.0, .9f, 0, -11f, 155_000, 196_000, 155_000, 186_000,
            hardEnding = false, phraseAtMs = 155_000.0, key = 7, keyStrength = .7f)
        state.into.analysis = SegmentAnalysis(0, 40_000, 126f, 300.0, .9f, 0, -10f, 300, 40_000, 15_780, 40_000, phraseAtMs = 300.0, key = 8, keyStrength = .7f)
        state.vocalEndMs = 170_000L
        state.defaultStyle = MixStyle.Auto
        state.intensity = 1
        return state
    }

    private fun snapshot(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png")
            file.parentFile?.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun theNewStylesAreInThePickerAndArePlayed() {
        val state = state()
        compose.setContent { KetvoiceTheme(useDark = true) { KetmixEditorScreen(state, onClose = {}) } }
        compose.onNodeWithTag("ketmix-style-Drop").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(MixStyle.Drop, state.choice.style)
        snapshot("ketmix-styles-phone")
        compose.onNodeWithTag("ketmix-style-Voices").performScrollTo().performClick()
        compose.onNodeWithTag("ketmix-style-Cut").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(MixStyle.Cut, state.choice.style)
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-xlarge-land-mdpi")
    fun theTabletPickerShowsEveryStyle() {
        val state = state()
        compose.setContent { KetvoiceTheme(useDark = true) { KetmixEditorScreen(state, onClose = {}) } }
        // 8.0: the Studio toolbar and its hint stand above the page, so on Quest (the phone layout in a short landscape
        // window) the chips start below the fold. performScrollTo only moves the nearest scroller (the chips' own row):
        // bring the section into the page's view first.
        compose.onNodeWithText("Détails").performScrollTo()
        compose.onNodeWithTag("ketmix-style-Voices").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(MixStyle.Voices, state.choice.style)
        snapshot("ketmix-styles-tablet")
    }
}
