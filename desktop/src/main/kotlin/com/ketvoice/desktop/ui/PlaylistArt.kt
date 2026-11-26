package com.ketvoice.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.Track
import com.ketvoice.desktop.ui.theme.Ket27Desk

/**
 * A playlist's cover as the phone draws it (app `ui/library/PlaylistArtwork.kt`): its own picture (an imported
 * playlist's artwork); else a 2 × 2 mosaic of its first four titles' covers, a deep gradient with the title's initial
 * where a title has none; an empty playlist is the gradient alone. Never the grey placeholder.
 */
internal object PlaylistMosaic {
    private val tints = listOf(0xFF514479, 0xFF2C5364, 0xFF6B4E71, 0xFF1F4037, 0xFF4B3621, 0xFF26475C).map { Color(it) }
    val DEEP = Color(0xFF151C2C)

    /** The phone's tint for a title or a playlist name. */
    fun tint(seed: String): Color = if (seed.isBlank()) tints[0] else tints[Math.floorMod(seed.hashCode(), tints.size)]

    /** The four tiles of the mosaic (null where the playlist has fewer titles); none for an empty playlist. */
    fun tiles(tracks: List<Track>): List<Track?> = if (tracks.isEmpty()) emptyList() else List(4) { tracks.getOrNull(it) }

    /** What a tile without a cover shows: the title's first letter, else a note. */
    fun initial(track: Track?): String = track?.title?.trim()?.take(1)?.uppercase()?.takeIf { it.isNotBlank() } ?: "♪"
}

/** The cover of a playlist of [tracks] at [size]; [cover] is its own picture when it has one. */
@Composable
fun Mosaic(tracks: List<Track>, size: Dp, cover: String? = null, name: String = "") {
    val big = size > 100.dp
    val own = CoverSources.playlist(cover)
    if (own != null) { Artwork(own, size, radius = if (big) Ket27Desk.Radius.artwork else 6.dp, shadow = if (big) 24.dp else 0.dp); return }
    val tiles = PlaylistMosaic.tiles(tracks)
    Box(Modifier.size(size).clip(RoundedCornerShape(if (big) Ket27Desk.Radius.artwork else 6.dp)).testTag("playlist-mosaic")) {
        if (tiles.isEmpty()) Box(Modifier.fillMaxSize().background(gradientOf(name)))
        else Column { for (r in 0..1) Row { for (c in 0..1) MosaicTile(tiles[r * 2 + c], size / 2) } }
    }
}

private fun gradientOf(seed: String) = Brush.linearGradient(listOf(PlaylistMosaic.tint(seed), PlaylistMosaic.DEEP))

@Composable
private fun MosaicTile(track: Track?, side: Dp) {
    val image = rememberCover(CoverSources.track(track), pixelsOf(side))
    Box(Modifier.size(side).background(gradientOf(track?.title.orEmpty())), contentAlignment = Alignment.Center) {
        if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
        else if (side >= 20.dp) KText(PlaylistMosaic.initial(track),
            Ket27Desk.Type.headline.copy(fontSize = Ket27Desk.Type.headline.fontSize * (side.value / 60f).coerceIn(.5f, 2.2f)),
            color = Color.White.copy(alpha = .82f), translate = false, weight = FontWeight.Black)
    }
}
