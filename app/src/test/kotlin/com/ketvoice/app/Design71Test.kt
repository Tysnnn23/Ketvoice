package com.ketvoice.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.Track
import com.ketvoice.app.glass.GlassClarity
import com.ketvoice.app.glass.GlassRole
import com.ketvoice.app.glass.HighContrastText
import com.ketvoice.app.player.NoRepeatWindow
import com.ketvoice.app.player.RadioMemory
import com.ketvoice.app.player.RadioMix
import com.ketvoice.app.player.RadioSeed
import com.ketvoice.app.theme.Contrast
import com.ketvoice.app.theme.CoverLook
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.ui.common.IndexLetters
import com.ketvoice.app.widget.WidgetSizing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 7.1 design picks: tinted glass, opaque menus, A–Z index, Noir, the 4×1 widget and the radio without repeats. */
class Design71Test {
    @After fun reset() {
        AppSettings.glassTinted.value = false
        AppSettings.trueBlack.value = false
        HighContrastText.on.value = false
    }

    // ---- Liquid Glass

    @Test fun tintedIsClearlyMoreOpaqueAndHighContrastTextTurnsItOn() {
        assertFalse(GlassClarity.tinted)
        HighContrastText.on.value = true
        assertTrue(GlassClarity.tinted)
        HighContrastText.on.value = false
        AppSettings.glassTinted.value = true
        assertTrue(GlassClarity.tinted)
        for (level in GlassClarity.MIN..GlassClarity.MAX) {
            // Clear keeps the navigation as it was; Tinted adds a body of milk well over any level of Clear.
            assertEquals(0f, GlassClarity.body(GlassRole.Control, false, level), 0f)
            assertTrue(GlassClarity.body(GlassRole.Control, true, level) >= GlassClarity.TINTED_BODY)
            assertTrue(GlassClarity.frostFor(level, true) >= GlassClarity.TINTED_FROST)
            assertEquals(0f, GlassClarity.clarityFor(level, true), 0f)
            // The old values keep their meaning in Clear.
            assertEquals(GlassClarity.frost(level), GlassClarity.frostFor(level, false), 0f)
        }
    }

    @Test fun menusAndSheetsAreMoreOpaqueThanTheTabBarAndReadOverAnyArtwork() {
        for (tinted in listOf(false, true)) for (level in GlassClarity.MIN..GlassClarity.MAX) {
            val sheet = GlassClarity.body(GlassRole.Sheet, tinted, level)
            // The tab bar's milk never exceeds .6 in the shader (Clear) or its tinted body.
            assertTrue(sheet > maxOf(.6f, GlassClarity.body(GlassRole.Control, tinted, level)) + .1f)
            for (dark in listOf(false, true)) {
                val milk = if (dark) Color(.24f, .24f, .27f) else Color.White
                val ink = Ket27.theme(dark, Color.Red).onBackground
                // Worst backdrops: pure white under a dark sheet, pure black under a light one (before the legibility veil).
                for (backdrop in listOf(Color.White, Color.Black, Color(0xFFFFE9A8), Color(0xFF1B1B5E))) {
                    val surface = lerp(backdrop, milk, sheet)
                    assertTrue("dark=$dark tinted=$tinted on $backdrop", Contrast.contrastRatio(ink, surface) >= 4.5f)
                }
            }
            // The fallback veil (below Android 13, performance mode) keeps the same floor.
            assertTrue(GlassClarity.fallbackVeil(true, level, tinted, GlassRole.Sheet) >= sheet)
        }
    }

    // ---- Noir

    @Test fun noirKeepsPagesPureBlackUnderACoverAndCardsApart() {
        val chosen = Ket27.theme(true, Color(0xFFFF2D55))
        val look = CoverLook(tone = Color(0xFFE8D25A), toneAmount = 1f)
        val black = look.apply(chosen.copy(background = Color.Black), black = true)
        assertEquals(Color.Black, black.background)
        assertTrue(Contrast.contrastRatio(black.card, black.background) > 1.15f)
        assertTrue(black.card.luminance() <= .034f)
        // Without Noir the page takes the cover, held dim (unchanged).
        val toned = look.apply(chosen)
        assertTrue(toned.background != Color.Black && toned.background.luminance() <= .034f)
        // Light pages are not affected.
        val light = Ket27.theme(false, Color.Red)
        assertEquals(look.apply(light), look.apply(light, black = true))
    }

    // ---- A–Z index

