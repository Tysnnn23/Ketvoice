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
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val height = (maxWidth * (if (wide) .65f else .80f)).coerceIn(230.dp, 380.dp)
            Box(Modifier.fillMaxWidth().height(height).clipToBounds().background(theme.background)) {
                if (hero != null) {
                    // Square portraits fill the hero instead of being zoomed into a
                    // random face crop; wide banners are fitted whole.
                    // Intelligent full-bleed scale: the artwork always covers the whole
                    // hero, so no vertical "end of image" line can appear at the sides.
                    // A slight overscan hides any residual seam from odd aspect ratios.
                    AsyncImage(heroRequest, page.artist.name, contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            val c = collapse().coerceIn(0f, 1f)
                             scaleX = 1.02f; scaleY = 1.02f
                            translationY = if (Motion.reduced) 0f else c * size.height * .07f
                            alpha = 1f - c * .18f
                        })
                } else {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                        theme.accent.copy(alpha = if (theme.isDark) .22f else .14f),
                        theme.background))))
                    Box(Modifier.size(118.dp).clip(RoundedCornerShape(50))
                        .background(theme.accent.copy(alpha = .16f)).align(Alignment.Center),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Person, null, tint = theme.accent, modifier = Modifier.size(64.dp))
                    }
                }
                // Voile supérieur : le bouton retour flottant reste lisible.
                Box(Modifier.fillMaxWidth().height(96.dp).background(Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = .34f), Color.Transparent))))
                // Long fade into the page, Apple Music style: no hard edge.
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height * .26f)
                    .background(Brush.verticalGradient(
                        0f to Color.Transparent,
                        .30f to theme.background.copy(alpha = .12f),
                        .62f to theme.background.copy(alpha = .45f),
                        .94f to theme.background,
                        1f to theme.background)))
            }
        }
        // The portrait's colours continue as a glow under the name and the actions,
        // and give the glass buttons something to refract.
        com.ketvoice.app.ui.common.CollectionAmbient(hero, Modifier.fillMaxWidth(), strength = .85f, glow = true) {
        Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 16.dp)) {
            StaggeredReveal(order = 0) { ArtistIdentity(page.artist.name, artwork.signature ?: artwork.logo, startAligned = true) }
        }
        StaggeredReveal(order = 1, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start) {
                if (page.listeners.isNotBlank()) {
                    Text(page.listeners, localize = false, color = theme.secondary, fontSize = 12.5.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    Text(" · ", color = theme.secondary, fontSize = 12.5.sp)
                }
                Text("YouTube Music", color = theme.secondary, fontSize = 12.5.sp, maxLines = 1)
            }
        }
        if (page.tracks.isNotEmpty()) StaggeredReveal(order = 2, modifier = Modifier.fillMaxWidth()) {
        CollectionPlaybackActions(page.tracks, Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 18.dp), label = "Écouter")
        }
        }
        }
    }
}
