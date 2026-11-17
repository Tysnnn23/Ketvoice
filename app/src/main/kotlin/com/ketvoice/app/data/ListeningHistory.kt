package com.ketvoice.app.data

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ListeningHistory {
    private val gson = Gson()
    private var prefs: android.content.SharedPreferences? = null
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks
    private val _known = MutableStateFlow<List<Track>>(emptyList())
    val knownTracks: StateFlow<List<Track>> = _known
    private val _playCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val playCounts: StateFlow<Map<String, Int>> = _playCounts

    // Temps d'écoute RÉEL : somme des positions réellement jouées (deltas du
    // lecteur), pas une estimation plays × durée. Persisté au fil de l'eau.
    private val _listenedMs = MutableStateFlow(0L)
    val listenedMs: StateFlow<Long> = _listenedMs
    private var unflushedMs = 0L
    private val _days = MutableStateFlow<Map<String, DayStat>>(emptyMap())
    /** Listening of the last two weeks, per day: what "your week" is made of. */
    internal val days: StateFlow<Map<String, DayStat>> = _days
    /** Listening per calendar year (since 5.9.0), for Ketvoice Wrapped: time and plays per title, keyed "2026". */
    private val _years = MutableStateFlow<Map<String, DayStat>>(emptyMap())
    internal val years: StateFlow<Map<String, DayStat>> = _years
    private fun year() = java.time.LocalDate.now().year.toString()
    private fun today() = java.time.LocalDate.now()
    /** Listening per calendar month (6.1), for the monthly Replay; keyed "2026-11", one preference each. */
    private val _months = MutableStateFlow<Map<String, MonthStat>>(emptyMap())
    internal val months: StateFlow<Map<String, MonthStat>> = _months
    /** Artists ever heard (normalised names): a first listen is a discovery of the month. */
    private var seenArtists = LinkedHashSet<String>()
    private const val MAX_SEEN_ARTISTS = 4000

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences("listening-history", Context.MODE_PRIVATE)
        _tracks.value = runCatching { gson.fromJson(prefs!!.getString("tracks", "[]"), Array<Track>::class.java).toList() }
            .getOrDefault(emptyList())
        _known.value = runCatching { gson.fromJson(prefs!!.getString("known", "[]"), Array<Track>::class.java).toList() }
            .getOrDefault(_tracks.value)
        _playCounts.value = runCatching {
            val root = com.google.gson.JsonParser.parseString(prefs!!.getString("counts", "{}"))
            root.asJsonObject.entrySet().associate { (key, value) -> key to value.asInt }
        }.getOrDefault(emptyMap())
        _listenedMs.value = prefs!!.getLong("listenedMs", 0L)
        _days.value = ListeningWeek.trim(ListeningWeek.decode(prefs!!.getString("days", null)), today())
        _years.value = Wrapped.decode(prefs!!.getString("years", null))
        runCatching { loadMonths(prefs!!) }
        runCatching { ListeningStats.refresh() }
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
        prefs?.edit()?.putString(monthPrefKey(), currentMonthJson())?.apply()
    }

    /**
     * Ajoute du temps d'écoute réellement joué. Appelé par le lecteur à chaque
     * tic de position (≈200 ms) ; les sauts (seek, piste suivante) sont ignorés
     * en amont. Écriture disque limitée : flush dès que ≥ 15 s non persistées.
     */
    @Synchronized fun addListenedMs(deltaMs: Long) {
        if (deltaMs <= 0L) return
        _listenedMs.value = _listenedMs.value + deltaMs
        _days.value = ListeningWeek.addTime(_days.value, today(), deltaMs)
        _years.value = Wrapped.addTime(_years.value, year(), deltaMs)
        _months.value = ListeningMonths.addTime(_months.value, today(), java.time.LocalTime.now().hour, deltaMs)
        unflushedMs += deltaMs
        if (unflushedMs >= 15_000L) {
            prefs?.edit()?.putLong("listenedMs", _listenedMs.value)?.putString("days", ListeningWeek.encode(_days.value))
                ?.putString("years", ListeningWeek.encode(_years.value))?.putString(monthPrefKey(), currentMonthJson())?.apply()
            unflushedMs = 0L
        }
    }

    /** Flush immédiat (arrêt du service, pause longue). */
    @Synchronized fun flushListenedMs() {
        if (unflushedMs > 0L) {
            prefs?.edit()?.putLong("listenedMs", _listenedMs.value)?.putString("days", ListeningWeek.encode(_days.value))
                ?.putString("years", ListeningWeek.encode(_years.value))?.putString(monthPrefKey(), currentMonthJson())?.apply()
            unflushedMs = 0L
        }
    }

    @Synchronized fun resetListenedMs() {
        _listenedMs.value = 0L
        unflushedMs = 0L
        prefs?.edit()?.putLong("listenedMs", 0L)?.apply()
    }
    @Synchronized fun rememberTrack(track: Track) {
        _known.value = (listOf(track.copy(streamUrl = null, downloadUrl = null)) + _known.value.filterNot { it.id == track.id }).take(400)
        prefs?.edit()?.putString("known", gson.toJson(_known.value))?.apply()
    }
    @Synchronized fun record(track: Track) {
        val saved = track.copy(streamUrl = null, downloadUrl = null)
        rememberTrack(saved)
        _tracks.value = recentWith(_tracks.value, saved)
        val counts = _playCounts.value.toMutableMap()
        counts[track.id] = (counts[track.id] ?: 0) + 1
        if (counts.size > 550) {
            counts.entries.sortedByDescending { it.value }.take(500).let { top ->
                counts.clear()
                top.forEach { counts[it.key] = it.value }
            }
        }
        _playCounts.value = counts
        _days.value = ListeningWeek.addPlay(_days.value, today(), track.id)
        _years.value = Wrapped.addPlay(_years.value, year(), track.id)
        // The first title ever heard of an artist makes it a discovery of the month.
        val newArtist = artistKey(saved)?.takeIf { prefs != null && seenArtists.add(it) }
            ?.let { PersonalMixes.primaryArtist(saved) }
        if (seenArtists.size > MAX_SEEN_ARTISTS) seenArtists.iterator().let { it.next(); it.remove() }
        _months.value = ListeningMonths.addPlay(_months.value, today(), saved, newArtist)
        prefs?.edit()?.putString("tracks", gson.toJson(_tracks.value))
            ?.putString("counts", gson.toJson(counts))?.putString("days", ListeningWeek.encode(_days.value))
            ?.putString("years", ListeningWeek.encode(_years.value))?.putString(monthPrefKey(), currentMonthJson())
            ?.also { if (newArtist != null) it.putString("artistsSeen", ListeningMonths.encodeSeen(seenArtists)) }?.apply()
        // Keep the recap projection warm so the stats screen opens instantly.
        runCatching { ListeningStats.refresh() }
    }
    @Synchronized fun replacePublication(track: Track) {
        if (ImportedSong.id(track) == null) return
        val safe = track.copy(streamUrl = null, downloadUrl = null)
        _tracks.value = _tracks.value.map { if (it.id == track.id) safe else it }
        _known.value = _known.value.map { if (it.id == track.id) safe else it }
        prefs?.edit()?.putString("tracks", gson.toJson(_tracks.value))?.putString("known", gson.toJson(_known.value))?.apply()
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
        runCatching { ListeningStats.refresh() }
    }
}
