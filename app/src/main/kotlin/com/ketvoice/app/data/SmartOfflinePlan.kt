package com.ketvoice.app.data

import java.time.LocalDate

/**
 * « Hors ligne intelligent » (7.0), the pure part: which titles deserve a place on the device, in which order,
 * what fits under the listener's limit and which automatic downloads make room. Nothing here touches files,
 * the network or Android: [SmartOffline] gathers the signals and carries the plan out.
 *
 * Rules the plan never breaks: a title downloaded by hand is never removed (only automatic downloads are passed
 * in [Owned.auto]), a title the listener deleted by hand is never proposed again, a protected title (playing,
 * being sent by Air Ket…) is never removed, and the automatic downloads stay under the limit.
 */
internal object SmartOfflinePlan {
    /** Why a title is wanted offline; the strongest reason is the one shown. */
    enum class Reason { Favorite, Played, Playlist, Discovery }

    data class Candidate(val track: Track, val score: Double, val reason: Reason)

    /** A title already on the device. [auto]: downloaded by Ketvoice on its own, so it may make room. */
    data class Owned(val id: String, val bytes: Long, val auto: Boolean, val plays: Int, val downloadedAt: Long)

    /** What the listener does, as the planner reads it. */
    data class Signals(
        /** Favourites with the time Ketvoice first saw them (0 when they were there before the feature). */
        val favorites: List<Pair<Track, Long>> = emptyList(),
        /** Plays of the last 30 days, per title id. */
        val recentPlays: Map<String, Int> = emptyMap(),
        /** Titles of each of the listener's playlists. */
        val playlists: List<List<Track>> = emptyList(),
        /** The week's discoveries, in their order, and the ids the listener has already played. */
        val discoveries: List<Track> = emptyList(),
        val played: Set<String> = emptySet(),
        /** Title objects for the ids of [recentPlays]. */
        val known: Map<String, Track> = emptyMap(),
    )

    data class Plan(val download: List<Candidate>, val evict: List<String>, val autoBytesAfter: Long)

    const val NEW_FAVORITE_DAYS = 30L
    private const val DAY_MS = 24L * 60 * 60 * 1000
    const val TOP_PLAYLISTS = 3
    const val PLAYLIST_TITLES = 50
    const val DISCOVERIES_AHEAD = 5
    /** At most this many new titles per pass: a pass stays short and gentle on the battery. */
    const val MAX_NEW_PER_RUN = 25
    /** Room always left free on the device, whatever the limit says. */
    const val KEEP_FREE_BYTES = 1_000_000_000L

    // ------------------------------------------------------------------ ranking

