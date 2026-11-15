package com.ketvoice.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.automix.Automix
import com.ketvoice.app.automix.MixKind
import com.ketvoice.app.data.*
import com.ketvoice.app.data.Frame
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.player.ArtworkCanvas
import com.ketvoice.app.ui.player.QueueActionsBar
import com.ketvoice.app.ui.player.QueuePane
import com.ketvoice.app.ui.player.ScrubBar
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Host fixtures in software Skia: layout, semantics and state, not a physical device or real audio. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutomixHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val player get() = Player.state as MutableStateFlow<PlayerUiState>
    private fun playerField(name: String) = Player::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private val mixUi get() = Automix::class.java.getDeclaredField("_ui").apply { isAccessible = true }.get(Automix) as MutableStateFlow<Automix.Ui>
    private var oldController: Any? = null
    private fun track(id: String, title: String, album: String = "Album $id") = Track("fixture:$id", title, "Artiste", album, durationMs = 200_000)

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.languageChosen.value = true
        AppSettings.reduceAnimations.value = true
        AppSettings.automix.value = false
        oldController = playerField("controller").get(Player)
        playerField("controller").set(Player, null)
    }

    @After fun clear() {
        coil.Coil.reset()
        mixUi.value = Automix.Ui()
        AppSettings.automix.value = false
        player.value = PlayerUiState()
        (Player.positionMs as MutableStateFlow<Long>).value = 0L
        playerField("controller").set(Player, oldController)
    }

    private fun image(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            org.robolectric.util.ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/$name.png").apply {
                parentFile?.mkdirs(); outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
    }

    @Test fun ketmixIsTheFirstSegmentOfTheBarUnderTheTransport() {
        player.value = PlayerUiState(queue = listOf(track("a", "Premier"), track("b", "Deuxième"), track("c", "Troisième")),
            queueEntryIds = listOf("k0", "k1", "k2"), index = 0)
        compose.setContent { KetvoiceTheme(useDark = true) {
            Column(Modifier.fillMaxSize().background(Color(0xFF14141A)).padding(18.dp)) {
                val state by Player.state.collectAsState()
                QueuePane(state, Modifier.weight(1f).fillMaxWidth())
                QueueActionsBar(state.shuffle, state.repeatOne, state.autoplay, state.repeatAll, {}, {}, {}, {})
            }
        } }
        // One switch only: in the bar, left of shuffle, and nothing over the list.
        compose.onAllNodesWithTag("automix-toggle").assertCountEquals(1)
        val toggle = compose.onNodeWithTag("automix-toggle")
        toggle.assertIsDisplayed().assertHasClickAction()
        val bounds = toggle.fetchSemanticsNode().boundsInRoot
        val shuffle = compose.onNodeWithContentDescription("Aléatoire").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("queue-list").fetchSemanticsNode().boundsInRoot
        assertTrue("left of shuffle (${bounds.right} / ${shuffle.left})", bounds.right <= shuffle.left + 1f)
        assertTrue("under the list (${bounds.top} / ${list.bottom})", bounds.top >= list.bottom - 1f)
        assertEquals("Désactivé", toggle.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        assertEquals("Ketmix", toggle.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single())
        image("ketmix-bar-off")
        toggle.performClick()
        compose.waitForIdle()
        assertTrue(AppSettings.automix.value)
        assertEquals("Activé", compose.onNodeWithTag("automix-toggle").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        image("ketmix-bar-on")
        compose.onNodeWithTag("automix-toggle").performClick()
        compose.waitForIdle()
        assertFalse(AppSettings.automix.value)
        compose.onNodeWithText("Troisième").assertIsDisplayed()
    }

    @Test fun theWideQueueCarriesKetmixWithItsModesAndSaysWhyItIsNotMixing() {
        val status = Automix::class.java.getDeclaredField("_status").apply { isAccessible = true }.get(Automix) as MutableStateFlow<String>
        player.value = PlayerUiState(queue = listOf(track("a", "Premier"), track("b", "Deuxième")), queueEntryIds = listOf("k0", "k1"), index = 0)
        AppSettings.automix.value = true
        try {
            status.value = "Pas de mix · titre répété"
            compose.setContent { KetvoiceTheme(useDark = true) {
                Box(Modifier.fillMaxSize().background(Color(0xFF14141A)).padding(18.dp)) {
                    val state by Player.state.collectAsState(); QueuePane(state, Modifier.fillMaxSize(), showModes = true)
                }
            } }
            compose.onNodeWithTag("automix-status").assertIsDisplayed()
            compose.onNodeWithText("Pas de mix · titre répété").assertIsDisplayed()
            // The line sits above the list, never over its rows.
            val line = compose.onNodeWithTag("automix-status").fetchSemanticsNode().boundsInRoot
            val list = compose.onNodeWithTag("queue-list").fetchSemanticsNode().boundsInRoot
            assertTrue("above the list (${line.bottom} / ${list.top})", line.bottom <= list.top + 1f)
            image("ketmix-queue-status")
            status.value = "Mix prévu dans 31 s · calé sur le tempo"
            compose.onNodeWithText("Mix prévu dans 31 s · calé sur le tempo").assertIsDisplayed()
            // Switched off: no status is shown, whatever was left in it.
            compose.onNodeWithTag("automix-toggle").performClick()
            compose.waitForIdle()
            compose.onAllNodesWithTag("automix-status").assertCountEquals(0)
        } finally { status.value = ""; AppSettings.automix.value = false }
    }

    @Test fun aMixShowsOnTheArtworkAndAboveTheProgressBar() {
        val from = track("a", "Sortant"); val to = track("b", "Entrant")
        player.value = PlayerUiState(queue = listOf(from, to), queueEntryIds = listOf("k0", "k1"), index = 0, isPlaying = true,
            durationMs = 200_000)
        (Player.positionMs as MutableStateFlow<Long>).value = 188_000L
        compose.mainClock.autoAdvance = false
        mixUi.value = Automix.Ui(Automix.Phase.Mixing, from, to, MixKind.Beatmatch,
            startedAt = android.os.SystemClock.elapsedRealtime() - 3_000, durationMs = 12_000, bpm = 120f,
            swapAt = .5f, measures = "124 → 128 BPM · 8A → 9A")
        compose.setContent { KetvoiceTheme(useDark = true) {
            Column(Modifier.fillMaxSize().background(Color(0xFF14141A)).padding(18.dp)) {
                ArtworkCanvas(from, null, playing = true, modifier = Modifier.size(320.dp), corner = 18.dp)
                com.ketvoice.app.ui.player.MixStrip(Modifier.fillMaxWidth().padding(top = 16.dp))
                ScrubBar(200_000, Modifier.fillMaxWidth().padding(top = 24.dp), onSeek = {})
                // A title that is not part of the mix shows nothing of it.
                ArtworkCanvas(track("z", "Autre"), null, playing = false, modifier = Modifier.size(120.dp))
            }
        } }
        compose.mainClock.advanceTimeBy(64)
        compose.onAllNodesWithTag("mix-artwork").assertCountEquals(1)
        compose.onNodeWithTag("mix-badge").assertExists()
        compose.onNodeWithText("Mix en cours").assertExists()
        compose.onNodeWithText(" · Entrant").assertExists()
        compose.onNodeWithTag("mix-strip").assertExists()
        compose.onNodeWithText("124 → 128 BPM · 8A → 9A").assertExists()
        image("automix-mixing")
        // After the hand-over the roles swap: the incoming title is shown, the outgoing one dissolves away.
        mixUi.value = mixUi.value.copy(promoted = true)
        compose.mainClock.advanceTimeBy(64)
        compose.onAllNodesWithTag("mix-artwork").assertCountEquals(0)
        mixUi.value = Automix.Ui()
        compose.mainClock.advanceTimeBy(64)
        compose.onAllNodesWithTag("mix-badge").assertCountEquals(0)
        compose.onAllNodesWithTag("mix-strip").assertCountEquals(0)
    }

    @Test fun severalTitlesOfTheQueueAreChosenThenRemovedOrPlayedNext() {
        val titles = listOf(track("a", "Premier"), track("b", "Deuxième"), track("c", "Troisième"), track("d", "Quatrième"), track("e", "Cinquième"))
        player.value = PlayerUiState(queue = titles, queueEntryIds = listOf("k0", "k1", "k2", "k3", "k4"), index = 0)
        compose.setContent { KetvoiceTheme(useDark = true) {
            Box(Modifier.fillMaxSize().background(Color(0xFF14141A)).padding(18.dp)) {
                val state by Player.state.collectAsState(); QueuePane(state, Modifier.fillMaxSize())
            }
        } }
        compose.onAllNodesWithTag("queue-row-check", useUnmergedTree = true).assertCountEquals(0)
        // A long press starts choosing, with that title chosen.
        compose.onNodeWithText("Troisième").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onAllNodesWithTag("queue-row-check", useUnmergedTree = true).assertCountEquals(4)
        compose.onNodeWithText("1 titre choisi").assertIsDisplayed()
        compose.onNodeWithText("Cinquième").performClick()
        compose.onNodeWithText("2 titres choisis").assertIsDisplayed()
        image("queue-selection")
        // Played next: they move right after the title playing, in their order.
        compose.onNodeWithTag("queue-selection-next").performClick()
        compose.waitForIdle()
        assertEquals(listOf("k0", "k2", "k4", "k1", "k3"), Player.state.value.queueEntryIds)
        compose.onAllNodesWithTag("queue-row-check", useUnmergedTree = true).assertCountEquals(0)
        // Chosen again, all at once, then removed.
        compose.onNodeWithTag("queue-select").performClick()
        compose.onNodeWithTag("queue-select-all").performClick()
        compose.onNodeWithText("4 titres choisis").assertIsDisplayed()
        compose.onNodeWithTag("queue-selection-remove").performClick()
        compose.waitForIdle()
        assertEquals(listOf("k0"), Player.state.value.queueEntryIds)
        // Back closes the selection without changing anything.
        player.value = PlayerUiState(queue = titles, queueEntryIds = listOf("k0", "k1", "k2", "k3", "k4"), index = 0)
        compose.onNodeWithTag("queue-select").performClick()
        compose.onNodeWithTag("queue-select-done").performClick()
        compose.onAllNodesWithTag("queue-row-check", useUnmergedTree = true).assertCountEquals(0)
        assertEquals(5, Player.state.value.queue.size)
    }

    @Test fun theFramingScreenWorksLikeAPhotoEditor() {
        var done: Frame? = null; var cancelled = false
        compose.setContent { KetvoiceTheme(useDark = true) {
            com.ketvoice.app.ui.sub.FramingEditor(track("a", "Titre"), null, null, initial = null, pageBoxAspect = 1.2f,
                onCancel = { cancelled = true }, onDone = { done = it })
        } }
        for (tag in listOf("framing-stage", "framing-angle", "framing-rotate", "framing-flip", "framing-custom", "framing-original",
            "framing-square", "framing-cancel", "framing-done")) compose.onNodeWithTag(tag).assertIsDisplayed()
        compose.onNodeWithText("Pivoter").assertIsDisplayed(); compose.onNodeWithText("Horizontal").assertIsDisplayed()
        compose.onNodeWithText("Personnalisé").assertIsDisplayed(); compose.onNodeWithText("Original").assertIsDisplayed()
        compose.onNodeWithText("1:1").assertIsDisplayed(); compose.onNodeWithText("0°").assertIsDisplayed()
        image("framing-editor")
        // The rectangle opens on what the page shows: a band 1.2 times wider than tall.
        compose.onNodeWithTag("framing-done").performClick()
        assertEquals(1.2f, done!!.aspect, .01f); assertEquals(0f, done!!.rotation, 0f)
        // A square, a quarter turn, a mirror.
        compose.onNodeWithTag("framing-square").performClick()
        compose.onNodeWithTag("framing-rotate").performClick()
        compose.onNodeWithTag("framing-flip").performClick()
        compose.onNodeWithTag("framing-done").performClick()
        assertEquals(1f, done!!.aspect, .01f); assertEquals(-90f, done!!.rotation, .5f); assertTrue(done!!.flip)
        // The wheel: dragging it to the left by 70 dp tilts by ten degrees.
        compose.onNodeWithTag("framing-angle").performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(-70f, 0f)); up() }
        // (the first pixels of a drag are the system's touch slop)
        val shownAngle = compose.onNodeWithTag("framing-angle-value").fetchSemanticsNode().config[SemanticsProperties.Text].first().text
        assertTrue("about ten degrees ($shownAngle)", shownAngle.removeSuffix("°").toInt() in 8..10)
        compose.onNodeWithTag("framing-angle").performTouchInput { doubleClick() }
        compose.onNodeWithText("0°").assertIsDisplayed()
        // Original takes the whole picture; dragging the rectangle afterwards makes it a custom one again.
        compose.onNodeWithTag("framing-original").performClick()
        compose.onNodeWithTag("framing-done").performClick()
        assertEquals(1f, done!!.w, .01f); assertEquals(1f, done!!.h, .01f)
        compose.onNodeWithTag("framing-stage").performTouchInput {
            // Take the right edge and pull it inwards.
            val unit = minOf(width * .9f, height * .9f)
            down(androidx.compose.ui.geometry.Offset(centerX + unit / 2f - 4f, centerY)); moveBy(androidx.compose.ui.geometry.Offset(-unit * .25f, 0f)); up()
        }
        compose.onNodeWithTag("framing-done").performClick()
        assertEquals(.75f, done!!.w, .03f); assertEquals(1f, done!!.h, .01f); assertEquals(-.125f, done!!.cx, .02f)
        image("framing-editor-custom")
        compose.onNodeWithTag("framing-cancel").performClick()
        assertTrue(cancelled)
        // Alone, the screen frames the page only: no modes to choose from.
        compose.onAllNodesWithTag("framing-scope-player").assertCountEquals(0)
    }

    @Test fun theFramingScreenHasAModeForTheMusicPlayerAlone() {
        var page: Frame? = null; var player: Frame? = null
        val box = .85f
        compose.setContent { KetvoiceTheme(useDark = true) {
            com.ketvoice.app.ui.sub.FramingEditor(track("a", "Titre"), null, null, initial = Frame(0f, 0f, 1f, .5f), pageBoxAspect = 1.2f,
                onCancel = { }, onDone = { page = it }, playerInitial = null, playerBoxAspect = box, onPlayerDone = { player = it })
        } }
        compose.onNodeWithTag("framing-scope-page").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("framing-scope-player").assertIsDisplayed()
        assertEquals("Sélectionné", compose.onNodeWithTag("framing-scope-page").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        compose.onNodeWithTag("framing-custom").assertIsDisplayed()
        compose.onNodeWithTag("framing-scope-player").performClick()
        compose.waitForIdle()
        assertEquals("Sélectionné", compose.onNodeWithTag("framing-scope-player").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        // One shape only in this mode: the free shapes leave, a way back to the default comes.
        compose.onAllNodesWithTag("framing-custom").assertCountEquals(0)
        compose.onAllNodesWithTag("framing-square").assertCountEquals(0)
        compose.onNodeWithTag("framing-player-default").assertIsDisplayed()
        compose.onNodeWithTag("framing-player-note").assertIsDisplayed()
        image("framing-editor-player")
        // Without a framing of its own the player shows the page's band as its artwork crops it.
        compose.onNodeWithTag("framing-done").performClick()
        assertNull("The page's callback is not the one called", page)
        assertEquals(box, player!!.aspect, .01f); assertEquals(.5f, player!!.h, .01f)
        // An edge pulled inwards: both sides follow, the shape stays the player's.
        compose.onNodeWithTag("framing-stage").performTouchInput {
            val unit = minOf(width * .9f, height * .9f)
            down(androidx.compose.ui.geometry.Offset(centerX + unit * .5f * box / 2f - 4f, centerY)); moveBy(androidx.compose.ui.geometry.Offset(-unit * .1f, 0f)); up()
        }
        compose.onNodeWithTag("framing-rotate").performClick()
        compose.onNodeWithTag("framing-done").performClick()
        assertEquals(box, player!!.aspect, .01f); assertEquals(90f, player!!.rotation, .5f)
        assertTrue("smaller than before (${player!!.h})", player!!.h < .49f)
        // Back to the page's mode: its own rectangle waited there, untouched.
        compose.onNodeWithTag("framing-scope-page").performClick()
        compose.onNodeWithTag("framing-done").performClick()
        assertEquals(2f, page!!.aspect, .01f); assertEquals(0f, page!!.rotation, 0f)
        // And the player's is found again as it was left.
        player = null
        compose.onNodeWithTag("framing-scope-player").performClick()
        compose.onNodeWithTag("framing-done").performClick()
        assertEquals(90f, player!!.rotation, .5f)
    }
}
