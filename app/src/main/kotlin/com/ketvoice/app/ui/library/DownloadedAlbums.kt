package com.ketvoice.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.CatalogRepo
import com.ketvoice.app.data.CatalogRequest
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.SmartOffline
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.Recordings
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How much of an album is on the device (7.0): Apple Music marks an album downloaded, or partly. */
internal data class AlbumDownload(val downloaded: Int, val total: Int) {
    val full: Boolean get() = total > 0 && downloaded >= total
    val partial: Boolean get() = downloaded in 1 until total
    /** « Téléchargé », « 3 sur 12 titres téléchargés », or null when nothing is on the device. */
    val label: String? get() = when {
        full -> AppText.t("Téléchargé")
        partial -> AppText.t("$downloaded sur $total titres téléchargés")
        else -> null
    }
}

internal object AlbumDownloads {
    /** Full tracklists the catalogue gave for albums opened in this session, by album key. */
    private val tracklists = mutableStateMapOf<String, List<Track>>()

    fun tracklist(key: String): List<Track>? = tracklists[key]
    internal fun remember(key: String, tracks: List<Track>) { if (tracks.isNotEmpty()) tracklists[key] = tracks }
    internal fun clearForTests() = tracklists.clear()

    /**
     * The album's titles in their order: the catalogue's tracklist when known (each matched to the title
     * the device holds, by id or recording), then the device's titles it did not list; without it, [album]'s own.
     */
    fun titles(album: LocalAlbum, tracklist: List<Track>?): List<Track> {
        if (tracklist.isNullOrEmpty()) return album.tracks
        val local = album.tracks
        val used = HashSet<String>()
        val ordered = tracklist.map { remote ->
            val mine = local.firstOrNull { it.id !in used && (it.id == remote.id || Recordings.of(it) == Recordings.of(remote)) }
            if (mine != null) { used += mine.id; mine } else remote
        }
        return ordered + local.filter { it.id !in used }
    }

    fun state(album: LocalAlbum, downloaded: Set<String>, tracklist: List<Track>? = tracklist(album.key)): AlbumDownload {
        val titles = titles(album, tracklist)
        return AlbumDownload(titles.count { it.id in downloaded }, titles.size)
    }
}

/** The small sign under an album's cover: downloaded, or how much of it is. */
@Composable
internal fun AlbumDownloadBadge(state: AlbumDownload, modifier: Modifier = Modifier) {
    val label = state.label ?: return
    val theme = LocalTheme.current
    Row(modifier.testTag(if (state.full) "album-downloaded" else "album-partly-downloaded"), verticalAlignment = Alignment.CenterVertically) {
        Icon(KetIcons.Download, null, tint = if (state.full) theme.accent else theme.secondary, modifier = Modifier.size(12.dp))
        Text(label, localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp))
    }
}

/**
 * An album of the library as Apple Music shows a downloaded one (7.0): the cover, its name and artist,
 * Play and Shuffle, every title with its download state, the titles still to download, and
 * « Supprimer le téléchargement » for the whole album.
 */
