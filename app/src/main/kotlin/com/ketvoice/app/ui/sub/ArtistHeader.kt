package com.ketvoice.app.ui.sub

import androidx.compose.ui.platform.testTag
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
/**
 * The colour a whole artist page takes from the portrait: the page background pulled
 * towards the portrait's dominant colour. Light pages become a pastel of it, dark
 * pages a deep shade; without a colour the page keeps its own background.
 */
internal fun artistPageTint(background: Color, tone: Color?, dark: Boolean): Color {
    if (tone == null) return background
    val tinted = androidx.compose.ui.graphics.lerp(background, tone, if (dark) .30f else .38f)
    // A dark page, not a light page shown with dark controls, is held down at night.
    return if (dark && background.luminance() < .2f) PageTint.nightCap(tinted) else tinted
}

/**
 * Colours of pages taken from a cover or a Canvas. Dark, a bright cover must not light the
 * whole screen at night (the complaint against Apple Music's adaptive pages in iOS 26.4):
 * the page keeps its hue but never goes above a dim level.
 */
internal object PageTint {
    /** Relative luminance a dark page never exceeds, about that of #333333. */
    const val NIGHT_MAX_LUMINANCE = .033f

    fun nightCap(color: Color): Color {
        val luminance = color.luminance()
        if (luminance <= NIGHT_MAX_LUMINANCE) return color
        // Luminance follows the components roughly to the power 2.2: scale them back by its root.
        val factor = Math.pow((NIGHT_MAX_LUMINANCE / luminance).toDouble(), 1 / 2.2).toFloat()
        val scaled = Color(color.red * factor, color.green * factor, color.blue * factor, color.alpha)
        // One more small step where the curve's approximation leaves it just above.
        return if (scaled.luminance() <= NIGHT_MAX_LUMINANCE * 1.02f) scaled else Color(scaled.red * .97f, scaled.green * .97f, scaled.blue * .97f, scaled.alpha)
    }
}

@Composable
internal fun ArtistHeader(page: ArtistPage, collapse: () -> Float = { 0f }, onTone: (Color?) -> Unit = {}) {
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
    // A photo the owner chose for this artist comes first; its shape is read once it loads.
    val curated = com.ketvoice.app.data.MediaOverrides.artistHero(page.artist.name)
    val hero = curated ?: artwork.hero ?: sourcePhoto
    var curatedRatio by remember(curated) { mutableStateOf<Float?>(null) }
    val ratio = (if (curated != null) curatedRatio else page.heroAspectRatio)?.takeIf { it.isFinite() && it > 0f } ?: 1f
    val wide = ratio > 1.6f
    val heroRequest = remember(hero, Motion.reduced) {
        ImageRequest.Builder(context).data(hero).crossfade(Motion.duration(280)).build()
    }
    // The portrait fills the top of the page and dissolves into the page colour, which the
    // page itself takes from the portrait: photo and page read as one surface.
    val tone = com.ketvoice.app.theme.rememberCoverAccent(hero)?.first
    LaunchedEffect(tone) { onTone(tone) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val height = (maxWidth * (if (wide) .62f else .96f)).coerceIn(260.dp, 460.dp)
            Box(Modifier.fillMaxWidth().height(height).clipToBounds().testTag("artist-portrait")) {
                if (hero != null) {
                    AsyncImage(heroRequest, page.artist.name, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
                        onSuccess = { result ->
                            if (curated != null) result.painter.intrinsicSize.takeIf { it.height > 0f }?.let { curatedRatio = it.width / it.height }
                        },
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            val c = collapse().coerceIn(0f, 1f)
                            // A slight overscan hides any seam; the photo drifts a little as the page scrolls.
                            scaleX = 1.02f; scaleY = 1.02f
                            translationY = if (Motion.reduced) 0f else c * size.height * .07f
                        })
                } else {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                        theme.accent.copy(alpha = if (theme.isDark) .26f else .18f), theme.background))))
                    Box(Modifier.size(118.dp).clip(RoundedCornerShape(50)).background(theme.accent.copy(alpha = .16f))
                        .align(Alignment.Center), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Person, null, tint = theme.accent, modifier = Modifier.size(64.dp))
                    }
                }
                // The floating back button stays readable over a bright photo.
                Box(Modifier.fillMaxWidth().height(96.dp).background(Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = .30f), Color.Transparent))))
                // A long dissolve into the page colour: no line where the photo ends.
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height * .52f).background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        .22f to theme.background.copy(alpha = .10f),
                        .50f to theme.background.copy(alpha = .46f),
                        .78f to theme.background.copy(alpha = .86f),
                        1f to theme.background)))
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            StaggeredReveal(order = 0) { ArtistIdentity(page.artist.name, artwork.signature ?: artwork.logo, startAligned = false) }
            val meta = listOf(page.listeners, "YouTube Music").filter { it.isNotBlank() }.joinToString(" · ")
            StaggeredReveal(order = 1) {
                Text(meta, localize = false, color = theme.secondary, fontSize = 12.5.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
            }
            if (page.tracks.isNotEmpty()) StaggeredReveal(order = 2, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    com.ketvoice.app.ui.common.CollectionActionPair(
                        { Player.setShuffle(false); Player.playQueue(page.tracks) }, { Player.playShuffled(page.tracks) },
                        Modifier.widthIn(max = 560.dp), playLabel = "Écouter", playTag = "artist-play", shuffleTag = "artist-shuffle")
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}
