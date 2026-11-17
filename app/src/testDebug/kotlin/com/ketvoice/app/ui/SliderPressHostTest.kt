package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.common.GlassSlider
import com.ketvoice.app.ui.common.SliderPress
import com.ketvoice.app.ui.common.SliderPressState
import com.ketvoice.app.ui.common.rememberSliderPress
import com.ketvoice.app.ui.motion.RubberBand
import com.ketvoice.app.ui.player.ScrubBar
import com.ketvoice.app.ui.player.VolumeRail
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * The press-and-hold of iOS 26/27 sliders (SliderPress): its maths, its state under a real gesture,
 * and software renders (host Skia, not a phone) of the player's sliders and the glass slider at rest,
 * held and pulled past their end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SliderPressHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun reset() {
        AppSettings.reduceAnimations.value = false
        (Player.positionMs as MutableStateFlow<Long>).value = 0L
    }

    private val density = Density(3f)

    // ---------------------------------------------------------------- maths

    @Test fun theTrackSwellsAndReachesOutWhileHeld() {
        val spec = SliderPress.Scrub
        val rest = SliderPress.track(spec, density, 0f, 900f, expansion = 0f)
        assertEquals(0f, rest.left, 0f); assertEquals(900f, rest.right, 0f)
        assertEquals(spec.rest * 3f, rest.thickness, 1e-3f)
        val held = SliderPress.track(spec, density, 0f, 900f, expansion = 1f)
        assertEquals(spec.held * 3f, held.thickness, 1e-3f)
        assertEquals(-spec.grow * 3f, held.left, 1e-3f); assertEquals(900f + spec.grow * 3f, held.right, 1e-3f)
        assertTrue("12-14 dp held, 4-6 dp at rest", spec.held in 12f..14f && spec.rest in 4f..6f)
        // The old helper keeps its values.
        assertEquals(5f, com.ketvoice.app.ui.player.scrubThicknessDp(0f), 0f)
        assertEquals(12f, com.ketvoice.app.ui.player.scrubThicknessDp(1f), 1e-5f)
        assertEquals(5f, com.ketvoice.app.ui.player.scrubThicknessDp(Float.NaN), 0f)
        // A spring overshoot is allowed a little, never wildly.
        assertEquals(SliderPress.thicknessDp(spec, 1.3f), SliderPress.thicknessDp(spec, 9f), 0f)
    }

    @Test fun whatIsUnderTheFingerIsWhatIsSet() {
        for (e in listOf(0f, .5f, 1f)) for (inset in listOf(0f, 54f)) {
            val t = SliderPress.track(SliderPress.Glass, density, 0f, 900f, e, inset = inset)
            for (f in listOf(0f, .25f, .5f, .9f, 1f)) assertEquals(f, t.fractionAt(t.xOf(f)), 1e-4f)
            assertEquals(0f, t.fractionAt(-500f), 0f); assertEquals(1f, t.fractionAt(5000f), 0f)
            assertEquals(0f, t.overshoot(t.xOf(.5f)), 0f)
            assertEquals(30f, t.overshoot(t.xOf(1f) + 30f), 1e-3f)
            assertEquals(-12f, t.overshoot(t.xOf(0f) - 12f), 1e-3f)
        }
        assertEquals(0f, SliderPress.track(SliderPress.Scrub, density, 0f, 900f, 0f).fractionAt(Float.NaN), 0f)
    }

    @Test fun theRubberBandFollowsLessAndLessAndNeverPastItsLimit() {
        val max = SliderPress.Scrub.maxStretch * 3f
        assertEquals(0f, SliderPress.stretchFor(0f, max), 0f)
        var last = 0f
        for (pull in listOf(5f, 20f, 60f, 200f, 1000f, 100_000f)) {
            val s = SliderPress.stretchFor(pull, max)
            assertTrue("grows with the pull ($pull → $s)", s > last)
            assertTrue("less than the pull", s < pull)
            assertTrue("within its limit", s <= max)
            assertEquals("symmetric", -s, SliderPress.stretchFor(-pull, max), 1e-4f)
            last = s
        }
        // It is iOS's band (RubberBand, shared with the lists), bounded by the slider's own limit.
        assertEquals(RubberBand.displayed(40f, max), SliderPress.stretchFor(40f, max), 1e-5f)
        assertEquals(0f, SliderPress.stretchFor(Float.NaN, max), 0f)
        // The stretched end moves out, the other stays; the track gets a little thinner as it is pulled.
        val pulled = SliderPress.track(SliderPress.Scrub, density, 0f, 900f, 1f, stretchPx = max)
        val held = SliderPress.track(SliderPress.Scrub, density, 0f, 900f, 1f)
        assertEquals(held.right + max, pulled.right, 1e-3f); assertEquals(held.left, pulled.left, 1e-3f)
        assertEquals(held.thickness * (1f - SliderPress.SQUEEZE), pulled.thickness, 1e-3f)
        val back = SliderPress.track(SliderPress.Scrub, density, 0f, 900f, 1f, stretchPx = -max * 5f)
        assertEquals(held.left - max, back.left, 1e-3f); assertEquals(held.right, back.right, 1e-3f)
    }

    @Test fun springTargetsAndReducedMotion() {
        assertEquals(1f, SliderPress.target(true), 0f); assertEquals(0f, SliderPress.target(false), 0f)
        val press = SliderPress.expansionSpec(held = true, reduced = false) as SpringSpec
        val release = SliderPress.expansionSpec(held = false, reduced = false) as SpringSpec
        assertTrue("swelling barely bounces", press.dampingRatio >= .7f)
        assertTrue("letting go bounces a little", release.dampingRatio in .4f..<.7f)
        assertTrue((SliderPress.stretchSpec(false) as SpringSpec).dampingRatio < .6f)
        assertTrue(SliderPress.expansionSpec(true, reduced = true) is SnapSpec)
        assertTrue(SliderPress.expansionSpec(false, reduced = true) is SnapSpec)
        assertTrue(SliderPress.stretchSpec(true) is SnapSpec)
        // Reduced motion: a small thickening only, no reach, no rubber band, labels stay.
        val calm = SliderPress.track(SliderPress.Scrub, density, 0f, 900f, 1f, stretchPx = 30f, reduced = true)
        assertEquals(0f, calm.left, 0f); assertEquals(900f, calm.right, 0f)
        assertEquals((SliderPress.Scrub.rest + SliderPress.REDUCED_THICKENING) * 3f, calm.thickness, 1e-3f)
        assertEquals(0f, SliderPress.labelShiftDp(SliderPress.Scrub, 1f, reduced = true), 0f)
        assertEquals(1f, SliderPress.labelScale(1f, 1f, reduced = true), 0f)
        assertTrue(SliderPress.labelScale(1f) > 1f && SliderPress.labelScale(1f, 1f) > SliderPress.labelScale(1f))
        assertEquals(SliderPress.Scrub.grow, SliderPress.labelShiftDp(SliderPress.Scrub, 1f), 0f)
    }

    // ---------------------------------------------------------------- gestures and renders

    private lateinit var volumePress: SliderPressState

    @Test fun holdingTheVolumeSwellsItAndPullingPastTheEndStretchesIt() {
        var volume by mutableFloatStateOf(.4f)
        compose.mainClock.autoAdvance = false
        compose.setContent { KetvoiceTheme(useDark = true) {
            Column(Modifier.fillMaxSize().background(Color(0xFF2A2230)).padding(24.dp)) {
                volumePress = rememberSliderPress()
                VolumeRail(volume, Modifier.fillMaxWidth().testTag("volume"), press = volumePress) { volume = it }
            }
        } }
        compose.mainClock.advanceTimeBy(100)
        assertFalse(volumePress.held)
        // A hold without moving presses it once the short delay has passed (not a quick tap).
        compose.onNodeWithTag("volume").performTouchInput { down(Offset(width * .4f, centerY)) }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertTrue("held", volumePress.held); assertEquals(1f, volumePress.expansion, .02f) }
        // A drag: the value follows the finger on the swollen track.
        compose.onNodeWithTag("volume").performTouchInput { moveTo(Offset(width * .75f, centerY)) }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertEquals(.75f, volume, .03f) }
        // Past the end: the value stays at the top and the track stretches, within its limit.
        compose.onNodeWithTag("volume").performTouchInput { moveTo(Offset(width + 240f, centerY)) }
        compose.mainClock.advanceTimeBy(50)
        compose.runOnIdle {
            assertEquals(1f, volume, 0f)
            val max = SliderPress.Volume.maxStretch * compose.activity.resources.displayMetrics.density
            assertTrue("stretched ${volumePress.stretch}", volumePress.stretch > max * .3f && volumePress.stretch <= max)
        }
        compose.onNodeWithTag("volume").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_500)
        compose.runOnIdle {
            assertFalse(volumePress.held)
            assertEquals(0f, volumePress.expansion, .01f); assertEquals(0f, volumePress.stretch, .5f)
        }
    }

    @Test fun reducedMotionSnapsAndNeverStretches() {
        AppSettings.reduceAnimations.value = true
        var volume by mutableFloatStateOf(.4f)
        compose.mainClock.autoAdvance = false
        compose.setContent { KetvoiceTheme(useDark = true) {
            volumePress = rememberSliderPress()
            VolumeRail(volume, Modifier.fillMaxWidth().testTag("volume"), press = volumePress) { volume = it }
        } }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("volume").performTouchInput { down(Offset(width * .4f, centerY)); moveTo(Offset(width + 240f, centerY)) }
        compose.mainClock.advanceTimeBy(16)
        compose.runOnIdle { assertTrue(volumePress.held); assertEquals(1f, volumePress.expansion, 0f); assertEquals(0f, volumePress.stretch, 0f) }
        compose.onNodeWithTag("volume").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(16)
        compose.runOnIdle { assertEquals(0f, volumePress.expansion, 0f) }
    }

    @Test fun rendersTheSlidersAtRestHeldAndPulledPastTheirEnd() {
        (Player.positionMs as MutableStateFlow<Long>).value = 84_000L
        var glass by mutableFloatStateOf(.55f)
        var volume by mutableFloatStateOf(.6f)
        compose.mainClock.autoAdvance = false
        compose.setContent { KetvoiceTheme(useDark = true) {
            Column(Modifier.fillMaxSize().background(Color(0xFF3A2A25)).padding(horizontal = 28.dp, vertical = 40.dp)) {
                ScrubBar(200_000, Modifier.fillMaxWidth().testTag("scrub"), onSeek = {})
                androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 28.dp))
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
                    com.ketvoice.app.ui.player.PlayerVolume(Modifier.fillMaxWidth().testTag("player-volume"), compact = true)
                }
                androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 28.dp))
                VolumeRail(volume, Modifier.fillMaxWidth().testTag("rail")) { volume = it }
            }
            Column(Modifier.fillMaxSize().padding(top = 420.dp)) {
                KetvoiceTheme(useDark = false) {
                    Column(Modifier.fillMaxWidth().background(Color(0xFFF2F2F7)).padding(horizontal = 28.dp, vertical = 30.dp)) {
                        GlassSlider(glass, { glass = it }, Modifier.width(320.dp).testTag("glass"))
                    }
                }
            }
        } }
        compose.mainClock.advanceTimeBy(500)
        image("slider-rest")
        // Held: each slider is pressed with a finger that does not move yet.
        val tags = listOf("scrub", "player-volume", "rail", "glass")
        tags.forEachIndexed { i, tag -> compose.onNodeWithTag(tag).performTouchInput { down(i, Offset(width * .55f, centerY)) } }
        compose.mainClock.advanceTimeBy(80)
        image("slider-pressing")
        compose.mainClock.advanceTimeBy(500)
        image("slider-held")
        // Dragged past the right end: the rubber band.
        tags.forEachIndexed { i, tag -> compose.onNodeWithTag(tag).performTouchInput {
            moveTo(i, Offset(width * .8f, centerY)); moveTo(i, Offset(width + 260f, centerY))
        } }
        compose.mainClock.advanceTimeBy(60)
        image("slider-overscrolled")
        compose.runOnIdle { assertEquals(1f, glass, 0f) }
        tags.forEachIndexed { i, tag -> compose.onNodeWithTag(tag).performTouchInput { up(i) } }
        compose.mainClock.advanceTimeBy(90)
        image("slider-releasing")
        compose.mainClock.advanceTimeBy(1_500)
        image("slider-released")
    }

    private fun image(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/sliders/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
