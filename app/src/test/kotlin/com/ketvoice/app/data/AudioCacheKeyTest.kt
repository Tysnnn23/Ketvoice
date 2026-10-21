package com.ketvoice.app.data

import com.ketvoice.app.player.AudioCache
import org.junit.Assert.*
import org.junit.Test

class AudioCacheKeyTest {
    @Test fun refreshedSignedUrlsKeepTheSameAudioKey() {
        val a = MusicApi.StreamInfo("https://audio.example/a?expires=1", "flac", "Lossless", "provider:track-42")
        val b = a.copy(url = "https://audio.example/a?expires=2")
        assertEquals(AudioCache.key("yt:42", a), AudioCache.key("yt:42", b))
        assertNotEquals(AudioCache.key("yt:42", a), AudioCache.key("yt:42", a.copy(cacheTag = "other-provider:42")))
    }
    @Test fun sliderHasExactlyTwentyHalfGigabyteSteps() {
        assertEquals(500_000_000L, AudioCache.STEP)
        assertEquals(20L, AudioCache.MAX / AudioCache.STEP)
    }
}
