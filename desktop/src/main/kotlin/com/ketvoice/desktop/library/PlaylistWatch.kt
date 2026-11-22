package com.ketvoice.desktop.library

import android.content.Context
import android.content.SharedPreferences
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.PlaylistFollow
import com.ketvoice.app.data.PlaylistStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * « Playlists téléchargées à jour » (8.4) on Windows: a downloaded playlist that gains titles (from the listener's
 * other devices through the account, or a shared playlist) is noticed while Ketvoice runs, every six hours and a few
 * minutes after the playlists change from elsewhere, with the phone's rules ([PlaylistFollow]). What is new is offered
 * in the window ([pending]); « Télécharger » downloads only those titles. Nothing is ever deleted. Switch: Settings ›
 * Téléchargements, key `playlistUpdates` (on), shared with the phone.
 */
object PlaylistWatch {
    private const val PREFS = "playlist-updates"
    private const val KNOWN = "known"
    internal const val EVERY_MS = 6L * 3600_000L
    internal const val SOON_MS = 3L * 60_000L

    @Volatile private var prefs: SharedPreferences? = null
    private var scope: CoroutineScope? = null
    private var soon: Job? = null

    private val _pending = MutableStateFlow<List<PlaylistFollow.Update>>(emptyList())
    /** New titles of followed playlists, not yet downloaded or dismissed, one entry per playlist. */
    val pending: StateFlow<List<PlaylistFollow.Update>> = _pending.asStateFlow()

    /** Hooks into the playlists and checks now, then every six hours. */
    fun start(context: Context, scope: CoroutineScope) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        this.scope = scope
        PlaylistStore.ownAdditionHook = ::noteAdded
        PlaylistStore.elsewhereHook = ::checkSoon
        scope.launch { while (true) { runCatching { check() }; delay(EVERY_MS) } }
    }

    private fun known(): Map<String, Set<String>> = PlaylistFollow.decode(prefs?.getString(KNOWN, null))

    @Synchronized private fun remember(change: (Map<String, Set<String>>) -> Map<String, Set<String>>) {
        val p = prefs ?: return
        p.edit().putString(KNOWN, PlaylistFollow.encode(change(known()))).apply()
    }

    /** « Télécharger » from a playlist's menu: the playlist is followed from now on. */
    fun follow(playlist: LocalPlaylist) = remember { PlaylistFollow.followed(it, playlist) }

    /** Titles the listener added themselves are never offered as new. */
    fun noteAdded(playlistId: String, trackIds: Collection<String>) = remember { PlaylistFollow.added(it, playlistId, trackIds) }

    /** Playlists changed from elsewhere: a check a few minutes later (one at a time). */
    fun checkSoon() {
        if (!AppSettings.playlistUpdates.value) return
        val s = scope ?: return
        soon?.cancel()
        soon = s.launch { delay(SOON_MS); runCatching { check() } }
    }

    /** The titles on this computer and those on their way. */
    private fun here(): Set<String> = DesktopDownloader.items.value.mapTo(HashSet()) { it.track.id } + DesktopDownloader.tasks.value.keys

    /** One pass: remembers what each followed playlist holds and offers what is new. */
    @Synchronized fun check(playlists: List<LocalPlaylist> = PlaylistStore.lists.value, downloaded: Set<String> = here()): List<PlaylistFollow.Update> {
        if (!AppSettings.playlistUpdates.value || prefs == null) return emptyList()
        val result = PlaylistFollow.diff(playlists, downloaded, known())
        prefs?.edit()?.putString(KNOWN, PlaylistFollow.encode(result.known))?.apply()
        offer(result.updates)
        return result.updates
    }

    /** Adds [updates] to what is offered: a playlist's new titles join the ones it already had waiting. */
    internal fun offer(updates: List<PlaylistFollow.Update>) {
        if (updates.isEmpty()) return
        _pending.update { now ->
            val merged = now.associateBy { it.playlistId }.toMutableMap()
            updates.forEach { u -> merged[u.playlistId] = merged[u.playlistId]?.let { it.copy(name = u.name, trackIds = (it.trackIds + u.trackIds).distinct()) } ?: u }
            merged.values.toList()
        }
    }

    /** Downloads the new titles of [update] still in the playlist and not on this computer; returns how many started. */
    fun download(update: PlaylistFollow.Update, playlists: List<LocalPlaylist> = PlaylistStore.lists.value,
                 start: (com.ketvoice.app.data.Track) -> Unit = DesktopDownloader::download): Int {
        dismiss(update)
        val wanted = update.trackIds.toSet()
        val playlist = playlists.firstOrNull { it.id == update.playlistId } ?: return 0
        val tracks = playlist.entries.mapNotNull { it.track }.distinctBy { it.id }
            .filter { it.id in wanted && DesktopDownloader.fileOf(it.id) == null }
        tracks.forEach(start)
        return tracks.size
    }

    /** « Plus tard »: the offer goes; the titles stay known, so they are not offered again. */
    fun dismiss(update: PlaylistFollow.Update) = _pending.update { list -> list.filterNot { it.playlistId == update.playlistId } }

    /** Tests: forget the state (the preferences file stays). */
    internal fun resetForTest(p: SharedPreferences? = null) { prefs = p; _pending.value = emptyList(); scope = null; soon = null }
}
