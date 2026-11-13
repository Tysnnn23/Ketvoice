package com.ketvoice.app.glass

import android.graphics.RenderEffect
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.effect
import com.ketvoice.app.data.AppSettings

/**
 * Liquid fusion: up to three glass controls rendered as ONE Liquid 27 body whose
 * outline is the smooth union of their shapes. While [GlassFusion.strengthPx] is 0
 * the controls are exactly separate; as it grows, controls that come close pull a
 * liquid bridge between them and merge like droplets, then split again.
 *
 * Each participating [GlassPanel] reports its bounds instead of drawing its own
 * glass; [GlassFusionLayer] draws the single pass behind them.
 */
internal const val MAX_FUSED_SHAPES = 3

internal const val LIQUID27_FUSED_SCENE = """
uniform float4 rect0;
uniform float4 rect1;
uniform float4 rect2;
uniform float3 radii;
uniform float3 present;
uniform float fuse;

// Polynomial smooth minimum: exact min when k is 0, a rounded bridge of reach k otherwise.
float smin(float a, float b, float k) {
    if (k <= 0.001) return min(a, b);
    float h = max(k - abs(a - b), 0.0) / k;
    return min(a, b) - h * h * k * 0.25;
}

float shapeSd(float2 local, float4 rect, float radius, float on) {
    if (on < 0.5) return 100000.0;
    return sdShape(local - rect.xy, rect.zw, min(radius, min(rect.z, rect.w)));
}

float sceneSd(float2 local) {
    float a = shapeSd(local, rect0, radii.x, present.x);
    float b = shapeSd(local, rect1, radii.y, present.y);
    float c = shapeSd(local, rect2, radii.z, present.z);
    return smin(smin(a, b, fuse), c, fuse);
}

// The nearest shape owns the pixel: magnification and backdrop taps use its box.
float4 sceneBox(float2 local) {
    float a = shapeSd(local, rect0, radii.x, present.x);
    float b = shapeSd(local, rect1, radii.y, present.y);
    float c = shapeSd(local, rect2, radii.z, present.z);
    if (a <= b && a <= c) return rect0;
    if (b <= c) return rect1;
    return rect2;
}
"""

internal const val LIQUID27_FUSED_AGSL = LIQUID27_COMMON + LIQUID27_FUSED_SCENE + LIQUID27_MATERIAL

/** One fused control: bounds in root pixels and its corner radius in pixels. */
internal data class FusedShape(val bounds: Rect, val radiusPx: Float)

class GlassFusion internal constructor() {
    internal val shapes = SnapshotStateMap<Any, FusedShape>()
    /** Reach of the liquid bridge in pixels. 0 keeps the controls separate. */
    var strengthPx by androidx.compose.runtime.mutableFloatStateOf(0f)
    /** False when this frame cannot draw the fused pass; panels then draw themselves. */
    internal var active by mutableStateOf(false)
    /** A device that rejected the fused shader once keeps its separate panels. */
    internal var failed = false

    internal fun report(key: Any, bounds: Rect, radiusPx: Float) {
        val next = FusedShape(bounds, radiusPx)
        if (shapes[key] != next) shapes[key] = next
    }

    internal fun forget(key: Any) { shapes.remove(key) }
}

@Composable
fun rememberGlassFusion(): GlassFusion = remember { GlassFusion() }

/** Set around controls that should fuse; the first [GlassPanel] on each branch consumes it. */
internal val LocalGlassFusion = staticCompositionLocalOf<GlassFusion?> { null }

/**
 * Bell-shaped bridge reach for a morph fraction in 0..1: nothing at rest on either
 * side, the full reach halfway through the transition.
 */
fun fusionStrength(fraction: Float, maxPx: Float): Float {
    val f = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    return kotlin.math.sin(Math.PI * f).toFloat().coerceIn(0f, 1f) * maxPx.coerceAtLeast(0f)
}

/** Shapes relative to the layer origin, largest first, at most [MAX_FUSED_SHAPES]. */
internal fun fusedShapesFor(shapes: Collection<FusedShape>, origin: Offset): List<FusedShape> =
    shapes.filter { it.bounds.width > 0f && it.bounds.height > 0f }
        .sortedByDescending { it.bounds.width * it.bounds.height }
        .take(MAX_FUSED_SHAPES)
        .map { it.copy(bounds = it.bounds.translate(-origin.x, -origin.y)) }

