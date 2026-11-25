package com.ketvoice.app.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Smart share links: what is built, and what the app opens from a link (both directions agree). */
class ShareUrlsTest {
    @Test fun aTitleLinkCarriesItsNameAndASafeCover() {
        val url = ShareUrls.track("yt:abc def&x", "Ocean Drive", "Néon & co", "https://i.ytimg.com/vi/a/hq.jpg")!!
        assertEquals("https://ketvoice-web.vercel.app/listen?track=yt%3Aabc%20def%26x&title=Ocean%20Drive&artist=N%C3%A9on%20%26%20co" +
            "&cover=https%3A%2F%2Fi.ytimg.com%2Fvi%2Fa%2Fhq.jpg", url)
        val noCover = ShareUrls.track("yt:abcdefghijk", "T", "A", "https://evil.example/x.jpg")!!
        assertTrue(!noCover.contains("cover="))
    }

    @Test fun localFilesAreNeverShared() {
        assertNull(ShareUrls.track("local:file:C:/Music/x.mp3", "Mine", "Me"))
        assertNull(ShareUrls.track("file:///C:/x.mp3", "Mine", "Me"))
        assertNull(ShareUrls.track("yt:abcdefghijk", " ", "Me"))
        assertNull(ShareUrls.album("local:album:x", "Mine", "Me"))
    }

    @Test fun albumsPlaylistsProfiles() {
        assertEquals("https://ketvoice-web.vercel.app/album/MPREb_abcdefgh", ShareUrls.album("MPREb_abcdefgh", "Nuits", "Néon"))
        assertEquals("https://ketvoice-web.vercel.app/listen?track=kv%3Aum%3A1&title=Nuits&artist=N%C3%A9on&kind=album",
            ShareUrls.album("kv:um:1", "Nuits", "Néon"))
        assertEquals("https://ketvoice-web.vercel.app/listen?playlist=457f4543-5206-46b9-8a61-1db0e7faf11a&name=%C3%89t%C3%A9",
            ShareUrls.playlist("457f4543-5206-46b9-8a61-1db0e7faf11a", "Été"))
        assertNull(ShareUrls.playlist("pl-1", "Été"))
        assertEquals("https://ketvoice-web.vercel.app/u/luna.k", ShareUrls.profile("@Luna.K"))
        assertNull(ShareUrls.profile("a b"))
        assertTrue(ShareUrls.room("ABC234", "ABCDEFGH").contains("kind=together"))
    }

    @Test fun everyBuiltLinkOpensWhatItShares() {
        assertEquals(ShareUrls.Link.Track("yt:abc def&x", "Ocean Drive", "Néon & co"),
            ShareUrls.parse(ShareUrls.track("yt:abc def&x", "Ocean Drive", "Néon & co")!!))
        assertEquals(ShareUrls.Link.Album("kv:um:1", "Nuits", "Néon"), ShareUrls.parse(ShareUrls.album("kv:um:1", "Nuits", "Néon")!!))
        assertEquals(ShareUrls.Link.Album("MPREb_abcdefgh", "", ""), ShareUrls.parse(ShareUrls.album("MPREb_abcdefgh", "Nuits", "Néon")!!))
        assertEquals(ShareUrls.Link.Playlist("457f4543-5206-46b9-8a61-1db0e7faf11a", "Été"),
            ShareUrls.parse(ShareUrls.playlist("457f4543-5206-46b9-8a61-1db0e7faf11a", "Été")!!))
        assertEquals(ShareUrls.Link.Profile("luna.k"), ShareUrls.parse(ShareUrls.profile("luna.k")!!))
        val room = ShareUrls.parse(ShareUrls.room("ABC234", "ABCDEFGH")) as ShareUrls.Link.Room
        assertEquals("ABC234", room.target.code)
        assertEquals("ABCDEFGH", room.target.fingerprint)
    }

    @Test fun thePhonesOwnLinksOpenOnThisComputer() {
        assertEquals(ShareUrls.Link.Track("yt:abcdefghijk", "Ocean Drive", "Néon"),
            ShareUrls.parse("ketvoice://listen?track=yt%3Aabcdefghijk&title=Ocean+Drive&artist=N%C3%A9on&kind=track"))
        assertEquals(ShareUrls.Link.Profile("luna.k"), ShareUrls.parse("ketvoice://u/@Luna.K/"))
        assertEquals(ShareUrls.Link.Join("ABCDEFGH23"), ShareUrls.parse("ketvoice://join/abcdefgh23"))
        assertEquals(ShareUrls.Link.Artist("UCabcdefghijklmnop", "Néon"),
            ShareUrls.parse("ketvoice://listen?track=UCabcdefghijklmnop&artist=N%C3%A9on&kind=artist"))
        assertEquals(ShareUrls.Link.Track("yt:abcdefghijk", "T", "A"),
            ShareUrls.parse("https://tysnnn23.github.io/Ketvoice/listen.html?track=yt:abcdefghijk&title=T&artist=A"))
        assertTrue(ShareUrls.parse("ketvoice://listen?track=ABC234-ABCDEFGH&kind=together") is ShareUrls.Link.Room)
    }

    @Test fun anythingElseIsIgnored() {
        listOf("https://evil.example/listen?track=yt:x", "ketvoice://auth/account?code=x", "ketvoice://listen?track=x&kind=airket",
            "ketvoice://listen?track=local:file:x", "javascript:alert(1)", "C:\\Music\\x.mp3", "ketvoice://listen?playlist=../x",
            "https://ketvoice-web.vercel.app/album/../../etc", "ketvoice://u/a b", "ketvoice://listen?track=" + "a".repeat(3000)).forEach {
            assertNull(it, ShareUrls.parse(it))
        }
    }
}
