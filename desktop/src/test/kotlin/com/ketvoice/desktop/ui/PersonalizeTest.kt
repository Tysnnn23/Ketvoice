package com.ketvoice.desktop.ui

import android.content.Context
import android.content.FileSharedPreferences
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.PersonalBackup
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.theme.AppFonts
import com.ketvoice.app.theme.CoverLegibility
import com.ketvoice.app.theme.Personalization
import com.ketvoice.app.theme.ThemeCodec
import com.ketvoice.app.theme.ThemePresets
import com.ketvoice.app.theme.ThemeSide
import com.ketvoice.app.theme.ThemeColors
import com.ketvoice.app.theme.LyricsLook as LyricsStyle
import com.ketvoice.desktop.ui.theme.DesktopTheme
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.KetColors
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** « Personnalisation » on the PC: the theme mapping, sizes, fonts, settings kept and synced, the page itself. */
@OptIn(ExperimentalTestApi::class)
class PersonalizeTest {
    @AfterTest fun tearDown() { Personalization.resetAll(); AppSettings.lyricsBlur.value = true; SettingsStore.resetForTest() }

    @Test fun theDefaultsAreTheDesktopsOwnColoursExactly() {
        for (dark in listOf(false, true)) {
            assertEquals(KetColors.of(dark), DesktopTheme.colors(dark, glass = false, themeColors = "", accentArgb = ThemePresets.DEFAULT_ACCENT))
            assertEquals(KetColors.glass(dark), DesktopTheme.colors(dark, glass = true, themeColors = "", accentArgb = ThemePresets.DEFAULT_ACCENT))
        }
        assertEquals(Ket27Desk.font, AppFonts.family(AppFonts.INTER))
        assertEquals(24.dp, Ket27Desk.Radius.card); assertEquals(48.dp, Ket27Desk.Metrics.trackRow); assertEquals(56.dp, Ket27Desk.Metrics.compactRow)
        val density = Density(1.25f, 1f)
        assertSame(density, DesktopTheme.scaled(density, 100))
    }

    @Test fun aCustomThemeIsGuardedToAA() {
        val paper = ThemePresets.byId("paper")!!
        val light = DesktopTheme.colors(false, false, ThemeCodec.store(paper.colors), paper.accent)
        assertEquals(Color(0xFFF4EFE6), light.ground); assertEquals(Color(0xFFFFFBF4), light.card); assertTrue(!light.dark)
        val dark = DesktopTheme.colors(true, false, ThemeCodec.store(paper.colors), paper.accent)
        assertEquals(Color(0xFF191613), dark.ground); assertTrue(dark.dark)
        // A murky theme: the text is moved just enough, and so is the accent.
        val murky = ThemeColors(ThemeSide(0xF2F2F7, 0xFFFFFF, 0x000000), ThemeSide(0x202020, 0x2A2A2A, 0x3A3A3A))
        val c = DesktopTheme.colors(true, false, ThemeCodec.store(murky), 0xFF333333L)
        for (ground in listOf(c.ground, c.card)) {
            assertTrue(CoverLegibility.contrast(c.label, ground) >= 4.5f)
            assertTrue(CoverLegibility.contrast(c.secondary, ground) >= 4.5f)
            assertTrue(CoverLegibility.contrast(c.accent, ground) >= 4.5f)
        }
        // Under Windows 11's glass the listener's colours stay, translucent over the Mica.
        val glass = DesktopTheme.colors(false, true, ThemeCodec.store(paper.colors), paper.accent)
        assertEquals(Color.Transparent, glass.window); assertTrue(glass.acrylic); assertEquals(light.label, glass.label)
    }

    @Test fun anAccentAloneKeepsTheDesktopColoursAndStaysReadable() {
        val c = DesktopTheme.colors(false, false, "", 0xFFFFEE00L)
        assertEquals(KetColors.of(false).ground, c.ground)
        assertNotEquals(KetColors.of(false).accent, c.accent)
        assertTrue(CoverLegibility.contrast(c.accent, c.ground) >= 4.5f && CoverLegibility.contrast(c.accent, c.card) >= 4.5f)
    }

