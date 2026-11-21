package com.ketvoice.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/*
 * Desktop counterparts of three Android-only data objects, reduced to the members the shared
 * sources call. Each says what the desktop has today; Phase 1 replaces them (see desktop/README.md).
 */

/**
 * Canvas videos. The Android CanvasRepo decodes posters with MediaMetadataRetriever and Bitmap and
 * watches ConnectivityManager; the desktop gets Canvas with the FFmpeg engine in Phase 1. Until
 * then no Canvas is stored, so there is none to find, drop or fetch.
 */
object CanvasRepo {
    internal const val POSTER_SUFFIX = ".poster.jpg"

    fun albumFile(album: String, artist: String): File? = null
    fun dropMissing() = Unit
    suspend fun resolve(track: Track, force: Boolean = false, ahead: Boolean = false): Unit = Unit
}

/** Music files on this computer. The folder scan (FFmpeg tags) arrives in Phase 1; none are known yet. */
object LocalMusic {
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()
}

/** Downloads. The desktop downloader arrives in Phase 1; with nothing downloaded there are no saved lyrics. */
object Downloader {
    fun localLyrics(trackId: String): Pair<String, List<MusicApi.LyricLine>>? = null
}