@Composable
internal fun AlbumPage(album: LocalAlbum, onBack: () -> Unit, modifier: Modifier = Modifier, backLabel: String = "Albums",
                       bottomPadding: androidx.compose.ui.unit.Dp = 120.dp) {
    val theme = LocalTheme.current
    val context = LocalContext.current
    val saved by Downloader.items.collectAsState()
    val busy by Downloader.busy.collectAsState()
    val player by Player.state.collectAsState()
    val scope = rememberCoroutineScope()
    androidx.activity.compose.BackHandler(onBack = onBack)
    LaunchedEffect(album.key) {
        if (AlbumDownloads.tracklist(album.key) != null) return@LaunchedEffect
        val first = album.tracks.firstOrNull() ?: return@LaunchedEffect
        val id = first.albumId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val page = runCatching { withContext(Dispatchers.IO) { CatalogRepo.page(CatalogRequest("album", id, first.album)) } }.getOrNull()
        page?.tracks?.let { AlbumDownloads.remember(album.key, it) }
    }
    val tracklist = AlbumDownloads.tracklist(album.key)
    val downloadedIds = remember(saved) { saved.mapTo(HashSet()) { it.track.id } }
    val titles = remember(album, tracklist) { AlbumDownloads.titles(album, tracklist) }
    val state = AlbumDownload(titles.count { it.id in downloadedIds }, titles.size)
    val onDevice = saved.filter { item -> titles.any { it.id == item.track.id } }
    val missing = titles.filter { it.id !in downloadedIds }
    val playable = titles.filter { it.id in downloadedIds }.ifEmpty { titles }
    var confirm by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val cover = ArtworkRepo.coverForTrack(album.tracks.first()) ?: album.tracks.first().coverUrl
    val minutes = titles.sumOf { it.durationMs } / 60_000
    BoxWithConstraints(modifier.fillMaxSize().testTag("album-page")) {
        val side = (maxWidth * .62f).coerceIn(180.dp, 300.dp)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding)) {
            item(key = "album-head") {
                // A phone stacks the cover over the album's name, centred; a wide pane sets them side by side, as on iPad.
                val wide = maxWidth >= 700.dp
                val info: @Composable ColumnScope.() -> Unit = {
                    Text(album.title, localize = false, color = theme.onBackground, style = if (wide) Ket27.Type.largeTitle else Ket27.Type.title2,
                        textAlign = if (wide) TextAlign.Start else TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.asHeading().testTag("album-title"))
                    Text(album.artist, localize = false, color = theme.accent, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) {
                            AppUi.openArtist(album.tracks.first().artistId.orEmpty(), album.artist)
                        })
                    Text(listOf(AppText.t(if (titles.size == 1) "1 titre" else "${titles.size} titres"),
                        if (minutes > 0) AppText.t("$minutes min") else "").filter { it.isNotBlank() }.joinToString(" · "),
                        localize = false, color = theme.secondary, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    AlbumDownloadBadge(state, Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(18.dp))
                    CollectionActionPair({ Player.setShuffle(false); Player.playQueue(playable) }, { Player.playShuffled(playable) },
                        Modifier.widthIn(max = if (wide) 420.dp else 520.dp).fillMaxWidth(), minHeight = 50.dp,
                        playTag = "album-play", shuffleTag = "album-shuffle")
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    BackLink(backLabel, onBack)
                    Spacer(Modifier.height(8.dp))
                    if (wide) Row(verticalAlignment = Alignment.Bottom) {
                        Cover(album.title, cover, 260.dp, 12.dp)
                        Column(Modifier.padding(start = 28.dp).weight(1f), content = info)
                    } else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Cover(album.title, cover, side, 12.dp)
                        Spacer(Modifier.height(18.dp))
                        info()
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
            itemsIndexed(titles, key = { i, t -> "${t.id}:$i" }) { i, track ->
                val here = track.id in downloadedIds
                val current = player.current?.id == track.id
                SwipeTrackActions(track, enabled = here) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .trackRowClick(track) { Player.playQueue(playable, playable.indexOfFirst { it.id == track.id }.coerceAtLeast(0)) }
                        .padding(horizontal = 20.dp).testTag(if (here) "album-track-downloaded" else "album-track-missing"),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", localize = false, color = theme.secondary, fontSize = 15.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.width(28.dp))
                        Column(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)) {
                            TrackTitle(track.title, color = if (current) theme.accent else if (here) theme.onBackground else theme.secondary,
                                selected = current, maxLines = 1)
                            if (!com.ketvoice.app.data.MediaOverrides.mainArtist(track.artist).equals(album.artist, true))
                                Text(track.artist, localize = false, color = theme.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        when {
                            track.id in busy -> ActivitySpinner(Modifier.padding(end = 14.dp), size = 18.dp)
                            here -> Icon(KetIcons.Download, "Téléchargé", tint = theme.secondary, modifier = Modifier.padding(end = 14.dp).size(14.dp))
                            else -> Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))
                                .clickable(role = Role.Button) { Downloader.download(track) }, contentAlignment = Alignment.Center) {
                                Icon(KetIcons.Download, "Télécharger ${track.title}", tint = theme.accent, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                Box(Modifier.padding(start = 58.dp).fillMaxWidth().height(.5.dp).background(theme.hairline))
            }
            item(key = "album-actions") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (onDevice.isNotEmpty()) Text(AppText.t(if (onDevice.size == 1) "1 titre" else "${onDevice.size} titres") + " · " +
                        android.text.format.Formatter.formatShortFileSize(context, onDevice.sumOf { it.bytes }) + " " + AppText.t("sur l'appareil"),
                        localize = false, color = theme.secondary, fontSize = 13.sp)
                    if (missing.isNotEmpty()) KetTextButton(if (missing.size == 1) "Télécharger le titre manquant" else "Télécharger les ${missing.size} titres manquants",
                        { missing.forEach { Downloader.download(it) } }, modifier = Modifier.testTag("album-download-missing"))
                    if (onDevice.isNotEmpty()) KetTextButton("Supprimer le téléchargement", { confirm = true }, destructive = true,
                        modifier = Modifier.testTag("album-delete-download"))
                }
            }
            // 7.2: the same « Titres recommandés » as a catalogue album closes with.
            if (titles.isNotEmpty()) item(key = "album-more-like-this", contentType = "more-like-this") {
                com.ketvoice.app.ui.sub.MoreLikeThisSection("local:" + album.key, titles, fromPlaylist = false)
            }
        }
    }
    if (confirm && onDevice.isNotEmpty()) AppAlertDialog(onDismissRequest = { if (!deleting) confirm = false },
        modifier = Modifier.testTag("album-delete-alert"),
        title = { Text("Supprimer le téléchargement") },
        text = { Text(AppText.t(if (onDevice.size == 1) "1 titre" else "${onDevice.size} titres") + " · " + album.title, localize = false) },
        confirmButton = { KetTextButton("Supprimer", enabled = !deleting, primary = true, destructive = true, onClick = {
            deleting = true
            scope.launch {
                val gone = withContext(Dispatchers.IO) { onDevice.filter { runCatching { Downloader.delete(it) }.getOrDefault(false) } }
                // Deleted by hand: « Hors ligne intelligent » never brings these back on its own.
                SmartOffline.noteDeletedByHand(gone.map { it.track.id })
                deleting = false; confirm = false
            }
        }) },
        dismissButton = { KetTextButton("Annuler", enabled = !deleting, onClick = { confirm = false }) })
}
