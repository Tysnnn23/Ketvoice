package com.ketvoice.app.ui.sub

import com.ketvoice.app.data.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DownloadSectionsTest {
    private fun track(id: String) = Track(id, "Title $id", "Artist $id", "Album $id")
    private fun item(id: String) = Downloader.Item(track(id), File("$id.flac"), "FLAC", 1024)

    @Test fun savedFilesNeverAppearInTheTransferSection() {
        val sections = downloadSections(listOf(item("a")), listOf(track("a"), track("b")), emptyMap(), "")
        assertEquals(listOf("a"), sections.saved.map { it.track.id })
        assertEquals(listOf("b"), sections.transfers.map { it.id })
    }

    @Test fun cancelledAndCompletedRowsDisappearButFailedAndPausedCanBeManaged() {
        val phases = listOf(DownloadPhase.Complete, DownloadPhase.Cancelled, DownloadPhase.Failed,
            DownloadPhase.Paused, DownloadPhase.WaitingWifi, DownloadPhase.Transferring)
        val tracks = phases.map { track(it.name) }
        val sections = downloadSections(emptyList(), tracks, phases.associate { it.name to DownloadTask(it) }, "")
        assertEquals(phases.drop(2).map { it.name }, sections.transfers.map { it.id })
    }

    @Test fun searchMatchesTitleArtistAndAlbumWithoutChangingTheStoredLibrary() {
        val saved = listOf(item("A"), item("B"))
        for (query in listOf("title b", "ARTIST B", "  Album B  ")) {
            assertEquals(listOf("B"), downloadSections(saved, emptyList(), emptyMap(), query).saved.map { it.track.id })
        }
        assertEquals(2, saved.size)
        assertTrue(downloadSections(saved, emptyList(), emptyMap(), "no match").saved.isEmpty())
    }

    @Test fun emptyQueryKeepsTheOriginalOrderAndDeduplicatesActiveTracks() {
        val sections = downloadSections(listOf(item("b"), item("a")),
            listOf(track("c"), track("c"), track("d")), emptyMap(), "  ")
        assertEquals(listOf("b", "a"), sections.saved.map { it.track.id })
        assertEquals(listOf("c", "d"), sections.transfers.map { it.id })
    }

    @Test fun filtersKeepPausedAndFailedWorkSeparateFromPlayableFiles() {
        val phases = listOf(DownloadPhase.Paused, DownloadPhase.Failed, DownloadPhase.WaitingWifi, DownloadPhase.Transferring)
        val active = phases.map { track(it.name) }
        val tasks = phases.associate { it.name to DownloadTask(it) }
        fun sections(filter: DownloadFilter) = downloadSections(listOf(item("saved")), active, tasks, "", filter)
        assertEquals(1, sections(DownloadFilter.Offline).saved.size)
        assertTrue(sections(DownloadFilter.Offline).transfers.isEmpty())
        assertEquals(listOf("Paused"), sections(DownloadFilter.Paused).transfers.map { it.id })
        assertEquals(listOf("Failed"), sections(DownloadFilter.Failed).transfers.map { it.id })
        assertTrue(sections(DownloadFilter.Active).saved.isEmpty())
        assertEquals(listOf("WaitingWifi", "Transferring"), sections(DownloadFilter.Active).transfers.map { it.id })
    }
}
