package com.ketvoice.app.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** « Musique locale pro » (7.0): tag normalisation, album grouping, catalogue matching and tag writing. */
class LocalMusicProTest {
    private fun tags(title: String, artist: String? = null, album: String? = null, albumArtist: String? = null,
                     track: Int? = null, disc: Int? = null, folder: String = "", compilation: Boolean = false, durationMs: Long = 200_000) =
        LocalTags(title, artist, album, albumArtist, trackNo = track, discNo = disc, folder = folder, compilation = compilation, durationMs = durationMs)

    @After fun reset() { LocalCorrections.resetForTest() }

    // ------------------------------------------------------------------ normalisation

    @Test fun placeholdersSpacesAndControlCharactersAreCleanedAway() {
        assertNull(LocalTagNormalizer.clean("<unknown>"))
        assertNull(LocalTagNormalizer.clean("  Unknown Artist "))
        assertNull(LocalTagNormalizer.clean("   "))
        assertEquals("Daft Punk", LocalTagNormalizer.clean("﻿ Daft   Punk\u0000 "))
    }

    @Test fun trackDiscAndYearNumbersAreRead() {
        assertEquals(3 to 12, LocalTagNormalizer.number("03/12"))
        assertEquals(3 to 12, LocalTagNormalizer.number("3 of 12"))
        assertEquals(7 to null, LocalTagNormalizer.number("7"))
        assertEquals(2 to null, LocalTagNormalizer.number("A2"))
        assertEquals(null to null, LocalTagNormalizer.number("abc"))
        assertEquals(5 to null, LocalTagNormalizer.number("5/3"))
        assertEquals(5 to 2, LocalTagNormalizer.mediaStoreTrack(2005))
        assertEquals(9 to null, LocalTagNormalizer.mediaStoreTrack(9))
        assertEquals(2019, LocalTagNormalizer.year("2019-05-01"))
        assertNull(LocalTagNormalizer.year("0"))
        assertEquals("Rock", LocalTagNormalizer.genre("(17)Rock"))
        assertNull(LocalTagNormalizer.genre("(17)"))
    }

    @Test fun aFileWithoutTagsIsNamedAfterItsFileNameNeverAGuess() {
        val a = LocalTagNormalizer.normalize(RawTags(fileName = "01 - Daft Punk - One More Time.mp3"))
        assertEquals("One More Time", a.title); assertEquals("Daft Punk", a.artist); assertEquals(1, a.trackNo)
        val b = LocalTagNormalizer.normalize(RawTags(fileName = "07. Intro.flac"))
        assertEquals("Intro", b.title); assertNull(b.artist); assertEquals(7, b.trackNo)
        // A title tag keeps the file name out of the artist: « Artist - Title » is only read when nothing is tagged.
        val c = LocalTagNormalizer.normalize(RawTags(title = "Real title", fileName = "Some - Thing.mp3"))
        assertEquals("Real title", c.title); assertNull(c.artist)
    }

    @Test fun variousArtistsMakesACompilationAndPrimaryArtistsDropGuests() {
        val t = LocalTagNormalizer.normalize(RawTags(title = "x", albumArtist = "Various Artists", album = "Hits"))
        assertTrue(t.compilation); assertNull(t.albumArtist)
        assertTrue(LocalTagNormalizer.normalize(RawTags(title = "x", compilation = "1")).compilation)
        assertEquals("Daft Punk", LocalTagNormalizer.primaryArtist("Daft Punk feat. Pharrell Williams"))
        assertEquals("Simon & Garfunkel", LocalTagNormalizer.primaryArtist("Simon & Garfunkel"))
        assertEquals("A", LocalTagNormalizer.primaryArtist("A, B"))
    }

    @Test fun keysIgnoreCaseAccentsVersionsAndGuests() {
        assertEquals(LocalTagNormalizer.key("Beyoncé"), LocalTagNormalizer.key("beyonce"))
        assertEquals(LocalTagNormalizer.key("Get Lucky"), LocalTagNormalizer.key("Get Lucky (feat. Pharrell Williams)"))
        assertEquals(LocalTagNormalizer.key("Let It Be"), LocalTagNormalizer.key("Let It Be - Remastered 2009"))
        assertEquals(LocalTagNormalizer.key("Discovery"), LocalTagNormalizer.key("Discovery [Remastered]"))
    }

