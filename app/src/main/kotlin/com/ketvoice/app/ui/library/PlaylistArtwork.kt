package com.ketvoice.app.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ketvoice.app.R
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.LocalPlaylist
import com.ketvoice.app.data.PlaylistEntry
import com.ketvoice.app.ui.common.Text

private val fallbackTints = listOf(
    Color(0xFF514479), Color(0xFF2C5364), Color(0xFF6B4E71),
    Color(0xFF1F4037), Color(0xFF4B3621), Color(0xFF26475C)
)

private fun tintFor(seed: String): Color {
    if (seed.isBlank()) return fallbackTints[0]
    return fallbackTints[kotlin.math.abs(seed.hashCode()) % fallbackTints.size]
}

/** Une tuile de la mosaïque : pochette réelle, sinon dégradé profond + initiale. */
@Composable
private fun MosaicTile(entry: PlaylistEntry?, modifier: Modifier = Modifier) {
    val track = entry?.track
    val cover = track?.let { ArtworkRepo.coverForTrack(it) } ?: track?.coverUrl
    Box(modifier.background(Brush.linearGradient(
        listOf(tintFor(entry?.title ?: ""), Color(0xFF151C2C))))) {
        if (cover != null) {
            AsyncImage(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(
                entry?.title?.trim()?.take(1)?.uppercase()?.takeIf { it.isNotBlank() } ?: "♪",
                localize = false,
                color = Color.White.copy(alpha = .82f),
                fontSize = 19.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/**
 * Couverture d'une playlist :
 *  - pochette réelle si la playlist en a une (import Spotify avec image officielle) ;
 *  - sinon mosaïque 2x2 des pochettes réelles des titres, avec dégradé propre en repli ;
 *  - petit logo Spotify en bas à gauche pour un import Spotify ;
 *  - plus jamais de couverture générique « KETVOICE M » ni de simple lettre.
 */
@Composable
fun PlaylistArtwork(playlist: LocalPlaylist?, label: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(18.dp))) {
        val cover = playlist?.coverUrl
        if (cover != null) {
            AsyncImage(cover, label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            val entries = playlist?.entries.orEmpty()
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(
                    listOf(tintFor(playlist?.name ?: label), Color(0xFF151C2C)))))
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f)) {
                        MosaicTile(entries.getOrNull(0), Modifier.weight(1f).fillMaxHeight())
                        MosaicTile(entries.getOrNull(1), Modifier.weight(1f).fillMaxHeight())
                    }
                    Row(Modifier.weight(1f)) {
                        MosaicTile(entries.getOrNull(2), Modifier.weight(1f).fillMaxHeight())
                        MosaicTile(entries.getOrNull(3), Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
        if (playlist?.spotifyUrl != null) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = .42f)),
                        startY = size.height * .55f, endY = size.height
                    )
                )
            }
            Box(Modifier.align(Alignment.BottomStart).padding(7.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_spotify_official),
                    contentDescription = "Import Spotify",
                    // Le vecteur porte les couleurs officielles : aucun tint.
                    tint = Color.Unspecified,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
