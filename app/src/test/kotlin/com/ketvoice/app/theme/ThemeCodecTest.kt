package com.ketvoice.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.*
import org.junit.Test

/** « Thèmes complets » (2026-11-22): the stored form, the `KT1.` share codes, the presets, the contrast guard and the picker's maths. */
class ThemeCodecTest {
    private val paper = ThemePresets.byId("paper")!!.colors!!

    @Test fun storedThemesRoundTripAndAnythingElseIsRefused() {
        val stored = ThemeCodec.store(paper)
        assertEquals("F4EFE6,FFFBF4,2B2620;191613,25211D,F1EADD", stored)
        assertEquals(paper, ThemeCodec.parse(stored))
        assertEquals("", ThemeCodec.store(null))
        assertNull(ThemeCodec.parse(""))
        assertTrue(ThemeCodec.valid(""))
        for (bad in listOf("F4EFE6,FFFBF4;191613,25211D,F1EADD", "GGGGGG,FFFBF4,2B2620;191613,25211D,F1EADD", "F4EFE6,FFFBF4,2B2620;191613,25211D,F1EADD;000000",
                "#F4EFE6,FFFBF4,2B2620;191613,25211D,F1EADD", "<script>", " F4EFE6,FFFBF4,2B2620;191613,25211D,F1EADD"))
            { assertFalse(bad, ThemeCodec.valid(bad)); assertNull(bad, ThemeCodec.parse(bad)) }
    }

    @Test fun shareCodesCarryTheColoursAndTheAccent() {
        val code = ThemeCodec.share(paper, 0xFFC2562EL)
        assertTrue(code, code.startsWith("KT1.") && code.length <= 40)
        assertEquals(ThemeCodec.Shared(paper, 0xFFC2562EL), ThemeCodec.find(code))
        // A whole shared message may be pasted: the code is found in it.
        assertEquals(ThemeCodec.Shared(paper, 0xFFC2562EL), ThemeCodec.find("Mon thème Ketvoice :\n$code\nÀ plus !"))
        // The default colours travel with an accent alone, in a shorter code.
        val accentOnly = ThemeCodec.share(null, 0xFF0A84FFL)
        assertTrue(accentOnly.length < code.length)
        assertEquals(ThemeCodec.Shared(null, 0xFF0A84FFL), ThemeCodec.find(accentOnly))
    }

    @Test fun damagedOrForeignCodesAreRefused() {
        val code = ThemeCodec.share(paper, 0xFFC2562EL)
        val body = code.removePrefix("KT1.")
        // One character changed anywhere: the checksum (or the decoding) refuses it.
        for (i in body.indices) {
            val swapped = body.substring(0, i) + (if (body[i] == 'A') 'B' else 'A') + body.substring(i + 1)
            assertNotEquals("changed at $i", ThemeCodec.Shared(paper, 0xFFC2562EL), ThemeCodec.find("KT1.$swapped"))
        }
        assertNull("cut", ThemeCodec.find(code.dropLast(4)))
        assertNull("no prefix", ThemeCodec.find(body))
        assertNull("a Ketmix code", ThemeCodec.find("KM1.eyJ2IjoxfQ"))
        assertNull("other version", ThemeCodec.find("KT1." + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(byteArrayOf(2, 0, 1, 2, 3, ThemeCodec.checksum(listOf<Byte>(2, 0, 1, 2, 3))))))
        assertNull("not base64", ThemeCodec.find("KT1.!!!!!!!!"))
        assertNull("empty", ThemeCodec.find(""))
    }

    @Test fun presetsAreDistinctCalmAndReadableWithoutAdjustment() {
        assertEquals(ThemePresets.all.size, ThemePresets.all.map { it.id }.toSet().size)
        assertNull("Ketvoice is the default, exactly", ThemePresets.byId("ketvoice")!!.colors)
        assertEquals(ThemePresets.DEFAULT_ACCENT, ThemePresets.byId("ketvoice")!!.accent)
        ThemePresets.all.mapNotNull { p -> p.colors?.let { p to it } }.forEach { (preset, colors) ->
            for (dark in listOf(false, true)) {
                val report = ThemeGuard.report(colors.side(dark), preset.accent)
                assertFalse("${preset.id} dark=$dark text ${report.text}", report.textAdjusted)
                assertTrue("${preset.id} dark=$dark secondary ${report.secondary}", report.secondary >= 4.5f)
                assertTrue("${preset.id} dark=$dark", ThemeCodec.valid(ThemeCodec.store(colors)))
                // No neon: the page and surfaces are muted (low saturation), never a saturated ground.
                val bg = Hsv.of(colors.side(dark).background)
                assertTrue("${preset.id} background chroma ${bg.s * bg.v}", bg.s * bg.v <= .08f)
            }
            assertEquals(preset, ThemePresets.matching(colors, preset.accent))
        }
    }

