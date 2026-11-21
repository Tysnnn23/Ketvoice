package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.common.ContextMenuHost
import com.ketvoice.app.ui.common.ContextMenus
import com.ketvoice.app.ui.common.KetMenuButton
import com.ketvoice.app.ui.common.KetMenuItem
import com.ketvoice.app.ui.common.TrackRow
import com.ketvoice.app.ui.motion.GrowFromSource
import com.ketvoice.app.ui.motion.GrowSources
import com.ketvoice.app.ui.motion.GrowTransition
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.SymbolTrigger
import com.ketvoice.app.ui.motion.growContainer
import com.ketvoice.app.ui.motion.growSource
import com.ketvoice.app.ui.motion.rememberGrowTransition
import com.ketvoice.app.ui.motion.symbolBounce
import kotlinx.coroutines.launch
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

/**
 * 8.0 motion (« Menus qui sortent du bouton », « Ressorts partout », « Icônes animées »), checked on the
 * host: the geometry of a menu growing out of its button, springs that continue when interrupted, and
 * symbol effects that play once per change. Drawn in software; not a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Motion80HostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = false
        GrowSources.clear()
    }
    @After fun reset() {
        GrowFromSource.frozen = null
        ContextMenus.request.value = null
        AppSettings.reduceAnimations.value = false
        GrowSources.clear()
    }

    private fun capture(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
        // Every window, in order: the activity, then a popup menu or a dialog over it.
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val roots = (ReflectionHelpers.getField<Any>(global, "mViews") as List<android.view.View>).ifEmpty { listOf(view) }
        for (root in roots) {
            if (root.width == 0 || root.height == 0) continue
            val at = IntArray(2).also { root.getLocationOnScreen(it) }
            canvas.save(); canvas.translate(at[0].toFloat(), at[1].toFloat()); root.draw(canvas); canvas.restore()
        }
        val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---------------------------------------------------------------- grow from source: geometry

    @Test fun theGrowthRunsFromTheButtonToItsOwnPlace() {
        val button = Rect(300f, 40f, 344f, 84f)
        val menu = Rect(110f, 90f, 344f, 400f)
        val start = GrowFromSource.frame(button, menu, 22f, 16f, 0f)
        assertEquals(button, start.rect)
        assertEquals("the button's corners", 22f, start.radius, 1e-3f)
        assertEquals("no content yet", 0f, start.contentAlpha, 1e-4f)
        val end = GrowFromSource.frame(button, menu, 22f, 16f, 1f)
        assertEquals(menu, end.rect)
        assertEquals(16f, end.radius, 1e-3f)
        assertEquals(1f, end.contentAlpha, 1e-4f); assertEquals(1f, end.contentScale, 1e-4f)
        assertEquals("content waits for 40 % of the travel", 0f,
            GrowFromSource.frame(button, menu, 22f, 16f, GrowFromSource.CONTENT_FROM).contentAlpha, 1e-4f)
        assertTrue(GrowFromSource.frame(button, menu, 22f, 16f, .7f).contentAlpha in .4f..0.6f)
        // It grows from the side the button is on.
        assertTrue(start.alignX > .85f); assertEquals(0f, start.alignY, 1e-4f)
        // A button gone from the screen: the menu folds into its own middle.
        val gone = GrowFromSource.resolve(Rect(-500f, -500f, -450f, -450f), menu, Rect(0f, 0f, 400f, 900f))
        assertEquals(menu.center.x, gone.center.x, 1e-3f); assertEquals(menu.center.y, gone.center.y, 1e-3f)
        assertTrue(gone.width < menu.width / 4f)
        assertEquals(button, GrowFromSource.resolve(button, menu, Rect(0f, 0f, 400f, 900f)))
    }

    @Test fun thePressedControlIsTheSourceOnlyWhileFresh() {
        GrowSources.noteTouch(androidx.compose.ui.geometry.Offset(10f, 10f), 22f, at = 1_000L)
        GrowSources.noteControl(Rect(0f, 0f, 44f, 44f), 22f, at = 1_000L)
        assertEquals(Rect(0f, 0f, 44f, 44f), GrowSources.current(now = 1_200L)?.rect)
        assertNull("a later opening was not caused by that touch", GrowSources.current(now = 1_000L + GrowFromSource.FRESH_MS + 1))
        GrowSources.noteTouch(androidx.compose.ui.geometry.Offset(200f, 300f), 22f, at = 5_000L)
        assertEquals("a touch outside any control: a drop around the finger", 200f, GrowSources.current(now = 5_010L)!!.rect.center.x, 1e-3f)
    }

    @Test fun aContainerStartsAtTheButtonRectAndEndsAtItsOwn() {
        lateinit var grow: GrowTransition
        var open by mutableStateOf(false)
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Box(Modifier.align(Alignment.TopEnd).padding(16.dp).size(44.dp).growSource().background(Color.Gray)
                    .clickable { open = true }.testTag("button"))
                if (open) {
                    grow = rememberGrowTransition()
                    LaunchedEffect(Unit) { grow.open() }
                    Box(Modifier.padding(top = 120.dp, start = 40.dp).size(260.dp, 300.dp).growContainer(grow, 16.dp)
                        .background(Color.White).testTag("container"))
                }
            }
        } }
        compose.onNodeWithTag("button").performClick()
        compose.waitForIdle()
        val button = compose.onNodeWithTag("button").fetchSemanticsNode().boundsInRoot
        val target = grow.target!!
        GrowFromSource.frozen = 0f
        assertEquals("starts on the button", button, grow.frame(16f)!!.rect)
        assertEquals("with the button's corners", button.height / 2f, grow.frame(16f)!!.radius, .5f)
        GrowFromSource.frozen = 1f
        assertEquals("ends where it is laid out", target, grow.frame(16f)!!.rect)
        GrowFromSource.frozen = null
        assertEquals("the spring has settled", 1f, grow.progress.value, 1e-3f)
        assertEquals(260f, target.width, 1f); assertEquals(120f, target.top, 1f)
    }

    // ---------------------------------------------------------------- springs everywhere

    /** A frame clock that moves 16 ms each frame, for animations driven without a screen. */
    private class StepClock : androidx.compose.runtime.MonotonicFrameClock {
        var now = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            kotlinx.coroutines.yield()
            now += 16_000_000L
            return onFrame(now)
        }
    }

    @Test fun anInterruptedSpringContinuesFromWhereItIs() = kotlinx.coroutines.runBlocking(StepClock()) {
        val grow = GrowTransition({ null }, null, reduced = false)
        val opening = launch { grow.open() }
        repeat(7) { kotlinx.coroutines.yield() }
        val before = grow.progress.value
        val velocity = grow.progress.velocity
        assertTrue("part-way up, still rising: $before at $velocity", before in .05f..0.95f && velocity > 0f)
        val closing = launch { grow.close() }
        repeat(2) { kotlinx.coroutines.yield() }
        val after = grow.progress.value
        assertTrue("the opening gave way", opening.isCancelled || opening.isCompleted)
        assertTrue("no jump: it carries on from $before (now $after)", kotlin.math.abs(after - before) < .1f)
        assertTrue("with its speed: it keeps rising a moment before folding back ($before -> $after)", after >= before - .005f)
        closing.join()
        assertEquals("then it folds back", 0f, grow.progress.value, 1e-3f)
    }

    @Test fun theSpringPresetsAreTheTimingsOfIos() {
        // spring(duration:bounce:): stiffness (2π/d)², damping 1 − bounce.
        val smooth = Motion.Springs.SMOOTH
        assertEquals(1f, smooth.damping, 1e-4f)
        assertEquals((2 * Math.PI / .4).let { it * it }.toFloat(), smooth.stiffness, .5f)
        assertEquals(.85f, Motion.Springs.SNAPPY.damping, 1e-4f)
        assertTrue(Motion.Springs.BOUNCY.damping < Motion.Springs.SNAPPY.damping)
        assertTrue("a snappy spring is quicker than a smooth one", Motion.Springs.SNAPPY.stiffness > smooth.stiffness)
    }

    // ---------------------------------------------------------------- animated symbols

    @Test fun aSymbolEffectPlaysOncePerChange() {
        val trigger = SymbolTrigger(false)
        assertFalse("the first value never plays", trigger.changed(false))
        assertTrue(trigger.changed(true))
        assertFalse("the same state twice plays once", trigger.changed(true))
        assertTrue(trigger.changed(false))
        assertEquals(2, trigger.plays)
    }

    @Test fun aBouncingHeartRecomposesOnlyOnItsChange() {
        var liked by mutableStateOf(false)
        var compositions = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            compositions++
            Box(Modifier.size(40.dp).symbolBounce(liked).background(Color.Red).testTag("heart"))
        }
        compose.mainClock.advanceTimeByFrame()
        val settled = compositions
        compose.runOnIdle { liked = true }
        repeat(30) { compose.mainClock.advanceTimeByFrame() }
        assertTrue("one composition for the change, none per frame of the bounce (${compositions - settled})", compositions - settled <= 2)
    }

    // ---------------------------------------------------------------- renders

    @Test fun rendersAMenuGrowingOutOfItsButton() {
        val track = Track("qa:grow", "Titre qui grandit", "Artiste", "Album")
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(com.ketvoice.app.theme.LocalTheme.current.background)) {
                Column(Modifier.padding(top = 140.dp)) { TrackRow(track, null, active = false, playing = false, onClick = {}) }
                ContextMenuHost()
            }
        } }
        compose.onNodeWithContentDescription("Options").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Lire ensuite").assertExists()
        for (p in listOf(0f, .5f, 1f)) {
            GrowFromSource.frozen = p
            compose.waitForIdle()
            capture("motion80-context-menu-${(p * 100).toInt()}")
        }
    }

    @Test fun rendersATopBarMenuGrowingOutOfItsButton() {
        compose.setContent { KetvoiceTheme(useDark = false) {
            Box(Modifier.fillMaxSize().background(com.ketvoice.app.theme.LocalTheme.current.background)) {
                KetMenuButton(listOf(listOf(KetMenuItem("Trier par titre", checked = true) {}, KetMenuItem("Trier par artiste") {}),
                    listOf(KetMenuItem("Sélectionner") {})), "Plus d'options", Modifier.align(Alignment.TopEnd).padding(16.dp), tag = "more")
            }
        } }
        compose.onNodeWithTag("more").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Trier par artiste").assertExists()
        for (p in listOf(0f, .5f, 1f)) {
            GrowFromSource.frozen = p
            compose.waitForIdle()
            capture("motion80-ket-menu-${(p * 100).toInt()}")
        }
        GrowFromSource.frozen = null
        compose.onNodeWithText("Sélectionner").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Trier par artiste").assertDoesNotExist()
    }
}
