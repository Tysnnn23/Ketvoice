package com.ketvoice.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
        Box(Modifier.fillMaxSize().background(ambience.color))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            Color.Black.copy(alpha = .82f), ambience.color.copy(alpha = .34f), Color.Black.copy(alpha = .92f)
        ))))
    } else if (panoramicCanvasActive(canvas)) {
        ArtworkCanvas(track, canvas, playing, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .72f)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .15f), Color.Transparent, Color.Black.copy(alpha = .55f)))))
    } else if (simple) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(ambience.color, Color.Black))))
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .48f)))
    } else AmbientArtwork(cover, ambience)
}
