package com.ketvoice.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * « Découvertes de la semaine » (6.1): about thirty titles the listener has not heard, chosen from
 * what they listen to — artists close to their favourites of the last weeks, the style radio of
 * those favourites, and titles of their artists they never played. A new selection is made every
 * Monday (local time) and kept on the device for the whole week, whatever is played meanwhile.
 *
 * Left out: blocked artists, every title of the library (favourites, downloads, playlists, files
 * on the phone), titles heard more than once or in the last two weeks, and second versions of
 * the same recording.
 */
internal object WeeklyDiscoveries {
    const val SIZE = 30
    /** Plays recorded before a selection is made: below this there is nothing to build from. */
    const val MIN_PLAYS = PersonalMixes.MIN_TOTAL_PLAYS
    /** At most this many titles of one artist in a week's selection. */
    const val PER_ARTIST = 2
    /** Titles of the listener's own artists they never played, among the [SIZE]. */
    const val KNOWN_ARTIST_SHARE = 8
    /** Seed artists asked for close artists, and close artists kept per seed. */
    private const val SEEDS = 5
    private const val RELATED_PER_SEED = 4

    /** The Monday that starts the week of [date]: the selection's key. */
    fun weekOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    data class Week(val monday: LocalDate, val tracks: List<Track>, val madeAt: Long)

    /** What a selection is built from: the listening on this device and the library to leave alone. */
    data class Taste(
        val known: List<Track>,
        val counts: Map<String, Int>,
        /** Plays per day of the last two weeks (see ListeningWeek). */
        val days: Map<String, DayStat>,
        val favorites: Set<String>,
        /** Titles the listener keeps: downloads, playlist entries, files on the phone. */
        val library: List<Track>,
        /** Recordings kept by name only (playlist entries without a track). */
        val libraryNames: List<Pair<String, String>> = emptyList(),
        val allows: (Track) -> Boolean = { true },
    ) {
        val totalPlays: Int get() = counts.values.sum()
    }

    /** Where candidates come from. The app reads the public catalogue and the search sources; tests give fixed answers. */
    interface Sources {
        /** Artists the catalogue lists as close to [name]. */
        suspend fun related(name: String, catalogId: String?): List<CatalogArtist>
        /** The most played titles of an artist. */
        suspend fun top(name: String, catalogId: String?): List<Track>
        /** The catalogue's radio of an artist's style: mostly other artists. */
        suspend fun radio(name: String, catalogId: String?): List<Track>
        /** The search sources (YouTube Music, publications, addons) for listeners the catalogue does not know. */
        suspend fun search(query: String): List<Track>
    }

