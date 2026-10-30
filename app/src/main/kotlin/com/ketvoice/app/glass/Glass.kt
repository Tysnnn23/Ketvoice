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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.theme.LocalTheme
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
 * v3 : le matériau AGSL fait tout le travail optique (réfraction, dispersion,
 * liseré de Fresnel, sheen, dithering). Les presets ne règlent que l'équilibre
 * transmission / lisibilité, avec des teintes volontairement basses : un verre
 * trop opaque devient du plastique, trop transparent disparaît.
 */
data class GlassParams(
    val cornerRadiusPx: Float,
    /** Rayon de flou du fond, en px. 0 = pas de flou. */
    val blurPx: Float = 28f,
    /** Hauteur de réfraction de la lentille (bord du verre), en px. */
    val refractionPx: Float = 18f,
    /** Opacité de la teinte de surface (le "lait" du verre). */
    val tintAlpha: Float = 0.10f,
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
        /**
         * Carte de contenu : grand rayon, réfraction discrète. Sert aux groupes
         * de réglages et aux boutons de l'application, qui étaient jusqu'ici
         * des aplats opaques alors que le reste de l'interface est en verre.
         */
        fun card(radiusPx: Float, dark: Boolean = false) = GlassParams(
            cornerRadiusPx = radiusPx,
            blurPx = 30f,
            refractionPx = 16f,
            tintAlpha = if (dark) 0.11f else 0.075f,
            saturation = if (dark) 1.10f else 1.22f,
            exposure = if (dark) 0.015f else 0.04f,
            highlightAlpha = if (dark) 0.28f else 0.44f,
            highlightIntensity = if (dark) 0.24f else 0.32f,
            dark = dark
        )

        fun pill(blur: Float = 26f, dark: Boolean = false) = GlassParams(
            cornerRadiusPx = 9999f,
            blurPx = blur,
            refractionPx = 24f,
            // Équilibre du matériau AGSL : assez de "lait" pour que le verre
            // existe visuellement, assez peu pour que le fond reste lisible à
            // travers. Sous ~0.06 le verre disparaît ; au-dessus de ~0.16 il
            // redevient du plastique blanc opaque.
            tintAlpha = if (dark) 0.105f else 0.08f,
            saturation = if (dark) 1.18f else 1.32f,
            brightness = 1.0f,
            contrast = 1.0f,
            exposure = if (dark) 0.02f else 0.035f,
            highlightAlpha = if (dark) 0.32f else 0.48f,
            highlightIntensity = if (dark) 0.26f else 0.36f,
            dark = dark
        )

        fun circle(blur: Float = 26f, dark: Boolean = false) = GlassParams(
            cornerRadiusPx = 9999f,
            blurPx = blur,
            refractionPx = 28f,
            tintAlpha = if (dark) 0.11f else 0.085f,
            saturation = if (dark) 1.20f else 1.36f,
            exposure = if (dark) 0.02f else 0.035f,
            highlightAlpha = if (dark) 0.34f else 0.52f,
            highlightIntensity = if (dark) 0.28f else 0.38f,
            dark = dark
        )

        fun sheet(radiusPx: Float, dark: Boolean = false) = GlassParams(
            cornerRadiusPx = radiusPx,
            blurPx = 34f,
            refractionPx = 26f,
            // Le sheet doit laisser deviner le contenu : fond gris trop opaque = profondeur perdue.
            tintAlpha = if (dark) 0.13f else 0.095f,
            saturation = if (dark) 1.08f else 1.20f,
            exposure = if (dark) 0.015f else 0.05f,
            highlightAlpha = if (dark) 0.32f else 0.54f,
            highlightIntensity = if (dark) 0.28f else 0.42f,
            dark = dark
        )
    }
}

/**
 * Surface du verre STATIQUE (verre désactivé, ou panneaux capturés dans leur
 * propre couche). Refonte complète : fini l'aplat plastique —
 *
 *  - base translucide alignée sur le thème (le contenu derrière teinte à travers) ;
 *  - dégradé diagonal subtil, lumière haut-gauche ;
 *  - liseré capillaire lumineux sur le bord haut-gauche, ombre capillaire bas-droite ;
 *  - voile spéculaire concentré sur le premier tiers.
 *
 * Le résultat lit comme un verre dépoli posé sur la page, pas comme un rectangle peint.
 */