@androidx.annotation.RequiresApi(33)
internal fun applyLiquid27FusedUniforms(shader: android.graphics.RuntimeShader, w: Float, h: Float, pad: Float, density: Float,
                                        params: GlassParams, shapes: List<FusedShape>, fusePx: Float, frost: Float,
                                        light: Liquid27Light) {
    fun rect(index: Int): FloatArray {
        val shape = shapes.getOrNull(index) ?: return floatArrayOf(0f, 0f, 0f, 0f)
        return floatArrayOf(shape.bounds.center.x, shape.bounds.center.y, shape.bounds.width / 2f, shape.bounds.height / 2f)
    }
    shader.setFloatUniform("rect0", rect(0))
    shader.setFloatUniform("rect1", rect(1))
    shader.setFloatUniform("rect2", rect(2))
    shader.setFloatUniform("radii", shapes.getOrNull(0)?.radiusPx ?: 0f, shapes.getOrNull(1)?.radiusPx ?: 0f,
        shapes.getOrNull(2)?.radiusPx ?: 0f)
    shader.setFloatUniform("present", if (shapes.size > 0) 1f else 0f, if (shapes.size > 1) 1f else 0f,
        if (shapes.size > 2) 1f else 0f)
    shader.setFloatUniform("fuse", if (fusePx.isFinite()) fusePx.coerceAtLeast(0f) else 0f)
    // The lens band is bounded by the thinnest control so small buttons keep a quiet centre.
    val thinnest = shapes.minOfOrNull { minOf(it.bounds.width, it.bounds.height) } ?: 0f
    applyLiquid27Material(shader, w, h, thinnest, pad, density, params, frost, light)
}

internal fun BackdropEffectScope.liquid27Fused(params: GlassParams, shapes: List<FusedShape>, fusePx: Float, frost: Float,
                                               light: Liquid27Light): Boolean {
    if (Build.VERSION.SDK_INT < 33 || shapes.isEmpty()) return false
    val shader = runCatching { obtainRuntimeShader("kv-liquid-27-fused", LIQUID27_FUSED_AGSL) }.getOrNull() ?: return false
    return runCatching {
        applyLiquid27FusedUniforms(shader, size.width, size.height, padding, density, params, shapes, fusePx, frost, light)
        effect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
        true
    }.getOrDefault(false)
}

/**
 * Draws the fused body. Place it first inside the container whose children are
 * wrapped in `CompositionLocalProvider(LocalGlassFusion provides fusion)`. When the
 * optical Liquid 27 pass is not available it draws nothing and marks the fusion
 * inactive, so every control falls back to its own glass.
 */
@Composable
fun GlassFusionLayer(fusion: GlassFusion, dark: Boolean, modifier: Modifier = Modifier) {
    val backdrop = LocalBackdropLayer.current
    val mode = currentGlassRenderMode(AppSettings.glassStyle.value, enabled = true, captured = false)
    val available = backdrop != null && mode == GlassRenderMode.Optical && !fusion.failed &&
        AppSettings.glassStyle.value == GlassStyles.LIQUID_27
    fusion.active = available
    if (!available || backdrop == null) return
    var origin by remember { mutableStateOf(Offset.Zero) }
    val params = remember(dark) { GlassParams.navigation(dark = dark) }
    Box(modifier.onGloballyPositioned { origin = it.positionInRoot() }.drawBackdrop(
        backdrop = backdrop,
        shape = { RectangleShape },
        effects = {
            val frost = GlassTuning.sanitize(AppSettings.glassTint.value)
            blur(liquid27BlurPx(frost, GlassRole.Control))
            val shapes = fusedShapesFor(fusion.shapes.values, origin)
            // A failed pass must not leave an unshaded blurred rectangle: fall back to panels.
            if (shapes.isNotEmpty() && !liquid27Fused(params, shapes, fusion.strengthPx, frost, liquid27Light(0f, 0f, 0f))) {
                fusion.failed = true
                fusion.active = false
            }
        },
        highlight = { null },
        shadow = { null },
        innerShadow = { null }
    ))
}

/** Exposed for [GlassFusionLayer] and the chrome: the mode a control would render in right now. */
@Composable
internal fun currentGlassRenderMode(style: Int, enabled: Boolean, captured: Boolean): GlassRenderMode {
    val backdrop = LocalBackdropLayer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val lowMemory = remember(context) { isLowRamDevice(context) }
    return glassRenderMode(Build.VERSION.SDK_INT, backdrop != null, enabled && AppSettings.liquidGlass.value,
        captured, style == GlassStyles.OPAQUE, AppSettings.reduceDynamicBlur.value, AppSettings.highPerformanceMode.value,
        lowMemory, com.ketvoice.app.ui.motion.Motion.reduced)
}

internal fun isLowRamDevice(context: android.content.Context): Boolean = runCatching {
    (context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.isLowRamDevice == true
}.getOrDefault(false)
