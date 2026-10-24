package com.ketvoice.app.glass

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight

/**
 * KETVOICE — LIQUID GLASS via le SDK « Backdrop » de Kyant0
 * (io.github.kyant0:backdrop, Apache-2.0).
 *
 * Pipeline :
 *   1. `rememberGlassBackdrop` capture le fond de l'app dans une couche.
 *   2. `layerBackdrop(backdrop)` sur le contenu : le fond est enregistré à
 *      chaque frame.
 *   3. `GlassPanel` utilise `drawBackdrop` : flou + vibrancy + lentille de
 *      réfraction (AGSL, API 33+) + aberration chromatique + reflet de bord.
 *
 * API 31-32 : flou + teinte (pas de RuntimeShader), rendu propre garanti.
 */
val LocalBackdropLayer = staticCompositionLocalOf<Backdrop?> { null }

/** Capture content without letting a descendant sample its own render layer.
 * Chrome stays a sibling and keeps the real backdrop, so Liquid Glass remains
 * enabled outside the capture. This also protects new inline glass controls. */
@Composable
fun BackdropCapture(backdrop: LayerBackdrop, modifier: Modifier = Modifier,
                    content: @Composable BoxScope.() -> Unit) {
    Box(modifier.layerBackdrop(backdrop)) {
        CompositionLocalProvider(LocalBackdropLayer provides null) { content() }
    }
}

/** Backdrop racine : fond opaque + contenu de l'app. */
@Composable
fun rememberGlassBackdrop(background: Color) = rememberLayerBackdrop {
    drawRect(background)
    drawContent()
}

data class GlassParams(
    val cornerRadiusPx: Float,
    val bevelPx: Float,
    val refractionPx: Float,
    val falloff: Float = 2.0f,
    val dispersion: Float = 0.10f,
    val tintStrength: Float = 0.16f,
    val highlight: Float = 0.50f,
    val blurPx: Float = 24f,
    val grain: Float = 0.018f,
    val dark: Boolean = false,
    val materialRegular: Boolean = true
) {
    companion object {
        fun pill(blur: Float = 24f, dark: Boolean = false) =
            GlassParams(cornerRadiusPx = 9999f, bevelPx = 24f, refractionPx = 18f, blurPx = blur, dark = dark)

        fun circle(blur: Float = 24f, dark: Boolean = false) =
            GlassParams(cornerRadiusPx = 9999f, bevelPx = 18f, refractionPx = 18f, blurPx = blur, dark = dark)

        fun sheet(radiusPx: Float, dark: Boolean = false) =
            GlassParams(cornerRadiusPx = radiusPx, bevelPx = 30f, refractionPx = 24f, blurPx = 28f, tintStrength = 0.16f, dark = dark)
    }
}

/**
 * Panneau en verre liquide : réfracte ce qui est derrière lui.
 * `shape` = forme visuelle ET géométrie de la lentille.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape,
    params: GlassParams,
    enabled: Boolean = true,
    // Un panneau posé DANS le contenu capturé par layerBackdrop ne doit jamais
    // lire ce même backdrop (drawBackdrop) : boucle de rétroaction → stack
    // overflow natif du RenderThread (crash page artiste / réglages).
    captured: Boolean = false,
    content: @Composable () -> Unit
) {
    val backdrop = LocalBackdropLayer.current
    val surface = if (params.dark) {
        Color.Black.copy(alpha = 0.22f)
    } else {
        Color.White.copy(alpha = 0.25f)
    }
    val rim = remember(params.dark) { Brush.linearGradient(listOf(
        Color.White.copy(alpha = if (params.dark) .32f else .66f), Color.White.copy(alpha = .04f),
        Color.White.copy(alpha = .14f))) }
    val edge = if (enabled) Modifier.border(.7.dp, rim, shape) else Modifier

    if (backdrop != null && enabled && !captured && !AppSettings.reduceDynamicBlur.value &&
        !AppSettings.highPerformanceMode.value
    ) {
        Box(
            modifier = modifier.then(edge).drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    if (params.blurPx > 0f) blur(params.blurPx)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        lens(
                            refractionHeight = params.refractionPx.coerceIn(0f, 80f),
                            refractionAmount = (params.refractionPx * 1.55f).coerceIn(0f, 120f),
                            depthEffect = true,
                            chromaticAberration = true
                        )
                    }
                },
                highlight = { Highlight.Default },
                onDrawSurface = { drawRect(surface) }
            )
        ) {
            Box(Modifier.background(Color.Transparent, shape)) { content() }
        }
    } else {
        // Verre désactivé ou backdrop absent : panneau translucide statique.
        Box(modifier = modifier.then(edge).background(surface, shape)) {
            content()
        }
    }
}
