package com.ketvoice.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ketvoice.app.sync.SettingsKind
import com.ketvoice.app.theme.AppFonts
import com.ketvoice.app.theme.Personalization
import com.ketvoice.app.theme.ThemeCodec
import com.ketvoice.app.theme.ThemePresets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * « Personnalisation » (2026-11-22): twelve new keys, at the look of 8.4 by default, kept on reload, checked in
 * backups and in the account's sync (where an older version, which does not list them, leaves them alone).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersonalizationSettingsHostTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("ketvoice_settings", Context.MODE_PRIVATE)
    private val keys = listOf("themeColors", "appFont", "textScale", "cornerScale", "listDensity", "lyricsFont", "lyricsScale",
        "lyricsAlign", "lyricsSung", "lyricsSungArgb", "lyricsBlurLevel", "lyricsMotion")

    @org.junit.Before fun loaded() { SettingsStore.load(context) }

    @After fun reset() {
        Personalization.resetAll(); AppSettings.lyricsBlur.value = true
        SettingsStore.load(context); Personalization.resetAll(); SettingsStore.save()
    }

    @Test fun aNewInstallHasTheLookOf84() {
        prefs.edit().apply { keys.forEach(::remove) }.commit()
        SettingsStore.load(context)
        val s = AppSettings
        assertEquals("", s.themeColors.value); assertEquals("inter", s.appFont.value)
        assertEquals(100, s.textScale.value); assertEquals(100, s.cornerScale.value); assertEquals(1, s.listDensity.value)
        assertEquals("", s.lyricsFont.value); assertEquals(100, s.lyricsScale.value); assertEquals(0, s.lyricsAlign.value)
        assertEquals(0, s.lyricsSung.value); assertEquals(100, s.lyricsBlurLevel.value); assertEquals(100, s.lyricsMotion.value)
        assertFalse(Personalization.changed())
    }

    @Test fun everyChoiceSurvivesAReload() {
        Personalization.applyPreset(ThemePresets.byId("forest")!!)
        AppSettings.appFont.value = "lora"; AppSettings.textScale.value = 120; AppSettings.cornerScale.value = 0
        AppSettings.listDensity.value = 2; AppSettings.lyricsFont.value = "nunito"; AppSettings.lyricsScale.value = 130
        AppSettings.lyricsAlign.value = 1; AppSettings.lyricsSung.value = 3; AppSettings.lyricsSungArgb.value = 0xFFFFCC00L
        AppSettings.lyricsBlurLevel.value = 40; AppSettings.lyricsMotion.value = 0
        SettingsStore.save()
        Personalization.resetAll()
        SettingsStore.load(context)
        val s = AppSettings
        assertEquals(ThemeCodec.store(ThemePresets.byId("forest")!!.colors), s.themeColors.value)
        assertEquals(0xFF2E9A5FL, s.accentArgb.value)
        assertEquals(listOf("lora", 120, 0, 2, "nunito", 130, 1, 3, 0xFFFFCC00L, 40, 0), listOf(s.appFont.value, s.textScale.value,
            s.cornerScale.value, s.listDensity.value, s.lyricsFont.value, s.lyricsScale.value, s.lyricsAlign.value, s.lyricsSung.value,
            s.lyricsSungArgb.value, s.lyricsBlurLevel.value, s.lyricsMotion.value))
        assertTrue(Personalization.changed())
    }

    @Test fun valuesNoVersionWritesFallBackToTheDefaults() {
        prefs.edit().putString("themeColors", "rouge").putString("appFont", "comic-sans").putInt("textScale", 400)
            .putInt("cornerScale", -5).putInt("listDensity", 7).putString("lyricsFont", "papyrus").putInt("lyricsScale", 10)
            .putInt("lyricsAlign", 3).putInt("lyricsSung", 9).putInt("lyricsBlurLevel", 101).putInt("lyricsMotion", -1)
            .putString("lyricsSungArgb", "white").commit()
        SettingsStore.load(context)
        val s = AppSettings
        assertEquals("", s.themeColors.value); assertEquals(AppFonts.INTER, s.appFont.value); assertEquals(100, s.textScale.value)
        assertEquals(100, s.cornerScale.value); assertEquals(1, s.listDensity.value); assertEquals("", s.lyricsFont.value)
        assertEquals(100, s.lyricsScale.value); assertEquals(0, s.lyricsAlign.value); assertEquals(0, s.lyricsSung.value)
        assertEquals(100, s.lyricsBlurLevel.value); assertEquals(100, s.lyricsMotion.value); assertEquals(0xFFFFFFFFL, s.lyricsSungArgb.value)
    }

    @Test fun backupsCheckEachKey() {
        val theme = ThemeCodec.store(ThemePresets.byId("paper")!!.colors)
        assertEquals(theme, PersonalBackup.setting("themeColors", theme))
        assertEquals("", PersonalBackup.setting("themeColors", ""))
        assertNull(PersonalBackup.setting("themeColors", "javascript:alert(1)"))
        assertEquals("manrope", PersonalBackup.setting("appFont", "manrope"))
        assertNull(PersonalBackup.setting("appFont", ""))
        assertEquals("", PersonalBackup.setting("lyricsFont", ""))
        assertNull(PersonalBackup.setting("lyricsFont", "../font"))
        assertEquals(85, PersonalBackup.setting("textScale", 85)); assertNull(PersonalBackup.setting("textScale", 84))
        assertEquals(200, PersonalBackup.setting("cornerScale", 200)); assertNull(PersonalBackup.setting("cornerScale", 201))
        assertNull(PersonalBackup.setting("listDensity", 3)); assertNull(PersonalBackup.setting("lyricsAlign", 2))
        assertNull(PersonalBackup.setting("lyricsSung", 4)); assertNull(PersonalBackup.setting("lyricsMotion", 101))
        assertEquals(0xFFFFCC00L, PersonalBackup.setting("lyricsSungArgb", 0xFFFFCC00L))
        assertNull(PersonalBackup.setting("lyricsSungArgb", -1L))
        assertNull(PersonalBackup.setting("textScale", "120"))
        // What a future version may add is dropped, as these keys are by 8.4.
        assertNull(PersonalBackup.setting("tabsOrder", "home,radio"))
    }

    @Test fun theAccountsSyncCarriesThemAndSkipsWhatItDoesNotKnow() = runBlocking {
        keys.forEach { assertTrue(it, it in SettingsKind.keys) }
        assertEquals(1, SettingsKind.parse("lyricsAlign", "1"))
        assertEquals(0xFFFFCC00L, SettingsKind.parse("lyricsSungArgb", "4294953984"))
        val theme = ThemeCodec.store(ThemePresets.byId("slate")!!.colors)
        SettingsKind.apply(context, mapOf("themeColors" to "\"$theme\"", "appFont" to "\"atkinson\"", "textScale" to "115",
            "lyricsMotion" to "30", "listDensity" to "9", "futureKey" to "\"x\""))
        assertEquals(theme, AppSettings.themeColors.value)
        assertEquals("atkinson", AppSettings.appFont.value)
        assertEquals(115, AppSettings.textScale.value)
        assertEquals(30, AppSettings.lyricsMotion.value)
        assertEquals("out of range: kept as the device had it", 1, AppSettings.listDensity.value)
        assertFalse(prefs.contains("futureKey"))
    }

    @Test fun thePageActionsChangeOnlyWhatTheySay() {
        val accent = AppSettings.accentArgb.value
        Personalization.setColour(dark = true, role = Personalization.Role.Background, rgb = 0x101820)
        assertEquals(0x101820, ThemeCodec.parse(AppSettings.themeColors.value)!!.dark.background)
        assertEquals("the light side starts from Ket 27's", ThemePresets.ket27.light, ThemeCodec.parse(AppSettings.themeColors.value)!!.light)
        assertEquals(accent, AppSettings.accentArgb.value)
        Personalization.setColour(dark = false, role = Personalization.Role.Accent, rgb = 0x34C759)
        assertEquals(0xFF34C759L, AppSettings.accentArgb.value)
        val code = Personalization.shareCode()
        Personalization.resetAll()
        assertEquals("", AppSettings.themeColors.value)
        assertFalse(Personalization.import("pas un code"))
        assertEquals("", AppSettings.themeColors.value)
        assertTrue(Personalization.import("Mon thème : $code"))
        assertEquals(0x101820, ThemeCodec.parse(AppSettings.themeColors.value)!!.dark.background)
        assertEquals(0xFF34C759L, AppSettings.accentArgb.value)
        Personalization.applyPreset(ThemePresets.byId("ketvoice")!!)
        assertEquals("", AppSettings.themeColors.value)
        assertEquals(ThemePresets.DEFAULT_ACCENT, AppSettings.accentArgb.value)
    }
}
