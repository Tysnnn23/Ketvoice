package com.ketvoice.app.ui.player

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ketvoice.app.BuildConfig
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.player.SonicMeter
import com.ketvoice.app.ui.motion.Motion

/**
 * Animated mesh gradient behind the player: four soft colour fields taken from the
 * cover drift slowly and breathe with the low end of the music. It gives the glass
 * controls something alive to refract when there is no Canvas.
 *
 * Inverse-distance weighting keeps every pixel a smooth blend of the four colours,
 * so there is never a hard edge or a visible blob outline.
 */
internal const val MESH_AGSL = """
uniform float2 size;
uniform float time;
uniform float energy;
uniform float3 c0;
uniform float3 c1;
uniform float3 c2;
uniform float3 c3;

float field(float2 uv, float2 centre) {
    float2 d = uv - centre;
    return 1.0 / (dot(d, d) + 0.035);
}

half4 main(float2 coord) {
    float2 uv = coord / max(size, float2(1.0));
    float t = time;
    float reach = 1.0 + energy * 0.35;
    float2 p0 = float2(0.22 + 0.20 * sin(t * 0.21), 0.18 + 0.16 * cos(t * 0.17));
    float2 p1 = float2(0.80 + 0.18 * cos(t * 0.19 + 1.3), 0.30 + 0.20 * sin(t * 0.23 + 0.4));
    float2 p2 = float2(0.30 + 0.22 * cos(t * 0.15 + 2.1), 0.78 + 0.16 * sin(t * 0.18 + 1.7));
    float2 p3 = float2(0.76 + 0.20 * sin(t * 0.17 + 0.9), 0.84 + 0.14 * cos(t * 0.20 + 2.6));
    float w0 = field(uv, p0) * reach;
    float w1 = field(uv, p1);
    float w2 = field(uv, p2);
    float w3 = field(uv, p3) * reach;
    float3 colour = (c0 * w0 + c1 * w1 + c2 * w2 + c3 * w3) / (w0 + w1 + w2 + w3);
    colour *= 1.0 + energy * 0.10;
    float n = fract(sin(dot(coord, float2(12.9898, 78.233))) * 43758.5453) - 0.5;
    return half4(clamp(colour + n / 255.0, 0.0, 1.0), 1.0);
}
"""

/**
 * Four mesh colours from what the player already knows about the cover: its darkened
 * dominant colour, its readable accent and, when extracted, its vivid pair. Every
 * colour is kept dark enough for white player text to stay readable above it.
 */
internal fun meshColors(dominant: Color, accent: Color?, vivid: Pair<Color, Color>?): List<Color> {
    fun deep(color: Color, keep: Float) = Color(color.red * keep, color.green * keep, color.blue * keep)
    val second = vivid?.first ?: accent ?: dominant
    val third = vivid?.second ?: lerp(dominant, accent ?: dominant, .5f)
    return listOf(deep(dominant, 1f), deep(second, .52f), deep(third, .44f), deep(lerp(dominant, Color.Black, .55f), 1f))
}

/** Whether the mesh may draw, and whether its clock may run. */
internal data class MeshPolicy(val draw: Boolean, val animate: Boolean)

internal fun meshPolicy(api: Int, enabled: Boolean, highPerformance: Boolean, reducedMotion: Boolean,
                        playing: Boolean, quest: Boolean): MeshPolicy {
    val draw = api >= 33 && enabled && !highPerformance
    return MeshPolicy(draw, draw && playing && !reducedMotion && !quest)
}

@Composable
internal fun MeshBackdrop(colors: List<Color>, playing: Boolean, modifier: Modifier = Modifier, alpha: Float = .62f) {
    val policy = meshPolicy(Build.VERSION.SDK_INT, AppSettings.meshBackground.value, AppSettings.highPerformanceMode.value,
        Motion.reduced, playing, BuildConfig.IS_QUEST)
    if (!policy.draw || Build.VERSION.SDK_INT < 33 || colors.size < 4) return
    val shader = remember { runCatching { RuntimeShader(MESH_AGSL) }.getOrNull() } ?: return
    val brush = remember(shader) { ShaderBrush(shader) }
    val time = remember { mutableFloatStateOf(0f) }
    val energy = remember { mutableFloatStateOf(0f) }

    // The audio meter only runs while this backdrop is visible, playing and allowed to move.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, policy.animate) {
        fun update() { SonicMeter.observe(policy.animate && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer); update()
        onDispose { lifecycle.removeObserver(observer); SonicMeter.observe(false) }
    }
    val levels by SonicMeter.levels.collectAsState()
    LaunchedEffect(policy.animate) {
        if (!policy.animate) { energy.floatValue = 0f; return@LaunchedEffect }
        var last = 0L
        var frame = 0
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    // Half-rate updates: the fields move slowly, and the glass above re-samples on each change.
                    frame++
                    if (frame % 2 == 0) {
                        time.floatValue += ((now - last) / 1_000_000_000f).coerceIn(0f, .1f) * 2f
                        energy.floatValue += (levels.low.coerceIn(0f, 1f) - energy.floatValue) * .18f
                        SonicMeter.expire()
                    }
                }
                last = now
            }
        }
    }
    Canvas(modifier.fillMaxSize()) {
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("time", time.floatValue)
        shader.setFloatUniform("energy", energy.floatValue)
        colors.take(4).forEachIndexed { index, color ->
            shader.setFloatUniform("c$index", color.red, color.green, color.blue)
        }
        drawRect(brush, alpha = alpha)
    }
}
