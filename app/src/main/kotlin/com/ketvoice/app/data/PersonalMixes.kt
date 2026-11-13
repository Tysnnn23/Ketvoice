package com.ketvoice.app.data

import kotlin.random.Random

enum class MixKind { Artist, Style, OnRepeat, Rediscover }

/** A proposed mix: enough to draw its card; its tracks are built when it is opened. */
data class MixCard(val id: String, val kind: MixKind, val artist: String?, val covers: List<String?>)

/**
 * Personal mixes derived from the listening history on this device. Nothing is
 * proposed before the listener has played enough music for a proposal to mean
 * something, and the selection rotates between launches, never while browsing.
 *
 * Everything here is computed locally; only opening an artist or style mix asks
 * the public catalogue for tracks to add to what the listener already knows.
 */
object PersonalMixes {
    /** Plays recorded before any mix is proposed. */
    const val MIN_TOTAL_PLAYS = 10
    /** Plays of one artist before that artist gets a mix. */
    const val MIN_ARTIST_PLAYS = 3
    const val MAX_CARDS = 4
    private const val MIX_SIZE = 30

    /** One seed per process: a launch reshuffles the proposals, navigation does not. */
    val sessionSeed: Long = System.nanoTime()

    data class ArtistTaste(val name: String, val plays: Int, val tracks: List<Track>)

    internal fun primaryArtist(track: Track): String =
        track.artist.substringBefore(',').substringBefore(" feat.").trim()

    private fun recording(track: Track) = "${track.title.trim().lowercase()}|${primaryArtist(track).lowercase()}"

    /** Artists ordered by how much they were played; a favourite counts as one more play. */
    internal fun topArtists(known: List<Track>, counts: Map<String, Int>, favorites: Set<String>): List<ArtistTaste> =
        known.filter { primaryArtist(it).isNotBlank() }
            .groupBy { primaryArtist(it).lowercase() }
            .map { (_, tracks) ->
                val ordered = tracks.sortedByDescending { counts[it.id] ?: 0 }
                ArtistTaste(primaryArtist(ordered.first()),
                    tracks.sumOf { (counts[it.id] ?: 0) + if (it.id in favorites) 1 else 0 }, ordered)
            }
            .filter { it.plays > 0 }
            .sortedWith(compareByDescending<ArtistTaste> { it.plays }.thenBy { it.name.lowercase() })

    /** Tracks played at least three times, most played first. */
    internal fun onRepeat(known: List<Track>, counts: Map<String, Int>): List<Track> =
        known.filter { (counts[it.id] ?: 0) >= 3 }.sortedByDescending { counts[it.id] ?: 0 }.take(MIX_SIZE)

    /** Tracks the listener played before but not among the thirty most recent ones. */
    internal fun rediscover(known: List<Track>, recent: List<Track>, counts: Map<String, Int>, seed: Long): List<Track> {
        val fresh = recent.take(30).mapTo(hashSetOf()) { it.id }
        return known.filter { (counts[it.id] ?: 0) >= 1 && it.id !in fresh }.shuffled(Random(seed)).take(MIX_SIZE)
    }

    /**
     * The cards to show on Home, at most [limit]. Empty until [MIN_TOTAL_PLAYS] plays.
     * The most played artist always keeps a mix; the style mix and the second artist
     * rotate with [seed] among the next favourites.
     */
    fun propose(known: List<Track>, recent: List<Track>, counts: Map<String, Int>, favorites: Set<String>,
                seed: Long = sessionSeed, limit: Int = MAX_CARDS): List<MixCard> {
        if (counts.values.sum() < MIN_TOTAL_PLAYS) return emptyList()
        val random = Random(seed)
        fun covers(tracks: List<Track>) = tracks.map { it.coverUrl }.filter { !it.isNullOrBlank() }.distinct().take(4)
        val artists = topArtists(known, counts, favorites).filter { it.plays >= MIN_ARTIST_PLAYS }.take(5)
        val cards = mutableListOf<MixCard>()
        var spare: ArtistTaste? = null
        if (artists.isNotEmpty()) {
            val lead = artists.first()
            cards += MixCard("artist:${lead.name}", MixKind.Artist, lead.name, covers(lead.tracks))
            val others = artists.drop(1).shuffled(random)
            val style = others.firstOrNull() ?: lead
            cards += MixCard("style:${style.name}", MixKind.Style, style.name, covers(style.tracks))
            spare = others.getOrNull(1)
        }
        onRepeat(known, counts).takeIf { it.size >= 5 }?.let { cards += MixCard("repeat", MixKind.OnRepeat, null, covers(it)) }
        rediscover(known, recent, counts, seed).takeIf { it.size >= 8 }
            ?.let { cards += MixCard("rediscover", MixKind.Rediscover, null, covers(it)) }
        spare?.let { cards += MixCard("artist:${it.name}", MixKind.Artist, it.name, covers(it.tracks)) }
        return cards.take(limit.coerceAtLeast(0))
    }

    /**
     * Known tracks first, then two catalogue tracks for each further known one, without
     * repeating a recording. A mix is therefore familiar at the start and new further on.
     */
    internal fun blend(local: List<Track>, remote: List<Track>, limit: Int = MIX_SIZE): List<Track> {
        val seen = hashSetOf<String>()
        val result = mutableListOf<Track>()
        fun add(track: Track) { if (seen.add(recording(track)) && result.size < limit) result += track }
        val mine = local.iterator()
        val theirs = remote.iterator()
        repeat(3) { if (mine.hasNext()) add(mine.next()) }
        while (result.size < limit && (mine.hasNext() || theirs.hasNext())) {
            repeat(2) { if (theirs.hasNext()) add(theirs.next()) }
            if (mine.hasNext()) add(mine.next())
        }
        return result
    }

    /** Tracks of a mix, or null when it needs the catalogue and the catalogue did not answer. */
    suspend fun tracks(id: String, seed: Long = sessionSeed): List<Track>? {
        val known = ListeningHistory.knownTracks.value
        val counts = ListeningHistory.playCounts.value
        val name = id.substringAfter(':', "")
        return when (id.substringBefore(':')) {
            "repeat" -> onRepeat(known, counts)
            "rediscover" -> rediscover(known, ListeningHistory.tracks.value, counts, seed)
            "artist" -> {
                val mine = known.filter { primaryArtist(it).equals(name, ignoreCase = true) }
                    .sortedByDescending { counts[it.id] ?: 0 }
                blend(mine, CatalogRepo.artistTop(name)).ifEmpty { null }
            }
            "style" -> CatalogRepo.artistRadio(name).take(MIX_SIZE).ifEmpty { null }
            else -> null
        }
    }
}
