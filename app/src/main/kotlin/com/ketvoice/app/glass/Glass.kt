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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
 * Les contrôles utilisent une lentille AGSL inspirée de Prismal ; le rôle Panel
 * reste mat. La réfraction de contrôle est définie en dp, puis bornée à sa taille.
 */
enum class GlassRole { Control, Panel, Sheet, Inline }

data class GlassParams(
    val cornerRadiusPx: Float,
    /** Rayon de flou du fond, en px. 0 = pas de flou. */
    val blurPx: Float = 28f,
    /** Hauteur de réfraction de la lentille (bord du verre), en px. */
    val refractionPx: Float = 18f,
    /** Prismal-style edge band and displacement, density-independent. */
    val refractionHeightDp: Float = 3.5f,
    val refractionAmountDp: Float = 6f,
    val role: GlassRole = GlassRole.Control,
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
    /** Hauteur d'ombre portée (dp) : plus grand = panneau plus « décollé ». */
    val elevationDp: Float = 8f,
    /** Opacité de l'ombre portée. Plus élevée en sombre pour détacher le verre. */
    val shadowAlpha: Float = 0.18f,
    /** Ombre interne : donne l'épaisseur du bloc de verre. */
    val innerShadow: Boolean = true,
    val dark: Boolean = false
) {
    companion object {
        /**
         * Carte de contenu mate. Le verre n'est plus un traitement de toute la page.
         */
        fun card(radiusPx: Float, dark: Boolean = false) = GlassParams(
            cornerRadiusPx = radiusPx,
            role = GlassRole.Panel,
            blurPx = 26f,
            refractionPx = 12f,
            tintAlpha = if (dark) 0.16f else 0.12f,
            saturation = if (dark) 1.06f else 1.12f,
            exposure = if (dark) 0.015f else 0.04f,
            highlightAlpha = if (dark) 0.28f else 0.44f,
            highlightIntensity = if (dark) 0.24f else 0.32f,
            // Matte content card: sits almost flat on the page (e0..e1).
            elevationDp = 1f,
            shadowAlpha = if (dark) 0.22f else 0.10f,
            dark = dark
        )

        fun pill(blur: Float = 8f, dark: Boolean = false) = GlassParams(
            cornerRadiusPx = 9999f,
            blurPx = blur,
            refractionPx = 18f,
            // Équilibre du matériau AGSL : assez de "lait" pour que le verre
            // existe visuellement, assez peu pour que le fond reste lisible à
            // travers. Sous ~0.06 le verre disparaît ; au-dessus de ~0.16 il
            // redevient du plastique blanc opaque.
            tintAlpha = if (dark) 0.065f else 0.045f,
            saturation = 1.08f,
            brightness = 1.0f,
            contrast = 1.0f,
            exposure = if (dark) 0.01f else 0.02f,
            highlightAlpha = if (dark) 0.26f else 0.30f,
            highlightIntensity = 0.24f,
            // Floating control: clearly lifted above content (e3).
            elevationDp = 8f,
            shadowAlpha = if (dark) 0.30f else 0.16f,
            dark = dark
        )

        fun circle(blur: Float = 6f, dark: Boolean = false) = GlassParams(
            cornerRadiusPx = 9999f,
            blurPx = blur,
            refractionPx = 14f,
            tintAlpha = if (dark) 0.06f else 0.04f,
            saturation = 1.06f,
            exposure = if (dark) 0.02f else 0.035f,
            highlightAlpha = if (dark) 0.28f else 0.34f,
            highlightIntensity = 0.26f,
            // Round button: small lift (e2).
            elevationDp = 4f,
            shadowAlpha = if (dark) 0.26f else 0.14f,
            dark = dark
        )

        /** Navigation is optical glass, not heavily frosted content material. */
        fun navigation(dark: Boolean = false) = pill(blur = 7f, dark = dark).copy(
            tintAlpha = if (dark) .055f else .035f,
            saturation = 1.08f, highlightAlpha = if (dark) .30f else .36f,
            refractionHeightDp = 4f, refractionAmountDp = 7f
        )

        /** The moving selection shares one material in expanded and compact navigation. */
        fun selection(dark: Boolean = false) = navigation(dark).copy(
            blurPx = 3f, tintAlpha = if (dark) .06f else .035f,
            highlightAlpha = if (dark) .36f else .42f
        )

        fun sheet(radiusPx: Float, dark: Boolean = false) = GlassParams(
            cornerRadiusPx = radiusPx,
            role = GlassRole.Sheet,
            blurPx = 34f,
            refractionPx = 18f,
            // Le sheet doit laisser deviner le contenu : fond gris trop opaque = profondeur perdue.
            tintAlpha = if (dark) 0.52f else 0.22f,
            saturation = if (dark) 1.04f else 1.10f,
            exposure = if (dark) 0.015f else 0.05f,
            highlightAlpha = if (dark) 0.32f else 0.54f,
            highlightIntensity = if (dark) 0.28f else 0.42f,
            // Bottom sheet: highest lift so it reads as an overlay above the page (e4).
            elevationDp = 16f,
            shadowAlpha = if (dark) 0.40f else 0.20f,
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
private fun rememberStaticMaterial(p: GlassParams, opaque: Boolean = false): Pair<Brush, Brush> {
    val th = LocalTheme.current
    return remember(p, th.isDark, opaque) {
        val base = if (p.dark) Color(0xFF232329) else Color.White
        // Transmission statique : le fond transparaît à ~12-18 %.
        val sheet = p.role == GlassRole.Sheet
        val body = base.copy(alpha = if (opaque) 1f else if (sheet) .97f else if (p.dark) 0.84f else 0.88f)
        val lift = base.copy(alpha = if (opaque) 1f else if (sheet) .98f else if (p.dark) 0.86f else 0.91f)
        val surface = Brush.linearGradient(listOf(lift, body, base.copy(alpha = if (opaque) 1f else if (sheet) .96f else if (p.dark) 0.80f else 0.84f), body))
        // Liseré capillaire : lumineux haut-gauche (entrée de lumière), sombre bas-droite.
        val lightHair = Color.White.copy(alpha = if (p.dark) 0.14f else 0.32f)
        val darkHair = if (p.dark) Color.Black.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.08f)
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
    styleOverride: Int? = null,
    content: @Composable () -> Unit
) {
    if (params.role == GlassRole.Inline) {
        Box(modifier.clip(shape)) { content() }
        return
    }
    val style = styleOverride ?: AppSettings.glassStyle.value
    val params = when (style) {
        1 -> params.copy(blurPx = maxOf(params.blurPx, 26f), tintAlpha = if (params.dark) .16f else .12f,
            refractionHeightDp = 3f, refractionAmountDp = 4f)
        else -> params
    }
    if (params.role == GlassRole.Panel) {
        // Glass belongs to controls and navigation, not every content card/menu.
        val theme = LocalTheme.current
        // A dark player menu can be opened above a light page. Its explicit
        // material, not the page underneath, must own the content contrast.
        val panelTheme = if (params.dark && !theme.isDark)
            com.ketvoice.app.theme.KetTheme.dark().copy(accent = theme.accent) else theme
        CompositionLocalProvider(LocalTheme provides panelTheme) {
            Box(modifier.clip(shape).background(panelTheme.card, shape)) { content() }
        }
        return
    }
    val theme = LocalTheme.current
    val glassTheme = if (params.dark && !theme.isDark)
        com.ketvoice.app.theme.KetTheme.dark().copy(accent = theme.accent) else theme
    CompositionLocalProvider(LocalTheme provides glassTheme) {
    val backdrop = LocalBackdropLayer.current
    val dynamic = backdrop != null && enabled && AppSettings.liquidGlass.value && !captured && style != 2 &&
        !AppSettings.reduceDynamicBlur.value &&
        !AppSettings.highPerformanceMode.value
    // Vrai verre AGSL (API 33+) : le matériau est calculé par un RuntimeShader
    // (réfraction + dispersion + teinte + liseré). Les pinceaux de surface et le
    // Highlight de la lib ne servent plus que de repli pré-33.
    val agsl = dynamic && GlassShader.isSupported()
    val renderState = remember { GlassRenderState() }

    val (surface, hairline) = rememberStaticMaterial(params, opaque = style == 2)
    val sheen = remember(params) { staticSheen(params) }
    // En AGSL, le shader dessine le liseré et le bord sombre : plus de bordure
    // Compose par-dessus (sinon double contour artificiel).
    val edge = if (enabled && !agsl) {
        Modifier.border(.5.dp, hairline, shape)
    } else Modifier

    if (dynamic) {
        Box(
            modifier = modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    // The shader owns saturation: stacking vibrancy here overcooks cover colours.
                    if (!agsl) vibrancy()
                    if (params.blurPx > 0f) blur(params.blurPx)
                    if (agsl) {
                        val bounds = size
                        val realRadii = glassRoundedShapeRadii(
                            shape, bounds.width, bounds.height, this, layoutDirection
                        ) ?: glassCornerRadiiOf(params.cornerRadiusPx)
                        renderState.shaderApplied = liquidGlass(params, realRadii)
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
                    if (!agsl || !renderState.shaderApplied) {
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
        // base translucide, liseré capillaire, voile spéculaire, ombre portée
        // douce (sinon le verre flotte sans profondeur). Pas d'échantillonnage,
        // donc aucun risque de boucle de rendu.
        val dropShadow = if (params.shadow) {
            Modifier.dropShadow(shape, elevation = params.elevationDp, alpha = params.shadowAlpha, dark = params.dark)
        } else Modifier
        Box(modifier = modifier.then(dropShadow).then(edge).clip(shape).background(surface, shape)) {
            Box(Modifier.background(sheen, shape)) { content() }
        }
    }
    }
}

private class GlassRenderState(var shaderApplied: Boolean = false)

/**
 * Soft, theme-aware drop shadow used by STATIC glass (glass disabled or captured).
 * The dynamic (drawBackdrop) path gets its shadow from the backdrop library; the
 * static path had none, which made disabled-glass panels read as painted rectangles.
 * The blur radius is intentionally wider than the offset so the shadow reads as a
 * diffuse lift rather than a hard stamp, and dark mode uses a slightly deeper
 * alpha so frosted panels still detach from a near-black page.
 */
private fun Modifier.dropShadow(shape: Shape, elevation: Float, alpha: Float, dark: Boolean): Modifier {
    val e = elevation.coerceIn(0f, 32f)
    if (e <= 0f) return this
    val a = (if (dark) alpha * 1.35f else alpha).coerceIn(0f, 0.5f)
    return this.shadow(
        elevation = e.dp,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = a * 0.6f),
        spotColor = Color.Black.copy(alpha = a)
    )
}
