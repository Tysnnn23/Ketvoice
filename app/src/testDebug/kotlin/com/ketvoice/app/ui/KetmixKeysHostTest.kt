package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.automix.LevelKey
import com.ketvoice.app.automix.Levels
import com.ketvoice.app.automix.MapPointsBackend
import com.ketvoice.app.automix.MixPointStore
import com.ketvoice.app.automix.MixStyle
import com.ketvoice.app.automix.SegmentAnalysis
import com.ketvoice.app.automix.Wave
import com.ketvoice.app.automix.WaveBuilder
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.player.EditorState
import com.ketvoice.app.ui.player.KetmixEditorScreen
import com.ketvoice.app.ui.player.setLevels
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
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** 7.6 (voice76): volume keyframes in the Ketmix editor, rendered on the host (not a phone). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KetmixKeysHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun points() { MixPointStore.use(MapPointsBackend()) }
    @After fun forget() { MixPointStore.use(null) }

    private val rate = 11_025
    private val beat = 60_000.0 / 124

    private fun music(fromMs: Long, untilMs: Long, origin: Double): Wave {
        val frames = ((untilMs - fromMs) * rate / 1000).toInt()
        val pcm = ShortArray(frames * 2)
        for (f in 0 until frames) {
            val t = fromMs + f * 1000L / rate
            val inBeat = ((t - origin) % beat + beat) % beat / 1000.0
            val x = .5 * sin(2 * PI * 52 * inBeat) * exp(-inBeat * 18) + .2 * sin(2 * PI * 41.2 * f / rate) + .08 * sin(2 * PI * 415 * f / rate)
            val v = (x * 32767).toInt().coerceIn(-32768, 32767).toShort()
            pcm[f * 2] = v; pcm[f * 2 + 1] = v
        }
        return WaveBuilder(rate, 2, fromMs).apply { accept(pcm) }.build()
    }

    private fun state(): EditorState {
        val state = EditorState(Track("demo-out", "Nuit blanche", "Lumen", "Lumen", 200_000L),
            Track("demo-in", "Sous la pluie", "Aster", "Aster", 214_000L), 200_000L, 214_000L)
        state.out.wave = music(110_000L, 200_000L, 155_000.0)
        state.out.analysis = SegmentAnalysis(155_000, 45_000, 124f, 155_000.0, .9f, 0, -11f, 155_000, 196_000, 155_000, 186_000,
            fadeOut = true, phraseAtMs = 155_000.0, key = 7, keyStrength = .7f)
        state.into.wave = music(0L, 60_000L, 300.0)
        state.into.analysis = SegmentAnalysis(0, 40_000, 126f, 300.0, .9f, 0, -10f, 300, 40_000, 15_780, 40_000,
            phraseAtMs = 300.0, key = 8, keyStrength = .7f)
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

    private fun fraction(state: EditorState, ms: Long): Float {
        val plan = state.plan()
        val window = com.ketvoice.app.ui.player.TimelineWindow.of(plan, plan.outMs, wide = false, zoom = state.zoom)
        return (ms - window.fromMs).toFloat() / window.spanMs
    }

    @Test fun keyframesArePlacedSetOnBothTitlesAndSaved() {
        val state = state()
        state.outSeparated = true; state.inSeparated = true
        compose.setContent { KetvoiceTheme(useDark = true) { KetmixEditorScreen(state, onClose = {}) } }
        val plan = state.plan()
        assertTrue(plan.mixes)
        // Two keyframes already set, drawn as curves on each title's half.
        state.choice = state.choice.copy(keys = listOf(
            LevelKey(plan.overlapMs / 4, out = Levels(voice = 10), into = Levels(instruments = 40)),
            LevelKey(plan.overlapMs * 3 / 4, out = Levels(voice = 0, music = 50), into = Levels(voice = 80))))
        compose.waitForIdle()
        snapshot("ketmix-keys-timeline")

        // A touch inside the transition places a third one and opens its sheet on the outgoing title.
        val at = plan.outMs + plan.overlapMs / 2
        val x = fraction(state, at)
        compose.onNodeWithTag("ketmix-timeline").performTouchInput { click(androidx.compose.ui.geometry.Offset(width * x, height * .3f)) }
        compose.waitForIdle()
        assertEquals(3, state.choice.keys.size)
        assertNotNull(state.keyOpen)
        compose.onNodeWithTag("ketmix-key-sheet").assertExists()
        compose.onNodeWithTag("ketmix-key-page-out").assertExists()
        compose.onNodeWithText("Nuit blanche").assertIsDisplayed()
        snapshot("ketmix-keys-sheet-out")

        // Swiped: the incoming title's page, with the same three controls.
        compose.onNodeWithTag("ketmix-key-pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("Sous la pluie").assertIsDisplayed()
        snapshot("ketmix-keys-sheet-in")

        // Setting a level is kept with the keyframe; « Enregistrer » stores the pair with its keyframes (format 2).
        val index = state.keyOpen!!
        compose.runOnIdle { state.setLevels(index, false, Levels(voice = 30, instruments = 100, music = 70)) }
        assertEquals(30, state.choice.keys[index].into.voice)
        compose.onNodeWithTag("ketmix-key-delete").assertExists()
        compose.onNodeWithText(com.ketvoice.app.data.AppText.t("OK")).performClick()
        compose.waitForIdle()
        assertNull(state.keyOpen)
        state.save()
        assertEquals(3, MixPointStore.pair("demo-out", "demo-in")?.keys?.size)

        // A touch on a keyframe opens it again; « Supprimer le point » removes it.
        val first = fraction(state, plan.outMs + state.choice.keys[0].atMs)
        compose.onNodeWithTag("ketmix-timeline").performTouchInput { click(androidx.compose.ui.geometry.Offset(width * first, height * .5f)) }
        compose.waitForIdle()
        assertEquals(0, state.keyOpen)
        compose.onNodeWithTag("ketmix-key-delete").performClick()
        compose.waitForIdle()
        assertEquals(2, state.choice.keys.size)
    }

    @Test fun withoutSeparationsOnlyMusicIsOffered() {
        val state = state()
        compose.setContent { KetvoiceTheme(useDark = false) { KetmixEditorScreen(state, onClose = {}) } }
        val plan = state.plan()
        state.choice = state.choice.copy(keys = listOf(LevelKey(plan.overlapMs / 2, out = Levels(music = 40))))
        state.keyOpen = 0
        compose.waitForIdle()
        compose.onNodeWithTag("ketmix-key-voice").assertIsNotEnabled()
        compose.onNodeWithTag("ketmix-key-music").assertIsEnabled()
        compose.onNodeWithTag("ketmix-key-separate").assertExists()
        snapshot("ketmix-keys-sheet-unseparated")
    }
}
