package com.ketvoice.app.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * 7.6 (owner: « un scale intelligent sur les Canvas … qu'on voie bien le Canvas sans que ça cache et que ça ne rende
 * pas trop grand, mais le Canvas est complet »): how a Canvas takes its place. A Canvas whose shape is close to the
 * place it gets fills it (at most [FILL_MAX_HIDDEN] of it cut off, as Apple Music does); any other is shown whole, as
 * large as the place allows, and the rest of the place is a soft extension of the video's own colours.
 */
internal object CanvasFit {
    const val FILL_MAX_HIDDEN = .12f
    /** Width over height of a Canvas whose shape is not known yet. */
    const val UPRIGHT = 9f / 16f

    /** Where the video goes in a [boxWidth] × [boxHeight] place: filling it, or whole at [width] × [height]. */
    data class Placement(val fill: Boolean, val width: Float, val height: Float)

    fun place(boxWidth: Float, boxHeight: Float, aspect: Float): Placement {
        if (boxWidth <= 0f || boxHeight <= 0f || !aspect.isFinite() || aspect <= 0f) return Placement(true, boxWidth, boxHeight)
        val box = boxWidth / boxHeight
        val hidden = 1f - minOf(box / aspect, aspect / box)
        if (hidden <= FILL_MAX_HIDDEN) return Placement(true, boxWidth, boxHeight)
        return if (aspect < box) Placement(false, boxHeight * aspect, boxHeight) else Placement(false, boxWidth, boxWidth / aspect)
    }

    /**
     * The extension: the still shrunk to a few pixels (its shape kept). Drawn over the video's place with its edges
     * clamped, each edge's colours run on smoothly to the border of the box, and the corners take the corners'.
     */
    fun extension(still: ImageBitmap): ImageBitmap? = runCatching {
        val wide = still.width >= still.height
        android.graphics.Bitmap.createScaledBitmap(still.asAndroidBitmap(), if (wide) 10 else 6, if (wide) 6 else 10, true).asImageBitmap()
    }.getOrNull()
}

/**
 * A Canvas placed by [CanvasFit] inside [safe] (what the buttons and words around it cover): its still shows at once,
 * the video starts over it, and when it does not fill, its edges melt into the extension (never an empty band:
 * before the still exists, [ambient], the cover's colour, is the extension).
 */
@Composable
internal fun FittedCanvas(path: String, playing: Boolean, ambient: Color, modifier: Modifier = Modifier,
                          safe: PaddingValues = PaddingValues(0.dp), onFailure: () -> Unit = {},
                          onAspect: ((Float) -> Unit)? = null) {
    val poster = com.ketvoice.app.ui.sub.rememberCanvasPoster(path)
    var aspect by remember(path) { mutableFloatStateOf(0f) }
    val shape = if (aspect > 0f) aspect else poster?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: CanvasFit.UPRIGHT
    val extension = remember(poster) { poster?.let(CanvasFit::extension) }
    val direction = LocalLayoutDirection.current
    BoxWithConstraints(modifier.clipToBounds().testTag("fitted-canvas")) {
        val areaW = maxWidth - safe.calculateStartPadding(direction) - safe.calculateEndPadding(direction)
        val areaH = maxHeight - safe.calculateTopPadding() - safe.calculateBottomPadding()
        val place = CanvasFit.place(areaW.value, areaH.value, shape)
        val density = LocalDensity.current
        val video = with(density) {
            val w = if (place.fill) areaW.toPx() else place.width.dp.toPx()
            val h = if (place.fill) areaH.toPx() else place.height.dp.toPx()
            Rect(Offset(safe.calculateStartPadding(direction).toPx() + (areaW.toPx() - w) / 2f,
                safe.calculateTopPadding().toPx() + (areaH.toPx() - h) / 2f), Size(w, h))
        }
        Box(Modifier.fillMaxSize().background(ambient).drawBehind {
            if (extension == null) return@drawBehind
            val shader = ImageShader(extension, TileMode.Clamp, TileMode.Clamp)
            shader.setLocalMatrix(android.graphics.Matrix().apply {
                setScale(video.width / extension.width, video.height / extension.height); postTranslate(video.left, video.top) })
            drawRect(ShaderBrush(shader))
            // A light shade: the extension stays behind the video, which reads as the picture.
            drawRect(Color.Black.copy(alpha = .06f))
        })
        Box(Modifier.fillMaxSize().padding(safe), contentAlignment = Alignment.Center) {
            val sides = place.width < areaW.value - 1f
            val ends = place.height < areaH.value - 1f
            Box((if (place.fill) Modifier.fillMaxSize() else Modifier.size(place.width.dp, place.height.dp))
                .testTag(if (place.fill) "canvas-fill" else "canvas-whole")
                .then(if (place.fill || (!sides && !ends)) Modifier.clipToBounds() else Modifier
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        // The video's edges melt into the extension over a few percent of its size.
                        val melt = listOf(0f to Color.Transparent, .10f to Color.White, .90f to Color.White, 1f to Color.Transparent)
                        if (sides) drawRect(Brush.horizontalGradient(*melt.toTypedArray()), blendMode = BlendMode.DstIn)
                        if (ends) drawRect(Brush.verticalGradient(*melt.toTypedArray()), blendMode = BlendMode.DstIn)
                    })) {
                if (poster != null) Image(poster, null, Modifier.fillMaxSize().testTag("canvas-poster"), contentScale = ContentScale.Crop)
                CanvasVideo(path, playing, cover = null, modifier = Modifier.fillMaxSize(),
                    resizeMode = CanvasPresentation.NORMAL_RESIZE_MODE, onFailure = onFailure,
                    onAspect = { value -> if (value.isFinite() && value > 0f) aspect = value; onAspect?.invoke(value) })
            }
        }
    }
}
