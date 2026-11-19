package com.ketvoice.app.ui.sub

import com.ketvoice.app.ui.motion.zoomTarget
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radio
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ArtistArtwork
import com.ketvoice.app.data.ArtistPage
import com.ketvoice.app.data.ArtistPrefs
import com.ketvoice.app.data.ArtistSignatures
import com.ketvoice.app.data.CanvasRepo
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.MusicArtist
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Text
import com.ketvoice.app.ui.motion.Motion
import com.ketvoice.app.ui.motion.StaggeredReveal
import com.ketvoice.app.ui.motion.ZoomTarget

/**
 * The colour a whole artist page takes from the portrait: the page background pulled
 * towards the portrait's dominant colour. Light pages become a pastel of it, dark
 * pages a deep shade; without a colour the page keeps its own background.
 */
internal fun artistPageTint(background: Color, tone: Color?, dark: Boolean): Color {
    if (tone == null) return background
    // Noir (7.1): dark pages stay pure black; the cover's colours remain in the header's ambient and the accent.
    if (dark && com.ketvoice.app.data.AppSettings.trueBlack.value && background.luminance() < .2f) return background
    val tinted = androidx.compose.ui.graphics.lerp(background, tone, if (dark) .30f else .38f)
    // A dark page, not a light page shown with dark controls, is held down at night.
    if (dark && background.luminance() < .2f) return PageTint.nightCap(tinted)
    // A light page stays light under a dark artwork: dark text must stay readable on it.
    return if (!dark) PageTint.dayFloor(tinted, background) else tinted
}

/**
 * Colours of pages taken from a cover or a Canvas. Dark, a bright cover must not light the
 * whole screen at night (the complaint against Apple Music's adaptive pages in iOS 26.4):
 * the page keeps its hue but never goes above a dim level.
 */
internal object PageTint {
    /** Relative luminance a dark page never exceeds, about that of #333333. */
    const val NIGHT_MAX_LUMINANCE = .033f

    /** Relative luminance a light page never goes under. */
    const val DAY_MIN_LUMINANCE = .55f