    // ------------------------------------------------------------------ grouping

    private fun local(id: String, title: String) = Track("local:$id", title, "", "")

    @Test fun albumsGroupByAlbumArtistOrderDiscsAndTracksAndKeepNamesakesApart() {
        val tracks = (1..6).map { local("$it", "t$it") }
        val t = mapOf(
            "local:1" to tags("Two", "Daft Punk", "Discovery", "Daft Punk", track = 2, disc = 1),
            "local:2" to tags("One", "Daft Punk feat. Romanthony", "Discovery", "Daft Punk", track = 1, disc = 1),
            "local:3" to tags("Bonus", "Daft Punk", "Discovery", "Daft Punk", track = 1, disc = 2),
            "local:4" to tags("Hit A", "Queen", "Greatest Hits", "Queen"),
            "local:5" to tags("Hit B", "ABBA", "Greatest Hits", "ABBA"),
            "local:6" to tags("Untagged"))
        val albums = LocalLibrary.albums(tracks, t)
        assertEquals(3, albums.size)
        val discovery = albums.single { it.title == "Discovery" }
        assertEquals("Daft Punk", discovery.artist)
        assertEquals(listOf(1, 2), discovery.discs.map { it.number })
        assertEquals(listOf("local:2", "local:1", "local:3"), discovery.tracks.map { it.id })
        assertEquals(setOf("Queen", "ABBA"), albums.filter { it.title == "Greatest Hits" }.map { it.artist }.toSet())
    }

    @Test fun severalArtistsInOneFolderWithoutAlbumArtistAreACompilation() {
        val tracks = (1..3).map { local("$it", "t$it") }
        val t = mapOf(
            "local:1" to tags("A", "Artist One", "Summer Mix", folder = "Music/Summer", track = 2),
            "local:2" to tags("B", "Artist Two", "Summer Mix", folder = "Music/Summer", track = 1),
            "local:3" to tags("C", "Solo", "Alone", folder = "Music/Alone"))
        val albums = LocalLibrary.albums(tracks, t)
        val summer = albums.single { it.title == "Summer Mix" }
        assertTrue(summer.compilation); assertNull(summer.artist)
        assertEquals(listOf("local:2", "local:1"), summer.tracks.map { it.id })
        assertEquals("Solo", albums.single { it.title == "Alone" }.artist)
        assertEquals("Music/Summer", summer.folder)
    }

    @Test fun artistsAndFoldersCollectTheirTitles() {
        val tracks = (1..4).map { local("$it", "t$it") }
        val t = mapOf(
            "local:1" to tags("A", "Nova feat. X", "One", folder = "Music/Nova/One", track = 2),
            "local:2" to tags("B", "Nova", "One", folder = "Music/Nova/One", track = 1),
            "local:3" to tags("C", "Nova", null, folder = "Music/Loose"),
            "local:4" to tags("D", "Orion", null, folder = "Music/Loose"))
        val artists = LocalLibrary.artists(tracks, t)
        assertEquals("nova", artists.first().key)
        assertEquals(listOf("local:2", "local:1", "local:3"), artists.first().tracks.map { it.id })
        assertEquals(1, artists.first().albums.size)
        val folders = LocalLibrary.folders(tracks, t)
        assertEquals(listOf("Music/Loose", "Music/Nova/One"), folders.map { it.path })
        assertEquals("One", folders[1].name); assertEquals("Music/Nova", folders[1].parent)
        assertEquals(listOf("local:2", "local:1"), folders[1].tracks.map { it.id })
    }

    // ------------------------------------------------------------------ matching

    private fun catalog(title: String, artist: String, album: String, durationMs: Long = 200_000) =
        Track("yt:$title", title, artist, album, durationMs = durationMs, coverUrl = "https://img.example/$title.jpg")

