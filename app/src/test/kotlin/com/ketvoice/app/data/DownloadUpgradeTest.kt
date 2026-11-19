package com.ketvoice.app.data

import com.ketvoice.app.data.DownloadUpgrade.Rendition
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadUpgradeTest {
    @get:Rule val folder = TemporaryFolder()

    private fun stream(format: String, quality: String, codec: String? = null) =
        MusicApi.StreamInfo(url = "https://example.invalid/a", format = format, quality = quality, codec = codec)

    /** A FLAC STREAMINFO head at [rate] Hz (fLaC, block header type 0, then sizes, then the rate's 20 bits). */
    private fun flacHead(rate: Int) = ByteArray(21).also {
        "fLaC".toByteArray().copyInto(it)
        it[18] = (rate shr 12).toByte(); it[19] = (rate shr 4).toByte(); it[20] = ((rate and 0xF) shl 4).toByte()
    }

    @Test fun savedFilesAreReadFromTheirHeaderFirstThenTheirStoredFormat() {
        assertEquals(Rendition(true, 96_000), DownloadUpgrade.ofSaved("MP3 · 128 kbps", flacHead(96_000)))
        assertEquals(Rendition(true), DownloadUpgrade.ofSaved("FLAC · Sans perte", null))
        assertEquals(Rendition(false, kbps = 320), DownloadUpgrade.ofSaved("MP3 · 320 kbps", ByteArray(21)))
        assertEquals(Rendition(false, kbps = 128), DownloadUpgrade.ofSaved("AAC · Standard", null))
        assertEquals(Rendition(true, 48_000), DownloadUpgrade.ofStream(stream("flac", "Hi-Res 24-bit / 48 kHz")))
        assertEquals(Rendition(true, 44_100), DownloadUpgrade.ofStream(stream("flac", "FLAC 44,1 kHz")))
    }

    @Test fun onlyAReallyBetterRenditionCounts() {
        val mp3 = Rendition(false, kbps = 128)
        assertTrue("lossless beats lossy", DownloadUpgrade.better(mp3, Rendition(true)))
        assertTrue(DownloadUpgrade.better(mp3, Rendition(false, kbps = 320)))
        assertFalse("a few kb/s more is not worth a download", DownloadUpgrade.better(mp3, Rendition(false, kbps = 160)))
        assertFalse(DownloadUpgrade.better(Rendition(false, kbps = 320), Rendition(false, kbps = 256)))
        assertFalse("lossy never replaces lossless", DownloadUpgrade.better(Rendition(true), Rendition(false, kbps = 320)))
        assertFalse("lossless at an unknown rate is not better than lossless", DownloadUpgrade.better(Rendition(true, 44_100), Rendition(true)))
        assertFalse(DownloadUpgrade.better(Rendition(true), Rendition(true, 96_000)))
        assertTrue("Hi-Res over CD quality", DownloadUpgrade.better(Rendition(true, 44_100), Rendition(true, 96_000)))
        assertFalse(DownloadUpgrade.better(Rendition(true, 96_000), Rendition(true, 48_000)))
    }

    @Test fun theDownloadedFileMustHoldWhatWasPromised() {
        val mp3 = Rendition(false, kbps = 128)
        assertTrue(DownloadUpgrade.verified(mp3, "flac", Rendition(true), flacHead(44_100)))
        assertFalse("a .flac without a FLAC header", DownloadUpgrade.verified(mp3, "flac", Rendition(true), ByteArray(21)))
        val cd = Rendition(true, 44_100)
        assertFalse("promised 96 kHz, header says 44.1", DownloadUpgrade.verified(cd, "flac", Rendition(true, 96_000), flacHead(44_100)))
        assertTrue(DownloadUpgrade.verified(cd, "flac", Rendition(true, 96_000), flacHead(96_000)))
        assertTrue(DownloadUpgrade.verified(mp3, "mp3", Rendition(false, kbps = 320), byteArrayOf(0x49, 0x44, 0x33)))
    }

    private fun audio(name: String, fill: Int): File = File(folder.root, name).apply { writeBytes(ByteArray(20_000) { fill.toByte() }) }

    @Test fun aNewFileUnderAnotherNameReplacesTheOldOnlyOnceTheMetadataPointsToIt() {
        val old = audio("k.mp3", 1); val fresh = audio("k.upgrade.flac", 2)
        val meta = File(folder.root, "k.json").apply { writeText("""{"file":"k.mp3"}""") }
        val lyrics = File(folder.root, "k.lyrics.json").apply { writeText("[]") }
        assertTrue(DownloadUpgrade.replace(old, fresh, File(folder.root, "k.flac"), meta, """{"file":"k.flac"}"""))
        assertFalse(old.exists()); assertFalse(fresh.exists())
        assertEquals(2.toByte(), File(folder.root, "k.flac").readBytes()[0])
        assertEquals("""{"file":"k.flac"}""", meta.readText())
        assertTrue("lyrics, Canvas and separations hang on the title and stay", lyrics.exists())
    }

    @Test fun aNewFileUnderTheSameNameKeepsNoBackupWhenAllWent() {
        val old = audio("k.mp3", 1); val fresh = audio("k.upgrade.mp3", 3)
        val meta = File(folder.root, "k.json").apply { writeText("old") }
        assertTrue(DownloadUpgrade.replace(old, fresh, old, meta, "new"))
        assertEquals(3.toByte(), old.readBytes()[0])
        assertEquals("new", meta.readText())
        assertEquals(listOf("k.json", "k.mp3"), folder.root.list()!!.sorted())
    }

    @Test fun aFailedSwapPutsTheOldFileAndMetadataBack() {
        val old = audio("k.mp3", 1); val fresh = audio("k.upgrade.mp3", 3)
        // The metadata cannot be replaced (a folder with something in it stands where it goes).
        val meta = File(folder.root, "k.json").apply { mkdirs(); File(this, "x").writeText("x") }
        assertFalse(DownloadUpgrade.replace(old, fresh, old, meta, "new"))
        assertEquals("the saved file is untouched", 1.toByte(), old.readBytes()[0])
        assertFalse(fresh.exists()); assertFalse(File(folder.root, "k.mp3.bak").exists())
        val other = audio("k.upgrade.flac", 2)
        assertFalse(DownloadUpgrade.replace(old, other, File(folder.root, "k.flac"), meta, "new"))
        assertTrue(old.exists()); assertFalse(File(folder.root, "k.flac").exists()); assertFalse(other.exists())
    }

    @Test fun anIncompleteNewFileIsNeverSwappedIn() {
        val old = audio("k.mp3", 1)
        val fresh = File(folder.root, "k.upgrade.flac").apply { writeBytes(ByteArray(100)) }
        val meta = File(folder.root, "k.json").apply { writeText("old") }
        assertFalse(DownloadUpgrade.replace(old, fresh, File(folder.root, "k.flac"), meta, "new"))
        assertTrue(old.exists()); assertEquals("old", meta.readText()); assertFalse(fresh.exists())
    }

    @Test fun recoveryAfterACrashBringsTheSavedFileBack() {
        val bak = audio("k.mp3.bak", 1)
        audio("j.mp3", 4); audio("j.mp3.bak", 5); audio("i.upgrade.flac", 6)
        DownloadUpgrade.recover(folder.root)
        assertFalse(bak.exists())
        assertEquals(1.toByte(), File(folder.root, "k.mp3").readBytes()[0])
        assertEquals("the current file wins over a spare backup", 4.toByte(), File(folder.root, "j.mp3").readBytes()[0])
        assertEquals(listOf("j.mp3", "k.mp3"), folder.root.list()!!.sorted())
    }
}
