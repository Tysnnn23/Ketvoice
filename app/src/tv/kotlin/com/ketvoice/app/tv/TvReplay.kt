package com.ketvoice.app.tv

import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.LiveStats
import com.ketvoice.app.data.MonthStat
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.Wrapped
import com.ketvoice.app.ui.sub.WrappedPage
import com.ketvoice.app.ui.sub.measuredBpm
import java.time.LocalDate
import java.time.YearMonth

/** The slides of « Replay » and « Wrapped » on the TV, in their order. */
internal enum class TvSlide { Intro, Minutes, Artists, Titles, Genres, Hours, Summary }

/** What the slides show: the phone's recap, the genres of its titles and the artists' photos (by lower-case name). */
internal data class TvReplayData(
    val recap: Wrapped.Recap,
    val genres: List<LiveStats.GenreRow> = emptyList(),
    val photos: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = recap.plays == 0 && recap.minutes == 0L
}

/**
 * « Replay / Wrapped TV » (Ketvoice TV 1.1.0): the listener's month (Replay) or year (Wrapped) as big slides driven
 * by the remote, from the phone's own numbers ([Wrapped], [LiveStats], [com.ketvoice.app.data.ListeningMonths]).
 * Pure: host-tested.
 */
internal object TvReplayPlan {
    /** How long a slide stays before the next one comes. */
    const val SLIDE_MS = 8_000L
    const val TOP = 5

    /** The host renders' recap (with genres, which need the catalogue's styles); never set by the app. */
    @Volatile internal var dataForTest: ((String?) -> TvReplayData)? = null

    fun slides(data: TvReplayData): List<TvSlide> = buildList {
        add(TvSlide.Intro); add(TvSlide.Minutes)
        if (data.recap.artists.isNotEmpty()) add(TvSlide.Artists)
        if (data.recap.tracks.isNotEmpty()) add(TvSlide.Titles)
        if (data.genres.size >= 2) add(TvSlide.Genres)
        if (data.recap.peakHour != null) add(TvSlide.Hours)
        add(TvSlide.Summary)
    }

    fun next(index: Int, size: Int): Int = (index + 1).coerceAtMost((size - 1).coerceAtLeast(0))
    fun previous(index: Int): Int = (index - 1).coerceAtLeast(0)

    /**
     * The title each slide plays in the background (the best part of it, as on the phone): the most played one opens
     * and closes, the artists' slide plays the top artist's title, the others take the next titles of the top list.
     */
    fun clips(slides: List<TvSlide>, recap: Wrapped.Recap): List<Track?> {
        val top = recap.tracks.map { it.first }.distinctBy { it.id }
        if (top.isEmpty()) return slides.map { null }
        var next = 1
        fun following(): Track = top[(next++).mod(top.size)]
        return slides.map { slide ->
            when (slide) {
                TvSlide.Intro, TvSlide.Summary -> top.first()
                TvSlide.Artists -> recap.topArtist?.name?.let { name -> top.firstOrNull { byArtist(it, name) } } ?: following()
                else -> following()
            }
        }
    }

    private fun byArtist(track: Track, name: String) = track.artist.split(',').any { it.trim().equals(name, ignoreCase = true) }

    /** Each slide in the phone story's colours. */
    fun palette(slide: TvSlide): Pair<Long, Long> = com.ketvoice.app.ui.sub.WrappedPages.palette(when (slide) {
        TvSlide.Intro -> WrappedPage.Intro
        TvSlide.Minutes -> WrappedPage.Minutes
        TvSlide.Artists -> WrappedPage.Artist
        TvSlide.Titles -> WrappedPage.Tracks
        TvSlide.Genres -> WrappedPage.Mood
        TvSlide.Hours -> WrappedPage.Clock
        TvSlide.Summary -> WrappedPage.Summary
    })

    /** Whole hours of [minutes] (rounded down), for « Soit {0} heures ». */
    fun hours(minutes: Long): Long = (minutes / 60L).coerceAtLeast(0L)

    /** The share of the busiest hour drawn full: every bar of the clock as a fraction of it. */
    fun clockBars(clock: List<Long>): List<Float> {
        val max = clock.maxOrNull()?.takeIf { it > 0L } ?: return List(24) { 0f }
        return List(24) { h -> clock.getOrElse(h) { 0L }.toFloat() / max }
    }

    /** The day [LiveStats] is asked about to get a whole past month (its last day) or the year's genres. */
    fun dayFor(month: String?, today: LocalDate): LocalDate =
        month?.let { runCatching { YearMonth.parse(it).atEndOfMonth() }.getOrNull() }?.takeIf { it.isBefore(today) } ?: today

    fun monthName(key: String): String = com.ketvoice.app.ui.sub.WrappedPeriod.monthName(key)

    /** A month's most played cover, for its card. */
    fun monthCover(key: String, stats: Map<String, MonthStat>, library: TvLibrary): String? {
        val plays = stats[key]?.plays ?: return null
        val known = stats[key]?.tracks.orEmpty()
        val id = plays.maxByOrNull { it.value }?.key ?: return null
        val track = known[id] ?: library.known.firstOrNull { it.id == id } ?: return null
        return library.cover(track)
    }
}

/** The recap of [month] ("2026-11"), or of the year under way when null, as the phone's Replay and Wrapped compute it. */
internal fun tvReplayData(month: String?, today: LocalDate = LocalDate.now()): TvReplayData {
    val cover = { t: Track -> ArtworkRepo.coverForTrack(t) ?: t.coverUrl }
    val months = ListeningHistory.months.value
    val recap = if (month != null) Wrapped.month(month, months, ListeningHistory.knownTracks.value, coverOf = cover,
        bpmOf = ::measuredBpm)
    else com.ketvoice.app.ui.sub.wrappedRecapNow()
    val input = LiveStats.Input(TvReplayPlan.dayFor(month, today), ListeningHistory.days.value, months, ListeningHistory.years.value,
        ListeningHistory.listenedMs.value, ListeningHistory.playCounts.value, ListeningHistory.knownTracks.value)
    val period = if (month != null) LiveStats.Period.Month else LiveStats.Period.Year
    val genres = runCatching { LiveStats.compute(period, input, genresOf = ::tvGenresOf).genres }.getOrDefault(emptyList())
    return TvReplayData(recap, genres)
}

private fun tvGenresOf(tracks: List<Track>): Map<String, Set<String>> = runCatching {
    com.ketvoice.app.smartlib.SmartFacts.build(tracks) { null }.mapValues { (_, facts) -> facts.genres.mapTo(LinkedHashSet()) { it.label } }
}.getOrDefault(emptyMap())
