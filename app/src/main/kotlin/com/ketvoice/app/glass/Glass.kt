package com.ketvoice.app.glass

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight

val LocalBackdropLayer = staticCompositionLocalOf<Backdrop?> { null }

@Composable
fun BackdropCapture(backdrop: LayerBackdrop, modifier: Modifier = Modifier,
                    content: @Composable BoxScope.() -> Unit) {
    Box(modifier.layerBackdrop(backdrop)) {
        CompositionLocalProvider(LocalBackdropLayer provides null) { content() }
    }
}

@Composable
fun rememberGlassBackdrop(background: Color) = rememberLayerBackdrop {
    drawRect(background)
    drawContent()
}

data class GlassParams(
    val cornerRadiusPx: Float,
    val bevelPx: Float,
    val refractionPx: Float,
    val falloff: Float = 2.0f,
    val dispersion: Float = 0.10f,
    val tintStrength: Float = 0.16f,
    val highlight: Float = 0.50f,
    val blurPx: Float = 24f,
    val grain: Float = 0.018f,
    val dark: Boolean = false,
    val materialRegular: Boolean = true
) {
    companion object {
        fun pill(blur: Float = 24f, dark: Boolean = false) =
            GlassParams(cornerRadiusPx = 9999f, bevelPx = 24f, refractionPx = 18f, blurPx = blur, dark = dark)

        fun circle(blur: Float = 24f, dark: Boolean = false) =
            GlassParams(cornerRadiusPx = 9999f, bevelPx = 18f, refractionPx = 18f, blurPx = blur, dark = dark)

        fun sheet(radiusPx: Float, dark: Boolean = false) =
            GlassParams(cornerRadiusPx = radiusPx, bevelPx = 30f, refractionPx = 24f, blurPx = 28f, tintStrength = 0.16f, dark = dark)
    }
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
    val surface = if (params.dark) {
        Color.Black.copy(alpha = 0.22f)
    } else {
        Color.White.copy(alpha = 0.25f)
    }
    val rim = remember(params.dark) { Brush.linearGradient(listOf(
        Color.White.copy(alpha = if (params.dark) .32f else .66f), Color.White.copy(alpha = .04f),
        Color.White.copy(alpha = .14f))) }
    val edge = if (enabled) Modifier.border(.7.dp, rim, shape) else Modifier

    if (backdrop != null && enabled && !captured && !AppSettings.reduceDynamicBlur.value &&
        !AppSettings.highPerformanceMode.value
    ) {
        Box(
            modifier = modifier.then(edge).drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    if (params.blurPx > 0f) blur(params.blurPx)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        lens(
                            refractionHeight = params.refractionPx.coerceIn(0f, 80f),
                            refractionAmount = (params.refractionPx * 1.55f).coerceIn(0f, 120f),
                            depthEffect = true,
                            chromaticAberration = true
                        )
                    }
                },
                highlight = { Highlight.Default },
                onDrawSurface = { drawRect(surface) }
            )
        ) {
            Box(Modifier.background(Color.Transparent, shape)) { content() }
        }
    } else {

        Box(modifier = modifier.then(edge).background(surface, shape)) {
            content()
        }
    }
}
