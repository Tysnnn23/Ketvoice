package com.ketvoice.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Each placed widget type, and how often at most it is sent to the launcher. */
internal enum class WidgetKind(val spacingMs: Long) {
    NowPlaying(WidgetRefresh.TRACK_SPACING_MS),
    Player(WidgetRefresh.LYRIC_SPACING_MS),
    Mini(WidgetRefresh.TRACK_SPACING_MS),
    Lyrics(WidgetRefresh.LYRIC_SPACING_MS),
    Ketmix(WidgetRefresh.TRACK_SPACING_MS),
    Week(WidgetRefresh.HISTORY_SPACING_MS),
    Mix(WidgetRefresh.HISTORY_SPACING_MS),
    Replay(WidgetRefresh.HISTORY_SPACING_MS),
}

/** Why the widgets need redrawing; each reason touches only the widgets that show it. */
internal enum class WidgetReason(val kinds: Set<WidgetKind>) {
    Track(setOf(WidgetKind.NowPlaying, WidgetKind.Player, WidgetKind.Mini, WidgetKind.Lyrics, WidgetKind.Ketmix)),
    Progress(setOf(WidgetKind.NowPlaying, WidgetKind.Player)),
    Lyric(setOf(WidgetKind.Player, WidgetKind.Lyrics)),
    Ketmix(setOf(WidgetKind.Ketmix)),
    History(setOf(WidgetKind.Week, WidgetKind.Mix, WidgetKind.Replay)),
}

internal object WidgetRefresh {
    /** The sung line: at most a little more than one redraw a second per widget type (a redraw sends its pictures again). */
    const val LYRIC_SPACING_MS = 750L
    /** A title and its cover arrive one after the other: two redraws at least this far apart. */
    const val TRACK_SPACING_MS = 250L
    const val HISTORY_SPACING_MS = 2_000L
    /** While a title plays and the screen is on, the progress bar moves this often. */
    const val PROGRESS_MS = 30_000L
}

/**
 * Coalesces redraw requests (host-tested): a widget type asked for again and again is sent at most once per its
 * [WidgetKind.spacingMs], always with the latest state, and nothing wakes up while nothing is pending.
 * Not thread-safe: the updater calls it under its own lock.
 */
internal class WidgetCoalescer {
    private val pending = LinkedHashSet<WidgetKind>()
    private val lastSent = HashMap<WidgetKind, Long>()

    fun request(reason: WidgetReason) { pending += reason.kinds }
    fun request(kinds: Collection<WidgetKind>) { pending += kinds }
    val idle: Boolean get() = pending.isEmpty()

    private fun dueAt(kind: WidgetKind): Long = lastSent[kind]?.let { it + kind.spacingMs } ?: Long.MIN_VALUE

    /** How long to wait at [now] before something may be sent; null when nothing is pending. */
    fun waitAt(now: Long): Long? {
        if (pending.isEmpty()) return null
        return pending.minOf { dueAt(it) }.let { if (it == Long.MIN_VALUE) 0L else (it - now).coerceAtLeast(0L) }
    }

    /** The widget types to send at [now]; they are no longer pending and count as sent. */
    fun take(now: Long): Set<WidgetKind> {
        val due = pending.filterTo(LinkedHashSet()) { dueAt(it) <= now }
        pending -= due
        due.forEach { lastSent[it] = now }
        return due
    }
}

/**
 * Whether the screen is on, for the widgets: nobody sees a home screen with the screen off, so the progress bar
 * and the sung line wait for it to come on (the line is brought up to date then).
 */
internal object ScreenState {
    private val _on = MutableStateFlow(true)
    val on: StateFlow<Boolean> = _on
    private var receiver: BroadcastReceiver? = null

    @Synchronized
    fun watch(context: Context) {
        if (receiver != null) return
        val app = context.applicationContext
        _on.value = com.ketvoice.app.player.LockscreenLyrics.defaultScreenOn(app)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> _on.value = true
                    Intent.ACTION_SCREEN_OFF -> _on.value = false
                }
            }
        }.also { r ->
            runCatching {
                androidx.core.content.ContextCompat.registerReceiver(app, r,
                    IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) },
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            }
        }
    }
}
