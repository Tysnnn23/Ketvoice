package com.ketvoice.desktop.social

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.ketvoice.app.data.Track
import com.ketvoice.app.social.ShareUrls
import com.ketvoice.desktop.platform.AppLog
import com.ketvoice.desktop.platform.DesktopPlatform
import com.ketvoice.desktop.ui.AlbumRef
import com.ketvoice.desktop.ui.ArtistRef
import com.ketvoice.desktop.ui.LocalNavigator
import com.ketvoice.desktop.ui.LocalPlayer
import com.ketvoice.desktop.ui.LocalSearch
import com.ketvoice.desktop.ui.LocalShell
import com.ketvoice.desktop.ui.Route
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The `ketvoice://` links Windows hands to Ketvoice (« Ouvrir dans Ketvoice » on the website, a friend's link): the
 * phone's own links, read by the shared [ShareUrls.parse]. A title plays, an album, an artist, a profile or a shared
 * playlist opens, a Ketogether room opens « Ensemble » with its invitation (joining stays the listener's choice).
 */
object DeepLinks {
    fun mine(link: String): Boolean = ShareUrls.parse(link) != null

    /** Kept composed in the shell ([com.ketvoice.desktop.ui.ShellHooks]): takes the pending links it knows. */
    @Composable
    fun Hook() {
        val pending by DesktopPlatform.pendingLinks.collectAsState()
        val nav = LocalNavigator.current
        val shell = LocalShell.current
        val player = LocalPlayer.current
        val search = LocalSearch.current
        val scope = rememberCoroutineScope()
        LaunchedEffect(pending) {
            DesktopPlatform.claim(::mine).forEach { raw ->
                val link = ShareUrls.parse(raw) ?: return@forEach
                AppLog.i("links", "open ${link::class.simpleName}")
                when (link) {
                    is ShareUrls.Link.Track -> scope.launch {
                        val track = resolve(link)
                        if (track != null) player.playQueue(listOf(track), 0)
                        else { search.query = listOf(link.title, link.artist).filter { it.isNotBlank() }.joinToString(" "); nav.root(Route.Search) }
                    }
                    is ShareUrls.Link.Album -> nav.push(Route.Album(AlbumRef(link.id, link.title, link.artist, null)))
                    is ShareUrls.Link.Artist -> nav.push(Route.Artist(ArtistRef(link.id.takeIf { it.startsWith("UC") }, link.name)))
                    is ShareUrls.Link.Profile -> nav.push(Route.Extra(SocialPages.PROFILE, link.handle))
                    is ShareUrls.Link.Playlist -> nav.push(Route.Extra(SocialPages.SHARED_PLAYLIST, link.id))
                    is ShareUrls.Link.Room -> { DesktopTogether.openLink(link.target); shell.open(SocialPages.TOGETHER_TAB) }
                    is ShareUrls.Link.Join -> ShareLinks.notice.value = "Les playlists partagées s'ouvrent sur le téléphone et sur le web"
                }
            }
        }
    }

    /** The shared title: known here (history, library), else the catalogue by id, then by title and artist. */
    suspend fun resolve(link: ShareUrls.Link.Track): Track? = withContext(Dispatchers.IO) {
        com.ketvoice.app.data.ListeningHistory.knownTracks.value.firstOrNull { it.id == link.id }?.let { return@withContext it }
        val query = listOf(link.title, link.artist).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return@withContext null
        val found = runCatching { com.ketvoice.app.data.MusicApi.searchAll(query) }.getOrDefault(emptyList())
        found.firstOrNull { it.id == link.id }
            ?: found.firstOrNull { it.title.equals(link.title, true) && (link.artist.isBlank() || it.artist.contains(link.artist, true)) }
    }
}

/**
 * Windows learns that `ketvoice://` opens this Ketvoice (per user, no administrator right): the installed launcher
 * registers it at start, and again when it moved. The single instance hands the link to the running window.
 */
object UrlScheme {
    private const val KEY = "Software\\Classes\\ketvoice"

    fun command(exe: String) = "\"$exe\" \"%1\""

    fun register(exe: String? = com.ketvoice.desktop.platform.Autostart.installedExe()) {
        if (exe == null || !System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        runCatching {
            val hkcu = WinReg.HKEY_CURRENT_USER
            val open = "$KEY\\shell\\open\\command"
            val wanted = command(exe)
            val current = if (Advapi32Util.registryKeyExists(hkcu, open)) runCatching { Advapi32Util.registryGetStringValue(hkcu, open, "") }.getOrNull() else null
            if (current == wanted) return
            listOf(KEY, "$KEY\\DefaultIcon", "$KEY\\shell", "$KEY\\shell\\open", open).forEach {
                if (!Advapi32Util.registryKeyExists(hkcu, it)) Advapi32Util.registryCreateKey(hkcu, it)
            }
            Advapi32Util.registrySetStringValue(hkcu, KEY, "", "URL:Ketvoice")
            Advapi32Util.registrySetStringValue(hkcu, KEY, "URL Protocol", "")
            Advapi32Util.registrySetStringValue(hkcu, "$KEY\\DefaultIcon", "", "\"$exe\",0")
            Advapi32Util.registrySetStringValue(hkcu, open, "", wanted)
            AppLog.i("links", "ketvoice:// registered")
        }.onFailure { AppLog.w("links", "ketvoice:// not registered: ${it.javaClass.simpleName}") }
    }
}