    @Test fun indexLettersHandleAccentsAndOtherScripts() {
        assertEquals("E", IndexLetters.of("Édith Piaf"))
        assertEquals("E", IndexLetters.of("  «éclat»"))
        assertEquals("O", IndexLetters.of("Ølafur Arnalds"))
        assertEquals("O", IndexLetters.of("Œuvre"))
        assertEquals("A", IndexLetters.of("Ærø"))
        assertEquals("Z", IndexLetters.of("zaz"))
        listOf("Кино", "宇多田ヒカル", "아이유", "Ελλάδα", "शान", "2Pac", "", "!!!").forEach {
            assertEquals(it, IndexLetters.OTHER, IndexLetters.of(it))
        }
        val names = listOf("2Pac", "Aya", "Ángel", "Кино", "Béa", "Zoé")
        val grouped = IndexLetters.grouped(names) { it }
        assertEquals(listOf("Aya", "Ángel", "Béa", "Zoé", "2Pac", "Кино"), grouped)
        assertEquals(listOf("A" to 0, "B" to 2, "Z" to 3, "#" to 4), IndexLetters.firsts(grouped) { it })
        assertFalse(IndexLetters.shown(IndexLetters.MIN_ITEMS - 1, 10))
        assertFalse(IndexLetters.shown(500, 2))
        assertTrue(IndexLetters.shown(IndexLetters.MIN_ITEMS, 3))
        assertEquals(0, IndexLetters.at(-5f, 270f, 27))
        assertEquals(26, IndexLetters.at(900f, 270f, 27))
        assertEquals(12, IndexLetters.at(125f, 270f, 27))
    }

    // ---- 4×1 widget

    @Test fun controlsWidgetKeepsTheTransportAtEveryWidth() {
        for (width in listOf(100f, 110f, 179f, 180f, 250f, 400f)) {
            val parts = WidgetSizing.controlsParts(width)
            // Previous, play/pause and next always fit (the narrowest a launcher may resize it to is 110 dp).
            val transport = parts.skipDp * 2 + parts.playDp + (if (parts.cover) 16 + 48 else 8)
            assertTrue("$width", transport <= maxOf(width, 110f))
            assertTrue(parts.skipDp >= 30 && parts.playDp >= 34)
        }
        assertFalse(WidgetSizing.controlsParts(100f).labels)
        assertTrue(WidgetSizing.controlsParts(250f).labels)
        assertEquals(WidgetSizing.controls, setOf(WidgetSizing.STRIP_NARROW, WidgetSizing.STRIP, WidgetSizing.STRIP_FULL))
    }

    // ---- Radio without repeats

    private fun t(id: String, artist: String = "Artist $id") = Track(id, "Title $id", artist, "")
    private val hour = 60 * 60 * 1000L

    @Test fun heardTimesAreKeptByIdAndRecordingForADay() {
        val now = 100 * hour
        val first = ListeningHistory.heardWith(emptyMap(), t("a"), now - 30 * hour)
        val next = ListeningHistory.heardWith(first, t("b"), now)
        assertNull(next["a"])
        assertEquals(now, next["b"])
        // The same song under another id is recognised by its recording.
        val other = Track("yt:other", "Title b", "Artist b", "")
        assertEquals(now, NoRepeatWindow.recent(next, now + hour)(other))
    }

    @Test fun autoplayLeavesOutTitlesHeardInTheLastHoursUnlessTooFewAreLeft() {
        val now = 50 * hour
        val heard = mapOf("a" to now - hour, "b" to now - 3 * hour, "c" to now - 5 * hour)
        val recent = NoRepeatWindow.recent(heard, now)
        assertEquals(now - hour, recent(t("a")))
        assertNull(recent(t("c")))
        val pool = listOf("a", "b", "c", "d", "e", "f", "g", "h").map { t(it) }
        // Enough fresh titles: none heard lately.
        val picked = NoRepeatWindow.pick(pool, recent) { it.take(10) }
        assertEquals(listOf("c", "d", "e", "f", "g", "h"), picked.map { it.id })
        // Too few: the fresh ones first, then those heard longest ago.
        val small = NoRepeatWindow.pick(listOf("a", "b", "c").map { t(it) }, recent) { it.take(10) }
        assertEquals(listOf("c", "b", "a"), small.map { it.id })
    }

    @Test fun radioNeverReplaysARecentTitleWhileTheCircleHasOthers() {
        val now = 50 * hour
        val fresh = (1..30).map { t("f$it", "Artist ${it % 10}") }
        val heard = fresh.take(10).associate { it.id to now - hour }
        val memory = RadioMemory(RadioSeed.Artist("Seed", null))
        repeat(2) {
            val batch = RadioMix.pick(fresh, emptyList(), emptyList(), memory, { true }, kotlin.random.Random(it),
                lately = NoRepeatWindow.recent(heard, now))
            assertTrue(batch.isNotEmpty())
            assertTrue(batch.none { it.id in heard })
        }
        // A circle of only recent titles still plays on, those heard longest ago first.
        val tiny = listOf(t("x"), t("y"), t("z"))
        val lately = NoRepeatWindow.recent(mapOf("x" to now - hour, "y" to now - 3 * hour, "z" to now - 2 * hour), now)
        val fallback = RadioMix.pick(tiny, emptyList(), emptyList(), RadioMemory(RadioSeed.Artist("Seed", null)), { true },
            kotlin.random.Random(1), lately = lately)
        assertEquals(listOf("y", "z", "x"), fallback.map { it.id })
    }
}
