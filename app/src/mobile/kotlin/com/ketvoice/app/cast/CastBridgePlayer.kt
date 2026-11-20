package com.ketvoice.app.cast

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaStatus

/**
 * What the media session shows while casting. Two things CastPlayer does not say are said here:
 *  - the end of a title: the receiver goes idle (« finished ») and CastPlayer reports STATE_IDLE; Player.kt moves
 *    the queue on STATE_ENDED, as with the phone's engine, so a finished title is reported as ended;
 *  - a title the receiver could not play (« error »): reported as a player error, so Player.kt tries its next
 *    source, as it does on the phone.
 * It also remembers the app's last item, so the phone takes up the same title when the session ends.
 */
@UnstableApi
internal class CastBridgePlayer(
    player: Player,
    /** The receiver's idle reason (MediaStatus.IDLE_REASON_*), or null when it is not idle. */
    private val idleReason: () -> Int?,
    /** False when the title cannot go to the receiver (the reason is said by the caller). */
    private val accept: (MediaItem) -> Boolean
) : ForwardingPlayer(player) {
    private val listeners = HashMap<Player.Listener, Player.Listener>()
    var lastItem: MediaItem? = null
        private set
    private var error: PlaybackException? = null

    override fun getPlaybackState(): Int = map(super.getPlaybackState())
    override fun getPlayerError(): PlaybackException? = error ?: super.getPlayerError()

    private fun map(state: Int): Int =
        if (state == Player.STATE_IDLE && idleReason() == MediaStatus.IDLE_REASON_FINISHED && lastItem != null) Player.STATE_ENDED else state

    override fun setMediaItem(mediaItem: MediaItem) = load(mediaItem, 0L)
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = load(mediaItem, startPositionMs)
    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = load(mediaItem, if (resetPosition) 0L else currentPosition)
    override fun setMediaItems(mediaItems: MutableList<MediaItem>) { mediaItems.firstOrNull()?.let { load(it, 0L) } }
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) { mediaItems.firstOrNull()?.let { load(it, 0L) } }
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
        mediaItems.getOrNull(startIndex.coerceAtLeast(0))?.let { load(it, startPositionMs.coerceAtLeast(0L)) }
    }

    private fun load(item: MediaItem, positionMs: Long) {
        lastItem = item
        error = null
        if (!accept(item)) return
        super.setMediaItems(mutableListOf(item), 0, positionMs)
    }

    override fun addListener(listener: Player.Listener) {
        super.addListener(listeners.getOrPut(listener) { Translating(listener) })
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)?.let { super.removeListener(it) }
    }

    private inner class Translating(private val target: Player.Listener) : Player.Listener by target {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_IDLE && idleReason() == MediaStatus.IDLE_REASON_ERROR && lastItem != null && error == null) {
                val failed = PlaybackException("Cast receiver could not play the title", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
                error = failed
                CastState.say("L'appareil Cast n'a pas pu lire ce titre")
                target.onPlaybackStateChanged(playbackState)
                target.onPlayerError(failed)
                return
            }
            target.onPlaybackStateChanged(map(playbackState))
        }
        override fun equals(other: Any?) = other is Translating && other.target == target
        override fun hashCode() = target.hashCode()
    }
}
