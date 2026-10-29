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
    }

    /**
     * Ajoute du temps d'écoute réellement joué. Appelé par le lecteur à chaque
     * tic de position (≈200 ms) ; les sauts (seek, piste suivante) sont ignorés
     * en amont. Écriture disque limitée : flush dès que ≥ 15 s non persistées.
     */
    @Synchronized fun addListenedMs(deltaMs: Long) {
        if (deltaMs <= 0L) return
        _listenedMs.value = _listenedMs.value + deltaMs
        unflushedMs += deltaMs
        if (unflushedMs >= 15_000L) {
            prefs?.edit()?.putLong("listenedMs", _listenedMs.value)?.apply()
            unflushedMs = 0L
        }
    }

    /** Flush immédiat (arrêt du service, pause longue). */
    @Synchronized fun flushListenedMs() {
        if (unflushedMs > 0L) {
            prefs?.edit()?.putLong("listenedMs", _listenedMs.value)?.apply()
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
        prefs?.edit()?.putString("tracks", gson.toJson(_tracks.value))
            ?.putString("counts", gson.toJson(counts))?.apply()
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
}
