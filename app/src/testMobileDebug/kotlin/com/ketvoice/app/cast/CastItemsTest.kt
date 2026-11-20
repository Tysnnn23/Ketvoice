package com.ketvoice.app.cast

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.google.android.gms.cast.MediaInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The title that plays as the Default Media Receiver reads it, and back to the app's own item. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CastItemsTest {
    private fun item(uri: String, mime: String?, art: String?) = MediaItem.Builder().setMediaId("kv:42").setUri(uri).setMimeType(mime)
        .setMediaMetadata(MediaMetadata.Builder().setTitle("Nuit blanche").setArtist("Ketvoice Band").setAlbumTitle("Lumières")
            .setArtworkUri(art?.let(Uri::parse)).build()).build()

    private val published = mutableListOf<String>()
    private val converter = CastItemConverter { uri, _ -> published += uri; "http://192.168.1.5:4000/t/${published.size}" }

    @Test fun aStreamKeepsItsAddressTypeAndMetadata() {
        val q = converter.toMediaQueueItem(item("https://cdn.example/a.webm", "audio/webm; codecs=\"opus\"", "https://img.example/c.jpg"))
        val info = q.media!!
        assertEquals("https://cdn.example/a.webm", info.contentUrl)
        assertEquals("audio/webm", info.contentType)
        assertEquals(MediaInfo.STREAM_TYPE_BUFFERED, info.streamType)
        val meta = info.metadata!!
        assertEquals("Nuit blanche", meta.getString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE))
        assertEquals("Ketvoice Band", meta.getString(com.google.android.gms.cast.MediaMetadata.KEY_ARTIST))
        assertEquals("Lumières", meta.getString(com.google.android.gms.cast.MediaMetadata.KEY_ALBUM_TITLE))
        assertEquals("https://img.example/c.jpg", meta.images.single().url.toString())
        assertEquals(emptyList<String>(), published)
    }

    @Test fun aDownloadAndItsCoverAreServedByThePhone() {
        val q = converter.toMediaQueueItem(item("file:///data/downloads/a.m4a", null, "file:///data/covers/a.jpg"))
        assertEquals("http://192.168.1.5:4000/t/1", q.media!!.contentUrl)
        assertEquals("audio/mp4", q.media!!.contentType)
        assertEquals("http://192.168.1.5:4000/t/2", q.media!!.metadata!!.images.single().url.toString())
        assertEquals(listOf("file:///data/downloads/a.m4a", "file:///data/covers/a.jpg"), published)
    }

    @Test fun theItemComesBackAsTheAppsOwn() {
        val original = item("file:///data/downloads/a.m4a", "audio/mp4", "https://img.example/c.jpg")
        val back = converter.toMediaItem(converter.toMediaQueueItem(original))
        assertEquals("kv:42", back.mediaId)
        assertEquals("file:///data/downloads/a.m4a", back.localConfiguration!!.uri.toString())
        assertEquals("audio/mp4", back.localConfiguration!!.mimeType)
        assertEquals("Nuit blanche", back.mediaMetadata.title.toString())
        assertEquals("https://img.example/c.jpg", back.mediaMetadata.artworkUri.toString())
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private class Idle : androidx.media3.common.SimpleBasePlayer(android.os.Looper.getMainLooper()) {
        override fun getState(): State = State.Builder().setPlaybackState(androidx.media3.common.Player.STATE_IDLE).build()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun aFinishedTitleIsReportedEndedSoTheAppMovesTheQueue() {
        var reason: Int? = com.google.android.gms.cast.MediaStatus.IDLE_REASON_FINISHED
        val refused = mutableListOf<String>()
        val bridge = CastBridgePlayer(Idle(), { reason }) { item ->
            (CastItemConverter.plan(item) !is CastSource.Unsupported).also { if (!it) refused += item.mediaId }
        }
        // Nothing handed over yet: an idle receiver is just idle.
        assertEquals(androidx.media3.common.Player.STATE_IDLE, bridge.playbackState)
        bridge.setMediaItem(item("https://a/b.mp3", null, null), 1_000L)
        assertEquals(androidx.media3.common.Player.STATE_ENDED, bridge.playbackState)
        reason = com.google.android.gms.cast.MediaStatus.IDLE_REASON_INTERRUPTED
        assertEquals(androidx.media3.common.Player.STATE_IDLE, bridge.playbackState)
        // An offline DASH download is not handed over, but is remembered for the return to the phone.
        val dash = MediaItem.Builder().setMediaId("kv:dash").setUri("file:///d/manifest.mpd")
            .setMediaMetadata(MediaMetadata.Builder().setExtras(android.os.Bundle().apply {
                putBoolean(com.ketvoice.app.player.OFFLINE_DASH_EXTRA, true) }).build()).build()
        bridge.setMediaItem(dash)
        assertEquals(listOf("kv:dash"), refused)
        assertEquals("kv:dash", bridge.lastItem?.mediaId)
    }

    @Test fun noCoverWhenTheReceiverCouldNotFetchIt() {
        assertNull(CastItems.artworkFor(item("https://a/b.mp3", null, "android.resource://x/1")) { _, _ -> "never" })
        assertEquals(0, converter.toMediaQueueItem(item("https://a/b.mp3", null, null)).media!!.metadata!!.images.size)
    }
}
