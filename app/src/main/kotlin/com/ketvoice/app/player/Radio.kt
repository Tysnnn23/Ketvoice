package com.ketvoice.app.player

import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.WeeklyDiscoveries

/**
 * What a radio remembers while it plays (6.1): every title and recording it has queued, so a
 * title never comes back in the session — even once removed from the queue — and the artists of
 * the last titles, so an artist is not heard again too soon.
 */
internal class RadioMemory(val seed: RadioSeed) {
    private val ids = LinkedHashSet<String>()
    private val recordings = LinkedHashSet<String>()
    private val artists = ArrayDeque<String>()

    fun remember(track: Track) {
        ids += track.id
        recordings += WeeklyDiscoveries.recording(track)
        artists.addLast(RadioMix.artistKey(track))
        while (artists.size > RadioMix.ARTIST_MEMORY) artists.removeFirst()
        if (ids.size > LIMIT) ids.remove(ids.first())
        if (recordings.size > LIMIT) recordings.remove(recordings.first())
    }

    fun rememberAll(tracks: List<Track>) = tracks.forEach(::remember)
    fun played(track: Track): Boolean = track.id in ids || WeeklyDiscoveries.recording(track) in recordings
    /** Artists of the last titles, the most recent last. */
    val lastArtists: List<String> get() = artists.toList()
    val size: Int get() = ids.size

    private companion object { const val LIMIT = 800 }
}

/** Where a radio starts: an artist (« Radio de l'artiste ») or a title (« Lancer la radio »). */
internal sealed class RadioSeed {
    abstract val artist: String
    data class Artist(override val artist: String, val catalogId: String?) : RadioSeed()
    data class Title(val track: Track) : RadioSeed() { override val artist: String get() = MediaOverrides.mainArtist(track.artist) }
}

/**
 * How a radio's next titles are chosen: titles of artists like the seed, titles of the seed's own
 * artist, and some of the listener's favourites of that circle, in a set ratio; never a title or a
 * recording already heard in the session, never a blocked artist, and never an artist again
 * before [ARTIST_GAP] other titles have played.
 */
internal object RadioMix {
    const val BATCH = 10
    /** An artist comes back only after this many titles of other artists. */
    const val ARTIST_GAP = 3
    /** Artists remembered for spacing. */
    const val ARTIST_MEMORY = 8
    /** One title in [FAMILIAR_EVERY] is one the listener already loves (when there is one of the circle). */
    const val FAMILIAR_EVERY = 4
    /** One fresh title in [OWN_EVERY] is by the seed's own artist. */
    const val OWN_EVERY = 4

    fun artistKey(track: Track): String = MediaOverrides.normalize(MediaOverrides.mainArtist(track.artist))

    /** The 5.x entry point, kept for its callers: a memory built from the queue. */
    fun next(similar: List<Track>, own: List<Track>, queue: List<Track>, allows: (Track) -> Boolean, seed: Long): List<Track> {
        val memory = RadioMemory(RadioSeed.Title(queue.firstOrNull() ?: Track("", "", "", ""))).apply { rememberAll(queue) }
        return pick(similar, own, emptyList(), memory, allows, kotlin.random.Random(seed))
    }

    /**
     * The next [batch] titles. [fresh] are titles of other artists, [own] of the seed's artist,
     * [familiar] titles the listener already knows well. The picked titles are added to [memory].
     */
    fun pick(fresh: List<Track>, own: List<Track>, familiar: List<Track>, memory: RadioMemory, allows: (Track) -> Boolean,
             random: kotlin.random.Random, batch: Int = BATCH, lately: (Track) -> Long? = { null }): List<Track> {
        // 7.1: what was heard in the last hours stays out ([NoRepeatWindow]); it comes back below only if too little is left.
        val heardLately = (fresh + own + familiar).filter { lately(it) != null }
        val pools = listOf(fresh, own, familiar).map { pool -> pool.filter { lately(it) == null }.shuffled(random).toMutableList() }
        val recent = ArrayDeque(memory.lastArtists)
        val out = ArrayList<Track>()
        fun usable(t: Track) = t.id.isNotBlank() && !memory.played(t) && allows(t)
        pools.forEach { pool -> pool.removeAll { !usable(it) } }

        /** The first title of [pool] whose artist was not heard within [gap] titles. */
        fun take(pool: MutableList<Track>, gap: Int): Track? {
            val blocked = recent.toList().takeLast(gap).toSet()
            val index = pool.indexOfFirst { artistKey(it) !in blocked && !memory.played(it) }
            return if (index >= 0) pool.removeAt(index) else null
        }

        var slot = memory.size
        var freshCount = 0
        while (out.size < batch && pools.any { it.isNotEmpty() }) {
            slot++
            // The slot's kind first, then any other kind, strict spacing first; a looser gap only when nothing else is left.
            val wanted = when {
                slot % FAMILIAR_EVERY == 0 -> listOf(2, 0, 1)
                (freshCount + 1) % OWN_EVERY == 0 -> listOf(1, 0, 2)
                else -> listOf(0, 1, 2)
            }
            val picked = wanted.firstNotNullOfOrNull { take(pools[it], ARTIST_GAP) }
                ?: wanted.firstNotNullOfOrNull { take(pools[it], 1) }
                // Nothing but the same artist left (a radio the catalogue knows nothing around): its other titles, never one twice.
                ?: wanted.firstNotNullOfOrNull { take(pools[it], 0) }
                ?: break
            if (picked !in familiar) freshCount++
            memory.remember(picked)
            recent.addLast(artistKey(picked))
            while (recent.size > ARTIST_MEMORY) recent.removeFirst()
            out += picked
        }
        // Too few titles not heard lately: those heard longest ago fill up to the minimum, never one twice.
        if (out.size < minOf(batch, NoRepeatWindow.MINIMUM)) for (t in NoRepeatWindow.ordered(heardLately, lately).distinctBy { it.id }) {
            if (out.size >= minOf(batch, NoRepeatWindow.MINIMUM)) break
            if (!usable(t)) continue
            memory.remember(t)
            out += t
        }
        return out
    }
}
