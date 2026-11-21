package com.ketvoice.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/*
 * Desktop counterparts of Android-only data objects, reduced to the members the shared sources call.
 * Each says what the desktop has today (see desktop/README.md).
 */

/**
 * Canvas videos. The Android CanvasRepo decodes posters with MediaMetadataRetriever and Bitmap and
 * watches ConnectivityManager; the desktop gets Canvas with the FFmpeg engine. Until then no Canvas
 * is stored, so there is none to find, drop or fetch.
 */
object CanvasRepo {
    internal const val POSTER_SUFFIX = ".poster.jpg"

    fun albumFile(album: String, artist: String): File? = null
    fun dropMissing() = Unit
    suspend fun resolve(track: Track, force: Boolean = false, ahead: Boolean = false): Unit = Unit
}

/** Music files on this computer. The folder scan (FFmpeg tags) is still to come; none are known yet. */
object LocalMusic {
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()
}

/** Downloads: the desktop downloader (`com.ketvoice.desktop.library.DesktopDownloader`) keeps them. */
object Downloader {
    fun localLyrics(trackId: String): Pair<String, List<MusicApi.LyricLine>>? =
        com.ketvoice.desktop.library.DesktopDownloader.localLyrics(trackId)
}
