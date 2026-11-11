package com.ketvoice.app.ui.sub

import com.ketvoice.app.data.DownloadPhase
import com.ketvoice.app.data.DownloadTask
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.Track

internal data class DownloadSections(val saved: List<Downloader.Item>, val transfers: List<Track>)

internal fun downloadSections(saved: List<Downloader.Item>, active: Collection<Track>,
                              tasks: Map<String, DownloadTask>, query: String): DownloadSections {
    val savedIds = saved.mapTo(hashSetOf()) { it.track.id }
    val term = query.trim()
    fun matches(track: Track) = term.isEmpty() || track.title.contains(term, ignoreCase = true) ||
        track.artist.contains(term, ignoreCase = true) || track.album.contains(term, ignoreCase = true)
    return DownloadSections(saved.filter { matches(it.track) }, active.distinctBy { it.id }.filter {
        it.id !in savedIds && tasks[it.id]?.phase !in setOf(DownloadPhase.Complete, DownloadPhase.Cancelled) && matches(it)
    })
}