    fun dayFloor(color: Color, background: Color): Color {
        val l = color.luminance(); val lb = background.luminance()
        if (l >= DAY_MIN_LUMINANCE || lb <= l) return color
        // Back towards the page colour, just enough.
        var t = ((DAY_MIN_LUMINANCE - l) / (lb - l)).coerceIn(0f, 1f)
        var result = androidx.compose.ui.graphics.lerp(color, background, t)
        repeat(4) { if (result.luminance() < DAY_MIN_LUMINANCE) { t = (t + .1f).coerceAtMost(1f); result = androidx.compose.ui.graphics.lerp(color, background, t) } }
        return result
    }

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

/** The artist's photos, fan art, logo and signature, as the listener's settings allow them; empty while they load. */
@Composable
internal fun rememberArtistArtwork(name: String, revision: Int = 0): ArtistArtwork {
    val signatures = AppSettings.artistSignatures.value
    val photos = AppSettings.artistPhotos.value
    var artwork by remember(name, signatures, photos) { mutableStateOf(ArtistArtwork()) }
    LaunchedEffect(name, signatures, photos, revision) {
        artwork = if (photos || signatures) ArtistSignatures.artwork(name, includeSignature = signatures) else ArtistArtwork()
    }
    return artwork
}

/**
 * The hero of an artist page (6.1, after Apple Music): the photo (or fan art) fills the top of the
 * page edge to edge and drifts slowly while motion is allowed; the name sits over its lower part,
 * where the photo dissolves into the page colour, with Listen, Shuffle and the favourite in glass.
 * A phone opens on a tall portrait, a wide window on a banner. The artist radio follows below.
 */
@Composable
internal fun ArtistHeader(page: ArtistPage, collapse: () -> Float = { 0f }, artwork: ArtistArtwork? = null,
                          onTone: (Color?) -> Unit = {}) {
    val theme = LocalTheme.current
    val art = artwork ?: rememberArtistArtwork(page.artist.name)
    val sourcePhoto = page.artist.photo?.takeIf { it.isNotBlank() }
    // A photo the owner chose for this artist comes first.
    val curated = MediaOverrides.artistHero(page.artist.name)
    val hero = curated ?: art.hero ?: sourcePhoto
    // The page takes its colour from the portrait: the photo and the page read as one surface.
    val tone = com.ketvoice.app.theme.rememberCoverAccent(hero)?.first
    LaunchedEffect(tone) { onTone(tone) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = ArtistLayout.wide(maxWidth.value)
        val window = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.toFloat()
        val height = ArtistLayout.heroHeight(maxWidth.value, window, wide).dp
        val margin = ArtistLayout.margin(wide).dp
        val stacked = maxWidth < 340.dp && LocalDensity.current.fontScale >= 1.2f
        Column(Modifier.fillMaxWidth()) {
            // 6.0.0: the artist's circle that was touched grows into this portrait (ui/motion/CoverZoom).
            val zoom = com.ketvoice.app.ui.motion.rememberZoomTarget()
            Box(Modifier.fillMaxWidth().height(height).zoomTarget(zoom).clipToBounds().testTag("artist-portrait")) {
                ArtistHeroVisual(page, hero, curated != null, wide, collapse, zoom)
                // The floating back button stays readable over a bright photo.
                Box(Modifier.fillMaxWidth().height(110.dp).background(Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = .30f), Color.Transparent))))
                // The legibility gradient: the photo dissolves into the page colour under the name, with no line
                // where it ends, so the name reads in the page's own ink in light and dark alike.
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(if (wide) .80f else .62f).background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        .28f to theme.background.copy(alpha = .26f),
                        .56f to theme.background.copy(alpha = .72f),
                        .78f to theme.background.copy(alpha = .93f),
                        1f to theme.background)))
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = margin).padding(bottom = 16.dp)) {
                    StaggeredReveal(order = 0) { ArtistIdentity(page.artist.name, art.logo, startAligned = true) }
                    val meta = listOf(page.listeners, "YouTube Music").filter { it.isNotBlank() }.joinToString(" · ")
                    StaggeredReveal(order = 1) {
                        Text(meta, localize = false, color = theme.secondary, fontSize = 13.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    }
                    StaggeredReveal(order = 2, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (page.tracks.isNotEmpty()) com.ketvoice.app.ui.common.CollectionActionPair(
                                { Player.setShuffle(false); Player.playQueue(page.tracks) }, { Player.playShuffled(page.tracks) },
                                Modifier.weight(1f, fill = false).widthIn(max = 440.dp), playLabel = "Écouter",
                                playTag = "artist-play", shuffleTag = "artist-shuffle", stacked = stacked)
                            ArtistFavouriteButton(page.artist)
                        }
                    }
                }
            }
            // The artist's radio, under the buttons: it only exists when the artist has titles to start it from.
            if (page.tracks.isNotEmpty()) Box(Modifier.fillMaxWidth().padding(horizontal = margin).padding(top = 12.dp, bottom = 2.dp),
                contentAlignment = if (wide) Alignment.CenterStart else Alignment.Center) {
                com.ketvoice.app.ui.common.GlassCapsuleButton("Radio de l'artiste", Icons.Filled.Radio,
                    { Player.startArtistRadio(page.artist.name, null, page.tracks) }, Modifier.width(IntrinsicSize.Max), tag = "artist-radio", minHeight = 44.dp)
            }
        }
    }
}

/**
 * What fills the hero: the photo, cropped to the hero and drifting slowly (a Ken Burns movement) while
 * the page is still, rising a little as the page scrolls; or, when the listener has Canvas on album pages,
 * the Canvas of the artist's most popular title fading in over it once it is ready. With reduced motion,
 * in performance mode or without living artwork the photo holds still.
 */
