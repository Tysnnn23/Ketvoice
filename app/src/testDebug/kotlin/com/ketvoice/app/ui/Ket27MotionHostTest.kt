package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.common.ContextMenuHost
import com.ketvoice.app.ui.common.ContextMenus
import com.ketvoice.app.ui.common.SheetDetents
import com.ketvoice.app.ui.common.SliderSteps
import com.ketvoice.app.ui.common.TrackRow
import com.ketvoice.app.ui.motion.FillingDownloadGlyph
import com.ketvoice.app.ui.motion.SymbolEffects
import com.ketvoice.app.ui.player.*
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Ket 27's motion — the mini-player that unfolds, the animated symbols, the sheets on detents and
 * the context menu — checked on the host: logic exactly, drawing in software. Not a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ket27MotionHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST")
    private val player get() = Player.state as MutableStateFlow<PlayerUiState>

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = false
        AppSettings.ket27.value = true
    }
    @After fun reset() {
        AppSettings.ket27.value = false
        AppSettings.reduceAnimations.value = false
        ContextMenus.request.value = null
        AppUi.trackMenuTrack.value = null
        MiniPlayerAnchors.title = null; MiniPlayerAnchors.play = null; MiniPlayerAnchors.next = null
        player.value = PlayerUiState()
    }

    private fun capture(name: String): Bitmap {
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }

    /** White pixels of the region [left, right) × [top, bottom) of a bitmap. */
    private fun lit(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Set<Int> {
        val found = HashSet<Int>()
        for (y in top until bottom) for (x in left until right)
            if (android.graphics.Color.red(bitmap.getPixel(x, y)) > 128) found.add((y - top) * 1000 + (x - left))
        return found
    }

    /** How many pixels two drawings disagree on (a layer may round an edge differently from a direct drawing). */
    private fun differ(a: Set<Int>, b: Set<Int>): Int = (a - b).size + (b - a).size

    // ---------------------------------------------------------------- animated symbols

    @Test fun playBecomesPauseThroughOneShape() {
        compose.setContent {
            Row(Modifier.fillMaxSize().background(Color.Black)) {
                TransportGlyphAt(0f, Modifier.size(100.dp), rounded = true)
                TransportGlyphAt(.5f, Modifier.size(100.dp), rounded = true)
                TransportGlyphAt(1f, Modifier.size(100.dp), rounded = true)
            }
        }
        val bitmap = capture("ket27-transport-morph")
        val play = lit(bitmap, 0, 0, 100, 100); val half = lit(bitmap, 100, 0, 200, 100); val pause = lit(bitmap, 200, 0, 300, 100)
        assertTrue("each stage draws something", play.size > 800 && half.size > 800 && pause.size > 800)
        assertNotEquals(play, half); assertNotEquals(half, pause); assertNotEquals(play, pause)
        // Play is one body (lit at its centre); pause is two bars with nothing between them.
        assertTrue(50 * 1000 + 45 in play)
        assertFalse(50 * 1000 + 50 in pause)
        assertTrue(50 * 1000 + 30 in pause); assertTrue(50 * 1000 + 70 in pause)
        // Half-way the two halves have begun to part without having become bars.
        assertEquals(0f, TransportMorph.gap(0f), 1e-4f)
        assertEquals(.18f, TransportMorph.gap(1f), 1e-4f)
        assertEquals(.09f, TransportMorph.gap(.5f), 1e-4f)
        // The spring may pass either end; the shape follows without folding over.
        assertTrue(TransportMorph.gap(1.3f) <= .21f); assertTrue(TransportMorph.gap(-.5f) > -.03f)
        assertEquals(1f, SymbolEffects.replaceDip(0f), 1e-4f); assertEquals(.88f, SymbolEffects.replaceDip(.5f), 1e-4f)
        assertEquals(1f, SymbolEffects.replaceDip(1f), 1e-4f)
    }

    @Test fun reducedMotionReplacesTheSymbolAtOnce() {
        AppSettings.reduceAnimations.value = true
        var playing by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Row(Modifier.fillMaxSize().background(Color.Black)) {
                MorphingTransportGlyph(playing, Modifier.size(100.dp))
                TransportGlyphAt(1f, Modifier.size(100.dp), rounded = true)
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { playing = true }; compose.waitForIdle()
        // A few frames, no time for a spring: the pause bars are already there.
        compose.mainClock.advanceTimeBy(32)
        val bitmap = capture("ket27-transport-reduced")
        val wanted = lit(bitmap, 100, 0, 200, 100)
        assertTrue("${differ(wanted, lit(bitmap, 0, 0, 100, 100))} of ${wanted.size}", differ(wanted, lit(bitmap, 0, 0, 100, 100)) < wanted.size / 50)
    }

    @Test fun withMotionTheSymbolIsStillOnItsWayAfterOneFrame() {
        var playing by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Row(Modifier.fillMaxSize().background(Color.Black)) {
                MorphingTransportGlyph(playing, Modifier.size(100.dp))
                TransportGlyphAt(1f, Modifier.size(100.dp), rounded = true)
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { playing = true }; compose.waitForIdle()
        compose.mainClock.advanceTimeBy(32)
        val early = capture("ket27-transport-moving")
        val wanted = lit(early, 100, 0, 200, 100)
        assertTrue("still far from the bars", differ(wanted, lit(early, 0, 0, 100, 100)) > wanted.size / 8)
        compose.mainClock.advanceTimeBy(1500)
        val settled = capture("ket27-transport-settled")
        assertTrue("${differ(wanted, lit(settled, 0, 0, 100, 100))} of ${wanted.size}", differ(wanted, lit(settled, 0, 0, 100, 100)) < wanted.size / 50)
    }

    @Test fun theDownloadArrowFillsWithTheFile() {
        AppSettings.reduceAnimations.value = true // the level is then exactly the progress given
        compose.setContent {
            Row(Modifier.fillMaxSize().background(Color.Black)) {
                listOf(0f, .5f, 1f).forEach { FillingDownloadGlyph(it, Modifier.size(100.dp), fill = Color.White, empty = Color(0xFF400000)) }
            }
        }
        val bitmap = capture("ket27-download-fill")
        val none = lit(bitmap, 0, 0, 100, 100).size; val half = lit(bitmap, 100, 0, 200, 100).size; val full = lit(bitmap, 200, 0, 300, 100).size
        assertEquals(0, none)
        assertTrue("half of the arrow is filled ($half of $full)", half > full / 5 && half < full * 4 / 5)
        assertTrue(full > 1500)
        // The fill rises from the bottom: at half, the tray is lit and the top of the shaft is not.
        assertTrue(android.graphics.Color.red(bitmap.getPixel(150, 82)) > 128)
        assertTrue(android.graphics.Color.red(bitmap.getPixel(150, 18)) < 128)
        assertEquals(0f, SymbolEffects.fillLevel(0f), 0f); assertEquals(1f, SymbolEffects.fillLevel(1f), 0f)
        assertTrue(SymbolEffects.fillLevel(.01f) > 0f && SymbolEffects.fillLevel(.99f) < 1f)
        assertEquals(25f, SymbolEffects.fillTop(.75f, 100f), 1e-3f)
    }

    // ---------------------------------------------------------------- mini-player → player

    @Test fun oneNumberPlacesEverythingBetweenTheMiniPlayerAndThePlayer() {
        assertEquals(0f, presentationProgress(0f, 0f, 900f), 0f)
        assertEquals(1f, presentationProgress(1f, 0f, 900f), 0f)
        // Dragged a third of the way down, the open player is a third of the way back to the mini-player.
        assertEquals(2f / 3f, presentationProgress(1f, 300f, 900f), 1e-4f)
        assertEquals(0f, presentationProgress(1f, 2000f, 900f), 0f)
        // The spring's overshoot only shows where it is allowed to.
        assertEquals(1f, presentationProgress(1.04f, 0f, 900f), 0f)
        assertEquals(1.04f, presentationProgress(1.04f, 0f, 900f, PlayerDismiss.OVERSHOOT), 1e-4f)
        assertEquals(1f, presentationProgress(Float.NaN, 0f, 900f), 0f)

        val mini = Rect(70f, 760f, 250f, 796f); val rest = Rect(32f, 430f, 300f, 486f)
        val start = sharedElementMorph(mini, rest, 0f, anchorStart = true)
        assertEquals(36f / 56f, start.scale, 1e-4f); assertEquals(38f, start.translationX, 1e-3f)
        assertEquals(778f - 458f, start.translationY, 1e-3f)
        assertEquals(ElementMorph(1f, 0f, 0f), sharedElementMorph(mini, rest, 1f, anchorStart = true))
        val middle = sharedElementMorph(mini, rest, .5f)
        assertEquals((160f - 166f) * .5f, middle.translationX, 1e-3f)
        assertTrue(middle.scale > start.scale && middle.scale < 1f)
        assertEquals("nothing to travel from", ElementMorph(1f, 0f, 0f), sharedElementMorph(null, rest, .3f))
    }

    @Test fun aFlickClosesThePlayerAndAFlickUpKeepsIt() {
        val h = 900f; val fling = 900f
        // Classic: the distance alone decides.
        assertFalse(PlayerDismiss.closes(100f, 5000f, h, fling, velocityAware = false))
        assertTrue(PlayerDismiss.closes(160f, -5000f, h, fling, velocityAware = false))
        // Ket 27: the same distance at rest, and the direction of a flick.
        assertFalse(PlayerDismiss.closes(100f, 0f, h, fling, velocityAware = true))
        assertTrue(PlayerDismiss.closes(160f, 0f, h, fling, velocityAware = true))
        assertTrue(PlayerDismiss.closes(40f, 1200f, h, fling, velocityAware = true))
        assertFalse(PlayerDismiss.closes(400f, -1200f, h, fling, velocityAware = true))
        assertFalse("a flick with no travel is a tap", PlayerDismiss.closes(0f, 1200f, h, fling, velocityAware = true))
        PlayerDismiss.throwAt(1500f); assertEquals(1500f, PlayerDismiss.takeVelocity(), 0f); assertEquals(0f, PlayerDismiss.takeVelocity(), 0f)
    }

    @Test fun thePlayerUnfoldsFromTheMiniPlayerInOneMovement() {
        AppSettings.lyricsMode.value = 2
        val track = Track("qa:unfold", "Titre qui voyage", "Artiste", "Album", durationMs = 180_000)
        player.value = PlayerUiState(queue = listOf(track), queueEntryIds = listOf("fixture"), index = 0, durationMs = 180_000)
        // Where the mini-player's title and buttons are (px = dp here).
        MiniPlayerAnchors.title = Rect(72f, 750f, 270f, 786f); MiniPlayerAnchors.play = Rect(290f, 744f, 338f, 792f)
        MiniPlayerAnchors.next = Rect(340f, 744f, 388f, 792f)
        var visible by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { PlayerPresentation(visible, Rect(10f, 740f, 390f, 794f), Modifier.fillMaxSize()) {
            KetvoiceTheme(useDark = true) { Box(Modifier.fillMaxSize().testTag("presented-player")) { FullPlayerScreen(onDismiss = { visible = false }) } }
        } }
        compose.runOnIdle { visible = true }; compose.waitForIdle()
        compose.mainClock.advanceTimeBy(96)
        val early = compose.onNodeWithText("Titre qui voyage").fetchSemanticsNode().boundsInRoot
        capture("ket27-player-unfold-early")
        compose.mainClock.advanceTimeBy(160)
        val middle = compose.onNodeWithText("Titre qui voyage").fetchSemanticsNode().boundsInRoot
        capture("ket27-player-unfold-middle")
        compose.mainClock.advanceTimeBy(2500)
        val rest = compose.onNodeWithText("Titre qui voyage").fetchSemanticsNode().boundsInRoot
        capture("ket27-player-unfold-open")
        // The title starts near the mini-player, at the bottom, and rises to its place without leaving the path.
        assertTrue("early ${early.top}, middle ${middle.top}, rest ${rest.top}", early.top > middle.top && middle.top > rest.top)
        assertTrue("it leaves from the mini-player (${early.top})", early.top > 560f)
        assertTrue("it grows on the way (${early.height} then ${rest.height})", early.height < rest.height)
        // Closing is the same journey back, then the player leaves the composition.
        compose.runOnIdle { visible = false }; compose.waitForIdle()
        compose.mainClock.advanceTimeBy(96)
        capture("ket27-player-fold-back")
        val back = compose.onNodeWithText("Titre qui voyage").fetchSemanticsNode().boundsInRoot
        assertTrue("closing takes it back down (${back.top} from ${rest.top})", back.top > rest.top)
        compose.mainClock.advanceTimeBy(3000)
        compose.waitForIdle()
        compose.onNodeWithTag("presented-player").assertDoesNotExist()
    }

    @Test fun reducedMotionOpensThePlayerInPlace() {
        AppSettings.reduceAnimations.value = true
        AppSettings.lyricsMode.value = 2
        val track = Track("qa:still", "Titre immobile", "Artiste", "Album", durationMs = 180_000)
        player.value = PlayerUiState(queue = listOf(track), queueEntryIds = listOf("fixture"), index = 0, durationMs = 180_000)
        MiniPlayerAnchors.title = Rect(72f, 750f, 270f, 786f)
        var visible by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { PlayerPresentation(visible, Rect(10f, 740f, 390f, 794f), Modifier.fillMaxSize()) {
            KetvoiceTheme(useDark = true) { FullPlayerScreen(onDismiss = { visible = false }) }
        } }
        compose.runOnIdle { visible = true }; compose.waitForIdle()
        compose.mainClock.advanceTimeBy(48)
        capture("ket27-player-reduced")
        val first = compose.onNodeWithText("Titre immobile").fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(2000)
        val later = compose.onNodeWithText("Titre immobile").fetchSemanticsNode().boundsInRoot
        assertEquals(later.top, first.top, .5f); assertEquals(later.height, first.height, .5f)
    }

    // ---------------------------------------------------------------- sheets on detents

    @Test fun aSheetRestsAtHalfHeightAndAtFullHeight() {
        // A short sheet has one resting place: its own height.
        assertEquals(listOf(300f), SheetDetents.heights(300f, 900f))
        // A tall one rests at half the window, then at its full height (never more than the window).
        assertEquals(listOf(450f, 700f), SheetDetents.heights(700f, 900f))
        assertEquals(listOf(450f, 900f), SheetDetents.heights(2000f, 900f))
        assertEquals(emptyList<Float>(), SheetDetents.heights(0f, 900f))
        val detents = listOf(450f, 900f); val fling = 700f
        // Let go without speed: the nearest detent, or closed once well under the lowest.
        assertEquals(450f, SheetDetents.settle(520f, 0f, detents, fling), 0f)
        assertEquals(900f, SheetDetents.settle(700f, 0f, detents, fling), 0f)
        assertEquals(450f, SheetDetents.settle(340f, 0f, detents, fling), 0f)
        assertEquals(0f, SheetDetents.settle(300f, 0f, detents, fling), 0f)
        // A flick goes one detent in its direction, whatever the distance.
        assertEquals(900f, SheetDetents.settle(470f, -1200f, detents, fling), 0f)
        assertEquals(450f, SheetDetents.settle(880f, 1200f, detents, fling), 0f)
        assertEquals(0f, SheetDetents.settle(450f, 1200f, detents, fling), 0f)
        assertEquals(900f, SheetDetents.settle(900f, -1200f, detents, fling), 0f)
        assertEquals(0f, SheetDetents.scrim(0f, detents), 0f); assertEquals(1f, SheetDetents.scrim(700f, detents), 0f)
        assertEquals(.5f, SheetDetents.scrim(225f, detents), 1e-4f)
    }

    private fun tallSheet(closed: () -> Unit) {
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF202020))) {
                PlayerSheetScrim(closed) {
                    Column(Modifier.fillMaxWidth().heightIn(max = com.ketvoice.app.ui.common.sheetMaxHeight())
                        .background(Color(0xFF3A3A3C)).verticalScroll(rememberScrollState()).testTag("sheet-body")) {
                        repeat(30) { com.ketvoice.app.ui.common.Text("Ligne $it", localize = false, color = Color.White,
                            modifier = Modifier.fillMaxWidth().height(48.dp).padding(14.dp)) }
                    }
                }
            }
        } }
    }

    @Test fun theSheetOpensAtHalfHeightFollowsTheFingerAndCloses() {
        var closed = 0
        tallSheet { closed++ }
        compose.waitForIdle()
        val sheet = compose.onNodeWithTag("spring-sheet")
        assertEquals("half of a 900 dp window", 450f, sheet.fetchSemanticsNode().boundsInRoot.top, 2f)
        capture("ket27-sheet-half")
        // Pulled up, it goes to its full height; only then does the list inside it scroll.
        sheet.performTouchInput { down(center.copy(y = 60f)); moveBy(androidx.compose.ui.geometry.Offset(0f, -470f), 400); advanceEventTime(400); up() }
        compose.waitForIdle()
        assertEquals(0f, sheet.fetchSemanticsNode().boundsInRoot.top, 2f)
        compose.onNodeWithText("Ligne 1").assertIsDisplayed()
        capture("ket27-sheet-full")
        // Pulled down gently, it comes back to half height; pulled down again, it closes.
        sheet.performTouchInput { down(center.copy(y = 100f)); moveBy(androidx.compose.ui.geometry.Offset(0f, 430f), 400); advanceEventTime(400); up() }
        compose.waitForIdle()
        assertEquals(450f, sheet.fetchSemanticsNode().boundsInRoot.top, 2f)
        assertEquals(0, closed)
        sheet.performTouchInput { swipeDown(startY = 40f, endY = 400f, durationMillis = 160) }
        compose.waitForIdle()
        assertEquals(1, closed)
    }

    @Test fun reducedMotionPutsTheSheetOnItsDetentAtOnce() {
        AppSettings.reduceAnimations.value = true
        compose.mainClock.autoAdvance = false
        tallSheet { }
        // Measured in one frame, placed in the next: no spring to wait for.
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(450f, compose.onNodeWithTag("spring-sheet").fetchSemanticsNode().boundsInRoot.top, 2f)
    }

    @Test fun theClassicInterfaceKeepsItsSheet() {
        AppSettings.ket27.value = false
        tallSheet { }
        compose.waitForIdle()
        compose.onNodeWithTag("spring-sheet").assertDoesNotExist()
        // 650 dp at most, resting on the bottom edge.
        assertEquals(250f, compose.onNodeWithTag("sheet-body").fetchSemanticsNode().boundsInRoot.top, 2f)
    }

    // ---------------------------------------------------------------- context menu

    @Test fun theMenuHangsUnderThePreviewAndBothStayOnScreen() {
        // A row in the middle of the screen: the preview stays on it, the menu under it.
        val middle = ContextMenus.place(Rect(0f, 300f, 400f, 366f), 68f, 280f, 250f, 380f, 400f, 24f, 876f, 16f, 10f)
        assertEquals(300f, middle.previewTop, 0f); assertEquals(378f, middle.menuTop, 0f)
        assertEquals(16f, middle.previewLeft, 0f); assertEquals(368f, middle.previewWidth, 0f); assertEquals(16f, middle.menuLeft, 0f)
        // A row at the bottom: the preview moves up just enough for the menu to fit.
        val low = ContextMenus.place(Rect(0f, 780f, 400f, 846f), 68f, 280f, 250f, 380f, 400f, 24f, 876f, 16f, 10f)
        assertEquals(876f - 16f - 380f - 10f - 68f, low.previewTop, 0f)
        assertEquals(876f - 16f, low.menuTop + 380f, 0f)
        // A small card on the right: the menu hangs from the right side of the preview.
        val card = ContextMenus.place(Rect(260f, 200f, 392f, 380f), 68f, 280f, 250f, 380f, 400f, 24f, 876f, 16f, 10f)
        assertTrue(card.menuFromEnd); assertEquals(card.previewLeft + card.previewWidth, card.menuLeft + 250f, 0f)
        assertTrue(card.previewLeft + card.previewWidth <= 400f - 16f + .01f)
        assertEquals(.2f, ContextMenus.unfold(0f), 1e-4f); assertEquals(1f, ContextMenus.unfold(1f), 1e-4f)
    }

    @Test fun holdingARowUnfoldsAMenuFromIt() {
        val track = Track("qa:held", "Titre tenu", "Artiste tenu", "Album")
        compose.setContent { KetvoiceTheme(useDark = false) {
            Box(Modifier.fillMaxSize().background(com.ketvoice.app.theme.LocalTheme.current.background)) {
                Column(Modifier.padding(top = 200.dp)) { TrackRow(track, null, active = false, playing = false, onClick = {}) }
                ContextMenuHost()
            }
        } }
        compose.onNodeWithTag("context-menu").assertDoesNotExist()
        compose.onNodeWithText("Titre tenu").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("context-menu-preview").assertIsDisplayed()
        compose.onNodeWithTag("context-menu-actions").assertIsDisplayed()
        compose.onNodeWithText("Lire ensuite").assertIsDisplayed()
        compose.onNodeWithText("Ajouter aux favoris").assertIsDisplayed()
        assertNull("the full sheet is not opened as well", AppUi.trackMenuTrack.value)
        val preview = compose.onNodeWithTag("context-menu-preview").fetchSemanticsNode().boundsInRoot
        val menu = compose.onNodeWithTag("context-menu-actions").fetchSemanticsNode().boundsInRoot
        assertEquals("the preview sits on the row", 200f, preview.top, 12f)
        assertTrue("the menu hangs under it", menu.top >= preview.bottom && menu.bottom <= 900f)
        capture("ket27-context-menu")
        // "Options" hands over to the full sheet of the title.
        compose.onNodeWithText("Options").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("context-menu").assertDoesNotExist()
        assertEquals(track, AppUi.trackMenuTrack.value)
    }

    @Test fun theClassicInterfaceStillOpensTheSheetOnALongPress() {
        AppSettings.ket27.value = false
        val track = Track("qa:classic", "Titre classique", "Artiste", "Album")
        compose.setContent { KetvoiceTheme(useDark = false) { Box(Modifier.fillMaxSize()) {
            TrackRow(track, null, active = false, playing = false, onClick = {}); ContextMenuHost()
        } } }
        compose.onNodeWithText("Titre classique").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("context-menu").assertDoesNotExist()
        assertEquals(track, AppUi.trackMenuTrack.value)
    }

    // ---------------------------------------------------------------- controls

    @Test fun aSliderIsFeltStepByStep() {
        assertEquals("one tick per decibel of the equalizer", 24, SliderSteps.count(-12f, 12f))
        assertEquals(20, SliderSteps.count(0f, 1f)); assertEquals(20, SliderSteps.count(0f, 100f)); assertEquals(20, SliderSteps.count(100f, 2000f))
        assertEquals(0, SliderSteps.index(0f, 20)); assertEquals(20, SliderSteps.index(1f, 20)); assertEquals(10, SliderSteps.index(.51f, 20))
        assertFalse(SliderSteps.crossed(.50f, .51f, 20)); assertTrue(SliderSteps.crossed(.50f, .58f, 20))
        assertTrue(SliderSteps.crossed(.5f, .56f, VOLUME_STEPS)); assertFalse(SliderSteps.crossed(.5f, .52f, VOLUME_STEPS))
    }

    @Test fun theSliderAndTheSwitchTakeTheShapesOfIos() {
        var value by mutableStateOf(.4f)
        var checked by mutableStateOf(false)
        compose.setContent { KetvoiceTheme(useDark = false) {
            Column(Modifier.fillMaxSize().background(com.ketvoice.app.theme.LocalTheme.current.background).padding(24.dp)) {
                com.ketvoice.app.ui.common.GlassSlider(value, { value = it }, Modifier.testTag("slider"))
                com.ketvoice.app.ui.common.LiquidGlassSwitch(checked, { checked = it }, Modifier.testTag("switch"))
            }
        } }
        compose.onNodeWithTag("switch").performClick()
        compose.waitForIdle()
        assertTrue(checked)
        compose.onNodeWithTag("slider").performTouchInput { swipeRight(startX = centerX, endX = right - 4f) }
        compose.waitForIdle()
        assertTrue("dragged towards the end ($value)", value > .8f)
        capture("ket27-controls")
    }
}
