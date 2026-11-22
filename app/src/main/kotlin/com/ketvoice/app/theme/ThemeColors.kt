package com.ketvoice.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.util.Base64

/**
 * « Thèmes complets » (owner, 2026-11-22: « rends complètement customisable l'app »): the listener's own colours for
 * one side of the interface, light or dark — the page, the surfaces on it (cards, grouped lists, sheets) and the text.
 * Opaque RGB (0xRRGGBB). The accent stays the `accentArgb` setting; cover colours (`coverAccent`, `coverBackground`)
 * still lay themselves over these, as over the default.
 */
data class ThemeSide(val background: Int, val surface: Int, val text: Int) {
    fun normalized() = ThemeSide(background and RGB, surface and RGB, text and RGB)
    companion object { const val RGB = 0xFFFFFF }
}

/** Both sides of a theme: the light and dark appearance (System / Clair / Sombre) pick one. */
data class ThemeColors(val light: ThemeSide, val dark: ThemeSide) {
    fun side(dark: Boolean): ThemeSide = if (dark) this.dark else light
    fun with(dark: Boolean, side: ThemeSide): ThemeColors = if (dark) copy(dark = side) else copy(light = side)
}

/** A ready-made theme. [colors] null is Ketvoice's own Ket 27 colours, exactly as without any custom theme. */
data class ThemePreset(val id: String, val name: String, val colors: ThemeColors?, val accent: Long)

object ThemePresets {
    /** Ket 27's system colours as an editable starting point (what « Personnaliser » starts from on the default). */
    val ket27 = ThemeColors(ThemeSide(0xF2F2F7, 0xFFFFFF, 0x000000), ThemeSide(0x000000, 0x1C1C1E, 0xFFFFFF))
    const val DEFAULT_ACCENT = 0xFFFF2D55L

    val all: List<ThemePreset> = listOf(
        ThemePreset("ketvoice", "Ketvoice", null, DEFAULT_ACCENT),
        ThemePreset("oled", "OLED", ThemeColors(ThemeSide(0xF2F2F7, 0xFFFFFF, 0x000000), ThemeSide(0x000000, 0x0E0E10, 0xFFFFFF)), DEFAULT_ACCENT),
        ThemePreset("contrast", "Contraste élevé", ThemeColors(ThemeSide(0xFFFFFF, 0xF0F0F2, 0x000000), ThemeSide(0x000000, 0x16161A, 0xFFFFFF)), 0xFF0A84FFL),
        ThemePreset("paper", "Papier", ThemeColors(ThemeSide(0xF4EFE6, 0xFFFBF4, 0x2B2620), ThemeSide(0x191613, 0x25211D, 0xF1EADD)), 0xFFC2562EL),
        ThemePreset("slate", "Ardoise", ThemeColors(ThemeSide(0xEDF0F4, 0xFFFFFF, 0x121822), ThemeSide(0x0E131A, 0x19202A, 0xE7ECF2)), 0xFF3D7BF7L),
        ThemePreset("forest", "Forêt", ThemeColors(ThemeSide(0xEDF1EB, 0xFFFFFF, 0x18261C), ThemeSide(0x0D1410, 0x17211A, 0xE4ECE5)), 0xFF2E9A5FL),
        ThemePreset("mist", "Brume", ThemeColors(ThemeSide(0xF0EEF5, 0xFFFFFF, 0x1D1A2A), ThemeSide(0x110F18, 0x1C1926, 0xEBE8F4)), 0xFF7458F0L)
    )

    fun byId(id: String): ThemePreset? = all.firstOrNull { it.id == id }

    /** The preset these colours and this accent are (null when the listener changed something). */
    fun matching(colors: ThemeColors?, accent: Long): ThemePreset? =
        all.firstOrNull { it.colors == colors && it.accent == accent } ?: all.firstOrNull { it.colors == colors }
}

/**
 * How a theme is stored (`themeColors`, « » for none) and shared. The stored form is six hex colours,
 * `bg,surface,text;bg,surface,text` (light, then dark). A shared code is `KT1.` and URL-safe base64 of: a version
 * byte (1), a flags byte (bit 0: colours follow), the accent's RGB, the light then dark side's colours, and a
 * checksum byte; about 32 characters with colours, 10 without. Nothing about the listener is in it.
 */
