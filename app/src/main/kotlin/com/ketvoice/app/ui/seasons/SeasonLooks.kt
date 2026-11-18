package com.ketvoice.app.ui.seasons

import android.annotation.SuppressLint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.theme.SeasonLook
import com.ketvoice.app.theme.SeasonPalette
import com.ketvoice.app.theme.SeasonPulse
import com.ketvoice.app.theme.seasonTopGlow

/** Which drawing a season's decor uses (see `SeasonMotifs.kt`). */
internal enum class Motif { Snow, Fireworks, Hearts, Blossom, Sun, Night }

/**
 * The looks of the seasonal themes (6.2), by the registry's id ([com.ketvoice.app.data.Seasons]). Vice City is not
 * one of them: it keeps the listener's theme with its own accent (`theme/ViceCityTheme.kt`). Every season here is a
 * palette and a decor drawn in code, until the owner's
 * pictures are added under the names documented on [com.ketvoice.app.data.SeasonalTheme] — they are then shown
 * in place of the drawing, without a code change.
 */
object SeasonLooks {
    private fun c(argb: Long) = Color(argb)

    val noel: SeasonLook = DrawnSeason("noel", Motif.Snow, SeasonPalette(
        night = c(0xFF0B1A15), card = c(0xFF15291F), secondary = c(0xFFB7D0C3), hairline = c(0x33CDEBD8),
        accent = c(0xFFFF7A6B), core = c(0xFFD8343A), mid = c(0xFFF2C14E), far = c(0xFF1F7A4E), cool = c(0xFFCDEBFF),
        fill = listOf(c(0xFFFF6A5C), c(0xFFFF8F6B), c(0xFFF5C451)), ink = c(0xFF0B1A15),
        edge = listOf(c(0xFFFF6A5C), c(0xFFF5C451), c(0xFFCDEBFF))))

    val nouvelAn: SeasonLook = DrawnSeason("nouvelan", Motif.Fireworks, SeasonPalette(
        night = c(0xFF090D1E), card = c(0xFF151A33), secondary = c(0xFFBEC4E0), hairline = c(0x33DCE2FF),
        accent = c(0xFFF5C451), core = c(0xFFC8902A), mid = c(0xFFE9C46A), far = c(0xFF3A3F9E), cool = c(0xFFCFD8FF),
        fill = listOf(c(0xFFE8B23A), c(0xFFF5C451), c(0xFFFFDF8A)), ink = c(0xFF0E1124),
        edge = listOf(c(0xFFE8B23A), c(0xFFFFDF8A), c(0xFFCFD8FF))))

    val saintValentin: SeasonLook = DrawnSeason("saintvalentin", Motif.Hearts, SeasonPalette(
        night = c(0xFF1C0912), card = c(0xFF2C121F), secondary = c(0xFFE2BFCB), hairline = c(0x33FFD1DC),
        accent = c(0xFFFF7A9C), core = c(0xFFE0457B), mid = c(0xFFFF9EB0), far = c(0xFF7A1E4A), cool = c(0xFFFFD1DC),
        fill = listOf(c(0xFFFF6F91), c(0xFFFF8FA8), c(0xFFFFB3C3)), ink = c(0xFF1C0912),
        edge = listOf(c(0xFFE0457B), c(0xFFFF9EB0), c(0xFFFFD1DC))))

    val printemps: SeasonLook = DrawnSeason("printemps", Motif.Blossom, SeasonPalette(
        night = c(0xFF0C1A19), card = c(0xFF162A28), secondary = c(0xFFBCD6CF), hairline = c(0x33D9F2E6),
        accent = c(0xFFFF9EC4), core = c(0xFFE76FA3), mid = c(0xFFB5E48C), far = c(0xFF2E7D6B), cool = c(0xFFFFF1B0),
        fill = listOf(c(0xFFFF9EC4), c(0xFFFFC2D6), c(0xFFC6EE9E)), ink = c(0xFF0C1A19),
        edge = listOf(c(0xFFE76FA3), c(0xFFFFC2D6), c(0xFFB5E48C))))

