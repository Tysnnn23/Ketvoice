package com.ketvoice.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.ketvoice.app.ui.vicecity.WELCOME_BODY_ALPHA
import com.ketvoice.app.ui.vicecity.WELCOME_NOTE_ALPHA
import com.ketvoice.app.ui.vicecity.WELCOME_VEIL
import org.junit.Assert.*
import org.junit.Test

/** The Vice City theme (6.1) keeps every text at WCAG AA, with the same checks as the cover colours ([CoverLegibility]). */
class ViceCityThemeTest {
    private val theme = ViceCityTheme.theme()
    private val aa = CoverLegibility.TEXT

    @Test fun textsReadOnThePagesAndCards() {
        assertTrue(theme.isDark && theme.viceCity)
        for (surface in listOf(theme.background, theme.card)) {
            assertTrue(CoverLegibility.contrast(theme.onBackground, surface) >= aa)
            assertTrue("secondary text", CoverLegibility.contrast(theme.secondary, surface) >= aa)
        }
    }

    @Test fun textsReadOnTheBrightestOfTheGlow() {
        // The top glow's core at its strongest breath (magenta at .46 over the night).
        val glow = ViceCityTheme.Colors.magenta.copy(alpha = .46f).compositeOver(theme.background)
        assertTrue(CoverLegibility.contrast(theme.onBackground, glow) >= aa)
        assertTrue(CoverLegibility.contrast(theme.secondary, glow) >= aa)
        assertTrue(CoverLegibility.contrast(theme.accent, glow) >= 3f) // large titles and icons (WCAG AA large)
    }

    @Test fun theAccentNeedsNoCorrection() {
        // Already readable as a link, in a capsule tinted with itself, and its own button label (black) reads on it.
        assertEquals(theme.accent, CoverLegibility.readableAccent(theme.accent, theme.background, theme.card))
        assertEquals(Color.Black, CoverLegibility.onFill(theme.accent))
        assertTrue(CoverLegibility.contrast(Color.Black, theme.accent) >= aa)
    }

    @Test fun theSunsetButtonLabelReadsOnEveryColourOfIt() {
        for (stop in listOf(ViceCityTheme.Colors.magenta, ViceCityTheme.Colors.accent, ViceCityTheme.Colors.orange)) {
            assertTrue("$stop", CoverLegibility.contrast(ViceCityTheme.Colors.ink, stop) >= aa)
            // With the light sheen of the top half of the button.
            assertTrue("$stop", CoverLegibility.contrast(ViceCityTheme.Colors.ink, Color.White.copy(alpha = .22f).compositeOver(stop)) >= aa)
        }
    }

    /**
     * The welcome panel over the brightest part of the owner's picture: its sun, blurred (measured on the picture
     * blurred by 24 px: #FDD981). The panel's veil keeps the title, the line and the small print at AA.
     */
    @Test fun theWelcomePanelReadsEvenOverTheSun() {
        val sun = Color(0xFFFDD981)
        val panel = ViceCityTheme.Colors.night.copy(alpha = WELCOME_VEIL).compositeOver(sun)
        assertTrue(CoverLegibility.contrast(Color.White, panel) >= aa)
        assertTrue(CoverLegibility.contrast(Color.White.copy(alpha = WELCOME_BODY_ALPHA), panel) >= aa)
        assertTrue(CoverLegibility.contrast(Color.White.copy(alpha = WELCOME_NOTE_ALPHA), panel) >= aa)
        assertTrue(CoverLegibility.contrast(Color.White.copy(alpha = .92f), panel) >= aa)
    }

    @Test fun theGlowMovesSlowlyAndLessInThePerformanceMode() {
        assertTrue(ViceCityTheme.stepMs(performance = true) > ViceCityTheme.stepMs(performance = false))
        assertTrue(ViceCityTheme.CYCLE_MS >= 10_000L)
    }
}
