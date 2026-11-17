package com.ketvoice.app.account

import android.content.Context
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.PlaylistStore
import com.ketvoice.app.data.Track
import com.ketvoice.app.sync.SyncTracks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** What a profile page shows of the title playing now (only when its owner chose « Montrer ce que j'écoute »). */
data class PublicListening(val title: String, val artist: String, val coverUrl: String?, val playing: Boolean)

/** A playlist its owner published: a snapshot, never read from their library. */
data class PublicPlaylist(val id: String, val name: String, val coverUrl: String?, val tracks: List<Track>)

/** A public profile as the server's `ketvoice_public_profile` returns it: never an e-mail address or a user id. */
data class PublicProfile(val handle: String, val displayName: String, val avatarRef: String?,
                         val playlists: List<PublicPlaylist>, val listening: PublicListening?)

internal object PublicProfileParser {
    /** Null for « no such public profile » (the server answers null for an unknown handle and for a private profile alike). */
    fun parse(text: String): PublicProfile? = runCatching {
        if (text.isBlank() || text.trim() == "null") return null
        val json = JSONObject(text)
        val lists = json.optJSONArray("playlists") ?: JSONArray()
        PublicProfile(json.getString("handle"), json.optString("display_name"),
            json.optString("avatar_ref").takeIf { !json.isNull("avatar_ref") && it.matches(Regex("[0-9a-f-]{36}")) },
            (0 until minOf(lists.length(), 20)).mapNotNull { playlist(lists.getJSONObject(it)) },
            json.optJSONObject("listening")?.let(::listening))
    }.getOrNull()

    private fun playlist(json: JSONObject): PublicPlaylist? = runCatching {
        val rows = json.optJSONArray("tracks") ?: JSONArray()
        PublicPlaylist(json.getString("id"), json.getString("name"),
            json.optString("cover_url").takeIf { !json.isNull("cover_url") && it.startsWith("https://") },
            (0 until minOf(rows.length(), 200)).mapNotNull { SyncTracks.parse(rows.getJSONObject(it).toString()) })
    }.getOrNull()

    private fun listening(json: JSONObject): PublicListening? = json.optString("title").takeIf { it.isNotBlank() }?.let {
        PublicListening(it.take(300), json.optString("artist").take(300),
            json.optString("cover_url").takeIf { url -> !json.isNull("cover_url") && url.startsWith("https://") }, json.optBoolean("playing"))
    }
}

/** The public profile's tables and function through PostgREST ([http] is the real session, or a fake in tests). */
internal class PublicProfileClient(private val http: RestHttp, private val user: () -> String) {
    private companion object {
        const val TABLE = "/rest/v1/ketvoice_public_playlists"
        const val LIMIT = 200
    }

    /** The profile of @[handle], or null when there is none or it is private. */
    suspend fun fetch(handle: String): PublicProfile? {
        val body = JSONObject().put("handle", handle.trim().removePrefix("@").lowercase()).toString()
        return PublicProfileParser.parse(http.call("POST", "/rest/v1/rpc/ketvoice_public_profile", body, emptyMap()).requireOk("Profile").text)
    }

    /** The ids (in the Library) of this account's published playlists. */
    suspend fun published(): Set<String> {
        val rows = JSONArray(http.call("GET", "$TABLE?select=local_id&limit=100", null, emptyMap()).requireOk("Published read").text)
        return (0 until rows.length()).map { rows.getJSONObject(it).getString("local_id") }.toSet()
    }

    /** Publishes (or republishes) a playlist: its name, cover and up to 200 titles another account can hear. */
    suspend fun publish(playlist: LocalPlaylist) {
        val tracks = playlist.entries.mapNotNull { it.track }.filter { !it.id.startsWith("local:") }.distinctBy { it.id }.take(LIMIT)
        val body = JSONObject().put("owner_id", user()).put("local_id", playlist.id).put("name", playlist.name)
            .put("cover_url", playlist.coverUrl?.takeIf { it.startsWith("https://") && it.length <= 1000 } ?: JSONObject.NULL)
            .put("tracks", JSONArray(tracks.map(SyncTracks::json))).toString()
        http.call("POST", "$TABLE?on_conflict=owner_id,local_id", body,
            mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")).requireOk("Publish")
    }

    suspend fun unpublish(localId: String) {
        http.call("DELETE", "$TABLE?local_id=eq.${SupabaseHttp.encode(localId)}", null, mapOf("Prefer" to "return=minimal")).requireOk("Unpublish")
    }
}

/**
 * « Rendre cette playlist publique »: the playlists the listener chose to publish on their profile. The
 * server holds a snapshot (never a flag on the synced playlist); it is sent again a few seconds after the
 * playlist changes. Nothing is sent while signed out, and unpublishing deletes the snapshot.
 */
object PublicProfiles {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _published = MutableStateFlow<Set<String>>(emptySet())
    /** Library ids of the playlists this account publishes. */
    val published: StateFlow<Set<String>> = _published
    private var watcher: Job? = null
    private val pushed = HashMap<String, Int>()
    private fun client() = PublicProfileClient(RealRestHttp) { AccountSession.userId().orEmpty() }

    internal fun signedIn(context: Context) {
        watcher?.cancel()
        watcher = scope.launch {
            runCatching { _published.value = client().published() }
            PlaylistStore.lists.value.filter { it.id in _published.value }.forEach { pushed[it.id] = fingerprint(it) }
            PlaylistStore.lists.collectLatest { lists -> delay(5_000); republish(lists) }
        }
    }

    internal fun signedOut() {
        watcher?.cancel(); watcher = null
        _published.value = emptySet(); pushed.clear()
    }

    internal fun fingerprint(playlist: LocalPlaylist): Int = (playlist.name + "|" + playlist.coverUrl + "|" +
        playlist.entries.joinToString(",") { it.track?.id.orEmpty() }).hashCode()

    private suspend fun republish(lists: List<LocalPlaylist>) {
        _published.value.mapNotNull { id -> lists.firstOrNull { it.id == id } }.forEach { playlist ->
            val now = fingerprint(playlist)
            if (pushed[playlist.id] == now) return@forEach
            try { client().publish(playlist); pushed[playlist.id] = now } catch (stopped: CancellationException) { throw stopped } catch (_: Exception) { }
        }
    }

    /** Publishes or unpublishes a playlist; the listener's profile page lists it from then on (or no more). */
    suspend fun setPublished(playlist: LocalPlaylist, on: Boolean) {
        if (on) client().publish(playlist) else client().unpublish(playlist.id)
        _published.value = if (on) _published.value + playlist.id else _published.value - playlist.id
        if (on) pushed[playlist.id] = fingerprint(playlist) else pushed.remove(playlist.id)
    }

    /** The profile of a handle, for the profile page and the search by handle. */
    suspend fun lookup(handle: String): PublicProfile? = client().fetch(handle)

    internal fun showForTest(ids: Set<String>) { _published.value = ids }
}
