package com.ketvoice.desktop.ui

import com.ketvoice.app.data.MediaOverride
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.Track
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Covers on Windows: the size each service is asked for, the disk cache, the fallback and retry, the Studio's overrides. */
class CoverLoadingTest {
    private val dir = File("build/test-data/covers-${System.nanoTime()}")
    private val network = Covers.fetchBytes

    @BeforeTest fun setUp() {
        Covers.trim(); Covers.forgetMisses(); CoverDisk.dir = dir.apply { mkdirs() }
    }

    @AfterTest fun restore() {
        Covers.trim(); Covers.forgetMisses(); Covers.fetchBytes = network
        MediaOverrides.replaceForTest(emptyList())
    }

    private fun png(side: Int): ByteArray = Surface.makeRasterN32Premul(side, side).run {
        canvas.clear(0xFF884422.toInt()); makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    @Test fun eachServiceIsAskedForTheSizeShown() {
        assertEquals(1024, CoverUrls.step(1000)); assertEquals(128, CoverUrls.step(1)); assertEquals(3000, CoverUrls.step(9000))
        val apple = "https://is1-ssl.mzstatic.com/image/thumb/Music/v4/aa/source/100x100bb.jpg"
        assertEquals(listOf("https://is1-ssl.mzstatic.com/image/thumb/Music/v4/aa/source/1024x1024bb.jpg", apple), CoverUrls.candidates(apple, 1000))
        assertEquals("https://e-cdns-images.dzcdn.net/images/cover/abc/768x768-000000-80-0-0.jpg",
            CoverUrls.candidates("https://e-cdns-images.dzcdn.net/images/cover/abc/250x250-000000-80-0-0.jpg", 600).first())
        // Deezer serves 1000 px at most.
        assertEquals("https://e-cdns-images.dzcdn.net/images/cover/abc/1000x1000-000000-80-0-0.jpg",
            CoverUrls.candidates("https://e-cdns-images.dzcdn.net/images/cover/abc/250x250-000000-80-0-0.jpg", 2000).first())
        assertEquals("https://lh3.googleusercontent.com/abc=w512-h512-l90-rj", CoverUrls.candidates("https://lh3.googleusercontent.com/abc=w120-h120-l90-rj", 500).first())
        assertEquals("https://yt3.ggpht.com/xyz=w256-h256-l90-rj", CoverUrls.candidates("https://yt3.ggpht.com/xyz=s88-c-k-c0x00ffffff-no-rj", 200).first())
        assertEquals("https://i1.sndcdn.com/artworks-xyz-t500x500.jpg", CoverUrls.candidates("https://i1.sndcdn.com/artworks-xyz-large.jpg", 400).first())
        assertEquals("https://resources.tidal.com/images/a/b/1280x1280.jpg", CoverUrls.candidates("https://resources.tidal.com/images/a/b/320x320.jpg", 1400).first())
    }

    @Test fun spotifyAndYouTubeFallBackThroughTheirSizes() {
        val small = "https://i.scdn.co/image/ab67616d00001e02cafe"
        assertEquals(listOf("https://i.scdn.co/image/ab67616d000082c1cafe", "https://i.scdn.co/image/ab67616d0000b273cafe", small),
            CoverUrls.candidates(small, 1000))
        assertEquals(listOf("https://i.scdn.co/image/ab67616d0000b273cafe", small), CoverUrls.candidates(small, 500))
        val yt = "https://i.ytimg.com/vi/ID/hqdefault.jpg?sqp=xyz"
        assertEquals(listOf("https://i.ytimg.com/vi/ID/maxresdefault.jpg", "https://i.ytimg.com/vi/ID/hq720.jpg",
            "https://i.ytimg.com/vi/ID/sddefault.jpg", yt), CoverUrls.candidates(yt, 1000))
    }

    @Test fun addressesAlreadyLargeOrUnknownStayAsGiven() {
        val big = "https://is1-ssl.mzstatic.com/image/thumb/a/3000x3000bb.jpg"
        assertEquals(listOf(big), CoverUrls.candidates(big, 1000))
        assertEquals(listOf("https://example.org/cover.jpg"), CoverUrls.candidates("https://example.org/cover.jpg", 2000))
        assertEquals(listOf("fixture:cover:1"), CoverUrls.candidates("fixture:cover:1", 600))
        assertTrue(CoverUrls.candidates(null, 600).isEmpty())
        assertTrue(CoverUrls.candidates("  ", 600).isEmpty())
    }

    @Test fun aMissingSizeFallsBackToTheGivenAddressAndIsNotAskedAgain() = runBlocking {
        val given = "https://i.scdn.co/image/ab67616d00001e02beef"
        val asked = mutableListOf<String>()
        Covers.fetchBytes = { url -> asked += url; if (url == given) png(300) else throw Covers.NotFound() }
        val image = assertNotNull(Covers.load(given, 900))
        assertEquals(300, image.width)
        assertEquals(given, asked.last())
        // Another place of the same size: from memory, nothing asked.
        val before = asked.size
        assertSame(image, Covers.load(given, 900))
        assertEquals(before, asked.size)
        // Out of memory: the disk has the given address; the sizes known missing are not asked again.
        Covers.trim(); asked.clear()
        assertNotNull(Covers.load(given, 900))
        assertTrue(asked.isEmpty(), "asked again: $asked")
    }

    @Test fun decodesAtTheShownSizeAndNeverReplacesALargerOne() = runBlocking {
        Covers.fetchBytes = { png(2400) }
        val url = "https://example.org/big.png"
        assertEquals(256, assertNotNull(Covers.load(url, 200)).width)
        assertNull(Covers.cachedFor(url, 1400), "a thumbnail is not enough for the full player")
        val sharp = assertNotNull(Covers.load(url, 1400))
        assertEquals(1536, sharp.width)
        // A thumbnail asked later is served by the larger decode.
        assertSame(sharp, Covers.load(url, 100))
        // A late smaller decode does not replace it.
        Covers.put(url, assertNotNull(Covers.decode(png(64), 64)), 128)
        assertSame(sharp, Covers.cached(url))
    }

    @Test fun aFailedLoadIsTriedAgain() = runBlocking {
        var calls = 0
        Covers.fetchBytes = { calls++; if (calls == 1) null else png(200) }
        val url = "https://example.org/flaky.png"
        assertNull(Covers.load(url, 200))
        assertNotNull(Covers.load(url, 200), "a network failure is not remembered as missing")
        assertTrue(COVER_RETRY_MS.size >= 3 && COVER_RETRY_MS.first() == 0L)
    }

    @Test fun theStudiosCoversWinOverTheCatalogues() {
        val track = Track("t1", "Marée haute", "Nora Vale feat. Ilo", "Marée basse", coverUrl = "https://catalogue.example/t1.jpg")
        assertEquals("https://catalogue.example/t1.jpg", CoverSources.track(track))
        val album = MediaOverride(MediaOverrides.ALBUM, MediaOverrides.albumKey("Marée basse", "Nora Vale")!!, cover = "https://studio.example/album.jpg")
        MediaOverrides.replaceForTest(listOf(album))
        assertEquals("https://studio.example/album.jpg", CoverSources.track(track))
        assertEquals("https://studio.example/album.jpg", CoverSources.album("Marée basse", "Nora Vale", "https://catalogue.example/a.jpg"))
        val own = MediaOverride(MediaOverrides.TRACK, MediaOverrides.keyFor(track)!!, cover = "https://studio.example/track.jpg")
        val artist = MediaOverride(MediaOverrides.ARTIST, MediaOverrides.artistKey("Nora Vale")!!, cover = "https://studio.example/portrait.jpg",
            hero = "https://studio.example/page.jpg")
        MediaOverrides.replaceForTest(listOf(album, own, artist))
        assertEquals("https://studio.example/track.jpg", CoverSources.track(track))
        assertEquals("https://studio.example/portrait.jpg", CoverSources.artistPhoto("Nora Vale", "https://catalogue.example/n.jpg"))
        assertEquals("https://studio.example/page.jpg", CoverSources.artistHero("Nora Vale", "https://catalogue.example/n.jpg"))
        assertEquals("https://catalogue.example/x.jpg", CoverSources.artistHero("Someone Else", "https://catalogue.example/x.jpg"))
    }

    @Test fun aPlaylistShowsItsOwnCoverElseThePhonesMosaic() {
        assertEquals("https://spotify.example/p.jpg", CoverSources.playlist("https://spotify.example/p.jpg"))
        assertNull(CoverSources.playlist(" "))
        val tracks = (1..2).map { Track("p$it", "Titre $it", "A", "") }
        assertEquals(listOf("p1", "p2", null, null), PlaylistMosaic.tiles(tracks).map { it?.id })
        assertTrue(PlaylistMosaic.tiles(emptyList()).isEmpty())
        assertEquals("T", PlaylistMosaic.initial(tracks[0])); assertEquals("♪", PlaylistMosaic.initial(null))
        assertEquals(PlaylistMosaic.tint("Route du soir"), PlaylistMosaic.tint("Route du soir"))
    }

    @Test fun theDiskCacheNamesAnyAddressSafely() {
        val a = CoverDisk.key("https://lh3.googleusercontent.com/abc=w512-h512?x=1&y=2")
        assertTrue(a.matches(Regex("[0-9a-f]{40}")))
        CoverDisk.write("https://example.org/a.jpg", byteArrayOf(1, 2, 3))
        assertEquals(listOf<Byte>(1, 2, 3), CoverDisk.read("https://example.org/a.jpg")!!.toList())
        CoverDisk.drop("https://example.org/a.jpg")
        assertNull(CoverDisk.read("https://example.org/a.jpg"))
    }
}
