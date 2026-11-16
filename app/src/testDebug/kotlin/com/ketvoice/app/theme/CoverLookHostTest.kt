package com.ketvoice.app.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Legibility first: whatever the cover, in the light and in the dark theme, pages, cards, links,
 * filled buttons and secondary text tinted by it keep WCAG contrast. Computed, not eyeballed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoverLookHostTest {
    private val chosenAccent = Color(0xFFFF2D55)

    /** Covers across the whole colour space: every 15° of hue, greys included, near black to near white. */
    private fun covers(): List<Color> = buildList {
        for (h in 0 until 360 step 15) for (s in listOf(0f, .35f, .7f, 1f)) for (l in listOf(.04f, .18f, .35f, .5f, .65f, .8f, .96f)) {
            add(CoverLegibility.Hsl(h.toFloat(), s, l).toColor())
        }
        // Real cover-like extremes.
        addAll(listOf(Color(0xFFFFFF00), Color(0xFF00FFFF), Color(0xFFFFD60A), Color(0xFF0A0A0A), Color(0xFFFAFAFA), Color(0xFF7F7F7F)))
    }

    private fun assertReadable(theme: KetTheme, what: String) {
        fun check(label: String, fg: Color, bg: Color, min: Float) {
            val ratio = CoverLegibility.contrast(fg, bg)
            assertTrue("$what: $label ${"%.2f".format(ratio)} < $min", ratio >= min - 1e-3f)
        }
        check("text on page", theme.onBackground, theme.background, 7f)
        check("text on card", theme.onBackground, theme.card, 7f)
        check("secondary on page", theme.secondary, theme.background, CoverLegibility.TEXT)
        check("secondary on card", theme.secondary, theme.card, CoverLegibility.TEXT)
        check("accent on page", theme.accent, theme.background, CoverLegibility.TEXT)
        check("accent on card", theme.accent, theme.card, CoverLegibility.TEXT)
        check("label on accent", CoverLegibility.onFill(theme.accent), theme.accent, CoverLegibility.TEXT)
        check("accent in a tinted capsule on the page", theme.accent, CoverLegibility.tinted(theme.accent, theme.background), CoverLegibility.TEXT)
        check("accent in a tinted capsule on a card", theme.accent, CoverLegibility.tinted(theme.accent, theme.card), CoverLegibility.TEXT)
    }

    @Test fun everyCoverKeepsTextReadableInBothThemes() {
        var checked = 0
        for (dark in listOf(false, true)) {
            val chosen = Ket27.theme(dark, chosenAccent)
            for (cover in covers()) {
                for (amount in listOf(.35f, .7f, 1f)) {
                    val theme = CoverLook(cover, amount, cover, amount).apply(chosen)
                    assertReadable(theme, "${if (dark) "dark" else "light"} cover=${cover.toHex()} amount=$amount")
                    assertEquals("the theme keeps its brightness", dark, theme.isDark)
                    checked++
                }
            }
        }
        assertTrue(checked > 1000)
    }

    @Test fun eachPartFollowsOnlyItsOwnSetting() {
        val chosen = Ket27.theme(false, chosenAccent)
        val red = Color(0xFFE53935)
        // Nothing asked: exactly the theme the listener chose (their accent untouched, even if it were pale).
        assertEquals(chosen, CoverLook().apply(chosen))
        assertEquals(chosen, CoverLook(red, 0f, red, 0f).apply(chosen))
        // Pages only: the accent stays the chosen one.
        val pages = CoverLook(tone = red, toneAmount = 1f).apply(chosen)
        assertNotEquals(chosen.background, pages.background)
        assertEquals(chosen.accent, pages.accent)
        // Accent only: the page stays the theme's.
        val accent = CoverLook(accent = red, accentAmount = 1f).apply(chosen)
        assertEquals(chosen.background, accent.background)
        assertNotEquals(chosen.accent, accent.accent)
    }

    @Test fun aReadableAccentIsKeptAsItIsAndAPaleOneOnlyDarkensAsMuchAsNeeded() {
        val light = Ket27.theme(false, chosenAccent)
        val blue = Color(0xFF0A4FD6)
        assertEquals(blue, CoverLegibility.readableAccent(blue, light.background, light.card))
        val yellow = Color(0xFFFFE14D)
        val fixed = CoverLegibility.readableAccent(yellow, light.background, light.card)
        val hsl = CoverLegibility.Hsl.of(fixed); val original = CoverLegibility.Hsl.of(yellow)
        assertEquals("same hue", original.h, hsl.h, 2f)
        assertTrue("darker", hsl.l < original.l)
        assertTrue(CoverLegibility.contrast(fixed, light.background) >= CoverLegibility.TEXT)
        // One step lighter would not have been enough: not darker than needed.
        val lighter = hsl.copy(l = hsl.l + .02f).toColor()
        assertTrue(listOf(light.background, light.card, CoverLegibility.tinted(lighter, light.background),
            CoverLegibility.tinted(lighter, light.card)).any { CoverLegibility.contrast(lighter, it) < CoverLegibility.TEXT })
        // Dark theme: a deep navy cover is lifted, not darkened.
        val dark = Ket27.theme(true, chosenAccent)
        val navy = Color(0xFF101A40)
        assertTrue(CoverLegibility.Hsl.of(CoverLegibility.readableAccent(navy, dark.background, dark.card)).l > CoverLegibility.Hsl.of(navy).l)
    }

    @Test fun hslRoundTrips() {
        for (c in covers()) {
            val back = CoverLegibility.Hsl.of(c).toColor()
            assertEquals(c.red, back.red, .01f); assertEquals(c.green, back.green, .01f); assertEquals(c.blue, back.blue, .01f)
        }
    }

    private fun Color.toHex() = "#%08X".format(android.graphics.Color.argb(alpha, red, green, blue))
}