    /** Every wanted title once, the most wanted first. Ids in [excluded] (deleted by hand, downloaded by hand) are left out. */
    fun rank(signals: Signals, excluded: Set<String>, now: Long): List<Candidate> {
        val parts = HashMap<String, MutableList<Pair<Double, Reason>>>()
        val tracks = HashMap<String, Track>()
        fun add(track: Track, score: Double, reason: Reason) {
            if (!offlineable(track) || track.id in excluded) return
            tracks.putIfAbsent(track.id, track)
            parts.getOrPut(track.id) { mutableListOf() } += score to reason
        }
        favoriteScores(signals, now).forEach { (t, s) -> add(t, s, Reason.Favorite) }
        playedScores(signals).forEach { (t, s) -> add(t, s, Reason.Played) }
        playlistScores(signals).forEach { (t, s) -> add(t, s, Reason.Playlist) }
        discoveryScores(signals).forEach { (t, s) -> add(t, s, Reason.Discovery) }
        return parts.map { (id, list) -> combine(tracks.getValue(id), list) }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.track.id })
    }

    /** The strongest reason, plus a quarter of the others: a favourite played often comes before a favourite alone. */
    private fun combine(track: Track, list: List<Pair<Double, Reason>>): Candidate {
        val best = list.maxBy { it.first }
        return Candidate(track, best.first + (list.sumOf { it.first } - best.first) / 4, best.second)
    }

    /** Catalogue titles only: local files are already on the device, a title without an id cannot be fetched. */
    fun offlineable(track: Track): Boolean = track.id.isNotBlank() && !track.id.startsWith("local:") &&
        track.title.isNotBlank()

    /** A favourite added in the last 30 days: 100, up to 120 for one added today. */
    private fun favoriteScores(s: Signals, now: Long): List<Pair<Track, Double>> = s.favorites.mapNotNull { (track, seen) ->
        val age = now - seen
        if (seen <= 0L || age < 0 || age > NEW_FAVORITE_DAYS * DAY_MS) null
        else track to 100.0 + 20.0 * (1.0 - age.toDouble() / (NEW_FAVORITE_DAYS * DAY_MS))
    }

    /** Titles heard at least twice in 30 days: 30 + 12 per play, up to 20 plays. */
    private fun playedScores(s: Signals): List<Pair<Track, Double>> = s.recentPlays.mapNotNull { (id, plays) ->
        val track = s.known[id] ?: return@mapNotNull null
        if (plays < 2) null else track to 30.0 + 12.0 * plays.coerceAtMost(20)
    }

    /** The three playlists heard most (their titles' plays in 30 days), their first 50 titles: 25, 20 or 15, plus 2 per play. */
    private fun playlistScores(s: Signals): List<Pair<Track, Double>> =
        topPlaylists(s.playlists, s.recentPlays).flatMapIndexed { rank, list ->
            list.take(PLAYLIST_TITLES).map { it to 25.0 - 5.0 * rank + 2.0 * (s.recentPlays[it.id] ?: 0).coerceAtMost(10) }
        }

    internal fun topPlaylists(playlists: List<List<Track>>, plays: Map<String, Int>): List<List<Track>> = playlists
        .map { list -> list to list.sumOf { plays[it.id] ?: 0 } }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .take(TOP_PLAYLISTS).map { it.first }

    /** The week's discoveries started: the next five titles after the furthest one heard, 35 then 3 less each. */
    private fun discoveryScores(s: Signals): List<Pair<Track, Double>> {
        val heard = s.discoveries.indexOfLast { it.id in s.played || (s.recentPlays[it.id] ?: 0) > 0 }
        if (heard < 0) return emptyList()
        return s.discoveries.drop(heard + 1).filter { it.id !in s.played }.take(DISCOVERIES_AHEAD)
            .mapIndexed { k, t -> t to 35.0 - 3.0 * k }
    }

    // ------------------------------------------------------------------ the plan

    /**
     * What to download and what to remove so that the automatic downloads hold the best-ranked titles that fit
     * under [limitBytes]. An automatic download outside the wanted set stays as long as there is room; when room
     * is needed, the least played go first (then the oldest). [estimate] is the expected size of a new title.
     */
    fun plan(ranked: List<Candidate>, owned: List<Owned>, limitBytes: Long, protected: Set<String>,
             estimate: (Track) -> Long, maxNew: Int = MAX_NEW_PER_RUN): Plan {
        val auto = owned.filter { it.auto }.associateBy { it.id }
        val manual = owned.filterNot { it.auto }.mapTo(HashSet()) { it.id }
        val (kept, wanted) = choose(ranked.filter { it.track.id !in manual }, auto, limitBytes, estimate, maxNew)
        var total = auto.values.sumOf { it.bytes } + wanted.sumOf { estimate(it.track) }
        val evict = ArrayList<String>()
        for (item in evictionOrder(auto.values, kept, protected)) {
            if (total <= limitBytes) break
            evict += item.id; total -= item.bytes
        }
        // Protected titles may hold the room: the lowest-ranked new titles wait for a later pass.
        val download = wanted.toMutableList()
        while (total > limitBytes && download.isNotEmpty()) total -= estimate(download.removeAt(download.lastIndex).track)
        return Plan(download, evict, total)
    }

    /** The wanted titles that fit, in rank order: ids already downloaded automatically, and new ones to fetch. */
    private fun choose(ranked: List<Candidate>, auto: Map<String, Owned>, limit: Long, estimate: (Track) -> Long,
                       maxNew: Int): Pair<Set<String>, List<Candidate>> {
        var room = limit
        val kept = HashSet<String>()
        val wanted = ArrayList<Candidate>()
        for (c in ranked) {
            val have = auto[c.track.id]
            val size = have?.bytes ?: estimate(c.track)
            if (size > room || (have == null && wanted.size >= maxNew)) continue
            room -= size
            if (have != null) kept += have.id else wanted += c
        }
        return kept to wanted
    }

    /** Automatic downloads that may go, least played first, then the oldest. */
    fun evictionOrder(auto: Collection<Owned>, kept: Set<String>, protected: Set<String>): List<Owned> = auto
        .filter { it.auto && it.id !in kept && it.id !in protected }
        .sortedWith(compareBy<Owned> { it.plays }.thenBy { it.downloadedAt }.thenBy { it.id })

    // ------------------------------------------------------------------ limits and sizes

    /** The limit choices: 1, 2, 5 or 10 GB, or a share of the free storage. */
    val GIGABYTES = listOf(1, 2, 5, 10)
    val PERCENTS = listOf(10, 25, 50)
    const val DEFAULT_GB = 2
    const val DEFAULT_PERCENT = 25

    fun clampGb(gb: Int): Int = if (gb == 0 || gb in GIGABYTES) gb else DEFAULT_GB
    fun clampPercent(p: Int): Int = if (p in PERCENTS) p else DEFAULT_PERCENT

    /**
     * The room the automatic downloads may take. [gb] 0 means « at most [percent] % of the free storage », counted
     * with the automatic downloads already there (they are free storage this feature may use again). Whatever the
     * choice, [KEEP_FREE_BYTES] stay free on the device.
     */
    fun limitBytes(gb: Int, percent: Int, freeBytes: Long, autoBytes: Long): Long {
        val usable = (freeBytes + autoBytes).coerceAtLeast(0L)
        val chosen = if (gb > 0) gb * 1_000_000_000L else usable / 100 * clampPercent(percent)
        return minOf(chosen, (usable - KEEP_FREE_BYTES).coerceAtLeast(0L))
    }

    /** Expected size of a title in the download quality: FLAC ~1000 kb/s, high ~256 kb/s, quick ~160 kb/s, plus lyrics and cover. */
    fun estimate(track: Track, qualityMode: Int): Long {
        val seconds = (track.durationMs.takeIf { it > 0 } ?: 210_000L) / 1000
        val bytesPerSecond = when (qualityMode) { 0 -> 125_000L; 1 -> 32_000L; else -> 20_000L }
        return seconds * bytesPerSecond + 200_000L
    }

    /** Plays of the last 30 days from the monthly counters: this month, and the part of last month still inside the window. */
    fun recentPlays(months: Map<String, MonthStat>, today: LocalDate): Map<String, Int> {
        val out = HashMap<String, Int>()
        months[ListeningMonths.key(today)]?.plays?.forEach { (id, n) -> out[id] = (out[id] ?: 0) + n }
        val before = today.minusMonths(1)
        val share = ((30 - today.dayOfMonth).coerceAtLeast(0)).toDouble() / before.lengthOfMonth()
        if (share > 0) months[ListeningMonths.key(before)]?.plays?.forEach { (id, n) ->
            val part = Math.round(n * share).toInt()
            if (part > 0) out[id] = (out[id] ?: 0) + part
        }
        return out
    }
}
