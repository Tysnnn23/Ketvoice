package com.ketvoice.app.data

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class MediaOverridesTest {
    @After fun clear() = MediaOverrides.replaceForTest(emptyList())

    @Test fun keysAreTheNormalisedFormTheServerAccepts() {
        assertEquals("beyonce", MediaOverrides.normalize("  Beyoncé! "))
        assertEquals("one more time|daft punk", MediaOverrides.trackKey("One More Time", "Daft Punk"))
        assertEquals("discovery|daft punk", MediaOverrides.albumKey("Discovery", "Daft Punk, Pharrell Williams"))
        assertEquals("Same album from a featuring credit", MediaOverrides.albumKey("Random Access Memories", "Daft Punk feat. Pharrell"),
            MediaOverrides.albumKey("Random Access Memories", "Daft Punk"))
        assertEquals("simon garfunkel", MediaOverrides.artistKey("Simon & Garfunkel"))
        assertNull("Nothing to key on", MediaOverrides.trackKey("!!!", "Daft Punk"))
        assertNull(MediaOverrides.artistKey("  "))
        val key = MediaOverrides.trackKey("a".repeat(400), "b".repeat(400))!!
        assertTrue(key.length <= 160)
        assertTrue(Regex("^[\\p{L}\\p{N}]+(?: [\\p{L}\\p{N}]+)*\\|[\\p{L}\\p{N}]+(?: [\\p{L}\\p{N}]+)*$").matches(key))
        assertEquals("東京事変", MediaOverrides.normalize("東京事変"))
    }

    @Test fun theMainArtistIsTheFirstCredit() {
        assertEquals("Daft Punk", MediaOverrides.mainArtist("Daft Punk, Pharrell Williams"))
        assertEquals("Drake", MediaOverrides.mainArtist("Drake feat. Future"))
        assertEquals("Malcolm X", MediaOverrides.mainArtist("Malcolm X"))
        assertEquals("Stromae", MediaOverrides.mainArtist("Stromae x Pomme"))
    }

    @Test fun serverRowsDecodeAndRoundTrip() {
        val text = """{"overrides":[
            {"kind":"album","key":"discovery|daft punk","label":"Discovery — Daft Punk","coverURL":"https://cdn/c.jpg","canvasURL":null,"heroURL":null,"updatedAt":"2026-11-14"},
            {"kind":"artist","key":"daft punk","label":"Daft Punk","coverURL":null,"canvasURL":null,"heroURL":"https://cdn/h.jpg"},
            {"kind":"playlist","key":"x","coverURL":"https://cdn/x.jpg"},
            {"kind":"track","key":"a|b","coverURL":"http://insecure/x.jpg"}]}"""
        val decoded = MediaOverrides.decode(text)
        assertEquals(setOf("album:discovery|daft punk", "artist:daft punk"), decoded.keys)
        assertEquals("https://cdn/h.jpg", decoded["artist:daft punk"]?.hero)
        assertEquals(decoded, MediaOverrides.decode(MediaOverrides.encode(decoded.values)))
        assertTrue(MediaOverrides.decode("not json").isEmpty())
    }

    @Test fun aTitleTakesItsOwnVisualThenItsAlbumsThenTheCatalogues() {
        val track = Track("yt:1", "One More Time", "Daft Punk", "Discovery", coverUrl = "https://catalogue/1.jpg")
        assertEquals("https://catalogue/1.jpg", MediaOverrides.cover(track, track.coverUrl))
        assertNull(MediaOverrides.canvas(track))
        MediaOverrides.replaceForTest(listOf(MediaOverride("album", "discovery|daft punk", cover = "https://cdn/album.jpg", canvas = "https://cdn/album.mp4")))
        assertEquals("https://cdn/album.jpg", MediaOverrides.cover(track, track.coverUrl))
        assertEquals("https://cdn/album.mp4", MediaOverrides.canvas(track))
        assertEquals("https://cdn/album.jpg", MediaOverrides.albumCover("Discovery", "Daft Punk", null))
        MediaOverrides.replaceForTest(MediaOverrides.all.values + MediaOverride("track", "one more time|daft punk", cover = "https://cdn/title.jpg"))
        assertEquals("https://cdn/title.jpg", MediaOverrides.cover(track, track.coverUrl))
        assertEquals("The album Canvas still applies", "https://cdn/album.mp4", MediaOverrides.canvas(track))
        val other = Track("yt:2", "Aerodynamic", "Daft Punk", "Discovery")
        assertEquals("https://cdn/album.jpg", MediaOverrides.cover(other, null))
        assertEquals("https://catalogue/x.jpg", MediaOverrides.cover(Track("yt:3", "Get Lucky", "Daft Punk", "RAM"), "https://catalogue/x.jpg"))
    }

    @Test fun artistPhotosFallBackToEachOther() {
        MediaOverrides.replaceForTest(listOf(MediaOverride("artist", "daft punk", hero = "https://cdn/h.jpg")))
        assertEquals("https://cdn/h.jpg", MediaOverrides.artistHero("Daft Punk"))
        assertEquals("https://cdn/h.jpg", MediaOverrides.artistPhoto("Daft Punk", "https://catalogue/p.jpg"))
        assertEquals("https://catalogue/p.jpg", MediaOverrides.artistPhoto("Justice", "https://catalogue/p.jpg"))
        assertNull(MediaOverrides.artistHero("Justice"))
    }

    @Test fun visualTargetsNameWhatTheyChange() {
        val track = Track("yt:1", "One More Time", "Daft Punk", "Discovery")
        val target = VisualTarget.track(track)!!
        assertEquals("track", target.kind); assertEquals("one more time|daft punk", target.key)
        assertEquals("One More Time — Daft Punk", target.label)
        val reopened = VisualTarget.saved(MediaOverride("track", target.key, target.label, cover = "https://cdn/c.jpg"))
        assertEquals("One More Time", reopened.title); assertEquals("Daft Punk", reopened.subtitle); assertEquals(target.key, reopened.key)
        assertEquals("Daft Punk", VisualTarget.saved(MediaOverride("artist", "daft punk", "Daft Punk", hero = "https://h")).title)
        assertNull(VisualTarget.album("", "Daft Punk", null))
    }

    @Test fun aCanvasSwitchedOffBeatsTheCatalogueAndAnAlbumThatIsOff() {
        val one = Track("yt:1", "One More Time", "Daft Punk", "Discovery")
        val two = Track("yt:2", "Aerodynamic", "Daft Punk", "Discovery")
        assertFalse(MediaOverrides.canvasDisabled(one))
        // A title off: nothing plays for it, even if its album has a Canvas.
        MediaOverrides.replaceForTest(listOf(
            MediaOverride("album", "discovery|daft punk", canvas = "https://cdn/album.mp4"),
            MediaOverride("track", "one more time|daft punk", canvasOff = true)))
        assertTrue(MediaOverrides.canvasDisabled(one)); assertNull(MediaOverrides.canvas(one))
        assertFalse(MediaOverrides.canvasDisabled(two)); assertEquals("https://cdn/album.mp4", MediaOverrides.canvas(two))
        // An album that is off disables its titles...
        MediaOverrides.replaceForTest(listOf(MediaOverride("album", "discovery|daft punk", canvasOff = true)))
        assertTrue(MediaOverrides.canvasDisabled(one)); assertTrue(MediaOverrides.canvasDisabled(two))
        // ...except a title that has a Canvas of its own.
        MediaOverrides.replaceForTest(listOf(MediaOverride("album", "discovery|daft punk", canvasOff = true),
            MediaOverride("track", "aerodynamic|daft punk", canvas = "https://cdn/own.mp4")))
        assertFalse(MediaOverrides.canvasDisabled(two)); assertEquals("https://cdn/own.mp4", MediaOverrides.canvas(two))
        assertTrue(MediaOverrides.canvasDisabled(one))
        // A row that only says "off" is kept by the decoder and survives the cache.
        val text = """{"overrides":[{"kind":"track","key":"a|b","canvasOff":true}]}"""
        assertTrue(MediaOverrides.decode(text)["track:a|b"]!!.canvasOff)
        assertEquals(MediaOverrides.decode(text), MediaOverrides.decode(MediaOverrides.encode(MediaOverrides.decode(text).values)))
        assertTrue("An empty row is not kept", MediaOverrides.decode("""{"overrides":[{"kind":"track","key":"a|b"}]}""").isEmpty())
    }

    @Test fun savingForSeveralTitlesReplacesExactlyThem() {
        MediaOverrides.replaceForTest(listOf(MediaOverride("track", "a|x", canvasOff = true), MediaOverride("track", "b|x", canvasOff = true),
            MediaOverride("album", "al|x", cover = "https://cdn/c.jpg")))
        // The server kept only "b|x" (off) and added "c|x": "a|x" went back to the catalogue's own.
        MediaOverrides.acceptMany("track", listOf("a|x", "b|x", "c|x"),
            listOf(MediaOverride("track", "b|x", canvasOff = true), MediaOverride("track", "c|x", canvas = "https://cdn/c.mp4")))
        assertNull(MediaOverrides.get("track", "a|x")); assertTrue(MediaOverrides.get("track", "b|x")!!.canvasOff)
        assertEquals("https://cdn/c.mp4", MediaOverrides.get("track", "c|x")!!.canvas)
        assertEquals("An override of another kind is untouched", "https://cdn/c.jpg", MediaOverrides.get("album", "al|x")!!.cover)
    }
}
