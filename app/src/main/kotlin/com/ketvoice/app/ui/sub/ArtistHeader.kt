package com.ketvoice.app.ui.sub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
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
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.ArtistPage
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text

/**
 * Bannière d'artiste immersive : photo aux proportions naturelles (jamais
 * agrandie ni recadrée sur le visage), fondu long vers la page, puis le nom et
 * les actions posés dans ce fondu. Les deux boutons sont ceux qu'on utilise
 * vraiment sur une fiche artiste : lecture et aléatoire.
 */
@Composable
internal fun ArtistHeader(page: ArtistPage) {
    val theme = LocalTheme.current
    val onAccent = if (theme.accent.luminance() > .179f) Color.Black else Color.White
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val ratio = page.heroAspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
            // Hauteur naturelle de la bannière, bornée pour que le nom reste
            // lisible même sur une photo très panoramique.
            val height = (maxWidth / ratio.coerceIn(.6f, 4f)).coerceIn(220.dp, 430.dp)
            Box(Modifier.fillMaxWidth().height(height).clipToBounds()
                .background(Brush.verticalGradient(listOf(theme.card, theme.background)))) {
                // Halo d'accent : donne de la profondeur derrière la photo sans
                // jamais l'agrandir ni la recadrer.
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0f to theme.accent.copy(alpha = if (theme.isDark) .20f else .12f),
                    .48f to Color.Transparent)))
                if (!page.artist.photo.isNullOrBlank()) {
                    AsyncImage(page.artist.photo, page.artist.name, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize())
                } else Text(page.artist.name.take(1).uppercase(), localize = false,
                    color = theme.accent.copy(alpha = .55f), fontSize = 120.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center))
                // Voile supérieur : le bouton retour flottant reste lisible.
                Box(Modifier.fillMaxWidth().height(96.dp).background(Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = .38f), Color.Transparent))))
                // Fondu long : la photo se dissout dans la page au lieu de
                // s'arrêter sur une bande franche.
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height * .62f)
                    .background(Brush.verticalGradient(
                        0f to Color.Transparent,
                        .42f to theme.background.copy(alpha = .55f),
                        .74f to theme.background.copy(alpha = .92f),
                        1f to theme.background)))
            }
        }
        // Le bloc texte mord sur le fondu : la photo et le nom forment un seul
        // ensemble au lieu de deux blocs empilés.
        Column(Modifier.fillMaxWidth().offset(y = (-20).dp).padding(horizontal = 24.dp)) {
            Text(AppText.t("Artiste").uppercase(), color = theme.accent, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
            Text(page.artist.name, localize = false, color = theme.onBackground, fontSize = 38.sp,
                lineHeight = 43.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.2).sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                Text("Lecture", color = onAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
