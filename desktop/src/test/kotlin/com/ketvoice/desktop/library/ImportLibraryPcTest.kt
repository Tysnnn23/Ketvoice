package com.ketvoice.desktop.library

import com.ketvoice.app.data.PlaylistFollow
import com.ketvoice.app.data.Track
import com.ketvoice.app.smartlib.ImportMatch
import com.ketvoice.app.smartlib.ImportPreview
import com.ketvoice.app.smartlib.ImportReport
import com.ketvoice.app.smartlib.ImportService
import com.ketvoice.app.smartlib.ImportSong
import com.ketvoice.app.smartlib.YouTubeImport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Windows 2.0 import and library: the report's numbers, drops, the watcher's gathering, followed playlists, YouTube. */
class ImportLibraryPcTest {
    private fun song(i: Int) = ImportSong("s$i", "Title $i", "Artist", 200_000)
    private fun track(i: Int) = Track("k$i", "Title $i", "Artist", "")

    @AfterTest fun reset() { OutsidePlaylists.resetForTest() }

    @Test fun theReportCountsSureUncertainAndMissingTitles() {
        val matches = listOf(ImportMatch(song(0), track(0), 1f), ImportMatch(song(1), track(1), .95f), ImportMatch(song(2), track(2), .8f),
            ImportMatch(song(3), null, .3f))
        val r = ImportReport.summary(matches)
        assertEquals(ImportReport.Summary(4, 2, 1, 1, 0), r)
        assertEquals(75, r.rate)
        // Fixed by hand: the missing one found, the uncertain one left out.
        val fixed = ImportReport.summary(matches, mapOf(3 to track(9), 2 to null))
        assertEquals(ImportReport.Summary(4, 3, 0, 1, 1), fixed)
        assertEquals(75, fixed.rate)
        assertEquals("k9", ImportReport.applied(matches, mapOf(3 to track(9)))[3].track?.id)
        assertEquals(0, ImportReport.summary(emptyList()).rate)
        assertEquals(33, ImportReport.summary(listOf(ImportMatch(song(0), track(0), 1f), ImportMatch(song(1), null, 0f), ImportMatch(song(2), null, 0f))).rate)
    }

    @Test fun dropsAreUnderstood() {
        assertIs<DropKinds.PlaylistLink>(DropKinds.ofText("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=x"))
        assertIs<DropKinds.PlaylistLink>(DropKinds.ofText("look: https://www.deezer.com/fr/playlist/1234567, nice"))
        assertIs<DropKinds.PlaylistLink>(DropKinds.ofText("https://music.youtube.com/playlist?list=PLabcdefghijk123"))
        assertIs<DropKinds.PlaylistLink>(DropKinds.ofText("https://soundcloud.com/someone/sets/summer"))
        assertIs<DropKinds.ServiceLink>(DropKinds.ofText("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC"))
        assertIs<DropKinds.ServiceLink>(DropKinds.ofText("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        val share = assertIs<DropKinds.ShareLink>(DropKinds.ofText("https://tysnnn23.github.io/Ketvoice/listen.html?track=um%3A12&title=Hello%20there&artist=Ad%C3%A8le"))
        assertEquals("um:12", share.trackId); assertEquals("Hello there", share.title); assertEquals("Adèle", share.artist)
        assertIs<DropKinds.ShareLink>(DropKinds.ofText("ketvoice://listen?playlist=abc&name=Mix"))
        assertIs<DropKinds.ManifestLink>(DropKinds.ofText("https://example.org/ketvoice/manifest.json"))
        assertEquals(DropKinds.Nothing, DropKinds.ofText("http://example.org/manifest.json"))
        assertEquals(DropKinds.Nothing, DropKinds.ofText("just some words"))
        assertEquals(DropKinds.Nothing, DropKinds.ofText("https://example.org/page"))

        val dir = Files.createTempDirectory("drop").toFile()
        val lrc = File(dir, "song.lrc").apply { writeText("[00:01.00]Hello") }
        val mp3 = File(dir, "a.mp3").apply { writeBytes(ByteArray(8)) }
        val sub = File(dir, "album").apply { mkdirs() }
        val txt = File(dir, "notes.pdf").apply { writeText("x") }
        assertEquals(DropKinds.LyricsFile(lrc), DropKinds.ofFiles(listOf(lrc)))
        assertEquals(DropKinds.AudioFiles(listOf(mp3), listOf(sub)), DropKinds.ofFiles(listOf(mp3, sub, txt, lrc)))
        assertEquals(DropKinds.Nothing, DropKinds.ofFiles(listOf(txt)))
        assertEquals(DropKinds.Nothing, DropKinds.ofFiles(emptyList()))
    }

    @Test fun theWatcherGathersBurstsBeforeReading() {
        val d = LibraryWatch.Debounce(quietMs = 1_000, maxMs = 5_000)
        d.add("/m/a.mp3", 0); d.add("/m/b.mp3", 400); d.add("/m/a.mp3", 800)
        assertEquals(emptySet(), d.due(1_500), "still coming in")
        assertEquals(setOf("/m/a.mp3", "/m/b.mp3"), d.due(1_900))
        assertEquals(emptySet(), d.due(5_000))
        // A copy that never stops is still read every few seconds.
        for (t in 0L..6_000L step 500) d.add("/m/c$t.flac", t)
        assertEquals(13, d.due(6_100).size)
        assertTrue(LibraryWatch.relevant(File("x.flac")) && LibraryWatch.relevant(File("x.lrc")) && !LibraryWatch.relevant(File("x.jpg")))
    }

    @Test fun theWatcherSeesFilesComeAndGo() {
        val dir = Files.createTempDirectory("watch").toFile()
        val seen = java.util.Collections.synchronizedSet(HashSet<String>())
        val apply = LibraryWatch.apply
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            LibraryWatch.apply = { seen += it }
            LibraryWatch.watch(scope, listOf(dir.path))
            val album = File(dir, "Album").apply { mkdirs() }
            Thread.sleep(300)
            File(album, "01.flac").writeBytes(ByteArray(16))
            File(dir, "cover.jpg").writeBytes(ByteArray(16))
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && seen.none { it.endsWith("01.flac") }) Thread.sleep(100)
            assertTrue(seen.any { it.endsWith("01.flac") }, "$seen")
            assertTrue(seen.none { it.endsWith("cover.jpg") })
        } finally { LibraryWatch.stop(); LibraryWatch.apply = apply; scope.cancel() }
    }

