package com.ketvoice.app.glass

import android.graphics.RenderEffect
import android.os.Build
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.effect
import com.ketvoice.app.theme.DesignTokens

/**
 * « Liquid 27 » material (glassStyle 3), modelled on the publicly described
 * 2026 Liquid Glass refinements: a continuous Clear ↔ Tinted control, more
 * uniform refraction, defined borders instead of drop shadows, a lighter dark
 * material and luminance-adaptive legibility. It is a separate shader so the
 * Clair / Dépoli / Opaque materials stay unchanged.
 *
 *  - continuous (superellipse) corners for cards and sheets; true capsules stay capsules;
 *  - a convex lens of constant strength all around the edge (numerical SDF normal,
 *    so corners do not refract harder than straight edges), slight centre magnification;
 *  - `frost` (0 = clear, 1 = tinted) drives milk and legibility veil; the blur
 *    itself is chosen on the Kotlin side before this pass;
 *  - backdrop luminance measured from five taps adapts the material to the content;
 *  - crisp two-sided rim (lit edge + bounce on the opposite edge), no thickness shadow;
 *  - light direction follows device tilt (uniform), touch adds a local glow and lens pressure.
 */
internal const val LIQUID27_AGSL = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float2 contentSize;
uniform float4 cornerRadii;
uniform float lensHeight;
uniform float lensAmount;
uniform float dispersion;
uniform float frost;
uniform float4 milk;
uniform float saturation;
uniform float materialDark;
uniform float2 lightDir;
uniform float rimLight;
uniform float pixelScale;
uniform float3 touch;

float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.w;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.z;
    }
}

// Continuous corner: a superellipse (n = 2.6) whose radius is widened so the curve
// starts earlier along the edge but never leaves the circular clip of the shape.
float sdShape(float2 p, float2 halfSize, float radius) {
    float limit = min(halfSize.x, halfSize.y);
    bool capsule = radius >= limit * 0.98;
    float r = capsule ? limit : min(radius * 1.3, limit);
    float2 q = abs(p) - (halfSize - float2(r));
    if (q.x > 0.0 && q.y > 0.0) {
        if (capsule) return length(q) - r;
        float n = 2.6;
        float2 a = q / r;
        return (pow(pow(a.x, n) + pow(a.y, n), 1.0 / n) - 1.0) * r;
    }
    return max(q.x, q.y) - r;
}

float4 sampleClamped(float2 p) {
    return float4(content.eval(clamp(p, float2(0.5), contentSize - float2(0.5))));
}

