package com.ketvoice.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import com.ketvoice.app.ui.motion.Motion
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Track

fun panoramicCanvasActive(canvas: String?): Boolean = AppSettings.panoramicCanvas.value &&
    !AppSettings.sonicCanvas.value && AppSettings.animatedArtwork.value && canvas != null

@Composable
internal fun PlayerSceneBackground(track: Track, canvas: String?, playing: Boolean, cover: String?,
                                   ambience: CoverAmbience, simple: Boolean = false, plain: Boolean = false) {
    if (AppSettings.sonicCanvas.value) {
        SonicCanvas(playing, ambience.accent ?: ambience.color)
    } else if (plain) {
        // Page paroles : ni Canvas video, ni pochette floutee derriere le texte.
        // Un visage flou a 90 dp derriere des lignes qui defilent est illisible
        // et distrait ; on garde seulement la couleur de l'album, assombrie.
        //
        // Pas de bandes noires en haut/bas : le degrade precedent montait a 82 %
        // puis 92 % de noir, ce qui creait une bordure sombre visible autour du
        // texte. On reste sur une teinte homogene, juste assez dense pour que le
        // blanc des paroles garde son contraste, avec un fondu lateral doux.
        Box(Modifier.fillMaxSize().background(ambience.color))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            Color.Black.copy(alpha = .40f),
            Color.Black.copy(alpha = .22f),
            Color.Black.copy(alpha = .46f)
        ))))
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = .30f)),
            radius = 1400f
        )))
    } else PanoramicOverAmbient(track, canvas.takeIf { panoramicCanvasActive(it) }, playing, ambience) { moving ->
        if (simple) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(ambience.color, Color.Black))))
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .48f)))
        } else AmbientArtwork(cover, ambience, moving)
    }
}

/**
 * The cover's colours are always the ground (7.4); a panoramic Canvas fades in over them once its first
 * frames are there and fades out when it goes (turned off, hidden for this title, another title), so the
 * background never flashes black while a Canvas loads and never jumps between the Canvas and the cover.
 * Under a Canvas fully shown, the colours below stop moving: nothing is drawn twice for nothing.
 */
@Composable
private fun PanoramicOverAmbient(track: Track, canvas: String?, playing: Boolean, ambience: CoverAmbience,
                                  ground: @Composable (moving: Boolean) -> Unit) {
    // The Canvas that is shown or fading out, and whether its picture is there.
    var held by remember { mutableStateOf<Pair<Track, String>?>(null) }
    if (canvas != null && held?.second != canvas) held = track to canvas
    var ready by remember(held?.second) { mutableStateOf(false) }
    val reveal by animateFloatAsState(if (canvas != null && ready) 1f else 0f,
        tween(Motion.duration(if (canvas != null) 600 else 360)), label = "panoramic-canvas",
        finishedListener = { if (it == 0f && canvas == null) held = null })
    ground(playing && reveal < 1f)
    val shown = held ?: return
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = reveal }) {
        ArtworkCanvas(shown.first, shown.second, playing, Modifier.fillMaxSize(), onMediaAspect = { ready = true })
        // The same veil as over the cover, warmed by its colour: the controls sit on the same ground either way.
        Box(Modifier.fillMaxSize().background(PlayerColors.scrim(ambience.color).copy(alpha = .70f)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .15f), Color.Transparent, Color.Black.copy(alpha = .50f)))))
    }
}