@Composable
private fun BoxScope.ArtistHeroVisual(page: ArtistPage, hero: String?, curated: Boolean, wide: Boolean,
                                      collapse: () -> Float, zoom: ZoomTarget) {
    val theme = LocalTheme.current
    val context = LocalContext.current
    val moving = !Motion.reduced && !AppSettings.highPerformanceMode.value && AppSettings.livingArtwork.value
    val drift = if (moving) {
        val transition = rememberInfiniteTransition(label = "artist-ken-burns")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(26_000), RepeatMode.Reverse), label = "artist-ken-burns-drift")
    } else null
    // A banner shows the upper middle of a portrait: faces sit there.
    val focus = if (wide) BiasAlignment(0f, -.35f) else Alignment.TopCenter
    if (hero != null) {
        val request = remember(hero, Motion.reduced) {
            ImageRequest.Builder(context).data(hero).crossfade(Motion.duration(280)).build()
        }
        AsyncImage(request, page.artist.name, contentScale = ContentScale.Crop, alignment = focus,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                alpha = zoom.alpha()
                val d = drift?.value ?: 0f
                // A slight overscan hides any seam; the drift adds to it and pans across the photo.
                val scale = 1.02f + .07f * d
                scaleX = scale; scaleY = scale
                translationX = (d - .5f) * size.width * .025f
                translationY = (if (Motion.reduced) 0f else collapse().coerceIn(0f, 1f) * size.height * .07f) - d * size.height * .012f
            })
    } else {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            theme.accent.copy(alpha = if (theme.isDark) .30f else .20f), theme.background))))
        Box(Modifier.size(118.dp).clip(RoundedCornerShape(50)).background(theme.accent.copy(alpha = .16f))
            .align(BiasAlignment(0f, -.35f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Person, null, tint = theme.accent, modifier = Modifier.size(64.dp))
        }
    }
    // The Canvas of the artist's best-known title, as Apple Music animates an artist page. The owner's own
    // photo for the artist is kept as it is.
    val canvasOn = moving && AppSettings.albumHeroCanvas.value && !curated
    val lead = page.tracks.firstOrNull()?.takeIf { canvasOn && !MediaOverrides.canvasDisabled(it) }
    if (lead != null) {
        val paths by CanvasRepo.paths.collectAsState()
        LaunchedEffect(lead.id) {
            // After the page has settled: the photo comes first, the Canvas is an extra.
            kotlinx.coroutines.delay(900)
            runCatching { CanvasRepo.resolve(lead) }
        }
        val canvas = paths[lead.id]
        // Shown once the video knows its size (its first frames are there), so nothing but the photo shows before.
        var shown by remember(canvas) { mutableStateOf(false) }
        val reveal by animateFloatAsState(if (shown) 1f else 0f, tween(Motion.duration(900)), label = "artist-canvas-reveal")
        if (canvas != null) com.ketvoice.app.ui.player.ArtworkCanvas(lead, canvas, playing = true, forceCanvas = true,
            onMediaAspect = { shown = true },
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = reveal * zoom.alpha() }.testTag("artist-hero-canvas"))
    }
}

/** Keeps the artist as a favourite, or no longer: one glass circle beside Listen and Shuffle. */
@Composable
private fun ArtistFavouriteButton(artist: MusicArtist) {
    val theme = LocalTheme.current
    val favourite = ArtistPrefs.isFavourite(artist.name)
    val entry = remember(artist) { ArtistPrefs.Entry(artist.name, artist.id, artist.photo) }
    val haptic = com.ketvoice.app.ui.motion.rememberHaptics()
    com.ketvoice.app.ui.common.LiquidGlassButton({
        haptic(com.ketvoice.app.ui.motion.Haptics.Kind.Tick)
        ArtistPrefs.setFavourite(entry, !favourite)
    }, Modifier.size(56.dp).testTag("artist-follow"), plain = true) {
        if (favourite) Box(Modifier.matchParentSize().clip(CircleShape).background(theme.accent.copy(alpha = .20f)))
        Icon(KetIcons.Heart, if (favourite) "Retirer des favoris" else "Mettre en favori",
            tint = if (favourite) theme.accent else theme.onBackground, modifier = Modifier.size(24.dp))
    }
}
