package com.ketvoice.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.LayoutSettings
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Cover
import com.ketvoice.app.ui.common.Text

/**
 * How each section of the library opens (9.0 « Bibliothèque et menus »): Playlists as a list, Albums and Artists as
 * grids, every order « Récents » — as before — unless the listener chose otherwise in Settings.
 */
internal object LibraryLayout {
    /** The sections that have a view to choose, with their names. */
    val viewSections = listOf("playlists" to "Playlists", "albums" to "Albums", "artists" to "Artistes")
    /** The sections that have an order to choose, with their names. */
    val sortSections = listOf("playlists" to "Playlists", "albums" to "Albums", "artists" to "Artistes", "titles" to "Titres")

    fun grid(section: String): Boolean = LayoutSettings.libraryView(section) == "grid"

    /** The order of Albums, Artists or Titles, as their pills number them. */
    fun sort(section: String): Int = LayoutSettings.librarySort(section).toIntOrNull() ?: 0

    fun playlistSort(): PlaylistSort = PlaylistSort.entries.firstOrNull { it.name == LayoutSettings.librarySort("playlists") } ?: PlaylistSort.Recent

    /** The orders a section offers, as (stored value, label). */
    fun sortChoices(section: String): List<Pair<String, String>> = when (section) {
        "playlists" -> PlaylistSort.entries.map { it.name to it.label }
        "artists" -> listOf("0" to "Récents", "1" to "Nom A–Z")
        else -> AlbumOrder.labels.mapIndexed { i, label -> i.toString() to label }
    }

    fun sortLabel(section: String): String =
        sortChoices(section).firstOrNull { it.first == LayoutSettings.librarySort(section) }?.second ?: sortChoices(section).first().second
}

/** An album of the library as a line: its cover, its title and artist, its download state. */
@Composable
internal fun LibraryAlbumLine(group: LocalAlbum, state: AlbumDownload, onOpen: () -> Unit) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onOpen).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Cover(group.title, ArtworkRepo.coverForTrack(group.tracks.first()) ?: group.tracks.first().coverUrl, 56.dp, 10.dp)
        Column(Modifier.weight(1f)) {
            Text(group.title, localize = false, color = theme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(group.artist, localize = false, color = theme.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AlbumDownloadBadge(state, Modifier.padding(top = 2.dp))
        }
    }
}

/** An artist of the library as a line: a round portrait, the name and how many titles. */
@Composable
internal fun LibraryArtistLine(name: String, tracks: List<Track>, onOpen: () -> Unit) {
    val theme = LocalTheme.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onOpen).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Cover(name, ArtworkRepo.coverForTrack(tracks.first()) ?: tracks.first().coverUrl, 56.dp, 28.dp)
        Column(Modifier.weight(1f)) {
            Text(name, localize = false, color = theme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(AppText.t(if (tracks.size == 1) "1 titre" else "${tracks.size} titres"), localize = false,
                color = theme.secondary, fontSize = 13.sp, maxLines = 1)
        }
    }
}
