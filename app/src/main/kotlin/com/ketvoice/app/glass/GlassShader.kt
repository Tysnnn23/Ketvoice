package com.ketvoice.app.glass

import android.graphics.RenderEffect
import android.os.Build
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.effect

/**
 * Vrai Liquid Glass en shader AGSL (Android Graphics Shading Language).
 *
 * Un unique RuntimeShader reçoit le contenu capturé derrière le verre
 * (`uniform shader content`, même contrat que la librairie backdrop :
 * `size`, `offset`, `cornerRadii`) et calcule TOUT le matériau par pixel :
 *
 *  - lentille de bord : réfraction par SDF de rectangle arrondi, profil
 *    smoothstep (pas de frontière dure), avec dispersion chromatique ;
 *  - teinte « lait » du verre + saturation et luminosité du fond ;
 *  - liseré de Fresnel côté lumière + contour capillaire permanent,
 *    ombre interne douce du côté opposé ;
 *  - voile spéculaire supérieur (sheen) ;
 *  - dithering fin : casse le banding des dégradés sur grands panneaux.
 *
 * Injecté via `effect(RenderEffect.createRuntimeShaderEffect(...))` dans la
 * chaîne du backdrop : le verre n'est plus un empilement de pinceaux blancs,
 * c'est un matériau calculé sur GPU.
 */
internal const val GLASS_AGSL = PRISMAL_OPTICS_AGSL + """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float2 contentSize;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float dispersion;
uniform float depthEffect;
uniform float4 tint;
uniform float saturation;
uniform float brightness;
uniform float edgeDark;
uniform float rimLight;
uniform float sheen;
uniform float materialDark;
uniform float2 lightDir;

float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.w;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.z;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        float2 corner = max(cornerCoord, 0.0);
        return sign(coord) * corner / max(length(corner), 0.001);
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

float4 sampleClamped(float2 p) {
    float2 lo = float2(0.5);
    float2 hi = contentSize - float2(0.5);
    return float4(content.eval(clamp(p, lo, hi)));
}

// Hash déterministe : dithering ±1 niveau pour supprimer le banding.
float dither(float2 coord) {
    float f = fract(sin(dot(coord, float2(12.9898, 78.233))) * 43758.5453);
    return f - 0.5;
}

float4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 local = coord + offset;
    float2 centeredCoord = local - halfSize;
    float radius = min(radiusAt(centeredCoord, cornerRadii), min(halfSize.x, halfSize.y));

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (sd > 0.5) {
        return float4(0.0);
    }

    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 radial = centeredCoord / max(length(centeredCoord), 0.001);
    float2 normal = gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * radial;
    float2 grad = normal / max(length(normal), 0.001);

    float4 color;
    if (-sd < refractionHeight && refractionHeight > 0.0) {
        // Profil de lentille smoothstep : le pli du bord monte continûment
        // depuis le coeur du panneau, sans marche visible à l'entrée de lentille.
        float x = 1.0 - -sd / refractionHeight;
        float d = prismalArcLift(x) * refractionAmount;
        float2 refracted = coord + d * grad;
        // Dispersion chromatique : le rouge plie moins, le bleu plie plus.
        float2 rCoord = coord + d * (1.0 - dispersion) * grad;
        float2 bCoord = coord + d * (1.0 + dispersion) * grad;
        color = float4(
            sampleClamped(rCoord).r,
            sampleClamped(refracted).g,
            sampleClamped(bCoord).b,
            sampleClamped(refracted).a);
    } else {
        color = sampleClamped(coord);
    }

    // Transmission : saturation et luminosité du fond à travers le verre.
    float luma = dot(color.rgb, float3(0.2126, 0.7152, 0.0722));
    color.rgb = mix(float3(luma), color.rgb, saturation) * brightness;

    // Teinte « lait » du verre (quasi imperceptible, sinon plastique).
    color.rgb = mix(color.rgb, tint.rgb, tint.a);
    // Regular glass must remain legible over artwork, not just over a blank page.
    // A local luminance-adaptive veil preserves the colours without letting black
    // labels disappear over a dark cover (or white labels over a bright cover).
    float veil = materialDark > 0.5
        ? 0.36 * smoothstep(0.18, 0.85, luma)
        : 0.30 * (1.0 - smoothstep(0.25, 0.80, luma));
    color.rgb = mix(color.rgb, materialDark > 0.5 ? float3(0.08) : float3(1.0), veil);

    // Épaisseur : lisière lumineuse du côté de la lumière, assombrie du côté
    // opposé, toutes deux concentrees sur ~2.5px, plus un contour capillaire
    // permanent qui donne sa definition au panneau sur fond clair comme sombre.
    float edge = clamp(1.0 + sd / 2.5, 0.0, 1.0);
    edge = edge * edge;
    float facing = dot(grad, lightDir);
    float specular = prismalSpecular(grad, lightDir, 3.2);
    color.rgb += rimLight * edge * specular * (facing > 0.0 ? 1.0 : 0.48);
    color.rgb -= edgeDark * edge * max(-facing, 0.0);
    float contour = clamp(1.0 + sd / 1.1, 0.0, 1.0);
    color.rgb += 0.028 * contour;

    // Voile spéculaire supérieur : la pellicule « mouillée » du verre.
    float fromTop = clamp(local.y / max(size.y, 1.0), 0.0, 1.0);
    float topLit = clamp(-grad.y, 0.0, 1.0);
    color.rgb += sheen * pow(1.0 - fromTop, 2.4) * (0.35 + 0.65 * topLit);

    // Dithering sub-pixel : rend les dégradés du verre parfaitement lisses.
    color.rgb += dither(coord) / 255.0;

    return half4(clamp(color, 0.0, 1.0));
}
"""

