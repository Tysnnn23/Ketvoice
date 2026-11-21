package com.ketvoice.desktop.library

import com.ketvoice.app.data.Track
import com.ketvoice.desktop.platform.AppLog
import com.ketvoice.desktop.platform.DesktopPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI

/**
 * Audio files opened with Ketvoice (the MSI's file associations, « Ouvrir avec », a drop on Ketvoice.exe): each launch's
 * paths reach [DesktopPlatform.openRequests] (a later launch through the single instance), are read like local music and
 * played in the order given.
 */
object OpenFiles {
    fun tracks(paths: List<String>, covers: File?): List<Track> = paths.asSequence()
        .mapNotNull { p -> if (p.startsWith("file:")) runCatching { File(URI(p)) }.getOrNull() else File(p) }
        .filter { it.isFile && it.extension.lowercase() in LocalFolders.EXTENSIONS }
        .mapNotNull { runCatching { LocalFolders.read(it, covers).first }.getOrNull() }
        .toList()

    fun start(scope: CoroutineScope, covers: File?, play: (List<Track>) -> Unit): Job = scope.launch(Dispatchers.IO) {
        DesktopPlatform.openRequests.collect { paths ->
            val tracks = tracks(paths, covers)
            AppLog.i("open", "${paths.size} paths, ${tracks.size} audio files")
            if (tracks.isNotEmpty()) play(tracks)
        }
    }
}