    @Test fun textSizeCornersAndDensityFollowTheSettings() {
        assertEquals(1.3f, DesktopTheme.scaled(Density(1f, 1f), 130).fontScale, 1e-5f)
        assertEquals(.85f, DesktopTheme.scaled(Density(1f, 1f), 40).fontScale, 1e-5f)
        AppSettings.cornerScale.value = 0; assertEquals(0.dp, Ket27Desk.Radius.card)
        AppSettings.cornerScale.value = 200; assertEquals(48.dp, Ket27Desk.Radius.card); assertEquals(16.dp, Ket27Desk.Radius.row)
        AppSettings.listDensity.value = 0; assertEquals(40.dp, Ket27Desk.Metrics.trackRow)
        AppSettings.listDensity.value = 2; assertEquals(64.dp, Ket27Desk.Metrics.compactRow)
    }

    @Test fun everyFontIsReadFromTheAppsFilesWithItsLicence() {
        for (choice in AppFonts.all) {
            assertTrue(javaClass.classLoader.getResource("font/${choice.file}.ttf") != null, choice.file)
            assertTrue((AppFonts.family(choice.id) as FontListFontFamily).fonts.isNotEmpty(), choice.id)
        }
        for (licence in listOf("Inter-OFL.txt", "Manrope-OFL.txt", "AtkinsonHyperlegibleNext-OFL.txt", "Nunito-OFL.txt", "Lora-OFL.txt"))
            assertTrue(com.ketvoice.desktop.platform.About.components.any { it.file == licence }, licence)
        AppSettings.lyricsFont.value = ""; AppSettings.appFont.value = "lora"
        assertEquals(AppFonts.family("lora"), LyricsStyle.font())
    }

