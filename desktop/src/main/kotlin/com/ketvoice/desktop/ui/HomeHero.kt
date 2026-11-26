package com.ketvoice.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.Track
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.desktop.ui.glass.GlassStyle
import com.ketvoice.desktop.ui.glass.glass
import com.ketvoice.desktop.ui.glass.glassSource
import com.ketvoice.desktop.ui.glass.rememberGlassBackdrop
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet

/** What Home's hero shows: the last title heard (to pick it up again), else the first one picked for the listener. */
internal object HomeHeroPick {
    data class Hero(val track: Track, val resume: Boolean, val list: List<Track>, val index: Int)

    /** The card is at most 620 dp wide; the cover needs its own width and air on both sides. */
    fun showCover(roomDp: Float, coverDp: Float): Boolean = roomDp >= 620f + 26f + 72f + 72f + coverDp

    fun pick(history: List<Track>, forYou: List<Track>): Hero? = when {
        history.isNotEmpty() -> Hero(history.first(), true, history, 0)
        forYou.isNotEmpty() -> Hero(forYou.first(), false, forYou, 0)
        else -> null
    }
}

/**
 * Home's full-frame hero (Windows 2.0): the title's Canvas, or its cover, from the window's top edge to edge, under
 * the floating glass; its name large on a clear glass card that refracts the picture; Play and the album.
 */
@Composable
internal fun HomeHero(hero: HomeHeroPick.Hero) {
    val ket = LocalKet.current
    val track = hero.track
    val canvas = rememberHeaderCanvas(track, track.albumId)
    val image = rememberCover(CoverSources.track(track))
    val colors = rememberCoverColors(CoverSources.track(track))
    val wash = colors?.first ?: ket.card
    val picture = rememberGlassBackdrop()
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("home-hero")) {
        val height = (maxWidth.value * .40f).coerceIn(440f, 600f).dp
        val width = maxWidth
        Box(Modifier.fillMaxWidth().height(height)) {
            Box(Modifier.fillMaxSize().background(wash).glassSource(picture)) {
                if (canvas != null) FilledCanvas(canvas, playing = true, cover = CoverSources.track(track), modifier = Modifier.fillMaxSize())
                // Without a Canvas: the cover blurred to fill the frame (its sharp copy stands on the right).
                else if (image != null) Image(image, null, Modifier.fillMaxSize().blur(56.dp), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = .5f), .6f to Color.Black.copy(alpha = .14f),
                    1f to Color.Black.copy(alpha = .05f))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .28f), .3f to Color.Transparent,
                    .78f to Color.Transparent, 1f to ket.ground.copy(alpha = 1f).compositeOver(Color.Black))))
            }
            val insets = LocalShellInsets.current
            val end = insets.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
            val room = width - insets.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) - end
            // The sharp cover only where it does not crowd the card (a narrow window or an open panel leave it out).
            if (canvas == null && HomeHeroPick.showCover(room.value, height.value * .58f)) {
                Artwork(CoverSources.track(track), height * .58f, Modifier.align(Alignment.CenterEnd).padding(end = end + 72.dp, top = 12.dp).testTag("hero-cover"),
                    radius = Ket27Desk.Radius.card, shadow = 44.dp)
            }
            HeroCard(hero, picture, Modifier.align(Alignment.BottomStart).then(PageEdges.sides()).padding(bottom = 54.dp))
        }
    }
}

/** The words of the hero on clear glass: what it is, the title, the artist, Play and « Aller à l'album ». */
@Composable
private fun HeroCard(hero: HomeHeroPick.Hero, picture: com.ketvoice.desktop.ui.glass.GlassBackdrop, modifier: Modifier) {
    val ket = LocalKet.current
    val actions = LocalTrackActions.current
    val track = hero.track
    Column(modifier.widthIn(max = 620.dp).glass(picture, 30.dp, Color.Black.copy(alpha = .16f), GlassStyle.Clear, rim = Color.White.copy(alpha = .3f))
        .padding(horizontal = 30.dp, vertical = 26.dp)) {
        KText(AppText.t(if (hero.resume) "Reprendre l'écoute" else "Pour toi").uppercase(), Ket27Desk.Type.eyebrow,
            color = Color.White.copy(alpha = .78f), translate = false)
        Spacer(Modifier.height(8.dp))
        KText(track.title, Ket27Desk.Type.hero, color = Color.White, maxLines = 2, translate = false)
        Spacer(Modifier.height(4.dp))
        KText(listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "), Ket27Desk.Type.title3, color = Color.White.copy(alpha = .8f),
            translate = false, weight = FontWeight.Medium)
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CapsuleButton(if (hero.resume) "Reprendre" else "Lecture", KetIcons.Play, { actions.play(hero.list, hero.index) }, filled = true,
                accent = ket.accent, modifier = Modifier.testTag("hero-play"))
            if (track.album.isNotBlank()) CapsuleButton("Aller à l'album", KetIcons.Album, { actions.openAlbum(track) }, filled = false,
                accent = Color.White, modifier = Modifier.testTag("hero-album"))
        }
    }
}
