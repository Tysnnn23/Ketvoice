package com.ketvoice.app.smartlib

import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.Track

/** One place a recording is saved: a download, or an entry of a playlist. */
data class SavedCopy(val track: Track, val playlistId: String? = null, val playlistName: String? = null,
                     val entryId: String? = null, val downloadBytes: Long = 0L, val format: String? = null) {
    val downloaded: Boolean get() = playlistId == null
}

/**
 * The same recording saved several times. [sameVersion] is false for a group of versions of one
 * song ("Live", "Remastered"…), which are only merged when the listener asks for it.
 */
data class DuplicateGroup(val key: String, val title: String, val artist: String, val copies: List<SavedCopy>, val sameVersion: Boolean) {
    val downloads: List<SavedCopy> get() = copies.filter { it.downloaded }
}

/** What merging a group into one title changes: entries removed or pointed at the kept title, downloads that may be deleted. */
data class MergePlan(val keep: Track, val removeEntries: List<Pair<String, String>>, val repoint: List<Pair<String, String>>,
                     val extraDownloads: List<SavedCopy>)

object Duplicates {
    /** Two durations of the same recording differ by at most this much (or 2.5 %). */
    const val TOLERANCE_MS = 3_000L

    private val noise = Regex("[(\\[](?:[^)\\]]*?)(?:official|officiel|officielle|clip|lyrics?|paroles|audio|video|vidéo|visuali[sz]er|hd|hq|4k|explicit)(?:[^)\\]]*)[)\\]]", RegexOption.IGNORE_CASE)
    private val featuring = Regex("[(\\[]\\s*(?:feat\\.?|ft\\.?|featuring|avec|with)\\s[^)\\]]*[)\\]]|\\s(?:feat\\.?|ft\\.?|featuring)\\s.*$", RegexOption.IGNORE_CASE)
    private val markers = listOf(
        "remaster" to "remaster(?:ed|isé|isee|izado)?", "live" to "live|en direct|en vivo|dal vivo", "acoustic" to "acoustic|acoustique|acústico|acustico|akustik|unplugged",
        "remix" to "remix|rmx|rework|bootleg|flip", "instrumental" to "instrumental|instru", "radio" to "radio edit|radio version",
        "extended" to "extended|club mix|long version", "sped" to "sped up|speed up|nightcore", "slowed" to "slowed|reverb",
        "demo" to "demo", "karaoke" to "karaok[eé]", "cover" to "cover|reprise", "orchestral" to "orchestral|symphonic", "piano" to "piano version",
        "edit" to "edit", "version" to "version|versión|versione",
    ).map { (name, pattern) -> name to Regex("(?<![\\p{L}])(?:$pattern)(?![\\p{L}])", RegexOption.IGNORE_CASE) }

    /** The version marks of a title ("Live", "Remastered"…), sorted. */
    fun versionOf(title: String): List<String> = markers.filter { it.second.containsMatchIn(title) }.map { it.first }.distinct().sorted()

    /** A title without its version, guests and video words: "Song (feat. X) [Official Video] - Live" → "song". */
    fun baseTitle(title: String): String {
        var text = title.replace(noise, " ").replace(featuring, " ")
        text = text.replace(Regex("[(\\[][^)\\]]*[)\\]]"), " ")
        val dash = Regex("\\s[-–—]\\s(.*)$").find(text)
        // "Song - Live", "Song - 2011 Remaster": the end is a version; "Artist - Song" keeps its words.
        if (dash != null && (versionOf(dash.groupValues[1]).isNotEmpty() || Regex("\\d{4}").containsMatchIn(dash.groupValues[1]) &&
                dash.groupValues[1].length < 24)) text = text.substring(0, dash.range.first)
        return SmartText.words(text)
    }

