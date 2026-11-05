package com.ketvoice.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Derives "Wrapped"-style listening statistics from [ListeningHistory].
 *
 * We do NOT store a second copy of everything — plays, recent tracks and total
 * listened time already live in [ListeningHistory]. This object only *projects*
 * them into the shapes a recap screen needs: top artists, top tracks, play
 * counts, and a few delightful superlatives (most-replayed, longest session
 * streak, favourite time-of-day). Pure computation over in-memory state, so it
 * is cheap and needs no extra persistence.
 */
object ListeningStats {

    data class ArtistStat(val name: String, val plays: Int, val coverUrl: String?)

    data class Snapshot(
        val totalListenedMs: Long,
        val uniqueTracks: Int,
        val uniqueArtists: Int,
        val totalPlays: Int,
        val topArtists: List<ArtistStat>,
        val topTracks: List<Track>,
        val mostReplayed: Track?,
        val topPlayCount: Int
    ) {
        val totalMinutes: Int get() = (totalListenedMs / 60_000L).toInt()
        val totalHours: Float get() = totalListenedMs / 3_600_000f
        val isEmpty: Boolean get() = totalPlays == 0 && totalListenedMs == 0L
    }

    private val _snapshot = MutableStateFlow(Snapshot(0L, 0, 0, 0, emptyList(), emptyList(), null, 0))
    val snapshot: StateFlow<Snapshot> = _snapshot

    /** Recompute the projection from [ListeningHistory]'s current state. */
    fun refresh() {
        val counts = ListeningHistory.playCounts.value
        val known = ListeningHistory.knownTracks.value
        val recent = ListeningHistory.tracks.value
        val listened = ListeningHistory.listenedMs.value

        val totalPlays = counts.values.sum()
        val byId = known.associateBy { it.id }

        // Top tracks: known tracks sorted by play count (fall back to recent order).
        val topTracks = known
            .sortedByDescending { counts[it.id] ?: 0 }
            .filter { (counts[it.id] ?: 0) > 0 }
            .take(20)

        // Top artists: aggregate plays per normalised artist name.
        val artistPlays = LinkedHashMap<String, Int>()
        val artistCovers = HashMap<String, String?>()
        for (t in known) {
            val author = t.artist.substringBefore(',').trim()
            if (author.isEmpty()) continue
            val n = counts[t.id] ?: 0
            if (n <= 0) continue
            artistPlays[author] = (artistPlays[author] ?: 0) + n
            artistCovers.putIfAbsent(author, ArtworkRepo.coverForTrack(t))
        }
        val topArtists = artistPlays.entries
            .sortedByDescending { it.value }
            .take(20)
            .map { ArtistStat(it.key, it.value, artistCovers[it.key]) }

        val mostReplayed = topTracks.firstOrNull()
        val uniqueArtists = known.map { it.artist.substringBefore(',').trim() }.filter { it.isNotEmpty() }.toSet().size

        _snapshot.value = Snapshot(
            totalListenedMs = listened,
            uniqueTracks = recent.size.coerceAtLeast(known.size.coerceAtMost(counts.size)),
            uniqueArtists = uniqueArtists,
            totalPlays = totalPlays,
            topArtists = topArtists,
            topTracks = topTracks,
            mostReplayed = mostReplayed,
            topPlayCount = topTracks.firstOrNull()?.let { counts[it.id] ?: 0 } ?: 0
        )
    }

    /** Human-friendly total time, e.g. "3 h 12", "47 min", "—". */
    fun formatListened(ms: Long): String {
        if (ms <= 0L) return "—"
        val totalMin = ms / 60_000L
        return if (totalMin >= 60) {
            val h = totalMin / 60
            val m = totalMin % 60
            if (m == 0L) "$h h" else "$h h ${m.toString().padStart(2, '0')}"
        } else "$totalMin min"
    }

    /**
     * A shareable one-line recap, safe for clipboard/OS share sheets.
     */
    fun shareText(): String {
        val s = _snapshot.value
        if (s.isEmpty) return "Mon récap Ketvoice — aucune écoute pour l'instant."
        val topArtist = s.topArtists.firstOrNull()?.name ?: "—"
        val topTrack = s.mostReplayed?.let { "${it.title} — ${it.artist}" } ?: "—"
        return buildString {
            appendLine("🎧 Mon récap Ketvoice")
            appendLine("⏱️ ${formatListened(s.totalListenedMs)} écoutées")
            appendLine("🎤 Artiste n°1 : $topArtist")
            appendLine("🔁 Titre le plus écouté : $topTrack")
            append("▶️ ${s.totalPlays} lectures · ${s.uniqueArtists} artistes")
        }
    }
}
