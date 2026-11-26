package com.ketvoice.desktop.library

import android.content.Context
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.player.Player
import com.ketvoice.desktop.platform.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * What the owner publishes from the phone's Studio (covers, Canvas, artist photos and pages, « Canvas désactivé », the
 * album and player framings) reaches this PC from the same place and by the same rules as the phone: the shared
 * [MediaOverrides] (the public `ketvoice-content/overrides` list, read only: nothing here can publish), kept on disk
 * so the window opens with it, fetched again every 15 minutes as on the phone, and here also every
 * [ACTIVE_EVERY_MS] while the window is in use and [SOON_MS] after it comes to the front or the player opens.
 * The list is served `no-cache` and every file is named per upload, so a new cover or Canvas is a new address: the
 * covers' disk cache never shows an old one, and the Canvas of the title playing and the next two are looked up again.
 */
object StudioContent {
    const val ACTIVE_EVERY_MS = 2 * 60_000L
    /** The window counts as in use this long after its last focus (or while music plays). */
    const val ACTIVE_FOR_MS = 15 * 60_000L
    const val SOON_MS = 20_000L

    @Volatile private var lastActive = 0L
    @Volatile private var lastFetch = 0L
    @Volatile private var scope: CoroutineScope? = null
    /** The fetch, replaced by the tests. */
    internal var refresh: suspend () -> Unit = { MediaOverrides.refresh() }

    /** The cached list now (for the first frame), before the window opens. */
    fun load(context: Context) {
        runCatching { MediaOverrides.init(context) }.onFailure { AppLog.w("studio", "overrides: $it") }
        runCatching { ArtworkRepo.init(context) }
    }

    fun start(context: Context, scope: CoroutineScope) {
        load(context)
        this.scope = scope
        lastFetch = System.currentTimeMillis() // MediaOverrides.init fetches once now
        scope.launch {
            while (true) {
                delay(30_000L)
                if (Plan.due(System.currentTimeMillis(), lastFetch, lastActive, Player.state.value.isPlaying)) fetch()
            }
        }
        // A published Canvas or switch: the title playing and the next two show the new one.
        scope.launch { MediaOverrides.revision.drop(1).collect { reresolveQueue() } }
    }

    /** The window came to the front, or the player opened. */
    fun touch(now: Long = System.currentTimeMillis()) {
        lastActive = now
        if (Plan.soon(now, lastFetch)) scope?.launch { fetch() }
    }

    private suspend fun fetch() {
        lastFetch = System.currentTimeMillis()
        runCatching { refresh() }
    }

    private fun reresolveQueue() {
        val state = Player.state.value
        val current = state.current ?: return
        CanvasRepo.request(current)
        (1..2).mapNotNull { state.queue.getOrNull(state.index + it) }.forEach { CanvasRepo.request(it, ahead = true) }
    }

    /** When to fetch: pure, for the tests. */
    internal object Plan {
        fun due(now: Long, lastFetch: Long, lastActive: Long, playing: Boolean): Boolean =
            (playing || now - lastActive < ACTIVE_FOR_MS) && now - lastFetch >= ACTIVE_EVERY_MS

        fun soon(now: Long, lastFetch: Long): Boolean = now - lastFetch >= SOON_MS
    }
}
