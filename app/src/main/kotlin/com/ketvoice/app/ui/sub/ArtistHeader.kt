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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.AppSettings
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
    val onAccent = if (theme.accent.luminance() > .179f) Color.Black else Color.White
    val signatures = AppSettings.artistSignatures.value
    var signature by remember(page.artist.name, signatures) { mutableStateOf<ArtistSignature?>(null) }
    LaunchedEffect(page.artist.name, signatures) {
        signature = if (signatures) ArtistSignatures.find(page.artist.name) else null
    }
    val photo = page.artist.photo?.takeIf { it.isNotBlank() }
    val ratio = page.heroAspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
    val wide = ratio > 1.6f
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val height = (maxWidth * (if (wide) .92f else 1.12f)).coerceIn(300.dp, 470.dp)
            Box(Modifier.fillMaxWidth().height(height).clipToBounds().background(theme.background)) {
                if (photo != null) {
                    // Blurred ambience fills the frame, so a wide banner never leaves
                    // two flat bands beside the artwork.
                    AsyncImage(photo, null, contentScale = ContentScale.Crop,
                        alignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize().blur(42.dp).graphicsLayer { alpha = .58f })
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = .22f),
                        .42f to Color.Transparent,
                        1f to theme.background.copy(alpha = .30f))))
                    // Square portraits fill the hero; wide banners are fitted whole
                    // instead of being zoomed into an arbitrary face crop.
                    if (wide) {
                        AsyncImage(photo, page.artist.name, contentScale = ContentScale.Fit,
                            alignment = Alignment.Center,
                            modifier = Modifier.fillMaxWidth().height(height * .76f)
                                .align(Alignment.TopCenter))
                    } else {
                        AsyncImage(photo, page.artist.name, contentScale = ContentScale.Crop,
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
                // Fondu long : la photo se dissout dans la page au lieu de
                // s'arrêter sur une bande franche.
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height * .66f)
                    .background(Brush.verticalGradient(
                        0f to Color.Transparent,
                        .38f to theme.background.copy(alpha = .48f),
                        .74f to theme.background.copy(alpha = .95f),
                        1f to theme.background)))
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    ArtistIdentity(page.artist.name, signature.takeIf { signatures })
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
