package com.ketvoice.app.smartlib

import com.ketvoice.app.data.Track

/** A generated ambiance: its mood and its titles, best first. */
data class MoodMix(val mood: Mood, val tracks: List<Track>) {
    val id: String get() = "mood:${mood.name}"
}

/**
 * Playlists by ambiance, built from what the listener has (downloads, playlists, files, history).
 * Titles that fit best come first; a title played often is lifted a little; [seed] reshuffles the
 * titles of near equal fit, so that refreshing gives another selection of the same ambiance.
 */
object MoodMixes {
    const val MIN_TRACKS = 6
    const val MAX_TRACKS = 40

    fun build(pool: List<Track>, facts: Map<String, TrackFacts>, plays: Map<String, Int>, seed: Long): List<MoodMix> =
        Mood.entries.mapNotNull { mood -> mix(mood, pool, facts, plays, seed) }

    fun mix(mood: Mood, pool: List<Track>, facts: Map<String, TrackFacts>, plays: Map<String, Int>, seed: Long): MoodMix? {
        val scored = pool.distinctBy { it.id }.mapNotNull { track ->
            val score = facts[track.id]?.let { MoodScore.score(mood, it) } ?: return@mapNotNull null
            if (score < SmartFilter.MOOD_YES) null else track to rankOf(track, score, plays, seed)
        }
        if (scored.size < MIN_TRACKS) return null
        val ordered = scored.sortedByDescending { it.second }.map { it.first }
        return MoodMix(mood, spreadArtists(ordered).take(MAX_TRACKS))
    }

    private fun rankOf(track: Track, score: Float, plays: Map<String, Int>, seed: Long): Float {
        val played = kotlin.math.ln(1f + (plays[track.id] ?: 0)) * .04f
        return score + played + jitter(track.id, seed) * .18f
    }

    /** A number in 0..1 that depends only on the title and the seed. */
    internal fun jitter(id: String, seed: Long): Float {
        var z = id.hashCode().toLong() * -0x61c8864680b583ebL + seed
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return ((z ushr 11).toDouble() / (1L shl 53).toDouble()).toFloat()
    }

    /** No artist twice in a row while another can come between. */
    internal fun spreadArtists(tracks: List<Track>): List<Track> {
        val left = tracks.toMutableList()
        val out = ArrayList<Track>(tracks.size)
        while (left.isNotEmpty()) {
            val previous = out.lastOrNull()?.let { SmartText.words(it.artist) }
            val index = left.indexOfFirst { SmartText.words(it.artist) != previous }.takeIf { it >= 0 } ?: 0
            out += left.removeAt(index)
        }
        return out
    }
}
