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
import com.ketvoice.app.automix.Wave
import com.ketvoice.app.automix.WaveBuilder
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.player.EditorState
import com.ketvoice.app.ui.player.KetmixEditorScreen
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

/**
 * The Ketmix DJ editor (6.1) rendered on the host, not a phone: two titles drawn from synthetic music (a kick,
 * a bass, chords and hats at 124 BPM, a breakdown and a fading outro; a DJ intro for the next one).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KetmixEditorHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun points() { MixPointStore.use(MapPointsBackend()) }
    @After fun forget() { MixPointStore.use(null) }

    private val bpm = 124f
    private val beat = 60_000.0 / bpm
    private val rate = 11_025

    /** Synthetic music from [fromMs] to [untilMs] of a title; [layers] says what plays at each moment. */
    private fun music(fromMs: Long, untilMs: Long, origin: Double, layers: (Long) -> Set<String>, gain: (Long) -> Double = { 1.0 }): Wave {
        val frames = ((untilMs - fromMs) * rate / 1000).toInt()
        val pcm = ShortArray(frames * 2)
        var noise = 12345
        for (f in 0 until frames) {
            val t = fromMs + f * 1000L / rate
            val inBeat = ((t - origin) % beat + beat) % beat / 1000.0
            val on = layers(t)
            var x = 0.0
            if ("kick" in on) x += .55 * sin(2 * PI * 52 * inBeat) * exp(-inBeat * 18)
            if ("bass" in on) x += .22 * sin(2 * PI * 41.2 * f / rate)
            if ("chords" in on) x += .09 * (sin(2 * PI * 330 * f / rate) + sin(2 * PI * 415 * f / rate) + sin(2 * PI * 494 * f / rate))
            if ("hats" in on) {
                noise = noise * 1103515245 + 12345
                val offBeat = ((inBeat * 1000 + beat / 2) % beat) / 1000.0
                x += .16 * ((noise shr 16 and 0x7fff) / 16384.0 - 1.0) * exp(-offBeat * 60)
            }
            val v = (x * gain(t) * 32767).toInt().coerceIn(-32768, 32767).toShort()
            pcm[f * 2] = v; pcm[f * 2 + 1] = v
        }
        return WaveBuilder(rate, 2, fromMs).apply { accept(pcm) }.build()
    }

    private val outTrack = Track("demo-out", "Nuit blanche", "Lumen", "Lumen", 200_000L)
    private val inTrack = Track("demo-in", "Sous la pluie", "Aster", "Aster", 214_000L)

    private fun state(): EditorState {
        val state = EditorState(outTrack, inTrack, 200_000L, 214_000L)
        val origin = 155_000.0
        state.out.wave = music(110_000L, 200_000L, origin, { t ->
            when {
                t >= 196_000 -> emptySet()
                t >= 186_000 -> setOf("chords", "hats")
                t >= 170_000 -> setOf("chords", "hats", "bass")
                else -> setOf("kick", "bass", "chords", "hats")
            }
        }, { t -> if (t < 186_000) 1.0 else ((196_000 - t) / 10_000.0).coerceIn(0.0, 1.0) })
        state.out.analysis = SegmentAnalysis(155_000, 45_000, bpm, origin, .9f, 0, -11f, 155_000, 196_000, 155_000, 186_000,
            fadeOut = true, phraseAtMs = origin, key = 7, keyStrength = .7f)
        state.into.wave = music(0L, 60_000L, 300.0, { t ->
            when {
                t < 300 -> emptySet()
                t < 15_780 -> setOf("kick", "hats")
                else -> setOf("kick", "bass", "chords", "hats")
            }
        })
        state.into.analysis = SegmentAnalysis(0, 40_000, 126f, 300.0, .9f, 0, -10f, 300, 40_000, 15_780, 40_000,
            phraseAtMs = 300.0, key = 8, keyStrength = .7f)
        state.vocalEndMs = 176_000L
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

    /** « Écouter » without the audio engine: what the editor asks of it. */
    private class FakeEngine : com.ketvoice.app.automix.PreviewEngine {
        val flow = kotlinx.coroutines.flow.MutableStateFlow(com.ketvoice.app.automix.PreviewState())
        override val state: kotlinx.coroutines.flow.StateFlow<com.ketvoice.app.automix.PreviewState> = flow
        var started: com.ketvoice.app.automix.MixPlan? = null
        var plain = false
        override fun available() = true
        override fun start(from: Track, to: Track, plan: com.ketvoice.app.automix.MixPlan, plain: Boolean): Boolean {
            started = plan; this.plain = plain
            flow.value = com.ketvoice.app.automix.PreviewState(running = true, positionMs = plan.outMs + plan.overlapMs / 3, plain = plain)
            return true
        }
        override fun stop() { flow.value = com.ketvoice.app.automix.PreviewState() }
    }
    private val fake = FakeEngine()
    @Before fun engine() { com.ketvoice.app.automix.TransitionPreview.engine = fake }
    @After fun realEngine() { com.ketvoice.app.automix.TransitionPreview.engine = com.ketvoice.app.automix.DeckPreview }

    /** Where [ms] of the outgoing clock stands across the timeline, 0..1. */
    private fun fraction(state: EditorState, ms: Long): Float {
        val plan = state.plan()
        val window = com.ketvoice.app.ui.player.TimelineWindow.of(plan, plan.outMs, wide = false, zoom = state.zoom)
        return (ms - window.fromMs).toFloat() / window.spanMs
    }

    @Test fun thePhoneEditorShowsBothTitlesOnOneTimeline() {
        val state = state()
        compose.setContent { KetvoiceTheme(useDark = true) { KetmixEditorScreen(state, onClose = {}) } }
        compose.onNodeWithTag("ketmix-editor").assertIsDisplayed()
        compose.onNodeWithTag("ketmix-timeline").assertIsDisplayed()
        compose.onNodeWithText(com.ketvoice.app.data.AppText.t("Ketmix Studio")).assertIsDisplayed()
        compose.onNodeWithText(com.ketvoice.app.data.AppText.t("Bêta")).assertIsDisplayed()
        val auto = state.plan()
        assertTrue(auto.mixes)
        snapshot("ketmix-editor-phone")

        // The outgoing wave, left of the box, dragged to the right: the mix starts earlier, on the grid.
        compose.onNodeWithTag("ketmix-timeline").performTouchInput {
            val y = height * .25f
            swipe(androidx.compose.ui.geometry.Offset(width * .04f, y), androidx.compose.ui.geometry.Offset(width * .04f + 160f, y), 400)
        }
        compose.waitForIdle()
        val chosen = state.choice.exitMs
        assertTrue("an exit was chosen ($chosen)", chosen in 0 until auto.outMs)
        assertEquals("the entry stays", -1L, state.choice.entryMs)
        assertEquals("listener", state.plan().reason)
        val analysis = state.out.analysis!!
        val beats = (chosen - analysis.beatAtMs) / analysis.beatMs
        assertTrue("on a beat ($beats)", kotlin.math.abs(beats - Math.round(beats)) * analysis.beatMs < 2.0)
        assertEquals("not kept before « Enregistrer »", -1L, MixPointStore.exitOf("demo-out", 200_000L))
        compose.onNodeWithTag("ketmix-save").performClick()
        assertEquals("remembered", chosen, MixPointStore.exitOf("demo-out", 200_000L))
        assertFalse(state.dirty)

        // The box itself, dragged: the exit and the entry move together.
        val before = state.plan()
        val middle = fraction(state, before.outMs + before.overlapMs / 2)
        compose.onNodeWithTag("ketmix-timeline").performTouchInput {
            val x = width * middle; val y = height * .5f
            swipe(androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Offset(x + 120f, y), 400)
        }
        compose.waitForIdle()
        val moved = state.plan()
        assertTrue("the exit moved later (${before.outMs} -> ${moved.outMs})", moved.outMs > before.outMs)
        assertTrue("the entry moved with it (${before.inMs} -> ${moved.inMs})", moved.inMs > before.inMs)

        // A preset re-plans at once; kept with the pair once saved.
        compose.onNodeWithTag("ketmix-style-Echo").performScrollTo().performClick()
        assertEquals(MixStyle.Echo, state.choice.style)
        assertEquals(com.ketvoice.app.automix.MixKind.Echo, state.plan().kind)
        compose.onNodeWithTag("ketmix-style-Auto").performScrollTo().performClick()
        assertNull(state.choice.style)
        compose.onNodeWithTag("ketmix-style-Tempo").performScrollTo().performClick()
        assertEquals(com.ketvoice.app.automix.MixKind.Beatmatch, state.plan().kind)
        compose.onNodeWithTag("ketmix-save").performClick()
        assertEquals(MixStyle.Tempo, MixPointStore.pair("demo-out", "demo-in")?.style)
        compose.onNodeWithTag("ketmix-timeline").performScrollTo()
        snapshot("ketmix-editor-phone-edited")

        // « … » › « Revenir au choix de Ketmix », then saved: back to Ketmix's choice.
        compose.onNodeWithTag("ketmix-more").performClick()
        compose.onNodeWithTag("ketmix-reset").performClick()
        assertTrue(state.choice.automatic)
        compose.onNodeWithTag("ketmix-save").performClick()
        assertEquals(-1L, MixPointStore.exitOf("demo-out", 200_000L))
        assertNull(MixPointStore.pair("demo-out", "demo-in"))
    }

    @Test fun theDetailRowsChangeWhatIsPlayedAndAreKeptWithThePair() {
        val state = state()
        compose.setContent { KetvoiceTheme(useDark = true) { KetmixEditorScreen(state, onClose = {}) } }
        val auto = state.plan()
        assertEquals(com.ketvoice.app.automix.MixKind.Beatmatch, auto.kind)
        assertTrue(auto.swapMs >= 0)
        compose.onNodeWithTag("ketmix-bass").performScrollTo().performClick()
        compose.onNodeWithTag("ketmix-bass-Off").performClick()
        assertEquals(com.ketvoice.app.automix.BassSwap.Off, state.choice.shape.bass)
        assertEquals("no bass swap", -1L, state.plan().swapMs)
        compose.onNodeWithTag("ketmix-filter").performScrollTo().performClick()
        compose.onNodeWithTag("ketmix-filter-LowPass").performClick()
        assertEquals("sinks through a low-pass", 1, state.plan().variant)
        compose.onNodeWithTag("ketmix-volume").performScrollTo().performClick()
        compose.onNodeWithTag("ketmix-volume-Linear").performClick()
        compose.onNodeWithTag("ketmix-tempo").performScrollTo().performClick()
        assertFalse(state.choice.shape.tempo)
        assertNotEquals("124 and 126 BPM are not matched without tempo", com.ketvoice.app.automix.MixKind.Beatmatch, state.plan().kind)
        compose.onNodeWithTag("ketmix-timeline").performScrollTo()
        snapshot("ketmix-editor-phone-details")
        compose.onNodeWithTag("ketmix-save").performClick()
        val kept = MixPointStore.pair("demo-out", "demo-in")!!.shape
        assertEquals(com.ketvoice.app.automix.MixShape(com.ketvoice.app.automix.VolumeCurve.Linear, com.ketvoice.app.automix.BassSwap.Off,
            com.ketvoice.app.automix.FilterSweep.LowPass, tempo = false), kept)
        // Read back as the editor opens it.
        val again = state()
        again.recall()
        assertEquals(kept, again.choice.shape)
    }

    @Test fun listeningWorksWithoutTheTitlePlayingAndCanBeStopped() {
        val state = state()
        compose.setContent { KetvoiceTheme(useDark = true) { KetmixEditorScreen(state, onClose = {}) } }
        assertNotEquals("the outgoing title is not the one playing", "demo-out", com.ketvoice.app.player.Player.state.value.current?.id)
        compose.onNodeWithTag("ketmix-preview").performScrollTo().assertIsEnabled().performClick()
        assertEquals(state.plan(), fake.started)
        assertFalse(fake.plain)
        compose.onNodeWithText(com.ketvoice.app.data.AppText.t("Arrêter")).assertExists()
        compose.onNodeWithTag("ketmix-timeline").performScrollTo()
        snapshot("ketmix-editor-phone-listening")
        // « Sans mix »: the same moment as a plain cut, at once.
        compose.onNodeWithTag("ketmix-ab-1").performScrollTo().performClick()
        assertTrue(fake.plain)
        compose.onNodeWithTag("ketmix-preview").performClick()
        assertFalse(fake.state.value.running)
    }

    @Test fun closingWithChangesAsksFirst() {
        val state = state()
        var closed = false
        compose.setContent { KetvoiceTheme(useDark = false) { KetmixEditorScreen(state, onClose = { closed = true }) } }
        compose.onNodeWithTag("ketmix-close").performClick()
        assertTrue("nothing changed: it closes", closed)
        closed = false
        state.commitStyle(MixStyle.Fade)
        compose.onNodeWithTag("ketmix-close").performClick()
        assertFalse(closed)
        compose.onNodeWithText(com.ketvoice.app.data.AppText.t("Enregistrer cette transition ?")).assertIsDisplayed()
        snapshot("ketmix-editor-phone-light-unsaved")
        compose.onNodeWithText(com.ketvoice.app.data.AppText.t("Ne pas enregistrer")).performClick()
        assertTrue(closed)
        assertNull("nothing kept", MixPointStore.pair("demo-out", "demo-in"))
    }

    @Test fun aWaveBeingReadShowsItsProgress() {
        val state = state()
        state.into.wave = null
        state.into.progress = .4f
        compose.setContent { KetvoiceTheme(useDark = false) { KetmixEditorScreen(state, onClose = {}) } }
        compose.onNodeWithText(com.ketvoice.app.data.AppText.t("Lecture de l'onde… {0} %").replace("{0}", "40")).assertExists()
        snapshot("ketmix-editor-phone-light-loading")
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-xlarge-land-mdpi")
    fun theTabletEditorIsWider() {
        val state = state()
        MixPointStore.setExit("demo-out", 200_000L, 171_000L)
        MixPointStore.setPair("demo-out", "demo-in", com.ketvoice.app.automix.PairChoice(MixStyle.Tempo, 16_000L))
        state.recall()
        compose.setContent { KetvoiceTheme(useDark = true) { KetmixEditorScreen(state, onClose = {}) } }
        compose.onNodeWithTag("ketmix-lane-out").assertIsDisplayed()
        compose.onNodeWithTag("ketmix-preview").assertIsDisplayed()
        assertTrue("on the beat nearest to the chosen exit", kotlin.math.abs(state.plan().outMs - 171_000L) <= 250)
        snapshot("ketmix-editor-tablet")
    }
}
