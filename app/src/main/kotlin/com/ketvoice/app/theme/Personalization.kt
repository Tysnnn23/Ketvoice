package com.ketvoice.app.theme

import com.ketvoice.app.data.AppSettings

/**
 * What the « Personnalisation » page changes, outside any screen so that the host tests drive it as the page does.
 * Nothing here saves: the page calls `SettingsStore.save()` after each change.
 */
object Personalization {
    /** The roles of a theme colour the editor offers. */
    enum class Role(val label: String) { Background("Fond"), Surface("Surfaces"), Text("Texte"), Accent("Accent") }

    /** The theme as stored, or Ket 27's colours as an editable start. */
    fun colors(): ThemeColors = ThemeCodec.parse(AppSettings.themeColors.value) ?: ThemePresets.ket27

    fun custom(): Boolean = ThemeCodec.parse(AppSettings.themeColors.value) != null

    fun applyPreset(preset: ThemePreset) {
        AppSettings.themeColors.value = ThemeCodec.store(preset.colors)
        AppSettings.accentArgb.value = preset.accent
    }

    fun colour(dark: Boolean, role: Role): Int {
        val side = colors().side(dark)
        return when (role) {
            Role.Background -> side.background
            Role.Surface -> side.surface
            Role.Text -> side.text
            Role.Accent -> AppSettings.accentArgb.value.toInt() and ThemeSide.RGB
        }
    }

    /** One colour changed; the theme becomes a custom one (an accent alone keeps the default colours). */
    fun setColour(dark: Boolean, role: Role, rgb: Int) {
        val clean = rgb and ThemeSide.RGB
        if (role == Role.Accent) { AppSettings.accentArgb.value = 0xFF000000L or clean.toLong(); return }
        val all = colors()
        val side = all.side(dark)
        val next = when (role) {
            Role.Background -> side.copy(background = clean)
            Role.Surface -> side.copy(surface = clean)
            else -> side.copy(text = clean)
        }
        AppSettings.themeColors.value = ThemeCodec.store(all.with(dark, next))
    }

    fun shareCode(): String = ThemeCodec.share(ThemeCodec.parse(AppSettings.themeColors.value), AppSettings.accentArgb.value)

    /** Applies the first theme code found in [text]; false when there is none (nothing is changed then). */
    fun import(text: String): Boolean {
        val shared = ThemeCodec.find(text) ?: return false
        AppSettings.themeColors.value = ThemeCodec.store(shared.colors)
        AppSettings.accentArgb.value = shared.accent
        return true
    }

    /** Everything on the page back to the look of 8.4 (the accent too). */
    fun resetAll() {
        val s = AppSettings
        s.themeColors.value = ""; s.accentArgb.value = ThemePresets.DEFAULT_ACCENT
        s.appFont.value = AppFonts.INTER; s.textScale.value = AppShape.TEXT_DEFAULT; s.cornerScale.value = AppShape.CORNER_DEFAULT
        s.listDensity.value = AppShape.NORMAL
        s.lyricsFont.value = ""; s.lyricsScale.value = LyricsLook.SIZE_DEFAULT; s.lyricsAlign.value = LyricsLook.LEFT
        s.lyricsSung.value = LyricsLook.SUNG_WHITE; s.lyricsSungArgb.value = 0xFFFFFFFFL
        s.lyricsBlurLevel.value = 100; s.lyricsMotion.value = 100
    }

    /** Whether anything on the page differs from the defaults. */
    fun changed(): Boolean {
        val s = AppSettings
        return s.themeColors.value.isNotEmpty() || s.appFont.value != AppFonts.INTER || s.textScale.value != AppShape.TEXT_DEFAULT ||
            s.cornerScale.value != AppShape.CORNER_DEFAULT || s.listDensity.value != AppShape.NORMAL || s.lyricsFont.value.isNotEmpty() ||
            s.lyricsScale.value != LyricsLook.SIZE_DEFAULT || s.lyricsAlign.value != LyricsLook.LEFT || s.lyricsSung.value != LyricsLook.SUNG_WHITE ||
            s.lyricsBlurLevel.value != 100 || s.lyricsMotion.value != 100
    }
}
