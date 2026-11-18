package com.ketvoice.app.data

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Titles made favourites are downloaded by themselves when the listener wants it (Settings →
 * Downloads): the Wi-Fi-only setting of downloads applies to them too. Since 7.0 they are automatic downloads
 * of [SmartOffline]: they carry the « Auto » badge, a favourite deleted by hand is not fetched again, and with
 * « Hors ligne intelligent » on they wait for its next pass on an unmetered network, within its limit.
 */
object FavoritesOffline {
    private var started = false

    fun start(context: android.content.Context? = null) {
        if (started) return
        started = true
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            var before: Set<String>? = null
            snapshotFlow { AppSettings.favoriteIds.value }.collect { now ->
                val previous = before
                before = now
                runCatching { SmartOffline.noteFavorites(now) }
                if (previous == null || !AppSettings.autoOfflineFavorites.value) return@collect
                for (id in added(previous, now)) {
                    val track = trackFor(id) ?: continue
                    SmartOffline.favoriteAdded(context, track)
                }
            }
        }
    }

    /** The favourites added between two states, in the order they appear. */
    internal fun added(before: Set<String>, now: Set<String>): List<String> = now.filter { it !in before }

    private fun trackFor(id: String): Track? =
        com.ketvoice.app.player.Player.state.value.current?.takeIf { it.id == id }
            ?: com.ketvoice.app.player.Player.state.value.queue.firstOrNull { it.id == id }
            ?: ListeningHistory.knownTracks.value.firstOrNull { it.id == id }
            ?: ListeningHistory.tracks.value.firstOrNull { it.id == id }
}
