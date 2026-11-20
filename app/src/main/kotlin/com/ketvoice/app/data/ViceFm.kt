package com.ketvoice.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Vice FM (6.3): the radio of the Vice City theme, a card on Home while the theme is on. Each listen is a new mix of
 * about forty titles from the public catalogue — the style radio of three synthwave artists and the best known titles
 * of two 80s ones, picked anew each time — blended so that no recording comes twice, no artist plays twice in a row
 * and no blocked artist is heard. When the catalogue says too little, the search sources fill the mix from a few
 * style searches. No server of Ketvoice's own is involved; this is the machinery of the radio and the weekly
 * discoveries ([CatalogRepo], [MusicApi]).
 */
object ViceFm {
    /** The titles of one mix, and the fewest it needs before the style searches fill it. */
    const val SIZE = 40
    const val FEWEST = 8
    /** What each seed brings at most: an artist's style radio, an artist's best known titles, a search. */
    const val PER_RADIO = 10
    const val PER_TOP = 6
    const val PER_SEARCH = 12

    val SYNTHWAVE = listOf("Kavinsky", "The Midnight", "FM-84", "Timecop1983", "Gunship", "Mitch Murder",
        "Lazerhawk", "College", "Miami Nights 1984", "Waveshaper")
    val EIGHTIES = listOf("a-ha", "Tears for Fears", "Hall & Oates", "Kim Wilde", "Duran Duran", "Pet Shop Boys",
        "Jan Hammer", "Glenn Frey", "Simple Minds", "Eurythmics")
    val SEARCHES = listOf("synthwave", "outrun", "80s hits")

    /** Where the titles come from: the catalogue and the search sources in the app, fixed answers in tests. */
    interface Sources {
        suspend fun radio(artist: String): List<Track>
        suspend fun top(artist: String): List<Track>
        suspend fun search(query: String): List<Track>
    }

    object AppSources : Sources {
        override suspend fun radio(artist: String) = CatalogRepo.artistRadio(artist)
        override suspend fun top(artist: String) = CatalogRepo.artistTop(artist)
        override suspend fun search(query: String) = MusicApi.searchYtMusic(query)
    }

    /** The seeds of one mix, picked by [seed]: three synthwave artists, then two of the 80s. */
    fun seeds(seed: Long): Pair<List<String>, List<String>> {
        val random = java.util.Random(seed)
        return SYNTHWAVE.shuffled(random).take(3) to EIGHTIES.shuffled(random).take(2)
    }

    /** A new mix for [seed]; empty when neither the catalogue nor the search answers. */
    suspend fun mix(sources: Sources, seed: Long, allows: (Track) -> Boolean = ArtistPrefs::allows): List<Track> {
        val (synth, eighties) = seeds(seed)
        val picked = blend(gather(synth, eighties, sources), allows)
        if (picked.size >= FEWEST) return picked.take(SIZE)
        return blend(listOf(picked) + searches(sources), allows).take(SIZE)
    }

    private suspend fun gather(synth: List<String>, eighties: List<String>, sources: Sources): List<List<Track>> = coroutineScope {
        (synth.map { artist -> async { quietly { sources.radio(artist) }.take(PER_RADIO) } } +
            eighties.map { artist -> async { quietly { sources.top(artist) }.take(PER_TOP) } }).awaitAll()
    }

    private suspend fun searches(sources: Sources): List<List<Track>> = coroutineScope {
        SEARCHES.map { query -> async { quietly { sources.search(query) }.take(PER_SEARCH) } }.awaitAll()
    }

    private suspend fun quietly(block: suspend () -> List<Track>): List<Track> =
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }

    /**
     * One title of each list in turn, so that the styles alternate; a recording only once, never an artist
     * [allows] refuses, and no artist twice in a row while another can come between.
     */
    internal fun blend(groups: List<List<Track>>, allows: (Track) -> Boolean): List<Track> {
        val seen = HashSet<String>()
        val out = ArrayList<Track>()
        val longest = groups.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) for (group in groups) {
            val track = group.getOrNull(i) ?: continue
            if (allows(track) && seen.add(Recordings.of(track))) out += track
        }
        return com.ketvoice.app.smartlib.MoodMixes.spreadArtists(out)
    }
}