@Composable
private fun rememberStaticMaterial(p: GlassParams): Pair<Brush, Brush> {
    val th = LocalTheme.current
    return remember(p, th.isDark) {
        val base = if (p.dark) Color(0xFF232329) else Color.White
        // Transmission statique : le fond transparaît à ~12-18 %.
        val body = base.copy(alpha = if (p.dark) 0.84f else 0.88f)
        val lift = base.copy(alpha = if (p.dark) 0.90f else 0.94f)
        val surface = Brush.linearGradient(listOf(lift, body, base.copy(alpha = if (p.dark) 0.80f else 0.84f), body))
        // Liseré capillaire : lumineux haut-gauche (entrée de lumière), sombre bas-droite.
        val lightHair = Color.White.copy(alpha = if (p.dark) 0.16f else 0.75f)
        val darkHair = if (p.dark) Color.Black.copy(alpha = 0.42f) else Color.Black.copy(alpha = 0.10f)
        val hairline = Brush.linearGradient(listOf(lightHair, Color.White.copy(alpha = lightHair.alpha * .25f), darkHair))
        surface to hairline
    }
}

/** Voile spéculaire du verre statique : réchauffe le tiers supérieur, ne le lave jamais. */
private fun staticSheen(p: GlassParams): Brush {
    val strength = (if (p.dark) 0.05f else 0.09f) * (0.5f + p.highlightIntensity * 0.7f)
    return Brush.verticalGradient(
        0.00f to Color.White.copy(alpha = strength.coerceIn(0f, 0.12f)),
        0.12f to Color.White.copy(alpha = (strength * 0.30f).coerceIn(0f, 0.05f)),
        0.30f to Color.Transparent
    )
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
    // Vrai verre AGSL (API 33+) : le matériau est calculé par un RuntimeShader
    // (réfraction + dispersion + teinte + liseré). Les pinceaux de surface et le
    // Highlight de la lib ne servent plus que de repli pré-33.
    val agsl = dynamic && GlassShader.isSupported()

    val (surface, hairline) = rememberStaticMaterial(params)
    val sheen = remember(params) { staticSheen(params) }
    val darkEdge = Color.Black.copy(alpha = if (params.dark) 0.34f else 0.14f)
    // En AGSL, le shader dessine le liseré et le bord sombre : plus de bordure
    // Compose par-dessus (sinon double contour artificiel).
    val edge = if (enabled && !agsl) {
        Modifier
            .border(1.dp, hairline, shape)
            .border(.5.dp, darkEdge, shape)
    } else Modifier

    if (dynamic && backdrop != null) {
        Box(
            modifier = modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    if (params.blurPx > 0f) blur(params.blurPx)
                    if (agsl) {
                        val bounds = size
                        val realRadii = glassRoundedShapeRadii(
                            shape, bounds.width, bounds.height, this, layoutDirection
                        ) ?: glassCornerRadiiOf(params.cornerRadiusPx)
                        liquidGlass(params, realRadii)
                    } else {
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
                    }
                },
                highlight = {
                    if (agsl) null else Highlight(
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
                innerShadow = { if (params.innerShadow && !agsl) InnerShadow.Default else null },
                onDrawSurface = {
                    if (!agsl) {
                        drawRect(surface)
                        drawRect(sheen)
                    }
                }
            )
        ) {
            Box(Modifier.background(Color.Transparent, shape)) { content() }
        }
    } else {
        // Verre STATIQUE (désactivé ou capturé) : matériau dépoli premium —
        // base translucide, liseré capillaire, voile spéculaire. Pas
        // d'échantillonnage, donc aucun risque de boucle de rendu.
        Box(modifier = modifier.then(edge).background(surface, shape)) {
            Box(Modifier.background(sheen, shape)) { content() }
        }
    }
}
