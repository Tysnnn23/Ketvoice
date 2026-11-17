package com.ketvoice.app.ui.sub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.Album
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtistBio
import com.ketvoice.app.data.ArtistPage
import com.ketvoice.app.data.ArtistPrefs
import com.ketvoice.app.data.ArtistSignature
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.CatalogRequest
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.MusicArtist
import com.ketvoice.app.theme.Ket27
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.SectionHeader
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.gradientFor

/**
 * The sections of the artist page (6.1) and the « Plus de … » shelf of an album page: a section title
 * in the page's ink with an optional link at its end, release cards that fly into their album page,
 * the latest release, the similar artists' circles, « À propos ».
 */

/** A section title, as iOS writes it, with an optional link at its end (« Tout afficher »). */
@Composable
internal fun PageSectionTitle(title: String, margin: Dp, modifier: Modifier = Modifier, localize: Boolean = true,
                              action: String? = null, actionTag: String? = null, onAction: (() -> Unit)? = null) {
    val theme = LocalTheme.current
    // One height with or without the link, so titles side by side stay on one line.
    Row(modifier.fillMaxWidth().padding(start = margin, end = (margin - 8.dp).coerceAtLeast(0.dp), top = 22.dp, bottom = 6.dp)
        .heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        SectionHeader(title, Modifier.weight(1f), localize = localize)
        if (action != null && onAction != null) Text(action, color = theme.accent, style = Ket27.Type.body,
            maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onAction)
                .heightIn(min = 44.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 8.dp)
                .then(if (actionTag != null) Modifier.testTag(actionTag) else Modifier))
    }
}