object ThemeCodec {
    const val PREFIX = "KT1."
    private const val VERSION = 1
    private val token = Regex("KT1\\.[A-Za-z0-9_-]{6,64}")
    private val stored = Regex("[0-9A-Fa-f]{6}(,[0-9A-Fa-f]{6}){2};[0-9A-Fa-f]{6}(,[0-9A-Fa-f]{6}){2}")

    data class Shared(val colors: ThemeColors?, val accent: Long)

    fun store(colors: ThemeColors?): String = colors?.let {
        fun side(s: ThemeSide) = listOf(s.background, s.surface, s.text).joinToString(",") { c -> "%06X".format(c and ThemeSide.RGB) }
        side(it.light) + ";" + side(it.dark)
    } ?: ""

    fun valid(text: String): Boolean = text.isEmpty() || stored.matches(text)

    fun parse(text: String?): ThemeColors? {
        if (text.isNullOrEmpty() || !stored.matches(text)) return null
        val (light, dark) = text.split(';').map { part -> part.split(',').map { it.toInt(16) } }
        return ThemeColors(ThemeSide(light[0], light[1], light[2]), ThemeSide(dark[0], dark[1], dark[2]))
    }

    fun share(colors: ThemeColors?, accent: Long): String {
        val bytes = ArrayList<Byte>()
        fun rgb(c: Int) { bytes += (c shr 16 and 0xFF).toByte(); bytes += (c shr 8 and 0xFF).toByte(); bytes += (c and 0xFF).toByte() }
        bytes += VERSION.toByte(); bytes += (if (colors != null) 1 else 0).toByte()
        rgb(accent.toInt())
        colors?.let { listOf(it.light, it.dark).forEach { s -> rgb(s.background); rgb(s.surface); rgb(s.text) } }
        bytes += checksum(bytes)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
    }

    /** The first theme code in [text] (a whole shared message may be pasted), checked; null when there is none or it is damaged. */
    fun find(text: String): Shared? {
        val code = token.find(text)?.value ?: return null
        val bytes = runCatching { Base64.getUrlDecoder().decode(code.removePrefix(PREFIX)) }.getOrNull() ?: return null
        if (bytes.size < 6 || bytes[0].toInt() != VERSION) return null
        val flags = bytes[1].toInt()
        if (flags !in 0..1) return null
        val expected = if (flags == 1) 24 else 6
        if (bytes.size != expected) return null
        val body = bytes.copyOfRange(0, bytes.size - 1).toList()
        if (checksum(body) != bytes.last()) return null
        fun rgb(at: Int) = (bytes[at].toInt() and 0xFF shl 16) or (bytes[at + 1].toInt() and 0xFF shl 8) or (bytes[at + 2].toInt() and 0xFF)
        val accent = 0xFF000000L or rgb(2).toLong()
        val colors = if (flags == 1) ThemeColors(ThemeSide(rgb(5), rgb(8), rgb(11)), ThemeSide(rgb(14), rgb(17), rgb(20))) else null
        return Shared(colors, accent)
    }

    /** CRC-8 (polynomial 0x07): a mistyped or cut code is refused rather than read as other colours. */
    internal fun checksum(bytes: List<Byte>): Byte {
        var crc = 0
        bytes.forEach { b ->
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) (crc shl 1 xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
        }
        return crc.toByte()
    }
}

/**
 * The contrast guard of a custom theme: text keeps WCAG AA (4.5:1) on the page and on its surfaces, and so do the
 * secondary text and the accent's links. What the listener picked is kept where it already reads; otherwise it is
 * moved just enough, and the editor says so ([report]).
 */
object ThemeGuard {
    const val TEXT = CoverLegibility.TEXT

    data class Report(val text: Float, val secondary: Float, val accent: Float, val textAdjusted: Boolean, val accentAdjusted: Boolean) {
        val adjusted: Boolean get() = textAdjusted || accentAdjusted
    }

    private fun c(rgb: Int) = Color(0xFF000000.toInt() or (rgb and ThemeSide.RGB))

