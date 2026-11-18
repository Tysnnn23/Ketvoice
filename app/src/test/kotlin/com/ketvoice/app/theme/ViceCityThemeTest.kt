package com.ketvoice.app.theme

import androidx.compose.ui.graphics.Color
import com.ketvoice.app.ui.seasons.SeasonLooks
import org.junit.Assert.*
import org.junit.Test

/**
 * The Vice City theme: the listener's light or dark Ket 27 theme with a flamingo accent that keeps every use of it at
 * WCAG AA ([CoverLegibility]), and none of the seasonal night, glows or edges.
 */
class ViceCityThemeTest {
    private val aa = CoverLegibility.TEXT

    @Test fun itKeepsTheListenersLightOrDarkTheme() {
        for (dark in listOf(false, true)) {
            val vice = ViceCityTheme.theme(dark)
            val plain = Ket27.theme(dark, Color.Blue)
            assertTrue(vice.viceCity)
            assertEquals(dark, vice.isDark)
            assertNull("no seasonal night, glow or edge", vice.season)
            assertEquals(plain.copy(accent = vice.accent, viceCity = true), vice)
        }
        assertNull("Vice City is not a seasonal look any more", SeasonLooks.of(ViceCityTheme.ID))
    }

    @Test fun theAccentReadsAsItIsOnBothThemes() {
        for (dark in listOf(false, true)) {
            val theme = ViceCityTheme.theme(dark)
            assertEquals("no correction needed", if (dark) ViceCityTheme.accentDark else ViceCityTheme.accentLight, theme.accent)
            for (surface in listOf(theme.background, theme.card)) {
                assertTrue("link on $surface", CoverLegibility.contrast(theme.accent, surface) >= aa)
                assertTrue("tinted capsule on $surface",
                    CoverLegibility.contrast(theme.accent, CoverLegibility.tinted(theme.accent, surface)) >= aa)
            }
            // « Profiter » and the filled buttons: their label reads on the accent.
            assertTrue(CoverLegibility.contrast(CoverLegibility.onFill(theme.accent), theme.accent) >= aa)
        }
    }

    @Test fun theIllustrationIsFlatPastelWithADarkerPalm() {
        for (scene in listOf(ViceCityTheme.day, ViceCityTheme.dusk)) {
            // The palms stand out against the sun they cross and the sky they rise into.
            assertTrue(CoverLegibility.contrast(scene.palm, scene.sun) >= 3f)
            assertTrue(CoverLegibility.contrast(scene.palm, scene.skyLow) >= 3f)
            assertTrue("the crowns, in the high sky", CoverLegibility.contrast(scene.palm, scene.skyHigh) >= 1.8f)
            listOf(scene.skyHigh, scene.skyLow, scene.sun, scene.sea, scene.glint, scene.palm).forEach { assertEquals(1f, it.alpha) }
        }
        assertSame(ViceCityTheme.day, ViceCityTheme.scene(dark = false))
        assertSame(ViceCityTheme.dusk, ViceCityTheme.scene(dark = true))
    }
}
