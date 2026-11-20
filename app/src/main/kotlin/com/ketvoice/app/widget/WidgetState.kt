package com.ketvoice.app.widget

import android.graphics.Bitmap

/**
 * What both widgets show (7.4): the title playing or last played (paused), or nothing ([empty]: « Rien en lecture »).
 * [NowPlayingWidgets] fills it from the player; [WidgetViews] turns it into RemoteViews.
 */
internal data class NowPlaying(
    val trackId: String? = null,
    val title: String = "",
    val artist: String = "",
    /** The listener wants it to play (play/pause shows pause). */
    val playing: Boolean = false,
    val liked: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** `SystemClock.elapsedRealtime()` when [positionMs] was read. */
    val atMs: Long = 0L,
    /** The cover, corners already rounded; null: none (yet). */
    val cover: Bitmap? = null,
    val palette: WidgetPalette = WidgetPalette.FALLBACK,
) {
    val empty: Boolean get() = title.isBlank()

    /** Where the title is at [now] (elapsedRealtime), within its length. */
    fun positionAt(now: Long): Long {
        val at = if (playing) positionMs + (now - atMs).coerceAtLeast(0) else positionMs
        return if (durationMs > 0) at.coerceIn(0, durationMs) else at.coerceAtLeast(0)
    }

    /** 0..[WidgetViews.PROGRESS_MAX] of the progress bar. */
    fun progressAt(now: Long): Int =
        if (durationMs <= 0) 0 else (positionAt(now) * WidgetViews.PROGRESS_MAX / durationMs).toInt().coerceIn(0, WidgetViews.PROGRESS_MAX)
}

/** Extras MainActivity reads when a widget, a tile or the lyrics notification opens it. */
internal object WidgetIntents {
    const val OPEN = "ketvoice.widget.open"
    /** [OPEN] values. */
    const val OPEN_LYRICS = "lyrics"
    const val OPEN_PLAYER = "player"
    /** A tile without a queue: resume what was playing. */
    const val OPEN_PLAY = "play"
}
