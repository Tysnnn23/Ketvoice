package com.ketvoice.app.data

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ListeningHistory {
    private val gson = Gson()
    private var prefs: android.content.SharedPreferences? = null
    // The `_…` flows hold this device's own listening (what it persists and publishes to the account); the public
    // flows are that plus what the account's other devices heard ([adoptOthers]), so every page shows the sum.
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    private val _allTracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _allTracks
    private val _known = MutableStateFlow<List<Track>>(emptyList())
    private val _allKnown = MutableStateFlow<List<Track>>(emptyList())
    val knownTracks: StateFlow<List<Track>> = _allKnown
    /** When each of this device's recent titles was heard (9.2), so recents of several devices interleave. */
    private var recentAt: Map<String, Long> = emptyMap()
    private val _ownChanges = MutableStateFlow(0L)
    /** Counts this device's own listening changes (not the other devices'): the account's sync follows it. */
    internal val ownChanges: StateFlow<Long> = _ownChanges
    /** The other devices of the account, as the last sync brought them. */
    private var others: List<DeviceListening> = emptyList()
    /**
     * When each title was last heard (7.1, « Radio sans répétition »), by id and by recording ([heardKeys]),
     * for the last [HEARD_KEEP_MS] only: the radio and autoplay leave out what was heard a few hours ago.
     */
    private val _heardAt = MutableStateFlow<Map<String, Long>>(emptyMap())
    val heardAt: StateFlow<Map<String, Long>> = _heardAt
    const val HEARD_KEEP_MS = 24 * 60 * 60 * 1000L
    private const val HEARD_MAX = 1_200

    /** The keys a hearing is remembered under: the id, and the recording (the same song under another id). */
    fun heardKeys(track: Track): List<String> =
        listOfNotNull(track.id.takeIf { it.isNotBlank() }, "r:" + Recordings.of(track))

    /** [previous] with [track] heard at [now], older than [HEARD_KEEP_MS] dropped and at most [HEARD_MAX] kept. */
    internal fun heardWith(previous: Map<String, Long>, track: Track?, now: Long): Map<String, Long> {
        val next = LinkedHashMap<String, Long>()
        previous.forEach { (key, at) -> if (now - at in 0 until HEARD_KEEP_MS) next[key] = at }
        track?.let { heardKeys(it).forEach { key -> next.remove(key); next[key] = now } }
        while (next.size > HEARD_MAX) next.remove(next.keys.first())
        return next
    }

    private val _playCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _allCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val playCounts: StateFlow<Map<String, Int>> = _allCounts

    // Temps d'écoute RÉEL : somme des positions réellement jouées (deltas du
    // lecteur), pas une estimation plays × durée. Persisté au fil de l'eau.
    private val _listenedMs = MutableStateFlow(0L)
    private val _allListened = MutableStateFlow(0L)
    val listenedMs: StateFlow<Long> = _allListened
    private var unflushedMs = 0L
    private val _days = MutableStateFlow<Map<String, DayStat>>(emptyMap())
    private val _allDays = MutableStateFlow<Map<String, DayStat>>(emptyMap())
    /** Listening of the last two weeks, per day: what "your week" is made of. */
    internal val days: StateFlow<Map<String, DayStat>> = _allDays
    /** Listening per calendar year (since 5.9.0), for Ketvoice Wrapped: time and plays per title, keyed "2026". */
    private val _years = MutableStateFlow<Map<String, DayStat>>(emptyMap())
    private val _allYears = MutableStateFlow<Map<String, DayStat>>(emptyMap())
    internal val years: StateFlow<Map<String, DayStat>> = _allYears
    private fun year() = java.time.LocalDate.now().year.toString()
    private fun today() = java.time.LocalDate.now()
    /** Listening per calendar month (6.1), for the monthly Replay; keyed "2026-11", one preference each. */
    private val _months = MutableStateFlow<Map<String, MonthStat>>(emptyMap())
    private val _allMonths = MutableStateFlow<Map<String, MonthStat>>(emptyMap())
    internal val months: StateFlow<Map<String, MonthStat>> = _allMonths
    /** Artists ever heard (normalised names): a first listen is a discovery of the month. */
    private var seenArtists = LinkedHashSet<String>()
    private const val MAX_SEEN_ARTISTS = 4000

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences("listening-history", Context.MODE_PRIVATE)
        TasteSignals.init(context)
        _tracks.value = runCatching { gson.fromJson(prefs!!.getString("tracks", "[]"), Array<Track>::class.java).toList() }
            .getOrDefault(emptyList())
        _known.value = runCatching { gson.fromJson(prefs!!.getString("known", "[]"), Array<Track>::class.java).toList() }
            .getOrDefault(_tracks.value)
        _playCounts.value = runCatching {
            val root = com.google.gson.JsonParser.parseString(prefs!!.getString("counts", "{}"))
            root.asJsonObject.entrySet().associate { (key, value) -> key to value.asInt }
        }.getOrDefault(emptyMap())
        _listenedMs.value = prefs!!.getLong("listenedMs", 0L)
        _heardAt.value = runCatching {
            val root = com.google.gson.JsonParser.parseString(prefs!!.getString("heardAt", "{}"))
            heardWith(root.asJsonObject.entrySet().associate { (key, value) -> key to value.asLong }, null, System.currentTimeMillis())
        }.getOrDefault(emptyMap())
        _days.value = ListeningWeek.trim(ListeningWeek.decode(prefs!!.getString("days", null)), today())
        _years.value = Wrapped.decode(prefs!!.getString("years", null))
        runCatching { loadMonths(prefs!!) }
        recentAt = runCatching {
            val root = com.google.gson.JsonParser.parseString(prefs!!.getString("recentAt", "{}"))
            root.asJsonObject.entrySet().associate { (key, value) -> key to value.asLong }
        }.getOrDefault(emptyMap())
        publish()
    }

    /** This device's own listening, as the account's `listening` document carries it (9.2). */
    @Synchronized internal fun own(): DeviceListening {
        val heard = _heardAt.value
        val recent = _tracks.value.mapNotNull { t ->
            ListeningShare.shareable(t)?.let { Heard(it, recentAt[t.id] ?: heard[t.id] ?: 0L) }
        }
        return DeviceListening(recent, _known.value.mapNotNull(ListeningShare::shareable), _playCounts.value,
            _listenedMs.value + pendingMs, _days.value, _years.value, _months.value)
    }

    internal val ownTracks: List<Track> get() = _tracks.value
    internal val ownKnown: List<Track> get() = _known.value
    internal val ownCounts: Map<String, Int> get() = _playCounts.value
    internal val ownListenedMs: Long get() = _listenedMs.value

    /** What the account's other devices heard, from the last sync; empty when signed out. */
    @Synchronized internal fun adoptOthers(devices: List<DeviceListening>) {
        if (devices == others) return
        others = devices
        publish(ownChange = false)
    }

    /** The public flows again: this device's own listening plus the others'. Called after every change. */
    private fun publish(refreshStats: Boolean = true, ownChange: Boolean = true) {
        if (ownChange) _ownChanges.value = _ownChanges.value + 1
        if (others.isEmpty()) {
            _allTracks.value = _tracks.value; _allKnown.value = _known.value; _allCounts.value = _playCounts.value
            _allListened.value = _listenedMs.value; _allDays.value = _days.value; _allYears.value = _years.value
            _allMonths.value = _months.value
        } else {
            val ownTracks = _tracks.value
            val heard = _heardAt.value
            val own = DeviceListening(ownTracks.map { Heard(it, recentAt[it.id] ?: heard[it.id] ?: 0L) }, _known.value,
                _playCounts.value, _listenedMs.value, _days.value, _years.value, _months.value)
            val all = ListeningShare.combine(own, others)
            _allTracks.value = all.recent.map { it.track }; _allKnown.value = all.known; _allCounts.value = all.counts
            _allListened.value = all.listenedMs; _allDays.value = ListeningWeek.trim(all.days, today())
            _allYears.value = all.years; _allMonths.value = all.months
        }
        if (refreshStats) runCatching { ListeningStats.refresh() }
    }

    private fun loadMonths(p: android.content.SharedPreferences) {
        val current = ListeningMonths.key(today())
        val loaded = mutableMapOf<String, MonthStat>()
        val edit = p.edit()
        p.all.keys.filter { it.startsWith(ListeningMonths.PREFIX) }.forEach { k ->
            val month = k.removePrefix(ListeningMonths.PREFIX)
            if (!ListeningMonths.isKey(month)) return@forEach
            val stat = ListeningMonths.decode(p.getString(k, null)) ?: return@forEach
            // A month that is over keeps only what its Replay shows.
            val kept = if (month < current) ListeningMonths.close(stat) else stat
            if (kept !== stat) edit.putString(k, ListeningMonths.encode(kept))
            loaded[month] = kept
        }
        edit.apply()
        _months.value = loaded
        // The first launch with monthly Replay: what was heard before is not a discovery.
        seenArtists = ListeningMonths.decodeSeen(p.getString("artistsSeen", null)) ?: LinkedHashSet<String>().also { seen ->
            _known.value.forEach { artistKey(it)?.let(seen::add) }
            p.edit().putString("artistsSeen", ListeningMonths.encodeSeen(seen)).apply()
        }
    }

    private fun artistKey(track: Track): String? =
        MediaOverrides.normalize(PersonalMixes.primaryArtist(track)).takeIf { it.isNotEmpty() && it !in PLACEHOLDER_ARTISTS }

    private val PLACEHOLDER_ARTISTS = setOf("artiste inconnu", "unknown artist", "unknown")

    private fun monthPrefKey() = ListeningMonths.PREFIX + ListeningMonths.key(today())
    private fun currentMonthJson(): String? = _months.value[ListeningMonths.key(today())]?.let(ListeningMonths::encode)

    /** A transition Ketmix really mixed, counted for the month's Replay and Wrapped. */
    @Synchronized fun noteKetmixMix() {
        _months.value = ListeningMonths.addMix(_months.value, today())
        _days.value = ListeningWeek.addMix(_days.value, today())
        prefs?.edit()?.putString(monthPrefKey(), currentMonthJson())?.putString("days", ListeningWeek.encode(_days.value))?.apply()
        publish()
    }

    /**
     * Ajoute du temps d'écoute réellement joué. Appelé par le lecteur à chaque
     * tic de position (≈200 ms) ; les sauts (seek, piste suivante) sont ignorés
     * en amont. Écriture disque limitée : flush dès que ≥ 15 s non persistées.
     */
    @Synchronized fun addListenedMs(deltaMs: Long) {
        if (deltaMs <= 0L) return
        pendingMs += deltaMs
        if (pendingMs >= PUBLISH_MS) publishListened()
    }

    /**
     * Time heard but not yet in the flows. The figures (week, month, year, total) move every [PUBLISH_MS] of
     * listening instead of at every 200 ms tick: each page showing them (Home's week and Replay cards, Replay,
     * Wrapped, the widgets) recomputed and recomposed five times a second while music played (6.6).
     */
    private var pendingMs = 0L
    const val PUBLISH_MS = 5_000L

    private fun publishListened() {
        val deltaMs = pendingMs
        if (deltaMs <= 0L) return
        pendingMs = 0L
        _listenedMs.value = _listenedMs.value + deltaMs
        val hour = java.time.LocalTime.now().hour
        _days.value = ListeningWeek.addTime(_days.value, today(), deltaMs, hour)
        _years.value = Wrapped.addTime(_years.value, year(), deltaMs)
        _months.value = ListeningMonths.addTime(_months.value, today(), hour, deltaMs)
        publish(refreshStats = false)
        unflushedMs += deltaMs
        if (unflushedMs >= 15_000L) persistListened()
    }

    private fun persistListened() {
        prefs?.edit()?.putLong("listenedMs", _listenedMs.value)?.putString("days", ListeningWeek.encode(_days.value))
            ?.putString("years", ListeningWeek.encode(_years.value))?.putString(monthPrefKey(), currentMonthJson())?.apply()
        unflushedMs = 0L
    }

    /** Flush immédiat (arrêt du service, pause longue). */
    @Synchronized fun flushListenedMs() {
        publishListened()
        if (unflushedMs > 0L) persistListened()
    }

    @Synchronized fun resetListenedMs() {
        _listenedMs.value = 0L
        unflushedMs = 0L
        pendingMs = 0L
        prefs?.edit()?.putLong("listenedMs", 0L)?.apply()
        publish()
    }
    @Synchronized fun rememberTrack(track: Track) {
        _known.value = (listOf(track.copy(streamUrl = null, downloadUrl = null)) + _known.value.filterNot { it.id == track.id }).take(400)
        prefs?.edit()?.putString("known", gson.toJson(_known.value))?.apply()
        publish()
    }
    @Synchronized fun record(track: Track) {
        val saved = track.copy(streamUrl = null, downloadUrl = null)
        rememberTrack(saved)
        _tracks.value = recentWith(_tracks.value, saved)
        recentAt = (recentAt.filterKeys { id -> _tracks.value.any { it.id == id } } + (track.id to System.currentTimeMillis()))
        val counts = _playCounts.value.toMutableMap()
        counts[track.id] = (counts[track.id] ?: 0) + 1
        if (counts.size > 550) {
            counts.entries.sortedByDescending { it.value }.take(500).let { top ->
                counts.clear()
                top.forEach { counts[it.key] = it.value }
            }
        }
        _playCounts.value = counts
        _heardAt.value = heardWith(_heardAt.value, saved, System.currentTimeMillis())
        // The first title ever heard of an artist makes it a discovery of the month.
        val newArtist = artistKey(saved)?.takeIf { prefs != null && seenArtists.add(it) }
            ?.let { PersonalMixes.primaryArtist(saved) }
        if (seenArtists.size > MAX_SEEN_ARTISTS) seenArtists.iterator().let { it.next(); it.remove() }
        _days.value = ListeningWeek.addPlay(_days.value, today(), track.id, newArtist != null)
        _years.value = Wrapped.addPlay(_years.value, year(), track.id)
        _months.value = ListeningMonths.addPlay(_months.value, today(), saved, newArtist)
        prefs?.edit()?.putString("tracks", gson.toJson(_tracks.value))?.putString("recentAt", gson.toJson(recentAt))
            ?.putString("counts", gson.toJson(counts))?.putString("heardAt", gson.toJson(_heardAt.value))?.putString("days", ListeningWeek.encode(_days.value))
            ?.putString("years", ListeningWeek.encode(_years.value))?.putString(monthPrefKey(), currentMonthJson())
            ?.also { if (newArtist != null) it.putString("artistsSeen", ListeningMonths.encodeSeen(seenArtists)) }?.apply()
        // Keep the recap projection warm so the stats screen opens instantly (publish refreshes it).
        publish()
    }
    @Synchronized fun replacePublication(track: Track) {
        if (ImportedSong.id(track) == null) return
        val safe = track.copy(streamUrl = null, downloadUrl = null)
        _tracks.value = _tracks.value.map { if (it.id == track.id) safe else it }
        _known.value = _known.value.map { if (it.id == track.id) safe else it }
        prefs?.edit()?.putString("tracks", gson.toJson(_tracks.value))?.putString("known", gson.toJson(_known.value))?.apply()
        publish()
    }
    fun recentWith(previous: List<Track>, track: Track): List<Track> =
        (listOf(track) + previous.filterNot {
            it.id == track.id || (it.title.equals(track.title, true) && it.artist.equals(track.artist, true))
        }).take(80)

    /** Import is idempotent and keeps existing entries/counts when they conflict. */
    @Synchronized internal fun mergeBackup(recent: List<Track>, known: List<Track>, counts: Map<String, Int>, listenedMs: Long) {
        val p = prefs ?: error("Listening history unavailable")
        // Do not replace unreadable existing history with an apparently empty import.
        for (key in listOf("tracks", "known")) {
            p.getString(key, null)?.let { requireNotNull(gson.fromJson(it, Array<Track>::class.java)) }
        }
        p.getString("counts", null)?.let {
            val root = com.google.gson.JsonParser.parseString(it)
            require(root.isJsonObject && root.asJsonObject.entrySet().all { entry ->
                runCatching { entry.value.asInt >= 0 }.getOrDefault(false)
            })
        }
        val existingRecent = _tracks.value.map { it.id }.toSet()
        val nextRecent = _tracks.value + recent.filterNot { it.id in existingRecent }.distinctBy { it.id }
            .take((80 - _tracks.value.size).coerceAtLeast(0))
        val existingKnown = _known.value.map { it.id }.toSet()
        val nextKnown = _known.value + (known + nextRecent).filterNot { it.id in existingKnown }.distinctBy { it.id }
            .take((400 - _known.value.size).coerceAtLeast(0))
        val nextCounts = _playCounts.value + counts.filterKeys { it !in _playCounts.value }.entries
            .take((550 - _playCounts.value.size).coerceAtLeast(0)).associate { it.key to it.value }
        val nextTime = maxOf(_listenedMs.value, listenedMs)
        check(p.edit().putString("tracks", gson.toJson(nextRecent)).putString("known", gson.toJson(nextKnown))
            .putString("counts", gson.toJson(nextCounts)).putLong("listenedMs", nextTime).commit())
        _tracks.value = nextRecent
        _known.value = nextKnown
        _playCounts.value = nextCounts
        _listenedMs.value = nextTime
        unflushedMs = 0L
        publish()
    }

    /**
     * Air Ket and backups (7.0): the days, years and months of a backup join this device's, a day or month kept
     * under the larger time heard when both have it. [stored] is the preferences text of each (`days`, `years`, `month:…`).
     */
    @Synchronized internal fun mergeStats(stored: Map<String, String>) {
        val p = prefs ?: return
        fun <T> union(a: Map<String, T>, b: Map<String, T>, ms: (T) -> Long): Map<String, T> =
            (a.keys + b.keys).associateWith { k -> val x = a[k]; val y = b[k]; if (x == null) y!! else if (y == null) x else if (ms(y) > ms(x)) y else x }
        val days = stored["days"]?.let { ListeningWeek.decode(it) }.orEmpty()
        val years = stored["years"]?.let { Wrapped.decode(it) }.orEmpty()
        val months = stored.filterKeys { it.startsWith(ListeningMonths.PREFIX) }.mapNotNull { (key, text) ->
            ListeningMonths.decode(text)?.let { key.removePrefix(ListeningMonths.PREFIX) to it }
        }.toMap()
        _days.value = ListeningWeek.trim(union(_days.value, days) { it.ms }, today())
        _years.value = union(_years.value, years) { it.ms }
        _months.value = union(_months.value, months) { it.ms }
        val edit = p.edit().putString("days", ListeningWeek.encode(_days.value)).putString("years", ListeningWeek.encode(_years.value))
        _months.value.forEach { (month, stat) -> edit.putString(ListeningMonths.PREFIX + month, ListeningMonths.encode(stat)) }
        edit.commit()
        publish()
    }
}
