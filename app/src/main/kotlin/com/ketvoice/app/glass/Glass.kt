package com.ketvoice.app.glass

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.exposureAdjustment
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

val LocalBackdropLayer = staticCompositionLocalOf<Backdrop?> { null }

/**
 * Marque la couche de contenu échantillonnable par le verre.
 *
 * IMPORTANT : tout `GlassPanel` placé DANS ce contenu reçoit `LocalBackdropLayer == null`
 * (voir le CompositionLocalProvider plus bas) et bascule automatiquement sur le verre
 * statique. C'est volontaire : échantillonner la couche dans laquelle on dessine provoque
 * une boucle `drawBackdrop` -> RenderThread -> crash.
 */
@Composable
fun BackdropCapture(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier.layerBackdrop(backdrop)) {
        CompositionLocalProvider(LocalBackdropLayer provides null) { content() }
    }
}

@Composable
fun rememberGlassBackdrop(background: Color) = rememberLayerBackdrop {
    drawRect(background)
    drawContent()
}

/**
 * Paramètres du matériau « liquid glass ».
 *
 * v2 : tous les champs sont désormais RÉELLEMENT consommés par [GlassPanel].
 * La v1 déclarait bevelPx / falloff / dispersion / tintStrength / grain /
 * materialRegular sans jamais les lire — d'où un rendu plat et sans relief.
 */
data class GlassParams(
    val cornerRadiusPx: Float,
    /** Rayon de flou du fond, en px. 0 = pas de flou. */
    val blurPx: Float = 24f,
    /** Hauteur de réfraction de la lentille (bord du verre), en px. */
    val refractionPx: Float = 18f,
    /** Opacité de la teinte de surface (le "lait" du verre). */
    val tintAlpha: Float = 0.14f,
    /** Saturation du fond à travers le verre. > 1 = couleurs plus riches. */
    val saturation: Float = 1.22f,
    /** Luminosité additionnelle du fond (colorControls). */
    val brightness: Float = 1.0f,
    /** Contraste du fond. */
    val contrast: Float = 1.0f,
    /** Exposition du fond (HDR-ish). */
    val exposure: Float = 0.04f,
    /** Opacité du liseré lumineux. */
    val highlightAlpha: Float = 0.55f,
    /** Intensité du reflet interne (HighlightStyle.Default). */
    val highlightIntensity: Float = 0.42f,
    /** Angle du reflet en degrés. */
    val highlightAngle: Float = 45f,
    /** Atténuation du reflet vers les coins. */
    val highlightFalloff: Float = 0.65f,
    /** Ombre portée douce sous le panneau (profondeur). */
    val shadow: Boolean = true,
    /** Ombre interne : donne l'épaisseur du bloc de verre. */
    val innerShadow: Boolean = true,
    val dark: Boolean = false
) {
    companion object {
        fun pill(blur: Float = 24f, dark: Boolean = false) = GlassParams(
            cornerRadiusPx = 9999f,
            blurPx = blur,
            refractionPx = 16f,
            tintAlpha = if (dark) 0.17f else 0.12f,
            saturation = if (dark) 1.10f else 1.26f,
            exposure = if (dark) 0.02f else 0.06f,
            highlightAlpha = if (dark) 0.32f else 0.58f,
            highlightIntensity = if (dark) 0.30f else 0.46f,
            dark = dark
        )

        fun circle(blur: Float = 24f, dark: Boolean = false) = GlassParams(
            cornerRadiusPx = 9999f,
            blurPx = blur,
            refractionPx = 20f,
            tintAlpha = if (dark) 0.19f else 0.13f,
            saturation = if (dark) 1.12f else 1.30f,
            exposure = if (dark) 0.02f else 0.06f,
            highlightAlpha = if (dark) 0.34f else 0.64f,
            highlightIntensity = if (dark) 0.32f else 0.50f,
            dark = dark
        )

        fun sheet(radiusPx: Float, dark: Boolean = false) = GlassParams(
            cornerRadiusPx = radiusPx,
            blurPx = 30f,
            refractionPx = 24f,
            tintAlpha = if (dark) 0.24f else 0.17f,
            saturation = if (dark) 1.06f else 1.20f,
            exposure = if (dark) 0.01f else 0.05f,
            highlightAlpha = if (dark) 0.28f else 0.52f,
            highlightIntensity = if (dark) 0.26f else 0.42f,
            dark = dark
        )
    }
}

