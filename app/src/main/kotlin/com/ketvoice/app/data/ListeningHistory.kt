package com.ketvoice.app.data

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Actual successful playback, not demo data or merely a tap on a result. */
object ListeningHistory {
    private val gson = Gson()
    private var prefs: android.content.SharedPreferences? = null
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks
    private val _known = MutableStateFlow<List<Track>>(emptyList())
    val knownTracks: StateFlow<List<Track>> = _known
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences("listening-history", Context.MODE_PRIVATE)
        _tracks.value = runCatching { gson.fromJson(prefs!!.getString("tracks", "[]"), Array<Track>::class.java).toList() }
            .getOrDefault(emptyList())
        _known.value = runCatching { gson.fromJson(prefs!!.getString("known", "[]"), Array<Track>::class.java).toList() }
            .getOrDefault(_tracks.value)
    }
    @Synchronized fun rememberTrack(track: Track) {
        _known.value = (listOf(track.copy(streamUrl = null, downloadUrl = null)) + _known.value.filterNot { it.id == track.id }).take(400)
        prefs?.edit()?.putString("known", gson.toJson(_known.value))?.apply()
    }
    @Synchronized fun record(track: Track) {
        val saved = track.copy(streamUrl = null, downloadUrl = null)
        rememberTrack(saved)
        _tracks.value = recentWith(_tracks.value, saved)
        prefs?.edit()?.putString("tracks", gson.toJson(_tracks.value))?.apply()
    }
    fun recentWith(previous: List<Track>, track: Track): List<Track> =
        (listOf(track) + previous.filterNot {
            it.id == track.id || (it.title.equals(track.title, true) && it.artist.equals(track.artist, true))
        }).take(80)
}