/** A release's cover: cropped square, its initial on its own colours while it loads or when it has none. */
@Composable
internal fun ReleaseCover(album: Album, size: Dp, modifier: Modifier = Modifier, radius: Dp = 12.dp) {
    val art by ArtworkRepo.covers.collectAsState()
    val cover = MediaOverrides.albumCover(album.title, album.artist, art["a:${album.id}"] ?: album.coverUrl)
    Box(modifier.size(size).shadow(8.dp, RoundedCornerShape(radius), ambientColor = Color.Black.copy(alpha = .25f),
        spotColor = Color.Black.copy(alpha = .30f)).clip(RoundedCornerShape(radius))
        .background(Brush.linearGradient(gradientFor(album.id))), contentAlignment = Alignment.Center) {
        Text(album.title.take(1).uppercase(), localize = false, color = Color.White.copy(alpha = .85f),
            fontWeight = FontWeight.Black, fontSize = (size.value / 2.8f).sp)
        if (!cover.isNullOrBlank()) AsyncImage(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** A release in a shelf: its cover grows into the album page it opens (ui/motion/CoverZoom). */
@Composable
internal fun ReleaseCard(album: Album, width: Dp, caption: String?, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val art by ArtworkRepo.covers.collectAsState()
    val cover = MediaOverrides.albumCover(album.title, album.artist, art["a:${album.id}"] ?: album.coverUrl)
    val zoom = com.ketvoice.app.ui.motion.rememberZoomSource(cover, 12.dp)
    Column(modifier.width(width).clip(RoundedCornerShape(14.dp))
        .clickable(role = Role.Button) { zoom.openCatalog(CatalogRequest("album", album.id, album.title)) }
        .testTag("release-${album.id}")) {
        ReleaseCover(album, width, com.ketvoice.app.ui.motion.zoomSourceOf(zoom))
        Text(album.title, localize = false, color = theme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        if (!caption.isNullOrBlank()) Text(caption, color = theme.secondary, fontSize = 13.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** A horizontal shelf of releases under its title; [cell] is the width of a card. */
@Composable
internal fun ReleaseShelf(title: String, albums: List<Album>, margin: Dp, cell: Dp, tag: String,
                          localizeTitle: Boolean = true, caption: (Album) -> String?) {
    Column(Modifier.fillMaxWidth().testTag(tag)) {
        PageSectionTitle(title, margin, localize = localizeTitle)
        LazyRow(contentPadding = PaddingValues(horizontal = margin), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(albums, key = { it.id }) { album -> ReleaseCard(album, cell, caption(album)) }
        }
    }
}

/**
 * The latest release, as a card: its cover, the year, its title and kind. A touch opens it, its cover
 * flying into the album page.
 */
@Composable
internal fun LatestReleaseCard(album: Album, modifier: Modifier = Modifier, cover: Dp = 112.dp) {
    val theme = LocalTheme.current
    val art by ArtworkRepo.covers.collectAsState()
    val coverUrl = MediaOverrides.albumCover(album.title, album.artist, art["a:${album.id}"] ?: album.coverUrl)
    val zoom = com.ketvoice.app.ui.motion.rememberZoomSource(coverUrl, 12.dp)
    val shape = RoundedCornerShape(22.dp)
    Row(modifier.fillMaxWidth().clip(shape).background(theme.onBackground.copy(alpha = if (theme.isDark) .07f else .05f))
        .border(.8.dp, theme.onBackground.copy(alpha = .08f), shape)
        .clickable(role = Role.Button) { zoom.openCatalog(CatalogRequest("album", album.id, album.title)) }
        .padding(14.dp).testTag("artist-latest"), verticalAlignment = Alignment.CenterVertically) {
        ReleaseCover(album, cover, com.ketvoice.app.ui.motion.zoomSourceOf(zoom))
        Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            ArtistDiscography.year(album)?.let { year ->
                Text(year.toString(), localize = false, color = theme.secondary, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = .6.sp)
            }
            Text(album.title, localize = false, color = theme.onBackground, style = Ket27.Type.headline, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            Text(ArtistDiscography.caption(album, singles = true).substringBefore(" · "), color = theme.secondary,
                style = Ket27.Type.footnote, maxLines = 1)
        }
        Icon(KetIcons.ChevronRight, null, tint = theme.secondary, modifier = Modifier.padding(start = 8.dp).size(18.dp))
    }
}

/** The similar artists, as circles; a long press keeps one as a favourite or blocks it, as in the search. */
@Composable
internal fun SimilarArtists(related: List<MusicArtist>, margin: Dp) {
    // A blocked artist is not proposed (data/ArtistPrefs).
    val blocked = ArtistPrefs.blocked
    val shown = remember(related, blocked) { related.filter { !ArtistPrefs.isBlocked(it.name) } }
    if (shown.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("artist-similar")) {
        PageSectionTitle("Artistes similaires", margin)
        LazyRow(contentPadding = PaddingValues(horizontal = margin), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(shown, key = { it.id }) { artist ->
                com.ketvoice.app.ui.overlay.SearchArtistCircle(artist) { origin -> com.ketvoice.app.ui.AppUi.openArtist(artist.id, artist.name, origin) }
            }
        }
    }
}

/**
 * « À propos »: the biography in the app's language (the original one touch away), the number of
 * listeners and the artist's signature when the listener has signatures on.
 */
@Composable
internal fun ArtistAbout(page: ArtistPage, signature: ArtistSignature?, margin: Dp, modifier: Modifier = Modifier) {
    val theme = LocalTheme.current
    val description = page.description
    if (description.isBlank() && signature == null) return
    var expanded by remember(description) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().testTag("artist-about")) {
        PageSectionTitle("À propos", margin)
        val shape = RoundedCornerShape(22.dp)
        Column(Modifier.padding(horizontal = margin).widthIn(max = 760.dp).fillMaxWidth().clip(shape)
            .background(theme.onBackground.copy(alpha = if (theme.isDark) .07f else .05f))
            .border(.8.dp, theme.onBackground.copy(alpha = .08f), shape).padding(18.dp)) {
            if (description.isNotBlank()) {
                val language = AppText.language()
                val translation by produceState<String?>(null, description, language) {
                    value = ArtistBio.translated(description, language)
                }
                var original by remember(description) { mutableStateOf(false) }
                val translated = ArtistBio.differs(description, translation)
                // « Lire plus » only when the text is cut; once open, it stays to fold it again.
                var cut by remember(description) { mutableStateOf(false) }
                Column(Modifier.testTag("artist-bio")) {
                    Text(if (translated && !original) translation!! else description, localize = false, color = theme.onBackground.copy(alpha = .86f),
                        style = Ket27.Type.subheadline, maxLines = if (expanded) 60 else 5, overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (!expanded) cut = it.hasVisualOverflow })
                    if (cut || expanded || translated) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (cut || expanded) Text(if (expanded) "Afficher moins" else "Lire plus", color = theme.accent, fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 14.dp).clip(RoundedCornerShape(10.dp))
                                .clickable(role = Role.Button) { expanded = !expanded }
                                .heightIn(min = 44.dp).wrapContentHeight(Alignment.CenterVertically))
                        if (translated) Text(if (original) "Voir la traduction" else "Traduit automatiquement · Voir l'original",
                            color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { original = !original }
                                .heightIn(min = 44.dp).wrapContentHeight(Alignment.CenterVertically).testTag("artist-bio-original"))
                    }
                }
            }
            if (page.listeners.isNotBlank()) Text(page.listeners, localize = false, color = theme.secondary,
                style = Ket27.Type.footnote, modifier = Modifier.padding(top = 4.dp))
            if (signature != null) ArtistSignatureMark(page.artist.name, signature, Modifier.padding(top = 10.dp).widthIn(max = 320.dp))
        }
    }
}

/** The end of the artist page: block or unblock the artist (left out of what Ketvoice proposes), and a full reload. */
@Composable
internal fun ArtistPageFooter(artist: MusicArtist, margin: Dp, onRefresh: () -> Unit) {
    val theme = LocalTheme.current
    val blocked = ArtistPrefs.isBlocked(artist.name)
    val entry = remember(artist) { ArtistPrefs.Entry(artist.name, artist.id, artist.photo) }
    Row(Modifier.fillMaxWidth().padding(horizontal = margin - 12.dp).padding(top = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        com.ketvoice.app.ui.common.KetTextButton(if (blocked) "Débloquer l'artiste" else "Bloquer l'artiste",
            { ArtistPrefs.setBlocked(entry, !blocked) }, Modifier.testTag("artist-page-block"), tint = theme.secondary)
        com.ketvoice.app.ui.common.KetTextButton("Actualiser", onRefresh, Modifier.testTag("artist-refresh"), tint = theme.secondary)
    }
}