float lumaOf(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

float dither(float2 coord) {
    return fract(sin(dot(coord, float2(12.9898, 78.233))) * 43758.5453) - 0.5;
}

float4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 local = coord + offset;
    float2 p = local - halfSize;
    float radius = min(radiusAt(p, cornerRadii), min(halfSize.x, halfSize.y));
    float sd = sdShape(p, halfSize, radius);
    if (sd > 0.5) return float4(0.0);

    // Numerical normal: identical refraction strength on edges and corners.
    float e = 1.0;
    float2 g = float2(sdShape(p + float2(e, 0.0), halfSize, radius) - sdShape(p - float2(e, 0.0), halfSize, radius),
                      sdShape(p + float2(0.0, e), halfSize, radius) - sdShape(p - float2(0.0, e), halfSize, radius));
    float2 n = g / max(length(g), 0.0001);

    float px = max(pixelScale, 1.0);
    float press = clamp(touch.z, 0.0, 1.0);
    float depth = -sd;

    // Slight magnification of the whole body (the glass is a thick convex slab).
    float minSide = max(min(size.x, size.y), 1.0);
    float magnify = 0.012 + press * 0.01;
    float2 body = coord - p * magnify;

    float4 color;
    if (depth < lensHeight && lensHeight > 0.0) {
        // Circular height map: bend rises smoothly to the rim, constant all around.
        float x = 1.0 - depth / lensHeight;
        float bend = (1.0 - sqrt(max(1.0 - x * x, 0.0))) * lensAmount * (1.0 + press * 0.22);
        float2 r = body + n * bend * (1.0 - dispersion);
        float2 gC = body + n * bend;
        float2 b = body + n * bend * (1.0 + dispersion);
        float4 green = sampleClamped(gC);
        color = float4(sampleClamped(r).r, green.g, sampleClamped(b).b, green.a);
    } else {
        color = sampleClamped(body);
    }

    // Saturation of the transmitted content.
    float l = lumaOf(color.rgb);
    color.rgb = mix(float3(l), color.rgb, saturation);

    // Backdrop luminance under the whole control (five taps, not per-pixel).
    float2 c0 = coord - p;
    float2 q = halfSize * 0.5;
    float bg = (lumaOf(sampleClamped(c0).rgb) + lumaOf(sampleClamped(c0 + q).rgb) +
                lumaOf(sampleClamped(c0 - q).rgb) + lumaOf(sampleClamped(c0 + float2(q.x, -q.y)).rgb) +
                lumaOf(sampleClamped(c0 + float2(-q.x, q.y)).rgb)) * 0.2;

    // Clear ↔ Tinted: milk opacity grows with frost; a bright backdrop needs less
    // milk in light mode, a dark backdrop needs less in dark mode.
    float adapt = materialDark > 0.5 ? (0.75 + bg * 0.5) : (1.25 - bg * 0.5);
    float milkAmount = clamp(mix(milk.a, 0.42, frost) * adapt, 0.0, 0.6);
    color.rgb = mix(color.rgb, milk.rgb, milkAmount);

    // Legibility veil: only as much light as glyphs need (lighter dark material).
    float lum = lumaOf(color.rgb);
    if (materialDark > 0.5) {
        float target = mix(0.30, 0.22, frost);
        if (lum > target) color.rgb *= mix(1.0, target / lum, 0.85);
    } else {
        float target = mix(0.46, 0.74, frost);
        if (lum < target) color.rgb = mix(color.rgb, float3(1.0), (target - lum) / max(1.0 - lum, 0.001) * 0.85);
    }

    // Defined border: thin lit rim facing the light, softer bounce on the opposite
    // edge, plus a faint permanent contour. No inner thickness shadow.
    float rim = clamp(1.0 + sd / (1.0 * px), 0.0, 1.0);
    float facing = dot(n, lightDir);
    float lit = pow(max(facing, 0.0), 1.6);
    float bounce = pow(max(-facing, 0.0), 2.2);
    color.rgb += rimLight * rim * (lit + bounce * 0.55);
    float contour = clamp(1.0 + sd / (0.6 * px), 0.0, 1.0);
    color.rgb = mix(color.rgb, materialDark > 0.5 ? float3(1.0) : float3(0.0), contour * 0.07);
    // Inner glow just inside the lit rim: the glass edge catching light.
    float inner = smoothstep(0.0, 3.0 * px, depth) * (1.0 - smoothstep(3.0 * px, 9.0 * px, depth));
    color.rgb += rimLight * 0.16 * inner * max(facing, 0.0);

    // Touch: light spreads from the finger through the material.
    if (press > 0.001) {
        float2 d = local - touch.xy;
        float sigma = minSide * 0.55;
        float glow = exp(-dot(d, d) / (2.0 * sigma * sigma)) * press;
        color.rgb += (materialDark > 0.5 ? 0.12 : 0.16) * glow;
    }

    color.rgb += dither(coord) / 255.0;
    return half4(clamp(color, 0.0, 1.0));
}
"""

/** Uniform values for one Liquid 27 pass. */
internal data class Liquid27Light(val lightX: Float, val lightY: Float, val touchX: Float, val touchY: Float, val press: Float)

/** Blur applied before the Liquid 27 pass: almost none when clear, frosted when tinted. */
internal fun liquid27BlurPx(frost: Float, role: GlassRole): Float {
    val f = GlassTuning.sanitize(frost)
    val base = if (role == GlassRole.Sheet) 10f else 1.5f
    val tinted = if (role == GlassRole.Sheet) 34f else 16f
    return base + (tinted - base) * f
}

internal fun BackdropEffectScope.liquid27Glass(params: GlassParams, radii: FloatArray, frost: Float, light: Liquid27Light): Boolean {
    if (Build.VERSION.SDK_INT < 33) return false
    val shader = runCatching { obtainRuntimeShader("kv-liquid-27", LIQUID27_AGSL) }.getOrNull() ?: return false
    return runCatching {
        applyLiquid27Uniforms(shader, size.width, size.height, padding, density, params, radii, frost, light)
        effect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
        true
    }.getOrDefault(false)
}

/** Every Liquid 27 uniform, shared by the backdrop effect and the host render test. */
@androidx.annotation.RequiresApi(33)
internal fun applyLiquid27Uniforms(shader: android.graphics.RuntimeShader, w: Float, h: Float, pad: Float, density: Float,
                                   params: GlassParams, radii: FloatArray, frost: Float, light: Liquid27Light) {
    // Uniform refraction: a wider band than the Prismal controls, still bounded by the control.
    val lens = ControlLens.forControl(minOf(w, h), density, params.refractionHeightDp * 2.2f, params.refractionAmountDp * 1.6f)
    shader.setFloatUniform("size", w, h)
    // Same capture coordinate contract as the Prismal pass: padding is negative.
    shader.setFloatUniform("offset", -pad, -pad)
    shader.setFloatUniform("contentSize", w + pad * 2f, h + pad * 2f)
    shader.setFloatUniform("cornerRadii", radii)
    shader.setFloatUniform("lensHeight", lens.heightPx)
    shader.setFloatUniform("lensAmount", lens.amountPx)
    shader.setFloatUniform("dispersion", DesignTokens.Glass.DISPERSION * 1.5f)
    shader.setFloatUniform("frost", GlassTuning.sanitize(frost))
    // Lighter dark material than the other styles.
    if (params.dark) shader.setFloatUniform("milk", .24f, .24f, .27f, .10f)
    else shader.setFloatUniform("milk", 1f, 1f, 1f, .06f)
    shader.setFloatUniform("saturation", if (params.dark) 1.18f else 1.24f)
    shader.setFloatUniform("materialDark", if (params.dark) 1f else 0f)
    shader.setFloatUniform("lightDir", light.lightX, light.lightY)
    shader.setFloatUniform("rimLight", if (params.dark) .34f else .46f)
    shader.setFloatUniform("pixelScale", density)
    shader.setFloatUniform("touch", light.touchX, light.touchY, light.press.let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f })
}
