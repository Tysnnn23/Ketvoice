package com.ketvoice.desktop.ui.glass

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * Liquid glass (Windows 2.0): a Skia runtime shader run as an image filter over what lies behind a surface. The
 * surface is a rounded rectangle whose rim curves down like the edge of a lens: light passing near the edge is
 * bent inward (refraction growing on a circular profile), the three colours are bent by slightly different amounts
 * (chromatic aberration), a thin specular line runs along the rim where it faces the light, and the middle is
 * frosted by a blur fed to the shader as its input. A lens ([Lens]) can magnify a disc of it, like a drop.
 *
 * The shader is compiled once ([compiled]); if Skia refuses it, [ok] is false and [GlassPolicy] falls back to the
 * plain blur. All lengths are in pixels.
 */
internal object GlassShader {
    /** The shader's source (SkSL). `content` is the (blurred) backdrop under the surface. */
    const val SKSL = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float refraction;
uniform float bevel;
uniform float dispersion;
uniform float specular;
uniform float2 light;
uniform float3 lens;
uniform float lensRadius;
uniform float rimWidth;

float roundBox(float2 p, float2 h, float r) {
    float2 q = abs(p) - h + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

half4 main(float2 coord) {
    float2 h = size * 0.5;
    float2 p = coord - h;
    float r = min(radius, min(h.x, h.y));
    float d = roundBox(p, h, r);
    float2 e = float2(0.75, 0.0);
    float2 n = float2(roundBox(p + e.xy, h, r) - roundBox(p - e.xy, h, r), roundBox(p + e.yx, h, r) - roundBox(p - e.yx, h, r));
    float nl = length(n);
    n = nl > 0.0001 ? n / nl : float2(0.0);
    float t = clamp(1.0 + d / max(bevel, 1.0), 0.0, 1.0);
    float bend = refraction * (1.0 - sqrt(max(1.0 - t * t, 0.0)));
    float2 shift = -n * bend;
    float2 src = coord + shift;
    if (lens.z > 1.0001) {
        float2 lp = coord - lens.xy;
        float w = lensRadius > 0.0 ? 1.0 - smoothstep(lensRadius * 0.55, lensRadius, length(lp)) : 1.0;
        src -= lp * (1.0 - 1.0 / lens.z) * w;
    }
    float2 split = shift * dispersion;
    half4 g = content.eval(src);
    half red = content.eval(src + split).r;
    half blue = content.eval(src + split * 2.0).b;
    half4 col = half4(red, g.g, blue, g.a);
    float inside = max(-d, 0.0);
    float rim = 1.0 - smoothstep(0.0, rimWidth, inside);
    float glow = 1.0 - smoothstep(0.0, bevel * 0.7, inside);
    float facing = dot(n, light);
    float spec = specular * (rim * (0.28 + 0.72 * max(facing, 0.0) + 0.3 * max(-facing, 0.0)) + glow * 0.10 * max(facing, 0.0));
    col.rgb = min(col.rgb + half3(spec), half3(1.0));
    col.a = max(col.a, half(min(spec, 1.0)));
    return col;
}
"""

    private val effect: Result<RuntimeEffect> by lazy { runCatching { RuntimeEffect.makeForShader(SKSL) } }

    /** True when Skia compiled the shader; the policy falls back to the plain blur otherwise. */
    val ok: Boolean get() = effect.isSuccess

    /** Why it failed, for the log. */
    val failure: Throwable? get() = effect.exceptionOrNull()

    /** The uniforms of one surface. */
    data class Params(
        val size: Size,
        val radius: Float,
        val refraction: Float,
        val bevel: Float,
        val frost: Float,
        val dispersion: Float = .35f,
        val specular: Float = .55f,
        val light: Offset = Offset(-.6f, -.8f),
        val lens: Offset = Offset.Unspecified,
        val zoom: Float = 1f,
        val lensRadius: Float = 0f,
        /** The specular line along the rim, in pixels. */
        val rimWidth: Float = 2f,
    )

    /** The last effects made, by their uniforms: a still surface reuses its own; a moving one makes a few. */
    private val cache = object : LinkedHashMap<Params, RenderEffect>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Params, RenderEffect>?) = size > 48
    }

    /** The surface's render effect, or null when the shader is not available. Re-made only when [p] changes. */
    fun effect(p: Params): RenderEffect? {
        val shader = effect.getOrNull() ?: return null
        cache[p]?.let { return it }
        val made = runCatching { build(shader, p) }.getOrNull() ?: return null
        cache[p] = made
        return made
    }

    private fun build(shader: RuntimeEffect, p: Params): RenderEffect {
        val b = RuntimeShaderBuilder(shader)
        b.uniform("size", p.size.width, p.size.height)
        b.uniform("radius", p.radius)
        b.uniform("refraction", p.refraction)
        b.uniform("bevel", p.bevel)
        b.uniform("dispersion", p.dispersion)
        b.uniform("specular", p.specular)
        b.uniform("light", p.light.x, p.light.y)
        val lens = if (p.lens.isSpecified && p.zoom > 1f) p.lens else Offset.Zero
        b.uniform("lens", lens.x, lens.y, if (p.lens.isSpecified) p.zoom else 1f)
        b.uniform("lensRadius", p.lensRadius)
        b.uniform("rimWidth", p.rimWidth)
        val frost = if (p.frost > 0f) ImageFilter.makeBlur(p.frost, p.frost, FilterTileMode.CLAMP, null, null) else null
        return ImageFilter.makeRuntimeShader(b, "content", frost).asComposeRenderEffect()
    }
}
