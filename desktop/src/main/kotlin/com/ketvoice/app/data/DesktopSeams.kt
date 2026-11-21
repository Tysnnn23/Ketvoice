package com.ketvoice.app.data

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/*
 * Desktop counterparts of Android-only data objects, reduced to the members the shared sources call.
 * Each says what the desktop has today (see desktop/README.md). Canvas is CanvasRepo.kt (FFmpeg).
 */

/** Music files on this computer: the folders chosen in Settings, scanned with FFmpeg ([com.ketvoice.desktop.library.LocalFolders]). */
object LocalMusic {
    val tracks: StateFlow<List<Track>> get() = com.ketvoice.desktop.library.LocalFolders.tracks
}

/** Downloads: the desktop downloader (`com.ketvoice.desktop.library.DesktopDownloader`) keeps them. */
object Downloader {
    /** The saved files (each with its [com.ketvoice.desktop.library.DesktopDownloader.Item.track]), as the account sync looks titles up. */
    val items: StateFlow<List<com.ketvoice.desktop.library.DesktopDownloader.Item>> get() = com.ketvoice.desktop.library.DesktopDownloader.items

    fun localLyrics(trackId: String): Pair<String, List<MusicApi.LyricLine>>? =
        com.ketvoice.desktop.library.DesktopDownloader.localLyrics(trackId)
}
