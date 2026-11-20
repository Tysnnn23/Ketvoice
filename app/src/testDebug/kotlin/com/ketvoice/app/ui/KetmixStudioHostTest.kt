package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ketvoice.app.automix.LevelKey
import com.ketvoice.app.automix.Levels
import com.ketvoice.app.automix.MapPointsBackend
import com.ketvoice.app.automix.MixPointStore
import com.ketvoice.app.automix.MixPreset
import com.ketvoice.app.automix.MixPresetStore
import com.ketvoice.app.automix.MixShare
import com.ketvoice.app.automix.MixStyle
import com.ketvoice.app.automix.PairChoice
import com.ketvoice.app.automix.SegmentAnalysis
import com.ketvoice.app.automix.Wave
import com.ketvoice.app.automix.WaveBuilder
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.player.EditorState
import com.ketvoice.app.ui.player.KetmixEditorScreen
import com.ketvoice.app.ui.player.TimelineWindow
import com.ketvoice.app.ui.player.importShared
import com.ketvoice.app.ui.player.shared
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
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** 8.0 (studio80): Ketmix Studio — « + Point », undo/redo, zoom and pan, presets, share code — rendered on the host (not a phone). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KetmixStudioHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun stores() { MixPointStore.use(MapPointsBackend()); MixPresetStore.use(MapPointsBackend()) }
    @After fun forget() { MixPointStore.use(null); MixPresetStore.use(null) }

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

    /**
     * The owner's report: in 7.6 nothing on screen placed a keyframe (only a touch inside the box, explained at the
     * bottom of the scrolled settings). Against 7.6 this fails at once: there is no « + Point » button.
     */
    @Test fun plusPointIsInSightAndPlacesAKeyframeWithItsSheet() {
        val state = state()
        compose.setContent { KetvoiceTheme(useDark = true) { KetmixEditorScreen(state, onClose = {}) } }
        compose.waitForIdle()
        assertTrue(state.plan().mixes)
        compose.onNodeWithTag("ketmix-key-hint").assertIsDisplayed()
        snapshot("ketmix-studio-phone")
        compose.onNodeWithTag("ketmix-add-key").assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(1, state.choice.keys.size)
        assertEquals(0, state.keyOpen)
        compose.onNodeWithTag("ketmix-key-sheet").assertExists()
        // In the middle of the blend, on a beat of the outgoing title (the magnet is on).
        val plan = state.plan()
        val at = state.choice.keys[0].atMs
        assertTrue(abs(at - plan.overlapMs / 2) <= 300)
        val analysis = state.out.analysis!!
        val k = (plan.outMs + at - analysis.beatAtMs) / analysis.beatMs.toDouble()
        assertTrue(abs(k - Math.rint(k)) * analysis.beatMs < 2.0)
        compose.onNodeWithText(com.ketvoice.app.data.AppText.t("OK")).performClick()
        compose.waitForIdle()
        // Undo takes it away, redo brings it back.
        compose.onNodeWithTag("ketmix-undo").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertTrue(state.choice.keys.isEmpty())
        compose.onNodeWithTag("ketmix-redo").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(1, state.choice.keys.size)
    }

    /** A short style (Cut) leaves a box a few pixels wide: « + Point » still places a keyframe. */
    @Test fun plusPointWorksOnANarrowTransition() {
        val state = state()
        compose.setContent { KetvoiceTheme(useDark = false) { KetmixEditorScreen(state, onClose = {}) } }
        state.commitStyle(MixStyle.values()[6])
        compose.waitForIdle()
        assertTrue(state.plan().mixes)
        compose.onNodeWithTag("ketmix-add-key").performClick()
        compose.waitForIdle()
        assertEquals(1, state.choice.keys.size)
        assertNotNull(state.keyOpen)
    }

    /** A title alone has no blend: the button is off and the hint says how to get a transition. */
    @Test fun aTitleAloneSaysWhereKeyframesAre() {
        val one = EditorState(Track("demo-out", "Nuit blanche", "Lumen", "Lumen", 200_000L), null, 200_000L, 0L)
        compose.setContent { KetvoiceTheme(useDark = true) { KetmixEditorScreen(one, onClose = {}) } }
        compose.waitForIdle()
        compose.onNodeWithTag("ketmix-add-key").assertIsNotEnabled()
        compose.onNodeWithTag("ketmix-key-hint").assertIsDisplayed()
    }

    @Test fun zoomedWithKeyframesOnBothLanes() {
        val state = state()
        MixPresetStore.hintSeen = true
        compose.setContent { KetvoiceTheme(useDark = true) { KetmixEditorScreen(state, onClose = {}) } }
        val plan = state.plan()
        state.choice = state.choice.copy(keys = listOf(
            LevelKey(plan.overlapMs / 5, out = Levels(voice = 20, music = 80), into = Levels(music = 30)),
            LevelKey(plan.overlapMs / 2, out = Levels(music = 45), into = Levels(voice = 60, music = 70)),
            LevelKey(plan.overlapMs * 4 / 5, out = Levels(music = 10), into = Levels(music = 100))))
        state.zoom = 2.5f
        state.panMs = plan.overlapMs / 3
        compose.waitForIdle()
        snapshot("ketmix-studio-zoomed")
        // Two fingers apart zoom in around them; the pan stays within reach of the exit.
        compose.onNodeWithTag("ketmix-timeline").performTouchInput {
            pinch(Offset(centerX - 40f, centerY), Offset(centerX - 300f, centerY), Offset(centerX + 40f, centerY), Offset(centerX + 300f, centerY))
        }
        compose.waitForIdle()
        assertTrue(state.zoom > 2.5f)
        assertTrue(abs(state.panMs) <= TimelineWindow.PAN_MAX_MS)
    }

    @Test fun presetsAreSavedAppliedAndShown() {
        val state = state()
        compose.setContent { KetvoiceTheme(useDark = true) { KetmixEditorScreen(state, onClose = {}) } }
        val plan = state.plan()
        state.commitStyle(MixStyle.values()[2])
        state.choice = state.choice.copy(keys = listOf(LevelKey(plan.overlapMs / 2, out = Levels(music = 40))))
        MixPresetStore.save(MixPreset.of("", "Fondu du soir", state.choice.pairChoice, state.plan().overlapMs))
        MixPresetStore.save(MixPreset.of("", "Coupe sèche", PairChoice(MixStyle.values()[6]), 0))
        state.reset()
        state.history.clear()
        state.presetsOpen = true
        compose.waitForIdle()
        compose.onNodeWithTag("ketmix-presets-sheet").assertExists()
        compose.onNodeWithText("Fondu du soir").assertIsDisplayed()
        snapshot("ketmix-studio-presets")
        compose.onNodeWithText("Fondu du soir").performClick()
        compose.waitForIdle()
        assertFalse(state.presetsOpen)
        assertEquals(MixStyle.values()[2], state.choice.style)
        assertEquals(1, state.choice.keys.size)
        // One undo takes the whole preset back.
        compose.runOnIdle { state.undo() }
        assertTrue(state.choice.keys.isEmpty())
    }

    @Test fun aSharedMixIsImportedOnTheSameOrOtherTitles() {
        val state = state()
        val plan = state.plan()
        state.choice = state.choice.copy(exitMs = 181_000, entryMs = 9_000, style = MixStyle.values()[3],
            keys = listOf(LevelKey(plan.overlapMs / 3, into = Levels(voice = 0))))
        val code = MixShare.encode(state.shared())
        val friend = state()
        assertTrue(friend.importShared(MixShare.find("regarde : $code")!!))
        assertEquals(state.choice, friend.choice)
        // On another pair: the transition, not the points of titles it does not play.
        val other = EditorState(Track("x", "Autre", "B", "B", 210_000L), Track("y", "Encore", "C", "C", 190_000L), 210_000L, 190_000L)
        assertFalse(other.importShared(MixShare.find(code)!!))
        assertEquals(-1L, other.choice.exitMs)
        assertEquals(MixStyle.values()[3], other.choice.style)
        assertEquals(1, other.choice.keys.size)
    }
}
