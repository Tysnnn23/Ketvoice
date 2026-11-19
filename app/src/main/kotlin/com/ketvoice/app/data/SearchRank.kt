package com.ketvoice.app.data

import java.text.Normalizer
import kotlin.math.ln
import kotlin.math.min

/**
 * The order of search results (7.2, owner: « les recherches sont peu pertinentes »). Sources answer in
 * their own order and were shown one after the other; now every title gets an explainable score:
 * exact title, then title prefix, then the artist named by the query, then every word found (accents
 * and case ignored, one typo allowed in words of 4 letters or more), with versions the query did not ask
 * for (karaoke, slowed…) further down and what the listener plays and likes a little higher. A tie keeps
 * the sources' order, which carries their own relevance.
 */
internal object SearchRank {
    /** What the listener does, to lift familiar titles a little (never above a better text match). */
    data class Taste(val plays: Map<String, Int> = emptyMap(), val liked: Set<String> = emptySet(),
                     val artistPlays: Map<String, Int> = emptyMap())

    private val marks = Regex("\\p{M}+")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")
    private val variants = listOf("karaoke", "instrumental", "cover", "slowed", "sped up", "nightcore", "8d", "reverb", "remix", "live")

    /** Lower case, accents and punctuation removed, spaces collapsed: « Beyoncé – Halo! » is « beyonce halo ». */
    fun fold(value: String): String = Normalizer.normalize(value.replace('œ', 'o').replace('æ', 'a'), Normalizer.Form.NFD)
        .replace(marks, "").lowercase().replace(nonWord, " ").trim()

    /** A title without what follows « feat. » or a bracket: « Halo (Remastered) » matches « halo ». */
    private fun coreTitle(title: String): String = fold(title.substringBefore(" (").substringBefore(" [").substringBefore(" feat"))

    fun mainArtist(artist: String): String = fold(MediaOverrides.mainArtist(artist).substringBefore(','))

    /** Edit distance up to [max] (more is reported as max + 1), adjacent swaps counting as one. */
    internal fun distance(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            var rowMin = Int.MAX_VALUE
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = min(v, d[i - 2][j - 2] + 1)
                d[i][j] = v
                rowMin = min(rowMin, v)
            }
            if (rowMin > max) return max + 1
        }
        return d[a.length][b.length]
    }

    /** How well one query word is found among [words]: 1 exact, 0.8 a word starts with it, 0.6 one typo. */
    private fun wordMatch(q: String, words: List<String>): Double {
        if (q in words) return 1.0
        if (q.length >= 2 && words.any { it.startsWith(q) }) return 0.8
        if (q.length >= 4) {
            val allowed = if (q.length >= 8) 2 else 1
            if (words.any { distance(q, it, allowed) <= allowed || (it.length > q.length && distance(q, it.take(q.length), 1) <= 1) }) return 0.6
        }
        return 0.0
    }

    fun score(query: String, track: Track, taste: Taste = Taste()): Double {
        val q = fold(query)
        if (q.isEmpty()) return 0.0
        val title = fold(track.title)
        val core = coreTitle(track.title)
        val artist = mainArtist(track.artist)
        val allArtists = fold(track.artist)
        var s = when {
            title == q || core == q -> 100.0
            title.startsWith(q) || core.startsWith(q) -> 70.0
            q == "$core $artist" || q == "$artist $core" -> 95.0
            artist == q -> 60.0
            artist.startsWith(q) && q.length >= 3 -> 45.0
            else -> 0.0
        }
        val qWords = q.split(' ')
        val words = (title.split(' ') + allArtists.split(' ') + fold(track.album).split(' ')).filter { it.isNotEmpty() }
        val found = qWords.sumOf { wordMatch(it, words) } / qWords.size
        s += found * 40
        if (found == 0.0 && s == 0.0) s -= 20
        // A version the query did not ask for goes after the original.
        if (variants.any { v -> title.contains(v) && !q.contains(v) }) s -= 15
        val plays = taste.plays[track.id] ?: 0
        if (plays > 0) s += min(10.0, 3 * ln(1.0 + plays))
        if (track.id in taste.liked) s += 8
        val heard = taste.artistPlays[artist] ?: 0
        if (heard > 0) s += min(6.0, 1.5 * ln(1.0 + heard))
        return s
    }

    /** Best first; equal scores keep the sources' order. */
    fun rank(query: String, tracks: List<Track>, taste: Taste = Taste()): List<Track> =
        tracks.withIndex().sortedWith(compareByDescending<IndexedValue<Track>> { score(query, it.value, taste) }.thenBy { it.index })
            .map { it.value }

    /** The query names an artist: at least two of the first results are theirs (accents and case ignored). */
    fun namesArtist(query: String, tracks: List<Track>): Boolean {
        val q = fold(query)
        return q.isNotEmpty() && tracks.take(10).count { mainArtist(it.artist) == q } >= 2
    }

    /** Artists whose name is the query first, then those that start with it; otherwise the source's order. */
    fun <A> artistsFirst(query: String, artists: List<A>, name: (A) -> String): List<A> {
        val q = fold(query)
        fun rankOf(a: A): Int { val n = fold(name(a)); return when { n == q -> 0; n.startsWith(q) -> 1; distance(q, n, 1) <= 1 -> 1; else -> 2 } }
        return artists.withIndex().sortedWith(compareBy<IndexedValue<A>> { rankOf(it.value) }.thenBy { it.index }).map { it.value }
    }

    /** The listener's taste as the app knows it now. */
    fun liveTaste(): Taste {
        val plays = ListeningHistory.playCounts.value
        val artistPlays = HashMap<String, Int>()
        ListeningHistory.knownTracks.value.forEach { t -> val n = plays[t.id] ?: 0; if (n > 0) artistPlays.merge(mainArtist(t.artist), n, Int::plus) }
        return Taste(plays, AppSettings.favoriteIds.value.toSet(), artistPlays)
    }
}
