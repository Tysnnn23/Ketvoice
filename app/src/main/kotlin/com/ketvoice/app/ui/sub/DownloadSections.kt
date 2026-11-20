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
    // 7.5: accents and case aside, every word (as in a playlist and Favoris).
    fun matches(track: Track) = term.isEmpty() || com.ketvoice.app.data.ListOrders.matches(term, track.title, track.artist, track.album)
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

/** How the saved titles are ordered. "Recent" uses the file date, the only download date kept. */
/** The sorts of Téléchargements (7.4, the shared list sorts since 7.5): the newest file first by default. */
internal val DownloadSorts = com.ketvoice.app.data.ListSort.entries
internal val DefaultDownloadOrder = com.ketvoice.app.data.ListOrder(com.ketvoice.app.data.ListSort.Added)

internal fun sortDownloads(items: List<Downloader.Item>, order: com.ketvoice.app.data.ListOrder): List<Downloader.Item> {
    // A file's date is read once per sort, not at each comparison.
    val dates = if (order.sort == com.ketvoice.app.data.ListSort.Added) items.associate { it.track.id to it.file.lastModified() } else emptyMap()
    return com.ketvoice.app.data.ListOrders.arrange(items, "", order, com.ketvoice.app.data.ListFields({ it.track.title },
        { it.track.artist }, { it.track.album }, { it.track.durationMs }, { dates[it.track.id] ?: 0L }, { it.bytes }))
}

internal fun isLosslessFormat(format: String): Boolean =
    listOf("FLAC", "WAV", "ALAC", "AIFF").any { format.contains(it, ignoreCase = true) }

/** What the summary card shows: how much is saved, how long it plays and how much of it is lossless. */
internal data class DownloadSummary(val count: Int, val bytes: Long, val durationMs: Long, val lossless: Int,
                                    val losslessBytes: Long, val artists: Int) {
    /** Share of the saved bytes that is lossless, 0..1. */
    val losslessShare: Float get() = if (bytes <= 0L) 0f else (losslessBytes.toFloat() / bytes).coerceIn(0f, 1f)
}

internal fun downloadSummary(items: List<Downloader.Item>): DownloadSummary {
    val lossless = items.filter { isLosslessFormat(it.format) }
    return DownloadSummary(items.size, items.sumOf { it.bytes.coerceAtLeast(0L) }, items.sumOf { it.track.durationMs.coerceAtLeast(0L) },
        lossless.size, lossless.sumOf { it.bytes.coerceAtLeast(0L) },
        items.map { it.track.artist.substringBefore(',').trim().lowercase() }.filter { it.isNotBlank() }.distinct().size)
}

internal data class DownloadArtist(val key: String, val name: String, val items: List<Downloader.Item>) {
    val bytes: Long get() = items.sumOf { it.bytes }
}

/** Saved titles grouped by their first credited artist, largest collection first. */
internal fun downloadArtists(items: List<Downloader.Item>): List<DownloadArtist> = items
    .filter { it.track.artist.substringBefore(',').isNotBlank() }
    .groupBy { it.track.artist.substringBefore(',').trim().lowercase() }
    .map { (key, group) -> DownloadArtist(key, group.first().track.artist.substringBefore(',').trim(), group) }
    .sortedWith(compareByDescending<DownloadArtist> { it.items.size }.thenBy { it.key })

/** "3 h 12 min", "47 min" or "" when unknown. */
internal fun playtimeLabel(durationMs: Long): String {
    val minutes = durationMs / 60_000L
    return when {
        minutes <= 0L -> ""
        minutes < 60L -> "$minutes min"
        else -> "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} min"
    }
}

/** Short codec label for a stored format such as "FLAC · Sans perte" or "DASH · FLAC". */
internal fun formatBadge(format: String): String =
    listOf("FLAC", "WAV", "ALAC", "AIFF").firstOrNull { format.contains(it, ignoreCase = true) }
        ?: format.substringBefore(" ·").trim().take(10).ifBlank { "?" }