    @Test fun titleArtistAndDurationMakeTheScore() {
        val local = tags("Get Lucky", "Daft Punk", durationMs = 248_000)
        assertTrue(LocalMatch.score(local, catalog("Get Lucky (feat. Pharrell Williams)", "Daft Punk", "RAM", 248_500)) >= LocalMatch.SURE_FROM)
        assertEquals(0.0, LocalMatch.score(local, catalog("Get Lucky", "Daft Punk", "RAM", 400_000)), 0.0)
        assertEquals(0.0, LocalMatch.score(local, catalog("Around the World", "Daft Punk", "Homework", 248_000)), 0.0)
        assertTrue(LocalMatch.score(local, catalog("Get Lucky", "Someone Else", "Covers", 248_000)) < LocalMatch.SURE_FROM)
        // A title alone, without an artist or a duration to confirm it, is never sure.
        assertTrue(LocalMatch.score(tags("Intro", durationMs = 0), catalog("Intro", "X", "Y", 0)) < LocalMatch.SURE_FROM)
    }

    @Test fun theBestMatchIsChosenAndLocalResultsIgnored() {
        val local = tags("Halo", "Beyonce", durationMs = 261_000)
        val best = LocalMatch.best(local, listOf(catalog("Halo", "Beyoncé", "I Am... Sasha Fierce", 261_000),
            catalog("Halo", "Some Cover Band", "Covers", 300_000), Track("local:9", "Halo", "Beyonce", "", durationMs = 261_000)))
        assertEquals("Beyoncé", best?.first?.artist)
    }

    @Test fun proposalsFillWhatIsMissingRespellAndNeverSwapAnAlbum() {
        val missing = tags("Halo", null, null, durationMs = 261_000)
        val c = catalog("Halo", "Beyoncé", "I Am... Sasha Fierce", 261_000)
        val p = LocalMatch.proposal("local:1", missing, c, 0.9)!!
        assertEquals("Beyoncé", p.changes[LocalMatch.Field.Artist])
        assertEquals("I Am... Sasha Fierce", p.changes[LocalMatch.Field.Album])
        assertEquals("Beyoncé", p.changes[LocalMatch.Field.AlbumArtist])
        val respell = LocalMatch.proposal("local:2", tags("Halo", "beyonce", "My Own Album"), c, 0.9)!!
        assertEquals(mapOf(LocalMatch.Field.Artist to "Beyoncé"), respell.changes)
        assertNull(LocalMatch.proposal("local:3", tags("Halo", "Beyoncé", "I Am... Sasha Fierce"), c, 0.95))
        assertNull(LocalMatch.proposal("local:4", missing, c, 0.5))
        val applied = LocalMatch.apply(missing, p.changes)
        assertEquals("Beyoncé", applied.artist); assertEquals("I Am... Sasha Fierce", applied.album)
    }

    @Test fun theSearchKeepsProposalsUntilTheListenerAcceptsAndRemembersStreamingDuplicates() = runBlocking {
        val files = listOf("local:1" to tags("Halo", null, null, durationMs = 261_000),
            "local:2" to tags("Nothing like it", "Nobody", "Nowhere", durationMs = 100_000))
        val found = LocalCorrections.findAll(files, { q -> if (q.startsWith("Halo")) listOf(catalog("Halo", "Beyoncé", "I Am... Sasha Fierce", 261_000)) else emptyList() }, pauseMs = 0)
        assertEquals(1, found)
        assertEquals(listOf("local:1"), LocalCorrections.proposals.value.map { it.localId })
        // Nothing is applied before the listener says so.
        assertNull(LocalCorrections.corrected("local:1", files[0].second).artist)
        assertNotNull(LocalCorrections.streaming.value["local:1"])
        assertEquals("https://img.example/Halo.jpg", LocalCorrections.borrowedCover("local:1"))
        LocalCorrections.accept(listOf("local:1"))
        assertEquals("Beyoncé", LocalCorrections.corrected("local:1", files[0].second).artist)
        assertTrue(LocalCorrections.proposals.value.isEmpty())
        LocalCorrections.undo("local:1")
        assertNull(LocalCorrections.corrected("local:1", files[0].second).artist)
    }

    // ------------------------------------------------------------------ writing into files

    private val audio = ByteArray(5000) { (it * 7 + 3).toByte() }.also { it[0] = 0xFF.toByte(); it[1] = 0xFB.toByte() }

