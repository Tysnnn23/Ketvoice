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
            blurPx = 26f,
            refractionPx = 14f,
            tintAlpha = if (dark) 0.10f else 0.08f,
            saturation = if (dark) 1.14f else 1.26f,
            exposure = if (dark) 0.015f else 0.04f,
            highlightAlpha = if (dark) 0.30f else 0.46f,
            highlightIntensity = if (dark) 0.26f else 0.34f,
            dark = dark
        )

        fun pill(blur: Float = 24f, dark: Boolean = false) = GlassParams(
            cornerRadiusPx = 9999f,
            blurPx = blur,
            refractionPx = 22f,
            // Équilibre du matériau AGSL : assez de "lait" pour que le verre
            // existe visuellement, assez peu pour que le fond reste lisible à
            // travers. Sous ~0.06 le verre disparaît ; au-dessus de ~0.16 il
            // redevient du plastique blanc opaque.
            tintAlpha = if (dark) 0.105f else 0.085f,
            saturation = if (dark) 1.22f else 1.38f,
            brightness = 1.0f,
            contrast = 1.0f,
            exposure = if (dark) 0.02f else 0.035f,
            highlightAlpha = if (dark) 0.34f else 0.50f,
            highlightIntensity = if (dark) 0.28f else 0.38f,
            dark = dark
        )

        fun circle(blur: Float = 24f, dark: Boolean = false) = GlassParams(
            cornerRadiusPx = 9999f,
            blurPx = blur,
            refractionPx = 26f,
            tintAlpha = if (dark) 0.11f else 0.09f,
            saturation = if (dark) 1.24f else 1.42f,
            exposure = if (dark) 0.02f else 0.035f,
            highlightAlpha = if (dark) 0.36f else 0.54f,
            highlightIntensity = if (dark) 0.30f else 0.40f,
            dark = dark
        )

        fun sheet(radiusPx: Float, dark: Boolean = false) = GlassParams(
            cornerRadiusPx = radiusPx,
            blurPx = 30f,
            refractionPx = 24f,
            // Le sheet doit laisser deviner le contenu : fond gris trop opaque = profondeur perdue.
            tintAlpha = if (dark) 0.13f else 0.10f,
            saturation = if (dark) 1.10f else 1.22f,
            exposure = if (dark) 0.015f else 0.05f,
            highlightAlpha = if (dark) 0.34f else 0.56f,
            highlightIntensity = if (dark) 0.30f else 0.44f,
            dark = dark
        )
    }
}

/** Teinte de surface : dégradé diagonal à quatre arrêts plutôt qu'un aplat, pour éviter l'effet "plastique". */
private fun surfaceBrush(p: GlassParams): Brush {
    // Choix de design propre à Ketvoice : la lumière entre par le haut-gauche et
    // ressort faiblement en bas, d'où une diagonale. En mode sombre, la base est
    // un gris foncé et non du noir pur : le verre sombre d'iOS 27 est décrit comme
    // plus clair que celui d'iOS 26.
    val base = if (p.dark) Color(0xFF2A2A30) else Color.White
    // Les deltas étaient auparavant de +0.15 / +0.10 : ils écrasaient totalement
    // `tintAlpha` et transformaient le verre en plastique blanc opaque, quel que
    // soit le preset. Ils doivent rester une modulation de la teinte, pas un
    // ajout d'opacité. Plafond volontairement bas (0.35) : au-delà, on perd la
    // transmission du fond et la réfraction ne se lit plus.
    val hot = base.copy(alpha = (p.tintAlpha + if (p.dark) 0.045f else 0.055f).coerceAtMost(0.35f))
    val upper = base.copy(alpha = (p.tintAlpha * 0.60f).coerceAtMost(0.30f))
    val lower = base.copy(alpha = (p.tintAlpha * 0.25f).coerceAtMost(0.22f))
    val lift = base.copy(alpha = (p.tintAlpha + if (p.dark) 0.020f else 0.012f).coerceAtMost(0.30f))
    return Brush.linearGradient(listOf(hot, upper, lower, lift))
}

/** Liseré lumineux : le reflet qui "attrape" la lumière en haut à gauche. */
private fun rimBrush(p: GlassParams): Brush {
    // Le reflet d'un vrai verre ne s'éteint pas d'un bord à l'autre : il culmine
    // en haut-gauche, retombe au milieu, puis se rallume en bas-droite là où la
    // lumière ressort. Quatre arrêts, pas trois.
    val peak = Color.White.copy(
        alpha = (if (p.dark) p.highlightAlpha * 0.85f else p.highlightAlpha).coerceIn(0f, 1f)
    )
    val shoulder = Color.White.copy(alpha = peak.alpha * 0.42f)
    val trough = Color.White.copy(alpha = peak.alpha * 0.07f)
    val wrap = Color.White.copy(alpha = peak.alpha * 0.30f)
    return Brush.linearGradient(listOf(peak, shoulder, trough, wrap))
}

/**
 * Voile spéculaire : la pellicule "mouillée" qui donne au verre son épaisseur.
 * Concentré sur le tiers supérieur, il s'éteint avant le milieu pour ne jamais
 * laver le contenu posé dessus.
 */
private fun sheenBrush(p: GlassParams): Brush {
    // Le `Highlight` de la lib dessine déjà le reflet spéculaire. Empiler ici un
    // second voile blanc revient à doubler l'intensité -> voile laiteux opaque.
    // On le divise par ~4 pour qu'il ne fasse que réchauffer le haut du panneau.
    val strength = (if (p.dark) 0.030f else 0.045f) * (0.5f + p.highlightIntensity * 0.7f)
    return Brush.verticalGradient(
        0.00f to Color.White.copy(alpha = strength.coerceIn(0f, 0.10f)),
        0.10f to Color.White.copy(alpha = (strength * 0.30f).coerceIn(0f, 0.05f)),
        0.26f to Color.Transparent
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

    val surface = remember(params) { surfaceBrush(params) }
    val rim = remember(params) { rimBrush(params) }
    val sheen = remember(params) { sheenBrush(params) }
    val darkEdge = Color.Black.copy(alpha = if (params.dark) 0.30f else 0.14f)
    // En AGSL, le shader dessine le liseré et le bord sombre : plus de bordure
    // Compose par-dessus (sinon double contour artificiel).
    val edge = if (enabled && !agsl) {
        Modifier.border(1.25.dp, darkEdge, shape).border(.75.dp, rim, shape)
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
        // Verre STATIQUE : pas d'échantillonnage, donc pas de crash — mais on garde
        // le dégradé, le liseré, le voile spéculaire et l'épaisseur pour rester cohérent.
        Box(modifier = modifier.then(edge).background(surface, shape)) {
            Box(Modifier.background(sheen, shape)) { content() }
        }
    }
}
