package com.ketvoice.desktop.library

import androidx.compose.runtime.mutableStateOf
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.PlaylistFollow
import com.ketvoice.app.data.Track
import com.ketvoice.app.smartlib.ImportPreview
import com.ketvoice.app.smartlib.ImportReport
import com.ketvoice.app.smartlib.ImportSong
import com.ketvoice.app.smartlib.PlaylistImport
import com.ketvoice.app.smartlib.YouTubeImport
import com.ketvoice.desktop.platform.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * « Suivre la playlist d'origine » (Windows 2.0): a playlist imported from Spotify, Deezer, Apple Music, Tidal,
 * SoundCloud or YouTube can stay followed. Every twelve hours (and a few minutes after start) its public page is read
 * again; the songs it gained since the last check ([PlaylistFollow.outsideFresh]) are looked for in Ketvoice and either
 * added to the Ketvoice copy, or offered in a card (« Ajouter » / « Plus tard »), as « Ajouter automatiquement » says.
 * Songs removed there are never removed here. Stored in the `ketvoice_outside_playlists` preferences (this PC only).
 */
object OutsidePlaylists {
    data class Followed(val playlistId: String, val link: String, val name: String, val known: Set<String>, val checkedAt: Long = 0L)
    /** New songs found for a followed playlist, waiting for the listener. */
    data class Offer(val playlistId: String, val name: String, val tracks: List<Track>)

    private const val PREFS = "ketvoice_outside_playlists"
    internal const val EVERY_MS = 12L * 3600_000L
    private const val FIRST_MS = 4L * 60_000L

    /** « Ajouter automatiquement les nouveaux titres » (off: they are offered). */
    val autoAdd = mutableStateOf(false)
    private val _followed = MutableStateFlow<List<Followed>>(emptyList())
    val followed: StateFlow<List<Followed>> = _followed
    private val _pending = MutableStateFlow<List<Offer>>(emptyList())
    val pending: StateFlow<List<Offer>> = _pending
    @Volatile private var prefs: android.content.SharedPreferences? = null

    // Seams (tests).
    internal var preview: suspend (String) -> ImportPreview = { link -> if (YouTubeImport.isLink(link)) YouTubeImport.preview(link) else PlaylistImport.preview(link) }
    internal var search: suspend (String) -> List<Track> = { q -> MusicApi.searchAll(q) }
    internal var add: (String, List<Track>) -> Unit = { id, tracks -> com.ketvoice.desktop.ui.LibraryBridge.addAllToPlaylist(id, tracks) }
    internal var exists: (String) -> Boolean = { id -> com.ketvoice.desktop.ui.LibraryBridge.playlists.value.any { it.id == id } }

    fun start(context: android.content.Context, scope: CoroutineScope) {
        val p = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        prefs = p
        autoAdd.value = p.getBoolean("autoAdd", false)
        _followed.value = decode(p.getString("followed", null))
        scope.launch(Dispatchers.IO) {
            delay(FIRST_MS)
            while (true) { runCatching { checkAll() }.onFailure { AppLog.w("library", "followed playlists: $it") }; delay(EVERY_MS) }
        }
    }

    fun setAutoAdd(on: Boolean) { autoAdd.value = on; prefs?.edit()?.putBoolean("autoAdd", on)?.apply() }

    fun isFollowed(playlistId: String) = _followed.value.any { it.playlistId == playlistId }

    /** Follows [preview]'s playlist from now on, as the Ketvoice playlist [playlistId]; its songs today are known. */
    fun follow(playlistId: String, preview: ImportPreview) =
        save(_followed.value.filterNot { it.playlistId == playlistId } + Followed(playlistId, preview.url, preview.name,
            preview.songs.map { it.sourceId }.toSet(), System.currentTimeMillis()))

    fun unfollow(playlistId: String) = save(_followed.value.filterNot { it.playlistId == playlistId })

    /** Reads every followed playlist again (playlists deleted here are let go). */
    suspend fun checkAll() {
        for (f in _followed.value) {
            if (!exists(f.playlistId)) { unfollow(f.playlistId); continue }
            runCatching { check(f) }.onFailure { AppLog.w("library", "followed playlist ${f.name}: $it") }
        }
    }

    /** One playlist: its new songs found in Ketvoice, added or offered. Returns them. */
    suspend fun check(f: Followed): List<Track> {
        val now = preview(f.link)
        val fresh = PlaylistFollow.outsideFresh(f.known, now.songs.map { it.sourceId }).toSet()
        val songs: List<ImportSong> = now.songs.filter { it.sourceId in fresh }
        val found = if (songs.isEmpty()) emptyList() else PlaylistImport.resolve(now.copy(songs = songs), search) { _, _ -> }
            .filter { ImportReport.kind(it) != ImportReport.Kind.Missing }.mapNotNull { it.track }
        // Every song seen is known now, found or not: a song Ketvoice does not have is not looked for at every check.
        save(_followed.value.map { if (it.playlistId == f.playlistId) it.copy(known = it.known + fresh, checkedAt = System.currentTimeMillis()) else it })
        if (found.isEmpty()) return found
        if (autoAdd.value) add(f.playlistId, found)
        else _pending.update { list -> list.filterNot { it.playlistId == f.playlistId } + Offer(f.playlistId, f.name,
            (list.firstOrNull { it.playlistId == f.playlistId }?.tracks.orEmpty() + found).distinctBy { it.id }) }
        return found
    }

    fun accept(offer: Offer) { add(offer.playlistId, offer.tracks); dismiss(offer) }

    fun dismiss(offer: Offer) = _pending.update { list -> list.filterNot { it.playlistId == offer.playlistId } }

    private fun save(list: List<Followed>) {
        _followed.value = list
        prefs?.edit()?.putString("followed", encode(list))?.apply()
    }

    internal fun encode(list: List<Followed>): String = JSONArray(list.map { f ->
        JSONObject().put("playlist", f.playlistId).put("link", f.link).put("name", f.name).put("known", JSONArray(f.known.sorted())).put("checkedAt", f.checkedAt)
    }).toString()

    internal fun decode(text: String?): List<Followed> = runCatching {
        val a = JSONArray(text ?: return emptyList())
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            val known = o.optJSONArray("known") ?: JSONArray()
            Followed(o.getString("playlist"), o.getString("link"), o.optString("name"), (0 until known.length()).map { known.getString(it) }.toSet(), o.optLong("checkedAt"))
        }
    }.getOrDefault(emptyList())

    /** Tests. */
    internal fun resetForTest() { prefs = null; _followed.value = emptyList(); _pending.value = emptyList(); autoAdd.value = false }
}
