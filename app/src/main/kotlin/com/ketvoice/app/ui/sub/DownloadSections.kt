package com.ketvoice.app.ui.sub

import com.ketvoice.app.data.DownloadPhase
import com.ketvoice.app.data.DownloadTask
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.Track

internal data class DownloadSections(val saved: List<Downloader.Item>, val transfers: List<Track>)
internal enum class DownloadFilter(val label: String) {
    All("Tout"), Offline("Disponibles hors ligne"), Active("Téléchargement en cours"), Paused("En pause"), Failed("Échec du téléchargement")
}

internal fun downloadSections(saved: List<Downloader.Item>, active: Collection<Track>,
                              tasks: Map<String, DownloadTask>, query: String, filter: DownloadFilter = DownloadFilter.All): DownloadSections {
    val savedIds = saved.mapTo(hashSetOf()) { it.track.id }
    val term = query.trim()
    fun matches(track: Track) = term.isEmpty() || track.title.contains(term, ignoreCase = true) ||
        track.artist.contains(term, ignoreCase = true) || track.album.contains(term, ignoreCase = true)
    return DownloadSections(saved.filter { (filter == DownloadFilter.All || filter == DownloadFilter.Offline) && matches(it.track) }, active.distinctBy { it.id }.filter {
        val phase = tasks[it.id]?.phase
        val included = when (filter) {
            DownloadFilter.All -> true
            DownloadFilter.Offline -> false
            DownloadFilter.Paused -> phase == DownloadPhase.Paused
            DownloadFilter.Failed -> phase == DownloadPhase.Failed
            DownloadFilter.Active -> phase != DownloadPhase.Paused && phase != DownloadPhase.Failed
        }
        included && it.id !in savedIds && phase !in setOf(DownloadPhase.Complete, DownloadPhase.Cancelled) && matches(it)
    })
}
