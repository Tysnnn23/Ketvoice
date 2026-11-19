package com.ketvoice.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.CatalogRequest
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.PlaylistStore
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.library.LikedCover
import com.ketvoice.app.ui.library.MosaicCover
import com.ketvoice.app.ui.library.PlaylistArtwork
import com.ketvoice.app.ui.library.downloadMosaic

/**
 * « Votre bibliothèque » on Home (7.2, the pinned row of Apple Music and Spotify): Favoris with the heart,
 * Téléchargements with its mosaic, then the pinned playlists, the same cards as every shelf of the page.
 * Collects what it shows itself, so Home does not recompose with the downloads.
 */
@Composable
internal fun HomeLibraryShelf(modifier: Modifier = Modifier) {
    val downloads by Downloader.items.collectAsState()
    val playlists by PlaylistStore.lists.collectAsState()
    val favorites = AppSettings.favoriteIds.value.size
    val pinned = remember(playlists) { playlists.filter { it.pinned } }
    val mosaic = remember(downloads) { downloadMosaic(downloads) }
    if (favorites == 0 && downloads.isEmpty() && pinned.isEmpty()) return
    fun count(n: Int) = AppText.t(if (n == 1) "1 titre" else "$n titres")
    Column(modifier.testTag("home-library")) {
        EditorialHeader("Votre bibliothèque", onMore = { AppUi.selectTab(AppUi.LIBRARY) })
        val cell = com.ketvoice.app.ui.adaptive.shelfCell(156.dp, com.ketvoice.app.ui.common.pageMargin, HomeLayout.gap)
        HomeRow {
            if (favorites > 0) item(key = "liked") {
                LibraryCard(AppText.t("Favoris"), count(favorites), cell, "home-library-liked",
                    { AppUi.openCatalog(CatalogRequest("favorites", "local", "Favoris")) }) { LikedCover() }
            }
            if (downloads.isNotEmpty()) item(key = "downloads") {
                LibraryCard(AppText.t("Téléchargements"), count(downloads.size), cell, "home-library-downloads",
                    { AppUi.openDownloads() }) { MosaicCover(mosaic) }
            }
            items(pinned, key = { "playlist:" + it.id }) { playlist ->
                LibraryCard(playlist.name, count(playlist.entries.size), cell, "home-library-playlist",
                    { AppUi.openPlaylist(playlist.id) }) { PlaylistArtwork(playlist, playlist.name, Modifier.size(cell), 0.dp) }
            }
        }
    }
}

@Composable
private fun LibraryCard(title: String, subtitle: String, side: Dp, tag: String, onClick: () -> Unit, cover: @Composable () -> Unit) {
    val theme = LocalTheme.current
    Column(Modifier.width(side).clip(RoundedCornerShape(Ket27.Radius.artwork)).clickable(role = Role.Button, onClick = onClick).testTag(tag)) {
        Box(Modifier.size(side).clip(RoundedCornerShape(Ket27.Radius.artwork))) { cover() }
        Text(title, localize = false, color = theme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(subtitle, localize = false, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
