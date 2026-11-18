package com.ketvoice.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.ViceFm
import com.ketvoice.app.ui.player.LyricsWords
import com.ketvoice.app.ui.vicecity.NeonFlicker
import com.ketvoice.app.ui.vicecity.NeonFloor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * The Vice City theme's 80s night (6.3): every new colour at WCAG AA where it is read, the floor still in the
 * performance mode and slow otherwise, the KETVOICE flicker short and ending lit, a held note's neon glow, and the
 * Vice FM mix.
 */
class ViceCityNightTest {
    private val aa = CoverLegibility.TEXT
    private val night = ViceCityTheme.Colors.night
    private val card = ViceCityTheme.Colors.card
    /** The top glow's core at its strongest breath, where the large titles and the KETVOICE sign sit. */
    private val glow = ViceCityTheme.Colors.magenta.copy(alpha = .46f).compositeOver(night)

    @Test fun theChromeOfTheTitlesReadsEvenOnTheGlow() {
        for ((_, stop) in ViceCityTheme.chromeStops) for (ground in listOf(night, card, glow)) {
            assertTrue("$stop on $ground", CoverLegibility.contrast(stop, ground) >= aa)
        }
        assertEquals("white at the top of each line", Color.White, ViceCityTheme.chromeStops.first().second)
    }

    @Test fun theKetvoiceSignReadsOnTheGlow() {
        assertTrue(CoverLegibility.contrast(ViceCityTheme.Colors.chromePink, glow) >= aa)
    }

    @Test fun thePlayersTimesReadOnTheDarkenedCover() {
        // The player darkens every cover (black veils); the times sit low, where even a white cover is no lighter than this.
        val ground = Color(0xFF2E2E2E)
        assertTrue(CoverLegibility.contrast(ViceCityTheme.elapsedTime, ground) >= aa)
        assertTrue(CoverLegibility.contrast(ViceCityTheme.remainingTime, ground) >= aa)
        // At least as readable as the white times they replace.
        val white = Color.White.copy(alpha = .62f).compositeOver(ground)
        assertTrue(CoverLegibility.contrast(ViceCityTheme.elapsedTime, ground) >= CoverLegibility.contrast(white, ground))
    }

    @Test fun aHeldNoteGlowsPinkThenCyanAndStaysSmall() {
        assertEquals(ViceCityTheme.Colors.accent, ViceCityTheme.heldNoteGlow(0f))
        assertEquals(ViceCityTheme.Colors.cyan, ViceCityTheme.heldNoteGlow(1f))
        assertEquals(ViceCityTheme.Colors.accent, ViceCityTheme.heldNoteGlow(Float.NaN))
        val white = LyricsWords.glowColor(.5f, neon = false, glow = 1f)
        assertEquals(Color.White.copy(alpha = .42f), white)
        val neon = LyricsWords.glowColor(.5f, neon = true, glow = 1f)
        assertTrue(neon.alpha <= .6f)
        assertEquals(0f, LyricsWords.glowColor(.5f, neon = true, glow = 0f).alpha, 0f)
    }

    @Test fun theFloorIsStillInThePerformanceModeAndSlowOtherwise() {
        assertEquals(NeonFloor.STILL, NeonFloor.scroll(.1f, performance = true), 0f)
        assertEquals(NeonFloor.STILL, NeonFloor.scroll(.9f, performance = true), 0f)
        assertEquals(NeonFloor.STILL, NeonFloor.scroll(Float.NaN, performance = false), 0f)
        // One line comes towards the viewer every two seconds at least.
        assertTrue(CYCLE_PER_PASS_MS >= 2_000L)
        // The travel loops without a jump when the pulse wraps.
        assertEquals(NeonFloor.scroll(0f, false), NeonFloor.scroll(1f, false), 1e-4f)
        val rows = NeonFloor.rows(.4f, NeonFloor.ROWS)
        assertEquals(NeonFloor.ROWS, rows.size)
        assertTrue(rows.all { it in 0f..1f })
        assertEquals(rows.sorted(), rows)
        // They spread out towards the viewer.
        assertTrue(rows[rows.size - 1] - rows[rows.size - 2] > rows[1] - rows[0])
        assertTrue(NeonFloor.ROWS_LIGHT < NeonFloor.ROWS && NeonFloor.RAYS_LIGHT < NeonFloor.RAYS)
    }

