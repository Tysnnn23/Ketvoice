package com.ketvoice.app.ui.sub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.LocalAlbumGroup
import com.ketvoice.app.data.LocalCorrections
import com.ketvoice.app.data.LocalLyrics
import com.ketvoice.app.data.LocalMusic
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.ActivitySpinner
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text

/** « Musique locale pro » (7.0): the pieces of the local library pages (album header, discs, streaming duplicates, tools). */

/** Titles, albums, artists or folders. */
@Composable
internal fun LocalViewSwitch(view: Int, onView: (Int) -> Unit) {
    com.ketvoice.app.ui.common.GlassSegmentedControl(listOf("Titres", "Albums", "Artistes", "Dossiers"), view, onView, tag = "local-view")
}

/** « 12 titres · 48 min · 2001 »: what an album or a folder holds. */
internal fun localMeta(count: Int, durationMs: Long, year: Int? = null): String =
    listOf(AppText.t(if (count == 1) "1 titre" else "$count titres"), playtimeLabel(durationMs), year?.toString().orEmpty())
        .filter { it.isNotBlank() }.joinToString(" · ")

/** An album's name, its album artist (or « Artistes divers »), its year and whether it is a compilation. */
@Composable
internal fun LocalAlbumHeader(album: LocalAlbumGroup) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("local-album-header"), verticalAlignment = Alignment.CenterVertically) {
        Cover(album.title, album.tracks.firstNotNullOfOrNull { it.coverUrl }, 96.dp, 14.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(album.title, localize = false, color = theme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(album.artist ?: AppText.t("Artistes divers"), localize = false, color = theme.accent, fontSize = 15.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(localMeta(album.tracks.size, album.durationMs, album.year), localize = false, color = theme.secondary, fontSize = 12.sp)
            if (album.compilation) Text("Compilation", color = theme.secondary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(theme.secondary.copy(alpha = .14f)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

/** « Disque 2 », above the titles of a disc when an album has several. */
@Composable
internal fun LocalDiscHeading(number: Int) {
    Text(AppText.t("Disque {0}").replace("{0}", number.toString()), localize = false, color = LocalTheme.current.secondary,
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 2.dp).testTag("local-disc"))
}

/** « Aussi disponible en streaming »: the catalogue has the same recording; a touch plays that one. */
@Composable
internal fun StreamingTwin(track: Track, numbered: Boolean = false) {
    val twins by LocalCorrections.streaming.collectAsState()
    val twin = twins[track.id] ?: return
    val theme = LocalTheme.current
    // Under the title's name: past the margin, the cover and (on an album page) the track number.
    Row(Modifier.padding(start = if (numbered) 106.dp else 80.dp, bottom = 6.dp).clip(RoundedCornerShape(10.dp)).background(theme.accent.copy(alpha = .10f))
        .clickable(role = Role.Button) { Player.playQueue(listOf(twin), 0) }.padding(horizontal = 8.dp, vertical = 4.dp)
        .testTag("local-streaming"), verticalAlignment = Alignment.CenterVertically) {
        Icon(KetIcons.Wifi, null, tint = theme.accent, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text("Aussi disponible en streaming", color = theme.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The library's tools: the corrections the catalogue proposes, and the synced lyrics fetched ahead. */
@Composable
internal fun LocalProTools(onCorrections: () -> Unit) {
    val proposals by LocalCorrections.proposals.collectAsState()
    val searching by LocalCorrections.searching.collectAsState()
    val fetching by LocalLyrics.fetching.collectAsState()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val search = searching
        when {
            search != null -> LocalToolRow(KetIcons.Search, AppText.t("Recherche dans le catalogue… {0}/{1}")
                .replace("{0}", search.done.toString()).replace("{1}", search.total.toString()), busy = true, tag = "local-searching") { }
            proposals.isNotEmpty() -> LocalToolRow(KetIcons.Sliders, AppText.t("Corrections proposées") + " · ${proposals.size}",
                tag = "local-corrections", onClick = onCorrections)
            else -> LocalToolRow(KetIcons.Search, AppText.t("Chercher des corrections dans le catalogue"), tag = "local-find") {
                com.ketvoice.app.data.LocalCorrections.forgetSearches(); LocalMusic.findCorrections()
            }
        }
        val lyrics = fetching
        if (lyrics != null) LocalToolRow(KetIcons.Lyrics, AppText.t("Paroles synchronisées… {0}/{1}")
            .replace("{0}", lyrics.done.toString()).replace("{1}", lyrics.total.toString()), busy = true, tag = "local-lyrics") { }
        else LocalToolRow(KetIcons.Lyrics, AppText.t("Chercher les paroles synchronisées"), tag = "local-lyrics") { LocalMusic.findLyrics() }
    }
}

@Composable
private fun LocalToolRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, busy: Boolean = false, tag: String,
                         onClick: () -> Unit) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(theme.card)
        .clickable(enabled = !busy, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        if (busy) ActivitySpinner(size = 18.dp, color = theme.accent) else Icon(icon, null, tint = theme.accent, modifier = Modifier.size(18.dp))
        Text(label, localize = false, color = theme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 10.dp))
        if (!busy) Icon(KetIcons.ChevronRight, null, tint = theme.secondary, modifier = Modifier.size(16.dp))
    }
}

/** One page of the library below Titres: an album, an artist or a folder, and the row that opens it. */
internal data class LocalPage(val key: String, val title: String, val subtitle: String?, val meta: String,
                              val tracks: List<Track>, val album: LocalAlbumGroup? = null)

/** The albums, artists and folders of the titles shown, ready for the views. */
internal class LocalLibraryState(tracks: List<Track>, tags: Map<String, com.ketvoice.app.data.LocalTags>) {
    private val albums = com.ketvoice.app.data.LocalLibrary.albums(tracks, tags)
    private val pages: List<List<LocalPage>> = listOf(
        emptyList(),
        albums.map { a -> LocalPage(a.key, a.title, a.artist ?: AppText.t("Artistes divers"), localMeta(a.tracks.size, a.durationMs, a.year), a.tracks, a) },
        com.ketvoice.app.data.LocalLibrary.artists(tracks, tags, albums).map { a ->
            LocalPage(a.key, a.name, if (a.albums.isEmpty()) null else AppText.t(if (a.albums.size == 1) "1 album" else "${a.albums.size} albums"),
                localMeta(a.tracks.size, a.tracks.sumOf { it.durationMs.coerceAtLeast(0L) }), a.tracks)
        },
        com.ketvoice.app.data.LocalLibrary.folders(tracks, tags).map { f ->
            LocalPage(f.path, f.name, f.parent.ifEmpty { null }, localMeta(f.tracks.size, f.tracks.sumOf { it.durationMs.coerceAtLeast(0L) }), f.tracks)
        })

    fun pages(view: Int): List<LocalPage> = pages.getOrElse(view) { emptyList() }
    fun page(view: Int, key: String?): LocalPage? = key?.let { k -> pages(view).firstOrNull { it.key == k } }
}

/** The way back from a page to its list. */
internal fun localViewName(view: Int): String = when (view) { 1 -> "Albums"; 2 -> "Artistes"; 3 -> "Dossiers"; else -> "Titres" }
