package com.ketvoice.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.Track
import com.ketvoice.app.theme.LocalTheme

/**
 * The covers of the app's own collections (7.2): Favoris wear a heart drawn on a gradient of the accent,
 * Téléchargements a mosaic of the covers of their first titles, never twice the same picture or album.
 */
internal object CoverMosaic {
    /** A picture's identity: the same image at another size (YouTube's « =w544-h544 », a query string) is the same. */
    fun imageKey(url: String): String {
        val base = url.trim().substringBefore('#')
        val sized = if ("googleusercontent." in base || "ggpht." in base || "ytimg." in base) base.substringBefore('=') else base
        return sized.lowercase()
    }

    private fun albumKey(track: Track): String? = track.album.takeIf { it.isNotBlank() }?.let {
        MediaOverrides.normalize(MediaOverrides.mainArtist(track.artist)) + "|" + MediaOverrides.normalize(it)
    }

    /**
     * Up to [limit] covers of [tracks], in their order, each a different picture of a different album:
     * a title whose cover or album was already used, or that has no cover, is skipped.
     */
    fun distinct(tracks: List<Track>, cover: (Track) -> String?, limit: Int = 4): List<String> {
        val images = HashSet<String>()
        val albums = HashSet<String>()
        val out = ArrayList<String>(limit)
        for (track in tracks) {
            if (out.size >= limit) break
            val url = cover(track)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val image = imageKey(url)
            val album = albumKey(track)
            if (image in images || (album != null && album in albums)) continue
            images += image
            if (album != null) albums += album
            out += url
        }
        return out
    }
}

/** The gradient of a collection cover: the accent a little lighter at the top left, deep at the bottom right. */
private fun collectionBrush(accent: Color): Brush = Brush.linearGradient(
    0f to lerp(accent, Color.White, .22f), 1f to lerp(accent, Color.Black, .48f),
    start = Offset.Zero, end = Offset.Infinite)

/** A heart in a unit square, drawn at any size from its curves (crisp on every screen). */
private fun heartPath(side: Float, origin: Offset): Path = Path().apply {
    fun p(x: Float, y: Float) = Offset(origin.x + x * side, origin.y + y * side)
    val tip = p(.5f, .9f)
    moveTo(tip.x, tip.y)
    p(.16f, .66f).let { a -> p(.02f, .47f).let { b -> p(.02f, .31f).let { c -> cubicTo(a.x, a.y, b.x, b.y, c.x, c.y) } } }
    p(.02f, .15f).let { a -> p(.14f, .05f).let { b -> p(.28f, .05f).let { c -> cubicTo(a.x, a.y, b.x, b.y, c.x, c.y) } } }
    p(.38f, .05f).let { a -> p(.46f, .1f).let { b -> p(.5f, .18f).let { c -> cubicTo(a.x, a.y, b.x, b.y, c.x, c.y) } } }
    p(.54f, .1f).let { a -> p(.62f, .05f).let { b -> p(.72f, .05f).let { c -> cubicTo(a.x, a.y, b.x, b.y, c.x, c.y) } } }
    p(.86f, .05f).let { a -> p(.98f, .15f).let { b -> p(.98f, .31f).let { c -> cubicTo(a.x, a.y, b.x, b.y, c.x, c.y) } } }
    p(.98f, .47f).let { a -> p(.84f, .66f).let { b -> cubicTo(a.x, a.y, b.x, b.y, tip.x, tip.y) } }
    close()
}

/** The cover of Favoris: a white heart on the accent's gradient, filling its box. */
@Composable
internal fun LikedCover(modifier: Modifier = Modifier) {
    val accent = LocalTheme.current.accent
    Box(modifier.fillMaxSize().testTag("liked-cover-art").drawWithCache {
        val brush = collectionBrush(accent)
        val side = size.minDimension * .44f
        val heart = heartPath(side, Offset((size.width - side) / 2f, (size.height - side) / 2f + side * .04f))
        onDrawBehind {
            drawRect(brush)
            drawPath(heart, Color.White.copy(alpha = .96f))
        }
    })
}

/**
 * The cover of Téléchargements: four covers in a 2×2 mosaic; three as one tall and two small, two side by
 * side, one alone. With none, the download arrow on the accent's gradient.
 */
@Composable
internal fun MosaicCover(covers: List<String>, modifier: Modifier = Modifier) {
    val accent = LocalTheme.current.accent
    val card = LocalTheme.current.card
    @Composable fun tile(url: String, place: Modifier) =
        AsyncImage(url, null, place.background(card), contentScale = ContentScale.Crop)
    Box(modifier.fillMaxSize().testTag("downloads-mosaic")) {
        when (covers.size) {
            0 -> Box(Modifier.fillMaxSize().drawWithCache { val brush = collectionBrush(accent); onDrawBehind { drawRect(brush) } },
                contentAlignment = Alignment.Center) {
                com.ketvoice.app.ui.common.Icon(com.ketvoice.app.ui.common.KetIcons.Download, null, tint = Color.White,
                    modifier = Modifier.fillMaxSize(.42f))
            }
            1 -> tile(covers[0], Modifier.fillMaxSize())
            2 -> Row(Modifier.fillMaxSize()) {
                tile(covers[0], Modifier.weight(1f).fillMaxHeight()); tile(covers[1], Modifier.weight(1f).fillMaxHeight())
            }
            3 -> Row(Modifier.fillMaxSize()) {
                tile(covers[0], Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    tile(covers[1], Modifier.weight(1f).fillMaxWidth()); tile(covers[2], Modifier.weight(1f).fillMaxWidth())
                }
            }
            else -> Column(Modifier.fillMaxSize()) {
                covers.take(4).chunked(2).forEach { line ->
                    Row(Modifier.weight(1f).fillMaxWidth()) { line.forEach { tile(it, Modifier.weight(1f).fillMaxHeight()) } }
                }
            }
        }
    }
}

/** The covers the Téléchargements mosaic shows: the latest saved titles first, distinct pictures only. */
internal fun downloadMosaic(items: List<com.ketvoice.app.data.Downloader.Item>): List<String> =
    CoverMosaic.distinct(items.sortedByDescending { it.file.lastModified() }.map { it.track }, {
        com.ketvoice.app.data.ArtworkRepo.coverForTrack(it) ?: it.coverUrl
    })
