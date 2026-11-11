package com.ketvoice.app.player

import android.content.Context
import androidx.media3.common.MimeTypes
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.core.app.ApplicationProvider
import com.ketvoice.app.data.CustomSource
import com.ketvoice.app.data.CustomSources
import com.ketvoice.app.data.Track
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackMediaItemHostTest {
    private val track = Track("fixture", "Title", "Artist", "Album")
    private val source = CustomSource("test", "https://source.example/manifest.json", "Test", "https://source.example")

    @Test fun media3TreatsTheOldExtensionlessItemWithoutMimeAsProgressive() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val oldItem = MediaItem.Builder().setUri("https://source.example/tidal-mpd/573293?quality=low").build()
        assertTrue(DefaultMediaSourceFactory(AudioCache.factory(context)).createMediaSource(oldItem) is ProgressiveMediaSource)
    }

    @Test fun extensionlessDashSelectsDashMediaSourceAndDoesNotUseAFileCacheKey() {
        val stream = CustomSources.parseStreamResponse("""{"url":"https://source.example/tidal-mpd/573293","format":"dash","codec":"flac","mimeType":"application/dash+xml"}""", source)!!
        val item = playbackMediaItem(track, stream.url, "file-cache-key", stream)
        assertEquals(MimeTypes.APPLICATION_MPD, item.localConfiguration!!.mimeType)
        assertNull(item.localConfiguration!!.customCacheKey)
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(playbackMediaSourceFactory(context).createMediaSource(item) is DashMediaSource)
    }

    @Test fun progressiveFlacKeepsItsMimeAndCacheKey() {
        val stream = CustomSources.parseStreamResponse("""{"url":"https://source.example/audio","format":"flac","mimeType":"audio/flac"}""", source)!!
        val item = playbackMediaItem(track, stream.url, "file-cache-key", stream)
        assertEquals("audio/flac", item.localConfiguration!!.mimeType)
        assertEquals("file-cache-key", item.localConfiguration!!.customCacheKey)
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(playbackMediaSourceFactory(context).createMediaSource(item) is ProgressiveMediaSource)
    }
}