    /** A recording, whatever its source: its plain title without versions and its main artist. */
    fun recording(title: String, artist: String): String {
        val plain = title.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), " ")
            .replace(Regex("\\s+-\\s+.*(remaster|version|edit|live|mix|radio|clip|video|audio|lyrics|paroles).*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE), "")
        return MediaOverrides.normalize(plain) + "|" + MediaOverrides.normalize(MediaOverrides.mainArtist(artist))
    }
    fun recording(track: Track): String = recording(track.title, track.artist)

    private fun artistKey(name: String) = MediaOverrides.normalize(MediaOverrides.mainArtist(name))

    /**
     * Builds the selection of the week starting [monday]. The same taste and the same answers
     * always give the same selection: candidates are ordered by a rank seeded with the week.
     */
    suspend fun build(taste: Taste, sources: Sources, monday: LocalDate): List<Track> {
        if (taste.totalPlays < MIN_PLAYS) return emptyList()
        val seed = monday.toEpochDay() * 0x9E3779B97F4A7C15uL.toLong()
        val recentIds = taste.days.values.flatMap { it.plays.keys }.toSet()
        // Heard: more than once, or lately. A title heard once long ago may come back as a discovery.
        val heard = taste.known.filter { (taste.counts[it.id] ?: 0) >= 2 || it.id in recentIds || it.id in taste.favorites }
        val excludedIds = HashSet<String>().apply {
            heard.forEach { add(it.id) }; taste.favorites.forEach(::add); taste.library.forEach { add(it.id) }
            taste.counts.filterValues { it >= 2 }.keys.forEach(::add); addAll(recentIds)
        }
        val excludedRecordings = HashSet<String>().apply {
            heard.forEach { add(recording(it)) }; taste.library.forEach { add(recording(it)) }
            taste.libraryNames.forEach { (title, artist) -> add(recording(title, artist)) }
        }

        // Seeds: the artists of the last two weeks first, then those of all time.
        val byId = taste.known.associateBy { it.id }
        val lately = mutableMapOf<String, Int>()
        taste.days.values.forEach { day -> day.plays.forEach { (id, n) ->
            byId[id]?.let { t -> PersonalMixes.primaryArtist(t).takeIf(String::isNotBlank)?.let { lately[it.lowercase()] = (lately[it.lowercase()] ?: 0) + n } }
        } }
        val tastes = PersonalMixes.topArtists(taste.known, taste.counts, taste.favorites).filter { taste.allows(it.tracks.first()) }
        val seeds = tastes.sortedWith(compareByDescending<PersonalMixes.ArtistTaste> { lately[it.name.lowercase()] ?: 0 }
            .thenByDescending { it.plays }.thenBy { it.name.lowercase() }).take(SEEDS)
        if (seeds.isEmpty()) return emptyList()
        // An artist played three times or more is one of the listener's: their titles are not "new artists".
        val familiarArtists = tastes.filter { it.plays >= PersonalMixes.MIN_ARTIST_PLAYS }.mapTo(HashSet()) { artistKey(it.name) }

        val fresh = mutableListOf<Track>()      // artists the listener does not know yet
        val deeper = mutableListOf<Track>()     // unheard titles of their own artists
        for (artist in seeds) {
            val related = runCatching { sources.related(artist.name, artist.catalogId) }.getOrDefault(emptyList())
                .filter { artistKey(it.name) !in familiarArtists && taste.allows(Track("", "", it.name, "")) }
                .sortedBy { PersonalMixes.seededRank(it.name.lowercase(), seed) }
                .take(RELATED_PER_SEED)
            for (other in related) fresh += runCatching { sources.top(other.name, other.id) }.getOrDefault(emptyList()).take(5)
            fresh += runCatching { sources.radio(artist.name, artist.catalogId) }.getOrDefault(emptyList())
            deeper += runCatching { sources.top(artist.name, artist.catalogId) }.getOrDefault(emptyList())
        }
        // Listeners of titles the catalogue does not know (YouTube, publications, files): the search sources.
        if (fresh.size + deeper.size < SIZE) for (artist in seeds.take(3)) {
            runCatching { sources.search(artist.name) }.getOrDefault(emptyList()).forEach { t ->
                if (artistKey(t.artist) in familiarArtists) deeper += t else fresh += t
            }
        }

        fun usable(t: Track) = t.id !in excludedIds && recording(t) !in excludedRecordings && taste.allows(t) &&
            t.title.isNotBlank() && t.artist.isNotBlank()
        val newOnes = fresh.filter { usable(it) && artistKey(it.artist) !in familiarArtists }
            .sortedBy { PersonalMixes.seededRank(it.id, seed) }
        val ownOnes = (deeper + fresh).filter { usable(it) && artistKey(it.artist) in familiarArtists }
            .sortedBy { PersonalMixes.seededRank(it.id, seed) }
        return compose(newOnes, ownOnes, SIZE)
    }

    /**
     * Fills [size] places: mostly new artists, [KNOWN_ARTIST_SHARE] titles of known ones spread
     * through them, never two versions of one recording, at most [PER_ARTIST] titles of an artist,
     * and never the same artist twice in a row.
     */
    internal fun compose(newOnes: List<Track>, ownOnes: List<Track>, size: Int): List<Track> {
        val recordings = HashSet<String>()
        val perArtist = HashMap<String, Int>()
        fun pick(pool: List<Track>, cap: Int): List<Track> {
            val out = mutableListOf<Track>()
            for (t in pool) {
                if (out.size >= cap) break
                val a = artistKey(t.artist)
                if ((perArtist[a] ?: 0) >= PER_ARTIST || !recordings.add(recording(t))) continue
                perArtist[a] = (perArtist[a] ?: 0) + 1
                out += t
            }
            return out
        }
        val own = pick(ownOnes, KNOWN_ARTIST_SHARE)
        val new = pick(newOnes, size - own.size)
        val more = if (new.size + own.size < size) pick(ownOnes.filterNot { it in own }, size - new.size - own.size) else emptyList()
        // One known artist after every few new ones.
        val step = if (own.isEmpty()) Int.MAX_VALUE else ((new.size + own.size) / (own.size + more.size).coerceAtLeast(1)).coerceAtLeast(2)
        val mixed = mutableListOf<Track>()
        val ownIt = (own + more).iterator(); val newIt = new.iterator()
        while (mixed.size < size && (ownIt.hasNext() || newIt.hasNext())) {
            if ((mixed.size + 1) % step == 0 && ownIt.hasNext() || !newIt.hasNext()) mixed += ownIt.next() else mixed += newIt.next()
        }
        return spaced(mixed)
    }

    /** Moves titles so one artist never follows itself, when another order allows it. */
    internal fun spaced(tracks: List<Track>): List<Track> {
        val rest = tracks.toMutableList()
        val out = mutableListOf<Track>()
        while (rest.isNotEmpty()) {
            val last = out.lastOrNull()?.let { artistKey(it.artist) }
            val index = rest.indexOfFirst { artistKey(it.artist) != last }.takeIf { it >= 0 } ?: 0
            out += rest.removeAt(index)
        }
        return out
    }

    // ------------------------------------------------------------ Storage

    /** Where the week's selection is kept. */
    interface Store {
        fun load(): Week?
        fun save(week: Week)
    }

    fun encode(week: Week): String = JSONObject().put("monday", week.monday.toString()).put("at", week.madeAt)
        .put("tracks", JSONArray().apply { week.tracks.forEach { put(ListeningMonths.trackJson(it)) } }).toString()

    fun decode(text: String?): Week? = runCatching {
        val o = JSONObject(text ?: return null)
        val array = o.optJSONArray("tracks") ?: JSONArray()
        Week(LocalDate.parse(o.getString("monday")), (0 until array.length()).mapNotNull { ListeningMonths.track(array.optJSONObject(it)) },
            o.optLong("at"))
    }.getOrNull()

    private class PrefsStore(context: Context) : Store {
        private val prefs = context.applicationContext.getSharedPreferences("weekly-discoveries", Context.MODE_PRIVATE)
        override fun load(): Week? = decode(prefs.getString("week", null))
        override fun save(week: Week) { prefs.edit().putString("week", encode(week)).apply() }
    }

    private val _week = MutableStateFlow<Week?>(null)
    /** The selection kept on the device (possibly of an earlier week: see [current]). */
    val week: StateFlow<Week?> = _week
    private val _building = MutableStateFlow(false)
    val building: StateFlow<Boolean> = _building
    private var store: Store? = null
    private val lock = Mutex()

    fun init(context: Context) {
        if (store != null) return
        useStore(PrefsStore(context))
    }

    internal fun useStore(s: Store?) {
        store = s
        _week.value = s?.load()
    }

    /** The selection of the week of [today], or null when it has not been made yet. */
    fun current(today: LocalDate = LocalDate.now()): Week? = _week.value?.takeIf { it.monday == weekOf(today) && it.tracks.isNotEmpty() }

    /**
     * Makes the week's selection when it is missing (a new Monday, a first time), and keeps it
     * otherwise: within a week the selection never changes. Returns the week's selection, or
     * null when there is not enough listening or nothing could be found.
     */
    suspend fun ensure(taste: Taste, sources: Sources, today: LocalDate = LocalDate.now(), clock: () -> Long = System::currentTimeMillis): Week? =
        lock.withLock {
            current(today)?.let { return@withLock it }
            if (taste.totalPlays < MIN_PLAYS) return@withLock null
            _building.value = true
            try {
                val monday = weekOf(today)
                val tracks = build(taste, sources, monday)
                if (tracks.size < 5) return@withLock null
                Week(monday, tracks.map(ListeningMonths::compact), clock()).also { week ->
                    store?.save(week)
                    _week.value = week
                }
            } finally { _building.value = false }
        }

    /** The card shown on Home and in the car, when this week's selection exists. */
    fun card(today: LocalDate = LocalDate.now()): MixCard? = current(today)?.let { week ->
        MixCard(MIX_ID, MixKind.Discover, null, week.tracks.mapNotNull { it.coverUrl }.distinct().take(4))
    }

    const val MIX_ID = "discover"

    /** The name a saved copy of the week's selection takes: « Découvertes · 16 nov. ». */
    fun playlistName(monday: LocalDate, locale: java.util.Locale): String =
        "${AppText.t("Découvertes")} · ${monday.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", locale))}".take(80)

    // ------------------------------------------------------------ The app's taste and sources

    /** What this device knows of the listener, read from the stores. */
    fun appTaste(): Taste {
        val playlists = PlaylistStore.lists.value
        return Taste(
            known = ListeningHistory.knownTracks.value,
            counts = ListeningHistory.playCounts.value,
            days = ListeningHistory.days.value,
            favorites = AppSettings.favoriteIds.value,
            library = Downloader.items.value.map { it.track } + LocalMusic.tracks.value + playlists.flatMap { list -> list.entries.mapNotNull { it.track } },
            libraryNames = playlists.flatMap { list -> list.entries.filter { it.track == null }.map { it.title to it.artist } },
            allows = ArtistPrefs::allows,
        )
    }

    /** The public catalogue first, the search sources for the rest. */
    object AppSources : Sources {
        override suspend fun related(name: String, catalogId: String?) = CatalogRepo.relatedArtists(name, catalogId)
        override suspend fun top(name: String, catalogId: String?) = CatalogRepo.artistTop(name, catalogId)
        override suspend fun radio(name: String, catalogId: String?) = CatalogRepo.artistRadio(name, catalogId)
        override suspend fun search(query: String): List<Track> =
            runCatching { MusicApi.searchYtMusic(query) }.getOrDefault(emptyList()) +
                runCatching { PublicationApi.search(query) }.getOrDefault(emptyList()) +
                runCatching { MusicApi.searchAddons(query) }.getOrDefault(emptyList())
    }

    /** On demand (Home, the car, the mix page): this week's selection, made now if it is missing. */
    suspend fun ensureNow(): Week? = runCatching { ensure(appTaste(), AppSources) }.getOrNull()
}
