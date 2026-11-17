package com.ketvoice.app.smartlib

/**
 * A free-text search read on the device ("rap français calme des années 2010"): the styles,
 * languages, ambiances, tempos, years and artists it names, and the words that remain.
 * No service is asked; an unknown word simply stays in [rest].
 */
data class NaturalQuery(val raw: String, val filters: List<QueryFilter>, val rest: String) {
    /** True when something more than an artist's name was understood: the search is then read as a request. */
    val natural: Boolean get() = filters.any { it !is ArtistFilter }

    /** The same request without the chips the listener removed. */
    fun without(keys: Set<String>): NaturalQuery = if (keys.isEmpty()) this else copy(filters = filters.filterNot { it.key in keys })

    /**
     * What is typed into the catalogue's search: the words that remain and the artists, or, when
     * nothing remains, the names of the style and the language ("Rap Français").
     */
    fun catalogueText(): String {
        val artists = filters.filterIsInstance<ArtistFilter>().map { it.name }
        val words = (listOf(rest) + artists).filter { it.isNotBlank() }.joinToString(" ")
        if (words.isNotBlank()) return words
        val named = filters.filter { it is GenreFilter || it is LanguageFilter }.map { it.label }
        return named.joinToString(" ").ifBlank { raw.trim() }
    }

    companion object {
        /** Reads [text]. [artists] are names the listener has (library, history): they are recognised as artists. */
        fun parse(text: String, artists: Collection<String> = emptyList()): NaturalQuery {
            var work = SmartText.words(text)
            val found = mutableListOf<QueryFilter>()
            work = QueryYears.extract(work, found)
            work = matchArtists(work, artists, found)
            work = matchLexicon(work, found)
            val rest = work.split(' ').filter { it.isNotBlank() && it !in QueryLexicon.fillers }.joinToString(" ")
            return NaturalQuery(text, found.distinctBy { it.key }.sortedBy(::order), rest)
        }

        /** Chips read as one says it: « Werenoi · Rap · Français · Calme · Lent · 2010–2019 ». */
        private fun order(filter: QueryFilter): Int = when (filter) {
            is ArtistFilter -> 0; is GenreFilter -> 1; is LanguageFilter -> 2; is MoodFilter -> 3; is TempoFilter -> 4; is YearFilter -> 5
        }

        private fun matchArtists(text: String, artists: Collection<String>, found: MutableList<QueryFilter>): String {
            var work = text
            val names = artists.asSequence().map { it.trim() }.filter { it.length >= 3 }
                .map { it to SmartText.words(it) }.filter { it.second.length >= 3 && !lexiconWord(it.second) }
                .distinctBy { it.second }.sortedByDescending { it.second.length }.take(4000).toList()
            for ((name, folded) in names) {
                val at = SmartText.find(work, folded)
                if (at < 0) continue
                found += ArtistFilter(name)
                work = SmartText.cut(work, at, folded.length)
            }
            return work
        }

        private fun lexiconWord(word: String) = word in QueryLexicon.fillers || word in QueryLexicon.words

        private fun matchLexicon(text: String, found: MutableList<QueryFilter>): String {
            var work = text
            for ((word, filters) in QueryLexicon.entries) {
                while (true) {
                    val at = SmartText.find(work, word)
                    if (at < 0) break
                    found += filters
                    work = SmartText.cut(work, at, word.length)
                }
            }
            return work
        }
    }
}

/** Years and decades in every interface language: "années 2010", "90s", "2010er", "90-х", "2010年代", "90년대"… */
internal object QueryYears {
    private const val YEAR = "(19[5-9]\\d|20[0-3]\\d)"
    private const val EDGE_L = "(?<![\\p{L}\\p{N}])"
    private const val EDGE_R = "(?![\\p{L}\\p{N}])"
    private val range = Regex("$EDGE_L$YEAR\\s*(?:a|to|bis|al|au|до|-|~|～)?\\s*$YEAR$EDGE_R")
    private val suffixed = Regex("$EDGE_L'?(\\d{2}|\\d{4})\\s?(?:s|er|е|х|x|年代|년대|के दशक|का दशक)(?![\\p{IsLatin}\\p{IsCyrillic}])")
    private val prefixed = Regex("$EDGE_L(?:annees|annee|anni|anos|decennie|decade|decada|decennio|jahre|годы|годов|years|eighties)\\s+'?(\\d{2}|\\d{4})$EDGE_R")
    private val single = Regex("$EDGE_L$YEAR$EDGE_R")

    fun extract(text: String, found: MutableList<QueryFilter>): String {
        var work = text
        range.find(work)?.let { m ->
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
            found += YearFilter(minOf(a, b), maxOf(a, b)); work = SmartText.cut(work, m.range.first, m.value.length)
        }
        work = decades(work, suffixed, found)
        work = decades(work, prefixed, found)
        single.find(work)?.let { m ->
            val year = m.groupValues[1].toInt()
            found += YearFilter(year, year); work = SmartText.cut(work, m.range.first, m.value.length)
        }
        return work
    }

    private fun decades(text: String, pattern: Regex, found: MutableList<QueryFilter>): String {
        var work = text
        while (true) {
            val m = pattern.find(work) ?: return work
            val start = decadeStart(m.groupValues[1])
            if (start != null) found += YearFilter(start, start + 9)
            work = SmartText.cut(work, m.range.first, m.value.length)
        }
    }

    /** "2010" → 2010, "90" → 1990, "10" → 2010; null for a number that is no decade. */
    fun decadeStart(number: String): Int? {
        val n = number.toIntOrNull() ?: return null
        return when {
            number.length == 4 && n % 10 == 0 && n in 1900..2030 -> n
            number.length == 2 && n % 10 == 0 && n <= 30 -> 2000 + n
            number.length == 2 && n % 10 == 0 && n >= 40 -> 1900 + n
            else -> null
        }
    }
}
