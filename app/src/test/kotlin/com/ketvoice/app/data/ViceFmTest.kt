package com.ketvoice.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Vice FM (6.3), the Vice City theme's radio: a blend of synthwave and 80s hits, never twice the same, with fallbacks. */
class ViceFmTest {
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
