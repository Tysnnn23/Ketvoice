package com.ketvoice.app.smartlib

import com.ketvoice.app.data.Track

/** Whether a title answers one understood filter: yes, no, or nothing known to say. */
enum class Verdict { Yes, No, Unknown }

/**
 * Applies a [NaturalQuery] to titles. Filters of one kind are alternatives ("rap pop": either),
 * kinds add up ("rap calme": both). A title that contradicts what is known is left out; what is
 * unknown is kept only where [strict] allows it.
 */
internal object SmartFilter {
    const val MOOD_YES = .58f
    const val MOOD_NO = .4f

    fun verdict(filter: QueryFilter, facts: TrackFacts): Verdict = when (filter) {
        is GenreFilter -> genre(filter.genre, facts)
        is LanguageFilter -> facts.lang?.let { if (it == filter.lang) Verdict.Yes else Verdict.No } ?: Verdict.Unknown
        is YearFilter -> facts.year?.let { if (it in filter.from..filter.to) Verdict.Yes else Verdict.No } ?: Verdict.Unknown
        is MoodFilter -> mood(filter.mood, facts)
        is TempoFilter -> tempo(filter.tempo, facts.bpm)
        is ArtistFilter -> if (SmartText.find(facts.artist, SmartText.words(filter.name)) >= 0) Verdict.Yes else Verdict.No
    }

    private fun genre(genre: Genre, facts: TrackFacts): Verdict = when {
        facts.genres.isEmpty() -> Verdict.Unknown
        genre in facts.genres -> Verdict.Yes
        genre == Genre.KPop && Genre.Pop in facts.genres && facts.lang == Lang.Korean -> Verdict.Yes
        else -> Verdict.No
    }

    private fun mood(mood: Mood, facts: TrackFacts): Verdict {
        val score = MoodScore.score(mood, facts) ?: return Verdict.Unknown
        return when {
            score >= MOOD_YES -> Verdict.Yes
            score < MOOD_NO -> Verdict.No
            else -> Verdict.Unknown
        }
    }

    private fun tempo(tempo: Tempo, bpm: Float?): Verdict {
        bpm ?: return Verdict.Unknown
        return when (tempo) {
            Tempo.Slow -> if (bpm < 100f) Verdict.Yes else if (bpm > 112f) Verdict.No else Verdict.Unknown
            Tempo.Fast -> if (bpm > 118f) Verdict.Yes else if (bpm < 104f) Verdict.No else Verdict.Unknown
        }
    }

    /** The verdict of each kind of filter: yes when one alternative says yes, no when all say no. */
    fun kinds(filters: List<QueryFilter>, facts: TrackFacts): List<Pair<Boolean, Verdict>> =
        filters.groupBy { it::class }.map { (_, group) ->
            val verdicts = group.map { verdict(it, facts) }
            val combined = when {
                Verdict.Yes in verdicts -> Verdict.Yes
                verdicts.all { it == Verdict.No } -> Verdict.No
                else -> Verdict.Unknown
            }
            (group.first() is ArtistFilter) to combined
        }

    /** How many kinds a title answers, or null when it must be left out. */
    fun rank(query: NaturalQuery, facts: TrackFacts, strict: Boolean): Int? {
        if (strict && !restMatches(query.rest, facts)) return null
        val kinds = kinds(query.filters, facts)
        if (kinds.any { it.second == Verdict.No }) return null
        if (strict && kinds.any { it.first && it.second != Verdict.Yes }) return null
        val yes = kinds.count { it.second == Verdict.Yes }
        if (strict && kinds.any { !it.first } && kinds.none { !it.first && it.second == Verdict.Yes }) return null
        return yes
    }

    private fun restMatches(rest: String, facts: TrackFacts): Boolean {
        if (rest.isBlank()) return true
        val haystack = facts.words + " " + facts.artist
        return rest.split(' ').all { SmartText.find(haystack, it) >= 0 || haystack.contains(it) }
    }

    /**
     * The titles of [tracks] answering [query], those answering most first (the order is kept between
     * equals). [strict] is for the library: a title must answer at least one thing asked of it.
     */
    fun apply(tracks: List<Track>, query: NaturalQuery, strict: Boolean, factsOf: (Track) -> TrackFacts): List<Track> =
        tracks.mapIndexedNotNull { index, track -> rank(query, factsOf(track), strict)?.let { Triple(track, it, index) } }
            .sortedWith(compareByDescending<Triple<Track, Int, Int>> { it.second }.thenBy { it.third })
            .map { it.first }
}
