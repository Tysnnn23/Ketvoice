package com.ketvoice.app.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import com.ketvoice.app.MainActivity
import com.ketvoice.app.R
import com.ketvoice.app.data.AppText

/**
 * [NowPlaying] → RemoteViews for both widgets (7.4). Every view a layout has is set on every update, so the sample
 * the layouts carry for the picker's preview never shows on a real widget. Host-tested (`NowPlayingWidgetsHostTest`).
 */
internal object WidgetViews {
    const val PROGRESS_MAX = 1000
    /** From this height (dp) « En cours + contrôles » shows the times under its progress bar. */
    const val FULL_HEIGHT_DP = 140
    const val DIMMED_ALPHA = 77

    fun small(context: Context, s: NowPlaying, now: Long, ground: Bitmap): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_now_playing_small).also { common(context, it, s, ground) }

    fun controls(context: Context, s: NowPlaying, now: Long, ground: Bitmap, compact: Boolean): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_now_playing_controls).also { v ->
            common(context, v, s, ground)
            val ink = s.palette.ink
            for (id in intArrayOf(R.id.widget_previous, R.id.widget_next, R.id.widget_like)) v.setInt(id, "setColorFilter", ink)
            v.setContentDescription(R.id.widget_previous, AppText.t("Précédent"))
            v.setContentDescription(R.id.widget_next, AppText.t("Suivant"))
            if (s.empty) {
                // Nothing to skip, like or follow: the transport stays in place, quiet; a tap there opens Ketvoice.
                v.setViewVisibility(R.id.widget_like, View.GONE)
                v.setViewVisibility(R.id.widget_progress_box, View.INVISIBLE)
                v.setViewVisibility(R.id.widget_times, View.GONE)
                for (id in intArrayOf(R.id.widget_previous, R.id.widget_next)) v.setInt(id, "setImageAlpha", DIMMED_ALPHA)
                return@also
            }
            for (id in intArrayOf(R.id.widget_previous, R.id.widget_next)) v.setInt(id, "setImageAlpha", 255)
            v.setOnClickPendingIntent(R.id.widget_previous, action(context, WidgetActionReceiver.PREVIOUS))
            v.setOnClickPendingIntent(R.id.widget_next, action(context, WidgetActionReceiver.NEXT))
            v.setViewVisibility(R.id.widget_like, View.VISIBLE)
            v.setImageViewResource(R.id.widget_like, if (s.liked) R.drawable.ic_widget_heart_filled else R.drawable.ic_widget_heart)
            v.setContentDescription(R.id.widget_like, AppText.t(if (s.liked) "Retirer des favoris" else "Ajouter aux favoris"))
            v.setOnClickPendingIntent(R.id.widget_like, action(context, WidgetActionReceiver.LIKE))
            v.setViewVisibility(R.id.widget_progress_box, View.VISIBLE)
            progress(v, s, now)
            times(v, s, now, compact)
        }

    /** Only the progress bar, for the partial update while a title plays. */
    fun progressOnly(context: Context, s: NowPlaying, now: Long): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_now_playing_controls).also { progress(it, s, now) }

    private fun common(context: Context, v: RemoteViews, s: NowPlaying, ground: Bitmap) {
        val p = s.palette
        v.setImageViewBitmap(R.id.widget_ground, ground)
        if (s.cover != null) v.setImageViewBitmap(R.id.widget_cover, s.cover) else v.setImageViewResource(R.id.widget_cover, R.drawable.widget_cover_empty)
        v.setTextViewText(R.id.widget_title, if (s.empty) AppText.t("Rien en lecture") else s.title)
        v.setTextViewText(R.id.widget_artist, if (s.empty) "Ketvoice" else s.artist)
        v.setTextColor(R.id.widget_title, p.ink)
        v.setTextColor(R.id.widget_artist, p.inkSoft)
        v.setInt(R.id.widget_play_disc, "setColorFilter", p.ink)
        v.setInt(R.id.widget_play_glyph, "setColorFilter", p.bottom)
        v.setImageViewResource(R.id.widget_play_glyph, if (s.playing && !s.empty) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
        v.setContentDescription(R.id.widget_play, AppText.t(if (s.playing && !s.empty) "Pause" else "Lecture"))
        v.setOnClickPendingIntent(R.id.widget_play, action(context, if (s.empty) WidgetActionReceiver.PLAY else WidgetActionReceiver.TOGGLE))
        v.setOnClickPendingIntent(android.R.id.background, openPlayer(context))
        v.setContentDescription(android.R.id.background, if (s.empty) AppText.t("Rien en lecture") else "${s.title}, ${s.artist}")
    }

    private fun progress(v: RemoteViews, s: NowPlaying, now: Long) {
        val shown = if (s.palette.lightInk) R.id.widget_progress_light else R.id.widget_progress_dark
        val hidden = if (s.palette.lightInk) R.id.widget_progress_dark else R.id.widget_progress_light
        v.setViewVisibility(shown, View.VISIBLE)
        v.setViewVisibility(hidden, View.GONE)
        v.setProgressBar(shown, PROGRESS_MAX, s.progressAt(now), false)
    }

    /**
     * The times: ticking chronometers while the title plays (the launcher moves them, Ketvoice sends nothing), plain
     * text while paused (a stopped chronometer would drift when the launcher redraws it later).
     */
    private fun times(v: RemoteViews, s: NowPlaying, now: Long, compact: Boolean) {
        if (compact || s.durationMs <= 0) { v.setViewVisibility(R.id.widget_times, View.GONE); return }
        v.setViewVisibility(R.id.widget_times, View.VISIBLE)
        val position = s.positionAt(now)
        val remaining = (s.durationMs - position).coerceAtLeast(0)
        val ticking = s.playing
        for (id in intArrayOf(R.id.widget_elapsed, R.id.widget_remaining, R.id.widget_elapsed_clock, R.id.widget_remaining_clock))
            v.setTextColor(id, s.palette.inkSoft)
        v.setViewVisibility(R.id.widget_elapsed, if (ticking) View.GONE else View.VISIBLE)
        v.setViewVisibility(R.id.widget_remaining, if (ticking) View.GONE else View.VISIBLE)
        v.setViewVisibility(R.id.widget_elapsed_clock, if (ticking) View.VISIBLE else View.GONE)
        v.setViewVisibility(R.id.widget_remaining_clock, if (ticking) View.VISIBLE else View.GONE)
        if (ticking) {
            v.setChronometer(R.id.widget_elapsed_clock, now - position, null, true)
            v.setChronometer(R.id.widget_remaining_clock, now + remaining, "−%s", true)
            v.setChronometerCountDown(R.id.widget_remaining_clock, true)
        } else {
            v.setChronometer(R.id.widget_elapsed_clock, now, null, false)
            v.setChronometer(R.id.widget_remaining_clock, now, null, false)
            v.setTextViewText(R.id.widget_elapsed, DateUtils.formatElapsedTime(position / 1000))
            v.setTextViewText(R.id.widget_remaining, "−" + DateUtils.formatElapsedTime(remaining / 1000))
        }
    }

    /** A tap on the card: the player (Ketvoice opens on it; without a title, on its home). */
    fun openPlayer(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(WidgetIntents.OPEN, WidgetIntents.OPEN_PLAYER)
        return PendingIntent.getActivity(context, 740, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** A button: a broadcast to [WidgetActionReceiver], which acts without opening Ketvoice. */
    fun action(context: Context, action: String): PendingIntent {
        val intent = Intent(context, WidgetActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(context, WidgetActionReceiver.requestCode(action), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
