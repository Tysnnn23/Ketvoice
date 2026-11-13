package com.ketvoice.app.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import android.content.Context
import android.os.Bundle
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.Downloader

internal const val OFFLINE_DASH_EXTRA = "ketvoice.offline_dash"

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun playbackMediaItem(track: Track, url: String, cacheKey: String?, stream: MusicApi.StreamInfo?,
                               offlineDash: Boolean = false): MediaItem =
    MediaItem.Builder().setUri(url).setMediaId(track.id)
        .setMimeType(if (stream?.isDash == true) MimeTypes.APPLICATION_MPD else stream?.mimeType?.substringBefore(';')?.trim())
        .setCustomCacheKey(if (stream?.isDash == true) null else cacheKey)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
            .setAlbumTitle(track.album)
            // Same artwork as the app (the enriched cover when known) for the notification and lock screen.
            .setArtworkUri((com.ketvoice.app.data.ArtworkRepo.coverForTrack(track) ?: track.coverUrl)?.let(Uri::parse))
            .setExtras(if (offlineDash) Bundle().apply { putBoolean(OFFLINE_DASH_EXTRA, true) } else null).build())
        .build()

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun playbackMediaSourceFactory(context: Context): MediaSource.Factory {
    val standard = DefaultMediaSourceFactory(AudioCache.factory(context))
    // MPD URLs can be stable while their signed segment URLs expire; never cache a stale manifest.
    val dash = DashMediaSource.Factory(AudioCache.networkFactory(context))
    return object : MediaSource.Factory by standard {
        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            if (mediaItem.mediaMetadata.extras?.getBoolean(OFFLINE_DASH_EXTRA) == true) {
                val saved = Downloader.itemFor(mediaItem.mediaId)
                require(saved?.dashManifestUrl != null && saved.dashManifestUrl == mediaItem.localConfiguration?.uri.toString())
                val offline = OfflineDashStore.get(context)
                return DashMediaSource.Factory(offline.playbackFactory(mediaItem.mediaId))
                    .setManifestParser(offline.manifestParser()).createMediaSource(mediaItem)
            }
            return if (mediaItem.localConfiguration?.mimeType == MimeTypes.APPLICATION_MPD) dash.createMediaSource(mediaItem)
                else standard.createMediaSource(mediaItem)
        }
    }
}
