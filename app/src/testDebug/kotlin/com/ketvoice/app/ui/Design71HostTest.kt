package com.ketvoice.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.*
import com.ketvoice.app.glass.BackdropCapture
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.glass.GlassStyles
import com.ketvoice.app.glass.LocalBackdropLayer
import com.ketvoice.app.glass.rememberGlassBackdrop
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerUiState
import com.ketvoice.app.theme.Contrast
import com.ketvoice.app.theme.CoverLook
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.chrome.FloatingChrome
import com.ketvoice.app.ui.common.ContextMenuHost
import com.ketvoice.app.ui.common.ContextMenus
import com.ketvoice.app.ui.common.Text
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** 7.1 design picks rendered on the host (Robolectric's native graphics; glass blur draws flat here). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Design71HostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Suppress("UNCHECKED_CAST") private val recent get() = ListeningHistory.tracks as MutableStateFlow<List<Track>>
    @Suppress("UNCHECKED_CAST") private val known get() = ListeningHistory.knownTracks as MutableStateFlow<List<Track>>
    @Suppress("UNCHECKED_CAST") private val player get() = Player.state as MutableStateFlow<PlayerUiState>
    private val lightCover = Brush.verticalGradient(listOf(Color(0xFFFFFDF6), Color(0xFFF4EFE4), Color(0xFFFFFFFF)))
    private val coverTone = CoverLook(tone = Color(0xFF2F6FD6), toneAmount = 1f)

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.language.value = AppLanguage.FRENCH.label
        AppSettings.languageChosen.value = true
        AppSettings.reduceAnimations.value = true
        AppSettings.glassStyle.value = GlassStyles.LIQUID_27
        AppSettings.liquidGlass.value = true
    }

    @After fun reset() {
        coil.Coil.reset()
        recent.value = emptyList(); known.value = emptyList()
        player.value = PlayerUiState()
        AppSettings.trueBlack.value = false
        AppSettings.glassIntensity.value = com.ketvoice.app.glass.GlassClarity.DEFAULT
        AppSettings.reduceAnimations.value = false
        AppSettings.glassStyle.value = 0
        ContextMenus.request.value = null
        AppUi.closeOverlays(); AppUi.selectTab(AppUi.HOME)
    }

    private fun bitmap(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
        view.draw(canvas)
        return bitmap
    }

    private fun image(name: String): Bitmap {
        compose.waitForIdle()
        var shot: Bitmap? = null
        compose.runOnIdle {
            shot = bitmap()
            val file = File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/design71-$name.png")
            file.parentFile?.mkdirs(); file.outputStream().use { shot!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return shot!!
    }

    /** The colour behind a line of text: the most frequent pixel of its bounds (the glyphs are the minority). */
    private fun ground(bitmap: Bitmap, bounds: Rect): Color {
        val counts = HashMap<Int, Int>()
        for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(bitmap.height))
            for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(bitmap.width))
                bitmap.getPixel(x, y).let { counts[it] = (counts[it] ?: 0) + 1 }
        return Color(counts.maxBy { it.value }.key)
    }

    private fun library(): List<Track> {
        val names = listOf("Abba", "Alicia", "Ángel", "Bonobo", "Björk", "Cassius", "Christine", "Daft", "Damso", "Édith",
            "Etienne", "Feu", "Gael", "Hania", "Iliona", "Jain", "Juliette", "Kavinsky", "Lomepal", "Lous", "Mistral",
            "Mylène", "Nekfeu", "Orelsan", "Øystein", "Pomme", "Phoenix", "Rone", "Roméo", "Stromae", "Sopico", "Tayc",
            "Vianney", "Wejdene", "Yelle", "Zaz", "Кино", "宇多田", "2Pac", "아이유")
        return names.mapIndexed { i, name -> Track("fixture:az$i", "$name titre", "$name", "Album $name", durationMs = 200_000) }
    }

    @Test fun libraryTitlesSortedByNameShowAnAccessibleAzIndex() {
        known.value = library(); recent.value = emptyList()
        var mode by androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent {
            KetvoiceTheme(useDark = mode > 0, cover = if (mode > 0) coverTone else CoverLook()) { com.ketvoice.app.ui.library.LibraryScreen() }
        }
        compose.onNode(hasText("Titres") and hasClickAction()).performClick()
        compose.onNodeWithTag("alphabet-index").assertDoesNotExist()
        compose.onNodeWithText("Titre A–Z").performScrollTo().performClick()
        // Over the header it stays away; once the list is scrolled it comes in.
        compose.onNodeWithTag("alphabet-index").assertDoesNotExist()
        compose.onNodeWithTag("library-list").performScrollToIndex(4)
        compose.onNodeWithTag("alphabet-index").assertIsDisplayed()
        compose.onNodeWithContentDescription("Autres noms").assertExists()
        image("library-az-light")
        compose.onNodeWithContentDescription("M").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Mistral titre").assertIsDisplayed()
        image("library-az-jump-light")
        mode = 1
        image("library-dark-cover")
        AppSettings.trueBlack.value = true; mode = 2
        image("library-noir-cover")
        // A search hides the index (the list is no longer the whole alphabet).
        compose.onNodeWithTag("library-list").performScrollToIndex(0)
        compose.onNode(hasSetTextAction()).performTextInput("Zaz")
        compose.onNodeWithTag("alphabet-index").assertDoesNotExist()
    }

    @Test fun homeInLightDarkAndNoir() {
        known.value = library().take(12); recent.value = known.value.take(8)
        var mode by androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent {
            KetvoiceTheme(useDark = mode > 0, cover = if (mode > 0) coverTone else CoverLook()) { com.ketvoice.app.ui.home.HomeScreen() }
        }
        compose.onNodeWithTag("home-greeting").assertIsDisplayed()
        image("home-light")
        mode = 1
        val dark = image("home-dark-cover")
        AppSettings.trueBlack.value = true; mode = 2
        val noir = image("home-noir-cover")
        // Noir: the page itself is pure black under the cover's colour; dark is the dimmed cover tone.
        assertTrue(noir.getPixel(4, 300) == android.graphics.Color.BLACK)
        assertTrue(dark.getPixel(4, 300) != android.graphics.Color.BLACK)
    }

    @Test fun contextMenuAndSheetStayReadableOverAVeryLightCover() {
        val track = Track("fixture:menu", "Titre du menu", "Artiste", "Album")
        var dark by androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent { KetvoiceTheme(useDark = dark == 1) {
            val backdrop = rememberGlassBackdrop(Color.White)
            Box(Modifier.fillMaxSize()) {
                BackdropCapture(backdrop, Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().background(lightCover)) }
                CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
                    val theme = LocalTheme.current
                    GlassPanel(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp).testTag("sample-sheet"),
                        RoundedCornerShape(34.dp), GlassParams.sheet(34f, dark = theme.isDark)) {
                        Column(Modifier.padding(24.dp)) {
                            Text("Texte du corps de la feuille", color = theme.onBackground, modifier = Modifier.testTag("sheet-body"))
                            Text("Une deuxième ligne de texte", color = theme.onBackground)
                        }
                    }
                    ContextMenuHost()
                }
            }
        } }
        compose.runOnIdle { ContextMenus.open(track, null) }
        for (mode in 0..1) {
            dark = mode
            compose.onNodeWithText("Lire ensuite").assertIsDisplayed()
            val shot = image(if (mode == 0) "menu-sheet-light-cover-light" else "menu-sheet-light-cover-dark")
            val ink = if (mode == 1) Color.White else Color.Black
            for (node in listOf(compose.onNodeWithText("Lire ensuite"), compose.onNodeWithTag("sheet-body"))) {
                val ratio = Contrast.contrastRatio(ink, ground(shot, node.fetchSemanticsNode().boundsInRoot))
                assertTrue("contrast $ratio (dark=$mode)", ratio >= 4.5f)
            }
        }
    }

    @Test fun collapsedMiniPlayerKeepsPlayAndNextAndStillOpensOnATap() {
        player.value = PlayerUiState(queue = listOf(Track("fixture:mini", "Titre en cours", "Artiste", "Album")), index = 0)
        var opened = 0
        compose.setContent { KetvoiceTheme(useDark = false) {
            val backdrop = rememberGlassBackdrop(Color.White)
            Box(Modifier.fillMaxSize()) {
                BackdropCapture(backdrop, Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().background(lightCover)) }
                CompositionLocalProvider(LocalBackdropLayer provides backdrop) {
                    Box(Modifier.align(Alignment.BottomCenter)) { FloatingChrome(collapsed = true, onOpenPlayer = { opened++ }) }
                }
            }
        } }
        compose.mainClock.advanceTimeBy(1200)
        image("mini-collapsed")
        compose.onNodeWithTag("mini-next", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Lecture").assertIsDisplayed()
        compose.onNodeWithText("Titre en cours").performClick()
        compose.runOnIdle { assertTrue(opened == 1) }
    }

    @Test fun tintedGlassIsClearlyDifferentFromClear() {
        compose.setContent { KetvoiceTheme(useDark = false) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                com.ketvoice.app.ui.settings.GlassTransparencyCard()
            }
        } }
        image("glass-clear")
        // 7.5: the opaque end of the intensity slider replaces the Teinté tab.
        compose.onNodeWithTag("glass-tint-slider").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(1f) }
        compose.runOnIdle { assertTrue(AppSettings.glassIntensity.value == 1f) }
        image("glass-tinted")
    }
}
