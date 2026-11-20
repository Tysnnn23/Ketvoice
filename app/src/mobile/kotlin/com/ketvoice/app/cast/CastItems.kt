package com.ketvoice.app.cast

import android.net.Uri
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.common.images.WebImage
import org.json.JSONObject

/**
 * The title that plays, as the Default Media Receiver reads it: the address it can fetch, the content type,
 * and the title, artist, album and cover for its screen. The original item travels in customData, so the item
 * CastPlayer reports back is the app's own (same media id, same address): Player.kt keeps following it, and the
 * phone takes it up again when the session ends.
 */
internal object CastItems {
    private const val KEY_ID = "ketvoiceId"
    private const val KEY_URI = "uri"
    private const val KEY_MIME = "mime"
    private const val KEY_ART = "art"

    /** [url] and [contentType] come from [CastPlan]; [artwork] is an address the receiver can fetch, if any. */
    fun queueItem(item: MediaItem, url: String, contentType: String, artwork: String?): MediaQueueItem {
        val meta = item.mediaMetadata
        val castMeta = com.google.android.gms.cast.MediaMetadata(com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MUSIC_TRACK)
        meta.title?.let { castMeta.putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, it.toString()) }
        meta.artist?.let { castMeta.putString(com.google.android.gms.cast.MediaMetadata.KEY_ARTIST, it.toString()) }
        meta.albumTitle?.let { castMeta.putString(com.google.android.gms.cast.MediaMetadata.KEY_ALBUM_TITLE, it.toString()) }
        artwork?.let { castMeta.addImage(WebImage(Uri.parse(it))) }
        val custom = JSONObject().put(KEY_ID, item.mediaId)
            .put(KEY_URI, item.localConfiguration?.uri?.toString().orEmpty())
            .put(KEY_MIME, item.localConfiguration?.mimeType.orEmpty())
            .put(KEY_ART, meta.artworkUri?.toString().orEmpty())
        val info = MediaInfo.Builder(url)
            .setContentUrl(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(contentType)
            .setMetadata(castMeta)
            .setCustomData(custom)
            .build()
        return MediaQueueItem.Builder(info).setAutoplay(true).setCustomData(custom).build()
    }

    /** Back to the app's item: its own id and address, the receiver's text where the app had none. */
    fun mediaItem(queueItem: MediaQueueItem): MediaItem {
        val info = queueItem.media
        val custom = queueItem.customData ?: info?.customData
        val meta = info?.metadata
        val uri = custom?.optString(KEY_URI)?.takeIf { it.isNotEmpty() } ?: info?.contentUrl ?: info?.contentId.orEmpty()
        val art = custom?.optString(KEY_ART)?.takeIf { it.isNotEmpty() } ?: meta?.images?.firstOrNull()?.url?.toString()
        return MediaItem.Builder()
            .setMediaId(custom?.optString(KEY_ID)?.takeIf { it.isNotEmpty() } ?: uri)
            .setUri(uri)
            .setMimeType(custom?.optString(KEY_MIME)?.takeIf { it.isNotEmpty() })
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(meta?.getString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE))
                .setArtist(meta?.getString(com.google.android.gms.cast.MediaMetadata.KEY_ARTIST))
                .setAlbumTitle(meta?.getString(com.google.android.gms.cast.MediaMetadata.KEY_ALBUM_TITLE))
                .setArtworkUri(art?.let(Uri::parse))
                .build())
            .build()
    }

    /** The cover as the receiver can fetch it: a web address as it is, a file or content address served by the phone. */
    fun artworkFor(item: MediaItem, publishLocal: (String, String) -> String?): String? {
        val art = item.mediaMetadata.artworkUri?.toString() ?: return null
        return when (art.substringBefore(':').lowercase()) {
            "http", "https" -> art
            "file", "content" -> publishLocal(art, "image/jpeg")
            else -> null
        }
    }
}

/** CastPlayer's converter: [CastPlan] decides; downloads and local covers go through the phone's server. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class CastItemConverter(private val publishLocal: (String, String) -> String?) : MediaItemConverter {
    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val (url, type) = when (val source = plan(mediaItem)) {
            is CastSource.Remote -> source.url to source.contentType
            is CastSource.Local -> (publishLocal(source.uri, source.contentType) ?: "") to source.contentType
            // CastBridgePlayer never hands these over; an empty address fails on the receiver, never in the app.
            is CastSource.Unsupported -> "" to "audio/mpeg"
        }
        return CastItems.queueItem(mediaItem, url, type, CastItems.artworkFor(mediaItem, publishLocal))
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem = CastItems.mediaItem(mediaQueueItem)

    companion object {
        fun plan(item: MediaItem): CastSource = CastPlan.decide(item.localConfiguration?.uri?.toString(),
            item.localConfiguration?.mimeType,
            item.mediaMetadata.extras?.getBoolean(com.ketvoice.app.player.OFFLINE_DASH_EXTRA) == true)
    }
}
