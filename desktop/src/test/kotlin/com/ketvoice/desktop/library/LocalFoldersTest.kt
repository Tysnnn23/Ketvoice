package com.ketvoice.desktop.library

import android.content.Context
import com.ketvoice.desktop.audio.AudioSource
import com.ketvoice.desktop.player.DesktopStreams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Folders of music files: real files tagged by the FFmpeg executable, read back through the JavaCPP natives. */
class LocalFoldersTest {
    private val ffmpeg: String by lazy { org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.ffmpeg::class.java) }

    private fun run(vararg args: String) {
        val p = ProcessBuilder(listOf(ffmpeg, "-y", "-hide_banner", "-loglevel", "error") + args).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { log }
    }

    @AfterTest fun tearDown() = LocalFolders.resetForTest()

    @Test fun foldersAreScannedWithTheirTagsAndPlayAsFiles() = runBlocking {
        val ctx = Context(Files.createTempDirectory("ketvoice-local").toFile())
        // An accented folder name: the path reaches FFmpeg as UTF-8.
        val music = File(ctx.dataDir, "Musique été/Album").apply { mkdirs() }
        val flac = File(music, "02 piste.flac")
        // Tags from a UTF-8 metadata file (command-line arguments would go through the test machine's locale).
        val meta = File(ctx.dataDir, "meta.txt").apply { writeText(";FFMETADATA1\ntitle=Été indien\nartist=Joe Test\nalbum=Saisons\ntrack=2/9\n", Charsets.UTF_8) }
        run("-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-i", meta.path, "-map_metadata", "1", flac.path)
        val cover = File(ctx.dataDir, "cover.png")
        run("-f", "lavfi", "-i", "color=c=red:s=32x32", "-frames:v", "1", cover.path)
        val mp3 = File(music, "01 intro.mp3")
        run("-f", "lavfi", "-i", "sine=frequency=330:duration=1", "-i", cover.path, "-map", "0:a", "-map", "1", "-c:v", "png",
            "-disposition:v", "attached_pic", "-id3v2_version", "3", "-metadata", "title=Intro", "-metadata", "artist=Joe Test",
            "-metadata", "album=Saisons", "-metadata", "track=1", mp3.path)
        File(music, "notes.txt").writeText("not music")

        LocalFolders.init(ctx, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        assertTrue(LocalFolders.addFolder(File(ctx.dataDir, "Musique été").path))
        LocalFolders.rescan().join()
        val tracks = LocalFolders.tracks.value
        assertEquals(listOf("Intro", "Été indien"), tracks.map { it.title }, "album order by track number")
        val ete = tracks[1]
        assertEquals("Joe Test", ete.artist); assertEquals("Saisons", ete.album)
        assertTrue(ete.durationMs in 900..1100, "${ete.durationMs}")
        assertTrue(ete.id.startsWith("local:file:"))
        val coverFile = File(URI(assertNotNull(tracks[0].coverUrl)))
        assertTrue(coverFile.length() > 0, "embedded cover kept")
        val source = DesktopStreams.source(ete)
        assertEquals(AudioSource.LocalFile(flac.path), source)

        // The folder list and the index come back after a restart.
        LocalFolders.resetForTest()
        LocalFolders.init(ctx, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        assertEquals(listOf(File(ctx.dataDir, "Musique été").path), LocalFolders.folders.value)
        assertEquals(2, LocalFolders.tracks.value.size)
        LocalFolders.removeFolder(LocalFolders.folders.value.single())
        LocalFolders.rescan().join()
        assertEquals(0, LocalFolders.tracks.value.size)
    }
}
