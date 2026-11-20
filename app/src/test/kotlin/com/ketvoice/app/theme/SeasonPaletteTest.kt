package com.ketvoice.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.ketvoice.app.data.Seasons
import com.ketvoice.app.ui.seasons.SeasonLooks
import org.junit.Assert.*
import org.junit.Test

/**
 * Every seasonal theme (6.2) keeps its texts at WCAG AA:
 * on the pages and cards, over the brightest breath of the glow, its accent as a link and a tinted capsule's label,
 * and the dark label of a filled button on every colour of its gradient.
 */
class SeasonPaletteTest {
    private val aa = CoverLegibility.TEXT

    @Test fun everySeasonOfTheRegistryHasALook() {
        Seasons.all.forEach { assertNotNull(it.id, SeasonLooks.of(it.id)) }
        // 7.6: the Vice City edition is gone from the registry and has no look.
        assertNull(SeasonLooks.of("vicecity"))
        assertNull(SeasonLooks.of(null))
        assertNull(SeasonLooks.of("nimporte"))
    }

    @Test fun textsReadOnThePagesCardsAndGlow() {
        SeasonLooks.all.forEach { look ->
            val theme = look.theme()
            assertTrue(look.id, theme.isDark)
            assertSame(look, theme.season)
            val glow = look.palette.core.copy(alpha = .46f).compositeOver(theme.background)
            for (surface in listOf(theme.background, theme.card, glow)) {
                assertTrue("${look.id} text", CoverLegibility.contrast(theme.onBackground, surface) >= aa)
                assertTrue("${look.id} secondary", CoverLegibility.contrast(theme.secondary, surface) >= aa)
            }
            assertTrue("${look.id} accent over the glow", CoverLegibility.contrast(theme.accent, glow) >= 3f)
        }
    }

    @Test fun theAccentNeedsNoCorrection() {
        SeasonLooks.all.forEach { look ->
            val theme = look.theme()
            assertEquals(look.id, theme.accent, CoverLegibility.readableAccent(theme.accent, theme.background, theme.card))
            assertEquals(look.id, Color.Black, CoverLegibility.onFill(theme.accent))
        }
    }

    @Test fun theFilledButtonLabelReadsOnEveryColourOfIt() {
        SeasonLooks.all.forEach { look ->
            look.palette.fill.forEach { stop ->
                assertTrue("${look.id} $stop", CoverLegibility.contrast(look.palette.ink, stop) >= aa)
                assertTrue("${look.id} $stop", CoverLegibility.contrast(look.palette.ink, Color.White.copy(alpha = .22f).compositeOver(stop)) >= aa)
            }
        }
    }

    @Test fun eachSeasonLooksItsOwn() {
        val accents = SeasonLooks.all.map { it.palette.accent }
        assertEquals(accents.size, accents.toSet().size)
        val nights = SeasonLooks.all.map { it.palette.night }
        assertEquals(nights.size, nights.toSet().size)
    }
}
