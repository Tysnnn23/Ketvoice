package com.ketvoice.app.ui.sub

import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.Track
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadOrganisationTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun item(id: String, title: String, artist: String, format: String, bytes: Long, age: Long, durationMs: Long = 180_000) =
        Downloader.Item(Track(id, title, artist, "Album", durationMs = durationMs),
            tmp.newFile("$id.bin").apply { setLastModified(1_700_000_000_000L + age) }, format, bytes)

    private fun library() = listOf(
        item("1", "Bravo", "Zed", "FLAC · Sans perte", 30_000, age = 1000),
        item("2", "alpha", "Abel, Guest", "MP3 · Haute qualité", 5_000, age = 3000),
        item("3", "Charlie", "abel", "DASH · FLAC", 50_000, age = 2000),
        item("4", "Delta", "", "AAC · Haute qualité", 1_000, age = 0))

    @Test fun eachSortOrdersTheSameTitles() {
        val items = library()
        assertEquals(listOf("2", "3", "1", "4"), sortDownloads(items, DownloadSort.Recent).map { it.track.id })
        assertEquals(listOf("2", "1", "3", "4"), sortDownloads(items, DownloadSort.Title).map { it.track.id })
        assertEquals(listOf("4", "3", "2", "1"), sortDownloads(items, DownloadSort.Artist).map { it.track.id })
        assertEquals(listOf("3", "1", "2", "4"), sortDownloads(items, DownloadSort.Size).map { it.track.id })
        DownloadSort.entries.forEach { assertEquals(items.toSet(), sortDownloads(items, it).toSet()) }
    }

    @Test fun summaryCountsBytesPlaytimeAndTheLosslessShare() {
        val summary = downloadSummary(library())
        assertEquals(4, summary.count)
        assertEquals(86_000L, summary.bytes)
        assertEquals(720_000L, summary.durationMs)
        assertEquals(2, summary.lossless)
        assertEquals(80_000L, summary.losslessBytes)
        assertEquals(80_000f / 86_000f, summary.losslessShare, 1e-4f)
        assertEquals(2, summary.artists)
        assertEquals(0f, downloadSummary(emptyList()).losslessShare, 0f)
    }

    @Test fun artistsGroupByFirstCreditWhateverTheCase() {
        val artists = downloadArtists(library())
        assertEquals(listOf("abel", "zed"), artists.map { it.key })
        assertEquals(listOf("2", "3"), artists.first().items.map { it.track.id })
        assertEquals(55_000L, artists.first().bytes)
    }

    @Test fun badgesAndPlaytimeAreShortAndHonest() {
        assertEquals("FLAC", formatBadge("FLAC · Sans perte"))
        assertEquals("FLAC", formatBadge("DASH · FLAC"))
        assertEquals("MP3", formatBadge("MP3 · Haute qualité"))
        assertEquals("?", formatBadge(""))
        assertTrue(isLosslessFormat("WAV · Sans perte"))
        assertFalse(isLosslessFormat("Opus · Haute qualité"))
        assertEquals("", playtimeLabel(0))
        assertEquals("47 min", playtimeLabel(47 * 60_000L))
        assertEquals("3 h 05 min", playtimeLabel(185 * 60_000L))
    }
}
