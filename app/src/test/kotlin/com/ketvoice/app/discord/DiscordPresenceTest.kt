package com.ketvoice.app.discord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordPresenceTest {

    private fun payload(
        title: String = "Lucid Dreams",
        artist: String = "Juice WRLD",
        cover: String? = "https://img.example/cover.jpg",
        playing: Boolean = true,
        positionMs: Long = 40_000,
        nowMs: Long = 1_000_000,
        listenUrl: String? = "https://tysnnn23.github.io/Ketvoice/listen.html?track=abc&title=Lucid%20Dreams"
    ) = DiscordPresence.fromTrack(title, artist, cover, playing, positionMs, nowMs, listenUrl)

    @Test fun pausedOrStoppedProducesNoPresence() {
        assertNull(payload(playing = false))
    }

    @Test fun discordTextLimitsAreEnforcedWithoutLosingTheArtist() {
        val long = "a".repeat(300)
        val result = payload(title = long, artist = long)!!
        assertEquals(128, result.details.length)
        assertEquals(128, result.state.length)
        assertTrue(result.details.endsWith("…"))
        assertEquals(128, result.largeText.length)
    }

    @Test fun whitespaceAndNewlinesAreCollapsed() {
        val result = payload(title = "  Lucid\n Dreams  ", artist = " Juice   WRLD ")!!
        assertEquals("Lucid Dreams", result.details)
        assertEquals("Juice WRLD", result.state)
    }

    @Test fun downloadButtonIsAlwaysFirstAndPointsToTheSite() {
        val result = payload()!!
        assertEquals(2, result.buttons.size)
        assertEquals(DiscordPresence.DOWNLOAD_LABEL, result.buttons[0].label)
        assertEquals("https://ketvoice.netlify.app/", result.buttons[0].url)
        assertEquals(DiscordPresence.LISTEN_LABEL, result.buttons[1].label)
    }

    @Test fun listenButtonOnlyAcceptsAShortPublicHttpsLink() {
        assertNull(payload(listenUrl = "http://insecure.example/track")!!.buttons.getOrNull(1))
        assertNull(payload(listenUrl = "ketvoice://listen?track=abc")!!.buttons.getOrNull(1))
        assertNull(payload(listenUrl = "https://example/" + "a".repeat(600))!!.buttons.getOrNull(1))
        assertNotNull(payload()!!.buttons.getOrNull(1))
    }

    @Test fun coverIsOnlyUsedForShortHttpsAssets() {
        assertNull(payload(cover = "file:///sdcard/cover.jpg")!!.largeImage)
        assertNull(payload(cover = "https://example/" + "a".repeat(400))!!.largeImage)
        assertEquals("https://img.example/cover.jpg", payload()!!.largeImage)
    }

    @Test fun elapsedTimerStartsAtTheCurrentPlaybackPosition() {
        assertEquals(960_000L, payload(positionMs = 40_000, nowMs = 1_000_000)!!.startEpochMs)
        assertEquals(1_000_000L, payload(positionMs = -5_000, nowMs = 1_000_000)!!.startEpochMs)
    }

    @Test fun blankMetadataFallsBackToTheAppName() {
        val result = payload(title = "   ", artist = "")!!
        assertEquals("Ketvoice", result.details)
        assertEquals("Ketvoice", result.state)
    }
}
