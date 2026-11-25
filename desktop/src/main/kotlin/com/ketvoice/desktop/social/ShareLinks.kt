package com.ketvoice.desktop.social

import com.ketvoice.app.data.Track
import com.ketvoice.app.social.ShareUrls
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.desktop.ui.AlbumRef
import com.ketvoice.desktop.ui.MenuItem
import com.ketvoice.desktop.ui.UserPlaylist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Smart share links on Windows: « Partager » in a title's, an album's and a playlist's menu copies a link to the
 * website's page for it (cover, title, « Ouvrir dans Ketvoice »), built by the shared [ShareUrls]. A playlist is
 * shared as a snapshot behind a random link (signed in only); a title or album needs no account.
 */
object ShareLinks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** The last thing copied, for the window's notice (French, translated when drawn). */
    val notice = MutableStateFlow<String?>(null)
    /** Where links go; tests read it instead of the clipboard. */
    internal var copy: (String) -> Unit = { text ->
        runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null) }
    }

    /** The page of [track], or null for a title that cannot be shared (a file of this computer). */
    fun trackUrl(track: Track): String? = ShareUrls.track(track.id, track.title, track.artist, track.coverUrl)

    /** A catalogue album's page; an album known only by its name has no link (another device could not find it). */
    fun albumUrl(ref: AlbumRef): String? = ShareUrls.album(ref.id, ref.title, ref.artist, ref.cover)

    /** Saves [playlist]'s snapshot (at most 100 titles, no local file) and answers its link id. */
    suspend fun saveSnapshot(playlist: UserPlaylist): String? {
        val tracks = JSONArray()
        playlist.tracks.filter { ShareUrls.shareableId(it.id) }.take(100).forEach { t ->
            tracks.put(JSONObject().put("id", t.id).put("title", t.title.take(300)).put("artist", t.artist.take(300))
                .put("album", t.album.take(300)).put("durationMs", t.durationMs.coerceIn(0, 86_400_000)).apply {
                    com.ketvoice.app.together.TrackRef.publicCover(t.coverUrl)?.let { put("coverUrl", it) }
                })
        }
        val cover = playlist.tracks.firstNotNullOfOrNull { com.ketvoice.app.together.TrackRef.publicCover(it.coverUrl) }
        return Social.backend.saveLink(playlist.id, playlist.name.ifBlank { "Playlist" }, cover, tracks)
    }

    suspend fun playlistUrl(playlist: UserPlaylist): String? = saveSnapshot(playlist)?.let { ShareUrls.playlist(it, playlist.name) }

    private fun share(url: String?, done: String = "Lien copié") {
        if (url == null) { notice.value = "Ce titre ne peut pas être partagé"; return }
        copy(url)
        notice.value = done
    }

    fun trackItem(track: Track): MenuItem? {
        if (!ShareUrls.shareableId(track.id)) return null
        return MenuItem("Partager", KetIcons.Share, "share") { share(trackUrl(track)) }
    }

    fun albumItem(ref: AlbumRef): MenuItem? = albumUrl(ref)?.let { url -> MenuItem("Partager l'album", KetIcons.Share, "share-album") { share(url) } }

    fun playlistItem(playlist: UserPlaylist): MenuItem? {
        if (playlist.tracks.none { ShareUrls.shareableId(it.id) }) return null
        return MenuItem("Partager la playlist", KetIcons.Share, "share-playlist") {
            if (!Social.signedIn) { notice.value = "Connectez-vous à votre compte Ketvoice pour partager une playlist"; return@MenuItem }
            scope.launch {
                val url = playlistUrl(playlist)
                if (url == null) notice.value = "Partage impossible pour le moment" else share(url)
            }
        }
    }

    /** Plugs « Partager » into the title, album and playlist menus. */
    fun register() {
        com.ketvoice.desktop.ui.TrackMenuSlots.share = ::trackItem
        com.ketvoice.desktop.ui.TrackMenuSlots.shareAlbum = ::albumItem
        com.ketvoice.desktop.ui.TrackMenuSlots.sharePlaylist = ::playlistItem
    }
}