    val ete: SeasonLook = DrawnSeason("ete", Motif.Sun, SeasonPalette(
        night = c(0xFF071827), card = c(0xFF10283E), secondary = c(0xFFB7CEDD), hairline = c(0x33CBE8F5),
        accent = c(0xFFFFB347), core = c(0xFFE8742C), mid = c(0xFFFFD166), far = c(0xFF1B6CA8), cool = c(0xFF5FE1D9),
        fill = listOf(c(0xFFFF9A4D), c(0xFFFFB347), c(0xFFFFD66B)), ink = c(0xFF071827),
        edge = listOf(c(0xFFFF9A4D), c(0xFFFFD66B), c(0xFF5FE1D9))))

    val halloween: SeasonLook = DrawnSeason("halloween", Motif.Night, SeasonPalette(
        night = c(0xFF120D0A), card = c(0xFF221912), secondary = c(0xFFD4C3B2), hairline = c(0x33F5DCC4),
        accent = c(0xFFFF9A2E), core = c(0xFFE0661A), mid = c(0xFFFFB347), far = c(0xFF5B2A86), cool = c(0xFFB18CFF),
        fill = listOf(c(0xFFFF8A1F), c(0xFFFFA43D), c(0xFFFFC456)), ink = c(0xFF120D0A),
        edge = listOf(c(0xFFFF8A1F), c(0xFFFFC456), c(0xFFB18CFF))))

    val all: List<SeasonLook> get() = listOf(noel, nouvelAn, saintValentin, printemps, ete, halloween)

    /** The look of the season [id], or null (no season, or an unknown id). */
    fun of(id: String?): SeasonLook? = if (id == null) null else all.firstOrNull { it.id == id }
}

/** An optional picture of the owner's for [id] (`season_<id>_<slot>`), or 0 while there is none. */
@SuppressLint("DiscouragedApi")
@Composable
internal fun seasonImage(id: String, slot: String): Int {
    val context = LocalContext.current
    return remember(id, slot) {
        runCatching { context.resources.getIdentifier("season_${id}_$slot", "drawable", context.packageName) }.getOrDefault(0)
    }
}

/** A season drawn in code: the palette's glows and edge, and its [motif] over Home's header. */
internal class DrawnSeason(id: String, val motif: Motif, palette: SeasonPalette) : SeasonLook(id, palette) {
    @Composable override fun HomeDecor(modifier: Modifier, fade: () -> Float) {
        val picture = seasonImage(id, "header")
        Box(modifier.fillMaxWidth().height(250.dp).clearAndSetSemantics { testTag = "season-decor-$id" }
            .graphicsLayer { alpha = 1f - fade().coerceIn(0f, 1f) }) {
            if (picture != 0) Image(painterResource(picture), null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            else Canvas(Modifier.fillMaxSize()) { drawMotif(motif, palette, SeasonPulse.phase.floatValue, 1f) }
        }
    }

    @Composable override fun Emblem(size: Dp, modifier: Modifier) {
        val picture = seasonImage(id, "emblem")
        val shape = RoundedCornerShape(size * .23f)
        if (picture != 0) Image(painterResource(picture), null, modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
        else Canvas(modifier.size(size).clip(shape).background(Brush.linearGradient(listOf(palette.far, palette.night)))) {
            drawEmblem(motif, palette)
        }
    }

    @Composable override fun Preview(modifier: Modifier, height: Dp) {
        val shape = RoundedCornerShape(18.dp)
        Box(modifier.fillMaxWidth().height(height).clip(shape).background(palette.night)
            .drawBehind { seasonTopGlow(this@DrawnSeason, .25f) }
            .clearAndSetSemantics { testTag = "season-preview-$id" }) {
            Canvas(Modifier.fillMaxSize()) { drawMotif(motif, palette, .25f, .5f) }
            PreviewBar(this@DrawnSeason, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
        }
    }
}

/** The tab bar of a preview: a selected tab with the season's edge, and a filled button. */
@Composable
private fun PreviewBar(look: SeasonLook, modifier: Modifier) {
    val pill = RoundedCornerShape(50)
    Box(modifier.size(width = 150.dp, height = 26.dp).clip(pill).background(Color.White.copy(alpha = .10f))) {
        Box(Modifier.padding(3.dp).fillMaxHeight().width(48.dp)
            .then(with(look) { Modifier.seasonEdge(pill, true) })
            .clip(pill).background(Color.White.copy(alpha = .12f)))
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).size(width = 52.dp, height = 14.dp).clip(pill)
            .background(look.fill))
    }
}
