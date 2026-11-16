package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.KetvoiceTheme
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

/** Every page under Ket 27, drawn in software on the host (not a phone), with what makes it a Ket 27 page checked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ket27PagesHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST")
    private val player get() = Player.state as MutableStateFlow<PlayerUiState>

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
        AppSettings.ket27.value = true
    }
    @After fun reset() {
        AppSettings.ket27.value = false
        AppSettings.reduceAnimations.value = false
        AppUi.closeOverlays(); AppUi.selectTab(AppUi.HOME)
        player.value = PlayerUiState()
    }

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

    private fun page(dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { KetvoiceTheme(useDark = dark) { content() } }
        compose.waitForIdle()
    }

    /** The bar of a pushed page: a round glass button with a chevron, the name of the page in its middle. */
    private fun assertPushedPage(title: String) {
        compose.onNodeWithTag("ket27-page-bar").assertIsDisplayed()
        val bar = compose.onNodeWithTag("ket27-page-bar").fetchSemanticsNode().boundsInRoot
        val name = compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag("ket27-page-bar")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("the name is centred in the bar", bar.center.x, name.center.x, 2f)
        assertEquals(54f, bar.height, .5f)
        compose.onNode(hasContentDescription("Retour") and hasAnyAncestor(hasTestTag("ket27-page-bar")), useUnmergedTree = true).assertExists()
    }

    /** A root page opens on a large title: 34 dp bold, 16 dp from the edge. */
    private fun assertLargeTitle(text: String) {
        val node = compose.onNodeWithText(text).fetchSemanticsNode()
        compose.runOnIdle {
            val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
            assertEquals(34f, layout.first().layoutInput.style.fontSize.value, 0f)
            assertEquals(androidx.compose.ui.text.font.FontWeight.Bold, layout.first().layoutInput.style.fontWeight)
            assertEquals(16f, node.boundsInRoot.left, 1f)
        }
    }

    @Test fun home() {
        page(false) { com.ketvoice.app.ui.home.HomeScreen() }
        compose.onNodeWithTag("home-greeting").assertIsDisplayed()
        assertLargeTitle(com.ketvoice.app.ui.home.homeGreeting(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)))
        image("ket27-page-home")
    }
    @Test fun explorer() { page(false) { com.ketvoice.app.ui.explorer.ExplorerScreen() }; assertLargeTitle("Explorer"); image("ket27-page-explorer") }
    @Test fun search() { page(false) { com.ketvoice.app.ui.overlay.SearchScreen() }; assertLargeTitle("Rechercher"); image("ket27-page-search") }
    @Test fun library() { page(true) { com.ketvoice.app.ui.library.LibraryScreen() }; image("ket27-page-library") }
    @Test fun sources() {
        page(false) { com.ketvoice.app.ui.sub.SourcesScreen() }
        assertPushedPage("Sources")
        compose.onNodeWithContentDescription("Ajouter").assertIsDisplayed()
        image("ket27-page-sources")
    }
    @Test fun downloads() { page(false) { com.ketvoice.app.ui.sub.DownloadsScreen() }; image("ket27-page-downloads") }
    @Test fun localMusic() { page(true) { com.ketvoice.app.ui.sub.LocalMusicScreen() }; assertPushedPage("Musique locale"); image("ket27-page-local") }
    @Test fun replay() { page(false) { com.ketvoice.app.ui.sub.ReplayScreen() }; assertPushedPage("Replay"); image("ket27-page-replay") }
    @Test fun stats() { page(false) { com.ketvoice.app.ui.stats.ListeningStatsScreen() }; assertLargeTitle("Ton récap"); image("ket27-page-stats") }
    @Test fun studio() { page(false) { com.ketvoice.app.ui.studio.PublisherScreen(null) {} }; image("ket27-page-studio") }
    @Test fun account() { page(false) { com.ketvoice.app.ui.account.AccountDialog() }; image("ket27-page-account") }
    @Test fun catalog() {
        page(false) { com.ketvoice.app.ui.sub.CatalogScreen(com.ketvoice.app.data.CatalogRequest("history", "history", "Historique")) }
        assertPushedPage("Historique")
        image("ket27-page-catalog")
    }
    @Test fun queue() {
        val tracks = (0 until 5).map { Track("qa:q$it", "Titre $it", "Artiste $it", "Album", durationMs = 180_000) }
        player.value = PlayerUiState(queue = tracks, queueEntryIds = tracks.map { it.id }, index = 0, durationMs = 180_000)
        page(true) { com.ketvoice.app.ui.player.FullPlayerScreen(initialPage = com.ketvoice.app.ui.player.PlayerPage.QUEUE, onDismiss = {}) }
        // Inter is wider than the classic face: the name of the queue is no longer cut by its actions.
        val name = compose.onNodeWithText("File d'attente").fetchSemanticsNode()
        compose.runOnIdle {
            val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            name.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
            assertFalse("the name is whole", layout.first().hasVisualOverflow)
        }
        compose.onNodeWithTag("queue-select").assertIsDisplayed(); compose.onNodeWithTag("queue-clear").assertIsDisplayed()
        image("ket27-page-queue")
    }

    @Test fun theClassicPagesKeepTheirOwnHeaders() {
        AppSettings.ket27.value = false
        page(false) { com.ketvoice.app.ui.sub.SourcesScreen() }
        compose.onNodeWithTag("ket27-page-bar").assertDoesNotExist()
        compose.onNodeWithText("Sources").assertIsDisplayed()
        compose.onNodeWithContentDescription("Retour").assertIsDisplayed()
    }

    @Test fun anAlertIsARoundedCardWithTwoCapsules() {
        var confirmed = 0; var dismissed = 0
        page(false) {
            com.ketvoice.app.ui.common.AppAlertDialog(onDismissRequest = { dismissed++ },
                title = { com.ketvoice.app.ui.common.Text("Supprimer cette playlist ?") },
                text = { com.ketvoice.app.ui.common.Text("Les titres restent dans votre bibliothèque.", localize = false) },
                confirmButton = { androidx.compose.material3.TextButton({ confirmed++ }) { com.ketvoice.app.ui.common.Text("Supprimer") } },
                dismissButton = { androidx.compose.material3.TextButton({ dismissed++ }) { com.ketvoice.app.ui.common.Text("Annuler") } })
        }
        compose.onNodeWithTag("ket27-alert").assertIsDisplayed()
        val cancel = compose.onNodeWithText("Annuler").fetchSemanticsNode().boundsInRoot
        val confirm = compose.onNodeWithText("Supprimer").fetchSemanticsNode().boundsInRoot
        assertEquals("the two buttons are side by side", cancel.center.y, confirm.center.y, 2f)
        assertTrue(cancel.right <= confirm.left)
        compose.onNodeWithText("Supprimer").performClick()
        compose.runOnIdle { assertEquals(1, confirmed); assertEquals(0, dismissed) }
    }

    @Test fun aCheckBoxIsTheRoundMarkOfAnIosList() {
        var checked by androidx.compose.runtime.mutableStateOf(false)
        page(false) {
            androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxSize()
                .background(com.ketvoice.app.theme.LocalTheme.current.background).padding(24.dp)) {
                com.ketvoice.app.ui.common.KetCheckbox(checked, { checked = it })
                com.ketvoice.app.ui.common.KetCheckbox(true, null)
            }
        }
        compose.onAllNodesWithTag("ket-checkbox")[0].assertIsOff().performClick()
        compose.waitForIdle()
        assertTrue(checked)
        compose.onAllNodesWithTag("ket-checkbox")[0].assertIsOn()
        image("ket27-checkbox")
    }

    @Test fun aPickerIsAnInsetListOnASheetThatOpensWhole() {
        AppSettings.reduceAnimations.value = false
        page(false) { com.ketvoice.app.ui.settings.SettingsScreen() }
        compose.onNodeWithTag("settings-category-Appearance").performClick()
        compose.onAllNodesWithText("Langue de l'application").onLast().performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("choice-options").assertIsDisplayed()
        compose.onNodeWithTag("spring-sheet").assertIsDisplayed()
        // Every language is within reach and the sheet does not hide its last rows under the edge.
        val sheet = compose.onNodeWithTag("spring-sheet").fetchSemanticsNode().boundsInRoot
        assertTrue("the sheet ends on screen (${sheet.bottom})", sheet.bottom <= 900.5f)
        compose.onNodeWithText("English").performScrollTo().assertIsDisplayed()
    }

    private fun inkOf(text: String): androidx.compose.ui.graphics.Color {
        val node = compose.onNodeWithText(text).fetchSemanticsNode()
        var ink = androidx.compose.ui.graphics.Color.Unspecified
        compose.runOnIdle {
            val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
            ink = layout.first().layoutInput.style.color
        }
        return ink
    }

    @Test fun aMenuSheetTakesTheColoursOfThePageUnderIt() {
        val track = Track("qa:menu", "Un titre", "Une artiste", "Album")
        page(false) {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(com.ketvoice.app.theme.LocalTheme.current.background)) {
                com.ketvoice.app.ui.player.PlayerSheetScrim({}) { com.ketvoice.app.ui.player.TrackMenuSheet(track) {} }
            }
        }
        compose.onNodeWithText("Lancer la radio").assertExists()
        // On a light page the menu is written in the page's ink, not in the white of the classic dark sheet.
        assertEquals(androidx.compose.ui.graphics.Color.Black, inkOf("Un titre"))
        image("ket27-track-menu-light")
    }

    @Test fun theClassicMenuSheetStaysWhiteOnDarkGlass() {
        AppSettings.ket27.value = false
        val track = Track("qa:menu", "Un titre", "Une artiste", "Album")
        page(false) { com.ketvoice.app.ui.player.PlayerSheetScrim({}) { com.ketvoice.app.ui.player.TrackMenuSheet(track) {} } }
        assertEquals(androidx.compose.ui.graphics.Color.White, inkOf("Un titre"))
    }
}
