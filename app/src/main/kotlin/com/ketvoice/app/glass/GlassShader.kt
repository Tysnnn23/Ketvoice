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
private const val GLASS_AGSL = """
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
uniform float2 lightDir;

float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
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
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
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
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = min(radiusAt(coord, cornerRadii), min(halfSize.x, halfSize.y));

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (sd > 0.5) {
        return float4(0.0);
    }

    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius)
        + depthEffect * normalize(centeredCoord + float2(0.001, 0.001)));

    float4 color;
    if (-sd < refractionHeight && refractionAmount > 0.0) {
        // Profil de lentille smoothstep : le pli du bord monte continûment
        // depuis le coeur du panneau, sans marche visible à l'entrée de lentille.
        float x = 1.0 - -sd / refractionHeight;
        float d = smoothstep(0.0, 1.0, x) * refractionAmount;
        float2 refracted = coord + d * grad;
        // Dispersion chromatique : le rouge plie moins, le bleu plie plus.
        float2 rCoord = coord + d * (1.0 - dispersion) * grad;
        float2 bCoord = coord + d * (1.0 + dispersion) * grad;
        color = float4(
            sampleClamped(rCoord).r,
            sampleClamped(refracted).g,
            sampleClamped(bCoord).b,
            1.0);
    } else {
        color = sampleClamped(coord);
    }

    // Transmission : saturation et luminosité du fond à travers le verre.
    float luma = dot(color.rgb, float3(0.2126, 0.7152, 0.0722));
    color.rgb = mix(float3(luma), color.rgb, saturation) * brightness;

    // Teinte « lait » du verre (quasi imperceptible, sinon plastique).
    color.rgb = mix(color.rgb, tint.rgb, tint.a);

    // Épaisseur : lisière lumineuse du côté de la lumière, assombrie du côté
    // opposé, toutes deux concentrees sur ~2.5px, plus un contour capillaire
    // permanent qui donne sa definition au panneau sur fond clair comme sombre.
    float edge = clamp(1.0 + sd / 2.5, 0.0, 1.0);
    edge = edge * edge;
    float facing = dot(grad, lightDir);
    color.rgb += rimLight * edge * max(facing, 0.0);
    color.rgb -= edgeDark * edge * max(-facing, 0.0);
    float contour = clamp(1.0 + sd / 1.1, 0.0, 1.0);
    color.rgb += 0.028 * contour;

    // Voile spéculaire supérieur : la pellicule « mouillée » du verre.
    float fromTop = clamp((coord.y - offset.y) / max(size.y, 1.0), 0.0, 1.0);
    float topLit = clamp(-grad.y, 0.0, 1.0);
    color.rgb += sheen * pow(1.0 - fromTop, 2.4) * (0.35 + 0.65 * topLit);

    // Dithering sub-pixel : rend les dégradés du verre parfaitement lisses.
    color.rgb += dither(coord) / 255.0;

    return half4(color);
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
) {
    if (Build.VERSION.SDK_INT < 33) return
    // Un shader invalide ne doit JAMAIS tuer l'app : repli silencieux.
    val shader = runCatching { obtainRuntimeShader("kv-liquid-glass", GLASS_AGSL) }.getOrNull() ?: return
    runCatching {
        val w = size.width
        val h = size.height
        val pad = padding
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("offset", pad, pad)
        shader.setFloatUniform("contentSize", w + pad * 2f, h + pad * 2f)
        shader.setFloatUniform("cornerRadii", radii)
        shader.setFloatUniform("refractionHeight", params.refractionPx.coerceIn(0f, 90f))
        shader.setFloatUniform("refractionAmount", (params.refractionPx * 1.45f).coerceIn(0f, 110f))
        shader.setFloatUniform("dispersion", 0.38f)
        shader.setFloatUniform("depthEffect", 0.35f)
        if (params.dark) {
            shader.setFloatUniform("tint", 0.16f, 0.16f, 0.19f, params.tintAlpha)
        } else {
            shader.setFloatUniform("tint", 1f, 1f, 1f, params.tintAlpha)
        }
        shader.setFloatUniform("saturation", params.saturation)
        shader.setFloatUniform("brightness", params.brightness * (1f + params.exposure))
        shader.setFloatUniform("edgeDark", if (params.dark) 0.26f else 0.13f)
        shader.setFloatUniform("rimLight", params.highlightAlpha * 0.75f)
        shader.setFloatUniform("sheen", if (params.dark) 0.05f else 0.07f)
        shader.setFloatUniform("lightDir", -0.7071f, -0.7071f)
        effect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
    }
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
