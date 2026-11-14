package com.ketvoice.app.ui.sub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ArtistArtwork
import com.ketvoice.app.data.ArtistPage
import com.ketvoice.app.data.ArtistSignature
import com.ketvoice.app.data.ArtistSignatures
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.common.CollectionPlaybackActions
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.StaggeredReveal

/** Portrait-led hero only; the app's navigation and catalogue remain unchanged. */
@Composable
internal fun ArtistHeader(page: ArtistPage, collapse: () -> Float = { 0f }) {
    val theme = LocalTheme.current
    val context = LocalContext.current
    val onAccent = if (theme.accent.luminance() > .179f) Color.Black else Color.White
    val signatures = AppSettings.artistSignatures.value
    val photos = AppSettings.artistPhotos.value
    var artwork by remember(page.artist.name, signatures, photos) { mutableStateOf(ArtistArtwork()) }
    LaunchedEffect(page.artist.name, signatures, photos) {
        artwork = if (photos || signatures) {
            ArtistSignatures.artwork(page.artist.name, includeSignature = signatures)
        } else ArtistArtwork()
    }
    val sourcePhoto = page.artist.photo?.takeIf { it.isNotBlank() }
    val hero = artwork.hero ?: sourcePhoto
    val ratio = page.heroAspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
    val wide = ratio > 1.6f
    val heroRequest = remember(hero, Motion.reduced) {
        ImageRequest.Builder(context).data(hero).crossfade(Motion.duration(280)).build()
    }
    // The shared collection header: the portrait floats, round, over its own blurred colours.
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
    com.ketvoice.app.ui.common.CollectionHero(hero, top, page.artist.name,
        meta = listOf(page.listeners, "YouTube Music").filter { it.isNotBlank() }.joinToString(" · "),
        round = true, artworkTag = "artist-portrait",
        titleSlot = { centred -> ArtistIdentity(page.artist.name, artwork.signature ?: artwork.logo, startAligned = !centred) },
        artwork = {
            // The glyph stays behind the photo: never an empty disc while it loads.
            Box(Modifier.fillMaxSize().background(theme.accent.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Person, null, tint = theme.accent, modifier = Modifier.fillMaxSize(.5f))
            }
            if (hero != null) AsyncImage(heroRequest, page.artist.name, contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
        }) {
        if (page.tracks.isNotEmpty()) com.ketvoice.app.ui.common.CollectionActionPair(
            { Player.setShuffle(false); Player.playQueue(page.tracks) }, { Player.playShuffled(page.tracks) },
            Modifier.widthIn(max = 560.dp), playLabel = "Écouter", playTag = "artist-play", shuffleTag = "artist-shuffle")
    }
}