    private fun id3v23(vararg frames: Pair<String, String>): ByteArray {
        val body = ByteArrayOutputStream()
        frames.forEach { (id, text) ->
            val payload = byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)
            body.write(id.toByteArray()); body.write(LocalTagWriter.u32beBytes(payload.size)); body.write(byteArrayOf(0, 0)); body.write(payload)
        }
        val b = body.toByteArray()
        return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0) + LocalTagWriter.syncsafeBytes(b.size) + b
    }

    @Test fun anMp3TagIsRewrittenKeepingOtherFramesAndTheAudio() {
        val file = id3v23("TIT2" to "old title", "COMM" to "keep me", "TPE1" to "old artist") + audio
        val out = LocalTagWriter.rewrite(file, LocalTagWriter.Update(artist = "Beyoncé", album = "I Am... Sasha Fierce"))!!
        assertEquals("Beyoncé", LocalTagWriter.Id3.text(out, "TPE1"))
        assertEquals("I Am... Sasha Fierce", LocalTagWriter.Id3.text(out, "TALB"))
        assertEquals("old title", LocalTagWriter.Id3.text(out, "TIT2"))
        assertEquals("keep me", LocalTagWriter.Id3.text(out, "COMM"))
        assertTrue(LocalTagWriter.sameAudio(file, out))
        assertArrayEquals(audio, out.copyOfRange(LocalTagWriter.audioStart(out)!!, out.size))
    }

    @Test fun anUntaggedMp3GetsATagAndAnUnsafeOneIsLeftAlone() {
        val out = LocalTagWriter.rewrite(audio, LocalTagWriter.Update(title = "Nouveau"))!!
        assertEquals("Nouveau", LocalTagWriter.Id3.text(out, "TIT2"))
        assertTrue(LocalTagWriter.sameAudio(audio, out))
        val unsync = id3v23("TIT2" to "x").also { it[5] = 0x80.toByte() } + audio
        assertNull(LocalTagWriter.rewrite(unsync, LocalTagWriter.Update(title = "y")))
        assertNull(LocalTagWriter.rewrite(byteArrayOf(0, 0, 0, 0x20) + "ftypM4A ".toByteArray(), LocalTagWriter.Update(title = "y")))
        assertNull(LocalTagWriter.rewrite(audio, LocalTagWriter.Update()))
    }

    private fun flac(comments: List<String>?): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        val streamInfo = ByteArray(34) { it.toByte() }
        out.write(if (comments == null) 0x00 else 0x00); out.write(0); out.write(0); out.write(34); out.write(streamInfo)
        if (comments != null) {
            val body = ByteArrayOutputStream()
            fun le(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())
            body.write(le(6)); body.write("vendor".toByteArray()); body.write(le(comments.size))
            comments.forEach { c -> body.write(le(c.toByteArray().size)); body.write(c.toByteArray()) }
            val b = body.toByteArray()
            out.write(0x04); out.write(b.size shr 16); out.write(b.size shr 8); out.write(b.size); out.write(b)
        }
        val padding = ByteArray(16)
        out.write(0x81); out.write(0); out.write(0); out.write(16); out.write(padding)
        out.write(audio)
        return out.toByteArray()
    }

    @Test fun aFlacCommentBlockIsRewrittenAndTheRestCopied() {
        val file = flac(listOf("TITLE=Old", "artist=old artist", "COMMENT=keep"))
        val out = LocalTagWriter.rewrite(file, LocalTagWriter.Update(artist = "Nova", albumArtist = "Nova"))!!
        val (blocks, _) = LocalTagWriter.Flac.blocks(out)!!
        assertEquals(listOf(0, 4, 1), blocks.map { it.type })
        val (vendor, comments) = LocalTagWriter.Flac.comments(blocks[1].body)!!
        assertEquals("vendor", vendor)
        assertEquals(listOf("TITLE=Old", "COMMENT=keep", "ARTIST=Nova", "ALBUMARTIST=Nova"), comments)
        assertTrue(LocalTagWriter.sameAudio(file, out))
        // A file without a comment block gets one right after STREAMINFO.
        val bare = LocalTagWriter.rewrite(flac(null), LocalTagWriter.Update(title = "T"))!!
        assertEquals(listOf(0, 4, 1), LocalTagWriter.Flac.blocks(bare)!!.first.map { it.type })
        assertFalse(LocalTagWriter.sameAudio(file, file.copyOf(file.size - 1)))
    }
}
