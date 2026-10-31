package com.ketvoice.app.ui.sub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ArtistPage
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text

/** Natural photo proportions, no bitmap/shader coordinate tricks or magnified face backdrop. */
@Composable
internal fun ArtistHeader(page: ArtistPage) {
    val theme = LocalTheme.current
    val onAccent = if (theme.accent.luminance() > .179f) Color.Black else Color.White
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val ratio = page.heroAspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
            val height = (maxWidth / ratio.coerceIn(.6f, 4f)).coerceIn(180.dp, 420.dp)
            Box(Modifier.fillMaxWidth().height(height).clipToBounds()
                .background(Brush.verticalGradient(listOf(theme.card, theme.background)))) {
                if (!page.artist.photo.isNullOrBlank()) {
                    // Fit keeps the face and complete provider photo, including panoramic headers.
                    AsyncImage(page.artist.photo, page.artist.name, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize())
                } else Text(page.artist.name.take(1).uppercase(), localize = false,
                    color = theme.accent.copy(alpha = .55f), fontSize = 120.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(50.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, theme.background))))
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 16.dp)) {
            Text(page.artist.name, localize = false, color = theme.onBackground, fontSize = 36.sp,
                lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (page.listeners.isNotBlank()) Text(page.listeners, localize = false, color = theme.secondary,
                fontSize = 13.sp, modifier = Modifier.padding(top = 9.dp))
            Text("Artiste · YouTube Music", color = theme.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            if (page.tracks.isNotEmpty()) Row(Modifier.padding(top = 19.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(theme.accent)
                    .clickable { Player.playQueue(page.tracks) }.heightIn(min = 50.dp).padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PlayArrow, null, tint = onAccent, modifier = Modifier.size(21.dp))
                    Spacer(Modifier.width(6.dp));Text("Lecture", color = onAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                GlassPanel(Modifier.weight(1f), RoundedCornerShape(16.dp), GlassParams.card(16f,theme.isDark),
                    enabled = AppSettings.liquidGlass.value, captured = true) {
                    Box(Modifier.fillMaxWidth().clickable { AppUi.pushOverlay(AppUi.QUEUE) }
                        .heightIn(min = 50.dp).padding(horizontal = 10.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text("File d'attente", color = theme.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