    @Test fun theGuardKeepsTextAtAaAndSaysSo() {
        // Grey text on a grey page: moved just enough to read at 4.5:1 on the page and its surfaces.
        val murky = ThemeSide(0x777777, 0x8A8A8A, 0x999999)
        val report = ThemeGuard.report(murky, 0xFF8E8E93L)
        assertTrue(report.textAdjusted); assertTrue(report.adjusted)
        assertTrue(report.text < 4.5f)
        val theme = ThemeGuard.theme(murky, dark = true, accent = Color(0xFF8E8E93))
        for (ground in listOf(theme.background, theme.card)) {
            assertTrue(CoverLegibility.contrast(theme.onBackground, ground) >= 4.5f)
            assertTrue(CoverLegibility.contrast(theme.secondary, ground) >= 4.5f)
            assertTrue(CoverLegibility.contrast(theme.accent, ground) >= 4.5f)
        }
        // Text that already reads is kept as picked.
        val fine = ThemeSide(0x101418, 0x1A2028, 0xE8ECF0)
        assertFalse(ThemeGuard.report(fine, 0xFF3D7BF7L).textAdjusted)
        assertEquals(Color(0xFFE8ECF0), ThemeGuard.theme(fine, dark = true, accent = Color(0xFF3D7BF7)).onBackground)
        // A « dark » side made light is drawn as light (glass and bars follow the page).
        assertFalse(ThemeGuard.theme(ThemeSide(0xF4EFE6, 0xFFFFFF, 0x222222), dark = true, accent = Color.Red).isDark)
    }

    @Test fun withoutACustomThemeTheDefaultIsKet27Exactly() {
        for (dark in listOf(false, true)) for (accent in listOf(Color(0xFFFF2D55), Color(0xFF34C759)))
            assertEquals(Ket27.theme(dark, accent), listenerTheme(null, dark, accent))
    }

    @Test fun thePickersColourMaths() {
        for (rgb in listOf(0x000000, 0xFFFFFF, 0xFF2D55, 0x0A84FF, 0x34C759, 0x7458F0, 0x123456, 0xF4EFE6))
            assertEquals("#%06X".format(rgb), rgb, Hsv.of(rgb).toRgb())
        assertEquals(0x0A84FF, Hsv.parseHex("#0a84ff"))
        assertEquals(0xFFFFFF, Hsv.parseHex("fff"))
        assertNull(Hsv.parseHex("#12345"))
        assertNull(Hsv.parseHex("bleu"))
    }

    @Test fun sungWordsStayReadableOnTheDarkLyricsGround() {
        val ground = Color(0xFF26262A)
        assertEquals(Color.White, LyricsLook.sung(LyricsLook.SUNG_WHITE, Color.Red, null, 0))
        assertEquals("no cover yet: white", Color.White, LyricsLook.sung(LyricsLook.SUNG_COVER, Color.Red, null, 0))
        for (c in listOf(Color(0xFF000080), Color(0xFF3A1010), Color(0xFFFF2D55), Color(0xFF0A84FF))) {
            assertTrue(CoverLegibility.contrast(LyricsLook.sung(LyricsLook.SUNG_ACCENT, c, null, 0), ground) >= 4.5f)
            assertTrue(CoverLegibility.contrast(LyricsLook.sung(LyricsLook.SUNG_COVER, Color.White, c, 0), ground) >= 4.5f)
            assertTrue(CoverLegibility.contrast(LyricsLook.sung(LyricsLook.SUNG_CUSTOM, Color.White, null, c.toArgb().toLong() and 0xFFFFFFFFL), ground) >= 4.5f)
        }
        // A colour that already reads is kept.
        assertEquals(Color(0xFFFFCC00), LyricsLook.sung(LyricsLook.SUNG_CUSTOM, Color.White, null, 0xFFFFCC00L))
    }
}