/** Teinte de surface : dégradé vertical plutôt qu'un aplat, pour éviter l'effet "plastique". */
private fun surfaceBrush(p: GlassParams): Brush {
    val top = if (p.dark) Color.Black.copy(alpha = p.tintAlpha + 0.08f)
    else Color.White.copy(alpha = p.tintAlpha + 0.10f)
    val mid = if (p.dark) Color.Black.copy(alpha = p.tintAlpha * 0.55f)
    else Color.White.copy(alpha = p.tintAlpha * 0.45f)
    val bottom = if (p.dark) Color.Black.copy(alpha = p.tintAlpha + 0.06f)
    else Color.White.copy(alpha = p.tintAlpha + 0.04f)
    return Brush.verticalGradient(listOf(top, mid, bottom))
}

/** Liseré lumineux : le reflet qui "attrape" la lumière en haut à gauche. */
private fun rimBrush(p: GlassParams): Brush {
    val strong = Color.White.copy(alpha = if (p.dark) p.highlightAlpha * 0.55f else p.highlightAlpha)
    val weak = Color.White.copy(alpha = 0.05f)
    val medium = Color.White.copy(alpha = if (p.dark) p.highlightAlpha * 0.25f else p.highlightAlpha * 0.30f)
    return Brush.linearGradient(listOf(strong, weak, medium))
}

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape,
    params: GlassParams,
    enabled: Boolean = true,
    captured: Boolean = false,
    content: @Composable () -> Unit
) {
    val backdrop = LocalBackdropLayer.current
    val dynamic = backdrop != null && enabled && !captured &&
        !AppSettings.reduceDynamicBlur.value &&
        !AppSettings.highPerformanceMode.value

    val surface = remember(params) { surfaceBrush(params) }
    val rim = remember(params) { rimBrush(params) }
    val edge = if (enabled) Modifier.border(.75.dp, rim, shape) else Modifier

    if (dynamic && backdrop != null) {
        Box(
            modifier = modifier.then(edge).drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    if (params.blurPx > 0f) blur(params.blurPx)
                    if (params.saturation != 1f || params.brightness != 1f || params.contrast != 1f) {
                        colorControls(params.saturation, params.brightness, params.contrast)
                    }
                    if (params.exposure != 0f) exposureAdjustment(params.exposure)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && params.refractionPx > 0f) {
                        lens(
                            refractionHeight = params.refractionPx.coerceIn(0f, 80f),
                            refractionAmount = (params.refractionPx * 1.55f).coerceIn(0f, 120f),
                            depthEffect = true,
                            chromaticAberration = true
                        )
                    }
                },
                highlight = {
                    Highlight(
                        0.6.dp,
                        14.dp,
                        params.highlightAlpha,
                        HighlightStyle.Default(
                            params.highlightIntensity,
                            params.highlightAngle,
                            params.highlightFalloff
                        )
                    )
                },
                shadow = { if (params.shadow) Shadow.Default else null },
                innerShadow = { if (params.innerShadow) InnerShadow.Default else null },
                onDrawSurface = { drawRect(surface) }
            )
        ) {
            Box(Modifier.background(Color.Transparent, shape)) { content() }
        }
    } else {
        // Verre STATIQUE : pas d'échantillonnage, donc pas de crash — mais on garde
        // le dégradé, le liseré et l'épaisseur pour rester cohérent visuellement.
        Box(modifier = modifier.then(edge).background(surface, shape)) {
            content()
        }
    }
}