    /** [text] where it reads on both [background] and [surface]; otherwise moved towards black or white just enough. */
    fun readableText(text: Color, background: Color, surface: Color): Color {
        fun ok(x: Color) = CoverLegibility.contrast(x, background) >= TEXT && CoverLegibility.contrast(x, surface) >= TEXT
        if (ok(text)) return text
        val towards = if (CoverLegibility.contrast(Color.White, background) + CoverLegibility.contrast(Color.White, surface) >=
            CoverLegibility.contrast(Color.Black, background) + CoverLegibility.contrast(Color.Black, surface)) Color.White else Color.Black
        for (step in 1..20) {
            val candidate = androidx.compose.ui.graphics.lerp(text, towards, step / 20f)
            if (ok(candidate)) return candidate
        }
        return towards
    }

    /** The secondary ink: the text at 70 %, made more opaque until it reads at AA on both grounds. */
    fun secondary(text: Color, background: Color, surface: Color): Color {
        var alpha = .70f
        while (alpha < 1f) {
            val candidate = text.copy(alpha = alpha)
            if (CoverLegibility.contrast(candidate, background) >= TEXT && CoverLegibility.contrast(candidate, surface) >= TEXT) return candidate
            alpha += .05f
        }
        return text
    }

    fun report(side: ThemeSide, accent: Long): Report {
        val bg = c(side.background); val surface = c(side.surface); val raw = c(side.text)
        val text = readableText(raw, bg, surface)
        val secondary = secondary(text, bg, surface)
        val rawAccent = Color(accent.toInt()).copy(alpha = 1f)
        val readable = CoverLegibility.readableAccent(rawAccent, bg, surface)
        fun worst(x: Color) = minOf(CoverLegibility.contrast(x, bg), CoverLegibility.contrast(x, surface))
        return Report(worst(raw), worst(secondary), worst(rawAccent), text != raw, readable != rawAccent)
    }

    /** The app's theme for [side], with the guard applied; [dark] is the appearance asked for. */
    fun theme(side: ThemeSide, dark: Boolean, accent: Color): KetTheme {
        val bg = c(side.background); val surface = c(side.surface)
        val text = readableText(c(side.text), bg, surface)
        // A « dark » side the listener made light is drawn as light (glass, bars and icons follow the page).
        val isDark = if (dark) bg.luminance() < .4f else bg.luminance() < .18f
        return KetTheme(isDark = isDark, background = bg, onBackground = text, secondary = secondary(text, bg, surface),
            card = surface, hairline = text.copy(alpha = if (isDark) .17f else .12f),
            accent = CoverLegibility.readableAccent(accent.copy(alpha = 1f), bg, surface))
    }
}

/** Hue (0..360), saturation and value (0..1) of a picked colour, for the colour picker. */
data class Hsv(val h: Float, val s: Float, val v: Float) {
    fun toRgb(): Int {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val c = v * s
        val x = c * (1f - kotlin.math.abs(hh % 2f - 1f))
        val (r, g, b) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        val m = v - c
        fun ch(f: Float) = kotlin.math.round(((f + m).coerceIn(0f, 1f)) * 255f).toInt()
        return (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    companion object {
        fun of(rgb: Int): Hsv {
            val r = (rgb shr 16 and 0xFF) / 255f; val g = (rgb shr 8 and 0xFF) / 255f; val b = (rgb and 0xFF) / 255f
            val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
            val h = when {
                d == 0f -> 0f
                max == r -> 60f * (((g - b) / d) % 6f)
                max == g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }.let { if (it < 0f) it + 360f else it }
            return Hsv(h, if (max == 0f) 0f else d / max, max)
        }

        /** « #0A84FF », « 0a84ff » or « #fff » as RGB, or null. */
        fun parseHex(text: String): Int? {
            val clean = text.trim().removePrefix("#")
            return when {
                clean.matches(Regex("[0-9A-Fa-f]{6}")) -> clean.toInt(16)
                clean.matches(Regex("[0-9A-Fa-f]{3}")) -> clean.map { "$it$it" }.joinToString("").toInt(16)
                else -> null
            }
        }
    }
}