    @Test fun theLooksComeBackAfterARestartAndBadValuesAreRefused() {
        val ctx = Context(Files.createTempDirectory("ketvoice-personalize").toFile())
        SettingsStore.load(ctx)
        val code = ThemeCodec.store(ThemePresets.byId("forest")!!.colors)
        AppSettings.themeColors.value = code; AppSettings.appFont.value = "nunito"; AppSettings.textScale.value = 115
        AppSettings.cornerScale.value = 150; AppSettings.listDensity.value = 2; AppSettings.lyricsAlign.value = 1
        AppSettings.lyricsSung.value = 3; AppSettings.lyricsSungArgb.value = 0xFF66CCFFL; AppSettings.lyricsMotion.value = 40
        SettingsStore.save(); FileSharedPreferences.flush()
        Personalization.resetAll()
        SettingsStore.load(ctx)
        assertEquals(code, AppSettings.themeColors.value); assertEquals("nunito", AppSettings.appFont.value)
        assertEquals(115, AppSettings.textScale.value); assertEquals(150, AppSettings.cornerScale.value); assertEquals(2, AppSettings.listDensity.value)
        assertEquals(1, AppSettings.lyricsAlign.value); assertEquals(0xFF66CCFFL, AppSettings.lyricsSungArgb.value); assertEquals(40, AppSettings.lyricsMotion.value)
        // Values another version wrote out of range fall back to the defaults.
        val prefs = ctx.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE)
        prefs.edit().putInt("textScale", 500).putString("appFont", "comic").putString("themeColors", "nope").putInt("lyricsSung", 9).apply()
        FileSharedPreferences.flush()
        SettingsStore.resetForTest(); SettingsStore.load(ctx)
        assertEquals(100, AppSettings.textScale.value); assertEquals(AppFonts.INTER, AppSettings.appFont.value)
        assertEquals("", AppSettings.themeColors.value); assertEquals(0, AppSettings.lyricsSung.value)
    }

    @Test fun theSettingsSyncTakesThePhonesLooks() {
        assertEquals(code(), PersonalBackup.setting("themeColors", code()))
        assertNull(PersonalBackup.setting("themeColors", "red"))
        assertEquals("manrope", PersonalBackup.setting("appFont", "manrope")); assertNull(PersonalBackup.setting("appFont", "comic"))
        assertEquals("", PersonalBackup.setting("lyricsFont", ""))
        assertEquals(120, PersonalBackup.setting("textScale", 120)); assertNull(PersonalBackup.setting("textScale", 200))
        assertEquals(0xFF112233L, PersonalBackup.setting("lyricsSungArgb", 0xFF112233L))
    }

    private fun code() = ThemeCodec.store(ThemePresets.byId("slate")!!.colors)

    @Test fun aSharedCodeImportsTheSameTheme() {
        Personalization.applyPreset(ThemePresets.byId("mist")!!)
        val text = shareText()
        assertTrue("KT1." in text)
        val (colors, accent) = AppSettings.themeColors.value to AppSettings.accentArgb.value
        Personalization.resetAll()
        assertTrue(Personalization.import(text))
        assertEquals(colors, AppSettings.themeColors.value); assertEquals(accent, AppSettings.accentArgb.value)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.settings(dark: Boolean) {
        com.ketvoice.app.data.AppText.init(Context.app)
        Fixtures.installCovers()
        AppSettings.reduceAnimations.value = true
        setContent { KetvoiceShell(RecordingPlayer().playing(), Fixtures.library(), FixtureCatalog, dark = dark) }
        waitForIdle()
        onNodeWithTag("shell").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Comma) } }
        waitForIdle()
    }

    @Test fun thePageChangesTheWholeWindow() = runComposeUiTest {
        settings(dark = false)
        onNodeWithTag("page:settings").performScrollToNode(hasTestTag("personalize-presets"))
        onNodeWithTag("preset-paper").performClick()
        waitForIdle()
        assertEquals(ThemeCodec.store(ThemePresets.byId("paper")!!.colors), AppSettings.themeColors.value)
        assertEquals(Color(0xFFF4EFE6), DesktopTheme.colors(false, false).ground)
        onNodeWithTag("page:settings").performScrollToNode(hasTestTag("personalize-reset"))
        onNodeWithTag("personalize-reset").performClick()
        onNodeWithTag("confirm-ok").performClick()
        waitForIdle()
        assertEquals("", AppSettings.themeColors.value)
        assertEquals(KetColors.of(false), DesktopTheme.colors(false, false))
    }

    @Test fun lyricsLookSettingsShowInThePreview() = runComposeUiTest {
        settings(dark = true)
        onNodeWithTag("page:settings").performScrollToNode(hasTestTag("personalize-lyrics-align"))
        onNodeWithTag("personalize-lyrics-align:1").performClick()
        assertEquals(LyricsStyle.CENTRE, AppSettings.lyricsAlign.value)
        onNodeWithTag("personalize-lyrics-sung:1").performClick()
        assertEquals(LyricsStyle.SUNG_ACCENT, AppSettings.lyricsSung.value)
        onNodeWithTag("page:settings").performScrollToNode(hasTestTag("personalize-lyrics-preview"))
        onNodeWithTag("personalize-lyrics-preview").assertIsDisplayed()
    }

    @Test fun theSungInkStaysReadableInThePanelAndOnTheDarkPlayer() {
        val light = KetColors.of(false)
        assertEquals(light.label, deskSungInk(LyricsStyle.SUNG_WHITE, false, light.label, light.accent, light.ground, light.card, null, 0))
        val yellow = deskSungInk(LyricsStyle.SUNG_CUSTOM, false, light.label, light.accent, light.ground, light.card, null, 0xFFFFEE00L)
        assertTrue(CoverLegibility.contrast(yellow, light.ground) >= 4.5f)
        assertEquals(Color.White, deskSungInk(LyricsStyle.SUNG_WHITE, true, light.label, light.accent, light.ground, light.card, null, 0))
        val navy = deskSungInk(LyricsStyle.SUNG_CUSTOM, true, light.label, light.accent, light.ground, light.card, null, 0xFF101040L)
        assertTrue(CoverLegibility.contrast(navy, Color(0xFF26262A)) >= 4.5f)
    }

    @Test fun theFullScreenArtworkTakesTheShapeOfItsMedia() {
        // A cover: the square column it always had (the screen's height, at most a little over half its width).
        assertEquals(1036.8f, ArtworkFormat.sideDp(1080f, 1f, 1920f * .54f), .01f)
        assertEquals(737.64f, ArtworkFormat.sideDp(768f, null, 1366f * .54f), .01f)
        // An upright Canvas is as wide as its shape, a wide one is held to the cap (its sides cut, never bars).
        assertEquals(607.5f, ArtworkFormat.sideDp(1080f, 9f / 16f, 1036.8f), .01f)
        assertEquals(1036.8f, ArtworkFormat.sideDp(1080f, 16f / 9f, 1036.8f), .01f)
        assertEquals(1.5f, ArtworkFormat.aspect(0f, 300, 200)!!, 1e-6f)
        assertEquals(.5f, ArtworkFormat.aspect(.5f, 300, 200)!!, 1e-6f)
        assertNull(ArtworkFormat.aspect(Float.NaN, 0, 0))
    }
}
