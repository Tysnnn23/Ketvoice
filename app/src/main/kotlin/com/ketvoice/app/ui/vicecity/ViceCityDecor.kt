package com.ketvoice.app.ui.vicecity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.R
import com.ketvoice.app.theme.SeasonPulse
import com.ketvoice.app.theme.ViceCityTheme

/** The palms' ink: a shade darker than the night, so they stand out only against the sunset glow. */
private val PalmInk = Color(0xFF07030F)

/**
 * Faint palm silhouettes in the top corners of Home (Vice City theme, 6.1), cut from the fronds of the owner's
 * welcome picture. They frame the glow behind the greeting and fade out with the large title ([fade], 0..1,
 * read in the draw phase only). Decorative: never focusable, never in the way of a touch.
 */
@Composable
fun ViceCityPalms(modifier: Modifier = Modifier, fade: () -> Float = { 0f }) {
    Box(modifier.fillMaxWidth().height(250.dp).clearAndSetSemantics { testTag = "vicecity-palms" }
        .graphicsLayer { alpha = 1f - fade().coerceIn(0f, 1f) }) {
        ViceCitySun(Modifier.matchParentSize())
        val ink = ColorFilter.tint(PalmInk.copy(alpha = .78f))
        Image(painterResource(R.drawable.vicecity_palm_left), null, Modifier.align(Alignment.TopStart)
            .offset(x = (-18).dp, y = (-46).dp).width(150.dp), contentScale = ContentScale.FillWidth, colorFilter = ink)
        Image(painterResource(R.drawable.vicecity_palm_right), null, Modifier.align(Alignment.TopEnd)
            .offset(x = 8.dp, y = (-30).dp).width(96.dp), contentScale = ContentScale.FillWidth, colorFilter = ink)
    }
}

/**
 * The setting sun of the 80s poster behind the greeting (6.2.1): a disc fading from orange to magenta whose lower half
 * is cut by horizontal slats that widen towards the horizon. Faint (it never lowers the greeting's contrast), it
 * breathes with the theme's slow pulse (read in the draw phase only) and holds still under reduced motion.
 */
@Composable
fun ViceCitySun(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val radius = size.width * .17f
        val centre = Offset(size.width * .70f, radius * .72f)
        val breath = .5f + .5f * kotlin.math.cos(SeasonPulse.phase.floatValue * 2f * Math.PI.toFloat())
        val sunset = Brush.verticalGradient(0f to ViceCityTheme.Colors.orange, .55f to ViceCityTheme.Colors.accent,
            1f to ViceCityTheme.Colors.magenta, startY = centre.y - radius, endY = centre.y + radius)
        val alpha = .20f + .05f * breath
        // The upper half whole, then the lower half in slats: thin near the middle, wide towards the bottom.
        clipRect(top = centre.y - radius, bottom = centre.y) { drawCircle(sunset, radius, centre, alpha) }
        var y = centre.y
        var gap = radius * .05f
        var band = radius * .16f
        while (y < centre.y + radius) {
            val bottom = (y + band).coerceAtMost(centre.y + radius)
            clipRect(top = y + gap, bottom = bottom) { drawCircle(sunset, radius, centre, alpha) }
            y = bottom + gap * .2f
            gap += radius * .035f
            band = (band - radius * .018f).coerceAtLeast(radius * .05f)
        }
    }
}

/**
 * A small picture of the theme for Settings: the night, the sunset glow, the palms, the tab bar's neon
 * selection and a sunset button, drawn with the theme's own pieces.
 */
@Composable
fun ViceCityPreview(modifier: Modifier = Modifier, height: Dp = 92.dp) {
    val shape = RoundedCornerShape(18.dp)
    Box(modifier.fillMaxWidth().height(height).clip(shape).background(ViceCityTheme.Colors.night)
        .drawBehind { with(ViceCityTheme) { topGlow(.25f) } }.clearAndSetSemantics { testTag = "vicecity-preview" }) {
        val ink = ColorFilter.tint(PalmInk.copy(alpha = .85f))
        Image(painterResource(R.drawable.vicecity_palm_left), null, Modifier.align(Alignment.TopStart)
            .offset(x = (-10).dp, y = (-24).dp).width(78.dp), contentScale = ContentScale.FillWidth, colorFilter = ink)
        Image(painterResource(R.drawable.vicecity_palm_right), null, Modifier.align(Alignment.TopEnd)
            .offset(x = 4.dp, y = (-16).dp).width(50.dp), contentScale = ContentScale.FillWidth, colorFilter = ink)
        PreviewBar(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
    }
}

@Composable
private fun BoxScope.PreviewBar(modifier: Modifier) {
    val pill = RoundedCornerShape(50)
    Box(modifier.size(width = 150.dp, height = 26.dp).clip(pill).background(Color.White.copy(alpha = .10f))) {
        Box(Modifier.padding(3.dp).fillMaxHeight().width(48.dp)
            .then(with(ViceCityTheme) { Modifier.viceCityNeon(pill, true) })
            .clip(pill).background(Color.White.copy(alpha = .12f)))
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).size(width = 52.dp, height = 14.dp).clip(pill)
            .background(ViceCityTheme.sunset))
    }
}

/** The edition's K, cropped from the owner's logo, in a rounded square. */
@Composable
fun ViceCityLogo(size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.vicecity_logo), null, modifier.size(size).clip(RoundedCornerShape(size * .23f)),
        contentScale = ContentScale.Crop)
}
