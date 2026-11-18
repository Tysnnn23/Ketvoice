package com.ketvoice.app.widget

import android.graphics.Bitmap
import androidx.glance.action.ActionParameters

/**
 * What every widget shows (6.4). One updater ([KetvoiceWidgetsUpdater]) fills it, and only works while at least
 * one widget is placed.
 */
internal data class WidgetsState(
    val title: String = "",
    val artist: String = "",
    val playing: Boolean = false,
    val cover: Bitmap? = null,
    /** The gradient of the cover ([WidgetLook.ground]). */
    val ground: Bitmap? = null,
    /** The same colours without the cover's picture melted in, for "Votre semaine". */
    val plainGround: Bitmap? = null,
    val tint: WidgetTint = WidgetLook.DEFAULT,
    val progress: Float = 0f,
    /** The sung line and the next one, while synced lyrics play (from [com.ketvoice.app.player.LiveLyrics]). */
    val lyric: String? = null,
    val nextLyric: String? = null,
    val weekMinutes: Long = 0,
    val weekPlays: Int = 0,
    val weekArtist: String? = null,
    /** Listening of the last seven days, oldest first, each 0..1 of the longest day. */
    val weekBars: List<Float> = emptyList(),
    val mixId: String? = null,
    val mixLabel: String = "",
    val mixTitle: String = "",
    val mixCover: Bitmap? = null,
    val mixGround: Bitmap? = null,
    val ketmix: KetmixWidgetState = KetmixWidgetState(),
    val replay: ReplayWidgetState = ReplayWidgetState(),
) {
    val hasTrack: Boolean get() = title.isNotBlank()
}

internal data class KetmixWidgetState(
    val on: Boolean = false,
    /** Two titles are being mixed right now. */
    val mixing: Boolean = false,
    /** The title Ketmix will mix in next (the queue's next title), if any. */
    val nextTitle: String = "",
    val nextArtist: String = "",
    val nextCover: Bitmap? = null,
)

internal data class ReplayWidgetState(
    /** "2026-11". */
    val month: String = "",
    /** « Novembre », in the interface language. */
    val monthName: String = "",
    val minutes: Long = 0,
    val plays: Int = 0,
    val topArtist: String? = null,
    val topTitle: String? = null,
    val topTitleArtist: String? = null,
    val cover: Bitmap? = null,
    val ground: Bitmap? = null,
) {
    val empty: Boolean get() = plays <= 0 && minutes <= 0
}

/** Extras MainActivity reads when a widget, a tile or the lyrics notification opens it. */
internal object WidgetIntents {
    const val MIX = "ketvoice.widget.mix"
    const val MIX_TITLE = "ketvoice.widget.mixTitle"
    const val OPEN = "ketvoice.widget.open"
    /** [OPEN] values. */
    const val OPEN_LYRICS = "lyrics"
    const val OPEN_REPLAY = "replay"
    const val OPEN_PLAYER = "player"
    /** « Écouter » in an empty widget without a mix: resume what was playing. */
    const val OPEN_PLAY = "play"
    val mixKey = ActionParameters.Key<String>(MIX)
    val mixTitleKey = ActionParameters.Key<String>(MIX_TITLE)
    val openKey = ActionParameters.Key<String>(OPEN)
}

/** Seven bars from the daily listening: each as a share of the longest day, a sliver for days with a little. */
internal fun weekBars(daily: List<Long>): List<Float> {
    val longest = daily.maxOrNull() ?: 0L
    if (longest <= 0L) return daily.map { 0f }
    return daily.map { if (it <= 0L) 0f else (it.toFloat() / longest).coerceIn(.08f, 1f) }
}
