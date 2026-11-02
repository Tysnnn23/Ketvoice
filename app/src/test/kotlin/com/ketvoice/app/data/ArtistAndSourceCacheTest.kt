package com.ketvoice.app.data

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ArtistAndSourceCacheTest {

    @After fun resetPriorities() {
        SourcePriority.playbackOrder.value = emptyList()
        SourcePriority.downloadOrder.value = emptyList()
        SourcePriority.playbackDisabled.value = emptySet()
        SourcePriority.downloadDisabled.value = emptySet()
    }

    private fun stream(url: String, format: String, quality: String) = MusicApi.StreamInfo(url, format, quality, url)

    @Test fun playbackAndDownloadKeepIndependentOrdersAndSwitches() {
        SourcePriority.playbackOrder.value = listOf("api:um", "api:last", "youtube")
        SourcePriority.downloadOrder.value = listOf("api:last", "youtube", "api:um")
        SourcePriority.downloadDisabled.value = setOf("youtube")
        assertEquals(listOf("api:um", "api:last", "youtube"), SourcePriority.entries(SourcePriority.Mode.PLAYBACK).map { it.id })
        val download = SourcePriority.entries(SourcePriority.Mode.DOWNLOAD)
        assertEquals(listOf("api:last", "youtube", "api:um"), download.map { it.id })
        assertFalse("A source disabled for downloads stays usable for playback",
            download.first { it.id == "youtube" }.enabled)
        assertTrue(SourcePriority.entries(SourcePriority.Mode.PLAYBACK).first { it.id == "youtube" }.enabled)
        SourcePriority.setEnabled("api:um", false, SourcePriority.Mode.DOWNLOAD)
        assertTrue(SourcePriority.entries(SourcePriority.Mode.PLAYBACK).first { it.id == "api:um" }.enabled)
        assertFalse(SourcePriority.entries(SourcePriority.Mode.DOWNLOAD).first { it.id == "api:um" }.enabled)
    }

    @Test fun movingASourceOnlyChangesThatModesOrder() {
        SourcePriority.playbackOrder.value = listOf("api:um", "youtube")
        SourcePriority.downloadOrder.value = listOf("api:um", "youtube")
        SourcePriority.move("youtube", -1, SourcePriority.Mode.DOWNLOAD)
        assertEquals(listOf("youtube", "api:um", "api:last"), SourcePriority.entries(SourcePriority.Mode.DOWNLOAD).map { it.id })
        assertEquals(listOf("api:um", "youtube", "api:last"), SourcePriority.entries(SourcePriority.Mode.PLAYBACK).map { it.id })
    }

    @Test fun downloadsPreferTheBestAvailableQualityOverThePriorityOrder() {
        val mp3 = StreamResolver.Candidate("api:um", 0, stream("https://a/mp3", "mp3", "128 kbps"))
        val flac = StreamResolver.Candidate("api:last", 1, stream("https://a/flac", "flac", "Sans perte"))
        val aac = StreamResolver.Candidate("youtube", 2, stream("https://a/aac", "aac", "256 kbps"))
        assertEquals(flac, StreamResolver.best(listOf(mp3, flac, aac)))
        assertEquals(aac, StreamResolver.best(listOf(mp3, aac)))
    }

    @Test fun bestWithinReturnsAsSoonAsEverySourceAnswered() = runBlocking {
        val candidates = flowOf(
            StreamResolver.Candidate("api:um", 0, stream("https://a/mp3", "mp3", "128 kbps")),
            StreamResolver.Candidate("youtube", 1, stream("https://a/aac", "aac", "160 kbps"))
        )
        val started = System.currentTimeMillis()
        val best = StreamResolver.bestWithin(candidates, 5_000)
        assertEquals("aac", best?.stream?.format)
        assertTrue("A completed flow must not wait the whole window", System.currentTimeMillis() - started < 1_000)
    }

    private fun samplePage(): ArtistPage {
        val track = Track("yt:abc", "Titre", "Artiste", "Album", durationMs = 180_000,
            coverUrl = "https://img.example/cover.jpg", videoId = "abc", youtubeCatalogVerified = true)
        return ArtistPage(
            MusicArtist("UC123", "Artiste", "https://img.example/photo.jpg"),
            "Biographie", listOf(track),
            listOf(Album("MPRE1", "Album", "Artiste", coverUrl = "https://img.example/a.jpg")),
            listOf(MusicArtist("UC456", "Similaire", null)),
            "1 234 auditeurs", 2.1f
        )
    }

    @Test fun artistPageSurvivesADiskRoundTripWithEveryFieldUsedByTheUi() {
        val page = samplePage()
        val decoded = ArtistCache.decode(JSONObject(ArtistCache.encode(page).toString()))!!
        assertEquals(page.artist, decoded.artist)
        assertEquals(page.description, decoded.description)
        assertEquals(page.listeners, decoded.listeners)
        assertEquals(page.heroAspectRatio, decoded.heroAspectRatio, .0001f)
        assertEquals(page.tracks.first().id, decoded.tracks.first().id)
        assertEquals(page.tracks.first().videoId, decoded.tracks.first().videoId)
        assertTrue(decoded.tracks.first().youtubeCatalogVerified)
        assertEquals("MPRE1", decoded.albums.first().id)
        assertEquals("UC456", decoded.related.first().id)
    }

    @Test fun aCachedMissIsStoredAsAVisibleAbsenceNotAsAFakeArtist() {
        assertNull(ArtistCache.decode(ArtistCache.encode(null)))
        assertEquals("name:juice wrld", ArtistCache.key(null, "  Juice   WRLD "))
        assertEquals("id:UC123", ArtistCache.key("UC123", "ignored"))
    }

    @Test fun artworkAndSignatureSurviveDiskRoundTrip() {
        val art = ArtistArtwork(
            photo = "https://assets.fanart.tv/fanart/x.jpg",
            background = null,
            signature = ArtistSignature("https://commons.wikimedia.org/wiki/Special:FilePath/s.png?width=600",
                "image", "https://www.wikidata.org/wiki/Q1", "Wikimedia Commons"),
            logo = null
        )
        val decoded = ArtworkCodec.decode(JSONObject(ArtworkCodec.encode(art).toString()))
        assertEquals(art.photo, decoded.photo)
        assertEquals(art.signature, decoded.signature)
        assertNull(decoded.logo)
        val refused = ArtworkCodec.decode(JSONObject().put("photo", "http://insecure/x.jpg"))
        assertNull("Only HTTPS artwork may be restored", refused.photo)
    }
}
