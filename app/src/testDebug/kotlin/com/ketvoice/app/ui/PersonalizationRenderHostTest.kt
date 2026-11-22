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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.AppFonts
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.KetTheme
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.LyricsLook
import com.ketvoice.app.theme.Personalization
import com.ketvoice.app.theme.ThemePresets
import com.ketvoice.app.ui.player.LyricsBlur
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * « Personnalisation » (2026-11-22) applied: colours, font, text size, corners and density reach the app's theme and
 * rows; the lyrics take their font, size, alignment, sung colour, blur and motion; and the page itself, rendered on the
 * host (software Skia, not a device GPU) in `build/host-ui-preview/<flavour>/personalize/`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PersonalizationRenderHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun setup() {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        AppSettings.languageChosen.value = true
        AppSettings.language.value = com.ketvoice.app.data.AppLanguage.FRENCH.label
        AppSettings.reduceAnimations.value = true
        AppSettings.trueBlack.value = false
        Personalization.resetAll()
    }

    @After fun reset() {
        Personalization.resetAll()
        AppSettings.reduceAnimations.value = false
        AppSettings.lyricsBlur.value = true
        AppUi.closeOverlays(); AppUi.selectTab(AppUi.HOME)
    }

    private fun capture(name: String): Bitmap {
        var out: Bitmap? = null
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            ReflectionHelpers.setField(canvas, "mAllowHwFeaturesInSwMode", true)
            view.draw(canvas)
            File("build/host-ui-preview/${com.ketvoice.app.BuildConfig.FLAVOR}/personalize/$name.png").also { it.parentFile?.mkdirs() }
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out = bitmap
        }
        return out!!
    }

    private var screen by androidx.compose.runtime.mutableStateOf<@androidx.compose.runtime.Composable () -> Unit>({})
    private var shown = false
    private var count = 0

    /** One content per test rule: later screens replace the first through a state. */
    private fun show(block: @androidx.compose.runtime.Composable () -> Unit) {
        // A new key each time: the previous screen's remembered state (an open page) does not carry over.
        val n = ++count
        val keyed: @androidx.compose.runtime.Composable () -> Unit = { androidx.compose.runtime.key(n) { block() } }
        if (shown) compose.runOnIdle { screen = keyed } else { screen = keyed; shown = true; compose.setContent { screen() } }
        compose.waitForIdle()
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    // ------------------------------------------------------------ theme, font, size, corners, density

    @Test fun aPresetReachesTheWholeThemeAndCoverColoursStillApply() {
        var seen: KetTheme? = null
        Personalization.applyPreset(ThemePresets.byId("paper")!!)
        show { KetvoiceTheme(useDark = false) { seen = LocalTheme.current } }
        compose.waitForIdle()
        assertEquals(Color(0xFFF4EFE6), seen!!.background)
        assertEquals(Color(0xFFFFFBF4), seen!!.card)
        assertEquals(Color(0xFF2B2620), seen!!.onBackground)
        // Cover tint still lays itself over the listener's colours.
        show { KetvoiceTheme(useDark = false, cover = com.ketvoice.app.theme.CoverLook(Color(0xFF2050C0), 1f)) { seen = LocalTheme.current } }
        compose.waitForIdle()
        assertNotEquals(Color(0xFFF4EFE6), seen!!.background)
    }

    @Test fun fontTextSizeAndCornersReachTheTheme() {
        var family: FontFamily? = null
        var fontScale = 0f
        var systemScale = 0f
        AppSettings.appFont.value = "lora"
        AppSettings.textScale.value = 120
        AppSettings.cornerScale.value = 0
        show {
            systemScale = LocalDensity.current.fontScale
            KetvoiceTheme(useDark = true) { family = LocalTextStyle.current.fontFamily; fontScale = LocalDensity.current.fontScale }
        }
        compose.waitForIdle()
        assertSame(AppFonts.family("lora"), family)
        assertSame(Ket27.font, family)
        assertEquals(systemScale * 1.2f, fontScale, .001f)
        assertEquals(0f, Ket27.Radius.card.value, 0f)
        AppSettings.cornerScale.value = 200
        assertEquals(48f, Ket27.Radius.card.value, .01f)
        assertEquals(68f, Ket27.Metrics.alertRadius.value, .01f)
        Personalization.resetAll()
        assertSame(AppFonts.family("inter"), Ket27.font)
        assertEquals(24f, Ket27.Radius.card.value, 0f)
        assertEquals(52f, Ket27.Metrics.row.value, 0f)
    }

    @Test fun listDensitySetsTheHeightOfRows() {
        val heights = (0..2).map { density ->
            AppSettings.listDensity.value = density
            show { KetvoiceTheme(useDark = false) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.testTag("row")) {
                        com.ketvoice.app.ui.common.TrackRow(Track("qa:density", "Titre", "Artiste", "Album"), null, active = false, playing = false, onClick = {})
                    }
                }
            } }
            compose.waitForIdle()
            bounds("row").height
        }
        assertEquals("compact, normal, airy", listOf(64f, 72f, 80f), heights.map { kotlin.math.round(it) })
    }

    // ------------------------------------------------------------ lyrics

    private val sungLine = MusicApi.LyricLine(-20_000L, "On chante encore", words = listOf(
        MusicApi.LyricWord(-20_000L, "On ", -19_500L), MusicApi.LyricWord(-19_500L, "chante ", -19_000L), MusicApi.LyricWord(-19_000L, "encore", -18_500L)))

    private fun lyric(line: MusicApi.LyricLine = MusicApi.LyricLine(-1L, "Bonjour"), timed: Boolean = false) {
        show { KetvoiceTheme(useDark = true) {
            Column(Modifier.fillMaxSize().background(Color(0xFF1B1B20)).padding(24.dp)) {
                Box(Modifier.fillMaxWidth().testTag("lyric")) {
                    androidx.compose.runtime.CompositionLocalProvider(com.ketvoice.app.ui.player.LocalLyricsInk provides
                        LyricsLook.sung(AppSettings.lyricsSung.value, LocalTheme.current.accent, null, AppSettings.lyricsSungArgb.value)) {
                        com.ketvoice.app.ui.player.LyricVoices(line, active = timed, timed = timed, fontScale = LyricsLook.sizeScale())
                    }
                }
            }
        } }
        compose.waitForIdle()
    }

    /** The leftmost column where the lyric line has light ink. */
    private fun firstInk(bitmap: Bitmap): Int {
        for (x in 0 until bitmap.width) for (y in 0 until 200) {
            val p = bitmap.getPixel(x, y)
            if ((p shr 16 and 0xFF) > 160 && (p shr 8 and 0xFF) > 160 && (p and 0xFF) > 160) return x
        }
        return -1
    }

    @Test fun lyricsAlignmentFontAndSizeAreApplied() {
        lyric()
        val left = capture("lyrics-left")
        val leftHeight = bounds("lyric").height
        AppSettings.lyricsAlign.value = LyricsLook.CENTRE
        lyric()
        val centred = capture("lyrics-centre")
        assertTrue("centred starts further in: ${firstInk(left)} vs ${firstInk(centred)}", firstInk(centred) > firstInk(left) + 60)
        AppSettings.lyricsAlign.value = LyricsLook.LEFT
        AppSettings.lyricsFont.value = "lora"
        lyric()
        assertSame(AppFonts.family("lora"), LyricsLook.font())
        val lora = capture("lyrics-lora")
        assertFalse("another face draws other pixels", left.sameAs(lora))
        AppSettings.lyricsFont.value = ""
        AppSettings.lyricsScale.value = 140
        lyric()
        assertTrue("bigger: ${bounds("lyric").height} vs $leftHeight", bounds("lyric").height > leftHeight * 1.3f)
    }

    @Test fun theSungWordsTakeTheChosenColour() {
        AppSettings.lyricsSung.value = LyricsLook.SUNG_CUSTOM
        AppSettings.lyricsSungArgb.value = 0xFFFFCC00L
        lyric(sungLine, timed = true)
        val image = capture("lyrics-sung-yellow")
        var yellow = 0; var white = 0
        for (x in 0 until image.width) for (y in 0 until 200) {
            val p = image.getPixel(x, y); val r = p shr 16 and 0xFF; val g = p shr 8 and 0xFF; val b = p and 0xFF
            if (r > 230 && g > 180 && b < 60) yellow++
            if (r > 235 && g > 235 && b > 235) white++
        }
        assertTrue("yellow ink $yellow, white $white", yellow > 300 && yellow > white * 4)
    }

    @Test fun blurAndMotionScaleThe83Lyrics() {
        // The 8.3 distances at 100 %, half at 50 %, nothing at 0 or with the switch off.
        val at = { level: Float, d: Int -> LyricsBlur.radiusDp(d, true, true, false, false, false, true, level) }
        assertEquals(1.7f, at(1f, 3), 0f); assertEquals(.85f, at(.5f, 3), .001f); assertEquals(0f, at(0f, 3), 0f)
        assertEquals("never the sung line", 0f, at(1f, 0), 0f)
        AppSettings.lyricsBlurLevel.value = 50
        assertEquals(.5f, LyricsLook.blur(), 0f)
        AppSettings.lyricsBlur.value = false
        assertEquals(0f, LyricsLook.blur(), 0f)
        AppSettings.lyricsMotion.value = 0
        assertEquals(0f, LyricsLook.motion(), 0f)
        val ink = com.ketvoice.app.ui.player.KaraokeInk(Color.White, null, 30f, 0f, 0f, 0f, false, motion = 0f)
        assertEquals("no growth at 0", 1f, ink.grown(1.035f), 0f)
        assertEquals(1.0175f, com.ketvoice.app.ui.player.KaraokeInk(Color.White, null, 30f, 0f, 0f, 0f, false, motion = .5f).grown(1.035f), .0001f)
        assertEquals(1.035f, com.ketvoice.app.ui.player.KaraokeInk(Color.White, null, 30f, 0f, 0f, 0f, false).grown(1.035f), 0f)
    }

    // ------------------------------------------------------------ the page

    private fun openPage(dark: Boolean) {
        show { KetvoiceTheme(useDark = dark) { com.ketvoice.app.ui.settings.SettingsScreen() } }
        compose.waitForIdle()
        compose.onNodeWithTag("settings-category-Personalize").performClick()
        compose.waitForIdle()
    }

    @Test fun thePageWithItsPreviewDarkAndLight() {
        openPage(dark = true)
        compose.onNodeWithTag("personalize-preview").assertIsDisplayed()
        compose.onNodeWithTag("personalize-lyrics-preview").assertExists()
        compose.onNodeWithText("Personnalisation").assertExists()
        capture("page-top-dark")
        compose.onNodeWithTag("personalize-fonts").performScrollTo()
        capture("page-theme-dark")
        compose.onNodeWithTag("personalize-lyrics-motion").performScrollTo()
        capture("page-lyrics-dark")
        openPage(dark = false)
        capture("page-top-light")
    }

    @Test fun choosingAPresetAFontAndADensityOnThePage() {
        openPage(dark = true)
        compose.onNodeWithTag("preset-forest").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(ThemeCodecStored.forest, AppSettings.themeColors.value)
        compose.onNodeWithTag("personalize-contrast").assertExists()
        capture("page-forest-dark")
        compose.onNodeWithTag("personalize-fonts-nunito").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals("nunito", AppSettings.appFont.value)
        compose.onNodeWithTag("personalize-density").performScrollTo()
        compose.onNodeWithText("Aérée").performClick()
        compose.waitForIdle()
        assertEquals(2, AppSettings.listDensity.value)
        compose.onNodeWithTag("personalize-preview").performScrollTo()
        capture("page-forest-nunito-airy-dark")
        compose.onNodeWithTag("personalize-lyrics-align").performScrollTo()
        compose.onNodeWithText("Centre").performClick()
        compose.waitForIdle()
        assertEquals(LyricsLook.CENTRE, AppSettings.lyricsAlign.value)
        compose.onNodeWithTag("personalize-reset").performScrollTo().performClick()
        compose.onNodeWithTag("personalize-reset-ok").performClick()
        compose.waitForIdle()
        assertFalse(Personalization.changed())
    }

    @Test fun aLowContrastColourIsAdjustedAndSaid() {
        Personalization.setColour(dark = true, role = Personalization.Role.Text, rgb = 0x202022)
        openPage(dark = true)
        compose.onNodeWithTag("personalize-adjusted").performScrollTo().assertIsDisplayed()
        capture("page-contrast-warning-dark")
    }

    @Test fun importingAThemeCodeOnThePage() {
        val code = com.ketvoice.app.theme.ThemeCodec.share(ThemePresets.byId("mist")!!.colors, 0xFF7458F0L)
        openPage(dark = false)
        compose.onNodeWithTag("personalize-import").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("personalize-import-code").performTextInput("KT1.abcdef")
        compose.onNodeWithTag("personalize-import-ok").performClick()
        compose.onNodeWithTag("personalize-import-wrong").assertExists()
        compose.onNodeWithTag("personalize-import-code").performTextClearance()
        compose.onNodeWithTag("personalize-import-code").performTextInput("Mon thème : $code")
        compose.onNodeWithTag("personalize-import-ok").performClick()
        compose.waitForIdle()
        assertEquals(com.ketvoice.app.theme.ThemeCodec.store(ThemePresets.byId("mist")!!.colors), AppSettings.themeColors.value)
        assertEquals(0xFF7458F0L, AppSettings.accentArgb.value)
    }

    private object ThemeCodecStored { val forest = com.ketvoice.app.theme.ThemeCodec.store(ThemePresets.byId("forest")!!.colors) }
}
