package com.ketvoice.app.ui.sub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
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

/** Portrait-led hero only; the app's navigation and catalogue remain unchanged. */
@Composable
internal fun ArtistHeader(page: ArtistPage) {
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
    val heroRequest = remember(hero) {
        ImageRequest.Builder(context).data(hero).crossfade(220).build()
    }
    val ambienceRequest = remember(hero) {
        ImageRequest.Builder(context).data(hero).crossfade(220).build()
    }
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val height = (maxWidth * (if (wide) .95f else 1.15f)).coerceIn(320.dp, 500.dp)
            Box(Modifier.fillMaxWidth().height(height).clipToBounds().background(theme.background)) {
                if (hero != null) {
                    // Blurred ambience fills the frame, so a wide banner never leaves
                    // two flat bands beside the artwork.
                    AsyncImage(ambienceRequest, null, contentScale = ContentScale.Crop,
                        alignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize().blur(42.dp).graphicsLayer { alpha = .58f })
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = .24f),
                        .40f to Color.Transparent,
                        1f to theme.background.copy(alpha = .30f))))
                    // Square portraits fill the hero instead of being zoomed into a
                    // random face crop; wide banners are fitted whole.
                    if (wide) {
                        AsyncImage(heroRequest, page.artist.name, contentScale = ContentScale.Fit,
                            alignment = Alignment.Center,
                            modifier = Modifier.fillMaxWidth().height(height * .80f)
                                .align(Alignment.TopCenter))
                    } else {
                        AsyncImage(heroRequest, page.artist.name, contentScale = ContentScale.Crop,
                            alignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize())
                    }
                } else {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                        theme.accent.copy(alpha = if (theme.isDark) .22f else .14f),
                        theme.background))))
                    Box(Modifier.size(118.dp).clip(RoundedCornerShape(50))
                        .background(theme.accent.copy(alpha = .16f)).align(Alignment.Center),
                        contentAlignment = Alignment.Center) {
                        Text(page.artist.name.take(1).uppercase(), localize = false,
                            color = theme.accent, fontSize = 52.sp, fontWeight = FontWeight.Black)
                    }
                }
                // Voile supérieur : le bouton retour flottant reste lisible.
                Box(Modifier.fillMaxWidth().height(96.dp).background(Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = .34f), Color.Transparent))))
                // Long fade into the page, Apple Music style: no hard edge.
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height * .70f)
                    .background(Brush.verticalGradient(
                        0f to Color.Transparent,
                        .30f to theme.background.copy(alpha = .35f),
                        .62f to theme.background.copy(alpha = .90f),
                        .86f to theme.background,
                        1f to theme.background)))
                val drawn: ArtistSignature? = artwork.signature ?: artwork.logo
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    ArtistIdentity(page.artist.name, drawn)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.padding(top = 8.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page.listeners.isNotBlank()) {
                    Text(page.listeners, localize = false, color = theme.secondary, fontSize = 12.5.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    Text(" · ", color = theme.secondary, fontSize = 12.5.sp)
                }
                Text("YouTube Music", color = theme.secondary, fontSize = 12.5.sp, maxLines = 1)
            }
        }
        if (page.tracks.isNotEmpty()) Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(50)).background(theme.accent)
                .clickable { Player.setShuffle(false); Player.playQueue(page.tracks) }.heightIn(min = 50.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, null, tint = onAccent, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(6.dp))
                Text("Écouter", color = onAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            GlassPanel(Modifier.weight(1f), RoundedCornerShape(50), GlassParams.pill(dark = theme.isDark),
                enabled = AppSettings.liquidGlass.value) {
                Row(Modifier.fillMaxWidth().clickable {
                    Player.playQueue(page.tracks.shuffled())
                    Player.setShuffle(true)
                }.heightIn(min = 50.dp).padding(horizontal = 10.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Shuffle, null, tint = theme.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Aléatoire", color = theme.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