    /** The first credited artist, without channel suffixes: "Artist - Topic", "ArtistVEVO". */
    fun baseArtist(artist: String): String {
        val first = artist.split(Regex(",|&|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bx\\b| et | and ", RegexOption.IGNORE_CASE)).first()
        return SmartText.words(first.replace(Regex("\\s*-\\s*topic$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("vevo$", RegexOption.IGNORE_CASE), "").replace(Regex("\\s+official$", RegexOption.IGNORE_CASE), ""))
    }

    fun sameLength(a: Long, b: Long): Boolean = a <= 0 || b <= 0 || kotlin.math.abs(a - b) <= maxOf(TOLERANCE_MS, maxOf(a, b) / 40)

    /** Every saved copy: the downloads and each entry of each playlist that has a title. */
    fun copies(downloads: List<SavedCopy>, playlists: List<LocalPlaylist>): List<SavedCopy> = downloads + playlists.flatMap { list ->
        list.entries.mapNotNull { entry -> entry.track?.let { SavedCopy(it, list.id, list.name, entry.id) } }
    }

    /**
     * Groups of copies that are one recording. A group is a duplicate when the recording is downloaded
     * twice, held twice by one playlist, or saved under two different titles of the catalogue.
     * Groups whose keys are in [kept] were reviewed and kept as they are.
     */
    fun find(copies: List<SavedCopy>, kept: Set<String> = emptySet()): List<DuplicateGroup> {
        val bySong = copies.filter { baseTitle(it.track.title).isNotBlank() }
            .groupBy { baseTitle(it.track.title) + "|" + baseArtist(it.track.artist) }
        return bySong.flatMap { (song, rows) -> byLength(rows).flatMap { cluster -> groupsOf(song, cluster) } }
            .filter { it.key !in kept }
            .sortedWith(compareByDescending<DuplicateGroup> { it.sameVersion }.thenBy { it.title.lowercase() })
    }

    /** Copies whose lengths agree, chained: two takes of a song minutes apart are not one recording. */
    private fun byLength(rows: List<SavedCopy>): List<List<SavedCopy>> {
        val sorted = rows.sortedBy { it.track.durationMs }
        val clusters = mutableListOf<MutableList<SavedCopy>>()
        for (row in sorted) {
            val last = clusters.lastOrNull()
            val joins = last != null && last.any { sameLength(it.track.durationMs, row.track.durationMs) }
            if (joins) last!!.add(row) else clusters += mutableListOf(row)
        }
        return clusters
    }

    private fun groupsOf(song: String, cluster: List<SavedCopy>): List<DuplicateGroup> {
        val byVersion = cluster.groupBy { versionOf(it.track.title).joinToString("+") }
        val exact = byVersion.mapNotNull { (version, rows) ->
            if (!isDuplicate(rows)) null else group("$song|$version", rows, true)
        }
        // Versions of one song (studio and live…) are offered apart, for the listener to decide.
        val versions = if (byVersion.size > 1) listOf(group("$song|*", cluster, false)) else emptyList()
        return exact + versions
    }

    private fun group(key: String, rows: List<SavedCopy>, same: Boolean): DuplicateGroup {
        val first = rows.first().track
        return DuplicateGroup(key, first.title, first.artist, rows, same)
    }

    private fun isDuplicate(rows: List<SavedCopy>): Boolean {
        if (rows.size < 2) return false
        if (rows.count { it.downloaded } >= 2) return true
        if (rows.filter { !it.downloaded }.groupBy { it.playlistId }.any { it.value.size >= 2 }) return true
        return rows.map { it.track.id }.distinct().size >= 2
    }

    /** The copy to keep by default: a download first (the best kept file), then the catalogue's own title. */
    fun preferred(group: DuplicateGroup): Track = group.copies.sortedWith(
        compareByDescending<SavedCopy> { it.downloaded }.thenByDescending { it.downloadBytes }
            .thenByDescending { it.track.addon == "um" }.thenByDescending { it.track.youtubeCatalogVerified }
    ).first().track

    /**
     * Merging [group] into [keep]: in every playlist the first entry stays and is pointed at [keep], the
     * others are removed; downloads of other titles are listed, to delete only once the listener confirms.
     */
    fun plan(group: DuplicateGroup, keep: Track): MergePlan {
        val entries = group.copies.filter { !it.downloaded }
        val remove = mutableListOf<Pair<String, String>>()
        val repoint = mutableListOf<Pair<String, String>>()
        entries.groupBy { it.playlistId!! }.forEach { (playlist, rows) ->
            val stays = rows.firstOrNull { it.track.id == keep.id } ?: rows.first()
            if (stays.track.id != keep.id) repoint += playlist to stays.entryId!!
            rows.filter { it !== stays }.forEach { remove += playlist to it.entryId!! }
        }
        val extra = group.downloads.filter { it.track.id != keep.id }
        return MergePlan(keep, remove, repoint, extra)
    }
}