    @Test fun theFloorFadesInFromItsHorizonAndStaysFaint() {
        assertEquals(0f, NeonFloor.presence(0f), 0f)
        assertEquals(1f, NeonFloor.presence(1f), 0f)
        assertTrue(NeonFloor.presence(.3f) < NeonFloor.presence(.7f))
        assertTrue(NeonFloor.MAX_ALPHA <= .35f)
    }

    @Test fun theSignFlickersBrieflyAndEndsLit() {
        assertTrue(NeonFlicker.DURATION_MS in 400L..800L)
        assertTrue("it starts dim", NeonFlicker.alpha(0) < .5f)
        assertEquals(1f, NeonFlicker.alpha(NeonFlicker.DURATION_MS), 0f)
        assertEquals(1f, NeonFlicker.alpha(5_000), 0f)
        val levels = (0L until NeonFlicker.DURATION_MS step 10).map(NeonFlicker::alpha)
        assertTrue("it stutters", levels.zipWithNext().count { (a, b) -> b < a } >= 3)
        assertTrue(levels.all { it > 0f })
        assertFalse("never under reduced motion", NeonFlicker.due(reduced = true))
    }

    private val CYCLE_PER_PASS_MS = SeasonPulse.CYCLE_MS / NeonFloor.PASSES_PER_CYCLE

    // ------------------------------------------------------------------ Vice FM

    private fun t(id: String, artist: String, title: String = id) = Track(id, title, artist, "Album")

    private class Fixed(
        val radios: Map<String, List<Track>> = emptyMap(), val tops: Map<String, List<Track>> = emptyMap(),
        val found: List<Track> = emptyList(), val failing: Boolean = false,
    ) : ViceFm.Sources {
        val searched = mutableListOf<String>()
        override suspend fun radio(artist: String): List<Track> { if (failing) error("offline"); return radios[artist].orEmpty() }
        override suspend fun top(artist: String): List<Track> = tops[artist].orEmpty()
        override suspend fun search(query: String): List<Track> { searched += query; return found }
    }

    @Test fun viceFmBlendsSynthwaveAndEightiesWithoutRepeats() = runBlocking {
        val (synth, eighties) = ViceFm.seeds(7L)
        assertEquals(3, synth.size); assertEquals(2, eighties.size)
        assertTrue(synth.all { it in ViceFm.SYNTHWAVE } && eighties.all { it in ViceFm.EIGHTIES })
        val radios = synth.associateWith { a -> (0 until 12).map { t("$a-r$it", "$a $it", "Night $a $it") } }
        val tops = eighties.associateWith { a -> (0 until 8).map { t("$a-t$it", a, "Hit $a $it") } } +
            // The same recording under another id comes once only.
            mapOf(eighties[0] to listOf(t("x1", "Same", "Drive"), t("x2", "Same", "Drive (Remastered)")))
        val mix = ViceFm.mix(Fixed(radios, tops), 7L) { it.artist != "${synth[0]} 1" }
        assertTrue(mix.size in ViceFm.FEWEST..ViceFm.SIZE)
        assertEquals(mix.size, mix.map { it.id }.distinct().size)
        assertEquals(1, mix.count { it.artist == "Same" })
        assertTrue("a blocked artist is never heard", mix.none { it.artist == "${synth[0]} 1" })
        assertTrue("both styles", mix.any { it.id.contains("-r") } && mix.any { it.artist in eighties || it.artist == "Same" })
        mix.zipWithNext().forEach { (a, b) -> assertNotEquals(a.artist, b.artist) }
        assertEquals("a new seed, new seeds", false, ViceFm.seeds(7L) == ViceFm.seeds(8L) && ViceFm.seeds(8L) == ViceFm.seeds(9L))
    }

    @Test fun viceFmFallsBackOnTheSearchesAndEndsEmptyWhenNothingAnswers() = runBlocking {
        val found = (0 until 20).map { t("s$it", "Artist $it") }
        val sources = Fixed(failing = true, found = found)
        val mix = ViceFm.mix(sources, 1L) { true }
        assertEquals(ViceFm.SEARCHES.toSet(), sources.searched.toSet())
        assertTrue(mix.isNotEmpty())
        assertTrue(ViceFm.mix(Fixed(failing = true), 1L) { true }.isEmpty())
    }

    @Test fun viceFmNeverNamesAnyGameStudio() {
        val words = (ViceFm.SYNTHWAVE + ViceFm.EIGHTIES + ViceFm.SEARCHES).joinToString(" ").lowercase()
        assertFalse(words.contains("rockstar"))
    }
}