object GlassShader {

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= 33
}

/**
 * Applique le verre liquide AGSL à la chaîne d'effets du backdrop.
 * Appelé dans le bloc `effects { }` de drawBackdrop. `radii` = rayons
 * des coins réels de la forme (px) : [hautG, hautD, basG, basD].
 */
fun BackdropEffectScope.liquidGlass(
    params: GlassParams,
    radii: FloatArray
): Boolean {
    if (Build.VERSION.SDK_INT < 33) return false
    // Un shader invalide ne doit JAMAIS tuer l'app : repli silencieux.
    val shader = runCatching { obtainRuntimeShader("kv-prismal-glass", GLASS_AGSL) }.getOrNull() ?: return false
    return runCatching {
        val w = size.width
        val h = size.height
        val pad = padding
        val lens = ControlLens.forControl(minOf(w, h), density, params.refractionHeightDp, params.refractionAmountDp)
        shader.setFloatUniform("size", w, h)
        // Same capture coordinate contract as Backdrop's lens: padding is NEGATIVE.
        shader.setFloatUniform("offset", -pad, -pad)
        shader.setFloatUniform("contentSize", w + pad * 2f, h + pad * 2f)
        shader.setFloatUniform("cornerRadii", radii)
        shader.setFloatUniform("refractionHeight", lens.heightPx)
        shader.setFloatUniform("refractionAmount", lens.amountPx)
        shader.setFloatUniform("dispersion", 0.035f)
        shader.setFloatUniform("depthEffect", 0f)
        if (params.dark) {
            shader.setFloatUniform("tint", 0.16f, 0.16f, 0.19f, params.tintAlpha)
        } else {
            shader.setFloatUniform("tint", 1f, 1f, 1f, params.tintAlpha)
        }
        shader.setFloatUniform("saturation", params.saturation)
        shader.setFloatUniform("brightness", params.brightness * (1f + params.exposure))
        shader.setFloatUniform("edgeDark", if (params.dark) 0.09f else 0.055f)
        shader.setFloatUniform("rimLight", params.highlightAlpha * 0.85f)
        shader.setFloatUniform("sheen", if (params.dark) 0.035f else 0.045f)
        shader.setFloatUniform("materialDark", if (params.dark) 1f else 0f)
        shader.setFloatUniform("lightDir", -0.7071f, -0.7071f)
        effect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
        true
    }.getOrDefault(false)
}

/** Rayons réels de la forme (px). */
fun glassCornerRadiiOf(radiusPx: Float): FloatArray {
    val r = if (radiusPx >= 9999f) 10_000f else radiusPx.coerceAtLeast(0f)
    return floatArrayOf(r, r, r, r)
}

fun glassRoundedShapeRadii(
    shape: androidx.compose.ui.graphics.Shape,
    w: Float,
    h: Float,
    density: androidx.compose.ui.unit.Density,
    layoutDirection: androidx.compose.ui.unit.LayoutDirection
): FloatArray? {
    return runCatching {
        when (val outline = shape.createOutline(Size(w, h), layoutDirection, density)) {
            is Outline.Rounded -> floatArrayOf(
                outline.roundRect.topLeftCornerRadius.x,
                outline.roundRect.topRightCornerRadius.x,
                outline.roundRect.bottomLeftCornerRadius.x,
                outline.roundRect.bottomRightCornerRadius.x
            )
            else -> null
        }
    }.getOrNull()
}
