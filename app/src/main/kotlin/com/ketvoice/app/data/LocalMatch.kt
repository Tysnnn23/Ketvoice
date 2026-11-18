package com.ketvoice.app.data

import kotlin.math.abs

/**
 * « Musique locale pro » (7.0): a local file against the catalogue's search results, by title, artist and
 * duration. A match becomes a proposal the listener accepts (per album or all at once); a sure match also shows
 * the title as « Aussi disponible en streaming » and lends the catalogue's cover to a file that has none. Pure.
 */
internal object LocalMatch {
    enum class Field { Title, Artist, Album, AlbumArtist }

    data class Proposal(val localId: String, val catalog: Track, val score: Double, val changes: Map<Field, String>)

    /** Below this a result is not the same recording. */
    const val PROPOSE_FROM = 0.72
    /** From this a result is surely the same recording: « Aussi disponible en streaming », and its cover may be borrowed. */
    const val SURE_FROM = 0.86

    /** How alike two names are, 0..1: equal keys 1, one inside the other 0.9, else the share of words in common. */
    fun similarity(a: String?, b: String?): Double {
        val x = LocalTagNormalizer.key(a)
        val y = LocalTagNormalizer.key(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if ((x.length >= 4 && y.contains(x)) || (y.length >= 4 && x.contains(y))) return 0.9
        val wx = x.split(' ').toSet()
        val wy = y.split(' ').toSet()
        return 2.0 * wx.intersect(wy).size / (wx.size + wy.size)
    }

    /** Durations: within 2 s it is the same recording, past 15 s another one; unknown says nothing. */
    fun durationScore(a: Long, b: Long): Double? {
        if (a <= 0 || b <= 0) return null
        val diff = abs(a - b)
        return when {
            diff <= 2_000 -> 1.0
            diff <= 5_000 -> 0.8
            diff <= 10_000 -> 0.5
            diff <= 15_000 -> 0.2
            else -> 0.0
        }
    }

    /**
     * 0..1: title half, artist three tenths, duration a fifth. A file without an artist is judged on its title and
     * duration only; a title that does not look alike is never a match, whatever the rest says.
     */
    fun score(local: LocalTags, candidate: Track): Double {
        val title = similarity(local.title, candidate.title)
        if (title < 0.6) return 0.0
        val artist = local.artist?.let { maxOf(similarity(it, candidate.artist), similarity(LocalTagNormalizer.primaryArtist(it), candidate.artist)) }
        val duration = durationScore(local.durationMs, candidate.durationMs)
        if (duration == 0.0) return 0.0
        var weight = 0.5; var sum = 0.5 * title
        if (artist != null) { weight += 0.3; sum += 0.3 * artist }
        if (duration != null) { weight += 0.2; sum += 0.2 * duration }
        // Without an artist or a duration to confirm it, a title alone is never sure.
        val confirmed = artist != null || duration != null
        return (sum / weight).let { if (confirmed) it else minOf(it, PROPOSE_FROM) }
    }

    fun best(local: LocalTags, candidates: List<Track>): Pair<Track, Double>? = candidates
        .filterNot { it.id.startsWith("local:") }
        .map { it to score(local, it) }
        .filter { it.second >= PROPOSE_FROM }
        .maxByOrNull { it.second }

    /** The search a file is matched with: its title and its artist when it has one. */
    fun query(local: LocalTags): String = listOfNotNull(local.title, local.artist?.let(LocalTagNormalizer::primaryArtist))
        .joinToString(" ").trim()

    /**
     * What the catalogue would correct. A missing value is filled; a value written differently (« beyonce » →
     * « Beyoncé ») is respelled. A different artist or album the file has is never swapped for another one (a
     * search often finds the single, or a version with guests). A missing album artist is filled only when the
     * album is filled too.
     */
    fun proposal(localId: String, local: LocalTags, candidate: Track, score: Double): Proposal? {
        if (score < PROPOSE_FROM) return null
        val changes = LinkedHashMap<Field, String>()
        fun offer(field: Field, now: String?, catalog: String?) {
            val value = LocalTagNormalizer.clean(catalog) ?: return
            val respelled = now != null && now != value && LocalTagNormalizer.fold(now) == LocalTagNormalizer.fold(value)
            if (now == null || respelled) changes[field] = value
        }
        offer(Field.Title, local.title, candidate.title.takeIf { similarity(local.title, it) >= 0.9 })
        offer(Field.Artist, local.artist, candidate.artist)
        offer(Field.Album, local.album, candidate.album)
        if (Field.Album in changes && local.albumArtist == null && !local.compilation)
            LocalTagNormalizer.clean(candidate.artist)?.let { changes[Field.AlbumArtist] = LocalTagNormalizer.primaryArtist(it) }
        return if (changes.isEmpty()) null else Proposal(localId, candidate, score, changes)
    }

    /** [tags] with the accepted [changes] laid over them. */
    fun apply(tags: LocalTags, changes: Map<Field, String>): LocalTags = tags.copy(
        title = changes[Field.Title] ?: tags.title,
        artist = changes[Field.Artist] ?: tags.artist,
        album = changes[Field.Album] ?: tags.album,
        albumArtist = changes[Field.AlbumArtist] ?: tags.albumArtist)
}