    @Test fun aFollowedPlaylistOffersOrAddsItsNewSongs() = runBlocking {
        val preview = ImportPreview(ImportService.Deezer, "Road", "https://www.deezer.com/playlist/1", null, listOf(song(1), song(2)), true)
        val added = mutableListOf<Pair<String, List<Track>>>()
        OutsidePlaylists.add = { id, t -> added += id to t }
        OutsidePlaylists.exists = { true }
        OutsidePlaylists.search = { q -> listOf(track(q.substringAfter("Title ").substringBefore(' ').toInt())) }
        OutsidePlaylists.follow("p1", preview)
        assertTrue(OutsidePlaylists.isFollowed("p1"))
        OutsidePlaylists.preview = { preview.copy(songs = listOf(song(1), song(2), song(3))) }
        assertEquals(listOf("k3"), OutsidePlaylists.check(OutsidePlaylists.followed.value.single()).map { it.id })
        assertEquals(listOf("k3"), OutsidePlaylists.pending.value.single().tracks.map { it.id })
        assertTrue(added.isEmpty(), "offered, not added")
        // Known now: not offered twice. With « Ajouter automatiquement », the next ones go straight in.
        assertEquals(emptyList(), OutsidePlaylists.check(OutsidePlaylists.followed.value.single()))
        OutsidePlaylists.autoAdd.value = true
        OutsidePlaylists.preview = { preview.copy(songs = listOf(song(1), song(4))) }
        OutsidePlaylists.check(OutsidePlaylists.followed.value.single())
        assertEquals(listOf("p1" to listOf("k4")), added.map { it.first to it.second.map { t -> t.id } })
        OutsidePlaylists.accept(OutsidePlaylists.pending.value.single())
        assertTrue(OutsidePlaylists.pending.value.isEmpty())
        // Stored and read back; a playlist deleted here is let go.
        val back = OutsidePlaylists.decode(OutsidePlaylists.encode(OutsidePlaylists.followed.value))
        assertEquals(setOf("s1", "s2", "s3", "s4"), back.single().known)
        OutsidePlaylists.exists = { false }
        OutsidePlaylists.checkAll()
        assertTrue(OutsidePlaylists.followed.value.isEmpty())
        assertEquals(listOf("s3"), PlaylistFollow.outsideFresh(setOf("s1", "s2"), listOf("s1", "s3", "s3", "", "s2")))
    }

    @Test fun youTubePlaylistsAreRead() {
        assertEquals("PLabcdefghijk123", YouTubeImport.playlistId("https://www.youtube.com/watch?v=abc&list=PLabcdefghijk123&index=2"))
        assertEquals("OLAK5uy_abcdefghij", YouTubeImport.playlistId("https://music.youtube.com/playlist?list=OLAK5uy_abcdefghij"))
        assertFailsWith<IllegalArgumentException> { YouTubeImport.playlistId("https://www.youtube.com/watch?v=abc") }
        assertFailsWith<IllegalArgumentException> { YouTubeImport.playlistId("https://example.org/?list=PLabcdefghijk123") }
        assertEquals(225_000L, YouTubeImport.durationMs("3:45"))
        assertEquals(3_723_000L, YouTubeImport.durationMs("1:02:03"))
        assertEquals(0L, YouTubeImport.durationMs("live"))
        fun row(id: String, title: String, artist: String, length: String) = JSONObject("""{"musicResponsiveListItemRenderer":{
            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title","navigationEndpoint":{"watchEndpoint":{"videoId":"$id"}}}]}}},
              {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$artist"},{"text":" • "},{"text":"Album"}]}}}],
            "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"$length"}]}}}]}}""")
        val raw = JSONObject().put("header", JSONObject("""{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"Night drive"}]}}}"""))
            .put("contents", org.json.JSONArray().put(row("vid000000001", "Neon", "Ava", "3:10")).put(row("vid000000002", "Rain", "Ben & Cy", "4:02"))
                .put(row("vid000000001", "Neon", "Ava", "3:10")))
        val p = YouTubeImport.parse(raw, "PLabcdefghijk123")
        assertEquals("Night drive", p.name)
        assertEquals(ImportService.YouTube, p.service)
        assertEquals(listOf("Neon" to "Ava", "Rain" to "Ben & Cy"), p.songs.map { it.title to it.artist })
        assertEquals(190_000L, p.songs[0].durationMs)
        assertTrue(p.complete)
    }
}
